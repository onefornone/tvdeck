@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class Risk { SAFE, CAREFUL, KEEP }

private class Cand(val app: AppItem, val risk: Risk, val reason: String, val size: Long, val running: Boolean, val curated: Boolean)

private val CURATED = mapOf(
    "com.google.android.videos" to "Google TV / Play Movies app. Safe to turn off if you don't buy or rent there.",
    "com.google.android.youtube.tvmusic" to "YouTube Music for TV.",
    "com.google.android.play.games" to "Google Play Games.",
    "com.google.android.feedback" to "Sends feedback reports to Google.",
    "com.google.android.retaildemo" to "Store demo mode.",
    "com.android.retaildemo" to "Store demo mode.",
    "com.android.egg" to "Hidden Android Easter egg.",
    "com.android.bips" to "Built-in printing service. Not needed on a TV.",
    "com.android.printspooler" to "Printing. Not needed on a TV.",
    "com.android.dreams.phototable" to "Photo-table screensaver.",
    "com.amazon.ags.app" to "Amazon GameCircle (old game service)."
)

private val PROTECT_PREFIX = listOf(
    "android", "com.android.", "com.google.android.gms", "com.google.android.gsf", "com.google.android.tv",
    "com.google.android.inputmethod", "com.google.android.webview", "com.google.android.packageinstaller",
    "com.google.android.permissioncontroller", "com.google.android.networkstack", "com.google.android.tts",
    "com.google.android.ext", "com.google.android.apps.tv", "com.google.android.leanbacklauncher",
    "com.amazon.tv.", "com.amazon.device", "com.amazon.dcp", "com.amazon.firehomestarter", "com.amazon.settings",
    "com.amazon.providers", "com.amazon.imp", "com.amazon.bueller", "com.amazon.ceviche", "com.amazon.whisperplay",
    "com.amazon.android.", "com.tvdeck."
)

private val PROTECT_WORDS = listOf(
    "launcher", "systemui", "settings", "provider", "inputmethod", "bluetooth", "webview", "keychain",
    "frameworks", "overlay", "wifi", "ethernet", "setupwizard", "bootstrap", "tvinput", "tvsystem", "cec",
    "hdmi", "update", "permission", "certinstaller"
)

private fun classify(a: AppItem): Pair<Risk, String> {
    CURATED[a.pkg]?.let { return Risk.SAFE to it }
    val p = a.pkg.lowercase()
    if (PROTECT_PREFIX.any { p.startsWith(it) } || PROTECT_WORDS.any { p.contains(it) }) {
        return Risk.KEEP to "Needed by the system"
    }
    return if (a.system) Risk.CAREFUL to "Pre-installed app. Usually fine to turn off, but check what it is first."
    else Risk.SAFE to "An app you installed. Safe to turn off or remove."
}

private fun dName(pkg: String): String = AppMeta.labels[pkg]?.takeIf { it.isNotBlank() } ?: prettyName(pkg)

