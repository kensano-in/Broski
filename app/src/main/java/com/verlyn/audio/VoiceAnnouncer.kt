package com.verlyn.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Media-routed TTS with an explicit exclusive-focus session for headset Command Mode. */
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
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(focusAttributes)
        .setOnAudioFocusChangeListener({}, handler)
        .setAcceptsDelayedFocusGain(false)
        .build()
    private val ready = AtomicBoolean(false)
    @Volatile private var statusText = "TTS initializing"
    private var pendingText: String? = null
    private var pendingKeepFocus = false
    private var pendingSpeechText: String? = null
    private var engineName = "Unknown TTS engine"
    private var focusHeld = false
    private var pausedViaMediaKey = false
    private var retainFocusAfterUtterance = false
    private var activeUtteranceId: String? = null
    private var finishingUtteranceId: String? = null
    private var focusStartTask: Runnable? = null
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
        val keepFocus = pendingKeepFocus
        pendingText = null
        pendingKeepFocus = false
        if (queued != null) speak(queued, keepFocusAfter = keepFocus)
    }

    fun beginCommandSession() {
        handler.post {
            retainFocusAfterUtterance = true
            setStatus("Command Mode · exclusive focus session armed")
        }
    }

    fun endCommandSession() {
        handler.post {
            retainFocusAfterUtterance = false
            if (activeUtteranceId == null) releaseExclusiveFocus()
        }
    }

    @Synchronized
    fun speak(message: String, keepFocusAfter: Boolean = false): Boolean {
        handler.post {
            retainFocusAfterUtterance = keepFocusAfter
            if (!ready.get()) {
                pendingText = message
                pendingKeepFocus = keepFocusAfter
                setStatus("TTS initializing · request queued")
                return@post
            }
            if (activeUtteranceId != null) {
                pendingText = message
                pendingKeepFocus = keepFocusAfter
                val current = activeUtteranceId
                runCatching { tts.stop() }
                if (current != null && finishingUtteranceId != current) {
                    finishUtterance(current, "TTS replaced by newer request")
                }
                return@post
            }
            if (focusHeld) beginWithHeldFocus(message) else beginWithExclusiveFocus(message)
        }
        return true
    }

    private fun beginWithExclusiveFocus(message: String) {
        val focusResult = audioManager.requestAudioFocus(focusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setStatus("TTS exclusive audio focus denied · code " + focusResult)
            onUtteranceFinished()
            return
        }
        focusHeld = true
        pausedViaMediaKey = false
        beginUtterance(message)
        prepareSpeechAfterMusicPause()
    }

    private fun beginWithHeldFocus(message: String) {
        beginUtterance(message)
        if (focusStartTask != null) prepareSpeechAfterMusicPause()
        else startSpeech(activeUtteranceId!!, message)
    }

    private fun beginUtterance(message: String) {
        activeUtteranceId = "verlyn-" + (++sequence)
        finishingUtteranceId = null
        pendingSpeechText = message
        setStatus("Exclusive focus held · preparing speech")
    }

    private fun prepareSpeechAfterMusicPause() {
        focusStartTask?.let { handler.removeCallbacks(it) }
        val id = activeUtteranceId ?: return
        val message = pendingSpeechText ?: return
        val task = Runnable {
            focusStartTask = null
            if (activeUtteranceId != id || !focusHeld) return@Runnable
            if (audioManager.isMusicActive && !pausedViaMediaKey) {
                pausedViaMediaKey = true
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                setStatus("Music still active after 200ms · MEDIA_PAUSE fallback sent")
            } else {
                setStatus("Music paused for Verlyn speech")
            }
            handler.postDelayed({
                if (activeUtteranceId == id && focusHeld) startSpeech(id, message)
            }, 150L)
        }
        focusStartTask = task
        handler.postDelayed(task, 200L)
    }

    private fun startSpeech(id: String, message: String) {
        if (activeUtteranceId != id || !focusHeld) return
        pendingSpeechText = null
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = tts.speak(message, TextToSpeech.QUEUE_FLUSH, params, id)
        if (result == TextToSpeech.ERROR) {
            finishUtterance(id, "TTS onError(code=" + TextToSpeech.ERROR + ")")
        } else {
            setStatus("TTS speaking · " + engineName)
        }
    }

    private fun finishUtterance(utteranceId: String?, result: String) {
        if (utteranceId == null) return
        handler.post {
            if (activeUtteranceId != utteranceId || finishingUtteranceId == utteranceId) return@post
            finishingUtteranceId = utteranceId
            setStatus(result + " · finishing")
            handler.postDelayed({
                if (activeUtteranceId != utteranceId) return@postDelayed
                activeUtteranceId = null
                finishingUtteranceId = null
                val queued = pendingText
                val keepFocus = pendingKeepFocus
                pendingText = null
                pendingKeepFocus = false
                if (queued != null) {
                    retainFocusAfterUtterance = keepFocus
                    if (focusHeld) beginWithHeldFocus(queued) else beginWithExclusiveFocus(queued)
                    return@postDelayed
                }
                if (retainFocusAfterUtterance) {
                    setStatus("TTS complete · Command Mode retains exclusive focus")
                } else {
                    releaseExclusiveFocus()
                    setStatus("TTS Ready: " + engineName + " · audio focus released")
                }
                onUtteranceFinished()
            }, 250L)
        }
    }

    private fun releaseExclusiveFocus() {
        focusStartTask?.let { handler.removeCallbacks(it) }
        focusStartTask = null
        if (focusHeld) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            focusHeld = false
        }
        if (pausedViaMediaKey) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            setStatus("MEDIA_PLAY fallback sent · playback resumed")
        }
        pausedViaMediaKey = false
    }

    private fun dispatchMediaKey(code: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun setStatus(value: String) {
        statusText = value
        onStatus(value)
    }

    fun shutdown() {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            runCatching { tts.stop(); tts.shutdown() }
            activeUtteranceId = null
            finishingUtteranceId = null
            pendingText = null
            pendingSpeechText = null
            retainFocusAfterUtterance = false
            releaseExclusiveFocus()
            ready.set(false)
            setStatus("TTS stopped")
        }
    }
}
