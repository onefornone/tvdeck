@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.tvdeck.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppItem(val pkg: String, val system: Boolean, val disabled: Boolean, val path: String = "")

/** Keeps the app list so switching tabs is instant. */
object AppsCache {
    var apps by mutableStateOf<List<AppItem>>(emptyList())
    var loaded by mutableStateOf(false)
}

private class Confirm(val title: String, val text: String, val label: String, val onYes: () -> Unit)

/** Parses "package:/path/base.apk=com.example" (or just "package:com.example"). */
private fun parsePkgs(out: String): List<Pair<String, String>> =
    out.lines().map { it.trim() }.filter { it.startsWith("package:") }.map {
        val body = it.removePrefix("package:")
        val eq = body.lastIndexOf('=')
        if (eq > 0) body.substring(eq + 1) to body.substring(0, eq) else body to ""
    }

private suspend fun loadApps() = coroutineScope {
    val u = async { AdbManager.shell("pm list packages -f -3") }
    val s = async { AdbManager.shell("pm list packages -f -s") }
    val d = async { AdbManager.shell("pm list packages -d") }
    val disabled = parsePkgs(d.await()).map { it.first }.toSet()
    val user = parsePkgs(u.await()).map { AppItem(it.first, false, it.first in disabled, it.second) }
    val sys = parsePkgs(s.await()).map { AppItem(it.first, true, it.first in disabled, it.second) }
    AppsCache.apps = (user + sys).distinctBy { it.pkg }
    AppsCache.loaded = true
}

