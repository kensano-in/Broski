package com.verlyn.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class FastPairInfo(
    val firmwareVersion: String? = null,
    val modelId: String? = null
)

/** Independent Classic RFCOMM client for the Google Fast Pair Message Stream. */
class ClassicRfcommEngine(
    private val onStatus: (String) -> Unit,
    private val onPacket: (UUID, ByteArray) -> Unit,
    private val onBattery: (List<Int>) -> Unit,
    private val onFastPairInfo: (FastPairInfo) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicInteger(0)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var activeUuid: UUID? = null
    @Volatile private var requestedUuid: UUID? = null
    @Volatile private var connectionJob: Job? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, uuid: UUID, label: String) {
        if (connectedUuid() == uuid) return
        val token = generation.incrementAndGet()
        requestedUuid = uuid
        runCatching { socket?.close() }
        socket = null
        connectionJob?.cancel()
        onStatus("Connecting to " + label + " over Classic RFCOMM")
        connectionJob = scope.launch {
            var candidate: BluetoothSocket? = null
            var failed = false
            var peerClosed = false
            try {
                candidate = withContext(Dispatchers.IO) {
                    val secure = device.createRfcommSocketToServiceRecord(uuid)
                    try {
                        secure.connect()
                        secure
                    } catch (secureError: Throwable) {
                        runCatching { secure.close() }
                        device.createInsecureRfcommSocketToServiceRecord(uuid).also { it.connect() }
                    }
                }
                val connected = candidate ?: error("RFCOMM socket creation failed")
                if (generation.get() != token) {
                    connected.close()
                    return@launch
                }
                socket = connected
                activeUuid = uuid
                onStatus("Connected · " + label)
                readLoop(connected, uuid, token) { peerClosed = it }
            } catch (error: Throwable) {
                failed = true
                if (generation.get() == token) {
                    onStatus("RFCOMM failed · " + error.javaClass.simpleName + ": " + (error.message ?: "service unavailable"))
                }
            } finally {
                runCatching { candidate?.close() }
                if (generation.get() == token) {
                    socket = null
                    activeUuid = null
                    requestedUuid = null
                    if (!failed && peerClosed) onStatus("Remote closed RFCOMM stream (EOF -1)")
                    else if (!failed) onStatus("Disconnected")
                }
            }
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        runCatching { socket?.close() }
        socket = null
        activeUuid = null
        requestedUuid = null
        onStatus("Disconnected")
    }

    fun connectedUuid(): UUID? = activeUuid ?: requestedUuid

    private suspend fun readLoop(active: BluetoothSocket, uuid: UUID, token: Int, onPeerClosed: (Boolean) -> Unit) {
        val input: InputStream = active.inputStream
        val buffer = ByteArray(2048)
        val parser = FastPairParser(onBattery, onFastPairInfo)
        while (generation.get() == token) {
            val count = input.read(buffer)
            if (count < 0) {
                onPeerClosed(true)
                return
            }
            if (count == 0) continue
            val data = buffer.copyOf(count)
            onPacket(uuid, data)
            if (uuid == FAST_PAIR_MESSAGE_STREAM_UUID) parser.append(data)
        }
    }

    /**
     * Message Stream TLV: group(1), code(1), payload length(2, big-endian), payload.
     * Partial frames remain buffered and all concatenated frames in each read are decoded.
     */
    private class FastPairParser(
        private val onBattery: (List<Int>) -> Unit,
        private val onInfo: (FastPairInfo) -> Unit
    ) {
        private val pending = ByteArrayOutputStream()

        @Synchronized
        fun append(chunk: ByteArray) {
            pending.write(chunk)
            val bytes = pending.toByteArray()
            var cursor = 0
            while (bytes.size - cursor >= HEADER_SIZE) {
                val group = bytes[cursor].toInt() and 0xFF
                val code = bytes[cursor + 1].toInt() and 0xFF
                val length = ((bytes[cursor + 2].toInt() and 0xFF) shl 8) or
                    (bytes[cursor + 3].toInt() and 0xFF)
                if (group > 0x0F || length > MAX_PAYLOAD) {
                    cursor++
                    continue
                }
                val frameEnd = cursor + HEADER_SIZE + length
                if (frameEnd > bytes.size) break
                val payload = bytes.copyOfRange(cursor + HEADER_SIZE, frameEnd)

                if (group == FAST_PAIR_GROUP) {
                    when (code) {
                        BATTERY_UPDATED_CODE -> {
                            val levels = buildList {
                                payload.forEach { encoded ->
                                    val raw = encoded.toInt() and 0xFF
                                    if (raw != UNKNOWN_LEVEL && raw != UNKNOWN_LEVEL_WITH_CHARGE) {
                                        val percent = raw and 0x7F
                                        if (percent in 0..100) add(percent)
                                    }
                                }
                            }
                            if (levels.isNotEmpty()) onBattery(levels)
                        }
                        MODEL_ID_CODE -> {
                            if (payload.isNotEmpty()) onInfo(FastPairInfo(modelId = payload.toHex()))
                        }
                        FIRMWARE_VERSION_CODE -> {
                            val version = payload.toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
                            if (version != null) onInfo(FastPairInfo(firmwareVersion = version))
                        }
                    }
                }
                cursor = frameEnd
            }
            pending.reset()
            if (cursor < bytes.size) pending.write(bytes, cursor, bytes.size - cursor)
            if (pending.size() > MAX_BUFFER) pending.reset()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        companion object {
            private const val HEADER_SIZE = 4
            private const val MAX_PAYLOAD = 4096
            private const val MAX_BUFFER = 8192
            private const val FAST_PAIR_GROUP = 0x03
            private const val BATTERY_UPDATED_CODE = 0x03
            private const val MODEL_ID_CODE = 0x01
            private const val FIRMWARE_VERSION_CODE = 0x09
            private const val UNKNOWN_LEVEL = 0x7F
            private const val UNKNOWN_LEVEL_WITH_CHARGE = 0xFF
        }
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    companion object {
        val FAST_PAIR_MESSAGE_STREAM_UUID: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")
        val JBL_VENDOR_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34ff")
        val AIROHA_RFCOMM_CANDIDATE_UUID: UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")
    }
}
