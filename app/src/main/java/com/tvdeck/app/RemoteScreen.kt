@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun RemoteScreen() {
    val cs = MaterialTheme.colorScheme
    var padMode by rememberSaveable { mutableStateOf(1) } // 0 cursor, 1 swipe, 2 d-pad
    var typing by rememberSaveable { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    val fast = AdbManager.inject != Inject.INPUT

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── header ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remote", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (AdbManager.isConnected) Color(0xFF22C55E) else cs.error)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (AdbManager.isConnected) AdbManager.deviceName else "Not connected",
                        fontSize = 12.sp, color = cs.onSurfaceVariant
                    )
                }
            }
            Box {
                AssistChip(
                    onClick = { speedMenu = true },
                    label = { Text(if (fast) "Fast" else "Standard") },
                    leadingIcon = { Icon(Icons.Default.FlashOn, null, Modifier.size(18.dp)) }
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Auto (recommended)") },
                        trailingIcon = { if (AdbManager.injectPref == "auto") Icon(Icons.Default.Check, null) },
                        onClick = { speedMenu = false; AdbManager.changeInjectPref("auto") }
                    )
                    DropdownMenuItem(
                        text = { Text("Compatible (slower)") },
                        trailingIcon = { if (AdbManager.injectPref == "compatible") Icon(Icons.Default.Check, null) },
                        onClick = { speedMenu = false; AdbManager.changeInjectPref("compatible") }
                    )
                }
            }
        }
        if (AdbManager.isConnected && !fast) {
            Text(
                "This TV only allows standard input, so buttons can take about a second. Everything still works.",
                fontSize = 11.sp, color = cs.onSurfaceVariant
            )
        }

        // ── power & volume ──
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyBtn(26, Modifier.weight(1f).fillMaxHeight(), tint = cs.error) { Icon(Icons.Default.PowerSettingsNew, "Power") }
            KeyBtn(164, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.VolumeOff, "Mute") }
            KeyBtn(25, Modifier.weight(1f).fillMaxHeight(), repeat = true) { Icon(Icons.Default.VolumeDown, "Volume down") }
            KeyBtn(24, Modifier.weight(1f).fillMaxHeight(), repeat = true) { Icon(Icons.Default.VolumeUp, "Volume up") }
        }
        VolumeRow()

        // ── pad ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = padMode == 0, onClick = { padMode = 0 }, label = { Text("Cursor") },
                leadingIcon = { Icon(Icons.Default.Mouse, null, Modifier.size(18.dp)) }
            )
            FilterChip(
                selected = padMode == 1, onClick = { padMode = 1 }, label = { Text("Swipe") },
                leadingIcon = { Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp)) }
            )
            FilterChip(selected = padMode == 2, onClick = { padMode = 2 }, label = { Text("D-pad") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (padMode) {
                0 -> CursorArea(Modifier.fillMaxSize())
                1 -> Touchpad(Modifier.fillMaxSize())
                else -> DPad(Modifier.fillMaxSize())
            }
        }

        // ── media ──
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyBtn(88, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.SkipPrevious, "Previous") }
            KeyBtn(89, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.FastRewind, "Rewind") }
            KeyBtn(85, Modifier.weight(1.3f).fillMaxHeight(), accent = true) { Icon(Icons.Default.PlayCircle, "Play or pause") }
            KeyBtn(90, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.FastForward, "Fast forward") }
            KeyBtn(87, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.SkipNext, "Next") }
        }

        // ── navigation ──
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyBtn(4, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.ArrowBack, "Back") }
            KeyBtn(3, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.Home, "Home") }
            KeyBtn(82, Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.Menu, "Menu") }
            ActionBtn(onClick = { AdbManager.openSettings() }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Icon(Icons.Default.Settings, "Settings")
            }
            ActionBtn(onClick = { typing = !typing }, modifier = Modifier.weight(1f).fillMaxHeight(), selected = typing) {
                Icon(Icons.Default.Keyboard, "Keyboard")
            }
        }

        AnimatedVisibility(typing) { TypePanel() }
    }
}

