@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

fun isApkName(name: String): Boolean {
    val n = name.substringBefore('?').lowercase()
    return n.endsWith(".apk") || n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm")
}

/** Downloading apps from links, installing shared files and sending files to the TV. */
object Installer {
    private fun download(url: String, out: File, progress: (Int) -> Unit) {
        var current = URL(url)
        var conn = current.openConnection() as HttpURLConnection
        var redirects = 0
        while (true) {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("The link redirects nowhere")
                conn.disconnect()
                current = URL(current, loc)
                conn = current.openConnection() as HttpURLConnection
                redirects++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IllegalStateException("The server answered with error $code")
            }
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { ins ->
            out.outputStream().use { o ->
                val buf = ByteArray(65536)
                var done = 0L
                var last = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        }
        conn.disconnect()
    }

    /** Returns the APK files inside [f]: the file itself, or all APKs inside a bundle (XAPK / APKS). */
    private fun extractApks(f: File, dir: File): List<File> {
        return try {
            ZipFile(f).use { z ->
                if (z.getEntry("AndroidManifest.xml") != null) {
                    val apk = File(dir, "single_${f.name}.apk")
                    f.copyTo(apk, overwrite = true)
                    listOf(apk)
                } else {
                    val list = ArrayList<File>()
                    val e = z.entries()
                    while (e.hasMoreElements()) {
                        val en = e.nextElement()
                        if (!en.isDirectory && en.name.lowercase().endsWith(".apk")) {
                            val o = File(dir, "${f.name}_part${list.size}.apk")
                            z.getInputStream(en).use { i -> o.outputStream().use { i.copyTo(it) } }
                            list.add(o)
                        }
                    }
                    list
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun installFromUrl(ctx: Context, url: String, onStatus: (String) -> Unit) {
        val dir = File(ctx.cacheDir, "install_dl").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "download.bin")
        withContext(Dispatchers.IO) { download(url, f) { onStatus("Downloading… $it%") } }
        onStatus("Checking the file…")
        val apks = withContext(Dispatchers.IO) { extractApks(f, dir) }
        if (apks.isEmpty()) throw IllegalStateException("This link isn't an Android app (APK).")
        onStatus("Installing on your TV…")
        AdbManager.install(apks)
    }

    /** Installs shared APK / XAPK files on the TV. Returns how many apps were installed. */
    suspend fun installUris(ctx: Context, uris: List<Uri>, onStatus: (String) -> Unit): Int {
        val dir = File(ctx.cacheDir, "install_in").apply { deleteRecursively(); mkdirs() }
        var ok = 0
        for ((i, uri) in uris.withIndex()) {
            val name = displayName(ctx, uri)
            onStatus("Preparing $name…")
            val f = File(dir, "in_$i.bin")
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)!!.use { s -> f.outputStream().use { s.copyTo(it) } }
            }
            val apks = withContext(Dispatchers.IO) { extractApks(f, dir) }
            if (apks.isEmpty()) throw IllegalStateException("$name isn't an Android app")
            onStatus("Installing $name…")
            AdbManager.install(apks)
            ok++
        }
        return ok
    }

    /** Copies files to the TV's Download/TVDeck folder. Returns the paths on the TV. */
    suspend fun sendFiles(ctx: Context, uris: List<Uri>): List<String> {
        AdbManager.shell("mkdir -p /sdcard/Download/TVDeck")
        val paths = ArrayList<String>()
        val dir = File(ctx.cacheDir, "send").apply { deleteRecursively(); mkdirs() }
        for ((i, uri) in uris.withIndex()) {
            val name = displayName(ctx, uri).replace("/", "_")
            val f = File(dir, "s_$i")
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)!!.use { s -> f.outputStream().use { s.copyTo(it) } }
            }
            val remote = "/sdcard/Download/TVDeck/$name"
            AdbManager.push(f, remote)
            paths.add(remote)
            f.delete()
        }
        return paths
    }
}

/** The "install from a link" card on the Install page. */
@Composable
fun LinkInstallCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    TvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.Link, cs.secondary, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Install from a link", fontWeight = FontWeight.SemiBold)
                Text("Paste a direct link to an APK or XAPK", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("https://…/app.apk") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clip.getText()?.text?.let { url = it.trim() } }, enabled = !busy) { Text("Paste") }
            Button(
                enabled = !busy && url.trim().startsWith("http") && AdbManager.isConnected,
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Starting…"
                        try {
                            Installer.installFromUrl(ctx, url.trim()) { status = it }
                            status = "✅ Installed on your TV"
                        } catch (e: Exception) {
                            status = "❌ ${e.message ?: "Failed"}"
                        }
                        busy = false
                    }
                }
            ) { Text("Download & install") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (status.isNotEmpty()) Text(status, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Text(
            "The phone downloads the file, then sends it to the TV. Only install apps from sources you trust.",
            fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
        )
    }
}
