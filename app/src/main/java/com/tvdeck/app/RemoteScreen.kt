@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun RemoteScreen() {
    val cs = MaterialTheme.colorScheme
    var padMode by rememberSaveable { mutableStateOf(true) }
    var typing by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remote", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (AdbManager.isConnected) AdbManager.deviceName else "Not connected",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
            FilterChip(
                selected = padMode, onClick = { padMode = true }, label = { Text("Touchpad") },
                leadingIcon = { Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp)) }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = !padMode, onClick = { padMode = false }, label = { Text("D-pad") })
        }

        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyBtn(26, Modifier.weight(1f).fillMaxHeight(), tint = cs.error) { Icon(Icons.Default.PowerSettingsNew, "Power") }
            KeyBtn(164, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.VolumeOff, "Mute") }
            KeyBtn(25, Modifier.weight(1f).fillMaxHeight(), repeat = true) { Icon(Icons.Default.VolumeDown, "Volume down") }
            KeyBtn(24, Modifier.weight(1f).fillMaxHeight(), repeat = true) { Icon(Icons.Default.VolumeUp, "Volume up") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (padMode) Touchpad(Modifier.fillMaxSize()) else DPad(Modifier.fillMaxSize())
        }

        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyBtn(88, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.SkipPrevious, "Previous") }
            KeyBtn(89, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.FastRewind, "Rewind") }
            KeyBtn(85, Modifier.weight(1.3f).fillMaxHeight(), accent = true) { Icon(Icons.Default.PlayCircle, "Play or pause") }
            KeyBtn(90, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.FastForward, "Fast forward") }
            KeyBtn(87, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.SkipNext, "Next") }
        }

        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyBtn(4, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.ArrowBack, "Back") }
            KeyBtn(3, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.Home, "Home") }
            KeyBtn(82, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.Menu, "Menu") }
            KeyBtn(176, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.Settings, "Settings") }
            ActionBtn(onClick = { typing = !typing }, modifier = Modifier.weight(1f).fillMaxHeight(), selected = typing) {
                Icon(Icons.Default.Keyboard, "Keyboard")
            }
        }

        AnimatedVisibility(typing) { TypePanel() }
    }
}

@Composable
private fun Touchpad(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(cs.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        AdbManager.sendKey(23)
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        AdbManager.sendKey(4)
                    }
                )
            }
            .pointerInput(Unit) {
                val step = 64.dp.toPx()
                var ax = 0f
                var ay = 0f
                detectDragGestures(
                    onDragStart = { ax = 0f; ay = 0f },
                    onDrag = { change, drag ->
                        change.consume()
                        ax += drag.x
                        ay += drag.y
                        while (abs(ax) >= step || abs(ay) >= step) {
                            if (abs(ax) >= abs(ay)) {
                                if (ax > 0) { AdbManager.sendKey(22); ax -= step } else { AdbManager.sendKey(21); ax += step }
                                ay = 0f
                            } else {
                                if (ay > 0) { AdbManager.sendKey(20); ay -= step } else { AdbManager.sendKey(19); ay += step }
                                ax = 0f
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.align(Alignment.TopCenter).padding(12.dp).size(36.dp).alpha(0.35f))
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(12.dp).size(36.dp).alpha(0.35f))
        Icon(Icons.Default.KeyboardArrowLeft, null, Modifier.align(Alignment.CenterStart).padding(12.dp).size(36.dp).alpha(0.35f))
        Icon(Icons.Default.KeyboardArrowRight, null, Modifier.align(Alignment.CenterEnd).padding(12.dp).size(36.dp).alpha(0.35f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Swipe to move", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            Text("Tap = OK   ·   Hold = Back", fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun DPad(modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cell = ((minOf(maxWidth, maxHeight) - 16.dp) / 3).coerceIn(56.dp, 100.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyBtn(19, Modifier.size(cell), repeat = true) { Icon(Icons.Default.KeyboardArrowUp, "Up", Modifier.size(34.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyBtn(21, Modifier.size(cell), repeat = true) { Icon(Icons.Default.KeyboardArrowLeft, "Left", Modifier.size(34.dp)) }
                KeyBtn(23, Modifier.size(cell), accent = true) { Text("OK", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                KeyBtn(22, Modifier.size(cell), repeat = true) { Icon(Icons.Default.KeyboardArrowRight, "Right", Modifier.size(34.dp)) }
            }
            KeyBtn(20, Modifier.size(cell), repeat = true) { Icon(Icons.Default.KeyboardArrowDown, "Down", Modifier.size(34.dp)) }
        }
    }
}

@Composable
private fun TypePanel() {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }

    fun send() {
        if (text.isBlank()) return
        val t = text
        text = ""
        scope.launch {
            try { AdbManager.typeText(t) } catch (e: Exception) { Shared.say(e.message ?: "Couldn't send text") }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type here — sent to the TV") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            FilledIconButton(onClick = { send() }) { Icon(Icons.Default.Send, "Send") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { AdbManager.sendKey(66) }, label = { Text("Enter") })
            AssistChip(onClick = { AdbManager.sendKey(67) }, label = { Text("Delete") })
            AssistChip(onClick = { clip.getText()?.text?.let { text = it } }, label = { Text("Paste") })
        }
    }
}
