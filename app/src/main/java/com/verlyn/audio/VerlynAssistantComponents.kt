package com.verlyn.audio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Transparent entry point used when Android forwards the headset assistant key. */
class VerlynAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        triggerCommandMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        triggerCommandMode()
    }

    private fun triggerCommandMode() {
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, VerlynConnectionService::class.java)
                    .setAction(VerlynConnectionService.ACTION_COMMAND_ASSIST)
            )
        }
        finish()
    }
}

class VerlynAssistantVoiceInteractionService : VoiceInteractionService()

class VerlynAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VerlynAssistantSession(this)
}

private class VerlynAssistantSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, VerlynConnectionService::class.java)
                    .setAction(VerlynConnectionService.ACTION_COMMAND_ASSIST)
            )
        }
        finish()
    }
}
