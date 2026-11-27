package com.verlyn.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AirohaRaceState(
    val ready: Boolean,
    val mode: String,
    val chipName: String,
    val supportedModes: List<String>,
    val detail: String
)

/** A deliberately restricted JBL Tune 770NC RACE client: identity/status reads and ANC modes only. */
class AirohaRaceClient {
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var active: BluetoothSocket? = null

    fun probe(device: BluetoothDevice, callback: (AirohaRaceState) -> Unit) {
        io.execute {
            val result = runCatching {
                withSocket(device) { input, output ->
                    val chip = readChip(input, output)
                    val layout = layoutOf(chip)
                    val filter = readFilter(input, output)
                    val modes = supported(layout)
                    AirohaRaceState(layout != Layout.UNKNOWN && filter != null, modeOf(filter, layout) ?: "Unknown", chip, modes,
                        if (filter == null) "No valid noise-control status reply" else "Headset status read")
                }
            }.getOrElse { AirohaRaceState(false, "Unavailable", "Unknown", emptyList(), it.message ?: "RACE probe failed") }
            callback(result)
        }
    }

    fun setNoiseMode(device: BluetoothDevice, mode: String, callback: (AirohaRaceState) -> Unit) {
        io.execute {
            val result = runCatching {
                withSocket(device) { input, output ->
                    val chip = readChip(input, output)
                    val layout = layoutOf(chip)
                    val filter = when (mode) {
                        "ANC" -> 1
                        "Ambient Aware" -> if (layout == Layout.AB156X) 10 else null
                        "TalkThru" -> if (layout == Layout.AB156X) 9 else null
                        "Off" -> 0
                        else -> null
                    } ?: throw IllegalArgumentException("Mode not supported by this chipset")
                    val payload = when (mode) {
                        "Off" -> byteArrayOf(0x00, 0x0B)
                        "ANC" -> when (layout) {
                            Layout.AB156X -> byteArrayOf(0x00, 0x0A, 0x01, 0x00)
                            Layout.AB155X -> byteArrayOf(0x00, 0x0A, 0x01)
                            else -> error("Unknown chipset layout")
                        }
                        "Ambient Aware" -> byteArrayOf(0x00, 0x0A, 0x0A, 0x04)
                        "TalkThru" -> byteArrayOf(0x00, 0x0A, 0x09, 0x04)
                        else -> error("Unsupported noise mode")
                    }
                    val ack = request(input, output, 0x0E06, payload)
                    require(ack.size >= 2 && ack[0].toInt() == 0 && (ack[1].toInt() and 0xFF) == (payload[1].toInt() and 0xFF)) {
                        "Headset rejected the command"
                    }
                    val actual = readFilter(input, output)
                    require(actual == filter) { "Headset did not confirm the requested mode" }
                    AirohaRaceState(true, modeOf(actual, layout) ?: mode, chip, supported(layout), "Confirmed by headset status reply")
                }
            }.getOrElse { AirohaRaceState(false, "Unavailable", "Unknown", emptyList(), it.message ?: "Noise-control operation failed") }
            callback(result)
        }
    }

    @SuppressLint("MissingPermission")
    private fun <T> withSocket(device: BluetoothDevice, action: (InputStream, OutputStream) -> T): T {
        var socket: BluetoothSocket? = null
        try {
            val secure = device.createRfcommSocketToServiceRecord(RACE_UUID)
            try {
                secure.connect()
                socket = secure
            } catch (e: Throwable) {
                runCatching { secure.close() }
                val insecure = device.createInsecureRfcommSocketToServiceRecord(RACE_UUID)
                insecure.connect()
                socket = insecure
            }
            val connected = socket ?: error("RFCOMM socket was not created")
            active = connected
            return action(connected.inputStream, connected.outputStream)
        } finally {
            active = null
            runCatching { socket?.close() }
        }
    }

