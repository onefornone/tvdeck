@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FileEntry(val name: String, val isDir: Boolean, val size: Long?, val date: String)

/** What the user copied / cut, waiting to be pasted. */
object FileClip {
    var path by mutableStateOf<String?>(null)
    var cut by mutableStateOf(false)
}

private val LS_LINE = Regex("""^([a-z\-])[rwxsStT\-]{9}.*?(?:\s(\d+))?\s(\d{4}-\d{2}-\d{2})\s(\d{2}:\d{2})\s(.+)$""")

private fun parseLs(out: String): List<FileEntry> = out.lines().mapNotNull { raw ->
    val m = LS_LINE.find(raw.trim()) ?: return@mapNotNull null
    val type = m.groupValues[1]
    var name = m.groupValues[5]
    if (type == "l") name = name.substringBefore(" -> ")
    if (name == "." || name == "..") return@mapNotNull null
    FileEntry(name, type == "d" || type == "l", m.groupValues[2].toLongOrNull(), m.groupValues[3] + " " + m.groupValues[4])
}.sortedWith(compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase() }))

private fun iconFor(e: FileEntry): Pair<ImageVector, Color> {
    if (e.isDir) return Icons.Default.Folder to Color(0xFFD97706)
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "apk", "xapk", "apks" -> Icons.Default.Android to Color(0xFF059669)
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Icons.Default.Image to Color(0xFF7C3AED)
        "mp4", "mkv", "avi", "mov", "webm" -> Icons.Default.Videocam to Color(0xFFE11D48)
        "mp3", "wav", "flac", "m4a", "ogg" -> Icons.Default.MusicNote to Color(0xFF0284C7)
        else -> Icons.Default.InsertDriveFile to Color(0xFF64748B)
    }
}

