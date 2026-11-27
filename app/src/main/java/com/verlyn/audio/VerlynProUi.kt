package com.verlyn.audio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val VBlack = Color(0xFF000000)
private val VZinc = Color(0xFF141417)
private val VZinc2 = Color(0xFF202024)
private val VBlue = Color(0xFF2563EB)
private val VMuted = Color(0xFFA1A1AA)
private val VWhite = Color(0xFFF4F4F5)
private val VGreen = Color(0xFF4ADE80)
private val VOrange = Color(0xFFFBBF24)

data class VerlynProUiState(
    val selectedTab: Int,
    val audioConnected: Boolean,
    val a2dpConnected: Boolean,
    val headsetConnected: Boolean,
    val bleState: String,
    val rfcommState: String,
    val batteryPercent: Int?,
    val batteryDetail: String,
    val route: String,
    val appStatus: String,
    val scanning: Boolean,
    val classicDeviceFound: Boolean,
    val classicSdpStatus: String,
    val classicSdpUuids: List<String>,
    val gattSnapshotTitle: String,
    val gattSnapshotLines: List<String>,
    val airohaListening: Boolean,
    val airohaCanListen: Boolean,
    val airohaCaptureLog: List<String>,
    val rfcommCaptureLog: List<String>,
    val packetLog: List<String>,
    val gains: List<Float>,
    val eqEnabled: Boolean,
    val preamp: Float,
    val limiterEnabled: Boolean,
    val effectStatus: String,
    val selectedPreset: String,
    val customPresets: List<EqPreset>,
    val balanceEnabled: Boolean,
    val balance: Float,
    val sessionCompatibility: Boolean,
    val triggerActions: Map<String, String>,
    val accessibilityEnabled: Boolean,
    val noiseMode: String = "Unknown",
    val noiseControlReady: Boolean = false,
    val noiseSupportedModes: List<String> = emptyList(),
    val noiseStatus: String = "",
    val bassBoostEnabled: Boolean = false,
    val spatialEnabled: Boolean = false,
    val clarity: Float = 0f,
    val firmwareVersion: String = "",
    val modelId: String = "",
    val batterySource: String = "Unavailable",
    val audioSessionBindings: List<String> = emptyList(),
    val raceLog: List<String> = emptyList(),
    val ttsStatus: String = "TTS initializing",
    val testAudioStatus: String = "",
    val smartKeyStatus: String = "Waiting for volume-key input",
    val engineMode: String = "native",
    val headsetGesturesEnabled: Boolean = false,
    val headsetTriggerActions: Map<String, String> = emptyMap(),
    val manualRfcommTargetUuid: String = ClassicRfcommEngine.JBL_VENDOR_UUID.toString(),
    val manualRfcommHex: String = "",
    val companionConfigured: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val assistantSelected: Boolean = false,
    val nativeBandCentersHz: List<Int> = emptyList(),
    val audioBypassed: Boolean = false,
    val bassPunch: Float = 0f,
    val vocalPresence: Float = 0f,
    val trebleAir: Float = 0f,
    val bassBoostStrength: Float = 65f,
    val spatialStrength: Float = 65f
)