    private fun readChip(input: InputStream, output: OutputStream): String {
        val body = request(input, output, 0x0A00, byteArrayOf(0x02, 0x10, 0xE8.toByte(), 0x03))
        require(body.size >= 3) { "Short chipset identity reply" }
        val n = u16(body, 0)
        require(n in 1..256 && body.size >= n + 2) { "Invalid chipset identity length" }
        return body.copyOfRange(2, n + 2).toString(Charsets.US_ASCII).trimEnd('\u0000')
    }

    private fun readFilter(input: InputStream, output: OutputStream): Int? {
        val body = request(input, output, 0x0901, byteArrayOf(0x05, 0x00))
        if (body.size < 4 || u16(body, 0) != 5 || body[2].toInt() != 0) return null
        return body[3].toInt() and 0xFF
    }

    private fun request(input: InputStream, output: OutputStream, opcode: Int, payload: ByteArray): ByteArray {
        val n = payload.size + 2
        val frame = ByteArray(6 + payload.size)
        frame[0] = 0x05
        frame[1] = 0x5A
        frame[2] = n.toByte()
        frame[3] = (n shr 8).toByte()
        frame[4] = opcode.toByte()
        frame[5] = (opcode shr 8).toByte()
        payload.copyInto(frame, 6)
        output.write(frame)
        output.flush()
        return readReply(input, opcode, 3_500L)
    }

    private fun readReply(input: InputStream, expectedOpcode: Int, timeoutMs: Long): ByteArray {
        val pending = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val buffer = ByteArray(1024)
        while (System.nanoTime() < deadline) {
            val available = input.available()
            if (available == 0) {
                Thread.sleep(15)
                continue
            }
            val count = input.read(buffer, 0, minOf(available, buffer.size))
            if (count <= 0) continue
            pending.write(buffer, 0, count)
            val bytes = pending.toByteArray()
            var cursor = 0
            while (bytes.size - cursor >= 6) {
                if ((bytes[cursor].toInt() and 0xFF) != 0x05) { cursor++; continue }
                val length = u16(bytes, cursor + 2)
                if (length !in 2..2048) { cursor++; continue }
                val end = cursor + 6 + length
                if (end > bytes.size) break
                val type = bytes[cursor + 1].toInt() and 0xFF
                val opcode = u16(bytes, cursor + 4)
                val body = bytes.copyOfRange(cursor + 6, end)
                cursor = end
                if ((type == 0x5B || type == 0x5D) && opcode == expectedOpcode) return body
            }
            pending.reset()
            if (cursor < bytes.size) pending.write(bytes, cursor, bytes.size - cursor)
            if (pending.size() > 4096) pending.reset()
        }
        throw IllegalStateException("RACE response timeout for 0x" + expectedOpcode.toString(16))
    }

    private enum class Layout { UNKNOWN, AB155X, AB156X }

    private fun layoutOf(chip: String): Layout {
        val s = chip.trim().uppercase()
        return when {
            s.isBlank() || s.contains("AB1562") -> Layout.UNKNOWN
            s.startsWith("AB155") -> Layout.AB155X
            s.startsWith("AB158") || s.startsWith("AB157") || s.startsWith("MT") ||
                s.contains("AB1565") || s.contains("AB1568") -> Layout.AB156X
            else -> Layout.UNKNOWN
        }
    }

    private fun supported(layout: Layout): List<String> = when (layout) {
        Layout.AB156X -> listOf("ANC", "Ambient Aware", "TalkThru", "Off")
        Layout.AB155X -> listOf("ANC", "Off")
        Layout.UNKNOWN -> emptyList()
    }

    private fun modeOf(filter: Int?, layout: Layout): String? = when (filter) {
        0 -> "Off"
        filter != null && filter in 1..4 -> "ANC"
        9 -> if (layout == Layout.AB156X) "TalkThru" else null
        10 -> if (layout == Layout.AB156X) "Ambient Aware" else null
        else -> null
    }

    private fun u16(bytes: ByteArray, i: Int): Int =
        (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)

    fun shutdown() {
        runCatching { active?.close() }
        io.shutdownNow()
    }

    companion object {
        private val RACE_UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")
    }
}
