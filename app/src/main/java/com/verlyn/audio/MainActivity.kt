@file:Suppress("DEPRECATION")

package com.verlyn.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentName
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.bluetooth.le.BluetoothDeviceFilter
import android.net.Uri
import android.os.PowerManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.audiofx.AudioEffect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

private val Ink = Color(0xFF090B10)
private val Panel = Color(0xFF151922)
private val PanelSoft = Color(0xFF1D2430)
private val Blue = Color(0xFF4C8DFF)
private val Muted = Color(0xFF9AA4B2)
private val Green = Color(0xFF69D69A)
private val BatteryServiceUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
private val BatteryLevelUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
private val CccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val AirohaRaceServiceUuid = UUID.fromString("5052494d-2dab-0341-6972-6f6861424c45")
private val AirohaTxCharacteristicUuid = UUID.fromString("43484152-2dab-3241-6972-6f6861424c45")
private val AirohaRxCharacteristicUuid = UUID.fromString("43484152-2dab-3141-6972-6f6861424c45")
private val AmazonAmaServiceUuid = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
private val GoogleFastPairServiceUuid = UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
private val DultServiceUuid = UUID.fromString("15190001-12f4-c226-88ed-2ac5579f2a85")
private const val BATTERY_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
private const val BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"

private data class DeviceRow(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int?,
    val paired: Boolean
) {
    val isJbl: Boolean
        get() = name.contains("JBL Tune 770NC", ignoreCase = true) || name.contains("JBL Tune770NC", ignoreCase = true)
}

private class GattCharacteristicRow(
    val serviceUuid: UUID,
    val characteristic: BluetoothGattCharacteristic
) {
    val uuid: UUID get() = characteristic.uuid
    val key: String get() = "$serviceUuid/$uuid"
    val properties: Int = characteristic.properties
    val descriptorUuids: List<UUID> = characteristic.descriptors.map { it.uuid }
    var valueHex by mutableStateOf("No value read")
    var valueText by mutableStateOf("—")
    var notificationsEnabled by mutableStateOf(false)
}

private class GattServiceRow(val service: android.bluetooth.BluetoothGattService) {
    val uuid: UUID get() = service.uuid
    var expanded by mutableStateOf(true)
    val characteristics = service.characteristics.map { GattCharacteristicRow(service.uuid, it) }
}

