@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Dialogs shown when something is shared to TVDeck from another app (link, text, files, APKs). */
@Composable
fun IncomingDialogs() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val link = Shared.pendingLink
    val files = Shared.pendingFiles
    val text = Shared.pendingText

    if (link != null) {
        val apk = isApkName(link)
        AlertDialog(
            onDismissRequest = { Shared.pendingLink = null },
            title = { Text(if (apk) "Install this app on your TV?" else "Open on your TV?") },
            text = { Text(link, maxLines = 4) },
            confirmButton = {
                TextButton(onClick = {
                    Shared.pendingLink = null
                    if (!AdbManager.isConnected) {
                        Shared.say("Connect to a TV first")
                    } else if (apk) {
                        scope.launch {
                            try {
                                Shared.say("Downloading and installing…")
                                Installer.installFromUrl(ctx, link) { }
                                Shared.say("Installed on your TV")
                            } catch (e: Exception) {
                                Shared.say("Install failed: ${e.message}")
                            }
                        }
                    } else {
                        AdbManager.runBg("am start -a android.intent.action.VIEW -d ${shq(link)}", "Sent to your TV")
                    }
                }) { Text(if (apk) "Install" else "Open") }
            },
            dismissButton = { TextButton(onClick = { Shared.pendingLink = null }) { Text("Cancel") } }
        )
    }

    if (files.isNotEmpty()) {
        val names = files.map { displayName(ctx, it) }
        val hasApk = names.any { isApkName(it) }

        fun send(open: Boolean) {
            val list = files
            Shared.pendingFiles = emptyList()
            if (!AdbManager.isConnected) {
                Shared.say("Connect to a TV first")
                return
            }
            scope.launch {
                try {
                    Shared.say("Sending to your TV…")
                    val paths = Installer.sendFiles(ctx, list)
                    Shared.say("Sent ${paths.size} file(s) to Download/TVDeck")
                    if (open && paths.isNotEmpty()) {
                        val ext = paths[0].substringAfterLast('.', "").lowercase()
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                        val fileUri = "file://" + paths[0]
                        AdbManager.runBg("am start -a android.intent.action.VIEW -d " + shq(fileUri) + " -t " + mime, "")
                    }
                } catch (e: Exception) {
                    Shared.say("Couldn't send: ${e.message}")
                }
            }
        }

        fun install() {
            val list = files
            Shared.pendingFiles = emptyList()
            if (!AdbManager.isConnected) {
                Shared.say("Connect to a TV first")
                return
            }
            scope.launch {
                try {
                    val n = Installer.installUris(ctx, list) { Shared.say(it) }
                    Shared.say("Installed $n app(s) on your TV")
                } catch (e: Exception) {
                    Shared.say("Install failed: ${e.message}")
                }
            }
        }

        AlertDialog(
            onDismissRequest = { Shared.pendingFiles = emptyList() },
            title = { Text(if (hasApk) "Install on your TV?" else "Send to your TV?") },
            text = {
                Column {
                    names.take(6).forEach { Text("• $it", maxLines = 1) }
                    if (names.size > 6) Text("…and ${names.size - 6} more")
                    Text("Files are saved in Download/TVDeck on the TV.", fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row {
                    if (hasApk) TextButton(onClick = { install() }) { Text("Install") }
                    if (files.size == 1 && !hasApk) TextButton(onClick = { send(true) }) { Text("Send & open") }
                    TextButton(onClick = { send(false) }) { Text("Send") }
                }
            },
            dismissButton = { TextButton(onClick = { Shared.pendingFiles = emptyList() }) { Text("Cancel") } }
        )
    }

    if (text != null) {
        AlertDialog(
            onDismissRequest = { Shared.pendingText = null },
            title = { Text("Type this on your TV?") },
            text = { Text(text, maxLines = 6) },
            confirmButton = {
                TextButton(onClick = {
                    Shared.pendingText = null
                    if (!AdbManager.isConnected) {
                        Shared.say("Connect to a TV first")
                    } else {
                        scope.launch {
                            try { AdbManager.typeText(text) } catch (e: Exception) { Shared.say(e.message ?: "Couldn't type") }
                        }
                    }
                }) { Text("Type") }
            },
            dismissButton = { TextButton(onClick = { Shared.pendingText = null }) { Text("Cancel") } }
        )
    }
}
