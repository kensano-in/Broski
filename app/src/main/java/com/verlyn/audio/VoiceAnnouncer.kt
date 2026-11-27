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

/** TTS announcements use Android's current route and transient may-duck audio focus. */
class VoiceAnnouncer(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { }
            .setWillPauseWhenDucked(false).build()
    } else null
    private val ready = AtomicBoolean(false)
    @Volatile private var pendingText: String? = null
    private val tts = TextToSpeech(appContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.US
        if (Build.VERSION.SDK_INT >= 21) tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = abandonFocus()
            @Deprecated("Deprecated by platform")
            override fun onError(utteranceId: String?) = abandonFocus()
            override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
        })
        ready.set(true)
        pendingText?.let { pendingText = null; speak(it) }
    }

    @Synchronized
    fun speak(message: String) {
        if (!ready.get()) { pendingText = message; return }
        val result = if (Build.VERSION.SDK_INT >= 26) audioManager.requestAudioFocus(focusRequest!!) else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "verlyn-" + System.nanoTime())
        }
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
    }
}
