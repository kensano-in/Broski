package com.verlyn.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import androidx.core.content.ContextCompat

/** Transparent ACTION_ASSIST / ACTION_VOICE_COMMAND fallback. */
class VerlynAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommandModeController.onAssistTriggered(this)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CommandModeController.onAssistTriggered(this)
        finish()
    }
}

/** Android's selectable default digital-assistant service. */
class VerlynVoiceInteractionService : VoiceInteractionService()

class VerlynVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VerlynVoiceInteractionSession(this)
}

private class VerlynVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        CommandModeController.onAssistTriggered(context)
        // A voice interaction session is transient here; never leave a blank overlay on screen.
        hide()
    }
}

/** Single entry point shared by the assistant session and legacy activity fallback. */
object CommandModeController {
    fun onAssistTriggered(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, VerlynConnectionService::class.java)
                    .setAction(VerlynConnectionService.ACTION_COMMAND_ASSIST)
            )
        }
    }
}
