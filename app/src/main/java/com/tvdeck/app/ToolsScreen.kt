@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private class Tool(val id: String, val title: String, val sub: String, val icon: ImageVector, val color: Color)

@Composable
fun ToolsScreen() {
    var sub by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = sub.isNotEmpty()) { sub = "" }
    when (sub) {
        "install" -> InstallTool { sub = "" }
        "shell" -> ShellTool { sub = "" }
        "info" -> InfoTool { sub = "" }
        "live" -> LiveViewTool { sub = "" }
        "tune" -> TuneTool { sub = "" }
        else -> ToolsHome { sub = it }
    }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────── HOME ─────────────────────────
@Composable
private fun ToolsHome(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme

    var shot by remember { mutableStateOf<Frame?>(null) }
    var showLink by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showPower by remember { mutableStateOf(false) }
    var confirmPower by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun takeShot() {
        scope.launch {
            try {
                Shared.say("Capturing…")
                val f = AdbManager.capture(1)
                if (f == null) Shared.say("The screenshot was empty") else shot = f
            } catch (e: Exception) { Shared.say(e.message ?: "Screenshot failed") }
        }
    }

    val tools = listOf(
        Tool("install", "Install apps", "APK, XAPK, APKS bundles", Icons.Default.Download, Color(0xFF0E7C7B)),
        Tool("shot", "Screenshot", "Capture the TV screen", Icons.Default.PhotoCamera, Color(0xFF4F6BED)),
        Tool("record", if (AdbManager.recording) "Stop recording" else "Screen record",
            if (AdbManager.recording) "Tap to stop and save" else "Record up to 3 minutes",
            if (AdbManager.recording) Icons.Default.Stop else Icons.Default.Videocam, Color(0xFFE11D48)),
        Tool("live", "Live view", "See and tap the TV screen", Icons.Default.Cast, Color(0xFF7C3AED)),
        Tool("shell", "Shell", "Run commands, save shortcuts", Icons.Default.Code, Color(0xFF475569)),
        Tool("info", "Device info", "Storage, memory, network", Icons.Default.Info, Color(0xFF0284C7)),
        Tool("tune", "Speed & display", "Free memory, animations", Icons.Default.Tune, Color(0xFF059669)),
        Tool("link", "Open link on TV", "Send a URL or video", Icons.Default.Link, Color(0xFFD97706)),
        Tool("sleep", "Sleep timer", "Turn the TV off later", Icons.Default.NightsStay, Color(0xFF6366F1)),
        Tool("power", "Power menu", "Sleep, wake, reboot", Icons.Default.PowerSettingsNew, Color(0xFFDC2626))
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Tools", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (AdbManager.isConnected) AdbManager.deviceName else "Connect a TV to use these",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
        }
        items(tools, key = { it.id }) { t ->
            TvCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (!AdbManager.isConnected) {
                        Shared.say("Connect to a TV first")
                    } else when (t.id) {
                        "shot" -> takeShot()
                        "record" -> AdbManager.toggleRecording()
                        "link" -> showLink = true
                        "sleep" -> showSleep = true
                        "power" -> showPower = true
                        else -> open(t.id)
                    }
                }
            ) {
                IconBadge(t.icon, t.color)
                Spacer(Modifier.height(10.dp))
                Text(t.title, fontWeight = FontWeight.SemiBold)
                Text(t.sub, fontSize = 12.sp, color = cs.onSurfaceVariant, minLines = 2, maxLines = 2)
            }
        }
    }

    shot?.let { f ->
        ShotDialog(f, onRetake = { shot = null; takeShot() }, onClose = { shot = null })
    }

    if (showLink) {
        val pre = clip.getText()?.text?.takeIf { it.startsWith("http") } ?: ""
        TextPromptDialog("Open link on TV", "https://…", pre, "Open",
            onConfirm = { url ->
                showLink = false
                AdbManager.runBg("am start -a android.intent.action.VIEW -d ${shq(url)}", "Sent to TV")
            },
            onDismiss = { showLink = false })
    }

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    Text("The TV itself keeps the timer, so you can close this app.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    listOf("15 minutes" to 15, "30 minutes" to 30, "1 hour" to 60, "2 hours" to 120).forEach { (label, mins) ->
                        TextButton(onClick = {
                            showSleep = false
                            scope.launch {
                                try {
                                    AdbManager.shell("pkill -f 'tvdeck_[s]leep' || true")
                                    AdbManager.shell("nohup sh -c 'sleep ${mins * 60}; input keyevent 223 #tvdeck_sleep' >/dev/null 2>&1 &")
                                    Shared.say("TV will go to sleep in $label")
                                } catch (e: Exception) { Shared.say(e.message ?: "Failed") }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                    TextButton(onClick = {
                        showSleep = false
                        AdbManager.runBg("pkill -f 'tvdeck_[s]leep' || true", "Sleep timer cancelled")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Cancel timer", color = cs.error) }
                }
            },
            confirmButton = { TextButton(onClick = { showSleep = false }) { Text("Close") } }
        )
    }

    if (showPower) {
        AlertDialog(
            onDismissRequest = { showPower = false },
            title = { Text("Power menu") },
            text = {
                Column {
                    TextButton(onClick = { showPower = false; AdbManager.runBg("input keyevent 223", "Sleep sent") }, modifier = Modifier.fillMaxWidth()) { Text("Sleep") }
                    TextButton(onClick = { showPower = false; AdbManager.runBg("input keyevent 224", "Wake sent") }, modifier = Modifier.fillMaxWidth()) { Text("Wake up") }
                    TextButton(onClick = { showPower = false; confirmPower = "Reboot the TV?" to "reboot" }, modifier = Modifier.fillMaxWidth()) { Text("Reboot") }
                    TextButton(onClick = { showPower = false; confirmPower = "Power off the TV?" to "reboot -p" }, modifier = Modifier.fillMaxWidth()) { Text("Power off") }
                }
            },
            confirmButton = { TextButton(onClick = { showPower = false }) { Text("Close") } }
        )
    }

    confirmPower?.let { (q, c) ->
        ConfirmDialog(q, "You may need to turn it back on with the physical remote.", "Yes",
            onConfirm = { confirmPower = null; AdbManager.runBg(c, "Sent") },
            onDismiss = { confirmPower = null })
    }
}

@Composable
private fun ShotDialog(frame: Frame, onRetake: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun save(): Saved {
        val f = File(ctx.cacheDir, "tv_${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) { FileOutputStream(f).use { frame.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        return withContext(Dispatchers.IO) { saveFile(ctx, f, f.name, "image/png", "image") }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Screenshot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    bitmap = frame.bitmap.asImageBitmap(),
                    contentDescription = "TV screenshot",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            try { Shared.say("Saved to ${save().label}") } catch (e: Exception) { Shared.say(e.message ?: "Couldn't save") }
                        }
                    }) { Text("Save") }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            try {
                                val s = save()
                                val uri = s.uri
                                if (uri == null) Shared.say("Saved at ${s.label}")
                                else {
                                    val i = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(Intent.createChooser(i, "Share screenshot"))
                                }
                            } catch (e: Exception) { Shared.say(e.message ?: "Couldn't share") }
                        }
                    }) { Text("Share") }
                    OutlinedButton(onClick = onRetake) { Text("Retake") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

// ───────────────────────── INSTALL ─────────────────────────
private fun buildInstallJobs(ctx: Context, uris: List<Uri>, dir: File, asOne: Boolean): List<Pair<String, List<File>>> {
    val jobs = ArrayList<Pair<String, List<File>>>()
    val loose = ArrayList<File>()
    uris.forEachIndexed { idx, uri ->
        val name = displayName(ctx, uri)
        val lower = name.lowercase()
        if (lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apkm")) {
            val parts = ArrayList<File>()
            ZipInputStream(ctx.contentResolver.openInputStream(uri)!!).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.lowercase().endsWith(".apk")) {
                        val f = File(dir, "${idx}_" + e.name.substringAfterLast('/'))
                        f.outputStream().use { zin.copyTo(it) }
                        parts.add(f)
                    }
                    e = zin.nextEntry
                }
            }
            if (parts.isNotEmpty()) jobs.add(name to parts)
        } else {
            val f = File(dir, "${idx}_$name")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            if (asOne) loose.add(f) else jobs.add(name to listOf(f))
        }
    }
    if (loose.isNotEmpty()) jobs.add("${loose.size} split parts" to loose)
    return jobs
}