// ───────────────────────── volume ─────────────────────────
@Composable
private fun VolumeRow() {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val known = AdbManager.isConnected && AdbManager.volume >= 0 && AdbManager.volumeMode != "tv"
    val lo = AdbManager.volumeMin
    val hi = AdbManager.volumeMax.coerceAtLeast(lo + 1)

    LaunchedEffect(AdbManager.isConnected) {
        while (AdbManager.isConnected) {
            if (SystemClock.elapsedRealtime() - AdbManager.lastKeyTime > 3000) {
                try { AdbManager.refreshVolume() } catch (_: Exception) {}
            }
            delay(5000)
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.VolumeUp, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Slider(
            value = (drag ?: AdbManager.volume.coerceAtLeast(lo).toFloat()).coerceIn(lo.toFloat(), hi.toFloat()),
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { AdbManager.setVolumeTo(it.roundToInt()) }
                drag = null
            },
            valueRange = lo.toFloat()..hi.toFloat(),
            steps = (hi - lo - 1).coerceAtLeast(0),
            enabled = known,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (known) "${(drag ?: AdbManager.volume.toFloat()).roundToInt()}" else "–",
            fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(24.dp)
        )
        Box {
            AssistChip(
                onClick = { menu = true },
                label = {
                    Text(
                        when (AdbManager.volumeMode) { "device" -> "Fire TV"; "tv" -> "TV"; else -> "Both" },
                        fontSize = 12.sp
                    )
                }
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Fire TV / Device volume") },
                    trailingIcon = { if (AdbManager.volumeMode == "device") Icon(Icons.Default.Check, null) },
                    onClick = { menu = false; AdbManager.changeVolumeMode("device") }
                )
                DropdownMenuItem(
                    text = { Text("TV volume (HDMI-CEC keys)") },
                    trailingIcon = { if (AdbManager.volumeMode == "tv") Icon(Icons.Default.Check, null) },
                    onClick = { menu = false; AdbManager.changeVolumeMode("tv") }
                )
                DropdownMenuItem(
                    text = { Text("Both together") },
                    trailingIcon = { if (AdbManager.volumeMode == "both") Icon(Icons.Default.Check, null) },
                    onClick = { menu = false; AdbManager.changeVolumeMode("both") }
                )
            }
        }
    }
}

// ───────────────────────── cursor (mouse pointer on the TV) ─────────────────────────
@Composable
private fun CursorArea(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(AdbManager.isConnected, AdbManager.deviceHost) {
        if (AdbManager.isConnected) CursorClient.check()
    }

    if (CursorClient.ready) {
        CursorPad(modifier)
    } else {
        TvCard(modifier = modifier) {
            Icon(Icons.Default.Mouse, null, Modifier.size(36.dp), tint = cs.primary)
            Spacer(Modifier.height(8.dp))
            Text("Show a mouse cursor on your TV", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "TVDeck installs a tiny helper on your TV. It draws a pointer you move with this touchpad, and clicks where you tap. One time setup, about 20 seconds.",
                fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)
            )
            Button(
                enabled = !busy && AdbManager.isConnected,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        error = AdbManager.setupCursorHelper { step = it }
                        busy = false
                        step = ""
                        if (error == null) CursorClient.show(true)
                    }
                }
            ) { Text(if (busy) "Setting up…" else "Set up cursor") }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(step, fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            error?.let { Text(it, fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(top = 8.dp)) }
            if (!AdbManager.isConnected) Text("Connect to a TV first.", fontSize = 12.sp, color = cs.error)
        }
    }
}

