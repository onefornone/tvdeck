package com.tvdeck.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class Frame(val bitmap: Bitmap, val fullW: Int, val fullH: Int)

/** How button presses reach the TV. SENDEVENT and CMD are instant; INPUT starts a Java process every time (slow). */
enum class Inject { SENDEVENT, CMD, INPUT }

private class InputPlan(val device: String, val keys: Set<Int>)

// Android key code -> Linux key code (used by the fast path)
private val LINUX_KEY = mapOf(
    19 to 103, 20 to 108, 21 to 105, 22 to 106,
    4 to 158, 3 to 102, 82 to 139,
    24 to 115, 25 to 114, 164 to 113, 26 to 116,
    85 to 164, 87 to 163, 88 to 165, 89 to 168, 90 to 208,
    66 to 28, 67 to 14
)

/** Returns the first line of a shell output that looks like an error, or null. */
fun errorLine(out: String): String? {
    val bad = listOf(
        "permission denied", "exception", "error", "not found", "can't find", "failure",
        "unknown command", "inaccessible", "no such file", "aborted"
    )
    return out.lines().map { it.trim() }.firstOrNull { l ->
        l.isNotEmpty() && bad.any { l.lowercase().contains(it) }
    }
}

/** Everything that talks to the TV over ADB (Wi-Fi). */
object AdbManager {
    private var dadb: Dadb? = null
    private var appCtx: Context? = null
    private var lastHost: String? = null
    private var lastPort = 5555

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isConnected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var deviceName by mutableStateOf("")
    var deviceHost by mutableStateOf("")
    var androidVersion by mutableStateOf("")
    var sdkLevel by mutableStateOf(0)
    var recording by mutableStateOf(false)
    var tvWidth = 1920
    var tvHeight = 1080

    // device (system) volume, read from the TV
    var volume by mutableStateOf(-1)
    var volumeMin by mutableStateOf(0)
    var volumeMax by mutableStateOf(15)
    private var volReadAt = 0L
    private var volSetOk = true
    private var volVerified = false

