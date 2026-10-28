@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private val GUIDE = listOf(
    "On the TV open Settings › Device Preferences › About and click “Build” 7 times. (Fire TV: Settings › My Fire TV › About, click the device name 7 times.)",
    "Go back to Developer options and switch on USB debugging / Network debugging. (Fire TV: ADB debugging.)",
    "Connect your phone and TV to the same Wi-Fi. It does not need internet.",
    "When the TV asks, tick “Always allow” and press Allow. You only do this once."
)

@Composable
fun DevicesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    DisposableEffect(Unit) {
        Discovery.start(ctx)
        onDispose { Discovery.stop() }
    }
    var msg by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedDevice?>(null) }
    var removing by remember { mutableStateOf<SavedDevice?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    var showDoctor by remember { mutableStateOf(false) }

    fun connectTo(name: String, host: String, port: Int) {
        scope.launch {
            msg = "Connecting… if your TV shows an “Allow debugging?” popup, tick Always allow and press Allow."
            try {
                val real = AdbManager.connect(host, port)
                Store.upsert(SavedDevice(name.ifBlank { real }, host, port))
                Store.lastHost = host
                msg = ""
            } catch (e: Exception) {
                msg = e.message ?: "Couldn't connect"
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenTitle("TVDeck", "Devices") {
                    IconButton(onClick = { showDoctor = true }) { Icon(Icons.Default.Info, "Connection check") }
                }
            }

            if (NetBinder.wifiConnected && NetBinder.wifiNoInternet) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tone.card()).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Wifi, null, Modifier.size(20.dp), tint = cs.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("This Wi-Fi has no internet. That's fine: TVDeck talks to your TV directly.", fontSize = 12.sp)
                    }
                }
            }

            item { StatusCard(onDisconnect = { AdbManager.disconnect() }) }

            if (msg.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (AdbManager.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(msg, fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                }
            }

            item {
                SectionTitle("Nearby TVs") {
                    TextButton(onClick = {
                        scope.launch {
                            scanning = true
                            Discovery.start(ctx)
                            found = try { AdbManager.scan() } catch (e: Exception) { emptyList() }
                            scanning = false
                            scanned = true
                        }
                    }, enabled = !scanning) {
                        Icon(if (scanning) Icons.Default.Refresh else Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (scanning) "Searching…" else "Search again")
                    }
                }
            }

            if (scanning || Discovery.running && Discovery.found.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            items(Discovery.found, key = { "tv-" + it.host }) { tv ->
                val ready = Discovery.adbReady[tv.host]
                DeviceRow(
                    title = tv.name,
                    subtitle = tv.kind + "  ·  " + tv.host,
                    active = AdbManager.isConnected && AdbManager.deviceHost == tv.host,
                    onClick = { if (!AdbManager.connecting) connectTo(tv.name, tv.host, tv.port) }
                ) {
                    when (ready) {
                        true -> StatusBadge("Debugging on", true)
                        false -> StatusBadge("Enable debugging", false)
                        null -> {}
                    }
                }
            }

            val extra = found.filter { ip -> Discovery.found.none { it.host == ip } }
            items(extra, key = { "ip-$it" }) { host ->
                DeviceRow(
                    title = host,
                    subtitle = "Found on your Wi-Fi",
                    active = AdbManager.isConnected && AdbManager.deviceHost == host,
                    onClick = { if (!AdbManager.connecting) connectTo("", host, 5555) }
                ) { StatusBadge("Debugging on", true) }
            }

            if (!scanning && Discovery.found.isEmpty() && extra.isEmpty() && scanned) {
                item {
                    Text(
                        "No TV found. Make sure the TV is on and on the same Wi-Fi, or add it by its IP address.",
                        fontSize = 13.sp, color = cs.onSurfaceVariant
                    )
                }
            }

            if (Store.devices.isNotEmpty()) {
                item { SectionTitle("My TVs") }
            }
            items(Store.devices, key = { it.host + ":" + it.port }) { d ->
                val active = AdbManager.isConnected && AdbManager.deviceHost == d.host
                DeviceRow(
                    title = d.name,
                    subtitle = d.host + ":" + d.port,
                    active = active,
                    onClick = { if (!AdbManager.connecting) connectTo(d.name, d.host, d.port) }
                ) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menu = false; renaming = d }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menu = false; removing = d }
                            )
                        }
                    }
                }
            }

            item {
                TvCard(onClick = { showGuide = !showGuide }, modifier = Modifier.animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Info, cs.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("First-time setup", fontWeight = FontWeight.SemiBold)
                            Text("Enable debugging on your TV (1 minute)", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Icon(if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (showGuide) {
                        Spacer(Modifier.size(10.dp))
                        GUIDE.forEachIndexed { i, step ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text("${i + 1}.", fontWeight = FontWeight.Bold, color = cs.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(step, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Add by IP", fontWeight = FontWeight.Medium) }
        )
    }

    if (showAdd) {
        AddDeviceDialog(
            onAdd = { n, h, p -> showAdd = false; connectTo(n, h, p) },
            onDismiss = { showAdd = false }
        )
    }
    if (showDoctor) ConnectionDoctor { showDoctor = false }
    renaming?.let { d ->
        TextPromptDialog("Rename TV", "Name", d.name, "Save",
            onConfirm = { Store.rename(d, it); renaming = null },
            onDismiss = { renaming = null })
    }
    removing?.let { d ->
        ConfirmDialog("Remove ${d.name}?", "It will be removed from your saved TVs.", "Remove",
            onConfirm = { Store.remove(d); removing = null },
            onDismiss = { removing = null })
    }
}

@Composable
private fun StatusCard(onDisconnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.primaryContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(cs.primary),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Tv, null, tint = cs.onPrimary, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    if (AdbManager.isConnected) AdbManager.deviceName else "No TV connected",
                    color = cs.onPrimaryContainer, fontSize = 20.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    if (AdbManager.isConnected) "${AdbManager.deviceHost}  ·  Android ${AdbManager.androidVersion}"
                    else if (AdbManager.connecting) "Connecting…" else "Pick a TV below to get started",
                    color = cs.onPrimaryContainer, fontSize = 13.sp
                )
            }
        }
        if (AdbManager.isConnected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF1B7F4B)))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Connected" + if (AdbManager.inject != Inject.INPUT) " · Fast" else "",
                    color = cs.onPrimaryContainer, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                Button(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, good: Boolean) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = if (good) Color(0xFF00391C) else Color(0xFF4A2800),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (good) Color(0xFFC8F0D6) else Color(0xFFFFDDB3)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun DeviceRow(title: String, subtitle: String, active: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TvCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Tone.high()),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Tv, null, tint = if (active) cs.primary else cs.onSurfaceVariant) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1)
                Text(subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
            }
            if (active) Text("Connected", fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.SemiBold)
            trailing()
        }
    }
}

