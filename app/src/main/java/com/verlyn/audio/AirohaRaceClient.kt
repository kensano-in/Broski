package com.verlyn.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class AirohaRaceState(
    val ready: Boolean,
    val mode: String,
    val detail: String,
    val endpoint: String = "",
    val modeCode: Int? = null
)

/** One at-a-time RACE transport. Each endpoint gets a hard four-second connect watchdog. */
class AirohaRaceClient(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationInFlight = AtomicBoolean(false)
    @Volatile private var activeSocket: BluetoothSocket? = null

    fun isBusy(): Boolean = operationInFlight.get()

    fun cancelActive() {
        closeSocket(activeSocket)
    }

    fun probe(device: BluetoothDevice, callback: (AirohaRaceState) -> Unit) {
        if (!operationInFlight.compareAndSet(false, true)) {
            callback(AirohaRaceState(false, "Unknown", "RACE busy · wait for the current RFCOMM operation"))
            return
        }
        scope.launch {
            val state = try {
                val connected = openVerifiedSocket(device)
                try {
                    AirohaRaceState(
                        true,
                        modeName(connected.modeCode),
                        "RACE status received · " + connected.endpoint,
                        connected.endpoint,
                        connected.modeCode
                    )
                } finally {
                    closeSocket(connected.socket)
                }
            } catch (error: Throwable) {
                failureState("RACE status unavailable", error)
            } finally {
                operationInFlight.set(false)
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
        if (!operationInFlight.compareAndSet(false, true)) {
            callback(AirohaRaceState(false, "Unknown", "RACE busy · wait for the current RFCOMM operation"))
            return
        }
        scope.launch {
            val state = try {
                val connected = openVerifiedSocket(device)
                try {
                    writeRequest(connected.output, SET_NOISE_OPCODE, command.payload)
                    val confirmation = awaitResponse(connected.input, GET_NOISE_OPCODE, RESPONSE_TIMEOUT_MS)
                    val code = parseModeCode(confirmation.body)
                        ?: throw IllegalStateException("Malformed 0x0901 mode response")
                    if (code != command.expectedCode) {
                        throw IllegalStateException("Headset reported " + modeName(code) + " instead of " + requestedMode)
                    }
                    AirohaRaceState(
                        true,
                        modeName(code),
                        requestedMode + " confirmed by headset · " + connected.endpoint,
                        connected.endpoint,
                        code
                    )
                } finally {
                    closeSocket(connected.socket)
                }
            } catch (error: Throwable) {
                failureState(requestedMode + " failed", error)
            } finally {
                operationInFlight.set(false)
            }
            callback(state)
        }
    }

    fun probeAllRfcommUuids(device: BluetoothDevice, callback: (String) -> Unit) {
        if (!operationInFlight.compareAndSet(false, true)) {
            callback("RFCOMM probe busy · wait for the current operation")
            return
        }
        scope.launch {
            val results = mutableListOf<String>()
            val accepted = mutableListOf<UUID>()
            try {
                for (uuid in RFCOMM_PROBE_UUIDS) {
                    val deadline = SystemClock.elapsedRealtime() + RFCOMM_PROBE_TIMEOUT_MS
                    var acceptedSocket = false
                    var lastError: Throwable? = null
                    onLog("RFCOMM UUID PROBE · " + uuid + " · budget=" + RFCOMM_PROBE_TIMEOUT_MS + "ms")
                    val factories = listOf(
                        "secure" to { device.createRfcommSocketToServiceRecord(uuid) },
                        "insecure" to { device.createInsecureRfcommSocketToServiceRecord(uuid) }
                    )
                    for ((transport, createSocket) in factories) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) break
                        var socket: BluetoothSocket? = null
                        try {
                            socket = createSocket()
                            activeSocket = socket
                            connectWithTimeout(socket, "probe " + uuid + " " + transport, remaining)
                            acceptedSocket = true
                            break
                        } catch (error: Throwable) {
                            lastError = error
                        } finally {
                            if (!acceptedSocket) closeSocket(socket)
                        }
                    }
                    if (acceptedSocket) {
                        accepted.add(uuid)
                        results.add(uuid.toString() + " · SOCKET ACCEPTED")
                        onLog("RFCOMM UUID PROBE ACCEPTED · " + uuid)
                        closeSocket(activeSocket)
                    } else {
                        val detail = lastError?.message ?: lastError?.javaClass?.simpleName ?: "timeout"
                        results.add(uuid.toString() + " · no socket (" + detail.take(90) + ")")
                        onLog("RFCOMM UUID PROBE failed · " + uuid + " · " + detail)
                    }
                }
            } finally {
                operationInFlight.set(false)
            }
            val summary = if (accepted.isEmpty()) {
                "Probe complete · no UUID accepted a socket\n" + results.joinToString("\n")
            } else {
                "Probe complete · accepted: " + accepted.joinToString(", ") + "\n" + results.joinToString("\n")
            }
            callback(summary)
        }
    }

    fun powerOff(device: BluetoothDevice, callback: (String) -> Unit) {
        if (!operationInFlight.compareAndSet(false, true)) {
            callback("RACE busy · wait for the current RFCOMM operation")
            return
        }
        scope.launch {
            val message = try {
                val connected = openVerifiedSocket(device)
                try {
                    writeRequest(connected.output, POWER_OFF_OPCODE, byteArrayOf(0x18, 0x00))
                    "Power-off command sent · " + connected.endpoint
                } finally {
                    closeSocket(connected.socket)
                }
            } catch (error: Throwable) {
                failureDetail("Power-off failed", error)
            } finally {
                operationInFlight.set(false)
            }
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
                connectWithTimeout(socket, candidate.label)
                activeSocket = socket
                onLog(if (candidate.label.contains("channel 21", true))
                    "RFCOMM Ch 21 connected · " + candidate.label
                else "RFCOMM socket connected · " + candidate.label)
                val input = socket.inputStream
                val output = socket.outputStream
                writeRequest(output, GET_NOISE_OPCODE, byteArrayOf(0x05, 0x00))
                val frame = awaitResponse(input, GET_NOISE_OPCODE, RESPONSE_TIMEOUT_MS)
                val code = parseModeCode(frame.body)
                    ?: throw IllegalStateException("Invalid 0x0901 response body " + frame.body.hex())
                onLog(
                    "RACE STATE · mode=" + modeName(code) +
                        " code=0x" + code.toString(16).uppercase() +
                        " endpoint=" + candidate.label
                )
                return ConnectedRaceSocket(socket, input, output, candidate.label, code)
            } catch (error: Throwable) {
                lastError = error
                onLog(
                    "RACE CONNECT/GET failed · " + candidate.label + " · " +
                        error.javaClass.simpleName + ": " + (error.message ?: "no response")
                )
                closeSocket(socket)
            }
        }
        val cause = lastError?.javaClass?.simpleName + ": " + (lastError?.message ?: "no response")
        throw IllegalStateException(
            "RFCOMM blocked — Force Stop the official JBL Headphones app and retry · All endpoints failed · " + cause,
            lastError
        )
    }

    @SuppressLint("MissingPermission")
    private fun socketCandidates(device: BluetoothDevice): List<SocketCandidate> = listOf(
        SocketCandidate("Airoha RACE SDP UUID " + AIROHA_SDP_RACE_UUID + " · secure") { device.createRfcommSocketToServiceRecord(AIROHA_SDP_RACE_UUID) },
        SocketCandidate("Airoha RACE SDP UUID " + AIROHA_SDP_RACE_UUID + " · insecure") { device.createInsecureRfcommSocketToServiceRecord(AIROHA_SDP_RACE_UUID) },
        SocketCandidate("RFCOMM channel 21 · insecure") { createChannelSocket(device, true) },
        SocketCandidate("RFCOMM channel 21 · secure") { createChannelSocket(device, false) },
        SocketCandidate("RACE RFCOMM UUID " + RACE_UUID + " · secure") { device.createRfcommSocketToServiceRecord(RACE_UUID) },
        SocketCandidate("RACE RFCOMM UUID " + RACE_UUID + " · insecure") { device.createInsecureRfcommSocketToServiceRecord(RACE_UUID) },
        SocketCandidate("JBL Vendor SPP " + JBL_VENDOR_UUID + " · secure") { device.createRfcommSocketToServiceRecord(JBL_VENDOR_UUID) },
        SocketCandidate("JBL Vendor SPP " + JBL_VENDOR_UUID + " · insecure") { device.createInsecureRfcommSocketToServiceRecord(JBL_VENDOR_UUID) },
        SocketCandidate("Harman Classic SPP " + HARMAN_CLASSIC_SPP_UUID + " · secure") { device.createRfcommSocketToServiceRecord(HARMAN_CLASSIC_SPP_UUID) },
        SocketCandidate("Harman Classic SPP " + HARMAN_CLASSIC_SPP_UUID + " · insecure") { device.createInsecureRfcommSocketToServiceRecord(HARMAN_CLASSIC_SPP_UUID) }
    )

    @SuppressLint("MissingPermission")
    private suspend fun connectWithTimeout(
        socket: BluetoothSocket,
        label: String,
        timeoutMs: Long = CONNECT_TIMEOUT_MS
    ) {
        runCatching { bluetoothAdapter?.cancelDiscovery() }
        val connectJob = CoroutineScope(Dispatchers.IO).async { socket.connect() }
        var connected = false
        try {
            connected = withTimeoutOrNull(timeoutMs) {
                connectJob.await()
                true
            } ?: false
            if (!connected) throw IOException("RFCOMM connect timeout after " + timeoutMs + "ms · " + label)
        } finally {
            if (!connected) {
                runCatching { socket.close() }
                connectJob.cancel()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createChannelSocket(device: BluetoothDevice, insecure: Boolean): BluetoothSocket {
        val methodName = if (insecure) "createInsecureRfcommSocket" else "createRfcommSocket"
        val method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType!!)
        return method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
    }

    private fun writeRequest(output: OutputStream, opcode: Int, payload: ByteArray) {
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
                onLog(
                    "RACE FRAME type=0x" + type.toString(16).uppercase() +
                        " op=0x" + opcode.toString(16).uppercase().padStart(4, '0') +
                        " body=" + body.hex()
                )
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

    private fun parseModeCode(body: ByteArray): Int? =
        if (body.size < 4) null else body[3].toInt() and 0xFF

    private fun modeName(code: Int): String = when (code) {
        0x00 -> "Off"
        0x01 -> "ANC"
        0x09 -> "TalkThru"
        0x0A -> "Ambient Aware"
        else -> "Unknown (0x" + code.toString(16).uppercase() + ")"
    }

    private fun failureState(prefix: String, error: Throwable): AirohaRaceState =
        AirohaRaceState(false, "Unknown", failureDetail(prefix, error))

    private fun failureDetail(prefix: String, error: Throwable): String {
        val cause = error.cause ?: error
        val message = cause.message ?: cause.javaClass.simpleName
        onLog(prefix + " · " + cause.javaClass.simpleName + ": " + message)
        return if (message.contains("RFCOMM blocked", true)) message
        else "RFCOMM blocked — Force Stop the official JBL Headphones app and retry · " + prefix + " · " + cause.javaClass.simpleName + ": " + message
    }

    private fun closeSocket(socket: BluetoothSocket?) {
        if (activeSocket === socket) activeSocket = null
        runCatching { socket?.close() }
    }

    fun shutdown() {
        closeSocket(activeSocket)
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
        private const val CONNECT_TIMEOUT_MS = 4_000L
        private const val RFCOMM_PROBE_TIMEOUT_MS = 2_500L
        private const val RESPONSE_TIMEOUT_MS = 3_000L
        private const val GET_NOISE_OPCODE = 0x0901
        private const val SET_NOISE_OPCODE = 0x0E06
        private const val POWER_OFF_OPCODE = 0x1101
        val AIROHA_SDP_RACE_UUID: UUID = UUID.fromString("5052494d-2dab-0341-6972-6f6861424c45")
        private val RACE_UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")
        val JBL_VENDOR_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34ff")
        val HARMAN_CLASSIC_SPP_UUID = UUID.fromString("f8d1fbe4-7966-4334-8024-ff96c9330e15")
        val HARMAN_VOICE_SPP_UUID = UUID.fromString("81c2e72a-0591-443e-a1ff-05f988593351")
        private val RFCOMM_PROBE_UUIDS = listOf(
            AIROHA_SDP_RACE_UUID,
            RACE_UUID,
            JBL_VENDOR_UUID,
            HARMAN_CLASSIC_SPP_UUID,
            HARMAN_VOICE_SPP_UUID
        )
        private val modeCommands = mapOf(
            "ANC" to ModeCommand(byteArrayOf(0x00, 0x0A, 0x01), 0x01),
            "Ambient Aware" to ModeCommand(byteArrayOf(0x00, 0x0A, 0x0A), 0x0A),
            "TalkThru" to ModeCommand(byteArrayOf(0x00, 0x0A, 0x09), 0x09),
            "Off" to ModeCommand(byteArrayOf(0x00, 0x0B), 0x00)
        )
    }
}
