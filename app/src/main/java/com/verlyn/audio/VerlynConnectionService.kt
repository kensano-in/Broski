package com.verlyn.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
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
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VerlynConnectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rfcomm: ClassicRfcommEngine
    private lateinit var race: AirohaRaceClient
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
    private var retryCount = 0
    private var reconnectEnabled = true
    private val stopped = AtomicBoolean(false)
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null
    private val activeAudioSessions = LinkedHashMap<Int, String>()
    private val intentSessionIds = mutableSetOf<Int>()
    private val playbackSessionIds = mutableSetOf<Int>()
    private val recentSessionEvents = LinkedHashMap<String, Long>()
    private val raceLog = ArrayList<String>()
    private var lastJblRouteState: Boolean? = null

    private val routePoll = object : Runnable {
        override fun run() {
            val active = isJblAudioRouteActive()
            if (lastJblRouteState != active) {
                lastJblRouteState = active
                VerlynAudioRuntime.get(this@VerlynConnectionService).setRouteActive(active)
                if (active) target?.let { connectFastPair(it) }
            }
            main.postDelayed(this, 1_500L)
        }
    }

    private val retryTask = Runnable {
        val device = target
        if (reconnectEnabled && device != null && isJblAudioRouteActive()) connectFastPair(device)
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
                BATTERY_ACTION -> {
                    val level = data.getIntExtra(BATTERY_EXTRA, -1)
                    if (level in 0..100) {
                        battery = level
                        batterySource = "Android Bluetooth battery broadcast"
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("headset_battery_percent", level).apply()
                        publishState()
                    }
                }
            }
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
        VerlynAudioRuntime.get(this)
        race = AirohaRaceClient { line -> main.post { appendRaceLog(line) } }
        rfcomm = ClassicRfcommEngine(
            onStatus = { value -> main.post {
                linkStatus = value
                publishState()
                if (value.contains("failed", true) || value.contains("EOF", true) ||
                    value.contains("closed", true) || value.contains("Disconnected", true) ||
                    value.contains("read failed", true)) scheduleRetry()
                if (value.contains("Connected", true)) retryCount = 0
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
            } }
        )
        registerBluetoothReceiver()
        registerAudioReceivers()
        registerPlaybackCallback()
        main.post(routePoll)

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.let { adapter ->
            runCatching {
                val sticky = registerReceiver(null, IntentFilter(BATTERY_ACTION))
                val level = sticky?.getIntExtra(BATTERY_EXTRA, -1) ?: -1
                if (level in 0..100) {
                    battery = level
                    batterySource = "Android Bluetooth battery broadcast"
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
        startForegroundCompat()
        val data = intent
        when (data?.action) {
            ACTION_FORCE_CONNECT -> {
                reconnectEnabled = true
                ensureTarget()?.let { connectFastPair(it) }
            }
            ACTION_STOP_LINK -> {
                reconnectEnabled = false
                main.removeCallbacks(retryTask)
                rfcomm.disconnect()
                linkStatus = "Disconnected"
                publishState()
            }
            ACTION_SET_NOISE_MODE -> {
                val requested = data?.getStringExtra(EXTRA_NOISE_MODE) ?: return START_STICKY
                val device = ensureTarget()
                if (device == null) {
                    noiseDetail = "Pair JBL Tune 770NC first"
                    publishState()
                } else {
                    noiseDetail = "Sending " + requested + " command…"
                    publishState()
                    race.setNoiseMode(device, requested) { result -> main.post { applyRaceState(result) } }
                }
            }
            ACTION_POWER_OFF_HEADSET -> {
                val device = ensureTarget()
                if (device != null) race.powerOff(device) { result ->
                    main.post { noiseDetail = result; publishState(); appendRaceLog(result) }
                } else {
                    noiseDetail = "Paired JBL Tune 770NC not found"
                    publishState()
                }
            }
            ACTION_AUDIO_SESSION_EVENT -> data?.let { handleAudioSessionIntent(it) }
            ACTION_AUDIO_SETTINGS_CHANGED -> reapplyAudioSettings()
            else -> {
                reconnectEnabled = true
                ensureTarget()?.let { device ->
                    if (isJblAudioRouteActive()) {
                        connectFastPair(device)
                        race.probe(device) { state -> main.post { applyRaceState(state) } }
                    }
                }
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
        reconnectEnabled = true
        noiseModes = ALL_NOISE_MODES
        val routeActive = isJblAudioRouteActive()
        lastJblRouteState = routeActive
        VerlynAudioRuntime.get(this).setRouteActive(routeActive)
        readNativeBattery(connectedDevice)
        publishState()
        // Fast Pair and Airoha use independent RFCOMM clients/sockets and independent IO jobs.
        connectFastPair(connectedDevice)
        race.probe(connectedDevice) { state -> main.post { applyRaceState(state) } }
    }

    private fun handleDisconnected() {
        reconnectEnabled = false
        main.removeCallbacks(retryTask)
        rfcomm.disconnect()
        noiseReady = false
        noiseMode = "Unknown"
        noiseModes = emptyList()
        noiseDetail = "Headset disconnected"
        lastJblRouteState = false
        VerlynAudioRuntime.get(this).setRouteActive(false)
        linkStatus = "Headset disconnected"
        publishState()
    }

    @SuppressLint("MissingPermission")
    private fun connectFastPair(device: BluetoothDevice) {
        if (stopped.get()) return
        if (rfcomm.connectedUuid() == ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID) return
        main.removeCallbacks(retryTask)
        rfcomm.connect(device, ClassicRfcommEngine.FAST_PAIR_MESSAGE_STREAM_UUID, "Fast Pair battery stream")
    }

    private fun scheduleRetry() {
        if (!reconnectEnabled || target == null || !isJblAudioRouteActive()) return
        main.removeCallbacks(retryTask)
        val delayMs = (1_000L shl retryCount.coerceIn(0, 5)).coerceAtMost(30_000L)
        retryCount = (retryCount + 1).coerceAtMost(5)
        main.postDelayed(retryTask, delayMs)
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
            .putStringArrayListExtra(EXTRA_NOISE_MODES, ArrayList(noiseModes))
            .putStringArrayListExtra(EXTRA_AUDIO_SESSIONS, sessions))
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification()) }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BATTERY_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
    }

    private fun registerAudioReceivers() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(audioSessionReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(audioSessionReceiver, filter)
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
            unbindAudioSession(sessionId, "AudioEffect close")
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
        val gains = prefs.getString("eq_gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }?.toFloatArray() ?: FloatArray(10)
        val balance = if (prefs.getBoolean("balance_enabled", false)) prefs.getFloat("balance", 0f) else 0f
        VerlynAudioRuntime.get(this).attachSession(
            sessionId, normalized, gains, prefs.getFloat("preamp", 0f),
            prefs.getBoolean("eq_enabled", true), prefs.getBoolean("limiter_enabled", true), balance
        )
        appendRaceLog("AUDIO SESSION bound · " + appLabel(normalized) + " · id=" + sessionId + " · source=" + source)
        publishState()
    }

    private fun unbindAudioSession(sessionId: Int, source: String) {
        if (activeAudioSessions.remove(sessionId) == null) return
        VerlynAudioRuntime.get(this).detachSession(sessionId)
        appendRaceLog("AUDIO SESSION detached · id=" + sessionId + " · " + source)
        publishState()
    }

    private fun reapplyAudioSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val gains = prefs.getString("eq_gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }?.toFloatArray() ?: FloatArray(10)
        val balance = if (prefs.getBoolean("balance_enabled", false)) prefs.getFloat("balance", 0f) else 0f
        VerlynAudioRuntime.get(this).apply(
            gains, prefs.getFloat("preamp", 0f), prefs.getBoolean("eq_enabled", true),
            prefs.getBoolean("limiter_enabled", true), balance
        )
        VerlynAudioRuntime.get(this).setBassBoost(prefs.getBoolean("bass_boost_enabled", false))
        VerlynAudioRuntime.get(this).setSpatial(prefs.getBoolean("spatial_enabled", false))
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
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(audioSessionReceiver) }
        runCatching { unregisterReceiver(auxiliaryReceiver) }
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
        const val ACTION_PACKET = "com.verlyn.audio.RFCOMM_PACKET"
        const val ACTION_RACE_LOG = "com.verlyn.audio.RACE_LOG"
        const val ACTION_FORCE_CONNECT = "com.verlyn.audio.FORCE_CONNECT"
        const val ACTION_STOP_LINK = "com.verlyn.audio.STOP_LINK"
        const val ACTION_SET_NOISE_MODE = "com.verlyn.audio.SET_NOISE_MODE"
        const val ACTION_POWER_OFF_HEADSET = "com.verlyn.audio.POWER_OFF_HEADSET"
        const val ACTION_AUX_CHANGED = "com.verlyn.audio.AUX_AUDIO_CHANGED"
        const val ACTION_AUDIO_SETTINGS_CHANGED = "com.verlyn.audio.AUDIO_SETTINGS_CHANGED"
        const val ACTION_AUDIO_SESSION_EVENT = "com.verlyn.audio.AUDIO_SESSION_EVENT"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_BATTERY_SOURCE = "battery_source"
        const val EXTRA_LINK = "link_status"
        const val EXTRA_NOISE_MODE = "noise_mode"
        const val EXTRA_NOISE_STATUS = "noise_status"
        const val EXTRA_NOISE_READY = "noise_ready"
        const val EXTRA_NOISE_MODES = "noise_modes"
        const val EXTRA_FIRMWARE = "firmware"
        const val EXTRA_MODEL_ID = "model_id"
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