private fun nameOf(pkg: String): String = AppMeta.labels[pkg]?.takeIf { it.isNotBlank() } ?: prettyName(pkg)

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(1) } // 0 all, 1 user, 2 system, 3 disabled
    var loading by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val apps = AppsCache.apps

    fun reload() {
        scope.launch {
            loading = true
            try { loadApps() } catch (e: Exception) { Shared.say(e.message ?: "Couldn't load apps") }
            loading = false
        }
    }

    LaunchedEffect(AdbManager.isConnected) {
        if (AdbManager.isConnected && !AppsCache.loaded) reload()
    }

    fun runCmd(cmd: String, ok: String, thenReload: Boolean = false) {
        scope.launch {
            try {
                val out = AdbManager.shell(cmd)
                val err = errorLine(out)
                if (err != null) Shared.say("Failed: " + err.take(120)) else if (ok.isNotEmpty()) Shared.say(ok)
                if (thenReload) loadApps()
            } catch (e: Exception) { Shared.say(e.message ?: "Failed") }
        }
    }

    fun extract(p: String) {
        scope.launch {
            try {
                Shared.say("Extracting APK…")
                val path = AdbManager.shell("pm path $p").lines().map { it.trim() }
                    .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")
                    ?: throw IllegalStateException("APK not found")
                val f = File(ctx.cacheDir, "$p.apk")
                AdbManager.pull(path, f)
                val s = withContext(Dispatchers.IO) {
                    saveFile(ctx, f, "$p.apk", "application/vnd.android.package-archive", "download")
                }
                Shared.say("Saved to ${s.label}")
            } catch (e: Exception) { Shared.say(e.message ?: "Couldn't extract APK") }
        }
    }

    fun act(key: String, app: AppItem) {
        val p = app.pkg
        val n = nameOf(p)
        when (key) {
            "open" -> AdbManager.launchApp(p)
            "stop" -> runCmd("am force-stop $p", "Stopped $n")
            "info" -> runCmd("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$p", "")
            "copy" -> { clipboard.setText(AnnotatedString(p)); Shared.say("Package name copied") }
            "extract" -> extract(p)
            "clear" -> confirm = Confirm("Clear data?", "All data of $n (logins, settings) will be erased.", "Clear") {
                runCmd("pm clear $p", "Data cleared")
            }
            "toggle" -> runCmd(
                if (app.disabled) "pm enable $p" else "pm disable-user --user 0 $p",
                if (app.disabled) "$n enabled" else "$n disabled", true
            )
            "uninstall" -> confirm = Confirm(
                "Uninstall $n?",
                p + if (app.system) "\n\nThis is a SYSTEM app. Removing the wrong one can break your TV." else "",
                "Uninstall"
            ) { runCmd("pm uninstall --user 0 $p", "$n uninstalled", true) }
        }
    }

    val counts = remember(apps) { listOf(apps.size, apps.count { !it.system }, apps.count { it.system }, apps.count { it.disabled }) }
    // re-sort only once all names are known, so the list doesn't jump while it loads
    val labelKey = if (AppMeta.remaining == 0) AppMeta.labels.size else -1
    val shown = remember(apps, filter, query, labelKey) {
        apps.filter { a ->
            val okFilter = when (filter) { 1 -> !a.system; 2 -> a.system; 3 -> a.disabled; else -> true }
            okFilter && (query.isBlank() || a.pkg.contains(query, ignoreCase = true) || nameOf(a.pkg).contains(query, ignoreCase = true))
        }.sortedBy { nameOf(it.pkg).lowercase() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Apps", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (AppMeta.remaining > 0) "${counts[0]} apps  ·  loading names & icons (${AppMeta.remaining} left)"
                        else "${counts[0]} apps on your TV",
                        fontSize = 12.sp, color = cs.onSurfaceVariant
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "Reload") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, "Clear selection") }
                Text("${selected.size} selected", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val list = selected.toList()
                    runCmd(list.joinToString("; ") { "am force-stop $it" }, "Stopped ${list.size} apps")
                    selected.clear()
                }) { Icon(Icons.Default.Stop, "Force stop") }
                IconButton(onClick = {
                    val list = selected.toList()
                    confirm = Confirm("Disable ${list.size} apps?", "They will be hidden and stop running. You can enable them again.", "Disable") {
                        runCmd(list.joinToString("; ") { "pm disable-user --user 0 $it" }, "Disabled ${list.size} apps", true)
                        selected.clear()
                    }
                }) { Icon(Icons.Default.Block, "Disable") }
                IconButton(onClick = {
                    val list = selected.toList()
                    confirm = Confirm("Uninstall ${list.size} apps?", list.joinToString("\n") { nameOf(it) }, "Uninstall") {
                        runCmd(list.joinToString("; ") { "pm uninstall --user 0 $it" }, "Uninstalled ${list.size} apps", true)
                        selected.clear()
                    }
                }) { Icon(Icons.Default.DeleteSweep, "Uninstall") }
            }
        }

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search by name or package") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "User", "System", "Disabled").forEachIndexed { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text("$label  ${counts[i]}") })
            }
        }

        if (!AdbManager.isConnected) {
            EmptyState(Icons.Default.Apps, "Connect a TV first", "Your apps will show up here.")
        } else if (shown.isEmpty() && !loading) {
            EmptyState(Icons.Default.Apps, "Nothing here", "Try another filter, or tap reload.")
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(shown, key = { it.pkg }) { app ->
                DisposableEffect(app.pkg) {
                    AppMeta.request(app)
                    onDispose { AppMeta.cancel(app.pkg) }
                }
                AppRow(
                    app = app,
                    selected = app.pkg in selected,
                    selectionMode = selected.isNotEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) { if (app.pkg in selected) selected.remove(app.pkg) else selected.add(app.pkg) }
                        else act("open", app)
                    },
                    onLong = { if (app.pkg !in selected) selected.add(app.pkg) },
                    onMenu = { act(it, app) }
                )
            }
        }
    }

    confirm?.let { c ->
        ConfirmDialog(c.title, c.text, c.label, onConfirm = { confirm = null; c.onYes() }, onDismiss = { confirm = null })
    }
}

@Composable
private fun AppRow(
    app: AppItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onMenu: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val icon = AppMeta.icons[app.pkg]
    val name = nameOf(app.pkg)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLong),
        shape = shape,
        color = if (selected) cs.primaryContainer else cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Image(
                    bitmap = icon, contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                val c = avatarColor(app.pkg)
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = c, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.pkg, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.disabled) Text("Disabled", fontSize = 11.sp, color = cs.error, fontWeight = FontWeight.SemiBold)
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("Open", Icons.Default.PlayArrow) { menu = false; onMenu("open") }
                        MenuItem("Force stop", Icons.Default.Stop) { menu = false; onMenu("stop") }
                        MenuItem("App info", Icons.Default.Info) { menu = false; onMenu("info") }
                        MenuItem("Clear data", Icons.Default.DeleteSweep) { menu = false; onMenu("clear") }
                        MenuItem("Extract APK to phone", Icons.Default.Download) { menu = false; onMenu("extract") }
                        MenuItem("Copy package name", Icons.Default.ContentCopy) { menu = false; onMenu("copy") }
                        MenuItem(if (app.disabled) "Enable" else "Disable", Icons.Default.Block) { menu = false; onMenu("toggle") }
                        MenuItem("Uninstall", Icons.Default.Delete) { menu = false; onMenu("uninstall") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}
