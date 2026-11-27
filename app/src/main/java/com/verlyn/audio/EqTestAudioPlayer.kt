package com.verlyn.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Continuous bass/synth test loop bound to its own AudioTrack session for DSP verification. */
class EqTestAudioPlayer(
    private val engine: VerlynAudioEngine,
    private val onStatus: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentJob: Job? = null
    @Volatile private var currentSessionId: Int? = null

    fun play(
        gains: FloatArray,
        preamp: Float,
        eqEnabled: Boolean,
        limiterEnabled: Boolean,
        balance: Float
    ) {
        stop()
        currentJob = scope.launch {
            var track: AudioTrack? = null
            var sessionId = 0
            try {
                val sampleRate = 48_000
                val channels = AudioFormat.CHANNEL_OUT_STEREO
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channels)
                    .build()
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val createdTrack = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(8_192)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = createdTrack
                sessionId = createdTrack.audioSessionId
                if (sessionId <= 0) throw IllegalStateException("Android did not allocate an audio session ID")
                if (createdTrack.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack failed to initialize")
                }
                currentTrack = createdTrack
                currentSessionId = sessionId
                engine.attachSession(sessionId, "VERLYN Test EQ", gains, preamp, eqEnabled, limiterEnabled, balance)
                createdTrack.play()
                onStatus("Verlyn Test Beat · Session " + sessionId + " · playing")
                val blockFrames = 1_024
                var frameOffset = 0L
                val samples = ShortArray(blockFrames * 2)
                val beatFrames = sampleRate / 2
                while (currentCoroutineContext().isActive) {
                    val frames = blockFrames
                    for (i in 0 until frames) {
                        val frame = frameOffset + i.toLong()
                        val t = frame.toDouble() / sampleRate.toDouble()
                        val fadeIn = (frame.toDouble() / (sampleRate / 20)).coerceIn(0.0, 1.0)
                        val beatPhase = (frame % beatFrames).toDouble() / sampleRate.toDouble()
                        val kick = sin(2.0 * PI * (48.0 - 8.0 * beatPhase) * t) *
                            kotlin.math.exp(-beatPhase * 15.0) * 0.22
                        val subBass = sin(2.0 * PI * 55.0 * t) * 0.105 +
                            sin(2.0 * PI * 82.41 * t) * 0.045 +
                            sin(2.0 * PI * 110.0 * t) * 0.035
                        val leftChord = sin(2.0 * PI * 220.0 * t) * 0.045 +
                            sin(2.0 * PI * 277.18 * t) * 0.034 +
                            sin(2.0 * PI * 329.63 * t) * 0.029 +
                            sin(2.0 * PI * 440.0 * t) * 0.021
                        val rightChord = sin(2.0 * PI * 246.94 * t) * 0.045 +
                            sin(2.0 * PI * 311.13 * t) * 0.034 +
                            sin(2.0 * PI * 369.99 * t) * 0.029 +
                            sin(2.0 * PI * 493.88 * t) * 0.021
                        val stereoMotion = sin(2.0 * PI * 0.25 * t) * 0.018
                        val left = ((kick + subBass + leftChord + stereoMotion) * fadeIn * 0.82).coerceIn(-0.85, 0.85)
                        val right = ((kick + subBass + rightChord - stereoMotion) * fadeIn * 0.82).coerceIn(-0.85, 0.85)
                        samples[i * 2] = (left * Short.MAX_VALUE).toInt().toShort()
                        samples[i * 2 + 1] = (right * Short.MAX_VALUE).toInt().toShort()
                    }
                    val result = createdTrack.write(samples, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    if (result < 0) throw IllegalStateException("AudioTrack write failed · code " + result)
                    frameOffset += frames.toLong()
                }
                createdTrack.stop()
                onStatus("Verlyn Test Beat stopped · Session " + sessionId)
            } catch (_: CancellationException) {
                // Normal stop request; the finally block releases the session and track.
            } catch (error: Throwable) {
                onStatus("Test audio failed · " + (error.message ?: error.javaClass.simpleName))
            } finally {
                if (sessionId > 0) engine.detachSession(sessionId)
                runCatching { track?.stop() }
                runCatching { track?.release() }
                currentTrack = null
                currentSessionId = null
            }
        }
    }

    fun stop() {
        val wasPlaying = currentTrack != null
        currentJob?.cancel()
        currentJob = null
        runCatching { currentTrack?.pause() }
        runCatching { currentTrack?.flush() }
        runCatching { currentTrack?.stop() }
        runCatching { currentTrack?.release() }
        currentTrack = null
        currentSessionId?.let { engine.detachSession(it) }
        currentSessionId = null
        if (wasPlaying) onStatus("Test Beat stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

}
