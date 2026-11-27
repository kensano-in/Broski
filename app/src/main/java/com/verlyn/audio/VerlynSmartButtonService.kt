package com.verlyn.audio

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Optional volume-key gesture recognizer. Short taps are replayed after the double-tap window. */
class VerlynSmartButtonService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var voice: VoiceAnnouncer
    private val downAt = mutableMapOf<Int, Long>()
    private val longFired = mutableSetOf<Int>()
    private val pendingShort = mutableMapOf<Int, Runnable>()
    private val lastTap = mutableMapOf<Int, Long>()
    private val longTasks = mutableMapOf<Int, Runnable>()
    private val longThresholdMs = 650L
    private val doubleWindowMs = 320L

    override fun onServiceConnected() {
        super.onServiceConnected()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        voice = VoiceAnnouncer(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return key in downAt
            val now = System.currentTimeMillis()
            if (now - (lastTap[key] ?: 0L) <= doubleWindowMs) pendingShort.remove(key)?.let { handler.removeCallbacks(it) }
            downAt[key] = now
            longFired.remove(key)
            val task = Runnable {
                if (key in downAt) {
                    longFired.add(key)
                    pendingShort.remove(key)?.let { handler.removeCallbacks(it) }
                    runAction(slotFor(key, "long"))
                }
            }
            longTasks[key] = task
            handler.postDelayed(task, longThresholdMs)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            downAt.remove(key)
            longTasks.remove(key)?.let { handler.removeCallbacks(it) }
            if (longFired.remove(key)) { lastTap.remove(key); return true }
            val now = System.currentTimeMillis()
            val prior = lastTap[key] ?: 0L
            if (now - prior <= doubleWindowMs) {
                lastTap.remove(key)
                pendingShort.remove(key)?.let { handler.removeCallbacks(it) }
                runAction(slotFor(key, "double"))
            } else {
                lastTap[key] = now
                val task = Runnable {
                    pendingShort.remove(key)
                    lastTap.remove(key)
                    replayVolumeStep(key)
                }
                pendingShort[key] = task
                handler.postDelayed(task, doubleWindowMs)
            }
            return true
        }
        return true
    }

    private fun replayVolumeStep(key: Int) {
        val direction = if (key == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    private fun slotFor(key: Int, gesture: String): String =
        "trigger_" + (if (key == KeyEvent.KEYCODE_VOLUME_UP) "volume_up" else "volume_down") + "_" + gesture

    private fun runAction(slot: String) {
        val prefs = getSharedPreferences("verlyn_audio_preferences", MODE_PRIVATE)
        when (prefs.getString(slot, defaultAction(slot)) ?: "disabled") {
            "speak_time" -> voice.speak("It is " + SimpleDateFormat("h:mm a", Locale.US).format(Date()))
            "speak_battery" -> {
                val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
                val phone = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
                val headset = prefs.getInt("headset_battery_percent", -1)
                voice.speak(if (headset >= 0) "Phone battery is " + phone + " percent. Headset battery is " + headset + " percent."
                    else "Phone battery is " + phone + " percent. Headset battery is unavailable.")
            }
            "cycle_eq" -> {
                val custom = (prefs.getString("saved_preset_names", "") ?: "").split("\n").filter { it.isNotBlank() }
                val names = (listOf("Flat", "Studio", "Bass", "Club", "Extreme Bass", "Vocal", "Jazz") + custom).distinct()
                val current = prefs.getString("preset", "Flat") ?: "Flat"
                val next = names[(names.indexOf(current).coerceAtLeast(-1) + 1) % names.size]
                prefs.edit().putString("preset", next).apply()
                sendBroadcast(Intent(ACTION_SMART_ACTION).setPackage(packageName)
                    .putExtra(EXTRA_ACTION, "apply_preset").putExtra(EXTRA_VALUE, next))
                voice.speak(next + " EQ active")
            }
            "toggle_limiter" -> {
                val enabled = !prefs.getBoolean("limiter_enabled", true)
                prefs.edit().putBoolean("limiter_enabled", enabled).apply()
                sendBroadcast(Intent(ACTION_SMART_ACTION).setPackage(packageName)
                    .putExtra(EXTRA_ACTION, "limiter_changed").putExtra(EXTRA_ENABLED, enabled))
                voice.speak(if (enabled) "Max volume limiter on" else "Max volume limiter off")
            }
            "toggle_bass_boost" -> {
                val enabled = !prefs.getBoolean("bass_boost_enabled", false)
                prefs.edit().putBoolean("bass_boost_enabled", enabled).apply()
                sendBroadcast(Intent(ACTION_SMART_ACTION).setPackage(packageName)
                    .putExtra(EXTRA_ACTION, "bass_boost_changed").putExtra(EXTRA_ENABLED, enabled))
                voice.speak(if (enabled) "Bass boost on" else "Bass boost off")
            }
            "media_next" -> dispatchMedia(KeyEvent.KEYCODE_MEDIA_NEXT)
            "media_previous" -> dispatchMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "media_play_pause" -> dispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "disabled" -> Unit
        }
    }

    private fun dispatchMedia(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    private fun defaultAction(slot: String): String = when (slot) {
        "trigger_volume_down_long" -> "speak_time"
        "trigger_volume_up_long" -> "speak_battery"
        "trigger_volume_down_double" -> "cycle_eq"
        "trigger_volume_up_double" -> "toggle_limiter"
        else -> "disabled"
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::voice.isInitialized) voice.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SMART_ACTION = "com.verlyn.audio.SMART_ACTION"
        const val EXTRA_ACTION = "action"
        const val EXTRA_VALUE = "value"
        const val EXTRA_ENABLED = "enabled"
    }
}
