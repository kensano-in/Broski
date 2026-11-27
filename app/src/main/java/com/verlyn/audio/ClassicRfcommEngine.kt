package com.verlyn.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Serial RFCOMM reader with secure/insecure socket fallback and incremental Fast Pair framing. */
class ClassicRfcommEngine(
    private val onStatus: (String) -> Unit,
    private val onPacket: (UUID, ByteArray) -> Unit,
    private val onBattery: (List<Int>) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var activeUuid: UUID? = null
    @Volatile private var requestedUuid: UUID? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, uuid: UUID, label: String) {
        if (connectedUuid() == uuid) return
        val token = generation.incrementAndGet()
        requestedUuid = uuid
        closeSocket()
        onStatus("Connecting to " + label + " over Classic RFCOMM")
        executor.execute {
            var candidate: BluetoothSocket? = null
            var failed = false
            var peerClosed = false
            try {
                val secure = device.createRfcommSocketToServiceRecord(uuid)
                try {
                    secure.connect()
                    candidate = secure
                } catch (secureError: Throwable) {
                    runCatching { secure.close() }
                    candidate = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    candidate.connect()
                }
                val connected = candidate ?: error("RFCOMM socket creation failed")
                if (generation.get() != token) { connected.close(); return@execute }
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
        closeSocket()
        activeUuid = null
        requestedUuid = null
        onStatus("Disconnected")
    }

    fun connectedUuid(): UUID? = activeUuid ?: requestedUuid

    private fun closeSocket() {
        val previous = socket
        socket = null
        runCatching { previous?.close() }
    }

    private fun readLoop(active: BluetoothSocket, uuid: UUID, token: Int, onPeerClosed: (Boolean) -> Unit) {
        val input: InputStream = active.inputStream
        val buffer = ByteArray(2048)
        val parser = FastPairParser(onBattery)
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
     * Fast Pair Message Stream: [group:1][code:1][payload length:2, big-endian][payload].
     * Carries partial frames across reads and iterates over every concatenated frame.
     */
    private class FastPairParser(private val onBattery: (List<Int>) -> Unit) {
        private val pending = ByteArrayOutputStream()

        @Synchronized
        fun append(chunk: ByteArray) {
            pending.write(chunk)
            val bytes = pending.toByteArray()
            var cursor = 0
            while (bytes.size - cursor >= 4) {
                val group = bytes[cursor].toInt() and 0xFF
                val code = bytes[cursor + 1].toInt() and 0xFF
                val length = ((bytes[cursor + 2].toInt() and 0xFF) shl 8) or
                    (bytes[cursor + 3].toInt() and 0xFF)

                // Fast Pair message groups are small. Skip a corrupt prefix instead of
                // trusting an implausible header and pinning the stream parser forever.
                if (group > 0x0F || length > MAX_PAYLOAD) {
                    cursor++
                    continue
                }
                val frameEnd = cursor + HEADER_SIZE + length
                if (frameEnd > bytes.size) break

                if (group == BATTERY_GROUP && code == BATTERY_UPDATED_CODE && length > 0) {
                    val levels = ArrayList<Int>(length)
                    for (index in cursor + HEADER_SIZE until frameEnd) {
                        val raw = bytes[index].toInt() and 0xFF
                        if (raw == UNKNOWN_LEVEL || raw == UNKNOWN_LEVEL_WITH_CHARGE) continue
                        val percent = raw and 0x7F // high bit is the charging flag
                        if (percent in 0..100) levels.add(percent)
                    }
                    if (levels.isNotEmpty()) onBattery(levels)
                }
                cursor = frameEnd
            }
            pending.reset()
            if (cursor < bytes.size) pending.write(bytes, cursor, bytes.size - cursor)
            if (pending.size() > MAX_BUFFER) pending.reset()
        }

        companion object {
            private const val HEADER_SIZE = 4
            private const val MAX_PAYLOAD = 4096
            private const val MAX_BUFFER = 8192
            private const val BATTERY_GROUP = 0x03
            private const val BATTERY_UPDATED_CODE = 0x03
            private const val UNKNOWN_LEVEL = 0x7F
            private const val UNKNOWN_LEVEL_WITH_CHARGE = 0xFF
        }
    }

    fun shutdown() {
        disconnect()
        executor.shutdownNow()
    }

    companion object {
        val FAST_PAIR_MESSAGE_STREAM_UUID: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")
        val JBL_VENDOR_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34ff")
        val AIROHA_RFCOMM_CANDIDATE_UUID: UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")
    }
}
