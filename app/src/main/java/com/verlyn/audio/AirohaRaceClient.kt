package com.verlyn.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class AirohaRaceState(
    val ready: Boolean,
    val mode: String,
    val detail: String,
    val endpoint: String = "",
    val modeCode: Int? = null
)

class AirohaRaceClient(private val onLog: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    @Volatile private var activeSocket: BluetoothSocket? = null

    fun probe(device: BluetoothDevice, callback: (AirohaRaceState) -> Unit) {
        scope.launch {
            val state = runCatching {
                commandMutex.withLock {
                    val connected = openVerifiedSocket(device)
                    try {
                        AirohaRaceState(true, modeName(connected.modeCode), "RACE status received · " + connected.endpoint,
                            connected.endpoint, connected.modeCode)
                    } finally {
                        closeSocket(connected.socket)
                    }
                }
            }.getOrElse {
                AirohaRaceState(false, "Unknown", "RACE status unavailable · " + (it.message ?: "no response"))
            }
            callback(state)
        }
    }

    fun setNoiseMode(device: BluetoothDevice, requestedMode: String, callback: (AirohaRaceState) -> Unit) {
        val command = modeCommands[requestedMode]
        if (command == null) {
            callback(AirohaRaceState(false, "Unknown", "Unsupported noise-control action"))
            return
        }
        scope.launch {
            val state = runCatching {
                commandMutex.withLock {
                    val connected = openVerifiedSocket(device)
                    try {
                        writeRequest(connected.output, SET_NOISE_OPCODE, command.payload)
                        val confirmation = awaitResponse(connected.input, GET_NOISE_OPCODE, RESPONSE_TIMEOUT_MS)
                        val code = parseModeCode(confirmation.body)
                            ?: throw IllegalStateException("Malformed 0x0901 mode response")
                        if (code != command.expectedCode) {
                            throw IllegalStateException("Headset reported " + modeName(code) + " instead of " + requestedMode)
                        }
                        AirohaRaceState(true, modeName(code), requestedMode + " confirmed by headset · " + connected.endpoint,
                            connected.endpoint, code)
                    } finally {
                        closeSocket(connected.socket)
                    }
                }
            }.getOrElse {
                AirohaRaceState(false, "Unknown", requestedMode + " failed · " + (it.message ?: "no response"))
            }
            callback(state)
        }
    }

    fun powerOff(device: BluetoothDevice, callback: (String) -> Unit) {
        scope.launch {
            val message = runCatching {
                commandMutex.withLock {
                    val connected = openVerifiedSocket(device)
                    try {
                        writeRequest(connected.output, POWER_OFF_OPCODE, byteArrayOf(0x18, 0x00))
                        "Power-off command sent · " + connected.endpoint
                    } finally {
                        closeSocket(connected.socket)
                    }
                }
            }.getOrElse { "Power-off failed · " + (it.message ?: "no response") }
            callback(message)
        }
    }

    private data class ConnectedRaceSocket(
        val socket: BluetoothSocket,
        val input: InputStream,
        val output: OutputStream,
        val endpoint: String,
        val modeCode: Int
    )
    private data class SocketCandidate(val label: String, val create: () -> BluetoothSocket)
    private data class RaceFrame(val type: Int, val opcode: Int, val body: ByteArray)
    private data class ModeCommand(val payload: ByteArray, val expectedCode: Int)

    @SuppressLint("MissingPermission")
    private suspend fun openVerifiedSocket(device: BluetoothDevice): ConnectedRaceSocket {
        var lastError: Throwable? = null
        for (candidate in socketCandidates(device)) {
            var socket: BluetoothSocket? = null
            try {
                onLog("RACE CONNECT attempt · " + candidate.label)
                socket = candidate.create()
                socket.connect()
                activeSocket = socket
                onLog("RACE CONNECT success · " + candidate.label)
                val input = socket.inputStream
                val output = socket.outputStream
                writeRequest(output, GET_NOISE_OPCODE, byteArrayOf(0x05, 0x00))
                val frame = awaitResponse(input, GET_NOISE_OPCODE, RESPONSE_TIMEOUT_MS)
                val code = parseModeCode(frame.body)
                    ?: throw IllegalStateException("Invalid 0x0901 response body " + frame.body.hex())
                onLog("RACE STATE · mode=" + modeName(code) + " code=0x" + code.toString(16).uppercase() + " endpoint=" + candidate.label)
                return ConnectedRaceSocket(socket, input, output, candidate.label, code)
            } catch (error: Throwable) {
                lastError = error
                onLog("RACE CONNECT/GET failed · " + candidate.label + " · " + error.javaClass.simpleName + ": " + (error.message ?: "no response"))
                closeSocket(socket)
            }
        }
        throw IllegalStateException("No RACE endpoint returned a valid 0x0901 status", lastError)
    }

    @SuppressLint("MissingPermission")
    private fun socketCandidates(device: BluetoothDevice): List<SocketCandidate> = listOf(
        SocketCandidate("RACE UUID " + RACE_UUID + " · secure") { device.createRfcommSocketToServiceRecord(RACE_UUID) },
        SocketCandidate("RACE UUID " + RACE_UUID + " · insecure") { device.createInsecureRfcommSocketToServiceRecord(RACE_UUID) },
        SocketCandidate("PRIM UUID " + PRIM_UUID + " · secure") { device.createRfcommSocketToServiceRecord(PRIM_UUID) },
        SocketCandidate("PRIM UUID " + PRIM_UUID + " · insecure") { device.createInsecureRfcommSocketToServiceRecord(PRIM_UUID) },
        SocketCandidate("RFCOMM channel 21 · secure reflection") { createChannelSocket(device, false) },
        SocketCandidate("RFCOMM channel 21 · insecure reflection") { createChannelSocket(device, true) }
    )

    @SuppressLint("MissingPermission")
    private fun createChannelSocket(device: BluetoothDevice, insecure: Boolean): BluetoothSocket {
        val methodName = if (insecure) "createInsecureRfcommSocket" else "createRfcommSocket"
        val method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType!!)
        return method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
    }

    private suspend fun writeRequest(output: OutputStream, opcode: Int, payload: ByteArray) {
        val frame = buildRequest(opcode, payload)
        onLog("RACE TX op=0x" + opcode.toString(16).uppercase().padStart(4, '0') + " · " + frame.hex())
        output.write(frame)
        output.flush()
    }

    private fun buildRequest(opcode: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return ByteArray(6 + payload.size).also { frame ->
            frame[0] = 0x05
            frame[1] = 0x5A
            frame[2] = (length and 0xFF).toByte()
            frame[3] = ((length shr 8) and 0xFF).toByte()
            frame[4] = (opcode and 0xFF).toByte()
            frame[5] = ((opcode shr 8) and 0xFF).toByte()
            payload.copyInto(frame, 6)
        }
    }

    private suspend fun awaitResponse(input: InputStream, expectedOpcode: Int, timeoutMs: Long): RaceFrame {
        val pending = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val available = input.available()
            if (available <= 0) {
                delay(12L)
                continue
            }
            val count = input.read(buffer, 0, minOf(available, buffer.size))
            if (count < 0) throw IllegalStateException("RACE stream closed while waiting for 0x" + expectedOpcode.toString(16))
            if (count == 0) continue
            val chunk = buffer.copyOf(count)
            onLog("RACE RX · " + chunk.hex())
            pending.write(chunk)
            val bytes = pending.toByteArray()
            var cursor = 0
            var matched: RaceFrame? = null
            while (bytes.size - cursor >= FRAME_HEADER_SIZE) {
                if ((bytes[cursor].toInt() and 0xFF) != FRAME_MAGIC) {
                    cursor++
                    continue
                }
                val length = u16le(bytes, cursor + 2)
                if (length !in 2..MAX_FRAME_BODY) {
                    cursor++
                    continue
                }
                // RACE length counts opcode + payload; total frame size is 4 + length.
                val frameEnd = cursor + 4 + length
                if (frameEnd > bytes.size) break
                val type = bytes[cursor + 1].toInt() and 0xFF
                val opcode = u16le(bytes, cursor + 4)
                val body = bytes.copyOfRange(cursor + 6, frameEnd)
                cursor = frameEnd
                onLog("RACE FRAME type=0x" + type.toString(16).uppercase() +
                    " op=0x" + opcode.toString(16).uppercase().padStart(4, '0') + " body=" + body.hex())
                if ((type == RESPONSE_TYPE || type == INDICATION_TYPE) && opcode == expectedOpcode) {
                    matched = RaceFrame(type, opcode, body)
                    break
                }
            }
            pending.reset()
            if (matched != null) return matched
            if (cursor < bytes.size) pending.write(bytes, cursor, bytes.size - cursor)
            if (pending.size() > MAX_PENDING_BYTES) pending.reset()
        }
        throw IllegalStateException("RACE response timeout for 0x" + expectedOpcode.toString(16))
    }

    private fun parseModeCode(body: ByteArray): Int? {
        if (body.size < 4) return null
        return body[3].toInt() and 0xFF
    }

    private fun modeName(code: Int): String = when (code) {
        0x00 -> "Off"
        0x01 -> "ANC"
        0x09 -> "TalkThru"
        0x0A -> "Ambient Aware"
        else -> "Unknown (0x" + code.toString(16).uppercase() + ")"
    }

    private fun closeSocket(socket: BluetoothSocket?) {
        if (activeSocket === socket) activeSocket = null
        runCatching { socket?.close() }
    }

    fun shutdown() {
        runCatching { activeSocket?.close() }
        scope.cancel()
    }

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val FRAME_MAGIC = 0x05
        private const val RESPONSE_TYPE = 0x5B
        private const val INDICATION_TYPE = 0x5D
        private const val FRAME_HEADER_SIZE = 6
        private const val MAX_FRAME_BODY = 2048
        private const val MAX_PENDING_BYTES = 4096
        private const val RFCOMM_CHANNEL = 21
        private const val RESPONSE_TIMEOUT_MS = 3_000L
        private const val GET_NOISE_OPCODE = 0x0901
        private const val SET_NOISE_OPCODE = 0x0E06
        private const val POWER_OFF_OPCODE = 0x1101
        private val RACE_UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")
        private val PRIM_UUID = UUID.fromString("5052494d-2dab-0341-6972-6f6861424c45")
        private val modeCommands = mapOf(
            "ANC" to ModeCommand(byteArrayOf(0x00, 0x0A, 0x01), 0x01),
            "Ambient Aware" to ModeCommand(byteArrayOf(0x00, 0x0A, 0x0A), 0x0A),
            "TalkThru" to ModeCommand(byteArrayOf(0x00, 0x0A, 0x09), 0x09),
            "Off" to ModeCommand(byteArrayOf(0x00, 0x0B), 0x00)
        )
    }
}
