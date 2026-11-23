// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.akeyboard.util.DiagnosticLog
import com.akeyboard.util.hasPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
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

enum class BridgeDeliveryError {
    UIPI,
    NO_FOCUS,
    SEND_FAILED,
    PC_DISCONNECTED
}

class BridgeDeliveryException(
    val error: BridgeDeliveryError,
    message: String
) : Exception(message)

sealed interface BluetoothReadiness {
    data object NoHardware : BluetoothReadiness
    data object PermissionMissing : BluetoothReadiness
    data class BluetoothDisabled(val turningOn: Boolean) : BluetoothReadiness
    data object NoPairedDevices : BluetoothReadiness
    data class Ready(val devices: List<BluetoothDevice>) : BluetoothReadiness
}

internal enum class ReadinessStatus {
    NO_HARDWARE,
    PERMISSION_MISSING,
    BLUETOOTH_OFF,
    BLUETOOTH_TURNING_ON,
    NO_PAIRED_DEVICES,
    READY
}

internal interface BridgeTransport : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val isConnected: Boolean
    fun connect()
}

private class BluetoothSocketTransport(
    private val rfcomm: BluetoothSocket
) : BridgeTransport {
    override val inputStream: InputStream get() = rfcomm.inputStream
    override val outputStream: OutputStream get() = rfcomm.outputStream
    override val isConnected: Boolean get() = rfcomm.isConnected

    @SuppressLint("MissingPermission")
    override fun connect() {
        rfcomm.connect()
    }

    override fun close() {
        rfcomm.close()
    }
}