@Composable
fun FilesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme

    var path by rememberSaveable { mutableStateOf("/sdcard/") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    BackHandler(enabled = path.length > 1) {
        path = path.trimEnd('/').substringBeforeLast('/', "") + "/"
    }

    LaunchedEffect(path, tick, AdbManager.isConnected) {
        if (!AdbManager.isConnected) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val out = AdbManager.shell("ls -lA ${shq(path)} 2>&1")
            var list = parseLs(out)
            if (list.isEmpty()) {
                // Some TVs print `ls -l` in an unusual format: fall back to plain names.
                val alt = AdbManager.shell("ls -1Ap ${shq(path)} 2>&1")
                val names = alt.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (names.isNotEmpty() && names.none { it.startsWith("ls:") }) {
                    list = names.map { n -> FileEntry(n.trimEnd('/'), n.endsWith("/"), null, "") }
                        .sortedWith(compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase() }))
                } else {
                    val t = (if (names.isNotEmpty()) alt else out).trim()
                    if (t.isNotEmpty() && !t.startsWith("total")) error = t
                }
            }
            entries = list
        } catch (e: Exception) {
            entries = emptyList()
            error = e.message ?: "Couldn't read this folder"
        }
        loading = false
    }

    fun refresh() { tick++ }

    fun shell(cmd: String, ok: String) {
        scope.launch {
            try { AdbManager.shell(cmd); if (ok.isNotEmpty()) Shared.say(ok); refresh() }
            catch (e: Exception) { Shared.say(e.message ?: "Failed") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                uris.forEachIndexed { i, uri ->
                    val name = displayName(ctx, uri)
                    busy = "Uploading ${i + 1}/${uris.size}: $name"
                    val f = File(ctx.cacheDir, "up_$name")
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    }
                    AdbManager.push(f, path + name)
                    f.delete()
                }
                Shared.say("Uploaded ${uris.size} file(s)")
                refresh()
            } catch (e: Exception) { Shared.say("Upload failed: ${e.message}") }
            busy = ""
        }
    }

    fun download(e: FileEntry) {
        scope.launch {
            try {
                busy = "Downloading ${e.name}…"
                val f = File(ctx.cacheDir, "dl_${e.name}")
                AdbManager.pull(path + e.name, f)
                val ext = e.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val kind = when {
                    mime.startsWith("image/") -> "image"
                    mime.startsWith("video/") -> "video"
                    else -> "download"
                }
                val s = withContext(Dispatchers.IO) { saveFile(ctx, f, e.name, mime, kind) }
                f.delete()
                Shared.say("Saved to ${s.label}")
            } catch (ex: Exception) { Shared.say("Download failed: ${ex.message}") }
            busy = ""
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Files", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(path, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else {
                if (path.length > 1) IconButton(onClick = { path = path.trimEnd('/').substringBeforeLast('/', "") + "/" }) {
                    Icon(Icons.Default.ArrowUpward, "Up")
                }
                IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Internal" to "/sdcard/", "Download" to "/sdcard/Download/", "DCIM" to "/sdcard/DCIM/",
                "Pictures" to "/sdcard/Pictures/", "Movies" to "/sdcard/Movies/", "Music" to "/sdcard/Music/",
                "Storage" to "/storage/", "Root" to "/"
            ).forEach { (label, p) ->
                FilterChip(selected = path == p, onClick = { path = p }, label = { Text(label) })
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { picker.launch(arrayOf("*/*")) },
                label = { Text("Upload") },
                leadingIcon = { Icon(Icons.Default.Upload, null, Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = { newFolder = true },
                label = { Text("New folder") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp)) }
            )
            if (FileClip.path != null) {
                AssistChip(
                    onClick = {
                        val src = FileClip.path ?: return@AssistChip
                        val cmd = if (FileClip.cut) "mv ${shq(src)} ${shq(path)}" else "cp -r ${shq(src)} ${shq(path)}"
                        if (FileClip.cut) FileClip.path = null
                        shell(cmd, "Pasted")
                    },
                    label = { Text("Paste here") },
                    leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)) }
                )
            }
        }

        if (busy.isNotEmpty()) Text(busy, fontSize = 12.sp, color = cs.primary)

        if (!AdbManager.isConnected) {
            EmptyState(Icons.Default.Folder, "Connect a TV first", "Browse and transfer files after you connect.")
        } else if (error.isNotEmpty()) {
            EmptyState(Icons.Default.Folder, "Can't open this folder", error.take(200))
        } else if (entries.isEmpty() && !loading) {
            EmptyState(Icons.Default.Folder, "Empty folder", "Use Upload to send files from your phone.")
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(entries, key = { it.name }) { e ->
                FileRow(
                    e,
                    onOpen = { path = path + e.name + "/" },
                    onAction = { a ->
                        when (a) {
                            "download" -> download(e)
                            "rename" -> renaming = e
                            "copy" -> { FileClip.path = path + e.name; FileClip.cut = false; Shared.say("Copied. Open a folder and tap Paste here") }
                            "cut" -> { FileClip.path = path + e.name; FileClip.cut = true; Shared.say("Cut. Open a folder and tap Paste here") }
                            "delete" -> deleting = e
                            "path" -> { clipboard.setText(AnnotatedString(path + e.name)); Shared.say("Path copied") }
                        }
                    }
                )
            }
        }
    }

    renaming?.let { e ->
        TextPromptDialog("Rename", "New name", e.name, "Rename",
            onConfirm = { n -> renaming = null; shell("mv ${shq(path + e.name)} ${shq(path + n)}", "Renamed") },
            onDismiss = { renaming = null })
    }
    deleting?.let { e ->
        ConfirmDialog("Delete ${e.name}?", "This can't be undone.", "Delete",
            onConfirm = { deleting = null; shell("rm -rf ${shq(path + e.name)}", "Deleted") },
            onDismiss = { deleting = null })
    }
    if (newFolder) {
        TextPromptDialog("New folder", "Folder name", "", "Create",
            onConfirm = { n -> newFolder = false; shell("mkdir -p ${shq(path + n)}", "Folder created") },
            onDismiss = { newFolder = false })
    }
}

@Composable
private fun FileRow(e: FileEntry, onOpen: () -> Unit, onAction: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val (icon, tint) = iconFor(e)
    Surface(
        onClick = { if (e.isDir) onOpen() else menu = true },
        shape = RoundedCornerShape(16.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon, tint, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = (if (e.isDir) "Folder" else e.size?.let { formatSize(it) } ?: "File") + (if (e.date.isBlank()) "" else "  ·  " + e.date)
                Text(sub, fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!e.isDir) FileMenuItem("Download to phone", Icons.Default.Download) { menu = false; onAction("download") }
                    FileMenuItem("Rename", Icons.Default.Edit) { menu = false; onAction("rename") }
                    FileMenuItem("Copy", Icons.Default.ContentCopy) { menu = false; onAction("copy") }
                    FileMenuItem("Cut", Icons.Default.ContentCut) { menu = false; onAction("cut") }
                    FileMenuItem("Copy path", Icons.Default.ContentCopy) { menu = false; onAction("path") }
                    FileMenuItem("Delete", Icons.Default.Delete) { menu = false; onAction("delete") }
                }
            }
        }
    }
}

@Composable
private fun FileMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}
