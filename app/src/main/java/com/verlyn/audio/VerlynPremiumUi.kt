package com.verlyn.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val VBlack = Color(0xFF000000)
private val VCard = Color(0xFF111318)
private val VBorder = Color(0xFF1F2430)
private val VBlue = Color(0xFF3B82F6)
private val VBlueLight = Color(0xFF60A5FA)
private val VWhite = Color(0xFFF8FAFC)
private val VMuted = Color(0xFF8E98A8)
private val VGreen = Color(0xFF63D6A0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerlynPremiumApp(state: VerlynProUiState, actions: VerlynProUiActions) {
    MaterialTheme(colorScheme = darkColorScheme(primary = VBlue, onPrimary = VWhite, background = VBlack, onBackground = VWhite, surface = VCard, onSurface = VWhite)) {
        var picker by remember { mutableStateOf<String?>(null) }
        Scaffold(
            containerColor = VBlack,
            bottomBar = {
                NavigationBar(containerColor = VCard) {
                    listOf("Control", "Equalizer", "Smart", "Inspector").forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = state.selectedTab == index,
                            onClick = { actions.onTabChange(index) },
                            icon = { Icon(when (index) { 0 -> Icons.Filled.Headphones; 1 -> Icons.Filled.MusicNote; 2 -> Icons.Filled.Settings; else -> Icons.Filled.Bluetooth }, title) },
                            label = { Text(title, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VERLYN", fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text("AUDIO COMPANION", color = VMuted, fontSize = 9.sp, letterSpacing = 2.sp)
                    }
                    Box(Modifier.size(34.dp).background(VBlue.copy(alpha = .14f), CircleShape), Alignment.Center) {
                        Icon(Icons.Filled.Bluetooth, null, tint = VBlueLight)
                    }
                }
                when (state.selectedTab) {
                    0 -> ControlTab(state, actions)
                    1 -> EqualizerTab(state, actions)
                    2 -> SmartTab(state, actions) { picker = it }
                    else -> InspectorTab(state, actions)
                }
            }
        }
        picker?.let { slot ->
            val current = state.triggerActions[slot] ?: "disabled"
            ModalBottomSheet(onDismissRequest = { picker = null }, containerColor = VCard) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ASSIGN ACTION", color = VBlueLight, fontWeight = FontWeight.Bold)
                    Text(slotName(slot), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    smartOptions.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().background(if (option.first == current) VBlue.copy(alpha = .17f) else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { actions.onTriggerActionChange(slot, option.first); picker = null }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(option.second, Modifier.weight(1f), fontSize = 14.sp)
                            if (option.first == current) Text("SELECTED", color = VBlueLight, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlTab(s: VerlynProUiState, a: VerlynProUiActions) {
    val batterySweep by animateFloatAsState(
        targetValue = (s.batteryPercent ?: 0).coerceIn(0, 100).toFloat() * 3.6f,
        label = "headset-battery-ring"
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!s.companionConfigured || !s.batteryUnrestricted || !s.assistantSelected) {
            PremiumCard {
                Text("QUICK SETUP", color = VBlueLight, fontWeight = FontWeight.Bold)
                Text("This checklist disappears when all three setup steps are complete.", color = VMuted, fontSize = 10.sp)
                Setting("Smart Auto-Connect", if (s.companionConfigured) "JBL association saved" else "Associate JBL Tune 770NC") {
                    Button(onClick = a.onAssociateHeadset, enabled = !s.companionConfigured, colors = ButtonDefaults.buttonColors(containerColor = VBlue)) { Text(if (s.companionConfigured) "DONE" else "SET UP") }
                }
                Setting("Unrestricted Battery", if (s.batteryUnrestricted) "Optimization exemption granted" else "Allow the companion to survive screen-off") {
                    Button(onClick = a.onRequestBatteryUnrestricted, enabled = !s.batteryUnrestricted, colors = ButtonDefaults.buttonColors(containerColor = VBlue)) { Text(if (s.batteryUnrestricted) "DONE" else "ALLOW") }
                }
                Setting("Headset Assistant Trigger", if (s.assistantSelected) "Verlyn is the selected assistant" else "Choose Verlyn in voice input settings") {
                    Button(onClick = a.onOpenVoiceInputSettings, enabled = !s.assistantSelected, colors = ButtonDefaults.buttonColors(containerColor = VBlue)) { Text(if (s.assistantSelected) "DONE" else "OPEN") }
                }
            }
        }
        PremiumCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(68.dp), Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val inset = 3.dp.toPx()
                        val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(VBorder, -90f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
                        if (s.batteryPercent != null) {
                            drawArc(VBlueLight, -90f, batterySweep, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
                        }
                    }
                    Box(Modifier.size(54.dp).background(Brush.radialGradient(listOf(VBlue.copy(alpha = .32f), VBlue.copy(alpha = .03f), Color.Transparent)), CircleShape), Alignment.Center) {
                        Icon(Icons.Filled.Headphones, null, tint = VBlueLight, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("JBL TUNE 770NC", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if (s.audioConnected) "Connected" else "Not connected", color = if (s.audioConnected) VGreen else VMuted, fontSize = 11.sp)
                    if (s.firmwareVersion.isNotBlank()) {
                        Text("FW " + s.firmwareVersion, color = VBlueLight, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(color = VBlue.copy(alpha = .12f), shape = RoundedCornerShape(7.dp)) {
                        Text(if (s.audioConnected) "A2DP · CONNECTED" else routeText(s.route).uppercase(), color = VBlueLight, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), maxLines = 1)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.batteryPercent?.let { "$it%" } ?: "—", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("BATTERY", color = VMuted, fontSize = 8.sp)
                }
            }
            HorizontalDivider(color = VBorder)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("BLUETOOTH AUDIO", color = VMuted, fontSize = 9.sp)
                    Text(if (s.audioConnected) "ACTIVE" else "STANDBY", color = if (s.audioConnected) VGreen else VMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("CLASSIC LINK", color = VMuted, fontSize = 9.sp)
                    Text(if (s.rfcommState.contains("Connected", true)) "CONNECTED" else "AUTO MONITOR", color = VBlueLight, fontSize = 10.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = a.onConnectHeadset, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = VBlue)) { Text("Connect") }
                OutlinedButton(onClick = a.onDisconnectHeadset, Modifier.weight(1f)) { Text("Disconnect") }
            }
        }
        PremiumCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NOISE CONTROL", fontWeight = FontWeight.Bold)
                    Text(if (s.noiseControlReady) "Headset: " + s.noiseMode else "Tap a mode to read or change it", color = VMuted, fontSize = 10.sp)
                }
                TinyTag(if (s.noiseControlReady) "READY" else "STANDBY", s.noiseControlReady)
            }
            listOf("ANC", "Ambient Aware", "TalkThru", "Off").chunked(2).forEach { modes ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    modes.forEach { mode ->
                        val enabled = s.audioConnected
                        val selected = s.noiseMode.equals(mode, true)
                        Surface(Modifier.weight(1f).clickable(enabled = enabled) { a.onNoiseModeChange(mode) },
                            color = if (selected) VBlue.copy(alpha = .2f) else Color(0xFF171B23),
                            shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, if (selected) VBlue else VBorder)) {
                            Text(mode, Modifier.padding(vertical = 13.dp), color = if (enabled) VWhite else VMuted.copy(alpha = .45f), textAlign = TextAlign.Center, fontSize = 11.sp)
                        }
                    }
                }
            }
            Text(s.noiseStatus.ifBlank { "No RFCOMM attempt yet" }, color = VMuted, fontSize = 9.sp)
        }
        PremiumCard {
            Text("QUICK AUDIO", fontWeight = FontWeight.Bold)
            Setting("Bass boost", "Low-frequency enhancement") { Switch(s.bassBoostEnabled, a.onBassBoostChange) }
            Value("Bass strength", s.bassBoostStrength, 0f..100f, "%", a.onBassStrengthChange)
            Setting("Spatial audio", "Android Virtualizer effect") { Switch(s.spatialEnabled, a.onSpatialChange) }
            Value("Spatial", s.spatialStrength, 0f..100f, "%", a.onSpatialStrengthChange)
            Setting("Peak limiter", "Output headroom control") { Switch(s.limiterEnabled, a.onLimiterChange) }
        }
    }
}

@Composable
private fun EqualizerTab(s: VerlynProUiState, a: VerlynProUiActions) {
    var active by remember { mutableIntStateOf(-1) }
    var activeGain by remember { mutableFloatStateOf(0f) }
    var draft by remember(s.gains) { mutableStateOf(s.gains) }
    var showSave by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("My JBL Profile") }
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PremiumCard {
            Text(
                s.audioSessionBindings.firstOrNull()?.let { "Bound: " + it + " · Active" }
                    ?: "Not bound to player — Open Spotify Settings → Equalizer",
                color = if (s.audioSessionBindings.isNotEmpty()) VGreen else VMuted,
                fontSize = 9.sp
            )
            Surface(
                modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        a.onEqBypassChange(true)
                        tryAwaitRelease()
                        a.onEqBypassChange(false)
                    })
                },
                color = if (s.audioBypassed) VBlue.copy(alpha = .24f) else Color(0xFF171B23),
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, if (s.audioBypassed) VBlueLight else VBorder)
            ) {
                Text(
                    if (s.audioBypassed) "ORIGINAL SOUND · RELEASE TO RESTORE" else "HOLD FOR ORIGINAL SOUND · A/B",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = if (s.audioBypassed) VBlueLight else VMuted,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (s.engineMode == "native" && s.nativeBandCentersHz.isNotEmpty()) "${s.nativeBandCentersHz.size}-BAND NATIVE EQUALIZER" else "10-BAND EQUALIZER", fontWeight = FontWeight.Bold)
                    Text(s.selectedPreset, color = VBlueLight, fontSize = 10.sp)
                }
                Switch(s.eqEnabled, a.onEqEnabledChange)
            }
            Text("ENGINE MODE", color = VMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = s.engineMode == "dynamics", onClick = { a.onEngineModeChange("dynamics") }, label = { Text("DynamicsProcessing (10-band)", fontSize = 10.sp) })
                FilterChip(selected = s.engineMode == "native", onClick = { a.onEngineModeChange("native") }, label = { Text("Compatibility Equalizer (5-band Native)", fontSize = 10.sp) })
            }
            if (s.engineMode == "dynamics") {
                Text("3-BAND DYNAMIC PUNCH", color = VBlueLight, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                Value("Bass Punch", s.bassPunch, -6f..6f, " dB") { a.onMbcTuningChange("bass", it) }
                Value("Vocal Presence", s.vocalPresence, -6f..6f, " dB") { a.onMbcTuningChange("vocal", it) }
                Value("Treble Air", s.trebleAir, -6f..6f, " dB") { a.onMbcTuningChange("air", it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("−12 dB", color = VMuted, fontSize = 9.sp)
                Text(if (active >= 0) String.format(Locale.US, "%+.1f dB", activeGain) else "DRAG A NODE", color = VBlueLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("+12 dB", color = VMuted, fontSize = 9.sp)
            }
            val graphCenters = if (s.engineMode == "native" && s.nativeBandCentersHz.size >= 3) s.nativeBandCentersHz else listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
            val graphValues = remember(draft, graphCenters) {
                graphCenters.map { frequency -> gainAtFrequency(frequency.toFloat(), draft) }
            }
            EqSpline(graphValues) { nodeIndex, value ->
                val frequency = graphCenters.getOrElse(nodeIndex) { 1000 }
                val gainIndex = VerlynAudioEngine.BAND_FREQUENCIES.indices.minByOrNull { index ->
                    kotlin.math.abs(kotlin.math.ln(VerlynAudioEngine.BAND_FREQUENCIES[index].toDouble()) - kotlin.math.ln(frequency.toDouble()))
                } ?: nodeIndex.coerceIn(0, 9)
                if (active != nodeIndex) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                active = nodeIndex
                activeGain = value
                draft = draft.toMutableList().also { it[gainIndex] = value }
                a.onGainChange(gainIndex, value)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                graphCenters.forEach { frequency -> Text(formatEqFrequency(frequency), color = VMuted, fontSize = 7.sp, maxLines = 1) }
            }
            OutlinedButton(onClick = a.onTestEq, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.testAudioStatus.contains("playing", true)) "STOP TEST BEAT" else "TEST BEAT")
            }
            if (s.testAudioStatus.isNotBlank()) Text(s.testAudioStatus, color = VMuted, fontSize = 9.sp)
        }
        PremiumCard {
            Text("SOUND PROFILES", fontWeight = FontWeight.Bold)
            listOf("Flat", "Studio", "Bass", "Club", "Extreme Bass", "Vocal", "Jazz", "Podcast", "Gaming").chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { name ->
                        val selected = s.selectedPreset.equals(name, true)
                        Surface(Modifier.weight(1f).clickable { a.onPreset(name) }, color = if (selected) VBlue.copy(alpha = .2f) else Color(0xFF171B23), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (selected) VBlue else VBorder)) {
                            Text(name, Modifier.padding(vertical = 11.dp, horizontal = 3.dp), textAlign = TextAlign.Center, fontSize = 10.sp)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            s.customPresets.forEach { preset ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.name, Modifier.weight(1f), fontSize = 11.sp)
                    TextButton(onClick = { a.onPreset(preset.name) }) { Text("APPLY") }
                    TextButton(onClick = { a.onDeletePreset(preset.name) }) { Text("REMOVE") }
                }
            }
            OutlinedButton(onClick = { showSave = true }, modifier = Modifier.fillMaxWidth()) { Text("Save current profile") }
        }
        PremiumCard {
            Text("TONE & OUTPUT", fontWeight = FontWeight.Bold)
            Value("Preamp", s.preamp, -12f..12f, "dB", a.onPreampChange)
            Value("Clarity", s.clarity, -6f..6f, "dB", a.onClarityChange)
            Value("L / R balance", s.balance, -1f..1f, "", a.onBalanceChange)
            Setting("Channel balance", "Enable stereo balance") { Switch(s.balanceEnabled, a.onBalanceEnabledChange) }
            Setting("Peak limiter", "Output headroom control") { Switch(s.limiterEnabled, a.onLimiterChange) }
            Text(s.effectStatus, color = VMuted, fontSize = 9.sp)
            if (s.audioSessionBindings.isEmpty()) {
                Text("Waiting for playback start — restart or pause/play music to attach", color = VMuted, fontSize = 9.sp)
            } else {
                s.audioSessionBindings.forEach { binding ->
                    Text("Bound to " + binding, color = VBlueLight, fontSize = 9.sp)
                }
            }
        }
    }
    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save EQ profile") },
            text = {
                TextField(value = presetName, onValueChange = { presetName = it }, singleLine = true, label = { Text("Profile name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = presetName.trim()
                    if (name.isNotEmpty()) a.onSavePreset(name)
                    showSave = false
                }) { Text("SAVE") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("CANCEL") } },
            containerColor = VCard
        )
    }
}