data class VerlynProUiActions(
    val onTabChange: (Int) -> Unit,
    val onConnectHeadset: () -> Unit,
    val onDisconnectHeadset: () -> Unit,
    val onScan: () -> Unit,
    val onBleConnect: () -> Unit,
    val onBleDisconnect: () -> Unit,
    val onRfcommConnect: (String) -> Unit,
    val onRfcommDisconnect: () -> Unit,
    val onScanClassicSdp: () -> Unit,
    val onAirohaListener: () -> Unit,
    val onCopyDump: () -> Unit,
    val onGainChange: (Int, Float) -> Unit,
    val onEqEnabledChange: (Boolean) -> Unit,
    val onPreset: (String) -> Unit,
    val onPreampChange: (Float) -> Unit,
    val onSavePreset: (String) -> Unit,
    val onRenamePreset: (String, String) -> Unit,
    val onDuplicatePreset: (String, String) -> Unit = { _, _ -> },
    val onDeletePreset: (String) -> Unit,
    val onBalanceEnabledChange: (Boolean) -> Unit,
    val onBalanceChange: (Float) -> Unit,
    val onLimiterChange: (Boolean) -> Unit,
    val onSessionCompatibilityChange: (Boolean) -> Unit,
    val onTriggerActionChange: (String, String) -> Unit,
    val onOpenAccessibilitySettings: () -> Unit,
    val onTestVoice: () -> Unit,
    val onNoiseModeChange: (String) -> Unit = {},
    val onBassBoostChange: (Boolean) -> Unit = {},
    val onSpatialChange: (Boolean) -> Unit = {},
    val onClarityChange: (Float) -> Unit = {},
    val onTestEq: () -> Unit = {},
    val onPowerOffHeadset: () -> Unit = {},
    val onEngineModeChange: (String) -> Unit = {},
    val onManualRfcommTargetChange: (String) -> Unit = {},
    val onManualRfcommHexChange: (String) -> Unit = {},
    val onSendRfcommHex: () -> Unit = {},
    val onHeadsetGesturesEnabledChange: (Boolean) -> Unit = {},
    val onHeadsetTriggerActionChange: (String, String) -> Unit = { _, _ -> },
    val onAssociateHeadset: () -> Unit = {},
    val onRequestBatteryUnrestricted: () -> Unit = {},
    val onOpenVoiceInputSettings: () -> Unit = {},
    val onEqBypassChange: (Boolean) -> Unit = {},
    val onMbcTuningChange: (String, Float) -> Unit = { _, _ -> },
    val onBassStrengthChange: (Float) -> Unit = {},
    val onSpatialStrengthChange: (Float) -> Unit = {}
)

private data class TriggerSlot(val id: String, val title: String, val defaultAction: String)
private val triggerSlots = listOf(
    TriggerSlot("trigger_volume_down_long", "Long-hold · Volume Down", "speak_time"),
    TriggerSlot("trigger_volume_up_long", "Long-hold · Volume Up", "speak_battery"),
    TriggerSlot("trigger_volume_down_double", "Double-press · Volume Down", "cycle_eq"),
    TriggerSlot("trigger_volume_up_double", "Double-press · Volume Up", "toggle_limiter")
)
private val smartActionOptions = listOf(
    "speak_time" to "Speak exact current time",
    "speak_battery" to "Speak battery status",
    "cycle_eq" to "Cycle saved EQ presets",
    "toggle_limiter" to "Toggle max volume limiter",
    "media_next" to "Media · Next",
    "media_previous" to "Media · Previous",
    "media_play_pause" to "Media · Play / Pause",
    "disabled" to "Disabled"
)
private val builtInPresetNames = listOf("Flat", "Studio", "Bass", "Club", "Extreme Bass", "Vocal", "Jazz", "Podcast", "Gaming")
private val eqLabels = listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

@Composable
fun VerlynProApp(state: VerlynProUiState, actions: VerlynProUiActions) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = VBlue, onPrimary = Color.White, background = VBlack,
        onBackground = VWhite, surface = VZinc, onSurface = VWhite, secondary = VBlue
    )) {
        var ambientTarget by remember { mutableStateOf("ANC") }
        var ambientEnabled by remember { mutableStateOf(true) }
        Scaffold(
            containerColor = VBlack,
            bottomBar = {
                NavigationBar(containerColor = VZinc, contentColor = VWhite) {
                    listOf("Control", "Equalizer", "Smart", "Inspector").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = state.selectedTab == index,
                            onClick = { actions.onTabChange(index) },
                            icon = {
                                Icon(when (index) {
                                    0 -> Icons.Filled.Headphones
                                    1 -> Icons.Filled.MusicNote
                                    2 -> Icons.Filled.Settings
                                    else -> Icons.Filled.Bluetooth
                                }, contentDescription = label)
                            },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White, selectedTextColor = Color.White,
                                indicatorColor = VBlue, unselectedIconColor = VMuted, unselectedTextColor = VMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VERLYN", fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text("PERSONAL AUDIO SYSTEM", fontSize = 9.sp, color = VMuted, letterSpacing = 1.5.sp)
                    }
                    Surface(color = VZinc, shape = CircleShape) {
                        Icon(Icons.Filled.Bluetooth, "Bluetooth", tint = VBlue, modifier = Modifier.padding(10.dp).size(22.dp))
                    }
                }
                when (state.selectedTab) {
                    0 -> ControlDashboard(state, actions, ambientTarget, { ambientTarget = it }, ambientEnabled, { ambientEnabled = it })
                    1 -> ProEqualizerPage(state, actions)
                    2 -> SmartActionsPage(state, actions)
                    else -> ProInspectorPage(state, actions)
                }
            }
        }
    }
}

