package com.verlyn.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One-socket, read-only Classic RFCOMM client. Never opens an output stream. */
class ClassicRfcommEngine(
    private val onStatus: (String) -> Unit,
    private val onPacket: (UUID, ByteArray) -> Unit,
    private val onBattery: (List<Int>) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var activeUuid: UUID? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, uuid: UUID, label: String) {
        val token = generation.incrementAndGet()
        closeSocket()
        onStatus("Connecting to " + label + " over Classic RFCOMM…")
        executor.execute {
            var candidate: BluetoothSocket? = null
            var failed = false
            try {
                candidate = device.createRfcommSocketToServiceRecord(uuid)
                if (generation.get() != token) { candidate.close(); return@execute }
                socket = candidate
                activeUuid = uuid
                candidate.connect()
                if (generation.get() != token) { candidate.close(); return@execute }
                onStatus("Connected · " + label + " · read-only capture")
                readLoop(candidate, uuid, token)
            } catch (error: Throwable) {
                failed = true
                if (generation.get() == token) {
                    onStatus("RFCOMM failed · " + error.javaClass.simpleName + ": " + (error.message ?: "service unavailable"))
                }
            } finally {
                try { candidate?.close() } catch (_: Throwable) {}
                if (generation.get() == token) {
                    socket = null
                    activeUuid = null
                    if (!failed) onStatus("Disconnected")
                }
            }
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        closeSocket()
        activeUuid = null
        onStatus("Disconnected")
    }

    fun connectedUuid(): UUID? = activeUuid

    private fun closeSocket() {
        val previous = socket
        socket = null
        try { previous?.close() } catch (_: Throwable) {}
    }

    private fun readLoop(active: BluetoothSocket, uuid: UUID, token: Int) {
        val input: InputStream = active.inputStream
        val buffer = ByteArray(2048)
        val parser = FastPairParser(onBattery)
        while (generation.get() == token) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            val data = buffer.copyOf(count)
            onPacket(uuid, data)
            if (uuid == FAST_PAIR_MESSAGE_STREAM_UUID) parser.append(data)
        }
    }

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
                val length = ((bytes[cursor + 2].toInt() and 0xFF) shl 8) or (bytes[cursor + 3].toInt() and 0xFF)
                if (length > 4096) { cursor++; continue }
                if (bytes.size - cursor < 4 + length) break
                if (group == 0x03 && code == 0x03) {
                    val payload = bytes.copyOfRange(cursor + 4, cursor + 4 + length)
                    // Fast Pair battery octets use bit 7 as the charging flag and
                    // bits 0..6 as the percentage. 0x7F / 0xFF mean unknown.
                    val known = payload.map { (it.toInt() and 0xFF) and 0x7F }
                        .filter { it in 0..100 }
                    if (known.isNotEmpty()) onBattery(known)
                }
                cursor += 4 + length
            }
            pending.reset()
            if (cursor < bytes.size) pending.write(bytes, cursor, bytes.size - cursor)
            if (pending.size() > 8192) pending.reset()
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
