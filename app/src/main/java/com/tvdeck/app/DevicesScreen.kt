@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val GUIDE = listOf(
    "On the TV open Settings › Device Preferences › About and click “Build” 7 times. (Fire TV: Settings › My Fire TV › About, click the device name 7 times.)",
    "Go back to Developer options and switch on USB debugging / Network debugging. (Fire TV: ADB debugging.)",
    "Connect your phone and TV to the same Wi-Fi, then tap Scan below, or add the TV’s IP address.",
    "When the TV asks, tick “Always allow” and press Allow. You only do this once."
)

@Composable
fun DevicesScreen() {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedDevice?>(null) }
    var removing by remember { mutableStateOf<SavedDevice?>(null) }
    var showGuide by remember { mutableStateOf(false) }

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

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("TVDeck", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Control any Android TV, Google TV or Fire TV",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { StatusCard(onDisconnect = { AdbManager.disconnect() }) }

        if (msg.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (AdbManager.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            SectionTitle("My TVs") {
                TextButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add by IP")
                }
            }
        }

        if (Store.devices.isEmpty()) {
            item {
                EmptyState(Icons.Default.Tv, "No saved TVs yet", "Scan your Wi-Fi below or add a TV by its IP address.")
            }
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
            SectionTitle("Nearby") {
                FilledTonalButton(onClick = {
                    scope.launch {
                        scanning = true
                        found = try { AdbManager.scan() } catch (e: Exception) { emptyList() }
                        scanning = false
                        scanned = true
                    }
                }, enabled = !scanning) {
                    Icon(if (scanning) Icons.Default.Refresh else Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Scanning…" else "Scan Wi-Fi")
                }
            }
        }

        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        if (scanned && !scanning && found.isEmpty()) {
            item {
                Text(
                    "No TV found. Make sure debugging is switched on, or add the TV by its IP address.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(found, key = { "found-$it" }) { host ->
            val saved = Store.devices.any { it.host == host }
            DeviceRow(
                title = host,
                subtitle = if (saved) "Saved TV" else "Tap to connect",
                active = AdbManager.isConnected && AdbManager.deviceHost == host,
                onClick = { if (!AdbManager.connecting) connectTo("", host, 5555) }
            ) {}
        }

        item {
            TvCard(onClick = { showGuide = !showGuide }, modifier = Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.Info, MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("First-time setup", fontWeight = FontWeight.SemiBold)
                        Text("Enable debugging on your TV (1 minute)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                if (showGuide) {
                    Spacer(Modifier.size(10.dp))
                    GUIDE.forEachIndexed { i, step ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text("${i + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(step, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDeviceDialog(
            onAdd = { n, h, p -> showAdd = false; connectTo(n, h, p) },
            onDismiss = { showAdd = false }
        )
    }
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
    val brush = Brush.linearGradient(listOf(Color(0xFF0E9F8E), Color(0xFF2B2F8F)))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(brush).padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Tv, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (AdbManager.isConnected) AdbManager.deviceName else "No TV connected",
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (AdbManager.isConnected) "${AdbManager.deviceHost}  ·  Android ${AdbManager.androidVersion}"
                        else if (AdbManager.connecting) "Connecting…" else "Pick a TV below to get started",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp
                    )
                }
            }
            if (AdbManager.isConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF7CF5D4)))
                    Spacer(Modifier.width(8.dp))
                    Text("Connected", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)
                    ) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(title: String, subtitle: String, active: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TvCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.Tv, if (active) cs.primary else cs.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            if (active) {
                Text("Connected", fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.SemiBold)
            }
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