@Composable
private fun ControlDashboard(
    state: VerlynProUiState,
    actions: VerlynProUiActions,
    ambientTarget: String,
    onAmbientTarget: (String) -> Unit,
    ambientEnabled: Boolean,
    onAmbientEnabled: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = VZinc2, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Filled.Headphones, null, tint = VBlue, modifier = Modifier.padding(16.dp).size(34.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("JBL TUNE 770NC", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Wireless over-ear headphones", color = VMuted, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(state.batteryPercent?.let { it.toString() + "%" } ?: "Unavailable", color = VWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("HEADSET BATTERY", color = VMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
            if (state.batteryPercent != null) {
                LinearProgressIndicator(
                    progress = { state.batteryPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp), color = VBlue, trackColor = VZinc2
                )
            }
            Text(state.batteryDetail, color = VMuted, fontSize = 9.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LinkPill(if (state.audioConnected) "AUDIO CONNECTED" else "AUDIO OFF", state.audioConnected)
                LinkPill(if (state.rfcommState.startsWith("Connected")) "RFCOMM CONNECTED" else "RFCOMM OFF", state.rfcommState.startsWith("Connected"))
                LinkPill("BLE " + state.bleState, state.bleState == "CONNECTED")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onConnectHeadset,
                    enabled = !state.rfcommState.startsWith("Connecting") && !state.rfcommState.startsWith("Connected"),
                    colors = ButtonDefaults.buttonColors(containerColor = VBlue), modifier = Modifier.weight(1f)
                ) { Text("Connect Control Link") }
                OutlinedButton(
                    onClick = actions.onDisconnectHeadset,
                    enabled = state.rfcommState.startsWith("Connecting") || state.rfcommState.startsWith("Connected"),
                    modifier = Modifier.weight(1f), border = BorderStroke(1.dp, VZinc2)
                ) { Text("Disconnect") }
            }
            Text(state.route, color = VMuted, fontSize = 10.sp)
            Text(state.appStatus, color = VMuted, fontSize = 10.sp)
            if (state.scanning) Text("Scanning nearby Bluetooth devices…", color = VBlue, fontSize = 9.sp)
        }

        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AMBIENT SOUND CONTROL", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = .7.sp)
                    Text("Noise control modes · protocol not yet verified", color = VMuted, fontSize = 10.sp)
                }
                Switch(checked = ambientEnabled, onCheckedChange = onAmbientEnabled)
            }
            SegmentedChoice(listOf("ANC", "Ambient Aware", "TalkThru"), ambientTarget, onAmbientTarget)
            BadgeLabel("Awaiting RFCOMM Protocol Verification", VOrange)
            Text("Selection is a local target preview only. No guessed hardware command is sent and no device mode is claimed to have changed.", color = VMuted, fontSize = 10.sp)
        }

        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("EQUALIZER", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(state.selectedPreset, color = VMuted, fontSize = 10.sp)
                }
                TextButton(onClick = { actions.onTabChange(1) }) { Text("EDIT EQ", color = VBlue) }
            }
            SplineEqualizer(state.gains, { _, _ -> }, false, Modifier.fillMaxWidth().height(112.dp))
            Text("10-band DynamicsProcessing · " + state.effectStatus, color = VMuted, fontSize = 9.sp)
        }

        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LEFT / RIGHT SOUND BALANCE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Native channel input-gain trim", color = VMuted, fontSize = 10.sp)
                }
                Switch(checked = state.balanceEnabled, onCheckedChange = actions.onBalanceEnabledChange)
            }
            Slider(value = state.balance, onValueChange = actions.onBalanceChange, enabled = state.balanceEnabled, valueRange = -1f..1f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("L", color = VMuted, fontWeight = FontWeight.Bold)
                Text(if (!state.balanceEnabled || kotlin.math.abs(state.balance) < .03f) "CENTER" else if (state.balance < 0) "LEFT" else "RIGHT", color = VBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("R", color = VMuted, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { actions.onBalanceChange(0f) }, enabled = state.balanceEnabled) { Text("RESET TO CENTER") }
            Text("Balance uses DynamicsProcessing channel gain. Bluetooth route support depends on Android/OEM audio-effect routing.", color = VMuted, fontSize = 9.sp)
        }

        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MAX VOLUME LIMITER", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Android stream ceiling + DSP limiter", color = VMuted, fontSize = 10.sp)
                }
                Switch(checked = state.limiterEnabled, onCheckedChange = actions.onLimiterChange)
            }
            BadgeLabel("Software limit · not an SPL-calibrated 85 dB cap", VOrange)
            Text("Android volume steps are not a sound-pressure meter. Actual dB SPL depends on headphone sensitivity, fit, source level and Bluetooth path.", color = VMuted, fontSize = 9.sp)
        }

        ProCard {
            Text("HEADPHONE SETTINGS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            LockedSetting("VoiceAware", "Low · Mid · High")
            LockedSetting("Smart Audio & Video", "Audio Mode · Video Mode")
            LockedSetting("Auto Power Off", "30 min · 1 hr · 2 hr")
            LockedSetting("Voice Prompts", "Language selection")
            LockedSetting("Voice Assistant", "Assistant selection")
            LockedSetting("LE Audio", "Capability negotiation")
            HorizontalDivider(color = VZinc2)
            Text("DEVICE INFORMATION", color = VWhite, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Text("JBL Tune 770NC · Classic SDP services: " + state.classicSdpUuids.size + " · BLE services: " + state.gattSnapshotLines.count { it.startsWith("SERVICE") }, color = VMuted, fontSize = 10.sp)
            Text("Proprietary controls stay locked until a verified protocol and safe command/response pair are available.", color = VMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ProEqualizerPage(state: VerlynProUiState, actions: VerlynProUiActions) {
    var saveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EqPreset?>(null) }
    var presetName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("10-BAND GRAPHIC EQ", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("32 Hz – 16 kHz · ±12 dB", color = VMuted, fontSize = 10.sp)
                }
                Switch(checked = state.eqEnabled, onCheckedChange = actions.onEqEnabledChange)
            }
            SplineEqualizer(state.gains, actions.onGainChange, true, Modifier.fillMaxWidth().height(190.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("-12 dB", color = VMuted, fontSize = 9.sp)
                Text("+12 dB", color = VMuted, fontSize = 9.sp)
            }
            Text(state.effectStatus, color = if (state.effectStatus.startsWith("Active")) VGreen else VMuted, fontSize = 10.sp)
        }
        ProCard {
            Text("BUILT-IN PRESETS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            FlowPresetChips(builtInPresetNames, state.selectedPreset, actions.onPreset)
            Text("CUSTOM PRESETS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (state.customPresets.isEmpty()) Text("No custom presets saved yet.", color = VMuted, fontSize = 10.sp)
            else state.customPresets.forEach { preset ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { actions.onPreset(preset.name) }) {
                        Text(preset.name, color = if (state.selectedPreset == preset.name) VBlue else VWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(preset.gains.joinToString(" · ") { String.format(Locale.US, "%.0f", it) }, color = VMuted, fontSize = 8.sp, maxLines = 1)
                    }
                    TextButton(onClick = { presetName = preset.name; renameTarget = preset }) { Text("RENAME", fontSize = 9.sp) }
                    TextButton(onClick = { actions.onDeletePreset(preset.name) }) { Text("DELETE", fontSize = 9.sp, color = VOrange) }
                }
                HorizontalDivider(color = VZinc2)
            }
            Button(onClick = { presetName = ""; saveDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = VBlue), modifier = Modifier.fillMaxWidth()) {
                Text("SAVE CURRENT EQ AS PRESET")
            }
        }
        ProCard {
            Text("PREAMP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            ValueSlider(state.preamp, -12f, 12f, actions.onPreampChange, "dB")
            HorizontalDivider(color = VZinc2)
            Text("FREQUENCY BANDS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            eqLabels.forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = VMuted, fontSize = 10.sp, modifier = Modifier.width(37.dp))
                    Slider(value = state.gains.getOrElse(index) { 0f }, onValueChange = { actions.onGainChange(index, it) }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
                    Text(String.format(Locale.US, "%+.1f", state.gains.getOrElse(index) { 0f }), color = VBlue, fontSize = 9.sp, modifier = Modifier.width(42.dp))
                }
            }
        }
        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MEDIA SESSION COMPATIBILITY", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Attach processing when a media app opens an effect session.", color = VMuted, fontSize = 9.sp)
                }
                Switch(checked = state.sessionCompatibility, onCheckedChange = actions.onSessionCompatibilityChange)
            }
            Text("Effects are subject to Android/OEM routing and may not affect every Bluetooth codec.", color = VMuted, fontSize = 9.sp)
        }
    }

    if (saveDialog || renameTarget != null) {
        val renaming = renameTarget != null
        AlertDialog(
            onDismissRequest = { saveDialog = false; renameTarget = null },
            containerColor = VZinc,
            title = { Text(if (renaming) "Rename EQ preset" else "Save EQ preset") },
            text = { OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(32) }, singleLine = true, label = { Text("Preset name") }) },
            confirmButton = {
                TextButton(onClick = {
                    val clean = presetName.trim()
                    if (clean.isNotEmpty()) {
                        if (renaming) actions.onRenamePreset(renameTarget!!.name, clean) else actions.onSavePreset(clean)
                    }
                    saveDialog = false
                    renameTarget = null
                }) { Text("SAVE", color = VBlue) }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false; renameTarget = null }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun SmartActionsPage(state: VerlynProUiState, actions: VerlynProUiActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProCard {
            Text("SMART BUTTONS", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Map volume-key gestures to Verlyn actions. Android requires the optional Accessibility Service to intercept key events.", color = VMuted, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Verlyn Smart Button Service", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text(if (state.accessibilityEnabled) "Enabled" else "Not enabled", color = if (state.accessibilityEnabled) VGreen else VOrange, fontSize = 10.sp)
                }
                Button(onClick = actions.onOpenAccessibilitySettings, colors = ButtonDefaults.buttonColors(containerColor = VBlue)) {
                    Text(if (state.accessibilityEnabled) "OPEN SETTINGS" else "SET UP")
                }
            }
            Button(onClick = actions.onTestVoice, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = VZinc2)) {
                Text("TEST VOICE OUTPUT IN HEADSET")
            }
            Text("Announcements request transient may-duck audio focus and follow Android's current output route; Verlyn cannot force TTS into a specific headset.", color = VMuted, fontSize = 9.sp)
        }
        ProCard {
            Text("GESTURE ASSIGNMENTS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            triggerSlots.forEach { slot ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(slot.title, color = VWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    var expanded by remember(slot.id) { mutableStateOf(false) }
                    val selected = state.triggerActions[slot.id] ?: slot.defaultAction
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, VZinc2)) {
                            Text(smartActionOptions.firstOrNull { it.first == selected }?.second ?: "Disabled", color = VWhite)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            smartActionOptions.forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { actions.onTriggerActionChange(slot.id, key); expanded = false })
                            }
                        }
                    }
                }
                HorizontalDivider(color = VZinc2)
            }
            Text("Defaults: hold Volume Down = time; hold Volume Up = battery; double Volume Down = cycle EQ; double Volume Up = limiter.", color = VMuted, fontSize = 9.sp)
        }
        ProCard {
            Text("AVAILABLE ACTIONS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            listOf(
                "Speak exact current time",
                "Speak phone and headset battery when available",
                "Cycle and announce saved EQ presets",
                "Toggle the software max-volume limiter",
                "Media next, previous or play / pause"
            ).forEach { Text("•  " + it, color = VMuted, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun ProInspectorPage(state: VerlynProUiState, actions: VerlynProUiActions) {
    var selectedEndpoint by remember { mutableStateOf(ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID.toString()) }
    val endpoints = listOf(
        ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID.toString() to "Google Fast Pair Message Stream",
        ClassicRfcommEngine.JBL_VENDOR_UUID.toString() to "JBL vendor UUID · unverified",
        ClassicRfcommEngine.AIROHA_RFCOMM_CANDIDATE_UUID.toString() to "Custom / Airoha candidate · unverified"
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CLASSIC RFCOMM STREAM", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(state.rfcommState, color = VMuted, fontSize = 10.sp)
                }
                StatusPillPro(if (state.rfcommState.startsWith("Connected")) "LIVE" else "IDLE", state.rfcommState.startsWith("Connected"))
            }
            Text("Paired JBL Tune 770NC · choose SDP endpoint", color = VMuted, fontSize = 9.sp)
            endpoints.forEach { (uuid, label) ->
                Row(Modifier.fillMaxWidth().clickable { selectedEndpoint = uuid }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedEndpoint == uuid, onClick = { selectedEndpoint = uuid }, colors = RadioButtonDefaults.colors(selectedColor = VBlue))
                    Column(Modifier.weight(1f)) {
                        Text(label, color = VWhite, fontSize = 10.sp)
                        Text(uuid, color = VMuted, fontSize = 8.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.onRfcommConnect(selectedEndpoint) },
                    enabled = state.classicDeviceFound && !state.rfcommState.startsWith("Connecting") && !state.rfcommState.startsWith("Connected"),
                    colors = ButtonDefaults.buttonColors(containerColor = VBlue), modifier = Modifier.weight(1f)
                ) { Text("CONNECT & LISTEN") }
                OutlinedButton(onClick = actions.onRfcommDisconnect, enabled = state.rfcommState.startsWith("Connected") || state.rfcommState.startsWith("Connecting")) { Text("STOP") }
            }
            Text("Read-only capture. Verlyn never writes to RFCOMM. An SDP UUID does not guarantee a usable RFCOMM service.", color = VMuted, fontSize = 9.sp)
            Text("RFCOMM chunks · " + state.rfcommCaptureLog.size, color = VBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            LogLines(state.rfcommCaptureLog, "No RFCOMM packets captured.")
        }
        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CLASSIC SDP UUID MAP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(state.classicSdpStatus, color = VMuted, fontSize = 9.sp)
                }
                TextButton(onClick = actions.onScanClassicSdp, enabled = state.classicDeviceFound) { Text("SCAN") }
            }
            if (state.classicSdpUuids.isEmpty()) Text("No Classic UUIDs cached.", color = VMuted, fontSize = 10.sp)
            else state.classicSdpUuids.forEach { Text(it, color = VWhite, fontSize = 9.sp) }
        }
        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BLE GATT SNAPSHOT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(state.gattSnapshotTitle + " · " + state.bleState, color = VMuted, fontSize = 9.sp)
                }
                StatusPillPro("CACHE", state.bleState == "DISCONNECTED")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onBleConnect, colors = ButtonDefaults.buttonColors(containerColor = VBlue), modifier = Modifier.weight(1f)) { Text("CONNECT BLE") }
                OutlinedButton(onClick = actions.onBleDisconnect, modifier = Modifier.weight(1f)) { Text("DISCONNECT") }
            }
            Text("Airoha RACE passive RX listener", color = VWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.airohaListening) "Listening · notifications only" else "No active capture", color = VMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.airohaListening, onCheckedChange = { actions.onAirohaListener() }, enabled = state.airohaCanListen)
            }
            Text("Airoha TX writes are blocked. Passive capture only.", color = VMuted, fontSize = 9.sp)
            LogLines(state.airohaCaptureLog, "No Airoha RX packets captured.")
            HorizontalDivider(color = VZinc2)
            Text(state.gattSnapshotTitle, color = VBlue, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            if (state.gattSnapshotLines.isEmpty()) Text("No GATT snapshot captured yet.", color = VMuted, fontSize = 10.sp)
            else state.gattSnapshotLines.forEach { Text(it, color = VWhite, fontSize = 8.sp) }
        }
        ProCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("COPY COMPLETE DIAGNOSTIC DUMP", color = VWhite, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Button(onClick = actions.onCopyDump, colors = ButtonDefaults.buttonColors(containerColor = VBlue)) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("COPY DUMP")
                }
            }
            Text("Includes RFCOMM, Fast Pair battery frames, Classic SDP, cached BLE GATT services, Airoha RX and operation logs.", color = VMuted, fontSize = 9.sp)
            Text("RECENT EVENTS", color = VBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            LogLines(state.packetLog, "No diagnostic events.")
        }
    }
}