private fun gainAtFrequency(frequencyHz: Float, gains: List<Float>): Float {
    val anchors = VerlynAudioEngine.BAND_FREQUENCIES
    val frequency = frequencyHz.coerceAtLeast(1f)
    if (frequency <= anchors.first()) return gains.firstOrNull() ?: 0f
    if (frequency >= anchors.last()) return gains.lastOrNull() ?: 0f
    val upper = anchors.indexOfFirst { it >= frequency }.coerceIn(1, anchors.lastIndex)
    val lower = upper - 1
    val ratio = ((kotlin.math.ln(frequency.toDouble()) - kotlin.math.ln(anchors[lower].toDouble())) /
        (kotlin.math.ln(anchors[upper].toDouble()) - kotlin.math.ln(anchors[lower].toDouble()))).toFloat()
    return gains.getOrElse(lower) { 0f } + (gains.getOrElse(upper) { 0f } - gains.getOrElse(lower) { 0f }) * ratio
}

private fun formatEqFrequency(frequencyHz: Int): String =
    if (frequencyHz >= 1000) {
        val value = frequencyHz / 1000f
        if (frequencyHz % 1000 == 0) "${value.toInt()}k" else String.format(Locale.US, "%.1fk", value)
    } else "${frequencyHz}Hz"

@Composable
private fun EqSpline(gains: List<Float>, onChange: (Int, Float) -> Unit) {
    val current by rememberUpdatedState(gains)
    val callback by rememberUpdatedState(onChange)
    Canvas(Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 8.dp).pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { p ->
                val lastIndex = (current.size - 1).coerceAtLeast(1)
                val i = ((p.x / size.width) * lastIndex).roundToInt().coerceIn(0, lastIndex)
                callback(i, (12f - p.y / size.height * 24f).coerceIn(-12f, 12f))
            },
            onDrag = { change, _ ->
                change.consume()
                val lastIndex = (current.size - 1).coerceAtLeast(1)
                val i = ((change.position.x / size.width) * lastIndex).roundToInt().coerceIn(0, lastIndex)
                callback(i, (12f - change.position.y / size.height * 24f).coerceIn(-12f, 12f))
            }
        )
    }) {
        val pointCount = current.size.coerceAtLeast(2)
        val points = List(pointCount) { i -> Offset(size.width * i / (pointCount - 1f), size.height * (12f - current.getOrElse(i) { 0f }.coerceIn(-12f, 12f)) / 24f) }
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(VBorder, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 0 until points.lastIndex) {
                val p = points[i]; val q = points[i + 1]; val dx = (q.x - p.x) / 3f
                cubicTo(p.x + dx, p.y, q.x - dx, q.y, q.x, q.y)
            }
        }
        val fill = Path().apply { addPath(path); lineTo(points.last().x, size.height); lineTo(points.first().x, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(VBlue.copy(alpha = .34f), VBlue.copy(alpha = .01f))))
        drawPath(path, VBlue.copy(alpha = .2f), style = androidx.compose.ui.graphics.drawscope.Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, Brush.horizontalGradient(listOf(VBlue, VBlueLight)), style = androidx.compose.ui.graphics.drawscope.Stroke(2.7.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { point ->
            drawCircle(VBlue.copy(alpha = .17f), 9.dp.toPx(), point)
            drawCircle(VBlack, 5.dp.toPx(), point)
            drawCircle(VWhite, 2.5.dp.toPx(), point)
        }
    }
}