@Composable
private fun InstallTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var asOne by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            log = emptyList()
            try {
                progress = "Preparing files…"
                val dir = File(ctx.cacheDir, "install").apply { deleteRecursively(); mkdirs() }
                val jobs = withContext(Dispatchers.IO) { buildInstallJobs(ctx, uris, dir, asOne) }
                if (jobs.isEmpty()) log = listOf("❌ No APK files found in your selection")
                jobs.forEachIndexed { i, (name, files) ->
                    progress = "Installing ${i + 1}/${jobs.size}: $name"
                    try {
                        AdbManager.install(files)
                        log = log + "✅ $name"
                    } catch (e: Exception) {
                        log = log + "❌ $name — ${(e.message ?: "failed").take(160)}"
                    }
                }
            } catch (e: Exception) {
                log = log + "❌ ${e.message}"
            }
            progress = ""
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubHeader("Install apps", onBack)
        TvCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Download, cs.primary, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Send apps from your phone", fontWeight = FontWeight.SemiBold)
                    Text("APK, XAPK, APKS and APKM files", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Choose files…")
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = asOne, onCheckedChange = { asOne = it })
                Spacer(Modifier.width(10.dp))
                Text("Selected files are parts of ONE app (split APKs)", fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(progress, fontSize = 13.sp)
        }
        log.forEach { Text(it, fontSize = 13.sp) }
        Text(
            "Tip: extra OBB data inside XAPK files is not copied. Installing over an existing app keeps its data.",
            fontSize = 12.sp, color = cs.onSurfaceVariant
        )
    }
}

