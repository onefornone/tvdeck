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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Finds the real name and icon of every app on the TV.
 * It copies each app's APK to the phone (one at a time, in the background), reads the
 * name/icon with Android's own tools, then caches them so it only happens once per app.
 */
object AppMeta {
    val labels = mutableStateMapOf<String, String>()
    val icons = mutableStateMapOf<String, ImageBitmap>()
    var remaining by mutableStateOf(0)

    private val queue = ArrayDeque<AppItem>()
    private val queued = HashSet<String>()
    private val tried = HashSet<String>()
    private var worker: Job? = null
    private var ctx: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var iconDir: File? = null
    private var labelFile: File? = null

    fun init(c: Context) {
        val app = c.applicationContext
        ctx = app
        val dir = File(app.filesDir, "icons").apply { mkdirs() }
        iconDir = dir
        val lf = File(app.filesDir, "labels.tsv")
        labelFile = lf
        try {
            if (lf.exists()) lf.readLines().forEach { l ->
                val i = l.indexOf('\t')
                if (i > 0) labels[l.substring(0, i)] = l.substring(i + 1)
            }
        } catch (_: Exception) {}
    }

    fun request(app: AppItem) {
        if (ctx == null || icons.containsKey(app.pkg)) return
        synchronized(queue) {
            if (app.pkg in queued || app.pkg in tried) return
            queued.add(app.pkg)
            queue.addLast(app)
            remaining = queue.size
        }
        startWorker()
    }

    fun cancel(pkg: String) {
        synchronized(queue) {
            if (queued.remove(pkg)) {
                queue.removeAll { it.pkg == pkg }
                remaining = queue.size
            }
        }
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive) {
                val app = synchronized(queue) {
                    val a = queue.removeFirstOrNull()
                    if (a != null) queued.remove(a.pkg)
                    remaining = queue.size
                    a
                } ?: break
                // never compete with the remote control for the Wi-Fi link
                while (SystemClock.elapsedRealtime() - AdbManager.lastKeyTime < 2500) delay(400)
                if (!AdbManager.isConnected) break
                try { loadOne(app) } catch (_: Exception) {}
                synchronized(queue) { tried.add(app.pkg) }
            }
        }
    }

    private fun decode(f: File): Bitmap? = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }

    private suspend fun loadOne(app: AppItem) {
        val c = ctx ?: return
        val dir = iconDir ?: return
        val png = File(dir, app.pkg + ".png")
        if (png.exists()) {
            decode(png)?.let { icons[app.pkg] = it.asImageBitmap() }
            return
        }
        if (app.path.isEmpty()) return
        val size = AdbManager.shell("stat -c %s ${shq(app.path)} 2>/dev/null").trim().toLongOrNull() ?: 0L
        if (size > 120L * 1024 * 1024) return
        val apk = File(c.cacheDir, "meta_${app.pkg}.apk")
        try {
            AdbManager.pull(app.path, apk)
            val pm = c.packageManager
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return
            val ai = info.applicationInfo ?: return
            ai.sourceDir = apk.absolutePath
            ai.publicSourceDir = apk.absolutePath
            val label = try { ai.loadLabel(pm).toString().trim() } catch (_: Exception) { "" }
            val bmp = try { ai.loadIcon(pm).toBitmap(112, 112) } catch (_: Exception) { null }
            if (label.isNotEmpty() && label != app.pkg) {
                labels[app.pkg] = label
                try { labelFile?.appendText(app.pkg + "\t" + label.replace("\t", " ").replace("\n", " ") + "\n") } catch (_: Exception) {}
            }
            if (bmp != null) {
                png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                icons[app.pkg] = bmp.asImageBitmap()
            }
        } finally {
            apk.delete()
        }
    }
}