@Composable
private fun CursorPad(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var finger by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(Unit) {
        CursorClient.show(true)
        onDispose { CursorClient.show(false) }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(cs.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        finger = it
                        tryAwaitRelease()
                        finger = null
                    },
                    onTap = {
                        CursorClient.click()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onLongPress = {
                        CursorClient.longClick()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                moved = true
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    CursorClient.scroll(pan.x * 1.4f, pan.y * 1.4f)
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (pressed.size == 1) {
                                val c = pressed[0]
                                if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                                if (moved) {
                                    val d = c.positionChange()
                                    if (d != Offset.Zero) {
                                        val dt = (c.uptimeMillis - c.previousUptimeMillis).coerceAtLeast(1L)
                                        val speed = d.getDistance() / dt
                                        val gain = 1.7f * (1f + (speed * 0.35f).coerceAtMost(2.2f))
                                        CursorClient.move(d.x * gain, d.y * gain)
                                        c.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Mouse, null, Modifier.size(34.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.5f))
            Text("Slide to move the pointer", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            Text(
                "Tap = click   ·   Hold = long press   ·   2 fingers = scroll",
                fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        AssistChip(
            onClick = { CursorClient.center() },
            label = { Text("Center", fontSize = 12.sp) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
        )
        val f = finger
        if (f != null) {
            Box(
                Modifier
                    .offset { IntOffset((f.x - 30.dp.toPx()).roundToInt(), (f.y - 30.dp.toPx()).roundToInt()) }
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.28f))
            )
        }
    }
}

// ───────────────────────── swipe pad / d-pad ─────────────────────────
@Composable
private fun Touchpad(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var finger by remember { mutableStateOf<Offset?>(null) }
    var flashCode by remember { mutableStateOf(0) }
    var flashAt by remember { mutableStateOf(0L) }

    LaunchedEffect(flashAt) {
        if (flashCode != 0) {
            delay(150)
            flashCode = 0
        }
    }

    fun fire(code: Int) {
        AdbManager.sendKey(code)
        flashCode = code
        flashAt = System.nanoTime()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(cs.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        finger = it
                        tryAwaitRelease()
                        finger = null
                    },
                    onTap = { fire(23) },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        AdbManager.sendKey(4)
                        flashCode = 4
                        flashAt = System.nanoTime()
                    }
                )
            }
            .pointerInput(Unit) {
                val base = 46.dp.toPx()
                var ax = 0f
                var ay = 0f
                detectDragGestures(
                    onDragStart = { finger = it; ax = 0f; ay = 0f },
                    onDragEnd = { finger = null },
                    onDragCancel = { finger = null },
                    onDrag = { change, drag ->
                        change.consume()
                        finger = change.position
                        val dt = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
                        val speed = hypot(drag.x, drag.y) / dt
                        val step = base * (if (speed > 2.5f) 0.55f else if (speed > 1.2f) 0.75f else 1f)
                        ax += drag.x
                        ay += drag.y
                        while (abs(ax) >= step || abs(ay) >= step) {
                            if (abs(ax) >= abs(ay)) {
                                if (ax > 0) { fire(22); ax -= step } else { fire(21); ax += step }
                                ay = 0f
                            } else {
                                if (ay > 0) { fire(20); ay -= step } else { fire(19); ay += step }
                                ax = 0f
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.align(Alignment.TopCenter).padding(12.dp).size(38.dp).alpha(if (flashCode == 19) 1f else 0.3f))
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(12.dp).size(38.dp).alpha(if (flashCode == 20) 1f else 0.3f))
        Icon(Icons.Default.KeyboardArrowLeft, null, Modifier.align(Alignment.CenterStart).padding(12.dp).size(38.dp).alpha(if (flashCode == 21) 1f else 0.3f))
        Icon(Icons.Default.KeyboardArrowRight, null, Modifier.align(Alignment.CenterEnd).padding(12.dp).size(38.dp).alpha(if (flashCode == 22) 1f else 0.3f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (flashCode == 23) "Select" else if (flashCode == 4) "Back" else "Swipe to move",
                fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant
            )
            Text("Tap = select   ·   Hold = back", fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.8f))
        }
        val f = finger
        if (f != null) {
            Box(
                Modifier
                    .offset { IntOffset((f.x - 30.dp.toPx()).roundToInt(), (f.y - 30.dp.toPx()).roundToInt()) }
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.28f))
            )
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