// ───────────────────────── SHELL ─────────────────────────
private val PRESETS = listOf(
    "Foreground app" to "dumpsys activity activities | grep mResumedActivity",
    "IP address" to "ip -f inet addr",
    "Properties" to "getprop",
    "Running apps" to "ps -A | head -40",
    "User apps" to "pm list packages -3"
)

@Composable
private fun ShellTool(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    var cmd by rememberSaveable { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun runIt(c: String) {
        if (c.isBlank()) return
        scope.launch {
            running = true
            Store.addHistory(c)
            val result = try { AdbManager.shell(c).ifBlank { "(no output)" } } catch (e: Exception) { "Error: ${e.message}" }
            out = "\$ $c\n$result"
            running = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubHeader("Shell", onBack)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (label, c) ->
                AssistChip(onClick = { cmd = c; runIt(c) }, label = { Text(label) })
            }
            Store.macros.forEach { m ->
                InputChip(
                    selected = false,
                    onClick = { cmd = m.second; runIt(m.second) },
                    label = { Text(m.first) },
                    trailingIcon = {
                        Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp).clickable { Store.removeMacro(m) })
                    }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cmd, onValueChange = { cmd = it },
                placeholder = { Text("Type a command, e.g. ls /sdcard") },
                singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)
            )
            FilledIconButton(onClick = { runIt(cmd) }, enabled = !running) { Icon(Icons.Default.PlayArrow, "Run") }
        }

        if (Store.history.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Store.history.take(6).forEach { h ->
                    SuggestionChip(onClick = { cmd = h }, label = { Text(h, maxLines = 1) })
                }
            }
        }

        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xFF0B1514)) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                if (running) CircularProgressIndicator(Modifier.size(22.dp).align(Alignment.TopEnd), strokeWidth = 2.dp)
                SelectionContainer {
                    Text(
                        out.ifEmpty { "Output appears here." },
                        color = Color(0xFFB8F5EC), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clip.setText(AnnotatedString(out)); Shared.say("Output copied") }, enabled = out.isNotEmpty()) { Text("Copy output") }
            OutlinedButton(onClick = { out = "" }, enabled = out.isNotEmpty()) { Text("Clear") }
            OutlinedButton(onClick = { saving = true }, enabled = cmd.isNotBlank()) { Text("Save command") }
        }
    }

    if (saving) {
        TextPromptDialog("Save command", "Button name", "", "Save",
            onConfirm = { n -> Store.addMacro(n, cmd); saving = false },
            onDismiss = { saving = false })
    }
}

// ───────────────────────── INFO ─────────────────────────
private class TvInfo(
    val device: String, val android: String, val build: String, val abi: String,
    val res: String, val density: String,
    val storageUsed: Long, val storageTotal: Long, val memUsed: Long, val memTotal: Long,
    val load: Float, val cores: Int, val uptime: String,
    val ip: String, val wifiMac: String, val ethMac: String
)

