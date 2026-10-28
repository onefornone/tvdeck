package com.tvdeck.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the real name and icon of every app on the TV, fast:
 *  1. saved copy on the phone (instant)
 *  2. the Google Play listing for that package (about 0.3 s per app, 6 at a time)
 *  3. otherwise the app's own APK is copied from the TV and read (slower, used for system apps)
 */
object AppMeta {
    val labels = mutableStateMapOf<String, String>()
    val icons = mutableStateMapOf<String, ImageBitmap>()
    var remaining by mutableStateOf(0)
    var online by mutableStateOf(true)

    private val lock = Any()
    private val queue = ArrayList<AppItem>()
    private val known = HashSet<String>()
    private var active = 0
    private var inflight = 0
    private val apkGate = Semaphore(2)
    private var ctx: Context? = null
    private var iconDir: File? = null
    private var labelFile: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(c: Context) {
        val app = c.applicationContext
        ctx = app
        iconDir = File(app.filesDir, "icons").apply { mkdirs() }
        val lf = File(app.filesDir, "labels.tsv")
        labelFile = lf
        online = Store.getPref("online", "1") == "1"
        try {
            if (lf.exists()) lf.readLines().forEach { l ->
                val i = l.indexOf('\t')
                if (i > 0) labels[l.substring(0, i)] = l.substring(i + 1)
            }
        } catch (_: Exception) {}
    }

    fun changeOnline(v: Boolean) {
        online = v
        Store.setPref("online", if (v) "1" else "0")
    }

    fun clearCache() {
        synchronized(lock) { known.clear(); queue.clear(); remaining = 0 }
        icons.clear()
        labels.clear()
        try { iconDir?.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        try { labelFile?.delete() } catch (_: Exception) {}
    }

    private fun updateRemaining() { remaining = queue.size + inflight }

    /** Ask for an app's name + icon. Rows that are on screen jump the queue (front = true). */
    fun request(app: AppItem, front: Boolean = true) {
        if (ctx == null) return
        synchronized(lock) {
            if (icons.containsKey(app.pkg) && labels.containsKey(app.pkg)) return
            if (known.contains(app.pkg)) {
                if (front) {
                    val idx = queue.indexOfFirst { it.pkg == app.pkg }
                    if (idx > 0) { val moved = queue.removeAt(idx); queue.add(0, moved) }
                }
                return
            }
            known.add(app.pkg)
            if (front) queue.add(0, app) else queue.add(app)
            updateRemaining()
        }
        ensureWorkers()
    }

    fun prefetch(list: List<AppItem>) { list.forEach { request(it, false) } }

    private fun ensureWorkers() {
        synchronized(lock) {
            while (active < 6 && queue.isNotEmpty()) {
                active++
                scope.launch { workerLoop() }
            }
        }
    }

    private suspend fun workerLoop() {
        try {
            while (true) {
                val app = synchronized(lock) {
                    if (queue.isEmpty()) null else {
                        val a = queue.removeAt(0)
                        inflight++
                        updateRemaining()
                        a
                    }
                } ?: break
                try { process(app) } catch (_: Exception) {}
                synchronized(lock) { inflight--; updateRemaining() }
            }
        } finally {
            synchronized(lock) { active-- }
        }
    }

    private fun decode(f: File): Bitmap? = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }

    private fun setLabel(pkg: String, label: String) {
        if (label.isBlank() || label == pkg) return
        labels[pkg] = label
        try {
            labelFile?.appendText(pkg + "\t" + label.replace("\t", " ").replace("\n", " ") + "\n")
        } catch (_: Exception) {}
    }

