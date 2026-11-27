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
import android.media.audiofx.AudioEffect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

class VerlynConnectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rfcomm: ClassicRfcommEngine
    private val race = AirohaRaceClient()
    private var target: BluetoothDevice? = null
    private var battery: Int? = null
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
                    if (data.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED &&
                        device != null && isJbl(safeName(device))) handleConnected(device)
                    if (data.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED &&
                        device != null && target?.address == device.address) handleDisconnected()
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
                    if (level in 0..100) { battery = level; publishState() }
                }
            }
        }
    }

    private val audioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent ?: return
            val id = data.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (id <= 0) return
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean("session_compatibility", true)) return
            val gains = prefs.getString("eq_gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 10 }?.toFloatArray() ?: FloatArray(10)
            val balance = if (prefs.getBoolean("balance_enabled", false)) prefs.getFloat("balance", 0f) else 0f
            if (data.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                VerlynAudioRuntime.get(this@VerlynConnectionService).attachSession(
                    id, gains, prefs.getFloat("preamp", 0f), prefs.getBoolean("eq_enabled", true),
                    prefs.getBoolean("limiter_enabled", true), balance
                )
            } else if (data.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
                VerlynAudioRuntime.get(this@VerlynConnectionService).detachSession(id)
            }
        }
    }

    private val auxiliaryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val engine = VerlynAudioRuntime.get(this@VerlynConnectionService)
            engine.setBassBoost(prefs.getBoolean("bass_boost_enabled", false))
            engine.setSpatial(prefs.getBoolean("spatial_enabled", false))
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
        VerlynAudioRuntime.get(this)
        rfcomm = ClassicRfcommEngine(
            onStatus = { value -> main.post {
                linkStatus = value
                publishState()
                if (value.contains("failed", true) || value.contains("EOF", true) || value.contains("closed", true) ||
                    value.contains("Disconnected", true) || value.contains("read failed", true)) scheduleRetry()
                if (value.contains("Connected", true)) retryCount = 0
            } },
            onPacket = { uuid, bytes -> publishPacket(uuid.toString(), bytes) },
            onBattery = { levels ->
                val level = levels.firstOrNull { it in 0..100 }
                if (level != null) main.post { battery = level; publishState() }
            }
        )
        registerBluetoothReceiver()
        registerAudioReceivers()
        main.post(routePoll)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.let { adapter ->
            runCatching {
                val sticky = registerReceiver(null, IntentFilter(BATTERY_ACTION))
                val level = sticky?.getIntExtra(BATTERY_EXTRA, -1) ?: -1
                if (level in 0..100) battery = level
            }
            runCatching { adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP) }
            runCatching { adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET) }
            runCatching { adapter.bondedDevices.firstOrNull { isJbl(safeName(it)) }?.let { target = it; readNativeBattery(it) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_FORCE_CONNECT -> {
                reconnectEnabled = true
                target?.let { connectFastPair(it) }
            }
            ACTION_STOP_LINK -> {
                reconnectEnabled = false
                main.removeCallbacks(retryTask)
                rfcomm.disconnect()
                linkStatus = "Disconnected"
                publishState()
            }
            ACTION_SET_NOISE_MODE -> {
                val requested = intent.getStringExtra(EXTRA_NOISE_MODE) ?: return START_STICKY
                val device = target
                if (device != null && requested in noiseModes) {
                    race.setNoiseMode(device, requested) { result -> main.post { applyRaceState(result) } }
                } else {
                    noiseDetail = "Mode not supported by the headset chipset"
                    publishState()
                }
            }
            else -> {
                reconnectEnabled = true
                target?.takeIf { isJblAudioRouteActive() }?.let { connectFastPair(it); race.probe(it) { state -> main.post { applyRaceState(state) } } }
            }
        }
        publishState()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun handleConnected(device: BluetoothDevice) {
        target = device
        reconnectEnabled = true
        val routeActive = isJblAudioRouteActive()
        lastJblRouteState = routeActive
        VerlynAudioRuntime.get(this).setRouteActive(routeActive)
        readNativeBattery(device)
        publishState()
        connectFastPair(device)
        race.probe(device) { state -> main.post { applyRaceState(state) } }
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
        val delay = (1_000L shl retryCount.coerceIn(0, 5)).coerceAtMost(30_000L)
        retryCount = (retryCount + 1).coerceAtMost(5)
        main.postDelayed(retryTask, delay)
    }

    private fun applyRaceState(state: AirohaRaceState) {
        noiseReady = state.ready
        noiseMode = state.mode
        noiseModes = state.supportedModes
        noiseDetail = state.detail
        publishState()
    }

    @SuppressLint("MissingPermission")
    private fun readNativeBattery(device: BluetoothDevice) {
        val value = runCatching { device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int }.getOrNull()
        if (value != null && value in 0..100) { battery = value; publishState() }
    }

    private fun isJblAudioRouteActive(): Boolean {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        return runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { output ->
                (output.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || output.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                    output.productName.toString().contains("JBL Tune 770NC", true)
            }
        }.getOrDefault(false)
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
            .setContentText((battery?.let { "Battery " + it + "% · " } ?: "Battery unavailable · ") + "Active EQ")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun startForegroundCompat() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, n)
    }

    private fun publishPacket(uuid: String, bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        sendBroadcast(Intent(ACTION_PACKET).setPackage(packageName)
            .putExtra(EXTRA_PACKET_UUID, uuid)
            .putExtra(EXTRA_PACKET_LENGTH, bytes.size)
            .putExtra(EXTRA_PACKET_HEX, hex))
    }

    private fun publishState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_BATTERY, battery ?: -1)
            .putExtra(EXTRA_LINK, linkStatus)
            .putExtra(EXTRA_NOISE_MODE, noiseMode)
            .putExtra(EXTRA_NOISE_STATUS, noiseDetail)
            .putExtra(EXTRA_NOISE_READY, noiseReady)
            .putStringArrayListExtra(EXTRA_NOISE_MODES, ArrayList(noiseModes)))
        if (Build.VERSION.SDK_INT >= 26) runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification()) }
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
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(bluetoothReceiver, filter)
    }

    private fun registerAudioReceivers() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(audioSessionReceiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(audioSessionReceiver, filter)
        val aux = IntentFilter(ACTION_AUX_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(auxiliaryReceiver, aux, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(auxiliaryReceiver, aux)
    }

    @Suppress("DEPRECATION")
    private fun readDevice(intent: Intent): BluetoothDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = runCatching { device.name ?: "" }.getOrDefault("")

    private fun isJbl(name: String): Boolean =
        name.equals("JBL Tune 770NC", true) || name.startsWith("JBL Tune 770NC-", true)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopped.set(true)
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(audioSessionReceiver) }
        runCatching { unregisterReceiver(auxiliaryReceiver) }
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
        const val ACTION_FORCE_CONNECT = "com.verlyn.audio.FORCE_CONNECT"
        const val ACTION_STOP_LINK = "com.verlyn.audio.STOP_LINK"
        const val ACTION_SET_NOISE_MODE = "com.verlyn.audio.SET_NOISE_MODE"
        const val ACTION_AUX_CHANGED = "com.verlyn.audio.AUX_AUDIO_CHANGED"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_LINK = "link_status"
        const val EXTRA_NOISE_MODE = "noise_mode"
        const val EXTRA_NOISE_STATUS = "noise_status"
        const val EXTRA_PACKET_UUID = "packet_uuid"
        const val EXTRA_PACKET_LENGTH = "packet_length"
        const val EXTRA_PACKET_HEX = "packet_hex"
        const val EXTRA_NOISE_READY = "noise_ready"
        const val EXTRA_NOISE_MODES = "noise_modes"
        const val CHANNEL_ID = "verlyn_connection"
        const val NOTIFICATION_ID = 770
        private const val PREFS = "verlyn_audio_preferences"
        private const val BATTERY_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"

        fun requestNoiseMode(context: Context, mode: String) {
            context.startService(Intent(context, VerlynConnectionService::class.java).setAction(ACTION_SET_NOISE_MODE).putExtra(EXTRA_NOISE_MODE, mode))
        }
    }
}
