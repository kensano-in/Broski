package com.verlyn.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** A short low-level chord/bass sample bound to its own AudioTrack session for DSP verification. */
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
                onStatus("Test audio playing · Session " + sessionId)
                val frameCount = sampleRate * TEST_DURATION_SECONDS
                val blockFrames = 1_024
                var frameOffset = 0
                val samples = ShortArray(blockFrames * 2)
                while (frameOffset < frameCount) {
                    val frames = minOf(blockFrames, frameCount - frameOffset)
                    for (i in 0 until frames) {
                        val frame = frameOffset + i
                        val t = frame.toDouble() / sampleRate.toDouble()
                        val fadeIn = (t / 0.08).coerceIn(0.0, 1.0)
                        val remain = (TEST_DURATION_SECONDS - t)
                        val fadeOut = (remain / 0.25).coerceIn(0.0, 1.0)
                        val envelope = minOf(fadeIn, fadeOut)
                        val bass = sin(2.0 * PI * 55.0 * t) * 0.10
                        val lowMid = sin(2.0 * PI * 110.0 * t) * 0.045
                        val chord = sin(2.0 * PI * 220.0 * t) * 0.035 +
                            sin(2.0 * PI * 277.18 * t) * 0.025 +
                            sin(2.0 * PI * 329.63 * t) * 0.025 +
                            sin(2.0 * PI * 440.0 * t) * 0.018
                        val left = ((bass + lowMid + chord) * envelope * 0.72).coerceIn(-0.8, 0.8)
                        val right = ((bass + lowMid + chord) * envelope * 0.72).coerceIn(-0.8, 0.8)
                        samples[i * 2] = (left * Short.MAX_VALUE).toInt().toShort()
                        samples[i * 2 + 1] = (right * Short.MAX_VALUE).toInt().toShort()
                    }
                    val result = createdTrack.write(samples, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    if (result < 0) throw IllegalStateException("AudioTrack write failed · code " + result)
                    frameOffset += frames
                }
                createdTrack.stop()
                onStatus("Test audio finished · Session " + sessionId)
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
        currentJob?.cancel()
        currentJob = null
        runCatching { currentTrack?.pause() }
        runCatching { currentTrack?.flush() }
        runCatching { currentTrack?.stop() }
        runCatching { currentTrack?.release() }
        currentTrack = null
        currentSessionId?.let { engine.detachSession(it) }
        currentSessionId = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TEST_DURATION_SECONDS = 6
    }
}