@Composable
private fun ProCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = VZinc), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun SplineEqualizer(gains: List<Float>, onGainChange: (Int, Float) -> Unit, interactive: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.pointerInput(interactive) {
        if (interactive) {
            detectDragGestures { change, _ ->
                val x = change.position.x.coerceIn(0f, size.width.toFloat())
                val y = change.position.y.coerceIn(0f, size.height.toFloat())
                val index = ((x / size.width) * (gains.size - 1)).roundToInt().coerceIn(0, gains.lastIndex)
                val db = (12f - (y / size.height) * 24f).coerceIn(-12f, 12f)
                onGainChange(index, db)
                change.consume()
            }
        }
    }) {
        val source = if (gains.size == 10) gains else List(10) { 0f }
        for (line in 0..4) {
            val y = size.height * line / 4f
            drawLine(Color(0xFF303036), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val pts = source.mapIndexed { i, value ->
            Offset(size.width * i / (source.size - 1f), size.height * (12f - value.coerceIn(-12f, 12f)) / 24f)
        }
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 0 until pts.lastIndex) {
                val a = pts[i]
                val b = pts[i + 1]
                val dx = (b.x - a.x) / 3f
                cubicTo(a.x + dx, a.y, b.x - dx, b.y, b.x, b.y)
            }
        }
        drawPath(path, VBlue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        pts.forEach { p ->
            drawCircle(VBlack, 7.dp.toPx(), p)
            drawCircle(VBlue, 4.dp.toPx(), p)
        }
    }
}