private data class GattOperation(
    val label: String,
    val timeoutMs: Long = 4_000L,
    val start: (BluetoothGatt) -> Boolean,
    val characteristicUuid: UUID? = null,
    val descriptor: BluetoothGattDescriptor? = null,
    val onComplete: (Boolean) -> Unit = {}
)

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceRows = mutableStateListOf<DeviceRow>()
    private val gattServices = mutableStateListOf<GattServiceRow>()
    private val packetLog = mutableStateListOf<String>()
    private val airohaCaptureLog = mutableStateListOf<String>()
    private val classicSdpUuids = mutableStateListOf<UUID>()
    private val rfcommCaptureLog = mutableStateListOf<String>()
    private val customPresets = mutableStateListOf<EqPreset>()
    private val smartTriggerActions = mutableStateMapOf<String, String>()
    private lateinit var voiceAnnouncer: VoiceAnnouncer
    private lateinit var eqTestPlayer: EqTestAudioPlayer
    private var rfcommState by mutableStateOf("Disconnected")
    private var headsetBatteryPercent by mutableStateOf<Int?>(null)
    private var headsetBatteryDetail by mutableStateOf("Connect Fast Pair RFCOMM to receive headset battery events")
    private var balanceEnabled by mutableStateOf(false)
    private var balance by mutableFloatStateOf(0f)
    private var accessibilityEnabled by mutableStateOf(false)
    private var companionConfigured by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)
    private var assistantSelected by mutableStateOf(false)

    private val companionAssociationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val device = result.data?.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
            if (device != null) {
                preferences.edit().putString("companion_mac", device.address)
                    .putBoolean("companion_device_associated", true).apply()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        (getSystemService(COMPANION_DEVICE_SERVICE) as CompanionDeviceManager)
                            .startObservingDevicePresence(device.address)
                    }.onFailure { appendLog("Companion presence observer failed · " + (it.message ?: "unsupported")) }
                }
                refreshQuickSetupStatus()
                Toast.makeText(this, "JBL companion association saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val gattOperationQueue = ArrayDeque<GattOperation>()
    private var activeGattOperation: GattOperation? = null
    private var pendingGattDevice: BluetoothDevice? = null
    private var airohaListening by mutableStateOf(false)
    private var gattSnapshotCached by mutableStateOf(false)
    private var snapshotDeviceName by mutableStateOf("Not captured")
    private var snapshotDeviceAddress by mutableStateOf("Unavailable")
    private var classicJblDevice: BluetoothDevice? = null
    private var classicSdpStatus by mutableStateOf("Not scanned")
    private var classicSdpScanInFlight = false
    private var lastClassicSdpScanAt = 0L
    private val classicSdpScanTimeout = Runnable {
        classicSdpScanInFlight = false
        if (classicSdpStatus.contains("pending", true)) classicSdpStatus = "SDP scan timed out; tap to retry"
    }

    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanActive = false
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var audioManager: AudioManager? = null
    private lateinit var audioEngine: VerlynAudioEngine

    private var appStatus by mutableStateOf("Ready")
    private var bleState by mutableStateOf("DISCONNECTED")
    private var a2dpConnected by mutableStateOf(false)
    private var headsetConnected by mutableStateOf(false)
    private var batteryLevel by mutableStateOf<Int?>(null)
    private var rssi by mutableStateOf<Int?>(null)
    private var mtu by mutableStateOf<Int?>(null)
    private val gattOperationTimeout = Runnable {
        val operation = activeGattOperation ?: return@Runnable
        appendLog("${operation.label} timed out; aborting this GATT session instead of advancing the queue")
        activeGattOperation = null
        val dropped = gattOperationQueue.size
        gattOperationQueue.clear()
        if (dropped > 0) appendLog("Discarded $dropped queued operation(s) after timeout")
        try { operation.onComplete(false) } catch (_: Throwable) {}
        disconnectGatt("GATT operation timed out: ${operation.label}")
    }
    private var outputRoute by mutableStateOf("No active output route reported")
    private var audioEffectStatus by mutableStateOf("Initializing")
    private var serviceBatteryPercent by mutableStateOf<Int?>(null)
    private var serviceBatterySource by mutableStateOf("Unavailable")
    private var firmwareVersion by mutableStateOf("")
    private var modelId by mutableStateOf("")
    private var audioSessionBindings by mutableStateOf(emptyList<String>())
    private val raceCaptureLog = mutableStateListOf<String>()
    private var ttsStatus by mutableStateOf("TTS initializing")
    private var testAudioStatus by mutableStateOf("")
    private var smartKeyStatus by mutableStateOf("Waiting for volume-key input")
    private var serviceNoiseMode by mutableStateOf("Unknown")
    private var serviceNoiseReady by mutableStateOf(false)
    private var serviceNoiseModes by mutableStateOf(emptyList<String>())
    private var serviceNoiseStatus by mutableStateOf("Waiting for headset")
    private var clarityValue by mutableFloatStateOf(0f)
    private var bassBoostEnabled by mutableStateOf(false)
    private var spatialEnabled by mutableStateOf(false)
    private var lastAppliedJblRoute: Boolean? = null
    private var scanning by mutableStateOf(false)
    private var selectedTab by mutableIntStateOf(0)
    private var inspectorExpanded by mutableStateOf(true)
    private var writeTarget by mutableStateOf<GattCharacteristicRow?>(null)
    private var writeHexInput by mutableStateOf("")
    private var manualRfcommTargetUuid by mutableStateOf(ClassicRfcommEngine.JBL_VENDOR_UUID.toString())
    private var manualRfcommHex by mutableStateOf("")
    private var engineMode by mutableStateOf("native")
    private var headsetGesturesEnabled by mutableStateOf(false)
    private val headsetTriggerActions = mutableStateMapOf<String, String>()
    private var eqEnabled by mutableStateOf(true)
    private var limiterEnabled by mutableStateOf(true)
    private var sessionCompatibility by mutableStateOf(true)
    private var preamp by mutableFloatStateOf(0f)
    private var musicVolume by mutableIntStateOf(0)
    private var maxMusicVolume by mutableIntStateOf(15)
    private var selectedPreset by mutableStateOf("Flat")
    private val eqGains = mutableStateListOf<Float>().apply { repeat(10) { add(0f) } }
    private val compatibilitySessions = mutableSetOf<Int>()

    private val preferences by lazy { getSharedPreferences("verlyn_audio_preferences", Context.MODE_PRIVATE) }

    companion object { private const val ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL = "android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL" }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
            }
            refreshClassicState()
        }
        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> { a2dpProxy = null; a2dpConnected = false }
                BluetoothProfile.HEADSET -> { headsetProxy = null; headsetConnected = false }
            }
        }
    }

    private val scanTimeout = Runnable {
        stopScan()
        appStatus = if (deviceRows.isEmpty()) "Scan complete. No nearby devices found."
        else "Scan complete. Found ${deviceRows.size} device(s)."
    }

    private val routePoll = object : Runnable {
        override fun run() { refreshOutputRoute(); mainHandler.postDelayed(this, 4_000L) }
    }

    private val audioApplyTask = Runnable {
        saveAudioPreferences()
        val gains = eqGains.toFloatArray()
        val currentPreamp = preamp
        val enabled = eqEnabled
        val limited = limiterEnabled
        val channelBalance = if (balanceEnabled) balance else 0f
        lifecycleScope.launch(Dispatchers.Default) {
            if (!audioEngine.hasGlobalMixEffect()) audioEngine.attachGlobal(gains, currentPreamp, enabled, limited, channelBalance)
            else audioEngine.apply(gains, currentPreamp, enabled, limited, channelBalance)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                loadPairedDevices()
                startScan()
                requestProfileProxies()
                startConnectionService()
            } else appStatus = "Nearby devices permission is required for Bluetooth discovery."
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try { device.name ?: result.scanRecord?.deviceName ?: "Unknown Bluetooth device" }
            catch (_: SecurityException) { "Bluetooth device" }
            val address = try { device.address } catch (_: SecurityException) { "Unavailable" }
            runOnUiThread {
                upsertDevice(device, name, address, result.rssi, false)
                if (isJblName(name) && targetDevice == null) {
                    targetDevice = device
                    refreshClassicState()
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                mainHandler.removeCallbacks(scanTimeout)
                scanActive = false
                scanning = false
                appStatus = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "A Bluetooth scan is already active."
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android could not register the BLE scan. Toggle Bluetooth and retry."
                    SCAN_FAILED_INTERNAL_ERROR -> "Android Bluetooth reported an internal scan error."
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support BLE scanning."
                    SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth scan resources are busy."
                    SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scans are too frequent. Wait a few seconds."
                    else -> "BLE scan failed (code $errorCode)."
                }
                appendLog("BLE scan failed: code $errorCode")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (callbackGatt !== gatt) {
                            appendLog("Ignoring stale GATT connected callback")
                            try { callbackGatt.close() } catch (_: Throwable) {}
                            return@runOnUiThread
                        }
                        if (status != BluetoothGatt.GATT_SUCCESS) appendLog("GATT connected callback reported status $status")
                        bleState = "CONNECTED"
                        appStatus = "BLE GATT connected"
                        appendLog("GATT connected to ${safeDeviceName(callbackGatt.device)}")
                        enqueueGattOperation(
                            GattOperation(
                                label = "Discover GATT services",
                                timeoutMs = 12_000L,
                                start = { active ->
                                    appendLog("Starting GATT service discovery (default MTU; no concurrent MTU negotiation)")
                                    active.discoverServices()
                                }
                            )
                        )
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        bleState = "CONNECTING"
                        appendLog("GATT connecting (status $status)")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasCurrentGatt = callbackGatt === gatt
                        if (wasCurrentGatt) {
                            bleState = "DISCONNECTED"
                            batteryLevel = null
                            rssi = null
                            mtu = null
                            gattSnapshotCached = gattServices.isNotEmpty()
                            airohaListening = false
                            cancelGattOperations("GATT disconnected")
                            gattServices.flatMap { it.characteristics }.forEach { it.notificationsEnabled = false }
                            gatt = null
                        }
                        appendLog("GATT disconnected (status $status)")
                        try { callbackGatt.close() } catch (_: Throwable) {}
                        if (wasCurrentGatt) startPendingGattConnection()
                    }
                }
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, value: Int, status: Int) {
            runOnUiThread {
                if (callbackGatt !== gatt) return@runOnUiThread
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) value else null
                appendLog("MTU result: ${if (status == BluetoothGatt.GATT_SUCCESS) value else "failed ($status)"}")
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (callbackGatt !== gatt) return@runOnUiThread
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gattServices.clear()
                    callbackGatt.services.forEach { gattServices.add(GattServiceRow(it)) }
                    gattSnapshotCached = false
                    snapshotDeviceName = safeDeviceName(callbackGatt.device)
                    snapshotDeviceAddress = safeAddress(callbackGatt.device)
                    appendLog("Discovered ${callbackGatt.services.size} GATT service(s); snapshot refreshed")
                    airohaListening = false
                    if (callbackGatt.getService(BatteryServiceUuid) == null) {
                        batteryLevel = null
                        appendLog("Standard Battery Service not exposed; no automatic characteristic reads queued")
                    } else {
                        appendLog("Battery Service present; automatic reads remain disabled")
                    }
                    if (activeGattOperation?.label == "Discover GATT services") completeGattOperation(true)
                } else {
                    appendLog("Service discovery failed: status $status")
                    if (activeGattOperation?.label == "Discover GATT services") completeGattOperation(false)
                }
            }
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            runOnUiThread {
                if (callbackGatt !== gatt) return@runOnUiThread
                handleCharacteristicValue(characteristic, value, status, "READ")
                val active = activeGattOperation
                if (active?.label?.startsWith("READ ") == true && active.characteristicUuid == characteristic.uuid) {
                    completeGattOperation(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        @Deprecated("Deprecated on Android 13; kept for Android 12 and earlier")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            runOnUiThread {
                if (callbackGatt !== gatt) return@runOnUiThread
                handleCharacteristicValue(characteristic, characteristic.value ?: byteArrayOf(), status, "READ")
                val active = activeGattOperation
                if (active?.label?.startsWith("READ ") == true && active.characteristicUuid == characteristic.uuid) {
                    completeGattOperation(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (callbackGatt === gatt) handleCharacteristicValue(characteristic, value, BluetoothGatt.GATT_SUCCESS, "NOTIFY")
        }

        @Deprecated("Deprecated on Android 13; kept for Android 12 and earlier")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (callbackGatt === gatt) {
                handleCharacteristicValue(characteristic, characteristic.value ?: byteArrayOf(), BluetoothGatt.GATT_SUCCESS, "NOTIFY")
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            runOnUiThread {
                if (callbackGatt !== gatt) return@runOnUiThread
                appendLog("DESCRIPTOR WRITE ${descriptor.uuid}: ${if (status == BluetoothGatt.GATT_SUCCESS) "success" else "failed ($status)"}")
                if (activeGattOperation?.descriptor === descriptor) completeGattOperation(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            runOnUiThread {
                if (callbackGatt !== gatt) return@runOnUiThread
                appendLog("CHARACTERISTIC WRITE ${characteristic.uuid}: ${if (status == BluetoothGatt.GATT_SUCCESS) "success" else "failed ($status)"}")
                val active = activeGattOperation
                if (active?.label?.startsWith("WRITE ") == true && active.characteristicUuid == characteristic.uuid) {
                    completeGattOperation(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        override fun onReadRemoteRssi(callbackGatt: BluetoothGatt, value: Int, status: Int) {
            runOnUiThread {
                if (callbackGatt !== gatt) return@runOnUiThread
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    rssi = value
                    val address = safeAddress(callbackGatt.device)
                    val index = deviceRows.indexOfFirst { it.address == address }
                    if (index >= 0) deviceRows[index] = deviceRows[index].copy(rssi = value)
                } else appendLog("RSSI read failed: status $status")
                if (activeGattOperation?.label == "Read remote RSSI") {
                    completeGattOperation(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_UUID) handleClassicUuidBroadcast(intent)
            if (intent?.action == BATTERY_ACTION) {
                val level = intent.getIntExtra(BATTERY_EXTRA, -1)
                if (level in 0..100) serviceBatteryPercent = level
            }
            refreshClassicState()
            refreshOutputRoute()
        }
    }

    private val smartActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(VerlynSmartButtonService.EXTRA_ACTION)) {
                "apply_preset" -> intent.getStringExtra(VerlynSmartButtonService.EXTRA_VALUE)?.let { applyPreset(it) }
                "limiter_changed" -> updateLimiterEnabled(intent.getBooleanExtra(VerlynSmartButtonService.EXTRA_ENABLED, limiterEnabled))
                "bass_boost_changed" -> {
                    bassBoostEnabled = intent.getBooleanExtra(VerlynSmartButtonService.EXTRA_ENABLED, bassBoostEnabled)
                    preferences.edit().putBoolean("bass_boost_enabled", bassBoostEnabled).apply()
                    audioEngine.setBassBoost(bassBoostEnabled)
                }
            }
        }
    }

    private val runtimeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VerlynConnectionService.ACTION_STATE -> {
                    val battery = intent.getIntExtra(VerlynConnectionService.EXTRA_BATTERY, -1)
                    serviceBatteryPercent = battery.takeIf { it in 0..100 }
                    serviceBatterySource = intent.getStringExtra(VerlynConnectionService.EXTRA_BATTERY_SOURCE) ?: serviceBatterySource
                    firmwareVersion = intent.getStringExtra(VerlynConnectionService.EXTRA_FIRMWARE)?.takeIf { it.isNotBlank() } ?: firmwareVersion
                    modelId = intent.getStringExtra(VerlynConnectionService.EXTRA_MODEL_ID)?.takeIf { it.isNotBlank() } ?: modelId
                    audioSessionBindings = intent.getStringArrayListExtra(VerlynConnectionService.EXTRA_AUDIO_SESSIONS)?.toList() ?: audioSessionBindings
                    rfcommState = intent.getStringExtra(VerlynConnectionService.EXTRA_LINK) ?: rfcommState
                    serviceNoiseMode = intent.getStringExtra(VerlynConnectionService.EXTRA_NOISE_MODE) ?: serviceNoiseMode
                    serviceNoiseReady = intent.getBooleanExtra(VerlynConnectionService.EXTRA_NOISE_READY, serviceNoiseReady)
                    serviceNoiseModes = intent.getStringArrayListExtra(VerlynConnectionService.EXTRA_NOISE_MODES)?.toList() ?: serviceNoiseModes
                    serviceNoiseStatus = intent.getStringExtra(VerlynConnectionService.EXTRA_NOISE_STATUS) ?: serviceNoiseStatus
                }
                VerlynConnectionService.ACTION_PACKET -> {
                    val uuid = intent.getStringExtra(VerlynConnectionService.EXTRA_PACKET_UUID) ?: "RFCOMM"
                    val length = intent.getIntExtra(VerlynConnectionService.EXTRA_PACKET_LENGTH, 0)
                    val hex = intent.getStringExtra(VerlynConnectionService.EXTRA_PACKET_HEX) ?: ""
                    val line = "UUID=" + uuid + " bytes=" + length + " HEX[" + hex + "]"
                    rfcommCaptureLog.add(0, line)
                    while (rfcommCaptureLog.size > 500) rfcommCaptureLog.removeAt(rfcommCaptureLog.lastIndex)
                    appendLog("RFCOMM RX " + line)
                }
                VerlynConnectionService.ACTION_RACE_LOG -> {
                    val line = intent.getStringExtra(VerlynConnectionService.EXTRA_RACE_LOG) ?: return
                    raceCaptureLog.add(line)
                    while (raceCaptureLog.size > 400) raceCaptureLog.removeAt(0)
                    appendLog(line)
                }
                VerlynSmartButtonService.ACTION_SMART_LOG -> {
                    smartKeyStatus = intent.getStringExtra(VerlynSmartButtonService.EXTRA_SMART_LOG) ?: smartKeyStatus
                    appendLog(smartKeyStatus)
                }
                VerlynAudioRuntime.ACTION_STATUS -> audioEffectStatus = intent.getStringExtra(VerlynAudioRuntime.EXTRA_STATUS) ?: audioEffectStatus
            }
        }
    }

    private val audioEffectSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (sessionId <= 0) return
            if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                if (sessionCompatibility) {
                    compatibilitySessions.add(sessionId)
                    audioEngine.attachSession(
                        sessionId,
                        intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Unknown media app",
                        eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled,
                        if (balanceEnabled) balance else 0f
                    )
                    appendLog("Audio effect session opened: $sessionId")
                }
            } else if (action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
                compatibilitySessions.remove(sessionId)
                audioEngine.detachSession(sessionId)
                appendLog("Audio effect session closed: $sessionId")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        scanner = adapter?.bluetoothLeScanner
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        loadAudioPreferences()
        refreshQuickSetupStatus()
        audioEngine = VerlynAudioRuntime.get(this)
        audioEngine.setEngineMode(engineMode)
        audioEngine.setBassBoost(bassBoostEnabled)
        audioEngine.setSpatial(spatialEnabled)
        voiceAnnouncer = VoiceAnnouncer(this) { status ->
            runOnUiThread {
                ttsStatus = status
                appendLog("TTS: " + status)
            }
        }
        eqTestPlayer = EqTestAudioPlayer(audioEngine) { status ->
            runOnUiThread {
                testAudioStatus = status
                appendLog("EQ test: " + status)
            }
        }
        audioEngine.attachGlobal(eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled, if (balanceEnabled) balance else 0f)
        handleAudioEffectPanelIntent(intent)
        refreshVolume()
        registerBluetoothReceiver()
        registerSmartActionReceiver()
        registerRuntimeStateReceiver()
        loadSmartPreferences()
        observeCustomPresets()
        requestProfileProxies()

        setContent {
            VerlynPremiumApp(
                state = VerlynProUiState(
                    selectedTab = selectedTab,
                    audioConnected = a2dpConnected || headsetConnected || outputRoute.contains("JBL Tune 770NC", true),
                    a2dpConnected = a2dpConnected,
                    headsetConnected = headsetConnected,
                    bleState = bleState,
                    rfcommState = rfcommState,
                    batteryPercent = headsetBatteryPercent ?: serviceBatteryPercent ?: batteryLevel,
                    batteryDetail = if (headsetBatteryPercent != null) headsetBatteryDetail else if (serviceBatteryPercent != null) "Android Bluetooth battery metadata · " + serviceBatteryPercent + "%" else if (batteryLevel != null) "Standard BLE Battery Service · " + batteryLevel + "%" else "Unavailable",
                    route = outputRoute,
                    appStatus = appStatus,
                    scanning = scanning,
                    classicDeviceFound = classicJblDevice != null,
                    classicSdpStatus = classicSdpStatus,
                    classicSdpUuids = classicSdpUuids.map { it.toString() },
                    gattSnapshotTitle = if (gattSnapshotCached || bleState == "DISCONNECTED") "Last discovered GATT snapshot (cached)" else "Live GATT snapshot",
                    gattSnapshotLines = gattServices.flatMap { service ->
                        listOf("SERVICE " + serviceDisplayName(service.uuid) + " · " + service.uuid) +
                            service.characteristics.map { row -> "  CHAR " + row.uuid + " · " + propertyNames(row.properties).joinToString(", ") + " · " + row.valueHex }
                    },
                    airohaListening = airohaListening,
                    airohaCanListen = bleState == "CONNECTED" && findAirohaRxRow()?.let {
                        it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 && it.characteristic.getDescriptor(CccdUuid) != null
                    } == true,
                    airohaCaptureLog = airohaCaptureLog.toList(),
                    rfcommCaptureLog = rfcommCaptureLog.toList(),
                    packetLog = packetLog.toList(),
                    gains = eqGains.toList(),
                    eqEnabled = eqEnabled,
                    preamp = preamp,
                    limiterEnabled = limiterEnabled,
                    effectStatus = audioEffectStatus,
                    selectedPreset = selectedPreset,
                    customPresets = customPresets.toList(),
                    balanceEnabled = balanceEnabled,
                    balance = balance,
                    sessionCompatibility = sessionCompatibility,
                    triggerActions = smartTriggerActions.toMap(),
                    accessibilityEnabled = accessibilityEnabled,
                    noiseMode = serviceNoiseMode,
                    noiseControlReady = serviceNoiseReady,
                    noiseSupportedModes = serviceNoiseModes,
                    noiseStatus = serviceNoiseStatus,
                    bassBoostEnabled = bassBoostEnabled,
                    spatialEnabled = spatialEnabled,
                    clarity = clarityValue,
                    firmwareVersion = firmwareVersion,
                    modelId = modelId,
                    batterySource = if (headsetBatteryPercent != null) "Fast Pair Message Stream" else serviceBatterySource,
                    audioSessionBindings = audioSessionBindings,
                    raceLog = raceCaptureLog.toList(),
                    ttsStatus = ttsStatus,
                    testAudioStatus = testAudioStatus,
                    smartKeyStatus = smartKeyStatus,
                    engineMode = engineMode,
                    headsetGesturesEnabled = headsetGesturesEnabled,
                    headsetTriggerActions = headsetTriggerActions.toMap(),
                    manualRfcommTargetUuid = manualRfcommTargetUuid,
                    manualRfcommHex = manualRfcommHex,
                    companionConfigured = companionConfigured,
                    batteryUnrestricted = batteryUnrestricted,
                    assistantSelected = assistantSelected
                ),
                actions = VerlynProUiActions(
                    onTabChange = { selectedTab = it },
                    onConnectHeadset = { startConnectionService(VerlynConnectionService.ACTION_FORCE_CONNECT) },
                    onDisconnectHeadset = { startConnectionService(VerlynConnectionService.ACTION_STOP_LINK) },
                    onScan = { checkPermissionsAndScan() },
                    onBleConnect = { connectBleHeadset() },
                    onBleDisconnect = { disconnectGatt() },
                    onRfcommConnect = { connectRfcommByUuid(it) },
                    onRfcommDisconnect = { startConnectionService(VerlynConnectionService.ACTION_MANUAL_RFCOMM_DISCONNECT) },
                    onScanClassicSdp = { scanClassicSdpUuids() },
                    onAirohaListener = { toggleAirohaRxListener() },
                    onCopyDump = { copyGattDump() },
                    onGainChange = { index, value ->
                        if (index in 0..9) {
                            eqGains[index] = value.coerceIn(-12f, 12f)
                            selectedPreset = "Custom"
                            mainHandler.removeCallbacks(audioApplyTask)
                            mainHandler.postDelayed(audioApplyTask, 32L)
                        }
                    },
                    onEqEnabledChange = { eqEnabled = it; saveAudioPreferences(); applyAudioSettings() },
                    onPreset = { applyPreset(it) },
                    onPreampChange = { preamp = it.coerceIn(-12f, 12f); selectedPreset = "Custom"; mainHandler.removeCallbacks(audioApplyTask); mainHandler.postDelayed(audioApplyTask, 32L) },
                    onSavePreset = { saveCustomPreset(it) },
                    onRenamePreset = { oldName, newName -> renameCustomPreset(oldName, newName) },
                    onDeletePreset = { deleteCustomPreset(it) },
                    onBalanceEnabledChange = {
                        balanceEnabled = it
                        preferences.edit().putBoolean("balance_enabled", it).apply()
                        applyAudioSettings()
                    },
                    onBalanceChange = {
                        balance = it.coerceIn(-1f, 1f)
                        balanceEnabled = true
                        preferences.edit().putFloat("balance", balance).putBoolean("balance_enabled", true).apply()
                        applyAudioSettings()
                    },
                    onLimiterChange = { updateLimiterEnabled(it) },
                    onSessionCompatibilityChange = { updateSessionCompatibility(it) },
                    onTriggerActionChange = { slot, action ->
                        smartTriggerActions[slot] = action
                        preferences.edit().putString(slot, action).apply()
                    },
                    onOpenAccessibilitySettings = { openVoiceInputSettings() },
                    onTestVoice = {
                        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                        speakNow("It is " + time)
                        Toast.makeText(this@MainActivity, "TTS: " + ttsStatus, Toast.LENGTH_SHORT).show()
                    },
                    onNoiseModeChange = { VerlynConnectionService.requestNoiseMode(this@MainActivity, it) },
                    onBassBoostChange = {
                        bassBoostEnabled = it
                        preferences.edit().putBoolean("bass_boost_enabled", it).apply()
                        audioEngine.setBassBoost(it)
                        sendBroadcast(Intent(VerlynConnectionService.ACTION_AUX_CHANGED).setPackage(packageName))
                    },
                    onSpatialChange = {
                        spatialEnabled = it
                        preferences.edit().putBoolean("spatial_enabled", it).apply()
                        audioEngine.setSpatial(it)
                        sendBroadcast(Intent(VerlynConnectionService.ACTION_AUX_CHANGED).setPackage(packageName))
                    },
                    onClarityChange = { value ->
                        clarityValue = value.coerceIn(-6f, 6f)
                        eqGains[8] = clarityValue
                        eqGains[9] = clarityValue
                        selectedPreset = "Custom"
                        mainHandler.removeCallbacks(audioApplyTask)
                        mainHandler.postDelayed(audioApplyTask, 32L)
                    },
                    onTestEq = {
                        if (testAudioStatus.contains("playing", true)) eqTestPlayer.stop()
                        else {
                            saveAudioPreferences()
                            eqTestPlayer.play(eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled, if (balanceEnabled) balance else 0f)
                        }
                    },
                    onPowerOffHeadset = { VerlynConnectionService.requestPowerOff(this@MainActivity) },
                    onHeadsetGesturesEnabledChange = { _ ->
                        headsetGesturesEnabled = false
                        preferences.edit().putBoolean("headset_earcup_gestures_enabled", false).apply()
                    },
                    onHeadsetTriggerActionChange = { key, action ->
                        headsetTriggerActions[key] = action
                        preferences.edit().putString(key, action).apply()
                    },
                    onAssociateHeadset = { associateJblCompanion() },
                    onRequestBatteryUnrestricted = { requestBatteryUnrestricted() },
                    onOpenVoiceInputSettings = { openVoiceInputSettings() },
                    onEngineModeChange = { mode ->
                        engineMode = if (mode == "native") "native" else "dynamics"
                        preferences.edit().putString("audio_engine_mode", engineMode).apply()
                        audioEngine.setEngineMode(engineMode)
                        applyAudioSettings()
                        appendLog("EQ engine mode selected: " + engineMode)
                    },
                    onManualRfcommTargetChange = { manualRfcommTargetUuid = it },
                    onManualRfcommHexChange = { manualRfcommHex = it },
                    onSendRfcommHex = {
                        val bytes = parseHex(manualRfcommHex)
                        when {
                            bytes == null || bytes.isEmpty() -> Toast.makeText(this@MainActivity, "Enter valid captured hex bytes", Toast.LENGTH_SHORT).show()
                            bytes.size > 512 -> Toast.makeText(this@MainActivity, "Maximum RFCOMM payload is 512 bytes", Toast.LENGTH_SHORT).show()
                            else -> {
                                val sendIntent = Intent(this@MainActivity, VerlynConnectionService::class.java)
                                    .setAction(VerlynConnectionService.ACTION_MANUAL_RFCOMM_SEND)
                                    .putExtra(VerlynConnectionService.EXTRA_MANUAL_RFCOMM_UUID, manualRfcommTargetUuid)
                                    .putExtra(VerlynConnectionService.EXTRA_MANUAL_RFCOMM_BYTES, bytes)
                                try {
                                    ContextCompat.startForegroundService(this@MainActivity, sendIntent)
                                    appendLog("Manual RFCOMM hex sender forwarded to the connection service · " + bytes.size + " byte(s)")
                                } catch (error: Throwable) {
                                    Toast.makeText(this@MainActivity, "RFCOMM send failed: " + (error.message ?: "service unavailable"), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                )
            )
        }
        checkPermissionsAndScan()
        if (hasBluetoothPermissions()) startConnectionService()
        mainHandler.post(routePoll)
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BATTERY_ACTION)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothStateReceiver, filter)
    }

    private fun registerRuntimeStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(VerlynConnectionService.ACTION_STATE)
            addAction(VerlynConnectionService.ACTION_PACKET)
            addAction(VerlynConnectionService.ACTION_RACE_LOG)
            addAction(VerlynSmartButtonService.ACTION_SMART_LOG)
            addAction(VerlynAudioRuntime.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(runtimeStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(runtimeStateReceiver, filter)
    }

    private fun startConnectionService(action: String? = null) {
        if (!hasBluetoothPermissions()) return
        val serviceIntent = Intent(this, VerlynConnectionService::class.java).apply { if (action != null) setAction(action) }
        try {
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, serviceIntent) else startService(serviceIntent)
        } catch (error: Throwable) {
            appStatus = "Bluetooth service unavailable: " + (error.message ?: "start failed")
        }
    }

    private fun registerSmartActionReceiver() {
        val filter = IntentFilter(VerlynSmartButtonService.ACTION_SMART_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smartActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smartActionReceiver, filter)
    }

    private fun registerAudioEffectReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(audioEffectSessionReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(audioEffectSessionReceiver, filter)
    }

    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionsAndScan() {
        if (adapter == null) { appStatus = "This phone does not expose a Bluetooth adapter."; return }
        if (!hasBluetoothPermissions()) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionLauncher.launch(permissions)
        } else {
            loadPairedDevices(); startScan(); requestProfileProxies()
        }
    }

    private fun isJblName(name: String): Boolean = name.contains("JBL Tune 770NC", true) || name.contains("JBL Tune770NC", true)

    private fun upsertDevice(device: BluetoothDevice, name: String, address: String, signal: Int?, paired: Boolean) {
        if (address == "Unavailable") return
        val index = deviceRows.indexOfFirst { it.address.equals(address, true) }
        val row = DeviceRow(device, name, address, signal, paired || (index >= 0 && deviceRows[index].paired))
        if (index >= 0) deviceRows[index] = row else deviceRows.add(row)
    }

    private fun loadPairedDevices() {
        if (!hasBluetoothPermissions()) return
        try {
            adapter?.bondedDevices?.forEach { device ->
                val name = device.name ?: "Paired Bluetooth device"
                upsertDevice(device, name, device.address, null, true)
                if (isJblName(name) && name.contains("-LE", true) && targetDevice == null) targetDevice = device
                if (isJblName(name) && !name.contains("-LE", true)) {
                    if (classicJblDevice == null || safeAddress(device).equals("88:92:CC:25:46:0E", true)) classicJblDevice = device
                    readNativeHeadsetBattery(device)
                    val cachedUuids = try { device.uuids?.map { it.uuid }.orEmpty() } catch (_: Throwable) { emptyList() }
                    if (classicSdpUuids.isEmpty() && cachedUuids.isNotEmpty()) {
                        classicSdpUuids.addAll(cachedUuids.distinct().sortedBy { it.toString() })
                        classicSdpStatus = "Loaded ${classicSdpUuids.size} cached Classic SDP UUID(s)"
                    }
                }
            }
            refreshClassicState()
        } catch (_: SecurityException) { appStatus = "Bluetooth permission is needed to read paired devices." }
    }

    private fun startScan() {
        if (!hasBluetoothPermissions() || scanActive) return
        if (adapter?.isEnabled != true) { appStatus = "Turn Bluetooth on, then tap Scan."; scanning = false; return }
        try {
            scanner = adapter?.bluetoothLeScanner
            val activeScanner = scanner
            if (activeScanner == null) { appStatus = "BLE scanner is unavailable on this phone."; return }
            deviceRows.removeAll { !it.paired }
            scanActive = true
            scanning = true
            appStatus = "Scanning nearby Bluetooth LE devices for 12 seconds…"
            activeScanner.startScan(scanCallback)
            mainHandler.removeCallbacks(scanTimeout)
            mainHandler.postDelayed(scanTimeout, 12_000L)
        } catch (_: SecurityException) {
            scanActive = false; scanning = false; appStatus = "Bluetooth scan permission was denied."
        } catch (error: Throwable) {
            scanActive = false; scanning = false; appStatus = "Unable to start scan: ${error.message ?: "unknown error"}"
        }
    }

    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        if (scanActive) try { scanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        scanActive = false
        scanning = false
    }

    private fun requestProfileProxies() {
        if (!hasBluetoothPermissions()) return
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.getProfileProxy(this, serviceListener, BluetoothProfile.A2DP)
            manager.adapter?.getProfileProxy(this, serviceListener, BluetoothProfile.HEADSET)
        } catch (_: Throwable) {}
    }

    private fun allJblDevices(): List<BluetoothDevice> {
        val result = linkedMapOf<String, BluetoothDevice>()
        deviceRows.filter { it.isJbl }.forEach { result[it.address] = it.device }
        targetDevice?.let { result[safeAddress(it)] = it }
        return result.values.toList()
    }

    private fun refreshClassicState() {
        if (!hasBluetoothPermissions()) return
        var a2dp = false
        var headset = false
        try {
            allJblDevices().forEach { device ->
                if (a2dpProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) a2dp = true
                if (headsetProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) headset = true
            }
        } catch (_: Throwable) {}
        a2dpConnected = a2dp
        headsetConnected = headset
        refreshOutputRoute()
    }

    private fun refreshOutputRoute() {
        val manager = audioManager ?: return
        try {
            val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val relevant = outputs.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            outputRoute = if (relevant.isEmpty()) {
                val other = outputs.firstOrNull()
                if (other == null) "No output route reported" else "Output: ${other.productName} (${audioTypeName(other.type)})"
            } else relevant.joinToString(" · ") { "${it.productName} (${audioTypeName(it.type)})" }
        } catch (_: Throwable) { outputRoute = "Output route unavailable on this Android build" }
        val jblActive = outputRoute.contains("JBL Tune 770NC", true)
        if (lastAppliedJblRoute != jblActive) {
            lastAppliedJblRoute = jblActive
            if (jblActive) restoreJblEqProfile()
            if (::audioEngine.isInitialized) {
                audioEngine.setRouteActive(jblActive)
                applyAudioSettings()
            }
        }
    }

    private fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        else -> "Output type $type"
    }

    @SuppressLint("MissingPermission")
    private fun readNativeHeadsetBattery(device: BluetoothDevice) {
        val value = runCatching { device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int }.getOrNull()
        if (value != null && value in 0..100) {
            serviceBatteryPercent = value
            serviceBatterySource = "Android Bluetooth device metadata"
        }
    }

    private fun safeAddress(device: BluetoothDevice): String = try { device.address } catch (_: Throwable) { "Unavailable" }
    private fun safeDeviceName(device: BluetoothDevice): String = try { device.name ?: "Bluetooth device" } catch (_: Throwable) { "Bluetooth device" }

    private fun connectGatt(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) { checkPermissionsAndScan(); return }
        val current = gatt
        if (current != null) {
            val sameDevice = safeAddress(current.device) == safeAddress(device)
            if (sameDevice && (bleState == "CONNECTED" || bleState == "CONNECTING")) {
                appStatus = if (bleState == "CONNECTED") "This device already has a BLE GATT connection." else "Connection attempt already in progress."
                return
            }
            pendingGattDevice = device
            if (bleState != "DISCONNECTING") disconnectGatt("Closing the previous GATT session before connecting to the selected device", preservePending = true)
            return
        }
        startGattConnection(device)
    }

    private fun startGattConnection(device: BluetoothDevice) {
        if (gatt != null) { pendingGattDevice = device; return }
        try {
            targetDevice = device
            batteryLevel = null
            rssi = null
            mtu = null
            airohaListening = false
            gattSnapshotCached = gattServices.isNotEmpty()
            bleState = "CONNECTING"
            appStatus = "Connecting to ${safeDeviceName(device)} over BLE…"
            appendLog("GATT connection requested: ${safeDeviceName(device)} (${safeAddress(device)})")
            val createdGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            gatt = createdGatt
            if (createdGatt == null) {
                bleState = "DISCONNECTED"
                appStatus = "Android did not create a GATT client."
                appendLog("connectGatt returned null")
                startPendingGattConnection()
            }
            refreshClassicState()
        } catch (_: SecurityException) {
            bleState = "DISCONNECTED"
            appStatus = "Bluetooth connect permission was denied."
            startPendingGattConnection()
        } catch (error: Throwable) {
            bleState = "DISCONNECTED"
            appStatus = "GATT connection failed: ${error.message ?: "unknown error"}"
            appendLog(appStatus)
            startPendingGattConnection()
        }
    }

    private fun disconnectGatt(
        reason: String = "GATT disconnect requested",
        preservePending: Boolean = false
    ) {
        val current = gatt
        if (!preservePending) pendingGattDevice = null
        if (current == null) {
            bleState = "DISCONNECTED"
            batteryLevel = null
            rssi = null
            mtu = null
            airohaListening = false
            gattSnapshotCached = gattServices.isNotEmpty()
            cancelGattOperations(reason)
            appStatus = "BLE GATT disconnected"
            appendLog(reason)
            return
        }
        if (bleState == "DISCONNECTING") return
        bleState = "DISCONNECTING"
        appStatus = "Disconnecting BLE GATT…"
        batteryLevel = null
        rssi = null
        mtu = null
        airohaListening = false
        gattSnapshotCached = gattServices.isNotEmpty()
        cancelGattOperations(reason)
        gattServices.flatMap { it.characteristics }.forEach { it.notificationsEnabled = false }
        appendLog(reason)
        try { current.disconnect() } catch (error: Throwable) {
            appendLog("GATT disconnect request failed: ${error.message ?: "unknown"}")
        }
        mainHandler.postDelayed({
            if (gatt === current) {
                try { current.close() } catch (_: Throwable) {}
                gatt = null
                bleState = "DISCONNECTED"
                gattSnapshotCached = gattServices.isNotEmpty()
                appendLog("Closed GATT client after disconnect fallback")
                startPendingGattConnection()
            }
        }, 1_800L)
    }

    private fun startPendingGattConnection() {
        val next = pendingGattDevice ?: return
        pendingGattDevice = null
        mainHandler.post {
            if (gatt == null) startGattConnection(next) else pendingGattDevice = next
        }
    }

    private fun enqueueGattOperation(operation: GattOperation) {
        if (gatt == null || bleState != "CONNECTED") {
            appendLog("${operation.label} skipped: no active GATT connection")
            return
        }
        gattOperationQueue.addLast(operation)
        drainGattOperationQueue()
    }

    private fun drainGattOperationQueue() {
        if (activeGattOperation != null) return
        val activeGatt = gatt ?: return
        if (bleState != "CONNECTED") return
        val operation = gattOperationQueue.pollFirst() ?: return
        activeGattOperation = operation
        val accepted = try {
            operation.start(activeGatt)
        } catch (error: Throwable) {
            appendLog("${operation.label} threw ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
            false
        }
        if (!accepted) {
            appendLog("${operation.label} was not accepted by Android Bluetooth; stopping the remaining queue")
            completeGattOperation(false)
            return
        }
        mainHandler.removeCallbacks(gattOperationTimeout)
        mainHandler.postDelayed(gattOperationTimeout, operation.timeoutMs)
    }

    private fun completeGattOperation(success: Boolean) {
        mainHandler.removeCallbacks(gattOperationTimeout)
        val finished = activeGattOperation
        activeGattOperation = null
        try { finished?.onComplete?.invoke(success) } catch (error: Throwable) {
            appendLog("${finished?.label ?: "GATT operation"} completion handler failed: ${error.message ?: "unknown"}")
        }
        if (!success) {
            val dropped = gattOperationQueue.size
            gattOperationQueue.clear()
            appendLog("${finished?.label ?: "GATT operation"} failed; GATT queue halted${if (dropped > 0) " and $dropped pending operation(s) discarded" else ""}")
            return
        }
        if (gatt != null && bleState == "CONNECTED") mainHandler.post { drainGattOperationQueue() }
    }

    private fun cancelGattOperations(reason: String) {
        mainHandler.removeCallbacks(gattOperationTimeout)
        val pending = (if (activeGattOperation != null) 1 else 0) + gattOperationQueue.size
        activeGattOperation = null
        gattOperationQueue.clear()
        if (pending > 0) appendLog("Cancelled $pending queued GATT operation(s): $reason")
    }

    private fun enqueueCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        label: String = "READ"
    ) {
        enqueueGattOperation(
            GattOperation(
                label = "$label ${characteristic.uuid}",
                timeoutMs = 4_000L,
                start = { active -> active.readCharacteristic(characteristic) },
                characteristicUuid = characteristic.uuid
            )
        )
    }

    private fun handleCharacteristicValue(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, status: Int, operation: String) {
        runOnUiThread {
            val serviceUuid = characteristic.service?.uuid
            val row = gattServices.flatMap { it.characteristics }
                .firstOrNull { it.uuid == characteristic.uuid && it.serviceUuid == serviceUuid }
            val hex = bytes.toHexString()
            val utf8 = bytes.toString(Charsets.UTF_8).replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "�")
            if (row != null && status == BluetoothGatt.GATT_SUCCESS) {
                row.valueHex = hex.ifBlank { "(empty)" }
                row.valueText = utf8.ifBlank { "(empty)" }
            }
            if (characteristic.uuid == BatteryLevelUuid && status == BluetoothGatt.GATT_SUCCESS && bytes.isNotEmpty()) {
                val value = bytes[0].toInt() and 0xFF
                batteryLevel = value.takeIf { it in 0..100 }
            }
            if (serviceUuid == AirohaRaceServiceUuid && characteristic.uuid == AirohaRxCharacteristicUuid &&
                operation == "NOTIFY" && status == BluetoothGatt.GATT_SUCCESS
            ) {
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val capture = "[$time] RX bytes=${bytes.size} HEX[$hex]"
                airohaCaptureLog.add(0, capture)
                while (airohaCaptureLog.size > 500) airohaCaptureLog.removeAt(airohaCaptureLog.lastIndex)
                appendLog("AIROHA RX PACKET bytes=${bytes.size} HEX[$hex]")
            }
            appendLog("$operation ${characteristic.uuid} status=$status HEX[$hex] UTF8[$utf8]")
        }
    }

    private fun readCharacteristic(row: GattCharacteristicRow) {
        if (row.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            appendLog("READ skipped: ${row.uuid} is not readable")
            return
        }
        enqueueCharacteristicRead(row.characteristic)
    }

    private fun findAirohaRxRow(): GattCharacteristicRow? =
        gattServices.firstOrNull { it.uuid == AirohaRaceServiceUuid }
            ?.characteristics?.firstOrNull { it.uuid == AirohaRxCharacteristicUuid }

    private fun toggleAirohaRxListener() {
        val activeGatt = gatt
        val row = findAirohaRxRow()
        if (activeGatt == null || bleState != "CONNECTED" || row == null) {
            appendLog("Airoha RX listener unavailable: connect and discover the Airoha RACE service first")
            return
        }
        val characteristic = row.characteristic
        val descriptor = characteristic.getDescriptor(CccdUuid)
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 || descriptor == null) {
            appendLog("Airoha RX listener unavailable: RX notification property or CCCD is missing")
            return
        }
        val enable = !row.notificationsEnabled
        try {
            if (!activeGatt.setCharacteristicNotification(characteristic, enable)) {
                appendLog("Android rejected local Airoha RX notification registration")
                return
            }
            descriptor.value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            enqueueGattOperation(
                GattOperation(
                    label = "${if (enable) "ENABLE" else "DISABLE"} Airoha RX notifications",
                    timeoutMs = 6_000L,
                    start = { it.writeDescriptor(descriptor) },
                    descriptor = descriptor,
                    onComplete = { success ->
                        row.notificationsEnabled = success && enable
                        airohaListening = success && enable
                        if (!success) {
                            try { activeGatt.setCharacteristicNotification(characteristic, false) } catch (_: Throwable) {}
                        }
                        appendLog(
                            if (success && enable) "Airoha RX passive listener enabled"
                            else if (success) "Airoha RX passive listener stopped"
                            else "Airoha RX CCCD subscription failed"
                        )
                    }
                )
            )
        } catch (error: Throwable) {
            appendLog("Airoha RX listener setup failed: ${error.message ?: "unknown error"}")
        }
    }


    private fun toggleNotifications(row: GattCharacteristicRow) {
        val activeGatt = gatt ?: return
        val characteristic = row.characteristic
        val supportsNotify = row.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = row.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) return
        val descriptor = characteristic.getDescriptor(CccdUuid)
        if (descriptor == null) {
            appendLog("CCCD descriptor missing on ${row.uuid}")
            return
        }
        val enable = !row.notificationsEnabled
        try {
            if (!activeGatt.setCharacteristicNotification(characteristic, enable)) {
                appendLog("Local notification registration failed for ${row.uuid}")
                return
            }
            descriptor.value = when {
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                supportsIndicate && !supportsNotify -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            enqueueGattOperation(
                GattOperation(
                    label = "${if (enable) "ENABLE" else "DISABLE"} CCCD ${row.uuid}",
                    timeoutMs = 4_000L,
                    start = { it.writeDescriptor(descriptor) },
                    descriptor = descriptor,
                    onComplete = { success ->
                        row.notificationsEnabled = success && enable
                        if (!success) activeGatt.setCharacteristicNotification(characteristic, !enable)
                        appendLog("${if (success && enable) "Notifications enabled" else if (success) "Notifications disabled" else "CCCD operation failed"} for ${row.uuid}")
                    }
                )
            )
        } catch (error: Throwable) {
            appendLog("Notification setup failed ${row.uuid}: ${error.message ?: "unknown"}")
        }
    }

    private fun writeSelectedCharacteristic() {
        val row = writeTarget ?: return
        val activeGatt = gatt ?: return
        if (row.serviceUuid == AirohaRaceServiceUuid && row.uuid == AirohaTxCharacteristicUuid) {
            appendLog("WRITE BLOCKED: Airoha RACE TX is deliberately read-only in VERLYN 0.1.4")
            writeTarget = null
            return
        }
        val bytes = parseHex(writeHexInput)
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(this, "Enter valid hex bytes, for example: 01 FF 0A", Toast.LENGTH_SHORT).show(); return
        }
        val props = row.characteristic.properties
        val canWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val canWriteNoResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        if (!canWrite && !canWriteNoResponse) {
            appendLog("WRITE blocked: characteristic does not advertise write property"); writeTarget = null; return
        }
        try {
            row.characteristic.writeType = if (!canWrite && canWriteNoResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            row.characteristic.value = bytes
            enqueueGattOperation(
                GattOperation(
                    label = "WRITE ${row.uuid}",
                    timeoutMs = 4_000L,
                    start = { it.writeCharacteristic(row.characteristic) },
                    characteristicUuid = row.uuid,
                    onComplete = { success ->
                        appendLog(if (success) "WRITE completed ${row.uuid} HEX[${bytes.toHexString()}]" else "WRITE did not complete ${row.uuid}")
                    }
                )
            )
        } catch (error: Throwable) {
            appendLog("WRITE error ${row.uuid}: ${error.message ?: "unknown"}")
        }
        writeTarget = null
    }

    private fun pairedClassicJbl(): BluetoothDevice? {
        if (classicJblDevice != null) return classicJblDevice
        if (!hasBluetoothPermissions()) return null
        return try {
            adapter?.bondedDevices?.firstOrNull { isJblName(it.name ?: "") && !(it.name ?: "").contains("-LE", true) }
                ?.also { classicJblDevice = it }
        } catch (_: Throwable) { null }
    }

    private fun connectFastPairRfcomm() {
        connectRfcommByUuid(ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID.toString())
    }

    private fun connectRfcommByUuid(value: String) {
        if (!hasBluetoothPermissions()) {
            appStatus = "Bluetooth Connect permission is required for RFCOMM."
            checkPermissionsAndScan()
            return
        }
        val uuid = runCatching { UUID.fromString(value) }.getOrNull()
        if (uuid == null) {
            rfcommState = "Invalid RFCOMM UUID"
            return
        }
        val label = when (uuid) {
            ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID -> "Fast Pair Message Stream"
            ClassicRfcommEngine.JBL_VENDOR_UUID -> "JBL Vendor SPP"
            ClassicRfcommEngine.AIROHA_RFCOMM_CANDIDATE_UUID -> "Airoha RACE UUID"
            else -> "Manual RFCOMM endpoint"
        }
        rfcommState = "Requesting exclusive RFCOMM connection · " + label
        appendLog("RFCOMM user connect request · " + label + " · " + uuid)
        val connectIntent = Intent(this, VerlynConnectionService::class.java)
            .setAction(VerlynConnectionService.ACTION_MANUAL_RFCOMM_CONNECT)
            .putExtra(VerlynConnectionService.EXTRA_MANUAL_RFCOMM_UUID, uuid.toString())
        runCatching { ContextCompat.startForegroundService(this, connectIntent) }
            .onFailure {
                rfcommState = "RFCOMM service failed · " + (it.message ?: "unavailable")
                appendLog(rfcommState)
            }
    }

    private fun connectBleHeadset() {
        val device = deviceRows.firstOrNull { it.isJbl && it.name.contains("-LE", true) }?.device ?: targetDevice
        if (device == null) { appStatus = "No paired/discovered JBL Tune 770NC-LE device found. Scan first."; checkPermissionsAndScan(); return }
        connectGatt(device)
    }

    private fun loadSmartPreferences() {
        val slots = listOf(
            "trigger_volume_down_long" to "speak_time",
            "trigger_volume_up_long" to "speak_battery",
            "trigger_volume_down_double" to "cycle_eq",
            "trigger_volume_up_double" to "toggle_limiter"
        )
        slots.forEach { (key, defaultValue) -> smartTriggerActions[key] = preferences.getString(key, defaultValue) ?: defaultValue }
        val commandSlots = listOf(
            "command_vol_up_single" to "speak_battery",
            "command_vol_down_single" to "enter_eq_scroll",
            "command_vol_up_double" to "toggle_bass_boost",
            "command_vol_down_double" to "cycle_sleep_timer",
            "command_center_long_hold" to "speak_full_briefing"
        )
        headsetGesturesEnabled = false
        preferences.edit().putBoolean("headset_earcup_gestures_enabled", false).apply()
        commandSlots.forEach { (key, defaultValue) ->
            headsetTriggerActions[key] = preferences.getString(key, defaultValue) ?: defaultValue
        }
        refreshQuickSetupStatus()
    }

    private fun isSmartAccessibilityEnabled(): Boolean = runCatching {
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.VOICE_INTERACTION_SERVICE).orEmpty()
        selected.contains(packageName, ignoreCase = true) &&
            selected.contains(VerlynAssistantVoiceInteractionService::class.java.simpleName, ignoreCase = true)
    }.getOrDefault(false)

    private fun refreshQuickSetupStatus() {
        companionConfigured = preferences.getBoolean("companion_device_associated", false)
        batteryUnrestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        } else true
        assistantSelected = isSmartAccessibilityEnabled()
        accessibilityEnabled = assistantSelected
    }

    private fun openVoiceInputSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            .onFailure { Toast.makeText(this, "Voice input settings are unavailable", Toast.LENGTH_LONG).show() }
    }

    private fun requestBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { Toast.makeText(this, "Battery optimization settings could not be opened", Toast.LENGTH_LONG).show() }
        }
    }

    private fun associateJblCompanion() {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder()
                .setNamePattern(java.util.regex.Pattern.compile("(?i)JBL\\s*Tune\\s*770NC"))
                .build())
            .setSingleDevice(true)
            .build()
        val manager = getSystemService(COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        runCatching {
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    companionAssociationLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                }
                override fun onFailure(error: CharSequence?) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, error ?: "No JBL Tune 770NC found", Toast.LENGTH_LONG).show()
                    }
                }
            }, mainHandler)
        }.onFailure { Toast.makeText(this, "Companion association failed · " + (it.message ?: "unsupported"), Toast.LENGTH_LONG).show() }
    }

    private fun observeCustomPresets() {
        lifecycleScope.launch {
            EqPresetStore.observe(this@MainActivity).collect { latest ->
                customPresets.clear()
                customPresets.addAll(latest)
                preferences.edit().putString("saved_preset_names", latest.joinToString("\n") { it.name }).apply()
            }
        }
    }

    private fun saveCustomPreset(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        lifecycleScope.launch {
            try {
                EqPresetStore.save(this@MainActivity, EqPreset(clean, eqGains.toList(), preamp))
                selectedPreset = clean
                saveAudioPreferences()
                Toast.makeText(this@MainActivity, "EQ preset saved", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, "Could not save preset: " + (error.message ?: "unknown"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renameCustomPreset(oldName: String, newName: String) {
        val existing = customPresets.firstOrNull { it.name == oldName } ?: return
        val clean = newName.trim()
        if (clean.isBlank()) return
        lifecycleScope.launch {
            try {
                EqPresetStore.save(this@MainActivity, existing.copy(name = clean))
                EqPresetStore.delete(this@MainActivity, oldName)
                if (selectedPreset == oldName) selectedPreset = clean
                saveAudioPreferences()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, "Rename failed: " + (error.message ?: "unknown"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteCustomPreset(name: String) {
        lifecycleScope.launch {
            EqPresetStore.delete(this@MainActivity, name)
            if (selectedPreset == name) applyPreset("Flat")
        }
    }

    private fun updateLimiterEnabled(enabled: Boolean) {
        limiterEnabled = enabled
        preferences.edit().putBoolean("limiter_enabled", enabled).apply()
        applyAudioSettings()
        if (enabled && musicVolume > (maxMusicVolume * 0.75f).toInt()) updateMusicVolume((maxMusicVolume * 0.75f).toInt())
        refreshVolume()
    }

    private fun handleClassicUuidBroadcast(intent: Intent) {
        classicSdpScanInFlight = false
        mainHandler.removeCallbacks(classicSdpScanTimeout)
        val device = try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        } catch (_: Throwable) {
            null
        } ?: return
        val expected = classicJblDevice ?: return
        if (safeAddress(device) != safeAddress(expected)) return
        val parcelUuids = try {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
        } catch (_: Throwable) {
            null
        }
        val discovered = parcelUuids?.mapNotNull { (it as? ParcelUuid)?.uuid }
            ?.distinct()
            ?.sortedBy { it.toString() }
            .orEmpty()
        classicSdpUuids.clear()
        classicSdpUuids.addAll(discovered)
        classicSdpStatus = when {
            discovered.isNotEmpty() -> "Received ${discovered.size} Classic SDP UUID(s)"
            parcelUuids == null -> "Classic SDP discovery timed out (UUID list was null)"
            else -> "Classic SDP discovery completed; no service UUIDs returned"
        }
        appendLog("Classic SDP result for ${safeDeviceName(device)}: ${discovered.size} UUID(s)")
    }

    private fun scanClassicSdpUuids() {
        val now = SystemClock.elapsedRealtime()
        if (classicSdpScanInFlight) {
            appendLog("Classic SDP scan ignored: request already in flight")
            return
        }
        if (now - lastClassicSdpScanAt < 5_000L) {
            classicSdpStatus = "Please wait before scanning again"
            appendLog("Classic SDP scan ignored: 5-second cooldown")
            return
        }
        val device = classicJblDevice
        if (device == null) {
            classicSdpStatus = "Paired Classic JBL Tune 770NC device not found"
            appendLog("Classic SDP scan unavailable: no paired non-LE JBL Tune 770NC entry")
            return
        }
        if (!hasBluetoothPermissions()) {
            classicSdpStatus = "Bluetooth Connect permission required"
            return
        }
        classicSdpScanInFlight = true
        lastClassicSdpScanAt = now
        mainHandler.removeCallbacks(classicSdpScanTimeout)
        mainHandler.postDelayed(classicSdpScanTimeout, 5_000L)
        classicSdpStatus = "Requesting Classic SDP service discovery…"
        appendLog("Starting Classic SDP UUID discovery for " + safeDeviceName(device) + " (" + safeAddress(device) + ")")
        try {
            val cached = device.uuids?.map { it.uuid }.orEmpty()
            if (cached.isNotEmpty()) {
                classicSdpUuids.clear()
                classicSdpUuids.addAll(cached.distinct().sortedBy { it.toString() })
                classicSdpStatus = "Loaded ${classicSdpUuids.size} cached UUID(s); fresh SDP request pending"
            }
            val accepted = device.fetchUuidsWithSdp()
            appendLog(if (accepted) "fetchUuidsWithSdp() accepted; waiting for ACTION_UUID" else "fetchUuidsWithSdp() was not accepted")
            if (!accepted) {
                classicSdpScanInFlight = false
                mainHandler.removeCallbacks(classicSdpScanTimeout)
                if (cached.isEmpty()) classicSdpStatus = "Android rejected the Classic SDP request"
            }
        } catch (error: Throwable) {
            classicSdpScanInFlight = false
            mainHandler.removeCallbacks(classicSdpScanTimeout)
            classicSdpStatus = "Classic SDP scan failed: " + (error.message ?: "unknown error")
            appendLog(classicSdpStatus)
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        packetLog.add(0, "[$time] $message")
        while (packetLog.size > 250) packetLog.removeAt(packetLog.lastIndex)
    }

    private fun copyGattDump() {
        val target = targetDevice
        val displayedBattery = headsetBatteryPercent ?: serviceBatteryPercent ?: batteryLevel
        val displayedSource = when {
            headsetBatteryPercent != null -> headsetBatteryDetail
            serviceBatteryPercent != null -> serviceBatterySource
            batteryLevel != null -> "Standard BLE Battery Service"
            else -> "Unavailable"
        }
        val dump = buildString {
            appendLine("VERLYN 0.4.0 — COMPLETE AUDIO / BLUETOOTH DUMP")
            appendLine("Device: " + (target?.let { safeDeviceName(it) } ?: "Not selected"))
            appendLine("Address: " + (target?.let { safeAddress(it) } ?: "Unavailable"))
            appendLine("BLE state: " + bleState)
            appendLine("Snapshot status: " + if (gattSnapshotCached || bleState == "DISCONNECTED") "Last Discovered Snapshot (Cached)" else "Live")
            appendLine("MTU: " + (mtu ?: "Unavailable"))
            appendLine("Battery: " + (displayedBattery?.let { it.toString() + "%" } ?: "Unavailable"))
            appendLine("Battery source: " + displayedSource)
            appendLine("Firmware: " + if (firmwareVersion.isNotBlank()) "FW " + firmwareVersion else "Unavailable")
            appendLine("Model ID: " + modelId.ifBlank { "Unavailable" })
            appendLine("TTS engine: " + ttsStatus)
            appendLine("EQ engine mode: " + engineMode)
            appendLine("Smart key engine: " + smartKeyStatus)
            appendLine("AudioEffect engine: " + audioEngine.statusForEffects())
            appendLine("Session compatibility: " + sessionCompatibility)
            appendLine("Audio session state:")
            audioEngine.sessionDebugLines().forEach { appendLine("  " + it) }
            appendLine("Service audio bindings:")
            audioSessionBindings.forEach { appendLine("  " + it) }
            appendLine("Test audio: " + testAudioStatus)
            appendLine("Classic RFCOMM status: " + rfcommState)
            appendLine()
            appendLine("CLASSIC RFCOMM CAPTURE")
            if (rfcommCaptureLog.isEmpty()) appendLine("No RFCOMM data captured") else rfcommCaptureLog.asReversed().forEach { appendLine(it) }
            appendLine("RSSI: " + (rssi?.let { it.toString() + " dBm" } ?: "Unavailable"))
            appendLine()
            appendLine(if (gattSnapshotCached || bleState == "DISCONNECTED") "Last Discovered Snapshot (Cached)" else "Current GATT Snapshot")
            appendLine("Snapshot Device: " + snapshotDeviceName)
            appendLine("Snapshot Address: " + snapshotDeviceAddress)
            appendLine("BLE GATT Services: " + gattServices.size)
            gattServices.forEach { service ->
                appendLine("SERVICE " + service.uuid)
                service.characteristics.forEach { item ->
                    appendLine("  CHARACTERISTIC " + item.uuid)
                    appendLine("    PROPERTIES " + propertyNames(item.properties).joinToString(", "))
                    appendLine("    VALUE_HEX " + item.valueHex)
                    appendLine("    VALUE_UTF8 " + item.valueText)
                    item.descriptorUuids.forEach { descriptorUuid -> appendLine("    DESCRIPTOR " + descriptorUuid) }
                }
            }
            appendLine()
            appendLine("CLASSIC BLUETOOTH SDP UUIDS")
            appendLine("Classic device: " + (classicJblDevice?.let { safeDeviceName(it) } ?: "Not found"))
            appendLine("SDP status: " + classicSdpStatus)
            if (classicSdpUuids.isEmpty()) appendLine("No Classic SDP UUIDs captured")
            else classicSdpUuids.forEach { appendLine("CLASSIC SERVICE UUID " + it) }
            appendLine()
            appendLine("AIROHA RACE TX / RX LOG")
            if (raceCaptureLog.isEmpty()) appendLine("No Classic RACE TX/RX bytes captured")
            else raceCaptureLog.forEach { appendLine(it) }
            appendLine()
            appendLine("AIROHA BLE PASSIVE RX LOG")
            appendLine("Listener active: " + airohaListening)
            if (airohaCaptureLog.isEmpty()) appendLine("No Airoha BLE RX notifications captured")
            else airohaCaptureLog.asReversed().forEach { appendLine(it) }
            appendLine()
            appendLine("PACKET / OPERATION LOG")
            packetLog.asReversed().forEach { appendLine(it) }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Verlyn diagnostic dump", dump))
        Toast.makeText(this, "VERLYN 0.4.0 diagnostic dump copied", Toast.LENGTH_SHORT).show()
    }

    private fun updateSessionCompatibility(enabled: Boolean) {
        sessionCompatibility = enabled
        preferences.edit().putBoolean("session_compatibility", enabled).apply()
        if (!enabled) {
            compatibilitySessions.toList().forEach { audioEngine.detachSession(it) }
            compatibilitySessions.clear()
            appendLog("Media-session compatibility mode disabled")
        } else appendLog("Media-session compatibility enabled; waiting for Android audio-effect session broadcasts")
    }

    private fun loadAudioPreferences() {
        preferences.getString("eq_gains", null)?.let { saved ->
            val values = saved.split(",").mapNotNull { it.toFloatOrNull() }
            if (values.size == 10) values.forEachIndexed { index, value -> eqGains[index] = value.coerceIn(-12f, 12f) }
        }
        preamp = preferences.getFloat("preamp", 0f).coerceIn(-12f, 12f)
        eqEnabled = preferences.getBoolean("eq_enabled", true)
        limiterEnabled = preferences.getBoolean("limiter_enabled", true)
        selectedPreset = preferences.getString("preset", "Flat") ?: "Flat"
        sessionCompatibility = preferences.getBoolean("session_compatibility", true)
        val storedEngineMode = preferences.getString("audio_engine_mode", null)
        val nativeDefaultMigrated = preferences.getBoolean("engine_mode_native_default_033", false)
        engineMode = if (!nativeDefaultMigrated && storedEngineMode == "dynamics") "native"
        else storedEngineMode ?: "native"
        preferences.edit()
            .putString("audio_engine_mode", engineMode)
            .putBoolean("engine_mode_native_default_033", true)
            .apply()
        clarityValue = preferences.getFloat("clarity", 0f).coerceIn(-6f, 6f)
        bassBoostEnabled = preferences.getBoolean("bass_boost_enabled", false)
        spatialEnabled = preferences.getBoolean("spatial_enabled", false)
        balanceEnabled = preferences.getBoolean("balance_enabled", false)
        balance = preferences.getFloat("balance", 0f).coerceIn(-1f, 1f)
    }

    private fun saveAudioPreferences() {
        preferences.edit()
            .putString("eq_gains", eqGains.joinToString(","))
            .putFloat("preamp", preamp)
            .putBoolean("eq_enabled", eqEnabled)
            .putBoolean("limiter_enabled", limiterEnabled)
            .putString("preset", selectedPreset)
            .putBoolean("balance_enabled", balanceEnabled)
            .putFloat("balance", balance)
            .putFloat("clarity", clarityValue)
            .putBoolean("bass_boost_enabled", bassBoostEnabled)
            .putBoolean("spatial_enabled", spatialEnabled)
            .putString("audio_engine_mode", engineMode)
            .apply()
        if (lastAppliedJblRoute == true) {
            preferences.edit()
                .putString("jbl_eq_gains", eqGains.joinToString(","))
                .putFloat("jbl_preamp", preamp)
                .putString("jbl_preset", selectedPreset)
                .apply()
        }
    }

    private fun restoreJblEqProfile() {
        val saved = preferences.getString("jbl_eq_gains", null) ?: return
        val values = saved.split(",").mapNotNull { it.toFloatOrNull() }
        if (values.size == 10) values.forEachIndexed { i, value -> eqGains[i] = value.coerceIn(-12f, 12f) }
        preamp = preferences.getFloat("jbl_preamp", preamp).coerceIn(-12f, 12f)
        selectedPreset = preferences.getString("jbl_preset", selectedPreset) ?: selectedPreset
    }

    private fun applyAudioSettings() {
        val gains = eqGains.toFloatArray()
        val currentPreamp = preamp
        val enabled = eqEnabled
        val limited = limiterEnabled
        val channelBalance = if (balanceEnabled) balance else 0f
        lifecycleScope.launch(Dispatchers.Default) {
            if (!audioEngine.hasGlobalMixEffect()) audioEngine.attachGlobal(gains, currentPreamp, enabled, limited, channelBalance)
            else audioEngine.apply(gains, currentPreamp, enabled, limited, channelBalance)
            if (sessionCompatibility) compatibilitySessions.forEach {
                audioEngine.attachSession(it, "Unknown media app", gains, currentPreamp, enabled, limited, channelBalance)
            }
        }
    }

    private fun applyPreset(name: String) {
        val custom = customPresets.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (custom != null) {
            custom.gains.forEachIndexed { index, value -> eqGains[index] = value }
            preamp = custom.preamp
        } else {
            val values = when (name) {
                "Studio" -> floatArrayOf(1f, 1f, 0f, 1f, 2f, 1f, 0f, 1f, 1f, 0f)
                "Bass" -> floatArrayOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
                "Club" -> floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 3f)
                "Extreme Bass" -> floatArrayOf(8f, 7f, 5f, 2f, 0f, -1f, -1f, 0f, 2f, 3f)
                "Vocal" -> floatArrayOf(-2f, -2f, -1f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)
                "Jazz" -> floatArrayOf(3f, 2f, 1f, 2f, -1f, -2f, 0f, 2f, 3f, 2f)
                "Podcast" -> floatArrayOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 2f, 0f, -1f)
                "Gaming" -> floatArrayOf(3f, 2f, 0f, -1f, 0f, 2f, 4f, 4f, 2f, 0f)
                else -> FloatArray(10) { 0f }
            }
            values.forEachIndexed { index, value -> eqGains[index] = value }
            preamp = if (name == "Bass" || name == "Club" || name == "Extreme Bass") -2f else 0f
        }
        selectedPreset = name
        saveAudioPreferences()
        applyAudioSettings()
    }

    private fun refreshVolume() {
        audioManager?.let {
            musicVolume = it.getStreamVolume(AudioManager.STREAM_MUSIC)
            maxMusicVolume = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }
    }

    private fun updateMusicVolume(value: Int) {
        val ceiling = if (limiterEnabled) (maxMusicVolume * 0.75f).toInt().coerceAtLeast(1) else maxMusicVolume
        val next = value.coerceIn(0, ceiling)
        musicVolume = next
        try {
            VerlynVolumeChangeGuard.markProgrammaticChange()
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        } catch (_: SecurityException) {}
    }

    private fun sendMediaKey(keyCode: Int) {
        val manager = audioManager ?: return
        try {
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (error: Throwable) {
            Toast.makeText(this, "Media key unavailable: ${error.message ?: "unsupported"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakNow(message: String) {
        appendLog("TTS request · " + message)
        if (!voiceAnnouncer.speak(message)) appendLog("TTS request was not accepted · " + ttsStatus)
    }

    private fun handleAudioEffectPanelIntent(data: Intent?) {
        if (data?.action != ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val sessionId = data.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (sessionId <= 0) {
            appendLog("Spotify/system EQ panel intent received without a valid EXTRA_AUDIO_SESSION")
            return
        }
        val mediaPackage = data.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty().ifBlank { "External media app" }
        audioEngine.attachSession(
            sessionId, mediaPackage, eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled,
            if (balanceEnabled) balance else 0f
        )
        val label = runCatching {
            if (mediaPackage == "External media app") mediaPackage
            else packageManager.getApplicationLabel(packageManager.getApplicationInfo(mediaPackage, 0)).toString()
        }.getOrDefault(mediaPackage)
        audioSessionBindings = (audioSessionBindings.filterNot { it.contains("Session " + sessionId) } +
            (label + " (Session " + sessionId + ")")).distinct()
        appendLog("System equalizer panel opened · " + label + " · audioSessionId=" + sessionId)
        val serviceIntent = Intent(this, VerlynConnectionService::class.java)
            .setAction(VerlynConnectionService.ACTION_AUDIO_SESSION_EVENT)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mediaPackage)
            .putExtra(VerlynConnectionService.EXTRA_SESSION_OPEN, true)
        runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
            .onFailure { appendLog("Could not hand off panel session to connection service · " + (it.message ?: "unknown")) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAudioEffectPanelIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::audioEngine.isInitialized) applyAudioSettings()
        refreshVolume(); refreshClassicState(); refreshOutputRoute()
        refreshQuickSetupStatus()
        if (adapter != null && hasBluetoothPermissions() && adapter?.isEnabled == true && !scanActive) {
            loadPairedDevices(); startScan(); startConnectionService()
        }
    }

    override fun onPause() {
        stopScan(); 
        super.onPause()
    }

    override fun onDestroy() {
        stopScan(); mainHandler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
        gatt = null
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(audioEffectSessionReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(smartActionReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(runtimeStateReceiver) } catch (_: Throwable) {}
        mainHandler.removeCallbacks(audioApplyTask)
        try { eqTestPlayer.shutdown() } catch (_: Throwable) {}
        try { voiceAnnouncer.shutdown() } catch (_: Throwable) {}
        try { a2dpProxy?.let { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (_: Throwable) {}
        try { headsetProxy?.let { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } } catch (_: Throwable) {}
        // The foreground connection service owns the shared audio engine lifetime.
        super.onDestroy()
    }
}

@Composable
private fun VerlynTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Blue, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White),
        content = content
    )
}

@Composable
private fun VerlynApp(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    bleState: String,
    audioConnected: Boolean,
    a2dpConnected: Boolean,
    headsetConnected: Boolean,
    batteryLevel: Int?,
    rssi: Int?,
    mtu: Int?,
    route: String,
    appStatus: String,
    scanning: Boolean,
    devices: List<DeviceRow>,
    onScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    gains: List<Float>,
    onGainChange: (Int, Float) -> Unit,
    preamp: Float,
    onPreampChange: (Float) -> Unit,
    eqEnabled: Boolean,
    onEqEnabledChange: (Boolean) -> Unit,
    limiterEnabled: Boolean,
    onLimiterChange: (Boolean) -> Unit,
    sessionCompatibility: Boolean,
    onSessionCompatibilityChange: (Boolean) -> Unit,
    effectStatus: String,
    selectedPreset: String,
    onPreset: (String) -> Unit,
    volume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    onMediaKey: (Int) -> Unit,
    services: List<GattServiceRow>,
    snapshotCached: Boolean,
    airohaListening: Boolean,
    canListenAirohaRx: Boolean,
    onToggleAirohaListener: () -> Unit,
    airohaCaptureLog: List<String>,
    classicSdpUuids: List<UUID>,
    classicSdpStatus: String,
    canScanClassicSdp: Boolean,
    onScanClassicSdp: () -> Unit,
    inspectorExpanded: Boolean,
    onInspectorExpanded: (Boolean) -> Unit,
    onReadCharacteristic: (GattCharacteristicRow) -> Unit,
    onToggleNotify: (GattCharacteristicRow) -> Unit,
    onRequestWrite: (GattCharacteristicRow) -> Unit,
    packetLog: List<String>,
    onCopyDump: () -> Unit,
    writeTarget: GattCharacteristicRow?,
    writeHexInput: String,
    onWriteHexChange: (String) -> Unit,
    onConfirmWrite: () -> Unit,
    onDismissWrite: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("VERLYN", fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text("PERSONAL AUDIO COMPANION", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                }
                Icon(Icons.Filled.Bluetooth, contentDescription = "Bluetooth", tint = Blue, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = selectedTab, containerColor = Ink, contentColor = Blue) {
                listOf("Control", "Equalizer", "Inspector").forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { onTabChange(index) }, text = {
                        Text(label, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal)
                    })
                }
            }
            when (selectedTab) {
                0 -> ControlPage(
                    bleState, audioConnected, a2dpConnected, headsetConnected, batteryLevel, rssi, mtu,
                    route, appStatus, scanning, devices, onScan, onConnect, onDisconnect,
                    volume, maxVolume, onVolumeChange, onMediaKey
                )
                1 -> EqualizerPage(
                    gains, onGainChange, preamp, onPreampChange, eqEnabled, onEqEnabledChange,
                    limiterEnabled, onLimiterChange, sessionCompatibility, onSessionCompatibilityChange,
                    effectStatus, selectedPreset, onPreset
                )
                else -> InspectorPage(
                    bleState, services, snapshotCached, airohaListening, canListenAirohaRx,
                    onToggleAirohaListener, airohaCaptureLog, classicSdpUuids, classicSdpStatus,
                    canScanClassicSdp, onScanClassicSdp, inspectorExpanded, onInspectorExpanded,
                    onReadCharacteristic, onToggleNotify, onRequestWrite, packetLog, onCopyDump
                )
            }
        }
    }
    if (writeTarget != null) {
        AlertDialog(
            onDismissRequest = onDismissWrite,
            title = { Text("Safe Hex Write") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Characteristic ${writeTarget.uuid}", color = Muted, fontSize = 12.sp)
                    Text("Only send bytes you understand. The device may interpret this as a proprietary command.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = writeHexInput,
                        onValueChange = onWriteHexChange,
                        label = { Text("Hex bytes") },
                        placeholder = { Text("01 FF 0A") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        singleLine = true
                    )
                }
            },
            confirmButton = { TextButton(onClick = onConfirmWrite) { Text("Confirm & Send", color = Blue) } },
            dismissButton = { TextButton(onClick = onDismissWrite) { Text("Cancel") } },
            containerColor = Panel
        )
    }
}

@Composable
private fun ControlPage(
    bleState: String,
    audioConnected: Boolean,
    a2dpConnected: Boolean,
    headsetConnected: Boolean,
    batteryLevel: Int?,
    rssi: Int?,
    mtu: Int?,
    route: String,
    appStatus: String,
    scanning: Boolean,
    devices: List<DeviceRow>,
    onScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    volume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    onMediaKey: (Int) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Headphones, contentDescription = null, tint = Blue, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("JBL TUNE 770NC", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Bluetooth audio + BLE control are monitored independently", color = Muted, fontSize = 10.sp)
                    }
                    StatusPill(bleState, if (bleState == "CONNECTED") Green else if (bleState == "CONNECTING") Blue else Muted)
                }
                HorizontalDivider(color = PanelSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AUDIO: ${if (audioConnected) "CONNECTED" else "DISCONNECTED"}", color = if (audioConnected) Green else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("A2DP ${if (a2dpConnected) "connected" else "off"} · Headset ${if (headsetConnected) "connected" else "off"}", color = Muted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("BLE GATT: $bleState", color = if (bleState == "CONNECTED") Green else if (bleState == "CONNECTING") Blue else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("MTU ${mtu ?: "—"}", color = Muted, fontSize = 10.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TelemetryItem("BATTERY", batteryLevel?.let { "$it%" } ?: "Unavailable")
                    TelemetryItem("RSSI", rssi?.let { "$it dBm" } ?: "Unavailable")
                    TelemetryItem("TRANSPORT", buildString {
                        if (bleState == "CONNECTED") append("BLE")
                        if (audioConnected) { if (isNotEmpty()) append(" + "); append("Audio") }
                        if (isEmpty()) append("None")
                    })
                }
                Text(route, color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val target = devices.filter { it.isJbl }.sortedBy { if (it.name.contains("-LE", true)) 0 else 1 }.firstOrNull()
                            if (target != null) onConnect(target.device)
                        },
                        enabled = bleState != "CONNECTING" && bleState != "CONNECTED",
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        modifier = Modifier.weight(1f)
                    ) { Text("Connect BLE") }
                    OutlinedButton(onClick = onDisconnect, enabled = bleState != "DISCONNECTED", modifier = Modifier.weight(1f)) { Text("Disconnect") }
                }
                Text(appStatus, color = Muted, fontSize = 11.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("PRECISION MEDIA & VOLUME", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Music volume", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("$volume / $maxVolume", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Slider(value = volume.toFloat(), onValueChange = { onVolumeChange(it.toInt()) }, valueRange = 0f..maxVolume.toFloat().coerceAtLeast(1f), steps = (maxVolume - 1).coerceAtLeast(0))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { onMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Blue) }
                    IconButton(onClick = { onMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play or pause", tint = Blue) }
                    IconButton(onClick = { onMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }) { Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Blue) }
                }
                Text("Media keys are dispatched to Android's current media-key consumer.", color = Muted, fontSize = 10.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NEARBY & PAIRED DEVICES", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f), fontSize = 12.sp)
            TextButton(onClick = onScan, enabled = !scanning) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (scanning) "SCANNING" else "SCAN")
            }
        }
        val sortedDevices = devices.sortedWith(compareBy<DeviceRow> { if (it.isJbl) 0 else 1 }.thenBy { if (it.name.contains("-LE", true)) 0 else 1 }.thenBy { it.name.lowercase(Locale.US) })
        if (sortedDevices.isEmpty()) Text("No devices found yet.", color = Muted, fontSize = 12.sp)
        else sortedDevices.take(40).forEach { device ->
            Card(
                modifier = Modifier.fillMaxWidth().then(if (device.isJbl) Modifier.clickable { onConnect(device.device) } else Modifier),
                colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = if (device.isJbl) Blue else Muted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${device.address} · ${if (device.paired) "Paired" else "Nearby"} · ${device.rssi?.let { "$it dBm" } ?: "RSSI —"}", color = Muted, fontSize = 10.sp)
                    }
                    if (device.isJbl) Text("CONNECT", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EqualizerPage(
    gains: List<Float>, onGainChange: (Int, Float) -> Unit,
    preamp: Float, onPreampChange: (Float) -> Unit,
    eqEnabled: Boolean, onEqEnabledChange: (Boolean) -> Unit,
    limiterEnabled: Boolean, onLimiterChange: (Boolean) -> Unit,
    sessionCompatibility: Boolean, onSessionCompatibilityChange: (Boolean) -> Unit,
    effectStatus: String, selectedPreset: String, onPreset: (String) -> Unit
) {
    val labels = listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("10-BAND DYNAMICS EQ", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Native Android audio effect · API 28+", color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = eqEnabled, onCheckedChange = onEqEnabledChange)
                }
                StatusPill(effectStatus, if (effectStatus.startsWith("Active")) Green else Muted)
                Text("EQ control: ${if (eqEnabled) "Enabled" else "Bypassed"}", color = Muted, fontSize = 11.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("QUICK PRESETS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                listOf(listOf("Flat", "Deep Bass", "Vocal Boost"), listOf("Club", "Studio", "Custom")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { preset ->
                            if (preset == "Custom") {
                                OutlinedButton(
                                    onClick = { onPreset("Custom") },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE4E4E7))
                                ) { Text(preset, fontSize = 10.sp, color = Color(0xFFE4E4E7)) }
                            } else {
                                val selected = selectedPreset == preset
                                Button(
                                    onClick = { onPreset(preset) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A)),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) Blue else PanelSoft,
                                        contentColor = if (selected) Color.White else Color(0xFFE4E4E7)
                                    )
                                ) { Text(preset, fontSize = 10.sp, color = if (selected) Color.White else Color(0xFFE4E4E7)) }
                            }
                        }
                    }
                }
                Text("Selected: $selectedPreset", color = Muted, fontSize = 10.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("PREAMP INPUT GAIN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(value = preamp, onValueChange = onPreampChange, valueRange = -12f..12f, steps = 23, modifier = Modifier.weight(1f))
                    Text("${"%.1f".format(Locale.US, preamp)} dB", modifier = Modifier.width(64.dp), color = Blue, fontSize = 11.sp)
                }
                HorizontalDivider(color = PanelSoft)
                labels.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.width(53.dp), fontSize = 11.sp, color = Muted)
                        Slider(value = gains[index], onValueChange = { onGainChange(index, it) }, valueRange = -12f..12f, steps = 23, modifier = Modifier.weight(1f))
                        Text("${if (gains[index] > 0) "+" else ""}${"%.1f".format(Locale.US, gains[index])}", modifier = Modifier.width(45.dp), color = Blue, fontSize = 10.sp)
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OUTPUT LIMITER", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("DynamicsProcessing end-stage limiter (-1 dBFS threshold)", color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = limiterEnabled, onCheckedChange = onLimiterChange)
                }
                HorizontalDivider(color = PanelSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SESSION COMPATIBILITY MODE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text("Attach DynamicsProcessing when an app opens an audio-effect session.", color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = sessionCompatibility, onCheckedChange = onSessionCompatibilityChange)
                }
            }
        }
        Text("Bluetooth note: Android audio effects are session/routing dependent. A reported active effect does not guarantee every OEM Bluetooth path applies processing to every codec.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun InspectorPage(
    bleState: String,
    services: List<GattServiceRow>,
    snapshotCached: Boolean,
    airohaListening: Boolean,
    canListenAirohaRx: Boolean,
    onToggleAirohaListener: () -> Unit,
    airohaCaptureLog: List<String>,
    classicSdpUuids: List<UUID>,
    classicSdpStatus: String,
    canScanClassicSdp: Boolean,
    onScanClassicSdp: () -> Unit,
    inspectorExpanded: Boolean,
    onInspectorExpanded: (Boolean) -> Unit,
    onReadCharacteristic: (GattCharacteristicRow) -> Unit,
    onToggleNotify: (GattCharacteristicRow) -> Unit,
    onRequestWrite: (GattCharacteristicRow) -> Unit,
    packetLog: List<String>,
    onCopyDump: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PROTOCOL INSPECTOR & DIAGNOSTICS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("BLE GATT: $bleState", color = if (bleState == "CONNECTED") Green else Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = onCopyDump) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("COPY DUMP")
                    }
                }
                Text("Read, notify and write controls are driven by the remote characteristic properties. Safe Hex Write always requires confirmation.", color = Muted, fontSize = 10.sp)
                Text(
                    if (snapshotCached || bleState == "DISCONNECTED") "Last Discovered Snapshot (Cached)" else "Live GATT snapshot",
                    color = if (snapshotCached || bleState == "DISCONNECTED") Blue else Green,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AIROHA RACE · PASSIVE RX LISTENER", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                            if (airohaListening) "Listening for RX notifications — press the physical ANC / Ambient Aware button"
                            else if (canListenAirohaRx) "Ready · notification-only capture; no Airoha TX payload writes"
                            else "Connect to JBL Tune 770NC-LE and discover the Airoha RACE service",
                            color = if (airohaListening) Green else Muted,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = airohaListening,
                        onCheckedChange = { onToggleAirohaListener() },
                        enabled = canListenAirohaRx
                    )
                }
                Text("Only RX 43484152-2DAB-3141-6972-6F6861424C45 is subscribed. VERLYN never sends unverified RACE commands.", color = Muted, fontSize = 9.sp)
                Text("AIROHA RX CAPTURE · ${airohaCaptureLog.size} PACKET(S)", color = Blue, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                if (airohaCaptureLog.isEmpty()) {
                    Text("No Airoha RX packets captured yet.", color = Muted, fontSize = 10.sp)
                } else {
                    airohaCaptureLog.take(100).forEach { line ->
                        Text(line, color = Color(0xFFC5D0DE), fontSize = 9.sp, lineHeight = 13.sp)
                        HorizontalDivider(color = PanelSoft)
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("CLASSIC RFCOMM / SDP SCANNER", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Button(
                    onClick = onScanClassicSdp,
                    enabled = canScanClassicSdp,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) { Text("SCAN CLASSIC RFCOMM / SDP UUIDS") }
                Text(classicSdpStatus, color = Muted, fontSize = 10.sp)
                if (classicSdpUuids.isEmpty()) {
                    Text("No Classic Service UUIDs captured yet.", color = Muted, fontSize = 10.sp)
                } else {
                    classicSdpUuids.forEach { uuid ->
                        Text("CLASSIC SERVICE UUID  $uuid", color = Color(0xFFE4E4E7), fontSize = 10.sp)
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onInspectorExpanded(!inspectorExpanded) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (snapshotCached || bleState == "DISCONNECTED") "LAST DISCOVERED SNAPSHOT (CACHED)" else "DISCOVERED GATT SERVICES"} (${services.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (inspectorExpanded) "COLLAPSE" else "EXPAND", color = Blue, fontSize = 9.sp)
                }
                if (inspectorExpanded) {
                    if (services.isEmpty()) Text("Connect to the JBL Tune 770NC-LE to discover services.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                    services.forEach { service ->
                        Card(colors = CardDefaults.cardColors(containerColor = PanelSoft), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Row(Modifier.fillMaxWidth().clickable { service.expanded = !service.expanded }, verticalAlignment = Alignment.CenterVertically) {
                                    Text(serviceDisplayName(service.uuid), color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(service.uuid.toString(), color = Color.White, fontSize = 9.sp)
                                    Text(if (service.expanded) "−" else "+", color = Blue)
                                }
                                if (service.expanded) service.characteristics.forEach { row ->
                                    CharacteristicCard(
                                        row,
                                        bleState == "CONNECTED",
                                        onReadCharacteristic,
                                        onToggleNotify,
                                        onRequestWrite
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("LIVE OPERATION & PACKET LOG", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Newest entries first · ${packetLog.size} captured events", color = Muted, fontSize = 10.sp)
                if (packetLog.isEmpty()) Text("No GATT operations captured yet.", color = Muted, fontSize = 11.sp)
                else packetLog.take(120).forEach { line -> Text(line, color = Color(0xFFC5D0DE), fontSize = 9.sp, lineHeight = 13.sp); HorizontalDivider(color = PanelSoft) }
            }
        }
    }
}

@Composable
private fun CharacteristicCard(
    row: GattCharacteristicRow,
    interactionsEnabled: Boolean,
    onRead: (GattCharacteristicRow) -> Unit,
    onNotify: (GattCharacteristicRow) -> Unit,
    onWrite: (GattCharacteristicRow) -> Unit
) {
    val props = row.properties
    val canRead = props and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val isProtectedAirohaTx = row.serviceUuid == AirohaRaceServiceUuid && row.uuid == AirohaTxCharacteristicUuid
    val canWrite = !isProtectedAirohaTx && props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val isDedicatedAirohaRx = row.serviceUuid == AirohaRaceServiceUuid && row.uuid == AirohaRxCharacteristicUuid
    val canNotify = !isDedicatedAirohaRx && props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("CHARACTERISTIC ${row.uuid}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            propertyNames(props).forEach { property ->
                Surface(color = Color(0xFF24344D), shape = RoundedCornerShape(5.dp)) {
                    Text(property, color = Color(0xFF9EC3FF), fontSize = 8.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
                }
            }
        }
        Text("Value HEX: ${row.valueHex}", color = Blue, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
        Text("Value UTF-8: ${row.valueText}", color = Muted, fontSize = 9.sp)
        val descriptors = row.descriptorUuids
        Text(if (descriptors.isEmpty()) "Descriptors: none" else "Descriptors: ${descriptors.joinToString { it.toString() }}", color = Muted, fontSize = 8.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (interactionsEnabled && canRead) OutlinedButton(onClick = { onRead(row) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp)) { Text("READ", fontSize = 9.sp) }
            if (interactionsEnabled && canNotify) OutlinedButton(onClick = { onNotify(row) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp)) { Text(if (row.notificationsEnabled) "STOP NOTIFY" else "NOTIFY / INDICATE", fontSize = 9.sp) }
            if (interactionsEnabled && canWrite) Button(onClick = { onWrite(row) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("SAFE HEX WRITE", fontSize = 9.sp) }
        }
        HorizontalDivider(color = Color(0xFF303A49), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(30.dp)) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
    }
}

@Composable
private fun RowScope.TelemetryItem(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private fun serviceDisplayName(uuid: UUID): String = when (uuid) {
    AirohaRaceServiceUuid -> "Airoha RACE"
    GoogleFastPairServiceUuid -> "Google Fast Pair"
    AmazonAmaServiceUuid -> "Amazon Alexa AMA"
    DultServiceUuid -> "DULT Tracker"
    UUID.fromString("00001800-0000-1000-8000-00805f9b34fb") -> "Generic Access"
    UUID.fromString("00001801-0000-1000-8000-00805f9b34fb") -> "Generic Attribute"
    UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb") -> "Device Information"
    else -> "Vendor / Bluetooth service"
}

private fun propertyNames(properties: Int): List<String> = buildList {
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    if (isEmpty()) add("NO STANDARD PROPERTIES")
}

private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

private fun parseHex(source: String): ByteArray? {
    val cleaned = source.replace(Regex("(?i)0x"), "").replace(Regex("[^0-9a-fA-F]"), "")
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    return try { ByteArray(cleaned.length / 2) { index -> cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte() } }
    catch (_: NumberFormatException) { null }
}
