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
    private val sessionPackages = linkedMapOf<Int, String>()
    private val engineNames = linkedMapOf<Int, String>()
    @Volatile private var bassBoostEnabled = false
    @Volatile private var spatialEnabled = false
    @Volatile private var routeActive = false
    @Volatile private var lastConfig = EffectConfig()

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
        attach(0, "Global Mix", lastConfig)
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val preEq = buildEq(config.gains, config.eqEnabled)
                val postEq = buildEq(FloatArray(BAND_FREQUENCIES.size), true)
                val limiter = DynamicsProcessing.Limiter(
                    true, config.limiterEnabled, 0, 10f, 100f, 20f, -1f, 0f
                )
                // The old configuration set both EQ blocks to false and enabled MBC instead.
                // That constructed a running but effectively flat EQ chain.
                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, BAND_FREQUENCIES.size,
                    true, BAND_FREQUENCIES.size,
                    false, 0,
                    true
                )
                    .setInputGainAllChannelsTo(config.preamp.coerceIn(-12f, 12f))
                    .setPreEqAllChannelsTo(preEq)
                    .setPostEqAllChannelsTo(postEq)
                    .setLimiterAllChannelsTo(limiter)

                val effect = DynamicsProcessing(0, sessionId, builder.build())
                applyBalance(effect, config.preamp, config.balance)
                if (!effect.hasControl()) {
                    effect.release()
                    throw IllegalStateException("DynamicsProcessing hasControl=false")
                }
                effect.enabled = true
                if (!effect.enabled || !effect.hasControl()) {
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
                        gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
                    )
                )
            }
        }
    }

    private fun applyBalance(effect: DynamicsProcessing, preamp: Float, balance: Float) {
        val base = preamp.coerceIn(-12f, 12f)
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
            applyLegacyEq(eq, config.gains, config.eqEnabled)
            eq.enabled = config.eqEnabled
            if (!eq.enabled || !eq.hasControl()) {
                eq.release()
                throw IllegalStateException("Equalizer failed enabled/control check")
            }
            equalizers[sessionId] = eq
            engineNames[sessionId] = "Android Equalizer"
            applyAuxEffects(sessionId)
            true
        }.getOrDefault(false)
    }

    private fun applyLegacyEq(effect: Equalizer, gains: FloatArray, enabled: Boolean) {
        val count = effect.numberOfBands.toInt()
        val range = effect.bandLevelRange
        for (band in 0 until count) {
            val frequency = effect.getCenterFreq(band.toShort()) / 1000f
            val nearest = BAND_FREQUENCIES.indices.minByOrNull { kotlin.math.abs(BAND_FREQUENCIES[it] - frequency) } ?: 0
            val level = (gains.getOrElse(nearest) { 0f } * 100f)
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
                    dp.setInputGainAllChannelsTo(config.preamp.coerceIn(-12f, 12f))
                    applyBalance(dp, config.preamp, config.balance)
                    dp.setPreEqAllChannelsTo(buildEq(config.gains, config.eqEnabled))
                    dp.setPostEqAllChannelsTo(buildEq(FloatArray(BAND_FREQUENCIES.size), true))
                    dp.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(true, config.limiterEnabled, 0, 10f, 100f, 20f, -1f, 0f)
                    )
                    dp.enabled = true
                    if (!dp.enabled || !dp.hasControl()) throw IllegalStateException("enabled/control check failed after update")
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
                        applyLegacyEq(legacy, config.gains, config.eqEnabled)
                        if (!legacy.hasControl() || legacy.enabled != config.eqEnabled) {
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
    fun setRouteActive(active: Boolean) {
        routeActive = active
        // Session-bound effects follow the actual app session; do not disable them simply
        // because the route poll has not yet refreshed or the app changed output devices.
        onStatus(statusForEffects())
    }

    @Synchronized
    fun setBassBoost(enabled: Boolean) {
        bassBoostEnabled = enabled
        (dynamics.keys + equalizers.keys + bassEffects.keys).distinct().ifEmpty { listOf(0) }.forEach {
            applyAuxEffects(it)
        }
        onStatus(statusForEffects())
    }

    @Synchronized
    fun setSpatial(enabled: Boolean) {
        spatialEnabled = enabled
        (dynamics.keys + equalizers.keys + virtualizers.keys).distinct().ifEmpty { listOf(0) }.forEach {
            applyAuxEffects(it)
        }
        onStatus(statusForEffects())
    }

    private fun applyAuxEffects(sessionId: Int) {
        if (bassBoostEnabled) {
            runCatching {
                val bass = bassEffects[sessionId] ?: BassBoost(0, sessionId).also { bassEffects[sessionId] = it }
                if (!bass.hasControl()) throw IllegalStateException("BassBoost hasControl=false")
                bass.setStrength(900.toShort())
                bass.enabled = true
                if (!bass.enabled || !bass.hasControl()) throw IllegalStateException("BassBoost failed enabled/control check")
            }.onFailure {
                bassEffects.remove(sessionId)?.let { effect -> runCatching { effect.release() } }
                onStatus("Session $sessionId · BassBoost unavailable: " + (it.message ?: "no control"))
            }
        } else bassEffects.remove(sessionId)?.let { runCatching { it.release() } }

        if (spatialEnabled) {
            runCatching {
                val virtualizer = virtualizers[sessionId] ?: Virtualizer(0, sessionId).also { virtualizers[sessionId] = it }
                if (!virtualizer.hasControl()) throw IllegalStateException("Virtualizer hasControl=false")
                virtualizer.setStrength(600.toShort())
                virtualizer.enabled = true
                if (!virtualizer.enabled || !virtualizer.hasControl()) throw IllegalStateException("Virtualizer failed enabled/control check")
            }.onFailure {
                virtualizers.remove(sessionId)?.let { effect -> runCatching { effect.release() } }
                onStatus("Session $sessionId · Virtualizer unavailable: " + (it.message ?: "no control"))
            }
        } else virtualizers.remove(sessionId)?.let { runCatching { it.release() } }
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
        if (sessionId == 0) {
            sessionPackages.remove(sessionId)
            engineNames.remove(sessionId)
        }
    }

    @Synchronized
    fun releaseAll() {
        (dynamics.keys + equalizers.keys + bassEffects.keys + virtualizers.keys).distinct().forEach(::releaseSessionEffects)
        dynamics.clear()
        equalizers.clear()
        bassEffects.clear()
        virtualizers.clear()
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
            packageName + " (Session " + id + ") · " + (engineNames[id] ?: "Unknown") +
                " · hasControl=" + control + " · enabled=" + enabled
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
            return engine + " · " + name + " · Session " + first + " · control=" + hasControl + " · enabled=" + enabled
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