private suspend fun fetchInfo(): TvInfo {
    val cmd = listOf(
        "echo MODEL=\$(getprop ro.product.model)",
        "echo BRAND=\$(getprop ro.product.manufacturer)",
        "echo AND=\$(getprop ro.build.version.release)",
        "echo SDK=\$(getprop ro.build.version.sdk)",
        "echo BUILD=\$(getprop ro.build.display.id)",
        "echo ABI=\$(getprop ro.product.cpu.abi)",
        "echo SIZE=\$(wm size | tail -1)",
        "echo DENS=\$(wm density | tail -1)",
        "echo DF=\$(df -k /data | tail -1)",
        "echo MEMT=\$(grep MemTotal /proc/meminfo)",
        "echo MEMA=\$(grep MemAvailable /proc/meminfo)",
        "echo LOAD=\$(cat /proc/loadavg)",
        "echo CORES=\$(grep -c ^processor /proc/cpuinfo)",
        "echo UP=\$(cat /proc/uptime)",
        "echo IP=\$(ip -f inet addr show 2>/dev/null | grep inet | grep -v 127.0.0.1 | head -1)",
        "echo MAC=\$(cat /sys/class/net/wlan0/address 2>/dev/null)",
        "echo ETH=\$(cat /sys/class/net/eth0/address 2>/dev/null)"
    ).joinToString("; ")
    val map = HashMap<String, String>()
    AdbManager.shell(cmd).lines().forEach { l ->
        val i = l.indexOf('=')
        if (i > 0) map[l.substring(0, i)] = l.substring(i + 1).trim()
    }
    fun num(s: String?): Long = Regex("\\d+").find(s ?: "")?.value?.toLongOrNull() ?: 0L
    val df = (map["DF"] ?: "").split(Regex("\\s+"))
    val total = if (df.size >= 4) (df[1].toLongOrNull() ?: 0L) * 1024 else 0L
    val used = if (df.size >= 4) (df[2].toLongOrNull() ?: 0L) * 1024 else 0L
    val memT = num(map["MEMT"]) * 1024
    val memA = num(map["MEMA"]) * 1024
    val cores = (map["CORES"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val load1 = (map["LOAD"] ?: "").split(" ").firstOrNull()?.toFloatOrNull() ?: 0f
    val up = ((map["UP"] ?: "").split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0).toLong()
    val ip = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(map["IP"] ?: "")?.groupValues?.get(1) ?: "—"
    val brand = (map["BRAND"] ?: "").replaceFirstChar { it.uppercase() }
    return TvInfo(
        device = (brand + " " + (map["MODEL"] ?: "")).trim(),
        android = (map["AND"] ?: "?") + "  (API " + (map["SDK"] ?: "?") + ")",
        build = map["BUILD"] ?: "—",
        abi = map["ABI"] ?: "—",
        res = (map["SIZE"] ?: "").substringAfter(": ", "—"),
        density = (map["DENS"] ?: "").substringAfter(": ", "—"),
        storageUsed = used, storageTotal = total, memUsed = (memT - memA).coerceAtLeast(0), memTotal = memT,
        load = (load1 / cores).coerceIn(0f, 1f), cores = cores, uptime = formatUptime(up),
        ip = ip, wifiMac = (map["MAC"] ?: "").ifBlank { "—" }, ethMac = (map["ETH"] ?: "").ifBlank { "—" }
    )
}

@Composable
private fun InfoTool(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var info by remember { mutableStateOf<TvInfo?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            try { info = fetchInfo(); error = "" } catch (e: Exception) { error = e.message ?: "Couldn't read device info" }
            delay(5000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubHeader("Device info", onBack)
        if (error.isNotEmpty()) Text(error, color = cs.error, fontSize = 13.sp)
        val i = info
        if (i == null) {
            if (error.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            TvCard {
                Text(i.device.ifBlank { "Your TV" }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Android ${i.android}", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
            TvCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Gauge(
                        if (i.storageTotal > 0) i.storageUsed.toFloat() / i.storageTotal else 0f,
                        formatSize(i.storageUsed), "Storage", cs.primary
                    )
                    Gauge(
                        if (i.memTotal > 0) i.memUsed.toFloat() / i.memTotal else 0f,
                        formatSize(i.memUsed), "Memory", cs.secondary
                    )
                    Gauge(i.load, "${(i.load * 100).toInt()}%", "CPU load", cs.tertiary)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("of ${formatSize(i.storageTotal)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    Text("of ${formatSize(i.memTotal)}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    Text("${i.cores} cores", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
            }
            TvCard {
                InfoRow("IP address", i.ip)
                InfoRow("Wi-Fi MAC", i.wifiMac)
                InfoRow("Ethernet MAC", i.ethMac)
                InfoRow("Resolution", i.res)
                InfoRow("Density", i.density)
                InfoRow("CPU type", i.abi)
                InfoRow("Uptime", i.uptime)
                InfoRow("Build", i.build)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

// ───────────────────────── LIVE VIEW ─────────────────────────
@Composable
private fun LiveViewTool(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var running by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf<Frame?>(null) }
    var fps by remember { mutableStateOf("") }

    LaunchedEffect(running) {
        while (running) {
            val t0 = System.currentTimeMillis()
            try {
                val f = AdbManager.capture(2)
                if (f != null) frame = f
            } catch (e: Exception) {
                Shared.say(e.message ?: "Capture failed")
                running = false
            }
            val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            fps = "%.1f fps".format(1000f / dt)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubHeader("Live view", onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tap or swipe the picture to control the TV. Speed depends on your TV (about $fps).",
                fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = { running = !running }) { Text(if (running) "Pause" else "Resume") }
        }
        val f = frame
        if (f == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val ratio = f.bitmap.width.toFloat() / f.bitmap.height.toFloat()
            val img = remember(f) { f.bitmap.asImageBitmap() }
            Image(
                bitmap = img,
                contentDescription = "TV screen",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(16.dp))
                    .pointerInput(f.fullW, f.fullH) {
                        detectTapGestures { o ->
                            AdbManager.tap((o.x / size.width * f.fullW).toInt(), (o.y / size.height * f.fullH).toInt())
                        }
                    }
                    .pointerInput(f.fullW, f.fullH) {
                        var sx = 0f
                        var sy = 0f
                        var ex = 0f
                        var ey = 0f
                        detectDragGestures(
                            onDragStart = { sx = it.x; sy = it.y; ex = it.x; ey = it.y },
                            onDrag = { change, d -> change.consume(); ex += d.x; ey += d.y },
                            onDragEnd = {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                AdbManager.swipe(
                                    (sx / w * f.fullW).toInt(), (sy / h * f.fullH).toInt(),
                                    (ex / w * f.fullW).toInt(), (ey / h * f.fullH).toInt()
                                )
                            }
                        )
                    },
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

// ───────────────────────── SPEED & DISPLAY ─────────────────────────
@Composable
private fun TuneCard(title: String, desc: String, content: @Composable RowScope.() -> Unit) {
    TvCard {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun TuneTool(onBack: () -> Unit) {
    fun anim(x: String, label: String) = AdbManager.runBg(
        "settings put global window_animation_scale $x; settings put global transition_animation_scale $x; settings put global animator_duration_scale $x",
        label
    )
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubHeader("Speed & display", onBack)

        TuneCard("Free up memory", "Closes background apps and clears caches. Great when the TV feels slow.") {
            Button(onClick = { AdbManager.runBg("am kill-all; pm trim-caches 999G", "Memory freed") }) { Text("Clean now") }
        }
        TuneCard("Animation speed", "Faster animations make menus feel snappier.") {
            FilledTonalButton(onClick = { anim("0", "Animations off") }) { Text("Off") }
            FilledTonalButton(onClick = { anim("0.5", "Animations 2× faster") }) { Text("Fast") }
            OutlinedButton(onClick = { anim("1", "Animations back to normal") }) { Text("Normal") }
        }
        TuneCard("Screensaver", "Turn the screensaver on or off.") {
            FilledTonalButton(onClick = { AdbManager.runBg("settings put secure screensaver_enabled 0", "Screensaver off") }) { Text("Off") }
            OutlinedButton(onClick = { AdbManager.runBg("settings put secure screensaver_enabled 1", "Screensaver on") }) { Text("On") }
        }
        TuneCard("Display size", "Change the TV’s UI resolution. If the picture looks wrong, press Reset.") {
            FilledTonalButton(onClick = { AdbManager.runBg("wm size 1920x1080", "Set to 1080p") }) { Text("1080p") }
            FilledTonalButton(onClick = { AdbManager.runBg("wm size 1280x720", "Set to 720p") }) { Text("720p") }
            OutlinedButton(onClick = { AdbManager.runBg("wm size reset; wm density reset", "Display reset") }) { Text("Reset") }
        }
    }
}
