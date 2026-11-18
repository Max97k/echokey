package com.akeyboard.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

enum class BridgeKey {
    ENTER,
    BACKSPACE
}

@Serializable
data class BridgeCommand(
    val type: String,
    val value: String? = null,
    val key: String? = null
) {
    companion object {
        fun text(value: String) = BridgeCommand(type = "text", value = value)
        fun key(key: BridgeKey) = BridgeCommand(type = "key", key = key.name)
    }
}

@Serializable
data class BridgeResponse(
    val ok: Boolean,
    val err: String? = null
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class Connected(val deviceName: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class BridgeDeliveryException(
    val code: String,
    message: String
) : Exception(message)

class BluetoothBridgeClient(
    private val context: Context
) {
    private val ioMutex = Mutex()
    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun hasConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices?.toList().orEmpty()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = ioMutex.withLock {
        disconnectLocked()
        if (!hasConnectPermission()) {
            val err = SecurityException("BLUETOOTH_CONNECT permission not granted")
            _connectionState.value = ConnectionState.Error(err.message ?: "Permission denied")
            return Result.failure(err)
        }
        val name = device.name ?: device.address
        _connectionState.value = ConnectionState.Connecting(name)

        return withContext(Dispatchers.IO) {
            try {
                val rfcomm = device.createRfcommSocketToServiceRecord(BRIDGE_UUID)
                rfcomm.connect()
                socket = rfcomm
                reader = BufferedReader(InputStreamReader(rfcomm.inputStream, Charsets.UTF_8))
                _connectionState.value = ConnectionState.Connected(name)
                Result.success(Unit)
            } catch (e: IOException) {
                disconnectLocked()
                _connectionState.value = ConnectionState.Error(e.message ?: "Bluetooth connect failed")
                Result.failure(e)
            }
        }
    }

    suspend fun sendCommand(command: BridgeCommand): Result<Unit> = ioMutex.withLock {
        val activeSocket = socket
        val activeReader = reader
        if (activeSocket == null || activeReader == null || !activeSocket.isConnected) {
            _connectionState.value = ConnectionState.Disconnected
            return Result.failure(IOException("Not connected"))
        }

        return withContext(Dispatchers.IO) {
            try {
                val line = bridgeJson.encodeToString(command) + "\n"
                val out = activeSocket.outputStream
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()

                val ackLine = coroutineScope {
                    val readJob = async(Dispatchers.IO) { activeReader.readLine() }
                    withTimeoutOrNull(ACK_TIMEOUT_MS) { readJob.await() }
                }
                if (ackLine == null) {
                    disconnectLocked()
                    _connectionState.value = ConnectionState.Error("Timed out waiting for PC ack")
                    return@withContext Result.failure(IOException("Ack timeout"))
                }

                val response = bridgeJson.decodeFromString<BridgeResponse>(ackLine)
                if (response.ok) {
                    Result.success(Unit)
                } else {
                    val code = response.err ?: "unknown"
                    Result.failure(BridgeDeliveryException(code, "PC rejected input: $code"))
                }
            } catch (e: Exception) {
                disconnectLocked()
                _connectionState.value = ConnectionState.Error(e.message ?: "Bluetooth write failed")
                Result.failure(e)
            }
        }
    }

    fun disconnect() {
        disconnectLocked()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun disconnectLocked() {
        try {
            reader?.close()
        } catch (_: IOException) {
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        reader = null
        socket = null
    }

    companion object {
        val BRIDGE_UUID: UUID = UUID.fromString("8e7b3f20-4c6a-4d8e-9a1b-2c3d4e5f6a7b")
        private const val ACK_TIMEOUT_MS = 2_000L

        val bridgeJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
