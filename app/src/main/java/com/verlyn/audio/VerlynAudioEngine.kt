@file:Suppress("DEPRECATION")

package com.verlyn.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build

/** Binds actual AudioEffect instances to media app sessions; session 0 is only a fallback. */
class VerlynAudioEngine(
    context: Context,
    private val onStatus: (String) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dynamics = linkedMapOf<Int, DynamicsProcessing>()
    private val equalizers = linkedMapOf<Int, Equalizer>()
    private val bassEffects = linkedMapOf<Int, BassBoost>()
    private val virtualizers = linkedMapOf<Int, Virtualizer>()
    private val nativeLimiters = linkedMapOf<Int, DynamicsProcessing>()
    private val sessionPackages = linkedMapOf<Int, String>()
    private val engineNames = linkedMapOf<Int, String>()
    @Volatile private var bassBoostEnabled = false
    @Volatile private var spatialEnabled = false
    @Volatile private var bypassed = false
    @Volatile private var bassPunch = 0f
    @Volatile private var vocalPresence = 0f
    @Volatile private var trebleAir = 0f
    @Volatile private var routeActive = false
    @Volatile private var lastConfig = EffectConfig()
    @Volatile private var preferredEngine = "native"

    private data class EffectConfig(
        val gains: FloatArray = FloatArray(10),
        val preamp: Float = 0f,
        val eqEnabled: Boolean = true,
        val limiterEnabled: Boolean = true,
        val balance: Float = 0f
    )

    companion object {
        val BAND_FREQUENCIES = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    }

    @Synchronized
    fun attachGlobal(gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        lastConfig = EffectConfig(gains.copyOf(), preamp, eqEnabled, limiterEnabled, balance)
        sessionPackages[0] = "Global Mix"
        attach(0, "Global Mix", lastConfig)
    }

    @Synchronized
    fun setEngineMode(mode: String) {
        preferredEngine = if (mode.equals("native", true)) "native" else "dynamics"
        val ids = (sessionPackages.keys + dynamics.keys + equalizers.keys + nativeLimiters.keys).distinct()
        ids.forEach { sessionId ->
            val packageName = sessionPackages[sessionId] ?: if (sessionId == 0) "Global Mix" else "Unknown app"
            attach(sessionId, packageName, lastConfig)
        }
        onStatus(statusForEffects())
    }

    fun currentEngineMode(): String = preferredEngine

    @Synchronized
    fun attachSession(
        sessionId: Int,
        packageName: String,
        gains: FloatArray,
        preamp: Float,
        eqEnabled: Boolean,
        limiterEnabled: Boolean,
        balance: Float = 0f
    ) {
        if (sessionId <= 0) return
        lastConfig = EffectConfig(gains.copyOf(), preamp, eqEnabled, limiterEnabled, balance)
        sessionPackages[sessionId] = packageName.ifBlank { "Unknown app" }
        attach(sessionId, sessionPackages[sessionId] ?: "Unknown app", lastConfig)
    }

    @Synchronized
    private fun attach(sessionId: Int, packageName: String, config: EffectConfig) {
        releaseSessionEffects(sessionId)
        if (preferredEngine == "dynamics" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val preEqInUse = true
                val mbcInUse = true
                val limiterInUse = true
                val preEq = buildEq(config.gains, config.eqEnabled)
                preEq.setEnabled(true)
                val postEq = buildEq(FloatArray(BAND_FREQUENCIES.size), true)
                postEq.setEnabled(true)
                val limiter = DynamicsProcessing.Limiter(
                    true, config.limiterEnabled, 0, 10f, 100f, 20f, -1f, 0f
                )
                // The old configuration set both EQ blocks to false and enabled MBC instead.
                // That constructed a running but effectively flat EQ chain.
                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    preEqInUse, BAND_FREQUENCIES.size,
                    mbcInUse, 3,
                    true, BAND_FREQUENCIES.size,
                    limiterInUse
                )
                    .setInputGainAllChannelsTo(effectiveInputPreamp(config))
                    .setPreEqAllChannelsTo(preEq)
                    .setMbcAllChannelsTo(buildMbc())
                    .setPostEqAllChannelsTo(postEq)
                    .setLimiterAllChannelsTo(limiter)

                val effect = DynamicsProcessing(0, sessionId, builder.build())
                applyBalance(effect, effectiveInputPreamp(config), config.balance)
                if (!effect.hasControl()) {
                    effect.release()
                    throw IllegalStateException("DynamicsProcessing hasControl=false")
                }
                effect.enabled = !bypassed
                if (effect.enabled != !bypassed || !effect.hasControl()) {
                    effect.release()
                    throw IllegalStateException("DynamicsProcessing failed its enabled/control check")
                }
                dynamics[sessionId] = effect
                engineNames[sessionId] = "DynamicsProcessing"
                applyAuxEffects(sessionId)
                onStatus(statusForEffects())
                return
            } catch (error: Throwable) {
                onStatus("Session $sessionId · DynamicsProcessing rejected: " + (error.message ?: error.javaClass.simpleName))
            }
        }
        if (attachLegacy(sessionId, config)) onStatus(statusForEffects())
        else onStatus("Session $sessionId · no controllable Equalizer/BassBoost effect")
    }

    private fun buildEq(gains: FloatArray, enabled: Boolean): DynamicsProcessing.Eq {
        return DynamicsProcessing.Eq(true, true, BAND_FREQUENCIES.size).also { eq ->
            eq.setEnabled(enabled)
            BAND_FREQUENCIES.indices.forEach { index ->
                eq.setBand(
                    index,
                    DynamicsProcessing.EqBand(
                        true,
                        BAND_FREQUENCIES[index],
                        (if (enabled) gains.getOrElse(index) { 0f } else 0f).coerceIn(-12f, 12f)
                    ).also { band -> band.setEnabled(true) }
                )
            }
        }
    }

    private fun effectiveInputPreamp(config: EffectConfig): Float {
        val maxPositiveBandGain = if (config.eqEnabled) (config.gains.maxOrNull() ?: 0f).coerceAtLeast(0f) else 0f
        return (config.preamp - maxPositiveBandGain).coerceIn(-24f, 12f)
    }

    private fun buildMbc(): DynamicsProcessing.Mbc {
        return DynamicsProcessing.Mbc(true, true, 3).also { mbc ->
            mbc.setBand(0, DynamicsProcessing.MbcBand(true, 250f, 10f, 100f, 2.0f, -24f, 6f, -70f, 1f, bassPunch.coerceIn(-6f, 6f), 0f))
            mbc.setBand(1, DynamicsProcessing.MbcBand(true, 2_500f, 10f, 120f, 1.6f, -24f, 6f, -70f, 1f, vocalPresence.coerceIn(-6f, 6f), 0f))
            mbc.setBand(2, DynamicsProcessing.MbcBand(true, 20_000f, 5f, 80f, 1.4f, -26f, 6f, -72f, 1f, trebleAir.coerceIn(-6f, 6f), 0f))
        }
    }

    private fun applyBalance(effect: DynamicsProcessing, preamp: Float, balance: Float) {
        val base = preamp.coerceIn(-24f, 12f)
        val attenuation = kotlin.math.abs(balance.coerceIn(-1f, 1f)) * 24f
        val left = (base - if (balance > 0f) attenuation else 0f).coerceIn(-24f, 12f)
        val right = (base - if (balance < 0f) attenuation else 0f).coerceIn(-24f, 12f)
        effect.setInputGainbyChannel(0, left)
        effect.setInputGainbyChannel(1, right)
    }

    private fun attachLegacy(sessionId: Int, config: EffectConfig): Boolean {
        return runCatching {
            val eq = Equalizer(0, sessionId)
            if (!eq.hasControl()) {
                eq.release()
                throw IllegalStateException("Equalizer hasControl=false")
            }
            applyLegacyEq(eq, config.gains, config.eqEnabled && !bypassed)
            eq.enabled = config.eqEnabled && !bypassed
            if (eq.enabled != (config.eqEnabled && !bypassed) || !eq.hasControl()) {
                eq.release()
                throw IllegalStateException("Equalizer failed enabled/control check")
            }
            equalizers[sessionId] = eq
            engineNames[sessionId] = "Compatibility Equalizer (" + eq.numberOfBands + "-band Native)"
            applyAuxEffects(sessionId)
            true
        }.getOrDefault(false)
    }

    /** Interpolates the 10-point UI curve at the device's actual native EQ band centres. */
    private fun interpolatedGain(frequencyHz: Float, gains: FloatArray): Float {
        val frequency = frequencyHz.coerceAtLeast(1f)
        if (frequency <= BAND_FREQUENCIES.first()) return gains.getOrElse(0) { 0f }
        if (frequency >= BAND_FREQUENCIES.last()) return gains.getOrElse(BAND_FREQUENCIES.lastIndex) { 0f }
        val upper = BAND_FREQUENCIES.indexOfFirst { it >= frequency }.coerceIn(1, BAND_FREQUENCIES.lastIndex)
        val lower = upper - 1
        val lowHz = BAND_FREQUENCIES[lower]
        val highHz = BAND_FREQUENCIES[upper]
        val ratio = ((kotlin.math.ln(frequency.toDouble()) - kotlin.math.ln(lowHz.toDouble())) /
            (kotlin.math.ln(highHz.toDouble()) - kotlin.math.ln(lowHz.toDouble()))).toFloat()
        val lowGain = gains.getOrElse(lower) { 0f }
        val highGain = gains.getOrElse(upper) { 0f }
        return lowGain + (highGain - lowGain) * ratio
    }

    private fun applyLegacyEq(effect: Equalizer, gains: FloatArray, enabled: Boolean) {
        val count = effect.numberOfBands.toInt()
        val range = effect.bandLevelRange
        for (band in 0 until count) {
            val frequency = effect.getCenterFreq(band.toShort()) / 1000f
            val level = ((if (enabled) interpolatedGain(frequency, gains) else 0f) * 100f)
                .toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
            effect.setBandLevel(band.toShort(), level)
        }
        effect.enabled = enabled
    }

    @Synchronized
    fun apply(gains: FloatArray, preamp: Float, eqEnabled: Boolean, limiterEnabled: Boolean, balance: Float = 0f) {
        val config = EffectConfig(gains.copyOf(), preamp, eqEnabled, limiterEnabled, balance)
        lastConfig = config
        val sessions = (dynamics.keys + equalizers.keys).toList()
        sessions.forEach { sessionId ->
            val dp = dynamics[sessionId]
            if (dp != null) {
                val updated = runCatching {
                    if (!dp.hasControl()) throw IllegalStateException("hasControl=false before update")
                    dp.setInputGainAllChannelsTo(effectiveInputPreamp(config))
                    applyBalance(dp, effectiveInputPreamp(config), config.balance)
                    dp.setPreEqAllChannelsTo(buildEq(config.gains, config.eqEnabled))
                    dp.setMbcAllChannelsTo(buildMbc())
                    dp.setPostEqAllChannelsTo(buildEq(FloatArray(BAND_FREQUENCIES.size), true))
                    dp.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(true, config.limiterEnabled, 0, 10f, 100f, 20f, -1f, 0f)
                    )
                    dp.enabled = !bypassed
                    if (dp.enabled != !bypassed || !dp.hasControl()) throw IllegalStateException("enabled/control check failed after update")
                    true
                }.getOrDefault(false)
                if (!updated) {
                    runCatching { dp.release() }
                    dynamics.remove(sessionId)
                    if (attachLegacy(sessionId, config)) {
                        engineNames[sessionId] = "Android Equalizer fallback"
                    } else {
                        engineNames[sessionId] = "Unavailable"
                    }
                }
            } else {
                val legacy = equalizers[sessionId]
                if (legacy != null) {
                    val updated = runCatching {
                        if (!legacy.hasControl()) throw IllegalStateException("Equalizer hasControl=false")
                        applyLegacyEq(legacy, config.gains, config.eqEnabled && !bypassed)
                        if (!legacy.hasControl() || legacy.enabled != (config.eqEnabled && !bypassed)) {
                            throw IllegalStateException("Equalizer did not confirm update")
                        }
                        true
                    }.getOrDefault(false)
                    if (!updated) {
                        runCatching { legacy.release() }
                        equalizers.remove(sessionId)
                        engineNames[sessionId] = "Unavailable"
                    }
                }
            }
            applyAuxEffects(sessionId)
        }
        onStatus(statusForEffects())
    }

    @Synchronized
    fun setBypassed(value: Boolean) {
        if (bypassed == value) return
        bypassed = value
        dynamics.values.forEach { effect -> runCatching { effect.enabled = !value } }
        equalizers.values.forEach { effect -> runCatching { effect.enabled = lastConfig.eqEnabled && !value } }
        bassEffects.values.forEach { effect -> runCatching { effect.enabled = bassBoostEnabled && !value } }
        virtualizers.values.forEach { effect -> runCatching { effect.enabled = spatialEnabled && !value } }
        nativeLimiters.values.forEach { effect -> runCatching { effect.enabled = lastConfig.limiterEnabled && !value } }
        onStatus(statusForEffects())
    }

    @Synchronized
    fun setMbcTuning(bass: Float, vocal: Float, air: Float) {
        bassPunch = bass.coerceIn(-6f, 6f)
        vocalPresence = vocal.coerceIn(-6f, 6f)
        trebleAir = air.coerceIn(-6f, 6f)
        dynamics.values.forEach { effect ->
            runCatching {
                if (!effect.hasControl()) throw IllegalStateException("DynamicsProcessing hasControl=false")
                effect.setMbcAllChannelsTo(buildMbc())
            }.onFailure { onStatus("MBC update unavailable: " + (it.message ?: it.javaClass.simpleName)) }
        }
        onStatus(statusForEffects())
    }

    @Synchronized
    fun setRouteActive(active: Boolean) {
        routeActive = active
        // Session-bound effects follow the actual app session; do not disable them simply
        // because the route poll has not yet refreshed or the app changed output devices.
        onStatus(statusForEffects())
    }

    @Synchronized
    fun setBassBoost(enabled: Boolean) {
        bassBoostEnabled = enabled
        (sessionPackages.keys + dynamics.keys + equalizers.keys + bassEffects.keys).distinct().ifEmpty { listOf(0) }.forEach {
            applyAuxEffects(it)
        }
        onStatus(statusForEffects())
    }

    @Synchronized
    fun setSpatial(enabled: Boolean) {
        spatialEnabled = enabled
        (sessionPackages.keys + dynamics.keys + equalizers.keys + virtualizers.keys).distinct().ifEmpty { listOf(0) }.forEach {
            applyAuxEffects(it)
        }
        onStatus(statusForEffects())
    }

    private fun applyAuxEffects(sessionId: Int) {
        runCatching {
            val bass = bassEffects[sessionId] ?: BassBoost(0, sessionId).also { bassEffects[sessionId] = it }
            if (!bass.hasControl()) throw IllegalStateException("BassBoost hasControl=false")
            if (bassBoostEnabled) bass.setStrength(650.toShort())
            val shouldEnableBass = bassBoostEnabled && !bypassed
            bass.enabled = shouldEnableBass
            if (bass.enabled != shouldEnableBass || !bass.hasControl()) throw IllegalStateException("BassBoost failed enabled/control check")
        }.onFailure {
            bassEffects.remove(sessionId)?.let { effect -> runCatching { effect.release() } }
            onStatus("Session $sessionId · BassBoost unavailable: " + (it.message ?: "no control"))
        }

        if (dynamics.containsKey(sessionId)) {
            nativeLimiters.remove(sessionId)?.let { runCatching { it.release() } }
        } else {
            applyNativeLimiter(sessionId, lastConfig.limiterEnabled && !bypassed)
        }

        if (spatialEnabled && !bypassed) {
            runCatching {
                val virtualizer = virtualizers[sessionId] ?: Virtualizer(0, sessionId).also { virtualizers[sessionId] = it }
                if (!virtualizer.hasControl()) throw IllegalStateException("Virtualizer hasControl=false")
                virtualizer.setStrength(650.toShort())
                virtualizer.enabled = true
                if (!virtualizer.enabled || !virtualizer.hasControl()) throw IllegalStateException("Virtualizer failed enabled/control check")
            }.onFailure {
                virtualizers.remove(sessionId)?.let { effect -> runCatching { effect.release() } }
                onStatus("Session $sessionId · Virtualizer unavailable: " + (it.message ?: "no control"))
            }
        } else virtualizers.remove(sessionId)?.let { runCatching { it.release() } }
    }

    private fun applyNativeLimiter(sessionId: Int, enabled: Boolean) {
        if (!enabled) {
            nativeLimiters.remove(sessionId)?.let { runCatching { it.release() } }
            return
        }
        runCatching {
            val limiter = nativeLimiters[sessionId] ?: run {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    false, 0,
                    false, 0,
                    false, 0,
                    true
                ).setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(true, true, 0, 10f, 100f, 20f, -1f, 0f)
                ).build()
                DynamicsProcessing(0, sessionId, config).also { nativeLimiters[sessionId] = it }
            }
            if (!limiter.hasControl()) throw IllegalStateException("native limiter hasControl=false")
            limiter.enabled = enabled && !bypassed
            if (!limiter.enabled || !limiter.hasControl()) {
                throw IllegalStateException("native limiter enabled/control check failed")
            }
        }.onFailure { error ->
            nativeLimiters.remove(sessionId)?.let { runCatching { it.release() } }
            onStatus("Session $sessionId · native peak limiter unavailable: " +
                (error.message ?: error.javaClass.simpleName))
        }
    }

    @Synchronized
    fun detachSession(sessionId: Int) {
        if (sessionId == 0) return
        releaseSessionEffects(sessionId)
        sessionPackages.remove(sessionId)
        engineNames.remove(sessionId)
        onStatus(statusForEffects())
    }

    private fun releaseSessionEffects(sessionId: Int) {
        dynamics.remove(sessionId)?.let { runCatching { it.release() } }
        equalizers.remove(sessionId)?.let { runCatching { it.release() } }
        bassEffects.remove(sessionId)?.let { runCatching { it.release() } }
        virtualizers.remove(sessionId)?.let { runCatching { it.release() } }
        nativeLimiters.remove(sessionId)?.let { runCatching { it.release() } }
        if (sessionId == 0) {
            sessionPackages.remove(sessionId)
            engineNames.remove(sessionId)
        }
    }

    @Synchronized
    fun releaseAll() {
        (dynamics.keys + equalizers.keys + bassEffects.keys + virtualizers.keys + nativeLimiters.keys).distinct().forEach(::releaseSessionEffects)
        dynamics.clear()
        equalizers.clear()
        bassEffects.clear()
        virtualizers.clear()
        nativeLimiters.clear()
        sessionPackages.clear()
        engineNames.clear()
        onStatus("No audio sessions bound")
    }

    @Synchronized
    fun hasGlobalMixEffect(): Boolean = dynamics.containsKey(0) || equalizers.containsKey(0)

    @Synchronized
    fun activeSessionCount(): Int = (dynamics.keys + equalizers.keys).count { it != 0 }

    @Synchronized
    fun boundSessionIds(): List<Int> = (dynamics.keys + equalizers.keys).filter { it != 0 }.sorted()

    @Synchronized
    fun sessionDebugLines(): List<String> {
        val ids = boundSessionIds()
        if (ids.isEmpty()) return listOf("No app media sessions currently bound")
        return ids.map { id ->
            val packageName = sessionPackages[id] ?: "Unknown app"
            val effect = dynamics[id]
            val legacy = equalizers[id]
            val control = when {
                effect != null -> runCatching { effect.hasControl() }.getOrDefault(false)
                legacy != null -> runCatching { legacy.hasControl() }.getOrDefault(false)
                else -> false
            }
            val enabled = when {
                effect != null -> runCatching { effect.enabled }.getOrDefault(false)
                legacy != null -> runCatching { legacy.enabled }.getOrDefault(false)
                else -> false
            }
            val bass = bassEffects[id]
            val virtualizer = virtualizers[id]
            val limiter = dynamics[id] ?: nativeLimiters[id]
            val limiterEnabledState = limiter?.let { runCatching { it.enabled }.getOrDefault(false) } ?: false
            packageName + " (Session " + id + ") · " + (engineNames[id] ?: "Unknown") +
                " · hasControl=" + control + " · enabled=" + enabled +
                " · BassBoost(control=" + (bass?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false) +
                ", enabled=" + (bass?.let { runCatching { it.enabled }.getOrDefault(false) } ?: false) +
                ", strength=" + (bass?.let { runCatching { it.roundedStrength.toInt() }.getOrDefault(0) } ?: 0) + ")" +
                " · Virtualizer(control=" + (virtualizer?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false) +
                ", enabled=" + (virtualizer?.let { runCatching { it.enabled }.getOrDefault(false) } ?: false) +
                ", strength=" + (virtualizer?.let { runCatching { it.roundedStrength.toInt() }.getOrDefault(0) } ?: 0) + ")" +
                " · PeakLimiter(control=" + (limiter?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false) +
                ", enabled=" + limiterEnabledState + ")"
        }
    }

    @Synchronized
    fun statusForEffects(): String {
        val sessions = boundSessionIds()
        if (sessions.isNotEmpty()) {
            val first = sessions.first()
            val name = sessionPackages[first] ?: "Media app"
            val engine = engineNames[first] ?: "AudioEffect"
            val hasControl = dynamics[first]?.let { runCatching { it.hasControl() }.getOrDefault(false) }
                ?: equalizers[first]?.let { runCatching { it.hasControl() }.getOrDefault(false) }
                ?: false
            val enabled = dynamics[first]?.let { runCatching { it.enabled }.getOrDefault(false) }
                ?: equalizers[first]?.let { runCatching { it.enabled }.getOrDefault(false) }
                ?: false
            val bass = bassEffects[first]
            val virtualizer = virtualizers[first]
            val limiter = dynamics[first] ?: nativeLimiters[first]
            val limiterEnabledState = limiter?.let { runCatching { it.enabled }.getOrDefault(false) } ?: false
            return engine + " · " + name + " · Session " + first + " · control=" + hasControl + " · enabled=" + enabled +
                " · BassBoost=" + (bass?.let { runCatching { it.hasControl() && it.enabled }.getOrDefault(false) } ?: false) +
                " · Virtualizer=" + (virtualizer?.let { runCatching { it.hasControl() && it.enabled }.getOrDefault(false) } ?: false) +
                " · LimiterControl=" + (limiter?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false) +
                " · LimiterEnabled=" + limiterEnabledState
        }
        val global = dynamics[0]
        val legacy = equalizers[0]
        return when {
            global != null -> "DynamicsProcessing · Global Mix · hasControl=" + runCatching { global.hasControl() }.getOrDefault(false) +
                " · enabled=" + runCatching { global.enabled }.getOrDefault(false)
            legacy != null -> "Android Equalizer · Global Mix · hasControl=" + runCatching { legacy.hasControl() }.getOrDefault(false) +
                " · enabled=" + runCatching { legacy.enabled }.getOrDefault(false)
            else -> "Waiting for playback session"
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
