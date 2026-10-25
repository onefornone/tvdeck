package com.tvdeck.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color
import java.io.File

/** Safely quote text for the TV's shell. */
fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return "file"
}

fun formatSize(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

fun formatUptime(sec: Long): String {
    val d = sec / 86400
    val h = (sec % 86400) / 3600
    val m = (sec % 3600) / 60
    return if (d > 0) "${d}d ${h}h ${m}m" else if (h > 0) "${h}h ${m}m" else "${m}m"
}

private val skipWords = setOf("com", "org", "net", "android", "google", "app", "apps", "mobile", "tv", "www", "io", "amazon", "androidtv", "atv")

fun prettyName(pkg: String): String {
    val parts = pkg.split('.')
    val keep = parts.filter { it.isNotEmpty() && it.lowercase() !in skipWords }
    val base = keep.firstOrNull() ?: parts.last()
    return base.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private val avatarColors = listOf(
    Color(0xFF0E7C7B), Color(0xFF4F6BED), Color(0xFFD97706), Color(0xFFE11D48),
    Color(0xFF7C3AED), Color(0xFF059669), Color(0xFF0284C7), Color(0xFFDB2777)
)

fun avatarColor(key: String): Color = avatarColors[(key.hashCode() and 0x7fffffff) % avatarColors.size]

class Saved(val label: String, val uri: Uri?)

/** Saves a file to the phone's Downloads / Pictures / Movies (kind = "download" | "image" | "video"). */
fun saveFile(ctx: Context, src: File, name: String, mime: String, kind: String): Saved {
    if (Build.VERSION.SDK_INT >= 29) {
        val (collection, dir) = when (kind) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/TVDeck"
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/TVDeck"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/TVDeck"
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }
        val uri = ctx.contentResolver.insert(collection, v)
            ?: throw IllegalStateException("Couldn't create the file on your phone")
        ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        return Saved("$dir/$name", uri)
    }
    val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "TVDeck").apply { mkdirs() }
    val out = File(dir, name)
    src.copyTo(out, overwrite = true)
    return Saved(out.absolutePath, null)
}
