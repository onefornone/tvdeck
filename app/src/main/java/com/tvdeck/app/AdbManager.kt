package com.tvdeck.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
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

    fun init(ctx: Context) { appCtx = ctx.applicationContext }
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

    /** Fire-and-forget command with a friendly message when done. */
    fun runBg(cmd: String, ok: String = "") {
        appScope.launch {
            try {
                shell(cmd)
                if (ok.isNotEmpty()) Shared.say(ok)
            } catch (e: Exception) {
                Shared.say(e.message ?: "Command failed")
            }
        }
    }

    // ───────────── fast key sender (batches taps so the remote never lags) ─────────────
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
                    shellBlocking("input keyevent " + batch.joinToString(" "))
                } catch (e: Exception) {
                    if (Shared.message == null) Shared.say(e.message ?: "Couldn't send key")
                }
            }
        }
    }

    fun sendKey(code: Int) {
        if (!isConnected) {
            if (Shared.message == null) Shared.say("Not connected. Open the Devices tab.")
            return
        }
        if (pending.get() >= 10) return
        pending.incrementAndGet()
        ensureWorker()
        keyChannel.trySend(code)
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
        shellBlocking("screencap -p $remote")
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
                shell("rm -f $remote; screenrecord --time-limit 180 $remote")
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
