package com.verlyn.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** TTS routed as media speech so Android can send it to the active Bluetooth A2DP output. */
class VoiceAnnouncer(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .setWillPauseWhenDucked(false)
            .build()
    } else null
    private val ready = AtomicBoolean(false)
    @Volatile private var pendingText: String? = null
    @Volatile private var engineName = "Unknown TTS engine"
    @Volatile private var statusText = "TTS initializing"
    private val tts = TextToSpeech(appContext, this)

    val status: String get() = statusText

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready.set(false)
            setStatus("TTS initialization failed · code " + status)
            return
        }
        val languageResult = runCatching { tts.setLanguage(Locale.US) }.getOrElse { TextToSpeech.ERROR }
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            ready.set(false)
            setStatus("TTS language unavailable · code " + languageResult)
            return
        }
        if (languageResult == TextToSpeech.ERROR) {
            ready.set(false)
            setStatus("TTS language setup failed")
            return
        }
        if (Build.VERSION.SDK_INT >= 21) tts.setAudioAttributes(attributes)
        engineName = runCatching {
            val packageName = tts.defaultEngine ?: return@runCatching "Android TTS"
            val appInfo = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault("Android TTS")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                setStatus("Speaking · " + engineName)
            }
            override fun onDone(utteranceId: String?) {
                setStatus("TTS Ready: " + engineName)
                abandonFocus()
            }
            @Deprecated("Deprecated by platform")
            override fun onError(utteranceId: String?) {
                setStatus("TTS playback failed")
                abandonFocus()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                setStatus("TTS playback failed · code " + errorCode)
                abandonFocus()
            }
        })
        ready.set(true)
        setStatus("TTS Ready: " + engineName)
        pendingText?.let {
            pendingText = null
            speak(it)
        }
    }

    @Synchronized
    fun speak(message: String): Boolean {
        if (!ready.get()) {
            if (statusText.contains("failed", true) || statusText.contains("unavailable", true)) return false
            pendingText = message
            setStatus("TTS initializing · request queued")
            return true
        }
        val focusResult = if (Build.VERSION.SDK_INT >= 26) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setStatus("TTS audio focus denied · code " + focusResult)
            return false
        }
        val result = tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "verlyn-" + System.nanoTime())
        if (result == TextToSpeech.ERROR) {
            setStatus("TTS speak request rejected")
            abandonFocus()
            return false
        }
        setStatus("TTS queued · " + engineName)
        return true
    }

    private fun setStatus(value: String) {
        statusText = value
        onStatus(value)
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Throwable) {}
        abandonFocus()
        ready.set(false)
        setStatus("TTS stopped")
    }
}
