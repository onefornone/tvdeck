@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
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
    var menu by remember { mutableStateOf(false) }
    var phoneVol by remember { mutableStateOf(Store.getPref("phonevol", "1") == "1") }
    var awake by remember { mutableStateOf(Store.getPref("awake", "1") == "1") }
    val fast = AdbManager.inject != Inject.INPUT

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── header ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remote", fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Tone.card()).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (AdbManager.isConnected) Color(0xFF1B7F4B) else cs.error))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (AdbManager.isConnected) AdbManager.deviceName.take(18) + (if (fast) " · Fast" else "") else "Not connected",
                    fontSize = 12.sp
                )
            }
            ThemeButton()
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Remote settings") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    RemoteMenuItem("Speed: Auto", AdbManager.injectPref == "auto") { AdbManager.changeInjectPref("auto") }
                    RemoteMenuItem("Speed: Compatible (slower)", AdbManager.injectPref == "compatible") { AdbManager.changeInjectPref("compatible") }
                    HorizontalDivider()
                    RemoteMenuItem("Volume: Fire TV / Device", AdbManager.volumeMode == "device") { AdbManager.changeVolumeMode("device") }
                    RemoteMenuItem("Volume: TV (HDMI-CEC keys)", AdbManager.volumeMode == "tv") { AdbManager.changeVolumeMode("tv") }
                    RemoteMenuItem("Volume: Both together", AdbManager.volumeMode == "both") { AdbManager.changeVolumeMode("both") }
                    HorizontalDivider()
                    RemoteMenuItem("Phone volume buttons control TV", phoneVol) {
                        phoneVol = !phoneVol
                        Store.setPref("phonevol", if (phoneVol) "1" else "0")
                    }
                    RemoteMenuItem("Keep phone screen on", awake) {
                        awake = !awake
                        Store.setPref("awake", if (awake) "1" else "0")
                    }
                }
            }
        }
        if (AdbManager.isConnected && !fast) {
            Text(
                "This TV only allows standard input, so buttons can take about a second.",
                fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)
            )
        }

        // ── power & volume ──
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyBtn(26, Modifier.weight(1f).fillMaxHeight(), tint = cs.error, round = true) { Icon(Icons.Default.PowerSettingsNew, "Power") }
            KeyBtn(164, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.VolumeOff, "Mute") }
            KeyBtn(25, Modifier.weight(1f).fillMaxHeight(), repeat = true, round = true) { Icon(Icons.Default.VolumeDown, "Volume down") }
            KeyBtn(24, Modifier.weight(1f).fillMaxHeight(), repeat = true, round = true) { Icon(Icons.Default.VolumeUp, "Volume up") }
        }
        VolumeRow()

        if (Store.pins.isNotEmpty()) PinnedRow()

        // ── pad mode (segmented) ──
        Row(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, cs.outline, RoundedCornerShape(20.dp))
        ) {
            listOf("Cursor", "Swipe", "D-pad").forEachIndexed { i, l ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (padMode == i) cs.primaryContainer else Color.Transparent)
                        .clickable { padMode = i },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (padMode == i) {
                            Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = cs.onPrimaryContainer)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(l, fontSize = 14.sp, fontWeight = if (padMode == i) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (padMode) {
                0 -> CursorArea(Modifier.fillMaxSize())
                1 -> Touchpad(Modifier.fillMaxSize())
                else -> DPad(Modifier.fillMaxSize())
            }
        }

        // ── media ──
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyBtn(88, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.SkipPrevious, "Previous") }
            KeyBtn(89, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.FastRewind, "Rewind") }
            KeyBtn(85, Modifier.weight(1.3f).fillMaxHeight(), accent = true, round = true) { Icon(Icons.Default.PlayArrow, "Play or pause", Modifier.size(30.dp)) }
            KeyBtn(90, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.FastForward, "Fast forward") }
            KeyBtn(87, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.SkipNext, "Next") }
        }

        // ── navigation ──
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyBtn(4, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.ArrowBack, "Back") }
            KeyBtn(3, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.Home, "Home") }
            KeyBtn(82, Modifier.weight(1f).fillMaxHeight(), round = true) { Icon(Icons.Default.Menu, "Menu") }
            ActionBtn(onClick = { AdbManager.openSettings() }, modifier = Modifier.weight(1f).fillMaxHeight()) { Icon(Icons.Default.Settings, "Settings") }
            ActionBtn(onClick = { typing = !typing }, modifier = Modifier.weight(1f).fillMaxHeight(), selected = typing) { Icon(Icons.Default.Keyboard, "Keyboard") }
        }

        AnimatedVisibility(typing) { TypePanel() }
    }
}

@Composable
private fun RemoteMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp) },
        trailingIcon = { if (checked) Icon(Icons.Default.Check, null) },
        onClick = onClick
    )
}

// ───────────────────────── pinned apps ─────────────────────────
@Composable
private fun PinnedRow() {
    val cs = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        items(Store.pins.toList(), key = { it }) { pkg ->
            LaunchedEffect(pkg) { AppMeta.request(AppItem(pkg, false, false)) }
            val icon = AppMeta.icons[pkg]
            val name = AppMeta.labels[pkg] ?: prettyName(pkg)
            Column(
                Modifier.width(60.dp).clip(RoundedCornerShape(14.dp)).clickable { AdbManager.launchApp(pkg) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = name, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                } else {
                    val c = avatarColor(pkg)
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text(name.take(1).uppercase(), color = c, fontWeight = FontWeight.Bold)
                    }
                }
                Text(name, fontSize = 10.sp, maxLines = 1, color = cs.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────── volume ─────────────────────────
@Composable
private fun VolumeRow() {
    val cs = MaterialTheme.colorScheme
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

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Tone.card()).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
        Text(
            when (AdbManager.volumeMode) { "device" -> "Fire TV"; "tv" -> "TV"; else -> "Both" },
            fontSize = 11.sp, color = cs.onSurfaceVariant
        )
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
                "TVDeck installs a tiny helper on your TV. It draws a pointer you move with this touchpad, clicks where you tap, and makes Live view taps instant. One-time setup, about 20 seconds.",
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
    var speedMenu by remember { mutableStateOf(false) }

    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Tone.high())
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
                                        val gain = 1.7f * CursorClient.speed * (1f + (speed * 0.35f).coerceAtMost(2.2f))
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
            Icon(Icons.Default.Mouse, null, Modifier.size(36.dp), tint = cs.primary)
            Text("Slide to move the pointer", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Tap = click · Hold = long press · 2 fingers = scroll",
                fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
            Text("The pointer hides itself after 3 seconds", fontSize = 11.sp, color = cs.onSurfaceVariant.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            AssistChip(onClick = { speedMenu = true }, label = { Text("Speed", fontSize = 12.sp) })
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                listOf("Slow" to 0.6f, "Normal" to 1.0f, "Fast" to 1.5f).forEach { (l, v) ->
                    DropdownMenuItem(
                        text = { Text(l) },
                        trailingIcon = { if (CursorClient.speed == v) Icon(Icons.Default.Check, null) },
                        onClick = { speedMenu = false; CursorClient.changeSpeed(v) }
                    )
                }
            }
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
            .background(Tone.high())
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
                fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant
            )
            Text("Tap = select · Hold = back", fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.8f))
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

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) text = t
    }

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
                placeholder = { Text("Type or speak — sent to the TV") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    IconButton(onClick = {
                        try {
                            voice.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            )
                        } catch (e: Exception) { Shared.say("Voice typing isn't available on this phone") }
                    }) { Icon(Icons.Default.Mic, "Voice typing") }
                }
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
