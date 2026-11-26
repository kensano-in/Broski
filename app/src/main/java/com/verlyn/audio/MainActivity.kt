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
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import java.text.SimpleDateFormat
import java.util.Date
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
    var valueHex by mutableStateOf("No value read")
    var valueText by mutableStateOf("—")
    var notificationsEnabled by mutableStateOf(false)
}

private class GattServiceRow(val service: android.bluetooth.BluetoothGattService) {
    val uuid: UUID get() = service.uuid
    var expanded by mutableStateOf(true)
    val characteristics = service.characteristics.map { GattCharacteristicRow(service.uuid, it) }
}

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceRows = mutableStateListOf<DeviceRow>()
    private val gattServices = mutableStateListOf<GattServiceRow>()
    private val packetLog = mutableStateListOf<String>()

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
    private var outputRoute by mutableStateOf("No active output route reported")
    private var audioEffectStatus by mutableStateOf("Initializing")
    private var scanning by mutableStateOf(false)
    private var selectedTab by mutableIntStateOf(0)
    private var inspectorExpanded by mutableStateOf(true)
    private var writeTarget by mutableStateOf<GattCharacteristicRow?>(null)
    private var writeHexInput by mutableStateOf("")
    private var eqEnabled by mutableStateOf(true)
    private var limiterEnabled by mutableStateOf(true)
    private var sessionCompatibility by mutableStateOf(false)
    private var preamp by mutableFloatStateOf(0f)
    private var musicVolume by mutableIntStateOf(0)
    private var maxMusicVolume by mutableIntStateOf(15)
    private var selectedPreset by mutableStateOf("Flat")
    private val eqGains = mutableStateListOf<Float>().apply { repeat(10) { add(0f) } }
    private val compatibilitySessions = mutableSetOf<Int>()

    private val preferences by lazy { getSharedPreferences("verlyn_audio_preferences", Context.MODE_PRIVATE) }

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

    private val rssiPoll = object : Runnable {
        override fun run() {
            val activeGatt = gatt
            if (activeGatt != null && bleState == "CONNECTED") {
                try { activeGatt.readRemoteRssi() } catch (error: Throwable) {
                    appendLog("RSSI request failed: ${error.message ?: "unknown error"}")
                }
                mainHandler.postDelayed(this, 10_000L)
            }
        }
    }

    private val routePoll = object : Runnable {
        override fun run() { refreshOutputRoute(); mainHandler.postDelayed(this, 4_000L) }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                loadPairedDevices()
                startScan()
                requestProfileProxies()
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
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    gatt = callbackGatt
                    bleState = "CONNECTED"
                    appStatus = "BLE GATT connected"
                    appendLog("GATT connected to ${safeDeviceName(callbackGatt.device)}")
                    mainHandler.removeCallbacks(rssiPoll)
                    mainHandler.post(rssiPoll)
                    val accepted = try { callbackGatt.requestMtu(517) } catch (_: Throwable) { false }
                    appendLog(if (accepted) "Requested MTU 517" else "MTU request unavailable; discovering services")
                    if (!accepted) callbackGatt.discoverServices()
                    mainHandler.postDelayed({
                        if (gatt === callbackGatt && bleState == "CONNECTED" && gattServices.isEmpty()) callbackGatt.discoverServices()
                    }, 3_500L)
                } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                    bleState = "CONNECTING"
                    appendLog("GATT connecting (status $status)")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (gatt === callbackGatt || gatt == null) {
                        bleState = "DISCONNECTED"
                        batteryLevel = null
                        rssi = null
                        mtu = null
                        gattServices.clear()
                        mainHandler.removeCallbacks(rssiPoll)
                        gatt = null
                    }
                    appendLog("GATT disconnected (status $status)")
                    try { callbackGatt.close() } catch (_: Throwable) {}
                }
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, value: Int, status: Int) {
            runOnUiThread {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) value else null
                appendLog("MTU result: ${if (status == BluetoothGatt.GATT_SUCCESS) value else "failed ($status)"}")
                if (callbackGatt === gatt) callbackGatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gattServices.clear()
                    callbackGatt.services.forEach { gattServices.add(GattServiceRow(it)) }
                    appendLog("Discovered ${callbackGatt.services.size} GATT service(s)")
                    readStandardBattery(callbackGatt)
                } else appendLog("Service discovery failed: status $status")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleCharacteristicValue(characteristic, value, status, "READ")
        }

        @Deprecated("Deprecated on Android 13; kept for Android 12 and earlier")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleCharacteristicValue(characteristic, characteristic.value ?: byteArrayOf(), status, "READ")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristicValue(characteristic, value, BluetoothGatt.GATT_SUCCESS, "NOTIFY")
        }

        @Deprecated("Deprecated on Android 13; kept for Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicValue(characteristic, characteristic.value ?: byteArrayOf(), BluetoothGatt.GATT_SUCCESS, "NOTIFY")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            runOnUiThread { appendLog("DESCRIPTOR WRITE ${descriptor.uuid}: ${if (status == BluetoothGatt.GATT_SUCCESS) "success" else "failed ($status)"}") }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) runOnUiThread {
                rssi = value
                val address = safeAddress(gatt.device)
                val index = deviceRows.indexOfFirst { it.address == address }
                if (index >= 0) deviceRows[index] = deviceRows[index].copy(rssi = value)
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshClassicState(); refreshOutputRoute() }
    }

    private val audioEffectSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (sessionId <= 0) return
            if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                if (sessionCompatibility) {
                    compatibilitySessions.add(sessionId)
                    audioEngine.attachSession(sessionId, eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled)
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
        audioEngine = VerlynAudioEngine(this) { value -> runOnUiThread { audioEffectStatus = value } }
        audioEngine.attachGlobal(eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled)
        refreshVolume()
        registerBluetoothReceiver()
        registerAudioEffectReceiver()
        requestProfileProxies()

        setContent {
            VerlynTheme {
                VerlynApp(
                    selectedTab = selectedTab,
                    onTabChange = { selectedTab = it },
                    bleState = bleState,
                    audioConnected = a2dpConnected || headsetConnected,
                    a2dpConnected = a2dpConnected,
                    headsetConnected = headsetConnected,
                    batteryLevel = batteryLevel,
                    rssi = rssi,
                    mtu = mtu,
                    route = outputRoute,
                    appStatus = appStatus,
                    scanning = scanning,
                    devices = deviceRows.toList(),
                    onScan = { checkPermissionsAndScan() },
                    onConnect = { connectGatt(it) },
                    onDisconnect = { disconnectGatt() },
                    gains = eqGains.toList(),
                    onGainChange = { index, value ->
                        eqGains[index] = value
                        selectedPreset = "Custom"
                        saveAudioPreferences()
                        applyAudioSettings()
                    },
                    preamp = preamp,
                    onPreampChange = {
                        preamp = it; selectedPreset = "Custom"; saveAudioPreferences(); applyAudioSettings()
                    },
                    eqEnabled = eqEnabled,
                    onEqEnabledChange = { eqEnabled = it; saveAudioPreferences(); applyAudioSettings() },
                    limiterEnabled = limiterEnabled,
                    onLimiterChange = { limiterEnabled = it; saveAudioPreferences(); applyAudioSettings() },
                    sessionCompatibility = sessionCompatibility,
                    onSessionCompatibilityChange = { updateSessionCompatibility(it) },
                    effectStatus = audioEffectStatus,
                    selectedPreset = selectedPreset,
                    onPreset = { applyPreset(it) },
                    volume = musicVolume,
                    maxVolume = maxMusicVolume,
                    onVolumeChange = { updateMusicVolume(it) },
                    onMediaKey = { sendMediaKey(it) },
                    services = gattServices.toList(),
                    inspectorExpanded = inspectorExpanded,
                    onInspectorExpanded = { inspectorExpanded = it },
                    onReadCharacteristic = { readCharacteristic(it) },
                    onToggleNotify = { toggleNotifications(it) },
                    onRequestWrite = { writeTarget = it; writeHexInput = "" },
                    packetLog = packetLog.toList(),
                    onCopyDump = { copyGattDump() },
                    writeTarget = writeTarget,
                    writeHexInput = writeHexInput,
                    onWriteHexChange = { writeHexInput = it },
                    onConfirmWrite = { writeSelectedCharacteristic() },
                    onDismissWrite = { writeTarget = null }
                )
            }
        }
        checkPermissionsAndScan()
        mainHandler.post(routePoll)
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothStateReceiver, filter)
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
                if (isJblName(name) && (targetDevice == null || name.contains("-LE", true))) targetDevice = device
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
    }

    private fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        else -> "Output type $type"
    }

    private fun safeAddress(device: BluetoothDevice): String = try { device.address } catch (_: Throwable) { "Unavailable" }
    private fun safeDeviceName(device: BluetoothDevice): String = try { device.name ?: "Bluetooth device" } catch (_: Throwable) { "Bluetooth device" }

    private fun connectGatt(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) { checkPermissionsAndScan(); return }
        if (bleState == "CONNECTING") return
        if (gatt != null && safeAddress(gatt!!.device) == safeAddress(device) && bleState == "CONNECTED") {
            appStatus = "This device already has a BLE GATT connection."; return
        }
        try {
            disconnectGatt()
            targetDevice = device
            batteryLevel = null; rssi = null; mtu = null; gattServices.clear()
            bleState = "CONNECTING"
            appStatus = "Connecting to ${safeDeviceName(device)} over BLE…"
            appendLog("GATT connection requested: ${safeDeviceName(device)} (${safeAddress(device)})")
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) { bleState = "DISCONNECTED"; appStatus = "Android did not create a GATT client."; appendLog("connectGatt returned null") }
            refreshClassicState()
        } catch (_: SecurityException) {
            bleState = "DISCONNECTED"; appStatus = "Bluetooth connect permission was denied."
        } catch (error: Throwable) {
            bleState = "DISCONNECTED"; appStatus = "GATT connection failed: ${error.message ?: "unknown error"}"
        }
    }

    private fun disconnectGatt() {
        mainHandler.removeCallbacks(rssiPoll)
        val current = gatt
        if (current != null) {
            try { current.disconnect() } catch (_: Throwable) {}
            mainHandler.postDelayed({
                try { current.close() } catch (_: Throwable) {}
                if (gatt === current) gatt = null
            }, 900L)
        }
        gatt = null; bleState = "DISCONNECTED"; batteryLevel = null; rssi = null; mtu = null
        gattServices.clear(); appStatus = "BLE GATT disconnected"
        appendLog("GATT disconnect requested")
    }

    private fun readStandardBattery(activeGatt: BluetoothGatt) {
        try {
            val characteristic = activeGatt.getService(BatteryServiceUuid)?.getCharacteristic(BatteryLevelUuid)
            if (characteristic == null) {
                batteryLevel = null; appendLog("Standard Battery Service not exposed by this device"); return
            }
            val accepted = activeGatt.readCharacteristic(characteristic)
            appendLog(if (accepted) "Reading standard Battery Level characteristic" else "Battery read request was not accepted")
            val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (canNotify) {
                val batteryRow = gattServices.flatMap { it.characteristics }
                    .firstOrNull { it.uuid == BatteryLevelUuid }
                if (batteryRow != null) toggleNotifications(batteryRow)
            }
        } catch (error: Throwable) {
            batteryLevel = null; appendLog("Battery read unavailable: ${error.message ?: "unknown error"}")
        }
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
            appendLog("$operation ${characteristic.uuid} status=$status HEX[$hex] UTF8[$utf8]")
        }
    }

    private fun readCharacteristic(row: GattCharacteristicRow) {
        val activeGatt = gatt ?: return
        if (row.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            appendLog("READ skipped: ${row.uuid} is not readable"); return
        }
        try {
            val accepted = activeGatt.readCharacteristic(row.characteristic)
            appendLog(if (accepted) "READ requested ${row.uuid}" else "READ request rejected ${row.uuid}")
        } catch (error: Throwable) { appendLog("READ error ${row.uuid}: ${error.message ?: "unknown"}") }
    }

    private fun toggleNotifications(row: GattCharacteristicRow) {
        val activeGatt = gatt ?: return
        val characteristic = row.characteristic
        val props = characteristic.properties
        val supportsNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) return
        val descriptor = characteristic.getDescriptor(CccdUuid)
        if (descriptor == null) { appendLog("CCCD descriptor missing on ${row.uuid}"); return }
        val enable = !row.notificationsEnabled
        try {
            if (!activeGatt.setCharacteristicNotification(characteristic, enable)) {
                appendLog("Local notification registration failed for ${row.uuid}"); return
            }
            descriptor.value = when {
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                supportsIndicate && !supportsNotify -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (activeGatt.writeDescriptor(descriptor)) {
                row.notificationsEnabled = enable
                appendLog("${if (enable) "SUBSCRIBE" else "UNSUBSCRIBE"} requested ${row.uuid}")
            } else {
                activeGatt.setCharacteristicNotification(characteristic, !enable)
                appendLog("CCCD write rejected for ${row.uuid}")
            }
        } catch (error: Throwable) { appendLog("Notification setup failed ${row.uuid}: ${error.message ?: "unknown"}") }
    }

    private fun writeSelectedCharacteristic() {
        val row = writeTarget ?: return
        val activeGatt = gatt ?: return
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
            val accepted = activeGatt.writeCharacteristic(row.characteristic)
            appendLog(if (accepted) "WRITE requested ${row.uuid} HEX[${bytes.toHexString()}]" else "WRITE request rejected ${row.uuid}")
        } catch (error: Throwable) { appendLog("WRITE error ${row.uuid}: ${error.message ?: "unknown"}") }
        writeTarget = null
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        packetLog.add(0, "[$time] $message")
        while (packetLog.size > 250) packetLog.removeAt(packetLog.lastIndex)
    }

    private fun copyGattDump() {
        val target = targetDevice
        val dump = buildString {
            appendLine("VERLYN 0.1.2 — GATT DUMP")
            appendLine("Device: ${target?.let { safeDeviceName(it) } ?: "Not selected"}")
            appendLine("Address: ${target?.let { safeAddress(it) } ?: "Unavailable"}")
            appendLine("BLE state: $bleState")
            appendLine("MTU: ${mtu ?: "Unavailable"}")
            appendLine("Battery: ${batteryLevel?.let { "$it%" } ?: "Unavailable"}")
            appendLine("RSSI: ${rssi?.let { "$it dBm" } ?: "Unavailable"}")
            appendLine()
            gattServices.forEach { service ->
                appendLine("SERVICE ${service.uuid}")
                service.characteristics.forEach { item ->
                    appendLine("  CHARACTERISTIC ${item.uuid}")
                    appendLine("    PROPERTIES ${propertyNames(item.characteristic.properties).joinToString(", ")}")
                    appendLine("    VALUE_HEX ${item.valueHex}")
                    appendLine("    VALUE_UTF8 ${item.valueText}")
                    item.characteristic.descriptors.forEach { descriptor -> appendLine("    DESCRIPTOR ${descriptor.uuid}") }
                }
            }
            appendLine(); appendLine("PACKET / OPERATION LOG")
            packetLog.asReversed().forEach { appendLine(it) }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Verlyn GATT dump", dump))
        Toast.makeText(this, "Full GATT dump copied", Toast.LENGTH_SHORT).show()
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
        sessionCompatibility = preferences.getBoolean("session_compatibility", false)
    }

    private fun saveAudioPreferences() {
        preferences.edit()
            .putString("eq_gains", eqGains.joinToString(","))
            .putFloat("preamp", preamp)
            .putBoolean("eq_enabled", eqEnabled)
            .putBoolean("limiter_enabled", limiterEnabled)
            .putString("preset", selectedPreset)
            .apply()
    }

    private fun applyAudioSettings() {
        if (!audioEngine.hasGlobalMixEffect()) audioEngine.attachGlobal(eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled)
        else audioEngine.apply(eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled)
        if (sessionCompatibility) compatibilitySessions.forEach {
            audioEngine.attachSession(it, eqGains.toFloatArray(), preamp, eqEnabled, limiterEnabled)
        }
    }

    private fun applyPreset(name: String) {
        if (name != "Custom") {
            val values = when (name) {
                "Deep Bass" -> floatArrayOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)
                "Vocal Boost" -> floatArrayOf(-1f, -1f, 0f, 1f, 2f, 3f, 3f, 2f, 1f, 0f)
                "Club" -> floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 3f)
                else -> FloatArray(10) { 0f }
            }
            values.forEachIndexed { index, value -> eqGains[index] = value }
            preamp = if (name == "Deep Bass" || name == "Club") -2f else 0f
        }
        selectedPreset = name
        saveAudioPreferences(); applyAudioSettings()
    }

    private fun refreshVolume() {
        audioManager?.let {
            musicVolume = it.getStreamVolume(AudioManager.STREAM_MUSIC)
            maxMusicVolume = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }
    }

    private fun updateMusicVolume(value: Int) {
        val next = value.coerceIn(0, maxMusicVolume)
        musicVolume = next
        try { audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) } catch (_: SecurityException) {}
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

    override fun onResume() {
        super.onResume()
        if (::audioEngine.isInitialized) applyAudioSettings()
        refreshVolume(); refreshClassicState(); refreshOutputRoute()
        if (adapter != null && hasBluetoothPermissions() && adapter?.isEnabled == true && !scanActive) {
            loadPairedDevices(); startScan()
        }
    }

    override fun onPause() {
        stopScan(); mainHandler.removeCallbacks(rssiPoll)
        super.onPause()
    }

    override fun onDestroy() {
        stopScan(); mainHandler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
        gatt = null
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(audioEffectSessionReceiver) } catch (_: Throwable) {}
        try { a2dpProxy?.let { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (_: Throwable) {}
        try { headsetProxy?.let { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } } catch (_: Throwable) {}
        if (::audioEngine.isInitialized) audioEngine.releaseAll()
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
                    bleState, services, inspectorExpanded, onInspectorExpanded, onReadCharacteristic,
                    onToggleNotify, onRequestWrite, packetLog, onCopyDump
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
                Divider(color = PanelSoft)
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
                            if (preset == "Custom") OutlinedButton(onClick = { onPreset("Custom") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(preset, fontSize = 10.sp) }
                            else Button(onClick = { onPreset(preset) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = if (selectedPreset == preset) Blue else PanelSoft)) { Text(preset, fontSize = 10.sp) }
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
                Divider(color = PanelSoft)
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
                Divider(color = PanelSoft)
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
    bleState: String, services: List<GattServiceRow>, inspectorExpanded: Boolean,
    onInspectorExpanded: (Boolean) -> Unit,
    onReadCharacteristic: (GattCharacteristicRow) -> Unit,
    onToggleNotify: (GattCharacteristicRow) -> Unit,
    onRequestWrite: (GattCharacteristicRow) -> Unit,
    packetLog: List<String>, onCopyDump: () -> Unit
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
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onInspectorExpanded(!inspectorExpanded) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("DISCOVERED GATT SERVICES (${services.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (inspectorExpanded) "COLLAPSE" else "EXPAND", color = Blue, fontSize = 9.sp)
                }
                if (inspectorExpanded) {
                    if (services.isEmpty()) Text("Connect to the JBL Tune 770NC-LE to discover services.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                    services.forEach { service ->
                        Card(colors = CardDefaults.cardColors(containerColor = PanelSoft), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Row(Modifier.fillMaxWidth().clickable { service.expanded = !service.expanded }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("SERVICE", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(7.dp))
                                    Text(service.uuid.toString(), color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    Text(if (service.expanded) "−" else "+", color = Blue)
                                }
                                if (service.expanded) service.characteristics.forEach { row ->
                                    CharacteristicCard(row, onReadCharacteristic, onToggleNotify, onRequestWrite)
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
                else packetLog.take(120).forEach { line -> Text(line, color = Color(0xFFC5D0DE), fontSize = 9.sp, lineHeight = 13.sp); Divider(color = PanelSoft) }
            }
        }
    }
}

@Composable
private fun CharacteristicCard(
    row: GattCharacteristicRow,
    onRead: (GattCharacteristicRow) -> Unit,
    onNotify: (GattCharacteristicRow) -> Unit,
    onWrite: (GattCharacteristicRow) -> Unit
) {
    val props = row.characteristic.properties
    val canRead = props and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canWrite = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val canNotify = props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
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
        val descriptors = row.characteristic.descriptors
        Text(if (descriptors.isEmpty()) "Descriptors: none" else "Descriptors: ${descriptors.joinToString { it.uuid.toString() }}", color = Muted, fontSize = 8.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (canRead) OutlinedButton(onClick = { onRead(row) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp)) { Text("READ", fontSize = 9.sp) }
            if (canNotify) OutlinedButton(onClick = { onNotify(row) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp)) { Text(if (row.notificationsEnabled) "STOP NOTIFY" else "NOTIFY / INDICATE", fontSize = 9.sp) }
            if (canWrite) Button(onClick = { onWrite(row) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("SAFE HEX WRITE", fontSize = 9.sp) }
        }
        Divider(color = Color(0xFF303A49), modifier = Modifier.padding(top = 8.dp))
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