    private fun setIcon(pkg: String, bmp: Bitmap) {
        val dir = iconDir ?: return
        try { File(dir, "$pkg.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
        icons[pkg] = bmp.asImageBitmap()
    }

    private suspend fun process(app: AppItem) {
        val dir = iconDir ?: return
        val png = File(dir, app.pkg + ".png")
        if (!icons.containsKey(app.pkg) && png.exists()) {
            decode(png)?.let { icons[app.pkg] = it.asImageBitmap() }
        }
        if (icons.containsKey(app.pkg) && labels.containsKey(app.pkg)) return

        if (online) {
            val r = try { playLookup(app.pkg) } catch (_: Exception) { null }
            if (r != null) {
                r.first?.let { if (!labels.containsKey(app.pkg)) setLabel(app.pkg, it) }
                r.second?.let { if (!icons.containsKey(app.pkg)) setIcon(app.pkg, it) }
            }
        }
        if (icons.containsKey(app.pkg) && labels.containsKey(app.pkg)) return

        if (app.path.isNotEmpty()) {
            apkGate.withPermit {
                try { fromApk(app) } catch (_: Exception) {}
            }
        }
    }

    // ───────────── Google Play lookup ─────────────
    private fun httpGet(url: String, maxBytes: Int): ByteArray? {
        val conn = NetBinder.open(url) as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (conn.responseCode != 200) return null
            val buf = ByteArray(16384)
            val out = ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                while (out.size() < maxBytes) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun playLookup(pkg: String): Pair<String?, Bitmap?>? {
        val bytes = httpGet("https://play.google.com/store/apps/details?id=$pkg&hl=en&gl=US", 450_000) ?: return null
        val html = String(bytes, Charsets.UTF_8)
        val rawTitle = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("<title[^>]*>([^<]+)</title>").find(html)?.groupValues?.get(1)
        var title = rawTitle?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() }
        title = title?.replace(Regex("\\s*-\\s*Apps on Google Play\\s*$"), "")?.trim()
        if (title != null && (title.isBlank() || title.equals("Not Found", true))) title = null

        var iconUrl = Regex("\"image\"\\s*:\\s*\"(https://play-lh\\.googleusercontent\\.com/[^\"]+)\"").find(html)?.groupValues?.get(1)
        if (iconUrl == null) {
            val tag = Regex("<img[^>]+alt=\"Icon image\"[^>]*>").find(html)?.value
            iconUrl = tag?.let { Regex("src=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        }
        var bmp: Bitmap? = null
        if (iconUrl != null) {
            val u = iconUrl.replace("\\u003d", "=").replace(Regex("=[sw]\\d+[^\"]*$"), "=s192-rw")
            val data = try { httpGet(u, 2_000_000) } catch (_: Exception) { null }
            if (data != null) {
                val raw = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (raw != null) bmp = Bitmap.createScaledBitmap(raw, 112, 112, true)
            }
        }
        if (title == null && bmp == null) return null
        return title to bmp
    }

    // ───────────── fallback: read the APK ─────────────
    private suspend fun fromApk(app: AppItem) {
        val c = ctx ?: return
        while (SystemClock.elapsedRealtime() - AdbManager.lastKeyTime < 1500) delay(300)
        if (!AdbManager.isConnected) return
        val size = AdbManager.shell("stat -c %s ${shq(app.path)} 2>/dev/null").trim().toLongOrNull() ?: 0L
        if (size > 90L * 1024 * 1024) return
        val apk = File(c.cacheDir, "meta_${app.pkg}.apk")
        try {
            AdbManager.pull(app.path, apk)
            val pm = c.packageManager
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return
            val ai = info.applicationInfo ?: return
            ai.sourceDir = apk.absolutePath
            ai.publicSourceDir = apk.absolutePath
            if (!labels.containsKey(app.pkg)) {
                val label = try { ai.loadLabel(pm).toString().trim() } catch (_: Exception) { "" }
                setLabel(app.pkg, label)
            }
            if (!icons.containsKey(app.pkg)) {
                val bmp = try { ai.loadIcon(pm).toBitmap(112, 112) } catch (_: Exception) { null }
                if (bmp != null) setIcon(app.pkg, bmp)
            }
        } finally {
            apk.delete()
        }
    }
}
