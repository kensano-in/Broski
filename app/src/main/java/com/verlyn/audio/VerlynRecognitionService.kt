package com.verlyn.audio

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Valid recognition-service endpoint for Android's VoiceInteractionService metadata.
 * Headset commands are handled by the assistant session; this app does not pretend to
 * provide speech-to-text when no recognition backend is configured.
 */
class VerlynRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }
}