@Composable
private fun FlowPresetChips(names: List<String>, selected: String, onClick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.chunked(3).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                group.forEach { name ->
                    val active = selected == name
                    Surface(
                        Modifier.weight(1f).clickable { onClick(name) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) VBlue else VZinc2,
                        border = if (active) null else BorderStroke(1.dp, Color(0xFF303036))
                    ) {
                        Text(name, color = VWhite, fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 3.dp), textAlign = TextAlign.Center)
                    }
                }
                repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SegmentedChoice(values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(VBlack, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        values.forEach { value ->
            Surface(Modifier.weight(1f).clickable { onSelect(value) }, color = if (selected == value) VBlue else Color.Transparent, shape = RoundedCornerShape(9.dp)) {
                Text(value, color = VWhite, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp))
            }
        }
    }
}

@Composable
private fun LockedSetting(name: String, options: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = VWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(options, color = VMuted, fontSize = 9.sp)
        }
        BadgeLabel("Awaiting verification", VMuted)
    }
}

@Composable
private fun BadgeLabel(label: String, color: Color) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(30.dp)) {
        Text(label, color = color, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun LinkPill(label: String, connected: Boolean) {
    Surface(color = (if (connected) VGreen else VMuted).copy(alpha = .12f), shape = RoundedCornerShape(20.dp)) {
        Text(label, color = if (connected) VGreen else VMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable
private fun StatusPillPro(label: String, active: Boolean) = BadgeLabel(label, if (active) VGreen else VMuted)

@Composable
private fun ValueSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit, suffix: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(value, onChange, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "%.1f %s", value, suffix), color = VBlue, fontSize = 10.sp, modifier = Modifier.width(65.dp))
    }
}

@Composable
private fun LogLines(lines: List<String>, empty: String) {
    if (lines.isEmpty()) Text(empty, color = VMuted, fontSize = 9.sp)
    else lines.take(80).forEach { line ->
        Text(line, color = Color(0xFFD4D4D8), fontSize = 8.sp, lineHeight = 12.sp)
        HorizontalDivider(color = VZinc2)
    }
}