    // input settings (shown in the Remote tab)
    var inject by mutableStateOf(Inject.INPUT)
    var injectPref by mutableStateOf("auto")      // auto | compatible
    var volumeMode by mutableStateOf("device")    // device | tv | both
    @Volatile var lastKeyTime = 0L
    private var plan: InputPlan? = null
    private var cmdInputOk = false
    private var volStyle = 2                      // 0 = cmd media_session, 1 = media volume, 2 = key event

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        injectPref = Store.getPref("inject", "auto")
        volumeMode = Store.getPref("vol", "device")
    }

    val context: Context get() = appCtx ?: throw IllegalStateException("App not ready")

    private fun keyPair(): AdbKeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun conn(): Dadb = dadb ?: throw IllegalStateException("Not connected to a TV. Open the Devices tab first.")

    // ───────────── connection ─────────────
    suspend fun connect(host: String, port: Int): String = withContext(Dispatchers.IO) {
        connecting = true
        try {
            try { dadb?.close() } catch (_: Exception) {}
            dadb = null
            isConnected = false
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 4000) }
            } catch (e: Exception) {
                throw IllegalStateException("Can't reach $host:$port. Check the IP address, that the TV is on, and that debugging is enabled.")
            }
            val c = Dadb.create(host, port, keyPair())
            val lines = c.shell(
                "echo \$(getprop ro.product.manufacturer); echo \$(getprop ro.product.model); " +
                    "echo \$(getprop ro.build.version.release); echo \$(getprop ro.build.version.sdk); wm size"
            ).allOutput.lines().map { it.trim() }
            val brand = lines.getOrElse(0) { "" }
            val model = lines.getOrElse(1) { "" }
            androidVersion = lines.getOrElse(2) { "" }
            sdkLevel = lines.getOrElse(3) { "" }.toIntOrNull() ?: 0
            val m = Regex("(\\d+)x(\\d+)").findAll(lines.drop(4).joinToString(" ")).lastOrNull()
            if (m != null) {
                tvWidth = m.groupValues[1].toIntOrNull() ?: 1920
                tvHeight = m.groupValues[2].toIntOrNull() ?: 1080
            }
            dadb = c
            lastHost = host
            lastPort = port
            val nice = (brand.replaceFirstChar { it.uppercase() } + " " + model).trim()
            deviceName = if (nice.isBlank()) host else nice
            deviceHost = host
            AppsCache.loaded = false
            AppsCache.apps = emptyList()
            try { probeInput() } catch (_: Exception) { inject = Inject.INPUT }
            isConnected = true
            deviceName
        } finally {
            connecting = false
        }
    }

    fun disconnect() {
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
        isConnected = false
        deviceName = ""
    }

    // ───────────── shell ─────────────
    private fun shellBlocking(cmd: String): String {
        val d = dadb ?: throw IllegalStateException("Not connected to a TV. Open the Devices tab first.")
        return try {
            d.shell(cmd).allOutput
        } catch (e: Exception) {
            // The link may have dropped (TV slept, Wi-Fi changed). Try to reconnect once.
            val h = lastHost ?: throw e
            try { d.close() } catch (_: Exception) {}
            try {
                val fresh = Dadb.create(h, lastPort, keyPair())
                dadb = fresh
                fresh.shell(cmd).allOutput
            } catch (e2: Exception) {
                isConnected = false
                throw e2
            }
        }
    }

    suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) { shellBlocking(cmd) }

    /** Fire-and-forget command. Shows a message when it finishes (or fails). */
    fun runBg(cmd: String, ok: String = "") {
        appScope.launch {
            try {
                val out = shell(cmd)
                val err = errorLine(out)
                if (err != null) Shared.say("Failed: " + err.take(120))
                else if (ok.isNotEmpty()) Shared.say(ok)
            } catch (e: Exception) {
                Shared.say(e.message ?: "Command failed")
            }
        }
    }

    fun openSettings() = runBg("am start -a android.settings.SETTINGS", "")

    /** Opens an app on the TV using its real launcher activity. */
    fun launchApp(pkg: String) {
        appScope.launch {
            try {
                var comp = ""
                for (cat in listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")) {
                    val o = shell("cmd package resolve-activity --brief -c $cat $pkg 2>&1")
                    val line = o.lines().map { it.trim() }.lastOrNull { it.contains("/") && !it.contains(" ") }
                    if (line != null) { comp = line; break }
                }
                if (comp.isNotEmpty()) {
                    val r = shell("am start -n $comp")
                    val err = errorLine(r)
                    if (err != null) Shared.say("Couldn't open: " + err.take(100))
                } else {
                    val r = shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1; monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1")
                    if (r.contains("No activities found") && !r.contains("Events injected: 1")) Shared.say("This app can't be opened from here")
                }
            } catch (e: Exception) { Shared.say(e.message ?: "Couldn't open app") }
        }
    }

    // ───────────── fast input ─────────────
    private fun detectPlan(): InputPlan? {
        val out = try { shellBlocking("getevent -p 2>/dev/null") } catch (e: Exception) { return null }
        val devices = ArrayList<Pair<String, Set<Int>>>()
        var cur: String? = null
        var keys = HashSet<Int>()
        var inKey = false
        fun flush() {
            val c = cur
            if (c != null) devices.add(c to keys)
            cur = null
            keys = HashSet()
            inKey = false
        }
        val addRe = Regex("^add device \\d+: (\\S+)")
        val hexRe = Regex("^\\s+([0-9a-fA-F]{4}\\s*)+$")
        for (line in out.lines()) {
            val a = addRe.find(line)
            if (a != null) { flush(); cur = a.groupValues[1]; continue }
            if (line.contains("KEY (0001):")) {
                inKey = true
                line.substringAfter(":").trim().split(Regex("\\s+")).forEach { t ->
                    t.toIntOrNull(16)?.let { keys.add(it) }
                }
            } else if (inKey && hexRe.matches(line)) {
                line.trim().split(Regex("\\s+")).forEach { t -> t.toIntOrNull(16)?.let { keys.add(it) } }
            } else {
                inKey = false
            }
        }
        flush()
        val nav = listOf(103, 108, 105, 106)
        val extra = listOf(158, 102, 139, 115, 114, 113, 116, 164, 163, 165, 168, 208, 353)
        var best: InputPlan? = null
        var bestScore = -1
        for ((dev, k) in devices) {
            if (!k.containsAll(nav)) continue
            if (!(k.contains(353) || k.contains(28))) continue
            val score = extra.count { k.contains(it) }
            if (score > bestScore) { bestScore = score; best = InputPlan(dev, k) }
        }
        return best
    }

    private fun probeInput() {
        plan = detectPlan()
        cmdInputOk = try { shellBlocking("cmd input keyevent 0 2>&1").isBlank() } catch (e: Exception) { false }
        volSetOk = true
        volVerified = false
        volume = -1
        volStyle = when {
            readVolumeVia(0) -> 0
            readVolumeVia(1) -> 1
            else -> { readVolumeDumpsys(); 2 }
        }
        chooseMode()
    }

    private fun chooseMode() {
        inject = when {
            injectPref == "compatible" -> Inject.INPUT
            plan != null -> Inject.SENDEVENT
            cmdInputOk -> Inject.CMD
            else -> Inject.INPUT
        }
    }

    fun changeInjectPref(p: String) {
        injectPref = p
        Store.setPref("inject", p)
        chooseMode()
        Shared.say(if (inject == Inject.INPUT) "Compatible mode" else "Fast mode on")
    }

    fun changeVolumeMode(m: String) {
        volumeMode = m
        Store.setPref("vol", m)
    }

    private fun sendeventFor(code: Int): String? {
        val p = plan ?: return null
        val linux = if (code == 23) (if (p.keys.contains(353)) 353 else 28) else (LINUX_KEY[code] ?: return null)
        if (!p.keys.contains(linux)) return null
        val d = p.device
        return "sendevent $d 1 $linux 1; sendevent $d 0 0 0; sendevent $d 1 $linux 0; sendevent $d 0 0 0"
    }

    // ───────────── device volume (what Fire TV shows as its volume bar) ─────────────
    private val volRe = Regex("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)\\]")

    private fun readVolumeVia(style: Int): Boolean {
        val cmd = if (style == 0) "cmd media_session volume --get --stream 3 2>&1" else "media volume --get --stream 3 2>&1"
        val o = try { shellBlocking(cmd) } catch (e: Exception) { return false }
        val m = volRe.find(o) ?: return false
        volume = m.groupValues[1].toInt()
        volumeMin = m.groupValues[2].toInt()
        volumeMax = m.groupValues[3].toInt().coerceAtLeast(1)
        volReadAt = SystemClock.elapsedRealtime()
        return true
    }

    private fun readVolumeDumpsys() {
        val o = try { shellBlocking("dumpsys audio 2>&1") } catch (e: Exception) { return }
        val start = o.indexOf("- STREAM_MUSIC:")
        if (start < 0) return
        var end = o.indexOf("- STREAM_", start + 10)
        if (end < 0) end = minOf(o.length, start + 4000)
        val block = o.substring(start, end)
        val max = Regex("Max:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull()
        val min = Regex("Min:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val cur = Regex("Current:(.*)").find(block)?.groupValues?.get(1) ?: return
        val pairs = Regex("\\((\\w+)\\):\\s*(\\d+)").findAll(cur).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
        val pick = pairs.firstOrNull { it.first == "hdmi" } ?: pairs.firstOrNull { it.first == "speaker" } ?: pairs.firstOrNull()
        if (pick != null) {
            volume = pick.second
            volumeMin = min
            if (max != null) volumeMax = max.coerceAtLeast(1)
            volReadAt = SystemClock.elapsedRealtime()
        }
    }

    /** Re-reads the TV's volume (used to keep the slider correct). */
    suspend fun refreshVolume() = withContext(Dispatchers.IO) {
        if (!isConnected || volStyle > 1) return@withContext
        readVolumeVia(volStyle)
    }

    private fun setVolumeBlocking(t: Int) {
        val cmd = if (volStyle == 1) "media volume --show --stream 3 --set $t" else "cmd media_session volume --show --stream 3 --set $t"
        shellBlocking(cmd)
        volume = t
        volReadAt = SystemClock.elapsedRealtime()
    }

    /** Moves the device volume by [delta] steps. Falls back to volume keys if setting isn't possible. */
    private fun adjustDevice(delta: Int) {
        if (delta == 0) return
        val now = SystemClock.elapsedRealtime()
        if (volStyle <= 1 && (volume < 0 || now - volReadAt > 6000)) readVolumeVia(volStyle)
        if (volStyle <= 1 && volume >= 0 && volSetOk) {
            val t = (volume + delta).coerceIn(volumeMin, volumeMax)
            if (t != volume) {
                setVolumeBlocking(t)
                if (!volVerified) {
                    volVerified = true
                    readVolumeVia(volStyle)
                    if (volume != t) volSetOk = false
                }
            }
            if (volSetOk) return
        }
        val k = if (delta > 0) 24 else 25
        shellBlocking(buildCommand(List(minOf(kotlin.math.abs(delta), 6)) { k }))
    }

    /** Set the device volume to an exact level (slider). */
    fun setVolumeTo(level: Int) {
        val t = level.coerceIn(volumeMin, volumeMax)
        volume = t
        ioScope.launch {
            try {
                if (volStyle <= 1) setVolumeBlocking(t)
            } catch (e: Exception) {
                Shared.say(e.message ?: "Couldn't change volume")
            }
        }
    }

    private fun buildCommand(batch: List<Int>): String {
        val parts = ArrayList<String>()
        var codes = ArrayList<Int>()
        fun flushCodes() {
            if (codes.isEmpty()) return
            parts.add(
                if (inject == Inject.CMD) codes.joinToString("; ") { "cmd input keyevent $it" }
                else "input keyevent " + codes.joinToString(" ")
            )
            codes = ArrayList()
        }
        for (t in batch) {
            if (t < 0) continue
            val seq = if (inject == Inject.SENDEVENT) sendeventFor(t) else null
            if (seq != null) { flushCodes(); parts.add(seq) } else codes.add(t)
        }
        flushCodes()
        return parts.joinToString("; ")
    }

    private val pending = AtomicInteger(0)
    private val keyChannel = Channel<Int>(Channel.UNLIMITED)
    private var worker: Job? = null

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = ioScope.launch {
            while (isActive) {
                val first = keyChannel.receive()
                val batch = ArrayList<Int>(8)
                batch.add(first)
                while (batch.size < 24) {
                    val next = keyChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                pending.addAndGet(-batch.size)
                try {
                    var volDelta = 0
                    for (t in batch) {
                        if (t == -24) volDelta++ else if (t == -25) volDelta--
                    }
                    val normal = batch.filter { it > 0 }
                    if (normal.isNotEmpty()) {
                        val out = shellBlocking(buildCommand(normal))
                        val low = out.lowercase()
                        val broken = (inject == Inject.SENDEVENT && (low.contains("sendevent") || low.contains("/dev/input"))) ||
                            (inject == Inject.CMD && (low.contains("find service") || low.contains("unknown")))
                        if (broken) {
                            inject = Inject.INPUT
                            Shared.say("Fast input isn't supported on this TV. Switched to Compatible mode.")
                            shellBlocking(buildCommand(normal))
                        }
                    }
                    if (volDelta != 0) adjustDevice(volDelta)
                } catch (e: Exception) {
                    if (Shared.message == null) Shared.say(e.message ?: "Couldn't send key")
                }
            }
        }
    }

    private fun enqueue(token: Int) {
        if (pending.get() >= 12) return
        pending.incrementAndGet()
        ensureWorker()
        keyChannel.trySend(token)
    }

    /** Press a button on the TV. Volume keys follow the Device / TV / Both setting. */
    fun sendKey(code: Int) {
        if (!isConnected) {
            if (Shared.message == null) Shared.say("Not connected. Open the Devices tab.")
            return
        }
        lastKeyTime = SystemClock.elapsedRealtime()
        if (code == 24 || code == 25) {
            when (volumeMode) {
                "device" -> enqueue(-code)
                "tv" -> enqueue(code)
                else -> { enqueue(-code); enqueue(code) }
            }
        } else enqueue(code)
    }

    suspend fun typeText(text: String) {
        shell("input text " + shq(text.replace(" ", "%s")))
    }

    fun tap(x: Int, y: Int) = runBg("input tap $x $y")
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = runBg("input swipe $x1 $y1 $x2 $y2 250")

    // ───────────── files / install ─────────────
    suspend fun push(local: File, remote: String) = withContext(Dispatchers.IO) { conn().push(local, remote) }
    suspend fun pull(remote: String, local: File) = withContext(Dispatchers.IO) { conn().pull(local, remote) }

    suspend fun install(files: List<File>) = withContext(Dispatchers.IO) {
        if (files.size == 1) conn().install(files[0]) else conn().installMultiple(files)
    }

    // ───────────── screen ─────────────
    suspend fun capture(sample: Int = 1): Frame? = withContext(Dispatchers.IO) {
        val remote = "/data/local/tmp/tvdeck_shot.png"
        val o = shellBlocking("screencap -p $remote 2>&1")
        if (o.contains("not found", true)) throw IllegalStateException("This TV doesn't support screen capture")
        val f = File(context.cacheDir, "tvdeck_shot_$sample.png")
        conn().pull(f, remote)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return@withContext null
        Frame(bmp, bmp.width * sample, bmp.height * sample)
    }

    fun toggleRecording() {
        if (recording) {
            appScope.launch {
                try { shell("pkill -2 screenrecord || killall -2 screenrecord") }
                catch (e: Exception) { Shared.say(e.message ?: "Couldn't stop recording") }
            }
            return
        }
        recording = true
        appScope.launch {
            val remote = "/data/local/tmp/tvdeck_rec.mp4"
            try {
                Shared.say("Recording… tap Record again to stop (max 3 min)")
                val o = shell("rm -f $remote; screenrecord --time-limit 180 $remote 2>&1")
                if (o.contains("not found", true)) throw IllegalStateException("This TV doesn't support screen recording")
                delay(800)
                val f = File(context.cacheDir, "tvdeck_rec.mp4")
                pull(remote, f)
                val saved = withContext(Dispatchers.IO) {
                    saveFile(context, f, "TV_${System.currentTimeMillis()}.mp4", "video/mp4", "video")
                }
                Shared.say("Saved to ${saved.label}")
            } catch (e: Exception) {
                Shared.say("Recording failed: ${e.message}")
            }
            recording = false
        }
    }

    // ───────────── on-TV cursor helper ─────────────
    const val HELPER_PKG = "com.tvdeck.helper"

    fun helperBundled(): Boolean = try { context.assets.open("tvhelper.apk").close(); true } catch (e: Exception) { false }

    /** Installs and switches on the cursor helper on the TV. Returns an error message, or null when it works. */
    suspend fun setupCursorHelper(onStep: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) return@withContext "Connect to a TV first."
            if (!helperBundled()) {
                return@withContext "This build of TVDeck doesn't include the cursor helper. Check the build log step \"Build TV cursor helper\"."
            }
            onStep("Checking the TV…")
            val installed = shellBlocking("pm list packages $HELPER_PKG").contains("package:$HELPER_PKG")
            if (!installed) {
                onStep("Installing the helper on your TV…")
                val f = File(context.cacheDir, "tvhelper.apk")
                context.assets.open("tvhelper.apk").use { i -> f.outputStream().use { i.copyTo(it) } }
                conn().install(f)
            }
            onStep("Switching it on…")
            shellBlocking("am start -n $HELPER_PKG/.MainActivity 2>&1")
            val svc = "$HELPER_PKG/$HELPER_PKG.CursorService"
            val cur = shellBlocking("settings get secure enabled_accessibility_services").trim()
            val list = when {
                cur.isBlank() || cur == "null" -> svc
                cur.split(":").contains(svc) -> cur
                else -> "$cur:$svc"
            }
            shellBlocking("settings put secure enabled_accessibility_services $list")
            shellBlocking("settings put secure accessibility_enabled 1")
            onStep("Connecting…")
            var ok = false
            for (i in 0 until 8) {
                delay(700)
                if (CursorClient.check()) { ok = true; break }
            }
            if (ok) null
            else "The helper is installed but didn't start. On the TV open Settings > Accessibility and switch on \"TVDeck Cursor\", then try again."
        } catch (e: Exception) {
            "Setup failed: ${e.message}"
        }
    }

    // ───────────── discovery ─────────────
    suspend fun scan(): List<String> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val mine = lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
            ?: return@withContext emptyList<String>()
        val prefix = mine.substringBeforeLast(".")
        (1..254).map { i ->
            async {
                val host = "$prefix.$i"
                try {
                    Socket().use { it.connect(InetSocketAddress(host, 5555), 400) }
                    host
                } catch (e: Exception) { null }
            }
        }.awaitAll().filterNotNull()
    }
}