class BluetoothBridgeClient internal constructor(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val ackTimeoutMs: Long
) {
    constructor(context: Context) : this(
        context = context,
        ioDispatcher = Dispatchers.IO,
        ackTimeoutMs = ACK_TIMEOUT_MS
    )

    private val ioMutex = Mutex()
    private val stateLock = Any()
    private val readerScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var socket: BridgeTransport? = null
    private var reader: BufferedReader? = null
    private var readerJob: Job? = null
    private var pendingAck: CompletableDeferred<String?>? = null
    private var monitorReceiver: BroadcastReceiver? = null

    @Volatile
    private var connectingSocket: BridgeTransport? = null

    @Volatile
    private var connectCancelled: Boolean = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun hasConnectPermission(): Boolean = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    fun isBluetoothEnabled(): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return try {
            adapter.state == BluetoothAdapter.STATE_ON || adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun readiness(): BluetoothReadiness {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return evaluateReadiness(
                hasHardware = false,
                hasPermission = hasConnectPermission(),
                adapterState = BluetoothAdapter.STATE_OFF,
                bondedDevices = emptyList()
            )
        val hasPerm = hasConnectPermission()
        val state = try {
            adapter.state
        } catch (_: SecurityException) {
            BluetoothAdapter.STATE_OFF
        }
        val devices = if (hasPerm && state == BluetoothAdapter.STATE_ON) {
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return evaluateReadiness(
            hasHardware = true,
            hasPermission = hasPerm,
            adapterState = state,
            bondedDevices = devices
        )
    }

    fun bondedDevices(): List<BluetoothDevice> {
        return (readiness() as? BluetoothReadiness.Ready)?.devices.orEmpty()
    }

    fun startMonitoring(onEnvironmentChanged: () -> Unit) {
        stopMonitoring()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val btState = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR
                        )
                        if (btState == BluetoothAdapter.STATE_OFF ||
                            btState == BluetoothAdapter.STATE_TURNING_OFF
                        ) {
                            disconnect()
                        }
                        onEnvironmentChanged()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        onEnvironmentChanged()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        monitorReceiver = receiver
    }

    fun stopMonitoring() {
        val receiver = monitorReceiver ?: return
        monitorReceiver = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> {
        if (!hasConnectPermission()) {
            val err = SecurityException("BLUETOOTH_CONNECT permission not granted")
            _connectionState.value = ConnectionState.Error(err.message ?: "Permission denied")
            return Result.failure(err)
        }
        val name = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
        return connectTransport(name) {
            BluetoothSocketTransport(device.createRfcommSocketToServiceRecord(BRIDGE_UUID))
        }
    }

    internal suspend fun connectTransport(
        deviceName: String,
        createTransport: () -> BridgeTransport
    ): Result<Unit> = ioMutex.withLock {
        disconnect()
        connectCancelled = false
        _connectionState.value = ConnectionState.Connecting(deviceName)

        return withContext(ioDispatcher) {
            var transport: BridgeTransport? = null
            try {
                transport = createTransport()
                connectingSocket = transport
                if (connectCancelled) {
                    closeQuietly(transport)
                    _connectionState.value = ConnectionState.Disconnected
                    return@withContext Result.failure(IOException("Connection cancelled"))
                }
                transport.connect()
                if (connectCancelled) {
                    closeQuietly(transport)
                    _connectionState.value = ConnectionState.Disconnected
                    return@withContext Result.failure(IOException("Connection cancelled"))
                }
                val activeReader = BufferedReader(
                    InputStreamReader(transport.inputStream, Charsets.UTF_8)
                )
                synchronized(stateLock) {
                    socket = transport
                    reader = activeReader
                    readerJob = readerScope.launch {
                        readIncomingLoop(transport, activeReader)
                    }
                }
                _connectionState.value = ConnectionState.Connected(deviceName)
                Result.success(Unit)
            } catch (e: IOException) {
                closeQuietly(transport)
                if (!connectCancelled) {
                    DiagnosticLog.record(
                        "Bridge",
                        "connect_error",
                        "${e::class.simpleName}: ${e.message.orEmpty()}"
                    )
                }
                _connectionState.value = if (connectCancelled) {
                    ConnectionState.Disconnected
                } else {
                    ConnectionState.Error(e.message ?: "Bluetooth connect failed")
                }
                Result.failure(e)
            } catch (e: SecurityException) {
                closeQuietly(transport)
                DiagnosticLog.record(
                    "Bridge",
                    "connect_error",
                    "${e::class.simpleName}: ${e.message.orEmpty()}"
                )
                _connectionState.value = ConnectionState.Error(e.message ?: "Permission denied")
                Result.failure(e)
            } finally {
                connectingSocket = null
            }
        }
    }

    fun cancelConnect() {
        connectCancelled = true
        val pendingSocket = connectingSocket
        connectingSocket = null
        closeQuietly(pendingSocket)
        if (_connectionState.value is ConnectionState.Connecting) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    suspend fun sendCommand(command: BridgeCommand): Result<Unit> = ioMutex.withLock {
        val ackDeferred = CompletableDeferred<String?>()
        val pair = synchronized(stateLock) {
            val activeSocket = socket
            val activeReader = reader
            if (activeSocket == null || activeReader == null || !activeSocket.isConnected) {
                null
            } else {
                pendingAck = ackDeferred
                activeSocket to activeReader
            }
        }
        if (pair == null) {
            _connectionState.value = ConnectionState.Disconnected
            return Result.failure(
                BridgeDeliveryException(
                    BridgeDeliveryError.PC_DISCONNECTED,
                    "Not connected to PC bridge"
                )
            )
        }
        val (activeSocket, activeReader) = pair

        val writeError = withContext(ioDispatcher) {
            try {
                val line = bridgeJson.encodeToString(command) + "\n"
                val out = activeSocket.outputStream
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                null
            } catch (e: IOException) {
                e
            }
        }
        if (writeError != null) {
            DiagnosticLog.record("Bridge", "write_error", writeError::class.simpleName.orEmpty())
            synchronized(stateLock) {
                if (pendingAck === ackDeferred) {
                    pendingAck = null
                }
            }
            ackDeferred.cancel()
            onReaderEnded(activeSocket)
            return Result.failure(
                BridgeDeliveryException(
                    BridgeDeliveryError.PC_DISCONNECTED,
                    writeError.message ?: "Bluetooth write failed"
                )
            )
        }

        var ackLine: String? = null
        val completedInTime = try {
            withTimeoutOrNull(ackTimeoutMs) {
                ackLine = ackDeferred.await()
                true
            } ?: false
        } finally {
            synchronized(stateLock) {
                if (pendingAck === ackDeferred) {
                    pendingAck = null
                }
            }
        }

        if (!completedInTime) {
            DiagnosticLog.record("Bridge", "ack_timeout")
            val jobToAwait = synchronized(stateLock) { readerJob }
            // Close active socket so the dedicated reader coroutine unblocks and finishes cleanup.
            closeQuietly(activeSocket)
            withTimeoutOrNull(READER_CLEANUP_WAIT_MS) {
                jobToAwait?.join()
            }
            onReaderEnded(activeSocket)
            return Result.failure(IOException("Ack timeout"))
        }

        val resolvedAck = ackLine
        if (resolvedAck == null) {
            return Result.failure(
                BridgeDeliveryException(
                    BridgeDeliveryError.PC_DISCONNECTED,
                    "PC bridge disconnected before acknowledging command"
                )
            )
        }

        return try {
            val response = bridgeJson.decodeFromString<BridgeResponse>(resolvedAck)
            if (response.ok) {
                Result.success(Unit)
            } else {
                DiagnosticLog.record("Bridge", "nack", "err=${response.err ?: "unknown"}")
                val mapped = mapErrorCode(response.err)
                Result.failure(
                    BridgeDeliveryException(
                        mapped,
                        "PC rejected input: ${response.err ?: "unknown"}"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disconnect() {
        connectCancelled = true
        val pendingSocket = connectingSocket
        connectingSocket = null
        closeQuietly(pendingSocket)

        val activeSocket: BridgeTransport?
        val jobToCancel: Job?
        synchronized(stateLock) {
            activeSocket = socket
            reader = null
            socket = null
            jobToCancel = readerJob
            readerJob = null
            pendingAck?.complete(null)
            pendingAck = null
            _connectionState.value = ConnectionState.Disconnected
        }
        closeQuietly(activeSocket)
        jobToCancel?.cancel()
    }

    private fun CoroutineScope.readIncomingLoop(
        transport: BridgeTransport,
        activeReader: BufferedReader
    ) {
        try {
            while (isActive) {
                val line = activeReader.readLine() ?: break
                val waiter = synchronized(stateLock) {
                    val current = pendingAck
                    pendingAck = null
                    current
                }
                waiter?.complete(line)
            }
        } catch (_: IOException) {
            // Stream closed or peer disconnected
        } finally {
            onReaderEnded(transport)
            closeQuietly(activeReader)
        }
    }

    private fun onReaderEnded(transport: BridgeTransport) {
        val closeTransport: Boolean
        val jobToCancel: Job?
        synchronized(stateLock) {
            if (this.socket === transport) {
                this.socket = null
                this.reader = null
                jobToCancel = this.readerJob
                this.readerJob = null
                pendingAck?.complete(null)
                pendingAck = null
                _connectionState.value = ConnectionState.Disconnected
                closeTransport = true
            } else {
                closeTransport = false
                jobToCancel = null
            }
        }
        if (closeTransport) {
            closeQuietly(transport)
        }
        jobToCancel?.cancel()
    }

    private fun closeQuietly(closeable: Closeable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (_: IOException) {
            // Ignore close failures
        }
    }

    companion object {
        val BRIDGE_UUID: UUID = UUID.fromString("8e7b3f20-4c6a-4d8e-9a1b-2c3d4e5f6a7b")
        const val ACK_TIMEOUT_MS = 3_000L
        private const val READER_CLEANUP_WAIT_MS = 300L

        val bridgeJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        internal fun evaluateReadinessStatus(
            hasHardware: Boolean,
            hasPermission: Boolean,
            adapterState: Int,
            hasPairedDevices: Boolean
        ): ReadinessStatus = when {
            !hasHardware -> ReadinessStatus.NO_HARDWARE
            !hasPermission -> ReadinessStatus.PERMISSION_MISSING
            adapterState != BluetoothAdapter.STATE_ON -> {
                if (adapterState == BluetoothAdapter.STATE_TURNING_ON) {
                    ReadinessStatus.BLUETOOTH_TURNING_ON
                } else {
                    ReadinessStatus.BLUETOOTH_OFF
                }
            }
            !hasPairedDevices -> ReadinessStatus.NO_PAIRED_DEVICES
            else -> ReadinessStatus.READY
        }

        fun evaluateReadiness(
            hasHardware: Boolean,
            hasPermission: Boolean,
            adapterState: Int,
            bondedDevices: List<BluetoothDevice>
        ): BluetoothReadiness = when (
            evaluateReadinessStatus(
                hasHardware = hasHardware,
                hasPermission = hasPermission,
                adapterState = adapterState,
                hasPairedDevices = bondedDevices.isNotEmpty()
            )
        ) {
            ReadinessStatus.NO_HARDWARE -> BluetoothReadiness.NoHardware
            ReadinessStatus.PERMISSION_MISSING -> BluetoothReadiness.PermissionMissing
            ReadinessStatus.BLUETOOTH_OFF -> BluetoothReadiness.BluetoothDisabled(turningOn = false)
            ReadinessStatus.BLUETOOTH_TURNING_ON -> BluetoothReadiness.BluetoothDisabled(turningOn = true)
            ReadinessStatus.NO_PAIRED_DEVICES -> BluetoothReadiness.NoPairedDevices
            ReadinessStatus.READY -> BluetoothReadiness.Ready(bondedDevices)
        }

        internal fun mapErrorCode(err: String?): BridgeDeliveryError = when (err) {
            "uipi" -> BridgeDeliveryError.UIPI
            "no_focus" -> BridgeDeliveryError.NO_FOCUS
            "send_failed", "line_too_long" -> BridgeDeliveryError.SEND_FAILED
            else -> BridgeDeliveryError.SEND_FAILED
        }
    }
}
