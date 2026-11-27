package com.verlyn.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.core.content.ContextCompat

/** Static bridge for media applications that announce audio-effect session lifecycle events. */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val open = when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> true
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> false
            else -> return
        }
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (sessionId <= 0) return
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Unknown media app"
        val serviceIntent = Intent(context, VerlynConnectionService::class.java)
            .setAction(VerlynConnectionService.ACTION_AUDIO_SESSION_EVENT)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(VerlynConnectionService.EXTRA_SESSION_OPEN, open)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, serviceIntent)
            else context.startService(serviceIntent)
        }
    }
}