@Composable
fun DebloatTool(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var loading by remember { mutableStateOf(true) }
    var cands by remember { mutableStateOf<List<Cand>>(emptyList()) }
    var disabledApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    val selected = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf<String?>(null) } // "off" or "remove"
    var working by remember { mutableStateOf(false) }

    suspend fun analyze() {
        loading = true
        try {
            if (!AppsCache.loaded) loadApps()
            val apps = AppsCache.apps
            val pathSize = HashMap<String, Long>()
            for (chunk in apps.filter { it.path.isNotEmpty() }.chunked(40)) {
                val out = AdbManager.shell("stat -c '%s %n' " + chunk.joinToString(" ") { shq(it.path) } + " 2>/dev/null")
                for (l in out.lines()) {
                    val sp = l.indexOf(' ')
                    if (sp > 0) {
                        val sz = l.substring(0, sp).toLongOrNull()
                        if (sz != null) pathSize[l.substring(sp + 1).trim()] = sz
                    }
                }
            }
            val ps = try { AdbManager.shell("ps -A 2>/dev/null || ps").lowercase() } catch (e: Exception) { "" }
            cands = apps.filter { !it.disabled }.map { a ->
                val (risk, why) = classify(a)
                Cand(a, risk, why, pathSize[a.path] ?: 0L, ps.contains(a.pkg.lowercase()), CURATED.containsKey(a.pkg))
            }
            disabledApps = apps.filter { it.disabled }
        } catch (e: Exception) {
            Shared.say(e.message ?: "Couldn't analyze apps")
        }
        loading = false
    }

    LaunchedEffect(AdbManager.isConnected) {
        if (AdbManager.isConnected) analyze() else loading = false
    }

    val recommended = remember(cands) { cands.filter { it.curated } }
    val biggest = remember(cands) { cands.filter { !it.app.system && it.risk != Risk.KEEP }.sortedByDescending { it.size }.take(15) }
    val optional = remember(cands) {
        cands.filter { it.app.system && !it.curated && it.risk == Risk.CAREFUL }.sortedBy { dName(it.app.pkg).lowercase() }
    }
    val selectedSize = cands.filter { it.app.pkg in selected }.sumOf { it.size }

    fun apply(remove: Boolean) {
        val list = cands.filter { it.app.pkg in selected }
        selected.clear()
        scope.launch {
            working = true
            var ok = 0
            for (c in list) {
                try {
                    val cmd = if (remove) "pm uninstall --user 0 ${c.app.pkg}" else "pm disable-user --user 0 ${c.app.pkg}"
                    val out = AdbManager.shell(cmd + " 2>&1")
                    if (errorLine(out) == null) {
                        ok++
                        if (!remove) Store.markRemoved(c.app.pkg, "disabled")
                        else if (c.app.system) Store.markRemoved(c.app.pkg, "removed")
                    }
                } catch (_: Exception) {}
            }
            Shared.say(if (remove) "Removed $ok of ${list.size} apps" else "Turned off $ok of ${list.size} apps")
            loadApps()
            analyze()
            working = false
        }
    }

    fun restore(pkg: String) {
        scope.launch {
            working = true
            try {
                val method = Store.removed.firstOrNull { it.first == pkg }?.second ?: "disabled"
                val out = if (method == "removed") {
                    AdbManager.shell("cmd package install-existing $pkg 2>&1; pm install-existing $pkg 2>&1")
                } else {
                    AdbManager.shell("pm enable $pkg 2>&1")
                }
                if (errorLine(out) != null && method != "removed") Shared.say("Couldn't restore: " + (errorLine(out) ?: ""))
                else Shared.say("Restored ${dName(pkg)}")
                Store.unmarkRemoved(pkg)
                loadApps()
                analyze()
            } catch (e: Exception) {
                Shared.say(e.message ?: "Couldn't restore")
            }
            working = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Smart debloater", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (loading || working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { loadApps(); analyze() } }) { Icon(Icons.Default.Refresh, "Refresh") }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                TvCard {
                    Text("Free up space and speed", fontWeight = FontWeight.Bold)
                    Text(
                        "TVDeck never lists apps the TV needs to work. Turning an app off is fully reversible: use Restore at the bottom of this page.",
                        fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            if (!AdbManager.isConnected) {
                item { EmptyState(Icons.Default.DeleteSweep, "Connect a TV first", "The debloater analyzes the apps on your TV.") }
            } else if (loading) {
                item { EmptyState(Icons.Default.DeleteSweep, "Analyzing your apps…", "This takes a few seconds.") }
            } else {
                if (recommended.isNotEmpty()) {
                    item { SectionTitle("Recommended to turn off") }
                    items(recommended, key = { "r-" + it.app.pkg }) { c -> CandRow(c, c.app.pkg in selected) { toggle(selected, c.app.pkg) } }
                }
                if (biggest.isNotEmpty()) {
                    item { SectionTitle("Your biggest apps") }
                    items(biggest, key = { "b-" + it.app.pkg }) { c -> CandRow(c, c.app.pkg in selected) { toggle(selected, c.app.pkg) } }
                }
                if (optional.isNotEmpty()) {
                    item { SectionTitle("Optional pre-installed apps") }
                    items(optional, key = { "o-" + it.app.pkg }) { c -> CandRow(c, c.app.pkg in selected) { toggle(selected, c.app.pkg) } }
                }
                item { SectionTitle("Turned off · tap Restore to undo") }
                if (disabledApps.isEmpty() && Store.removed.isEmpty()) {
                    item { Text("Nothing turned off yet.", fontSize = 13.sp, color = cs.onSurfaceVariant) }
                }
                items(disabledApps, key = { "d-" + it.pkg }) { a ->
                    RestoreRow(a.pkg, "Turned off") { restore(a.pkg) }
                }
                items(Store.removed.filter { r -> disabledApps.none { it.pkg == r.first } && r.second == "removed" }, key = { "x-" + it.first }) { r ->
                    RestoreRow(r.first, "Removed for you") { restore(r.first) }
                }
            }
        }

        if (selected.isNotEmpty()) {
            Surface(color = cs.surface, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${selected.size} selected", fontWeight = FontWeight.SemiBold)
                        if (selectedSize > 0) Text("~" + formatSize(selectedSize), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { confirm = "remove" }) { Text("Remove") }
                    Button(onClick = { confirm = "off" }) { Text("Turn off") }
                }
            }
        }
    }

    confirm?.let { mode ->
        val remove = mode == "remove"
        val names = cands.filter { it.app.pkg in selected }
        val hasUser = names.any { !it.app.system }
        ConfirmDialog(
            title = if (remove) "Remove ${selected.size} apps?" else "Turn off ${selected.size} apps?",
            text = if (remove) {
                "Pre-installed apps can be restored later. Apps you installed yourself are uninstalled for good" +
                    (if (hasUser) " (you'd have to install them again)." else ".")
            } else "They will be hidden and stop running. You can restore them any time.",
            confirmLabel = if (remove) "Remove" else "Turn off",
            onConfirm = { confirm = null; apply(remove) },
            onDismiss = { confirm = null }
        )
    }
}

private fun toggle(list: MutableList<String>, pkg: String) {
    if (pkg in list) list.remove(pkg) else list.add(pkg)
}

@Composable
private fun CandRow(c: Cand, checked: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    DisposableEffect(c.app.pkg) {
        AppMeta.request(c.app)
        onDispose { }
    }
    val icon = AppMeta.icons[c.app.pkg]
    val shape = RoundedCornerShape(16.dp)
    val tag = when (c.risk) {
        Risk.SAFE -> "Safe" to Color(0xFF16A34A)
        Risk.CAREFUL -> "Check first" to Color(0xFFD97706)
        Risk.KEEP -> "Keep" to Color(0xFFDC2626)
    }
    Surface(
        onClick = onToggle,
        shape = shape,
        color = if (checked) cs.primaryContainer else cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(checked = checked, onCheckedChange = null)
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
            } else {
                val col = avatarColor(c.app.pkg)
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(col.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text(dName(c.app.pkg).take(1).uppercase(), color = col, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(dName(c.app.pkg), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.reason, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val extra = buildString {
                    if (c.size > 0) append(formatSize(c.size))
                    if (c.running) { if (isNotEmpty()) append("  ·  "); append("running now") }
                }
                if (extra.isNotEmpty()) Text(extra, fontSize = 11.sp, color = cs.primary)
            }
            Text(tag.first, color = tag.second, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RestoreRow(pkg: String, note: String, onRestore: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surface, border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dName(pkg), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$note  ·  $pkg", fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}
