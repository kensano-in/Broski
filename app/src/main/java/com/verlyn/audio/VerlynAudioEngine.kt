package com.verlyn.audio

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.os.Build

/** Native Android DynamicsProcessing wrapper for global and media-session audio effects. */
class VerlynAudioEngine(
    context: Context,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val effects = linkedMapOf<Int, DynamicsProcessing>()

    companion object {
        val BAND_FREQUENCIES = floatArrayOf(
            32f, 64f, 125f, 250f, 500f,
            1000f, 2000f, 4000f, 8000f, 16000f
        )
    }

    fun attachGlobal(gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        attach(0, gains, preamp, eqEnabled, limiterEnabled, balance)
    }

    fun attachSession(sessionId: Int, gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        if (sessionId <= 0) return
        attach(sessionId, gains, preamp, eqEnabled, limiterEnabled, balance)
    }

    private fun attach(sessionId: Int, gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onStatus("Bypassed — DynamicsProcessing requires Android 9 or newer")
            return
        }
        try {
            effects.remove(sessionId)?.release()
            val eq = DynamicsProcessing.Eq(true, true, BAND_FREQUENCIES.size)
            BAND_FREQUENCIES.indices.forEach { index ->
                eq.setBand(index, DynamicsProcessing.EqBand(true, BAND_FREQUENCIES[index], gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f)))
            }
            eq.setEnabled(eqEnabled)
            val limiter = DynamicsProcessing.Limiter(true, limiterEnabled, 0, 10f, 100f, 20f, -1f, 0f)
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                false, 0,
                false, 0,
                true, BAND_FREQUENCIES.size,
                true
            )
                .setInputGainAllChannelsTo(preamp.coerceIn(-12f, 12f))
                .setPostEqAllChannelsTo(eq)
                .setLimiterAllChannelsTo(limiter)
                .build()

            val effect = DynamicsProcessing(0, sessionId, config)
            applyChannelBalance(effect, preamp, balance)
            effect.enabled = true
            effects[sessionId] = effect
            onStatus(statusForEffects())
        } catch (error: Throwable) {
            onStatus("Bypassed — ${error.javaClass.simpleName}: ${error.message ?: "effect unavailable"}")
        }
    }

    fun apply(gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onStatus("Bypassed — DynamicsProcessing requires Android 9 or newer")
            return
        }
        effects.toMap().forEach { (sessionId, effect) ->
            try {
                effect.setInputGainAllChannelsTo(preamp.coerceIn(-12f, 12f))
                applyChannelBalance(effect, preamp, balance)
                val eq = effect.getPostEqByChannelIndex(0)
                eq.setEnabled(eqEnabled)
                BAND_FREQUENCIES.indices.forEach { index ->
                    eq.setBand(index, DynamicsProcessing.EqBand(true, BAND_FREQUENCIES[index], gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f)))
                }
                effect.setPostEqAllChannelsTo(eq)
                effect.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, limiterEnabled, 0, 10f, 100f, 20f, -1f, 0f))
                effect.enabled = true
            } catch (error: Throwable) {
                onStatus("Bypassed — session $sessionId: ${error.message ?: "update failed"}")
            }
        }
        onStatus(statusForEffects())
    }

    private fun applyChannelBalance(effect: DynamicsProcessing, preamp: Float, balance: Float) {
        val center = preamp.coerceIn(-12f, 12f)
        val amount = balance.coerceIn(-1f, 1f) * 12f
        val left = (center + if (amount > 0f) -amount else 0f).coerceIn(-12f, 12f)
        val right = (center + if (amount < 0f) amount else 0f).coerceIn(-12f, 12f)
        effect.setInputGainbyChannel(0, left)
        effect.setInputGainbyChannel(1, right)
    }

    fun detachSession(sessionId: Int) {
        if (sessionId == 0) return
        try { effects.remove(sessionId)?.release() } catch (_: Throwable) {}
        onStatus(statusForEffects())
    }

    fun releaseAll() {
        effects.values.forEach { try { it.release() } catch (_: Throwable) {} }
        effects.clear()
        onStatus("Bypassed")
    }

    fun hasGlobalMixEffect(): Boolean = effects.containsKey(0)
    fun activeSessionCount(): Int = effects.keys.count { it != 0 }

    private fun statusForEffects(): String {
        val mediaSessions = activeSessionCount()
        val global = effects[0]
        return when {
            mediaSessions > 0 -> "Active on Media Session ($mediaSessions)"
            global != null -> try {
                if (global.hasControl() && global.enabled) "Active on Global Mix"
                else "Bypassed — global effect has no control"
            } catch (_: Throwable) {
                "Bypassed — global effect status unavailable"
            }
            else -> "Bypassed"
        }
    }
}