@Composable
private fun AddDeviceDialog(onAdd: (String, String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it.trim() }, label = { Text("IP address, e.g. 192.168.1.20") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("Port") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ip.isNotBlank(), onClick = { onAdd(name.trim(), ip, port.toIntOrNull() ?: 5555) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A plain-language checklist: shows exactly what is working and what to fix. */
@Composable
private fun ConnectionDoctor(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val host = if (AdbManager.deviceHost.isNotEmpty()) AdbManager.deviceHost else Store.lastHost
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(host) {
        reachable = if (host.isEmpty()) false else withContext(Dispatchers.IO) {
            try { Socket().use { it.connect(InetSocketAddress(host, 5555), 900) }; true } catch (e: Exception) { false }
        }
        if (AdbManager.isConnected) CursorClient.check()
    }

    @Composable
    fun Row1(ok: Boolean?, title: String, hint: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (ok == true) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                tint = if (ok == true) Color(0xFF1B7F4B) else if (ok == null) cs.outline else Color(0xFF8A5100),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                if (ok != true) Text(hint, fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Connection check") },
        text = {
            Column {
                Row1(NetBinder.wifiConnected, "Phone is on Wi-Fi", "Connect your phone to the same Wi-Fi as the TV.")
                Row1(true, if (NetBinder.wifiNoInternet) "Wi-Fi has no internet (fine)" else "Wi-Fi has internet", "")
                Row1(reachable, "TV answers on your network" + if (host.isNotEmpty()) " ($host)" else "", "Turn the TV on, and switch on ADB / network debugging in its developer options.")
                Row1(AdbManager.isConnected, "TV allowed this phone", "Tap your TV in the list and press Allow on the TV.")
                Row1(AdbManager.isConnected && AdbManager.inject != Inject.INPUT, "Fast button mode", "This TV only supports standard mode. Buttons still work, a little slower.")
                Row1(AdbManager.isConnected && AdbManager.volume >= 0, "Device volume control", "Volume keys are used instead of exact levels.")
                Row1(CursorClient.ready, "Cursor helper on the TV", "Open Remote › Cursor and tap Set up cursor.")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
