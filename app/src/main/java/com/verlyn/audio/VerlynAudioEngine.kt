package com.verlyn.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.media.audiofx.Equalizer
import android.os.Build

/** Native Android DynamicsProcessing wrapper for global and media-session audio effects. */
class VerlynAudioEngine(
    context: Context,
    private val onStatus: (String) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val effects = linkedMapOf<Int, DynamicsProcessing>()
    private val bassEffects = linkedMapOf<Int, BassBoost>()
    private val virtualizers = linkedMapOf<Int, Virtualizer>()
    private val legacyEqualizers = linkedMapOf<Int, Equalizer>()
    @Volatile private var bassBoostEnabled = false
    @Volatile private var spatialEnabled = false
    @Volatile private var routeActive = false
    @Volatile private var eqEnabledState = true

    companion object {
        val BAND_FREQUENCIES = floatArrayOf(
            32f, 64f, 125f, 250f, 500f,
            1000f, 2000f, 4000f, 8000f, 16000f
        )
    }

    @Synchronized fun attachGlobal(gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        attach(0, gains, preamp, eqEnabled, limiterEnabled, balance)
    }

    @Synchronized fun attachSession(sessionId: Int, gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        if (sessionId <= 0) return
        attach(sessionId, gains, preamp, eqEnabled, limiterEnabled, balance)
    }

    @Synchronized private fun attach(sessionId: Int, gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float) {
        eqEnabledState = eqEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            if (!attachLegacy(sessionId, gains, eqEnabled)) onStatus("Bypassed — Android Equalizer unavailable")
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
            effect.enabled = routeActive
            effects[sessionId] = effect
            legacyEqualizers.remove(sessionId)?.release()
            applyAuxEffects(sessionId)
            onStatus(statusForEffects())
        } catch (error: Throwable) {
            if (!attachLegacy(sessionId, gains, eqEnabled)) {
                onStatus("Bypassed — ${error.javaClass.simpleName}: ${error.message ?: "effect unavailable"}")
            }
        }
    }

    private fun attachLegacy(sessionId: Int, gains: FloatArray, enabled: Boolean): Boolean = try {
        legacyEqualizers.remove(sessionId)?.release()
        val equalizer = Equalizer(0, sessionId)
        val count = equalizer.numberOfBands.toInt()
        val range = equalizer.bandLevelRange
        for (band in 0 until count) {
            val frequencyHz = equalizer.getCenterFreq(band.toShort()) / 1000f
            val nearest = BAND_FREQUENCIES.indices.minByOrNull { kotlin.math.abs(BAND_FREQUENCIES[it] - frequencyHz) } ?: 0
            val level = (gains.getOrElse(nearest) { 0f } * 100f).toInt()
                .coerceIn(range[0].toInt(), range[1].toInt()).toShort()
            equalizer.setBandLevel(band.toShort(), level)
        }
        equalizer.enabled = enabled && routeActive
        legacyEqualizers[sessionId] = equalizer
        applyAuxEffects(sessionId)
        onStatus(statusForEffects())
        true
    } catch (_: Throwable) {
        false
    }

    private fun applyLegacyEffects(gains: FloatArray, enabled: Boolean) {
        legacyEqualizers.toMap().forEach { (sessionId, equalizer) ->
            try {
                val count = equalizer.numberOfBands.toInt()
                val range = equalizer.bandLevelRange
                for (band in 0 until count) {
                    val frequencyHz = equalizer.getCenterFreq(band.toShort()) / 1000f
                    val nearest = BAND_FREQUENCIES.indices.minByOrNull { kotlin.math.abs(BAND_FREQUENCIES[it] - frequencyHz) } ?: 0
                    val level = (gains.getOrElse(nearest) { 0f } * 100f).toInt()
                        .coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    equalizer.setBandLevel(band.toShort(), level)
                }
                equalizer.enabled = enabled && routeActive
            } catch (_: Throwable) { legacyEqualizers.remove(sessionId)?.release() }
        }
    }

    @Synchronized fun apply(gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        eqEnabledState = eqEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            applyLegacyEffects(gains, eqEnabled)
            onStatus(statusForEffects())
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
                effect.enabled = routeActive
            } catch (error: Throwable) {
                onStatus("Bypassed — session $sessionId: ${error.message ?: "update failed"}")
            }
        }
        applyLegacyEffects(gains, eqEnabled)
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

    @Synchronized fun setRouteActive(active: Boolean) {
        routeActive = active
        effects.values.forEach { runCatching { it.enabled = active } }
        legacyEqualizers.values.forEach { runCatching { it.enabled = active && eqEnabledState } }
        bassEffects.values.forEach { runCatching { it.enabled = active && bassBoostEnabled } }
        virtualizers.values.forEach { runCatching { it.enabled = active && spatialEnabled } }
        onStatus(statusForEffects())
    }

    @Synchronized fun setBassBoost(enabled: Boolean) {
        bassBoostEnabled = enabled
        val targets = (effects.keys + legacyEqualizers.keys + bassEffects.keys).ifEmpty { setOf(0) }
        targets.forEach { applyAuxEffects(it) }
    }

    @Synchronized fun setSpatial(enabled: Boolean) {
        spatialEnabled = enabled
        val targets = (effects.keys + legacyEqualizers.keys + virtualizers.keys).ifEmpty { setOf(0) }
        targets.forEach { applyAuxEffects(it) }
    }

    private fun applyAuxEffects(sessionId: Int) {
        try {
            val bass = bassEffects.getOrPut(sessionId) { BassBoost(0, sessionId) }
            bass.setStrength(650.toShort())
            bass.enabled = bassBoostEnabled && routeActive
        } catch (_: Throwable) { }
        try {
            val platformSpatializerActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { audioManager.spatializer.isAvailable && audioManager.spatializer.isEnabled }.getOrDefault(false)
            val virtualizer = virtualizers.getOrPut(sessionId) { Virtualizer(0, sessionId) }
            virtualizer.setStrength(500.toShort())
            // Prefer Android's native spatializer when active; only enable the app-controlled
            // Virtualizer fallback when platform spatialization is unavailable or disabled.
            virtualizer.enabled = spatialEnabled && !platformSpatializerActive && routeActive
        } catch (_: Throwable) { }
    }

    @Synchronized fun detachSession(sessionId: Int) {
        if (sessionId == 0) return
        try { effects.remove(sessionId)?.release() } catch (_: Throwable) {}
        try { legacyEqualizers.remove(sessionId)?.release() } catch (_: Throwable) {}
        try { bassEffects.remove(sessionId)?.release() } catch (_: Throwable) {}
        try { virtualizers.remove(sessionId)?.release() } catch (_: Throwable) {}
        onStatus(statusForEffects())
    }

    @Synchronized fun releaseAll() {
        effects.values.forEach { try { it.release() } catch (_: Throwable) {} }
        bassEffects.values.forEach { try { it.release() } catch (_: Throwable) {} }
        virtualizers.values.forEach { try { it.release() } catch (_: Throwable) {} }
        effects.clear()
        legacyEqualizers.values.forEach { try { it.release() } catch (_: Throwable) {} }
        legacyEqualizers.clear()
        bassEffects.clear()
        virtualizers.clear()
        onStatus("Bypassed")
    }

    @Synchronized fun hasGlobalMixEffect(): Boolean = effects.containsKey(0) || legacyEqualizers.containsKey(0)
    @Synchronized fun activeSessionCount(): Int = (effects.keys + legacyEqualizers.keys).count { it != 0 }

    private fun statusForEffects(): String {
        val mediaSessions = activeSessionCount()
        val global = effects[0]
        val legacyGlobal = legacyEqualizers[0]
        return when {
            !routeActive && (global != null || legacyGlobal != null || mediaSessions > 0) -> "Bypassed · JBL output inactive"
            mediaSessions > 0 -> "Active on Media Session ($mediaSessions)"
            legacyGlobal != null -> "Active on Android Equalizer"
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


object VerlynAudioRuntime {
    const val ACTION_STATUS = "com.verlyn.audio.AUDIO_EFFECT_STATUS"
    const val EXTRA_STATUS = "status"
    @Volatile private var instance: VerlynAudioEngine? = null

    @Synchronized
    fun get(context: Context): VerlynAudioEngine {
        instance?.let { return it }
        val app = context.applicationContext
        return VerlynAudioEngine(app) { status ->
            app.sendBroadcast(android.content.Intent(ACTION_STATUS).setPackage(app.packageName).putExtra(EXTRA_STATUS, status))
        }.also { instance = it }
    }

    @Synchronized
    fun release() {
        instance?.releaseAll()
        instance = null
    }
}
