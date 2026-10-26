package com.tvdeck.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        AdbManager.init(this)
        AppMeta.init(this)
        handleShare(intent)
        setContent {
            TvDeckTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TvDeckApp()
                }
            }
        }
    }

    private fun handleShare(i: Intent?) {
        if (i != null && i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val m = Regex("https?://\\S+").find(t)
            Shared.pendingLink = m?.value ?: t.trim()
        }
    }
}

private class Tab(val label: String, val icon: ImageVector)

@Composable
fun TvDeckApp() {
    var tab by rememberSaveable { mutableStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val holder = rememberSaveableStateHolder()
    val tabs = remember {
        listOf(
            Tab("Devices", Icons.Default.Tv),
            Tab("Remote", Icons.Default.SettingsRemote),
            Tab("Apps", Icons.Default.Apps),
            Tab("Files", Icons.Default.Folder),
            Tab("Tools", Icons.Default.Build)
        )
    }

    // Reconnect to the last TV automatically.
    LaunchedEffect(Unit) {
        if (!AdbManager.isConnected && Store.lastHost.isNotEmpty()) {
            val d = Store.devices.firstOrNull { it.host == Store.lastHost }
            if (d != null) {
                try { AdbManager.connect(d.host, d.port) } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Shared.message) {
        val m = Shared.message
        if (m != null) {
            snack.showSnackbar(m)
            if (Shared.message == m) Shared.message = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!AdbManager.isConnected && tab != 0) NotConnectedBanner { tab = 0 }
            Box(Modifier.weight(1f)) {
                holder.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> DevicesScreen()
                        1 -> RemoteScreen()
                        2 -> AppsScreen()
                        3 -> FilesScreen()
                        else -> ToolsScreen()
                    }
                }
            }
        }
    }

    Shared.pendingLink?.let { link ->
        AlertDialog(
            onDismissRequest = { Shared.pendingLink = null },
            title = { Text("Open on your TV?") },
            text = { Text(link) },
            confirmButton = {
                TextButton(onClick = {
                    Shared.pendingLink = null
                    if (!AdbManager.isConnected) Shared.say("Connect to a TV first")
                    else AdbManager.runBg("am start -a android.intent.action.VIEW -d ${shq(link)}", "Sent to TV")
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { Shared.pendingLink = null }) { Text("Cancel") } }
        )
    }
}
