package com.verlyn.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.view.KeyEvent

class VerlynConnectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rfcomm: ClassicRfcommEngine
    private lateinit var race: AirohaRaceClient
    private lateinit var voiceAnnouncer: VoiceAnnouncer
    private lateinit var audioManager: AudioManager
    private var target: BluetoothDevice? = null
    private var battery: Int? = null
    private var batterySource = "Unavailable"
    private var firmwareVersion: String? = null
    private var modelId: String? = null
    private var noiseMode = "Unknown"
    private var noiseDetail = "Waiting for headset"
    private var noiseReady = false
    private var noiseModes = emptyList<String>()
    private var linkStatus = "Monitoring Bluetooth"
    private val stopped = AtomicBoolean(false)
    private var lastBluetoothBatteryAt = 0L
    private var headsetVolumeReceiverRegistered = false
    private enum class CommandMode { IDLE, COMMAND_WINDOW, EQ_SCROLL_MODE }
    private var commandMode = CommandMode.IDLE
    private var savedCommandVolume = -1
    private var commandSpeechPending = false
    private var commandEqPreviewPending = false
    private var pendingCommandDirection = 0
    private var pendingCommandTap: Runnable? = null
    private var previewPauseTask: Runnable? = null
    private var sleepTimerEndsAt = 0L
    private var sleepTimerStartVolume = 0
    private var sleepTimerTask: Runnable? = null
    private var pingAudioTrack: android.media.AudioTrack? = null
    private var pingStopTask: Runnable? = null
    private val commandTapWindowMs = 380L
    private val commandWindowTimeout = Runnable { onCommandWindowTimeout() }
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null
    private val activeAudioSessions = LinkedHashMap<Int, String>()
    private val intentSessionIds = mutableSetOf<Int>()
    private val playbackSessionIds = mutableSetOf<Int>()
    private val recentSessionEvents = LinkedHashMap<String, Long>()
    private val raceLog = ArrayList<String>()
    private var lastJblRouteState: Boolean? = null
    @Volatile private var activeCodecStatus: String = "Unavailable"

    private val routePoll = object : Runnable {
        override fun run() {
            val active = isJblAudioRouteActive()
            if (lastJblRouteState != active) {
                lastJblRouteState = active
                VerlynAudioRuntime.get(this@VerlynConnectionService).setRouteActive(active)
                syncHeadsetVolumeReceiver()
            }
            syncHeadsetVolumeReceiver()
            main.postDelayed(this, 1_500L)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent ?: return
            val action = data.action ?: return
            val device = readDevice(data)
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (device != null && isJbl(safeName(device))) handleConnected(device)
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = data.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_CONNECTED && device != null && isJbl(safeName(device))) {
                        handleConnected(device)
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED && device != null &&
                        target?.address == device.address) {
                        handleDisconnected()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (device != null && target?.address == device.address) handleDisconnected()
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    lastJblRouteState = false
                    VerlynAudioRuntime.get(this@VerlynConnectionService).setRouteActive(false)
                }
                CODEC_CONFIG_CHANGED_ACTION -> {
                    val deviceMatches = device == null ||
                        target?.address == device.address || isJbl(safeName(device))
                    if (deviceMatches) updateCodecStatus(data)
                }
                BATTERY_ACTION -> {
                    val level = data.getIntExtra(BATTERY_EXTRA, -1)
                    if (level in 0..100) {
                        battery = level
                        batterySource = "Android Bluetooth battery broadcast"
                        lastBluetoothBatteryAt = SystemClock.elapsedRealtime()
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("headset_battery_percent", level).apply()
                        publishState()
                    }
                }
            }
        }
    }

    private val headsetVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VOLUME_CHANGED_ACTION) handleHeadsetVolumeChanged(intent)
        }
    }

    private val audioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) handleAudioSessionIntent(intent)
        }
    }

    private val auxiliaryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val engine = VerlynAudioRuntime.get(this@VerlynConnectionService)
            engine.setBassBoost(prefs.getBoolean("bass_boost_enabled", false))
            engine.setSpatial(prefs.getBoolean("spatial_enabled", false))
            reapplyAudioSettings()
            syncHeadsetVolumeReceiver()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            syncPlaybackSessions(configs)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
            }
            runCatching {
                proxy.connectedDevices.firstOrNull { isJbl(safeName(it)) }?.let { handleConnected(it) }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dpProxy = null
            if (profile == BluetoothProfile.HEADSET) headsetProxy = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        VerlynAudioRuntime.get(this)
        race = AirohaRaceClient(bluetoothAdapter) { line -> main.post {
            noiseDetail = line
            appendRaceLog(line)
            publishState()
        } }
        voiceAnnouncer = VoiceAnnouncer(
            this,
            onUtteranceFinished = { main.post { onCommandSpeechFinished() } },
            onStatus = { line -> main.post {
                appendRaceLog("HEADSET TTS · " + line)
                sendSmartLog(line)
            } }
        )
        rfcomm = ClassicRfcommEngine(
            onStatus = { value -> main.post {
                linkStatus = value
                publishState()
                // No automatic reconnect: Classic RFCOMM only opens on a user action.
            } },
            onPacket = { uuid, bytes -> publishPacket(uuid.toString(), bytes) },
            onBattery = { levels ->
                val level = levels.firstOrNull { it in 0..100 }
                if (level != null) main.post {
                    battery = level
                    batterySource = "Fast Pair Battery Updated event"
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("headset_battery_percent", level).apply()
                    publishState()
                }
            },
            onFastPairInfo = { info -> main.post {
                info.firmwareVersion?.let { firmwareVersion = it }
                info.modelId?.let { modelId = it }
                if (info.firmwareVersion != null) appendRaceLog("Fast Pair firmware decoded · " + info.firmwareVersion)
                if (info.modelId != null) appendRaceLog("Fast Pair Model ID decoded · " + info.modelId)
                publishState()
            } },
            bluetoothAdapter = bluetoothAdapter
        )
        registerBluetoothReceiver()
        registerAudioReceivers()
        registerPlaybackCallback()
        main.post(routePoll)

        bluetoothAdapter?.let { adapter ->
            runCatching {
                val sticky = registerReceiver(null, IntentFilter(BATTERY_ACTION))
                val level = sticky?.getIntExtra(BATTERY_EXTRA, -1) ?: -1
                if (level in 0..100) {
                    battery = level
                    batterySource = "Android Bluetooth battery broadcast"
                    lastBluetoothBatteryAt = SystemClock.elapsedRealtime()
                }
            }
            runCatching { adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP) }
            runCatching { adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET) }
            runCatching {
                findPairedJbl(adapter)?.let {
                    target = it
                    readNativeBattery(it)
                    if (isJblAudioRouteActive()) handleConnected(it)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent
        if (data?.action == ACTION_COMPANION_DISAPPEARED) {
            closeCommandMode("JBL disconnected")
            VerlynAudioRuntime.get(this).releaseAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val isOperationalAction = data?.action in setOf(
            ACTION_COMPANION_APPEARED, ACTION_COMMAND_ASSIST, ACTION_COMMAND_BRIEFING,
            ACTION_COMMAND_NEXT_EQ, ACTION_AUTO_EQ_RULES_CHANGED, ACTION_FORCE_CONNECT, ACTION_SET_NOISE_MODE,
            ACTION_POWER_OFF_HEADSET, ACTION_MANUAL_RFCOMM_CONNECT,
            ACTION_MANUAL_RFCOMM_SEND
        )
        if (!isJblA2dpRouteActive() && !isOperationalAction) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForegroundCompat()
        when (data?.action) {
            ACTION_FORCE_CONNECT -> {
                ensureTarget()?.let { connectFastPair(it) }
            }
            ACTION_STOP_LINK, ACTION_MANUAL_RFCOMM_DISCONNECT -> {
                rfcomm.disconnect()
                linkStatus = "Disconnected"
                publishState()
            }
            ACTION_MANUAL_RFCOMM_CONNECT -> {
                connectManualRfcomm(data?.getStringExtra(EXTRA_MANUAL_RFCOMM_UUID).orEmpty())
            }
            ACTION_MANUAL_RFCOMM_SEND -> {
                val bytes = data?.getByteArrayExtra(EXTRA_MANUAL_RFCOMM_BYTES) ?: ByteArray(0)
                val requestedUuid = data?.getStringExtra(EXTRA_MANUAL_RFCOMM_UUID)
                val activeUuid = rfcomm.connectedUuid()?.toString()
                if (bytes.isEmpty()) {
                    linkStatus = "RFCOMM TX blocked · empty captured frame"
                    publishState()
                } else if (requestedUuid.isNullOrBlank() || activeUuid != requestedUuid) {
                    linkStatus = "RFCOMM TX blocked · selected service UUID is not the connected socket"
                    publishState()
                } else rfcomm.sendRaw(bytes)
            }
            ACTION_SET_NOISE_MODE -> {
                val requested = data?.getStringExtra(EXTRA_NOISE_MODE) ?: return START_STICKY
                val device = ensureTarget()
                if (device == null) {
                    noiseDetail = "Pair JBL Tune 770NC first"
                    publishState()
                } else {
                    rfcomm.disconnect()
                    noiseDetail = "Opening exclusive RACE socket for " + requested
                    publishState()
                    race.setNoiseMode(device, requested) { result -> main.post { applyRaceState(result) } }
                }
            }
            ACTION_POWER_OFF_HEADSET -> {
                val device = ensureTarget()
                rfcomm.disconnect()
                if (device != null) race.powerOff(device) { result ->
                    main.post { noiseDetail = result; publishState(); appendRaceLog(result) }
                } else {
                    noiseDetail = "Paired JBL Tune 770NC not found"
                    publishState()
                }
            }
            ACTION_AUDIO_SESSION_EVENT -> data?.let { handleAudioSessionIntent(it) }
            ACTION_AUDIO_SETTINGS_CHANGED -> reapplyAudioSettings()
            ACTION_COMMAND_ASSIST -> handleAssistantCommand()
            ACTION_COMMAND_BRIEFING -> {
                if (commandMode == CommandMode.IDLE) {
                    savedCommandVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    commandMode = CommandMode.COMMAND_WINDOW
                    voiceAnnouncer.beginCommandSession()
                    syncHeadsetVolumeReceiver()
                    appendRaceLog("COMMAND MODE · IDLE → COMMAND_WINDOW · notification briefing")
                }
                runConfiguredCommand("command_center_long_hold", "speak_full_briefing")
            }
            ACTION_COMMAND_NEXT_EQ -> cyclePresetFromNotification()
            ACTION_AUTO_EQ_RULES_CHANGED -> rebindActiveAudioSessions()
            ACTION_FIND_HEADSET_PING -> playFindHeadsetPing()
            ACTION_COMPANION_APPEARED -> {
                appendRaceLog("Companion presence · JBL Tune 770NC detected")
                ensureTarget()?.let { handleConnected(it) }
                restoreJblProfile()
                reapplyAudioSettings()
                syncPlaybackSessions(runCatching { audioManager.activePlaybackConfigurations }.getOrDefault(emptyList()))
            }
            else -> {
                // Service startup observes Bluetooth state only; Classic RFCOMM is user-triggered.
                syncHeadsetVolumeReceiver()
            }
        }
        publishState()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun handleConnected(device: BluetoothDevice) {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val preferred = runCatching { manager.adapter?.let { findPairedJbl(it) } }.getOrNull()
        target = preferred ?: device
        val connectedDevice = target ?: device
        noiseModes = ALL_NOISE_MODES
        val routeActive = isJblAudioRouteActive()
        lastJblRouteState = routeActive
        VerlynAudioRuntime.get(this).setRouteActive(routeActive)
        readNativeBattery(connectedDevice)
        restoreJblProfile()
        reapplyAudioSettings()
        syncPlaybackSessions(runCatching { audioManager.activePlaybackConfigurations }.getOrDefault(emptyList()))
        syncHeadsetVolumeReceiver()
        publishState()
    }

    private fun handleDisconnected() {
        closeCommandMode("headset disconnected")
        rfcomm.disconnect()
        race.cancelActive()
        syncHeadsetVolumeReceiver()
        noiseReady = false
        noiseMode = "Unknown"
        noiseModes = emptyList()
        noiseDetail = "Headset disconnected"
        lastJblRouteState = false
        VerlynAudioRuntime.get(this).setRouteActive(false)
        VerlynAudioRuntime.get(this).releaseAll()
        linkStatus = "Headset disconnected"
        publishState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasLiveBluetoothBatteryBroadcast(): Boolean =
        batterySource == "Android Bluetooth battery broadcast" &&
            battery in 0..100 &&
            SystemClock.elapsedRealtime() - lastBluetoothBatteryAt <= BATTERY_BROADCAST_FRESH_MS

    @SuppressLint("MissingPermission")
    private fun connectFastPair(device: BluetoothDevice) {
        if (stopped.get()) return
        if (hasLiveBluetoothBatteryBroadcast()) {
            linkStatus = "Fast Pair RFCOMM skipped · Android Bluetooth battery broadcast is live"
            publishState()
            return
        }
        if (race.isBusy()) {
            linkStatus = "Fast Pair RFCOMM blocked · RACE owns the Classic socket"
            publishState()
            return
        }
        if (rfcomm.connectedUuid() == ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID) return
        rfcomm.connect(device, ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID, "Fast Pair battery stream")
    }

    @SuppressLint("MissingPermission")
    private fun connectManualRfcomm(uuidValue: String) {
        if (race.isBusy()) {
            linkStatus = "RFCOMM blocked · RACE operation is active; retry after it finishes"
            publishState()
            return
        }
        val device = ensureTarget()
        if (device == null) {
            linkStatus = "Pair JBL Tune 770NC first"
            publishState()
            return
        }
        val uuid = runCatching { java.util.UUID.fromString(uuidValue) }.getOrNull()
        if (uuid == null) {
            linkStatus = "RFCOMM blocked · invalid service UUID"
            publishState()
            return
        }
        val label = when (uuid) {
            ClassicRfcommEngine.JBL_VENDOR_UUID -> "JBL Vendor SPP"
            ClassicRfcommEngine.AIROHA_RFCOMM_CANDIDATE_UUID -> "Airoha RACE UUID"
            ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID -> "Fast Pair Message Stream"
            else -> "Manual RFCOMM endpoint"
        }
        rfcomm.connect(device, uuid, label)
    }

    private fun applyRaceState(state: AirohaRaceState) {
        noiseReady = state.ready
        noiseMode = state.mode
        noiseModes = if (target != null) ALL_NOISE_MODES else emptyList()
        noiseDetail = state.detail
        publishState()
    }

    private fun appendRaceLog(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        raceLog.add("[" + stamp + "] " + message)
        while (raceLog.size > MAX_RACE_LOG_LINES) raceLog.removeAt(0)
        sendBroadcast(Intent(ACTION_RACE_LOG).setPackage(packageName).putExtra(EXTRA_RACE_LOG, raceLog.last()))
    }

    private fun publishPacket(uuid: String, bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        sendBroadcast(Intent(ACTION_PACKET).setPackage(packageName)
            .putExtra(EXTRA_PACKET_UUID, uuid)
            .putExtra(EXTRA_PACKET_LENGTH, bytes.size)
            .putExtra(EXTRA_PACKET_HEX, hex))
    }

    @SuppressLint("MissingPermission")
    private fun readNativeBattery(device: BluetoothDevice) {
        val value = runCatching {
            device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
        }.getOrNull()
        if (value != null && value in 0..100 && batterySource != "Fast Pair Battery Updated event") {
            battery = value
            batterySource = "Android Bluetooth device metadata"
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("headset_battery_percent", value).apply()
            publishState()
        }
    }

    private fun isJblAudioRouteActive(): Boolean {
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { output ->
                (output.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || output.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                    output.productName.toString().contains("JBL Tune 770NC", true)
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun findPairedJbl(adapter: android.bluetooth.BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices
        return bonded.firstOrNull { safeAddress(it).equals(PREFERRED_JBL_ADDRESS, true) }
            ?: bonded.firstOrNull { isJbl(safeName(it)) && !safeName(it).contains("-LE", true) }
    }

    @SuppressLint("MissingPermission")
    private fun ensureTarget(): BluetoothDevice? {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return target
        val preferred = runCatching { findPairedJbl(adapter) }.getOrNull()
        if (preferred != null) {
            target = preferred
            return preferred
        }
        return target
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Verlyn connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Bluetooth headphone connection and audio status"
                setShowBadge(false)
            })
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_verlyn)
            .setContentTitle("VERLYN · JBL Tune 770NC")
            .setContentText((battery?.let { "Battery " + it + "% · " } ?: "Battery unavailable · ") + "Audio companion active")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_stat_verlyn, "Briefing", PendingIntent.getService(this, 771,
                Intent(this, VerlynConnectionService::class.java).setAction(ACTION_COMMAND_BRIEFING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_stat_verlyn, "Next EQ", PendingIntent.getService(this, 772,
                Intent(this, VerlynConnectionService::class.java).setAction(ACTION_COMMAND_NEXT_EQ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_stat_verlyn, "Stop", PendingIntent.getService(this, 773,
                Intent(this, VerlynConnectionService::class.java).setAction(ACTION_COMPANION_DISAPPEARED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun startForegroundCompat() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, n)
    }

    private fun publishState() {
        val sessions = ArrayList<String>()
        activeAudioSessions.forEach { (id, pkg) -> sessions.add(appLabel(pkg) + " (Session " + id + ")") }
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_BATTERY, battery ?: -1)
            .putExtra(EXTRA_BATTERY_SOURCE, batterySource)
            .putExtra(EXTRA_LINK, linkStatus)
            .putExtra(EXTRA_NOISE_MODE, noiseMode)
            .putExtra(EXTRA_NOISE_STATUS, noiseDetail)
            .putExtra(EXTRA_NOISE_READY, noiseReady)
            .putExtra(EXTRA_FIRMWARE, firmwareVersion ?: "")
            .putExtra(EXTRA_MODEL_ID, modelId ?: "")
            .putExtra(EXTRA_CODEC_STATUS, activeCodecStatus)
            .putStringArrayListExtra(EXTRA_NOISE_MODES, ArrayList(noiseModes))
            .putStringArrayListExtra(EXTRA_AUDIO_SESSIONS, sessions))
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification()) }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateCodecStatus(intent: Intent) {
        if (Build.VERSION.SDK_INT < 33) {
            activeCodecStatus = "Unavailable · API 33+"
            publishState()
            return
        }
        val status = runCatching {
            intent.getParcelableExtra(
                android.bluetooth.BluetoothCodecStatus.EXTRA_CODEC_STATUS,
                android.bluetooth.BluetoothCodecStatus::class.java
            )
        }.getOrNull()
        val config = status?.codecConfig
        activeCodecStatus = when (config?.codecType) {
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
            android.bluetooth.BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS -> "Opus"
            null -> "Unavailable"
            else -> "Other"
        }
        appendRaceLog("A2DP CODEC · " + activeCodecStatus)
        publishState()
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BATTERY_ACTION)
            addAction(CODEC_CONFIG_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
    }

    private fun isJblA2dpRouteActive(): Boolean = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { output ->
            output.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                output.productName.toString().contains("JBL Tune 770NC", true)
        }
    }.getOrDefault(false)

    /** Normal mode never subscribes to system volume broadcasts. */
    private fun syncHeadsetVolumeReceiver() {
        val shouldRegister = commandMode != CommandMode.IDLE && isJblA2dpRouteActive()
        if (shouldRegister && !headsetVolumeReceiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this, headsetVolumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION),
                    ContextCompat.RECEIVER_EXPORTED
                )
                headsetVolumeReceiverRegistered = true
                sendSmartLog("Command Mode · headset volume keys armed")
            }.onFailure {
                appendRaceLog("Command volume receiver registration failed · " + (it.message ?: "unknown"))
            }
        } else if (!shouldRegister && headsetVolumeReceiverRegistered) {
            runCatching { unregisterReceiver(headsetVolumeReceiver) }
            headsetVolumeReceiverRegistered = false
        }
    }

    private fun handleHeadsetVolumeChanged(intent: Intent) {
        if (commandMode == CommandMode.IDLE) return
        if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
        val now = SystemClock.elapsedRealtime()
        if (VerlynVolumeChangeGuard.shouldIgnore(now)) return
        val current = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
        val previous = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)
        if (current < 0 || previous < 0 || current == previous) return
        val direction = if (current > previous) 1 else -1
        restoreCommandVolume()
        appendRaceLog("COMMAND INPUT · " + if (direction > 0) "VOL+" else "VOL-")
        if (commandMode == CommandMode.EQ_SCROLL_MODE) {
            previewPauseTask?.let { main.removeCallbacks(it) }
            moveEqPreset(direction)
            return
        }
        pendingCommandTap?.let { main.removeCallbacks(it) }
        if (pendingCommandDirection == direction) {
            pendingCommandTap = null
            pendingCommandDirection = 0
            runConfiguredCommand(
                if (direction > 0) "command_vol_up_double" else "command_vol_down_double",
                if (direction > 0) "toggle_bass_boost" else "cycle_sleep_timer"
            )
        } else {
            pendingCommandTap?.run()
            pendingCommandDirection = direction
            val task = Runnable {
                pendingCommandTap = null
                pendingCommandDirection = 0
                runConfiguredCommand(
                    if (direction > 0) "command_vol_up_single" else "command_vol_down_single",
                    if (direction > 0) "speak_battery" else "enter_eq_scroll"
                )
            }
            pendingCommandTap = task
            main.postDelayed(task, commandTapWindowMs)
        }
    }

    private fun restoreCommandVolume() {
        if (savedCommandVolume < 0) return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restored = savedCommandVolume.coerceIn(0, maxVolume)
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != restored) {
            VerlynVolumeChangeGuard.markProgrammaticChange()
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restored, 0) }
                .onFailure { appendRaceLog("Command volume lock failed · " + (it.message ?: "unknown")) }
        }
    }

    private fun handleAssistantCommand() {
        when (commandMode) {
            CommandMode.IDLE -> enterCommandWindow()
            CommandMode.COMMAND_WINDOW -> runConfiguredCommand("command_center_long_hold", "speak_full_briefing")
            CommandMode.EQ_SCROLL_MODE -> lockEqScrollMode()
        }
    }

    private fun enterCommandWindow() {
        savedCommandVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        commandMode = CommandMode.COMMAND_WINDOW
        voiceAnnouncer.beginCommandSession()
        syncHeadsetVolumeReceiver()
        appendRaceLog("COMMAND MODE · IDLE → COMMAND_WINDOW · savedVolume=$savedCommandVolume")
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
        speakCommand("It is $time")
    }

    private fun runConfiguredCommand(slot: String, defaultAction: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val action = prefs.getString(slot, defaultAction) ?: defaultAction
        appendRaceLog("COMMAND ACTION · $slot → $action")
        when (action) {
            "speak_battery" -> {
                val phone = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
                val headset = prefs.getInt("headset_battery_percent", battery ?: -1)
                speakCommand(if (headset in 0..100) "Phone $phone percent, Headset $headset percent" else "Phone $phone percent, Headset battery unavailable")
            }
            "enter_eq_scroll" -> {
                commandMode = CommandMode.EQ_SCROLL_MODE
                appendRaceLog("COMMAND MODE · COMMAND_WINDOW → EQ_SCROLL_MODE")
                speakCommand("EQ Mode")
            }
            "toggle_bass_boost" -> {
                val enabled = !prefs.getBoolean("bass_boost_enabled", false)
                prefs.edit().putBoolean("bass_boost_enabled", enabled).apply()
                VerlynAudioRuntime.get(this).setBassBoost(enabled)
                speakCommand(if (enabled) "Bass boost on" else "Bass boost off")
            }
            "cycle_sleep_timer" -> cycleSleepTimer()
            "toggle_spatial" -> {
                val enabled = !prefs.getBoolean("spatial_enabled", false)
                prefs.edit().putBoolean("spatial_enabled", enabled).apply()
                VerlynAudioRuntime.get(this).setSpatial(enabled)
                speakCommand(if (enabled) "Spatial audio on" else "Spatial audio off")
            }
            "toggle_volume_cap" -> {
                val enabled = !prefs.getBoolean("limiter_enabled", true)
                prefs.edit().putBoolean("limiter_enabled", enabled).apply()
                reapplyAudioSettings()
                speakCommand(if (enabled) "Volume cap on" else "Volume cap off")
            }
            "speak_full_briefing" -> {
                val phone = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
                val headset = prefs.getInt("headset_battery_percent", battery ?: -1)
                val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
                val headsetValue = if (headset in 0..100) "$headset percent" else "unavailable"
                val preset = prefs.getString("preset", "Flat") ?: "Flat"
                speakCommand("It is $time. Phone battery $phone percent. Headset battery $headsetValue. Equalizer preset $preset.")
            }
            "disabled" -> resetCommandTimeout()
            else -> {
                appendRaceLog("COMMAND ACTION · unsupported action ignored: $action")
                resetCommandTimeout()
            }
        }
    }

    private fun speakCommand(message: String) {
        commandSpeechPending = true
        main.removeCallbacks(commandWindowTimeout)
        val retainFocus = commandMode != CommandMode.IDLE && !commandEqPreviewPending
        if (!voiceAnnouncer.speak(message, keepFocusAfter = retainFocus)) {
            commandSpeechPending = false
            resetCommandTimeout()
        }
    }

    private fun onCommandSpeechFinished() {
        if (!commandSpeechPending) return
        commandSpeechPending = false
        if (commandMode == CommandMode.IDLE) return
        if (commandMode == CommandMode.EQ_SCROLL_MODE && commandEqPreviewPending) {
            commandEqPreviewPending = false
            previewPauseTask?.let { main.removeCallbacks(it) }
            val task = Runnable {
                if (commandMode == CommandMode.EQ_SCROLL_MODE) {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    appendRaceLog("EQ SCROLL · 4-second live preview complete")
                }
            }
            previewPauseTask = task
            main.postDelayed(task, 4_000L)
        }
        resetCommandTimeout()
    }

    private fun resetCommandTimeout() {
        main.removeCallbacks(commandWindowTimeout)
        if (commandMode != CommandMode.IDLE) main.postDelayed(commandWindowTimeout, 5_000L)
    }

    private fun onCommandWindowTimeout() {
        if (commandMode == CommandMode.EQ_SCROLL_MODE) {
            lockEqScrollMode()
        } else {
            closeCommandMode("five-second inactivity timeout")
        }
    }

    private fun closeCommandMode(reason: String) {
        main.removeCallbacks(commandWindowTimeout)
        pendingCommandTap?.let { main.removeCallbacks(it) }
        pendingCommandTap = null
        pendingCommandDirection = 0
        previewPauseTask?.let { main.removeCallbacks(it) }
        previewPauseTask = null
        commandSpeechPending = false
        commandEqPreviewPending = false
        val previous = commandMode
        commandMode = CommandMode.IDLE
        restoreCommandVolume()
        savedCommandVolume = -1
        voiceAnnouncer.endCommandSession()
        syncHeadsetVolumeReceiver()
        if (previous != CommandMode.IDLE) appendRaceLog("COMMAND MODE · $previous → IDLE · $reason")
        if (!isJblA2dpRouteActive()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun lockEqScrollMode() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val preset = prefs.getString("preset", "Flat") ?: "Flat"
        closeCommandMode("EQ preset locked")
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        voiceAnnouncer.speak("$preset locked")
    }

    private fun moveEqPreset(direction: Int) {
        if (commandMode != CommandMode.EQ_SCROLL_MODE) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val custom = (prefs.getString("saved_preset_names", "") ?: "").split("\n").filter { it.isNotBlank() }
        val names = (listOf("Flat", "Studio", "Bass", "Club", "Extreme Bass", "Vocal", "Jazz", "Podcast", "Gaming") + custom).distinct()
        val current = prefs.getString("preset", "Flat") ?: "Flat"
        val index = names.indexOf(current).let { if (it < 0) 0 else it }
        val next = names[(index + direction + names.size) % names.size]
        val selected = resolvePresetProfile(prefs, next)
        prefs.edit().putString("preset", next).putString("eq_gains", selected.first.joinToString(","))
            .putFloat("preamp", selected.second).apply()
        reapplyAudioSettings()
        commandEqPreviewPending = true
        main.postDelayed({
            if (commandMode == CommandMode.EQ_SCROLL_MODE) speakCommand(next)
        }, 250L)
        appendRaceLog("EQ SCROLL · $current → $next · live preview requested")
    }

    private fun cyclePresetFromNotification() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val custom = (prefs.getString("saved_preset_names", "") ?: "").split("\n").filter { it.isNotBlank() }
        val names = (listOf("Flat", "Studio", "Bass", "Club", "Extreme Bass", "Vocal", "Jazz", "Podcast", "Gaming") + custom).distinct()
        val current = prefs.getString("preset", "Flat") ?: "Flat"
        val index = names.indexOf(current).let { if (it < 0) 0 else it }
        val next = names[(index + 1) % names.size]
        val selected = resolvePresetProfile(prefs, next)
        prefs.edit().putString("preset", next).putString("eq_gains", selected.first.joinToString(","))
            .putFloat("preamp", selected.second).apply()
        reapplyAudioSettings()
        sendSmartLog("Next EQ · $next")
    }

    private fun restoreJblProfile() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString("jbl_eq_gains", null) ?: return
        val gains = saved.split(",").mapNotNull { it.toFloatOrNull() }
        if (gains.size == 10) prefs.edit()
            .putString("eq_gains", saved)
            .putFloat("preamp", prefs.getFloat("jbl_preamp", 0f))
            .putString("preset", prefs.getString("jbl_preset", "Flat"))
            .apply()
    }

    private fun cycleSleepTimer() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val steps = listOf(15, 30, 60, 0)
        val current = prefs.getInt("sleep_timer_minutes", 0)
        val next = steps[(steps.indexOf(current).coerceAtLeast(-1) + 1) % steps.size]
        prefs.edit().putInt("sleep_timer_minutes", next).apply()
        setSleepTimer(next)
        speakCommand(if (next == 0) "Sleep timer off" else "Sleep timer $next minutes")
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimerTask?.let { main.removeCallbacks(it) }
        sleepTimerTask = null
        sleepTimerEndsAt = 0L
        if (minutes <= 0) {
            if (sleepTimerStartVolume > 0) setCommandVolume(sleepTimerStartVolume)
            return
        }
        sleepTimerStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        sleepTimerEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        val task = object : Runnable {
            override fun run() {
                val remaining = sleepTimerEndsAt - SystemClock.elapsedRealtime()
                if (remaining > 10_000L) {
                    main.postDelayed(this, remaining - 10_000L)
                } else if (remaining > 0L) {
                    val elapsed = ((10_000L - remaining) / 1_000L).toInt().coerceIn(0, 10)
                    setCommandVolume((sleepTimerStartVolume * (10 - elapsed) / 10f).toInt())
                    main.postDelayed(this, 1_000L)
                } else {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    main.postDelayed({
                        setCommandVolume(sleepTimerStartVolume)
                        sleepTimerEndsAt = 0L
                        sleepTimerTask = null
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("sleep_timer_minutes", 0).apply()
                        sendSmartLog("Sleep timer completed · playback paused and volume restored")
                    }, 350L)
                }
            }
        }
        sleepTimerTask = task
        main.postDelayed(task, maxOf(1L, sleepTimerEndsAt - SystemClock.elapsedRealtime() - 10_000L))
    }

    private fun setCommandVolume(value: Int) {
        VerlynVolumeChangeGuard.markProgrammaticChange()
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                value.coerceIn(0, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
                0
            )
        }.onFailure { appendRaceLog("Sleep timer volume update failed · " + (it.message ?: "unknown")) }
    }

    private fun sendSmartLog(message: String) {
        sendBroadcast(
            Intent(VerlynSmartButtonService.ACTION_SMART_LOG)
                .setPackage(packageName)
                .putExtra(VerlynSmartButtonService.EXTRA_SMART_LOG, message)
        )
    }

    private fun runHeadsetAction(action: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        when (action) {
            "speak_time" -> voiceAnnouncer.speak(
                "It is " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
            )
            "speak_battery" -> {
                val phone = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
                val headset = prefs.getInt("headset_battery_percent", -1)
                voiceAnnouncer.speak(
                    if (headset in 0..100) {
                        "Phone battery is " + phone + " percent. Headset battery is " + headset + " percent."
                    } else {
                        "Phone battery is " + phone + " percent. Headset battery is unavailable."
                    }
                )
            }
            "cycle_eq" -> {
                val custom = (prefs.getString("saved_preset_names", "") ?: "").split("\n").filter { it.isNotBlank() }
                val names = (listOf("Flat", "Studio", "Bass", "Club", "Extreme Bass", "Vocal", "Jazz", "Podcast", "Gaming") + custom).distinct()
                val current = prefs.getString("preset", "Flat") ?: "Flat"
                val next = names[(names.indexOf(current).coerceAtLeast(-1) + 1) % names.size]
                val preset = resolvePresetProfile(prefs, next)
                prefs.edit()
                    .putString("preset", next)
                    .putString("eq_gains", preset.first.joinToString(","))
                    .putFloat("preamp", preset.second)
                    .apply()
                reapplyAudioSettings()
                sendHeadsetSmartAction("apply_preset", next)
                voiceAnnouncer.speak(next + " EQ active")
            }
            "toggle_limiter" -> {
                val enabled = !prefs.getBoolean("limiter_enabled", true)
                prefs.edit().putBoolean("limiter_enabled", enabled).apply()
                reapplyAudioSettings()
                sendHeadsetSmartAction("limiter_changed", enabled = enabled)
                voiceAnnouncer.speak(if (enabled) "Peak limiter on" else "Peak limiter off")
            }
            "toggle_bass_boost" -> {
                val enabled = !prefs.getBoolean("bass_boost_enabled", false)
                prefs.edit().putBoolean("bass_boost_enabled", enabled).apply()
                VerlynAudioRuntime.get(this).setBassBoost(enabled)
                sendHeadsetSmartAction("bass_boost_changed", enabled = enabled)
                voiceAnnouncer.speak(if (enabled) "Bass boost on" else "Bass boost off")
            }
            "media_next" -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            "media_previous" -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "media_play_pause" -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "disabled" -> sendSmartLog("Headset gesture ignored · action disabled")
        }
    }

    private fun sendHeadsetSmartAction(action: String, value: String? = null, enabled: Boolean? = null) {
        val intent = Intent(VerlynSmartButtonService.ACTION_SMART_ACTION).setPackage(packageName)
            .putExtra(VerlynSmartButtonService.EXTRA_ACTION, action)
        if (value != null) intent.putExtra(VerlynSmartButtonService.EXTRA_VALUE, value)
        if (enabled != null) intent.putExtra(VerlynSmartButtonService.EXTRA_ENABLED, enabled)
        sendBroadcast(intent)
    }

    private fun playFindHeadsetPing() {
        if (!isJblA2dpRouteActive()) {
            appendRaceLog("FIND HEADSET · ping skipped because JBL A2DP is not connected")
            return
        }
        pingStopTask?.let { main.removeCallbacks(it) }
        pingAudioTrack?.let { old -> runCatching { old.stop(); old.release() } }
        pingAudioTrack = null
        val sampleRate = 48_000
        val frameCount = (sampleRate * 1.95f).toInt()
        val samples = ShortArray(frameCount * 2)
        val pulseDuration = 0.48
        val cycleDuration = 0.65
        for (frame in 0 until frameCount) {
            val time = frame.toDouble() / sampleRate
            val pulseTime = time % cycleDuration
            var sample = 0.toShort()
            if (pulseTime < pulseDuration) {
                val phase = 2.0 * Math.PI * (620.0 * pulseTime +
                    (980.0 * pulseTime * pulseTime) / (2.0 * pulseDuration))
                val attack = (pulseTime / 0.018).coerceIn(0.0, 1.0)
                val release = ((pulseDuration - pulseTime) / 0.085).coerceIn(0.0, 1.0)
                val envelope = minOf(attack, release) * 0.22
                sample = (kotlin.math.sin(phase) * envelope * Short.MAX_VALUE).toInt().toShort()
            }
            samples[frame * 2] = sample
            samples[frame * 2 + 1] = sample
        }
        runCatching {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = android.media.AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            if (track.state != android.media.AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw IllegalStateException("AudioTrack failed to initialize")
            }
            val written = track.write(samples, 0, samples.size)
            if (written <= 0) {
                track.release()
                throw IllegalStateException("AudioTrack rejected the ping waveform")
            }
            track.setVolume(0.78f)
            pingAudioTrack = track
            track.play()
            val stop = Runnable {
                pingAudioTrack?.let { active -> runCatching { active.pause(); active.flush(); active.release() } }
                pingAudioTrack = null
                pingStopTask = null
            }
            pingStopTask = stop
            main.postDelayed(stop, 2_150L)
            appendRaceLog("FIND HEADSET · three rising sine chirps sent to the active A2DP media route")
        }.onFailure {
            pingAudioTrack?.let { runCatching { it.release() } }
            pingAudioTrack = null
            appendRaceLog("FIND HEADSET · ping failed · " + (it.message ?: it.javaClass.simpleName))
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    private fun headsetPreset(name: String): Pair<FloatArray, Float> {
        val gains = when (name) {
            "Studio" -> floatArrayOf(1f, 1f, 0f, 1f, 2f, 1f, 0f, 1f, 1f, 0f)
            "Bass" -> floatArrayOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
            "Club" -> floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 3f)
            "Extreme Bass" -> floatArrayOf(8f, 7f, 5f, 2f, 0f, -1f, -1f, 0f, 2f, 3f)
            "Vocal" -> floatArrayOf(-2f, -2f, -1f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)
            "Jazz" -> floatArrayOf(3f, 2f, 1f, 2f, -1f, -2f, 0f, 2f, 3f, 2f)
            "Podcast" -> floatArrayOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 2f, 0f, -1f)
            "Gaming" -> floatArrayOf(3f, 2f, 0f, -1f, 0f, 2f, 4f, 4f, 2f, 0f)
            else -> FloatArray(10)
        }
        val preamp = if (name == "Bass" || name == "Club" || name == "Extreme Bass") -2f else 0f
        return gains to preamp
    }

    private fun registerAudioReceivers() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, audioSessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val aux = IntentFilter(ACTION_AUX_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(auxiliaryReceiver, aux, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(auxiliaryReceiver, aux)
    }

    private fun registerPlaybackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                audioManager.registerAudioPlaybackCallback(playbackCallback, main)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) syncPlaybackSessions(audioManager.activePlaybackConfigurations)
            }.onFailure { appendRaceLog("Audio playback callback unavailable · " + (it.message ?: "unsupported")) }
        }
    }

    private fun syncPlaybackSessions(configs: List<AudioPlaybackConfiguration>) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("session_compatibility", true)) {
            activeAudioSessions.keys.toList().forEach { unbindAudioSession(it, "session compatibility disabled") }
            playbackSessionIds.clear()
            return
        }
        val found = LinkedHashMap<Int, String>()
        configs.forEach { config ->
            val sessionId = runCatching { config.javaClass.getMethod("getAudioSessionId").invoke(config) as? Int }.getOrNull() ?: -1
            if (sessionId <= 0) return@forEach
            val uid = runCatching { config.javaClass.getMethod("getClientUid").invoke(config) as? Int }.getOrNull()
            val packageName = uid?.let { packageManager.getPackagesForUid(it)?.firstOrNull() } ?: "Audio session"
            found[sessionId] = packageName
        }
        found.forEach { (id, packageName) ->
            playbackSessionIds.add(id)
            bindAudioSession(id, packageName, "playback")
        }
        val disappeared = playbackSessionIds.filter { it !in found.keys && it !in intentSessionIds }
        disappeared.forEach { unbindAudioSession(it, "playback stopped") }
        playbackSessionIds.retainAll(found.keys)
    }

    private fun handleAudioSessionIntent(intent: Intent) {
        val action = intent.action ?: return
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Unknown media app"
        if (sessionId <= 0) return
        val isOpen = action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ||
            action == ACTION_AUDIO_SESSION_EVENT && intent.getBooleanExtra(EXTRA_SESSION_OPEN, false)
        val sessionEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("session_compatibility", true)
        if (!sessionEnabled && isOpen) return
        val key = (if (isOpen) "open" else "close") + ":" + sessionId + ":" + packageName
        val now = SystemClock.elapsedRealtime()
        if (now - (recentSessionEvents[key] ?: 0L) < 500L) return
        recentSessionEvents[key] = now
        while (recentSessionEvents.size > 100) recentSessionEvents.remove(recentSessionEvents.keys.first())
        if (isOpen) {
            intentSessionIds.add(sessionId)
            bindAudioSession(sessionId, packageName, "AudioEffect broadcast")
        } else {
            intentSessionIds.remove(sessionId)
            if (sessionId !in playbackSessionIds) unbindAudioSession(sessionId, "AudioEffect close")
            else appendRaceLog("AudioEffect close received; keeping active playback session " + sessionId)
        }
    }

    private fun bindAudioSession(sessionId: Int, packageName: String, source: String) {
        val normalized = packageName.ifBlank { "Unknown media app" }
        if (activeAudioSessions[sessionId] == normalized) return
        if (activeAudioSessions.containsKey(sessionId)) {
            VerlynAudioRuntime.get(this).detachSession(sessionId)
        }
        activeAudioSessions[sessionId] = normalized
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val assignedPreset = prefs.getString("auto_eq_rule:" + normalized, null)
            ?.takeIf { it.isNotBlank() && it != "Off" }
        val assignedProfile = assignedPreset?.let { resolvePresetProfile(prefs, it) }
        val gains = assignedProfile?.first
            ?: prefs.getString("eq_gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 10 }?.toFloatArray() ?: FloatArray(10)
        val preamp = assignedProfile?.second ?: prefs.getFloat("preamp", 0f)
        val balance = if (prefs.getBoolean("balance_enabled", false)) prefs.getFloat("balance", 0f) else 0f
        VerlynAudioRuntime.get(this).setEngineMode(prefs.getString("audio_engine_mode", "native") ?: "native")
        VerlynAudioRuntime.get(this).attachSession(
            sessionId, normalized, gains, preamp,
            prefs.getBoolean("eq_enabled", true), prefs.getBoolean("limiter_enabled", true), balance
        )
        appendRaceLog("AUDIO SESSION bound · " + appLabel(normalized) + " · id=" + sessionId + " · source=" + source +
            (assignedPreset?.let { " · AUTO EQ=" + it } ?: ""))
        publishState()
    }

    private fun rebindActiveAudioSessions() {
        val active = activeAudioSessions.toMap()
        active.forEach { (sessionId, packageName) ->
            VerlynAudioRuntime.get(this).detachSession(sessionId)
            activeAudioSessions.remove(sessionId)
            bindAudioSession(sessionId, packageName, "Auto-EQ rule changed")
        }
    }

    private fun resolvePresetProfile(
        prefs: android.content.SharedPreferences,
        presetName: String
    ): Pair<FloatArray, Float> {
        val builtIns = setOf("Flat", "Studio", "Bass", "Club", "Extreme Bass", "Vocal", "Jazz", "Podcast", "Gaming")
        if (presetName in builtIns) return headsetPreset(presetName)
        prefs.getString("saved_preset_profiles", "").orEmpty().lineSequence().forEach { record ->
            val parts = record.split(";", limit = 3)
            if (parts.size == 3) {
                val name = runCatching {
                    String(android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrDefault("")
                if (name == presetName) {
                    val gains = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
                    val preamp = parts[2].toFloatOrNull() ?: 0f
                    if (gains.size == 10) return gains.toFloatArray() to preamp
                }
            }
        }
        appendRaceLog("AUTO EQ · profile '$presetName' not found in saved cache; using Flat")
        return headsetPreset("Flat")
    }

    private fun unbindAudioSession(sessionId: Int, source: String) {
        if (activeAudioSessions.remove(sessionId) == null) return
        VerlynAudioRuntime.get(this).detachSession(sessionId)
        appendRaceLog("AUDIO SESSION detached · id=" + sessionId + " · " + source)
        publishState()
    }

    private fun reapplyAudioSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        VerlynAudioRuntime.get(this).setEngineMode(prefs.getString("audio_engine_mode", "native") ?: "native")
        VerlynAudioRuntime.get(this).setMbcTuning(
            prefs.getFloat("mbc_bass_punch", 0f),
            prefs.getFloat("mbc_vocal_presence", 0f),
            prefs.getFloat("mbc_treble_air", 0f)
        )
        VerlynAudioRuntime.get(this).setBassBoostStrength(prefs.getInt("bass_boost_strength", 65))
        VerlynAudioRuntime.get(this).setSpatialStrength(prefs.getInt("spatial_strength", 65))
        val gains = prefs.getString("eq_gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }?.toFloatArray() ?: FloatArray(10)
        val balance = if (prefs.getBoolean("balance_enabled", false)) prefs.getFloat("balance", 0f) else 0f
        VerlynAudioRuntime.get(this).apply(
            gains, prefs.getFloat("preamp", 0f), prefs.getBoolean("eq_enabled", true),
            prefs.getBoolean("limiter_enabled", true), balance
        )
        VerlynAudioRuntime.get(this).setBassBoost(prefs.getBoolean("bass_boost_enabled", false))
        VerlynAudioRuntime.get(this).setSpatial(prefs.getBoolean("spatial_enabled", false))
        activeAudioSessions.toMap().forEach { (sessionId, packageName) ->
            val assignedPreset = prefs.getString("auto_eq_rule:" + packageName, null)
                ?.takeIf { it.isNotBlank() && it != "Off" }
            val assignedProfile = assignedPreset?.let { resolvePresetProfile(prefs, it) }
            VerlynAudioRuntime.get(this).attachSession(
                sessionId,
                packageName,
                assignedProfile?.first ?: gains,
                assignedProfile?.second ?: prefs.getFloat("preamp", 0f),
                prefs.getBoolean("eq_enabled", true),
                prefs.getBoolean("limiter_enabled", true),
                balance
            )
        }
        publishState()
    }

    private fun appLabel(packageName: String): String {
        if (packageName == "Audio session" || packageName == "Unknown media app") return packageName
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    @Suppress("DEPRECATION")
    private fun readDevice(intent: Intent): BluetoothDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = runCatching { device.name ?: "" }.getOrDefault("")
    @SuppressLint("MissingPermission")
    private fun safeAddress(device: BluetoothDevice): String = runCatching { device.address }.getOrDefault("")
    private fun isJbl(name: String): Boolean =
        name.equals("JBL Tune 770NC", true) || name.startsWith("JBL Tune 770NC-", true)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopped.set(true)
        main.removeCallbacksAndMessages(null)
        sleepTimerTask?.let { main.removeCallbacks(it) }
        pingStopTask?.let { main.removeCallbacks(it) }
        pingAudioTrack?.let { runCatching { it.stop(); it.release() } }
        pingAudioTrack = null
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(audioSessionReceiver) }
        runCatching { unregisterReceiver(auxiliaryReceiver) }
        if (headsetVolumeReceiverRegistered) runCatching { unregisterReceiver(headsetVolumeReceiver) }
        runCatching { voiceAnnouncer.shutdown() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
        runCatching { rfcomm.shutdown() }
        race.shutdown()
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        runCatching { a2dpProxy?.let { manager.adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        runCatching { headsetProxy?.let { manager.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        VerlynAudioRuntime.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STATE = "com.verlyn.audio.CONNECTION_STATE"
        const val CODEC_CONFIG_CHANGED_ACTION = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        const val ACTION_COMMAND_ASSIST = "com.verlyn.audio.COMMAND_ASSIST"
        const val ACTION_COMMAND_BRIEFING = "com.verlyn.audio.COMMAND_BRIEFING"
        const val ACTION_COMMAND_NEXT_EQ = "com.verlyn.audio.COMMAND_NEXT_EQ"
        const val ACTION_AUTO_EQ_RULES_CHANGED = "com.verlyn.audio.AUTO_EQ_RULES_CHANGED"
        const val ACTION_FIND_HEADSET_PING = "com.verlyn.audio.FIND_HEADSET_PING"
        const val ACTION_COMPANION_APPEARED = "com.verlyn.audio.COMPANION_APPEARED"
        const val ACTION_COMPANION_DISAPPEARED = "com.verlyn.audio.COMPANION_DISAPPEARED"
        const val ACTION_PACKET = "com.verlyn.audio.RFCOMM_PACKET"
        const val ACTION_RACE_LOG = "com.verlyn.audio.RACE_LOG"
        const val ACTION_FORCE_CONNECT = "com.verlyn.audio.FORCE_CONNECT"
        const val ACTION_STOP_LINK = "com.verlyn.audio.STOP_LINK"
        const val ACTION_SET_NOISE_MODE = "com.verlyn.audio.SET_NOISE_MODE"
        const val ACTION_POWER_OFF_HEADSET = "com.verlyn.audio.POWER_OFF_HEADSET"
        const val ACTION_AUX_CHANGED = "com.verlyn.audio.AUX_AUDIO_CHANGED"
        const val ACTION_AUDIO_SETTINGS_CHANGED = "com.verlyn.audio.AUDIO_SETTINGS_CHANGED"
        const val ACTION_AUDIO_SESSION_EVENT = "com.verlyn.audio.AUDIO_SESSION_EVENT"
        const val ACTION_MANUAL_RFCOMM_CONNECT = "com.verlyn.audio.MANUAL_RFCOMM_CONNECT"
        const val ACTION_MANUAL_RFCOMM_SEND = "com.verlyn.audio.MANUAL_RFCOMM_SEND"
        const val ACTION_MANUAL_RFCOMM_DISCONNECT = "com.verlyn.audio.MANUAL_RFCOMM_DISCONNECT"
        const val EXTRA_MANUAL_RFCOMM_UUID = "manual_rfcomm_uuid"
        const val EXTRA_MANUAL_RFCOMM_BYTES = "manual_rfcomm_bytes"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_BATTERY_SOURCE = "battery_source"
        const val EXTRA_LINK = "link_status"
        const val EXTRA_NOISE_MODE = "noise_mode"
        const val EXTRA_NOISE_STATUS = "noise_status"
        const val EXTRA_NOISE_READY = "noise_ready"
        const val EXTRA_NOISE_MODES = "noise_modes"
        const val EXTRA_FIRMWARE = "firmware"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_CODEC_STATUS = "codec_status"
        const val EXTRA_AUDIO_SESSIONS = "audio_sessions"
        const val EXTRA_PACKET_UUID = "packet_uuid"
        const val EXTRA_PACKET_LENGTH = "packet_length"
        const val EXTRA_PACKET_HEX = "packet_hex"
        const val EXTRA_RACE_LOG = "race_log"
        const val EXTRA_SESSION_OPEN = "session_open"
        const val CHANNEL_ID = "verlyn_connection"
        const val NOTIFICATION_ID = 770
        const val EXTRA_AUDIO_SESSION_PACKAGE = "audio_session_package"
        const val EXTRA_AUDIO_SESSION_ID = "audio_session_id"
        private const val PREFS = "verlyn_audio_preferences"
        private const val BATTERY_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private const val BATTERY_BROADCAST_FRESH_MS = 10 * 60 * 1000L
        private const val PREFERRED_JBL_ADDRESS = "88:92:CC:25:46:0E"
        private const val MAX_RACE_LOG_LINES = 400
        private val ALL_NOISE_MODES = listOf("ANC", "Ambient Aware", "TalkThru", "Off")

        fun requestNoiseMode(context: Context, mode: String) {
            val intent = Intent(context, VerlynConnectionService::class.java)
                .setAction(ACTION_SET_NOISE_MODE)
                .putExtra(EXTRA_NOISE_MODE, mode)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun requestPowerOff(context: Context) {
            val intent = Intent(context, VerlynConnectionService::class.java).setAction(ACTION_POWER_OFF_HEADSET)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
