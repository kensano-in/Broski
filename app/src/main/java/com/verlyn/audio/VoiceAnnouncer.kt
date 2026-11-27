package com.verlyn.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Serialized media-routed speech with exclusive focus and an explicit pause/resume fallback. */
class VoiceAnnouncer(
    context: Context,
    private val onUtteranceFinished: () -> Unit = {},
    private val onStatus: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(focusAttributes)
            .setOnAudioFocusChangeListener { }
            .setAcceptsDelayedFocusGain(false)
            .build()
    } else null
    private val ready = AtomicBoolean(false)
    @Volatile private var pendingText: String? = null
    @Volatile private var engineName = "Unknown TTS engine"
    @Volatile private var statusText = "TTS initializing"
    private var focusHeld = false
    private var pausedViaMediaKey = false
    private var activeUtteranceId: String? = null
    private var finishingUtteranceId: String? = null
    private var sequence = 0L
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
        tts.setAudioAttributes(mediaAttributes)
        engineName = runCatching {
            val packageName = tts.defaultEngine ?: return@runCatching "Android TTS"
            val appInfo = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault("Android TTS")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                setStatus("TTS onStart · " + (utteranceId ?: "unknown") + " · " + engineName)
            }

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId, "TTS onDone")
            }

            @Deprecated("Deprecated by platform")
            override fun onError(utteranceId: String?) {
                finishUtterance(utteranceId, "TTS onError")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishUtterance(utteranceId, "TTS onError(code=$errorCode)")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finishUtterance(utteranceId, if (interrupted) "TTS stopped · interrupted" else "TTS stopped")
            }
        })
        ready.set(true)
        setStatus("TTS Ready: " + engineName)
        val queued = pendingText
        pendingText = null
        if (queued != null) speak(queued)
    }

    @Synchronized
    fun speak(message: String): Boolean {
        if (!ready.get()) {
            pendingText = message
            setStatus("TTS initializing · request queued")
            return true
        }
        handler.post {
            if (!ready.get()) {
                pendingText = message
                return@post
            }
            if (focusHeld) {
                pendingText = message
                activeUtteranceId?.let { current ->
                    runCatching { tts.stop() }
                    if (finishingUtteranceId != current) finishUtterance(current, "TTS replaced by newer request")
                }
                return@post
            }
            beginWithExclusiveFocus(message)
        }
        return true
    }

    private fun beginWithExclusiveFocus(message: String) {
        val focusResult = if (Build.VERSION.SDK_INT >= 26) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setStatus("TTS exclusive audio focus denied · code " + focusResult)
            return
        }
        focusHeld = true
        pausedViaMediaKey = false
        val id = "verlyn-" + (++sequence)
        activeUtteranceId = id
        finishingUtteranceId = null
        setStatus("Exclusive audio focus acquired · waiting 200ms for Spotify pause")
        handler.postDelayed({
            if (activeUtteranceId != id || !focusHeld) return@postDelayed
            if (audioManager.isMusicActive) {
                pausedViaMediaKey = true
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                setStatus("Spotify still active · MEDIA_PAUSE fallback sent")
            } else {
                setStatus("Music paused by exclusive audio focus")
            }
            handler.postDelayed({
                if (activeUtteranceId != id || !focusHeld) return@postDelayed
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                }
                val result = tts.speak(message, TextToSpeech.QUEUE_FLUSH, params, id)
                if (result == TextToSpeech.ERROR) {
                    finishUtterance(id, "TTS onError(code=${TextToSpeech.ERROR})")
                } else {
                    setStatus("TTS speaking · " + engineName)
                }
            }, 150L)
        }, 200L)
    }

    private fun finishUtterance(utteranceId: String?, result: String) {
        if (utteranceId == null) return
        handler.post {
            if (activeUtteranceId != utteranceId || finishingUtteranceId == utteranceId) return@post
            finishingUtteranceId = utteranceId
            setStatus(result + " · resume timer 250ms")
            handler.postDelayed({
                if (activeUtteranceId != utteranceId) return@postDelayed
                activeUtteranceId = null
                finishingUtteranceId = null
                abandonExclusiveFocus()
                if (pausedViaMediaKey) {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    setStatus(result + " · MEDIA_PLAY fallback sent")
                } else {
                    setStatus("TTS Ready: " + engineName + " · audio focus abandoned")
                }
                pausedViaMediaKey = false
                val queued = pendingText
                pendingText = null
                if (queued != null) beginWithExclusiveFocus(queued)
                else onUtteranceFinished()
            }, 250L)
        }
    }

    private fun dispatchMediaKey(code: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun abandonExclusiveFocus() {
        if (!focusHeld) return
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusHeld = false
    }

    private fun setStatus(value: String) {
        statusText = value
        onStatus(value)
    }

    fun shutdown() {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            runCatching { tts.stop(); tts.shutdown() }
            abandonExclusiveFocus()
            if (pausedViaMediaKey) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            pausedViaMediaKey = false
            activeUtteranceId = null
            finishingUtteranceId = null
            ready.set(false)
            setStatus("TTS stopped")
        }
    }
}