@Composable
private fun SmartTab(s: VerlynProUiState, a: VerlynProUiActions, pick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PremiumCard {
            Text("HEADSET COMMAND MODE", color = VBlueLight, fontWeight = FontWeight.Bold)
            Text("Long-hold the JBL centre button for about two seconds. Normal volume and single-tap media controls remain native.", color = VMuted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = a.onOpenVoiceInputSettings, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = VBlue)) {
                    Text(if (s.assistantSelected) "ASSISTANT SETTINGS" else "SET DEFAULT ASSISTANT")
                }
                OutlinedButton(onClick = a.onTestVoice, modifier = Modifier.weight(1f)) { Text("TEST VOICE") }
            }
            Text(if (s.assistantSelected) "Verlyn is the Android digital assistant." else "Select Verlyn in Android voice input settings to receive the headset assistant action.", color = if (s.assistantSelected) VGreen else VMuted, fontSize = 10.sp)
        }
        PremiumCard {
            Text("COMMAND WINDOW · 5 SECONDS", fontWeight = FontWeight.Bold)
            Text("The window opens after the centre-button long-hold. Volume is restored to its entry level after every command.", color = VMuted, fontSize = 10.sp)
            HeadsetGestureAssignment("Vol + · single tap", s.headsetTriggerActions["command_vol_up_single"] ?: "speak_battery") { a.onHeadsetTriggerActionChange("command_vol_up_single", it) }
            HeadsetGestureAssignment("Vol − · single tap", s.headsetTriggerActions["command_vol_down_single"] ?: "enter_eq_scroll") { a.onHeadsetTriggerActionChange("command_vol_down_single", it) }
            HeadsetGestureAssignment("Vol + · double tap", s.headsetTriggerActions["command_vol_up_double"] ?: "toggle_bass_boost") { a.onHeadsetTriggerActionChange("command_vol_up_double", it) }
            HeadsetGestureAssignment("Vol − · double tap", s.headsetTriggerActions["command_vol_down_double"] ?: "cycle_sleep_timer") { a.onHeadsetTriggerActionChange("command_vol_down_double", it) }
            HeadsetGestureAssignment("Centre · long-hold again", s.headsetTriggerActions["command_center_long_hold"] ?: "speak_full_briefing") { a.onHeadsetTriggerActionChange("command_center_long_hold", it) }
        }
        PremiumCard {
            Text("EQ SCROLL MODE", fontWeight = FontWeight.Bold)
            Text("Vol + / Vol − selects the next or previous preset. Each change is previewed for four seconds; five seconds of inactivity locks the preset.", color = VMuted, fontSize = 10.sp)
            Text("Sleep timer cycles 15 · 30 · 45 · 60 minutes · Off.", color = VMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun HeadsetGestureAssignment(
    title: String,
    selectedAction: String,
    onActionChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "speak_battery" to "Speak Battery",
        "enter_eq_scroll" to "Enter EQ Scroll Mode",
        "toggle_bass_boost" to "Toggle Bass Boost",
        "cycle_sleep_timer" to "Cycle Sleep Timer",
        "toggle_spatial" to "Toggle Spatial Audio",
        "toggle_volume_cap" to "Toggle Volume Cap",
        "speak_full_briefing" to "Speak Full Briefing",
        "disabled" to "Disabled"
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = VWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(options.firstOrNull { it.first == selectedAction }?.second ?: "Choose action")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.second) },
                        onClick = {
                            expanded = false
                            onActionChange(option.first)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorTab(s: VerlynProUiState, a: VerlynProUiActions) {
    var confirmPowerOff by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PremiumCard {
            Text("CONNECTION INSPECTOR", fontWeight = FontWeight.Bold)
            InspectorLine("Bluetooth audio", if (s.audioConnected) "Connected" else "Standby")
            InspectorLine("Classic RFCOMM", s.rfcommState)
            InspectorLine("BLE GATT", s.bleState)
            InspectorLine("Battery", s.batteryPercent?.let { "$it%" } ?: "Unavailable")
            InspectorLine("Battery source", s.batterySource)
            InspectorLine("Firmware", if (s.firmwareVersion.isBlank()) "Not received" else "FW " + s.firmwareVersion)
            InspectorLine("Model ID", s.modelId.ifBlank { "Not received" })
            InspectorLine("Noise control", s.noiseStatus.ifBlank { s.noiseMode })
            InspectorLine("Audio effect", s.effectStatus)
            InspectorLine("TTS engine", s.ttsStatus)
            s.audioSessionBindings.forEach { InspectorLine("Bound session", it) }
            InspectorLine("Smart keys", s.smartKeyStatus)
            TextButton(onClick = a.onScan) { Text("Refresh devices") }
            TextButton(onClick = a.onScanClassicSdp) { Text("Scan Classic SDP") }
            TextButton(onClick = a.onCopyDump) { Text("Copy diagnostic dump") }
            OutlinedButton(onClick = { confirmPowerOff = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Power off headset")
            }
        }
        PremiumCard {
            Text("RFCOMM HEX SENDER", color = VBlueLight, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = s.manualRfcommTargetUuid == ClassicRfcommEngine.JBL_VENDOR_UUID.toString(),
                    onClick = { a.onManualRfcommTargetChange(ClassicRfcommEngine.JBL_VENDOR_UUID.toString()) },
                    label = { Text("JBL Vendor SPP", fontSize = 9.sp) }
                )
                FilterChip(
                    selected = s.manualRfcommTargetUuid == ClassicRfcommEngine.AIROHA_RFCOMM_CANDIDATE_UUID.toString(),
                    onClick = { a.onManualRfcommTargetChange(ClassicRfcommEngine.AIROHA_RFCOMM_CANDIDATE_UUID.toString()) },
                    label = { Text("Airoha RACE", fontSize = 9.sp) }
                )
            }
            OutlinedTextField(
                value = s.manualRfcommHex,
                onValueChange = a.onManualRfcommHexChange,
                label = { Text("HCI-captured frame hex") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Text("Paste exact bytes from the Bluetooth HCI capture. No ANC payloads are preloaded.", color = VMuted, fontSize = 9.sp)
            Text("RFCOMM: " + s.rfcommState, color = VMuted, fontSize = 9.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { a.onRfcommConnect(s.manualRfcommTargetUuid) }, modifier = Modifier.weight(1f)) { Text("CONNECT") }
                Button(onClick = a.onSendRfcommHex, modifier = Modifier.weight(1f)) { Text("SEND HEX") }
            }
        }
        if (s.classicSdpUuids.isNotEmpty()) PremiumCard {
            Text("CLASSIC SERVICE UUIDS", color = VBlueLight, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            s.classicSdpUuids.forEach { Text(it, color = VMuted, fontSize = 9.sp) }
        }
        PremiumCard {
            Text("FAST PAIR RFCOMM", color = VBlueLight, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            if (s.rfcommCaptureLog.isEmpty()) Text("No packets captured", color = VMuted, fontSize = 10.sp)
            else s.rfcommCaptureLog.take(30).forEach { Text(it, color = VMuted, fontSize = 8.sp, lineHeight = 12.sp) }
        }
        PremiumCard {
            Text("AIROHA RACE TX / RX", color = VBlueLight, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            if (s.raceLog.isEmpty()) Text("No RACE frames captured", color = VMuted, fontSize = 10.sp)
            else s.raceLog.takeLast(80).asReversed().forEach {
                Text(it, color = VMuted, fontSize = 8.sp, lineHeight = 12.sp)
            }
        }
        PremiumCard {
            Text("RECENT EVENTS", color = VBlueLight, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            if (s.packetLog.isEmpty()) Text("No recent events", color = VMuted, fontSize = 10.sp)
            else s.packetLog.take(35).forEach { Text(it, color = VMuted, fontSize = 8.sp, lineHeight = 12.sp) }
        }
    }
    if (confirmPowerOff) {
        AlertDialog(
            onDismissRequest = { confirmPowerOff = false },
            title = { Text("Power off JBL Tune 770NC?") },
            text = { Text("The headset will disconnect and may need to be powered on manually.") },
            confirmButton = {
                TextButton(onClick = { confirmPowerOff = false; a.onPowerOffHeadset() }) { Text("POWER OFF") }
            },
            dismissButton = { TextButton(onClick = { confirmPowerOff = false }) { Text("CANCEL") } },
            containerColor = VCard
        )
    }
}

@Composable
private fun PremiumCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = VCard), border = BorderStroke(1.dp, VBorder)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
private fun Setting(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp)
            Text(subtitle, color = VMuted, fontSize = 9.sp)
        }
        trailing()
    }
}
@Composable
private fun Value(title: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String, change: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(88.dp), color = VMuted, fontSize = 10.sp)
        Slider(value, change, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "%.1f%s", value, suffix), Modifier.width(48.dp), color = VBlueLight, textAlign = TextAlign.End, fontSize = 9.sp)
    }
}
@Composable
private fun TinyTag(text: String, active: Boolean) {
    Surface(color = (if (active) VGreen else VMuted).copy(alpha = .14f), shape = RoundedCornerShape(20.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = if (active) VGreen else VMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun InspectorLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = VMuted, fontSize = 10.sp)
        Text(value, color = VWhite, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 190.dp))
    }
}
private fun routeText(route: String): String = when {
    route.contains("JBL Tune 770NC", true) -> "Bluetooth · JBL Tune 770NC"
    route.contains("A2DP", true) -> "Bluetooth audio"
    route.contains("speaker", true) -> "Phone speaker"
    else -> route.take(54)
}
private val smartOptions = listOf(
    "speak_time" to "Speak current time",
    "speak_battery" to "Speak headset & phone battery",
    "cycle_eq" to "Cycle EQ preset",
    "toggle_bass_boost" to "Toggle bass boost",
    "toggle_limiter" to "Toggle volume cap",
    "media_next" to "Next track",
    "media_previous" to "Previous track",
    "media_play_pause" to "Play / pause",
    "disabled" to "Off"
)
private val slots = listOf(
    Triple("trigger_volume_down_long", "Long-hold · Volume Down", "speak_time"),
    Triple("trigger_volume_up_long", "Long-hold · Volume Up", "speak_battery"),
    Triple("trigger_volume_down_double", "Double-press · Volume Down", "cycle_eq"),
    Triple("trigger_volume_up_double", "Double-press · Volume Up", "toggle_limiter")
)
private fun actionName(id: String): String = smartOptions.firstOrNull { it.first == id }?.second ?: "Off"
private fun slotName(id: String): String = slots.firstOrNull { it.first == id }?.second ?: id
