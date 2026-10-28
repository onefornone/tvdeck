package com.tvdeck.app

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        AdbManager.init(this)
        AppMeta.init(this)
        NetBinder.start(this)
        handleIncoming(intent)
        setContent {
            TvDeckTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TvDeckApp()
                }
            }
        }
    }

    /** Phone volume buttons control the TV while the Remote tab is open (can be switched off). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            Shared.currentTab == 1 && AdbManager.isConnected && Store.getPref("phonevol", "1") == "1"
        ) {
            AdbManager.sendKey(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 24 else 25)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            Shared.currentTab == 1 && AdbManager.isConnected && Store.getPref("phonevol", "1") == "1"
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Things shared to TVDeck from other apps: links, text, files, APKs. */
    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    Shared.pendingFiles = listOf(stream)
                } else {
                    val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val m = Regex("https?://\\S+").find(t)
                    if (m != null) Shared.pendingLink = m.value else Shared.pendingText = t.trim()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (list != null && list.isNotEmpty()) Shared.pendingFiles = list.toList()
            }
            Intent.ACTION_VIEW -> {
                val d = i.data
                if (d != null) Shared.pendingFiles = listOf(d)
            }
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

    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(tab) { Shared.currentTab = tab }
    LaunchedEffect(tab) {
        view.keepScreenOn = tab == 1 && Store.getPref("awake", "1") == "1"
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
            NavigationBar(containerColor = Tone.card()) {
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

    IncomingDialogs()
}
