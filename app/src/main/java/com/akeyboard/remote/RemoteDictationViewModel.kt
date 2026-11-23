package com.akeyboard.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.akeyboard.AKeyboardApp
import com.akeyboard.audio.AudioCaptureManager
import com.akeyboard.bridge.BluetoothBridgeClient
import com.akeyboard.bridge.BluetoothReadiness
import com.akeyboard.bridge.BridgeCommand
import com.akeyboard.bridge.BridgeDeliveryError
import com.akeyboard.bridge.BridgeDeliveryException
import com.akeyboard.bridge.BridgeKey
import com.akeyboard.bridge.ConnectionState
import com.akeyboard.live.GeminiLiveClient
import com.akeyboard.live.VoiceDictationSession
import com.akeyboard.live.VoiceSessionError
import com.akeyboard.live.VoiceSessionPhase
import com.akeyboard.util.hasPermission
import com.akeyboard.util.isNetworkAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DictationState {
    data object Idle : DictationState
    data object Listening : DictationState
    data object Finalizing : DictationState
    data class Error(val error: VoiceSessionError) : DictationState
}

sealed interface RemoteBlocker {
    data object NoHardware : RemoteBlocker
    data class PermissionsMissing(val missingBt: Boolean, val missingMic: Boolean) : RemoteBlocker
    data class BluetoothDisabled(val turningOn: Boolean) : RemoteBlocker
    data object NoPairedDevices : RemoteBlocker
    data object ApiKeyMissing : RemoteBlocker
}

enum class RemoteConnectNotice {
    BT_ENABLE_DENIED,
    BT_ENABLE_TIMEOUT,
    DEVICE_UNAVAILABLE
}

data class RecentTranscriptItem(
    val id: Long,
    val text: String
)

internal data class PendingConnect(
    val address: String,
    val expiresAtElapsedMs: Long
) {
    fun isExpired(nowElapsedMs: Long): Boolean = nowElapsedMs >= expiresAtElapsedMs
}

internal sealed interface PendingConnectDecision {
    data object None : PendingConnectDecision
    data object KeepWaiting : PendingConnectDecision
    data object Expired : PendingConnectDecision
    data class ConnectAddress(val address: String) : PendingConnectDecision
    data object DeviceMissing : PendingConnectDecision
    data object Clear : PendingConnectDecision
}

class RemoteDictationViewModel(
    private val app: AKeyboardApp
) : ViewModel() {

    private val bridgeClient = BluetoothBridgeClient(app)

    private val _preferBuiltInMic = MutableStateFlow(true)
    val preferBuiltInMic: StateFlow<Boolean> = _preferBuiltInMic.asStateFlow()

    private val audioManager = AudioCaptureManager(
        context = app,
        preferBuiltInMicProvider = { preferBuiltInMic.value }
    )
    private val liveClient = GeminiLiveClient(
        okHttpClient = app.okHttpClient,
        onUsageReported = app::recordUsage
    )

    private val _dictationState = MutableStateFlow<DictationState>(DictationState.Idle)
    val dictationState: StateFlow<DictationState> = _dictationState.asStateFlow()

    private val _interimText = MutableStateFlow("")
    val interimText: StateFlow<String> = _interimText.asStateFlow()

    private val _pendingResendText = MutableStateFlow("")
    val pendingResendText: StateFlow<String> = _pendingResendText.asStateFlow()

    private val _recentTranscripts = MutableStateFlow<List<RecentTranscriptItem>>(emptyList())
    val recentTranscripts: StateFlow<List<RecentTranscriptItem>> = _recentTranscripts.asStateFlow()

    private var nextRecentId = 1L

    private val _deliveryError = MutableStateFlow<BridgeDeliveryError?>(null)
    val deliveryError: StateFlow<BridgeDeliveryError?> = _deliveryError.asStateFlow()

    private val _connectNotice = MutableStateFlow<RemoteConnectNotice?>(null)
    val connectNotice: StateFlow<RemoteConnectNotice?> = _connectNotice.asStateFlow()

    private val _readiness = MutableStateFlow<BluetoothReadiness>(BluetoothReadiness.NoHardware)
    val readiness: StateFlow<BluetoothReadiness> = _readiness.asStateFlow()

    private val _blocker = MutableStateFlow<RemoteBlocker?>(null)
    val blocker: StateFlow<RemoteBlocker?> = _blocker.asStateFlow()

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val selectedDevice: StateFlow<BluetoothDevice?> = _selectedDevice.asStateFlow()

    private val _offlineTargetName = MutableStateFlow<String?>(null)
    val offlineTargetName: StateFlow<String?> = _offlineTargetName.asStateFlow()

    private val _hasApiKey = MutableStateFlow<Boolean?>(null)
    val hasApiKey: StateFlow<Boolean?> = _hasApiKey.asStateFlow()

    private val _pendingConnectActive = MutableStateFlow(false)
    val pendingConnectActive: StateFlow<Boolean> = _pendingConnectActive.asStateFlow()

    private val _uiRequestEnableBt = Channel<Unit>(Channel.BUFFERED)
    val uiRequestEnableBt: Flow<Unit> = _uiRequestEnableBt.receiveAsFlow()

    val connectionState: StateFlow<ConnectionState> = bridgeClient.connectionState

    private var lastRemoteAddress: String? = null
    private var lastRemoteName: String? = null
    private var pendingConnect: PendingConnect? = null
    private var btEnableWaitJob: Job? = null
    private var resendJob: Job? = null

    private val session = VoiceDictationSession(
        scope = viewModelScope,
        audioManager = audioManager,
        liveClient = liveClient,
        apiKeyStore = app.apiKeyStore,
        settingsRepository = app.settingsRepository,
        hasMicPermission = { app.hasPermission(Manifest.permission.RECORD_AUDIO) },
        isNetworkAvailable = { app.isNetworkAvailable() },
        onPhaseChanged = { phase ->
            _dictationState.value = when (phase) {
                VoiceSessionPhase.IDLE, VoiceSessionPhase.WARM -> DictationState.Idle
                VoiceSessionPhase.RECORDING -> DictationState.Listening
                VoiceSessionPhase.FINALIZING -> DictationState.Finalizing
            }
        },
        onInterim = { text -> _interimText.value = text },
        onFinal = { text -> handleFinalTranscript(text) },
        onFinishComposing = { _interimText.value = "" },
        onError = { err ->
            _interimText.value = ""
            _dictationState.value = DictationState.Error(err)
        }
    )

    init {
        viewModelScope.launch {
            app.settingsRepository.settings.collect { settings ->
                _preferBuiltInMic.value = settings.remotePreferBuiltInMic
                lastRemoteAddress = settings.lastRemoteDeviceAddress
                lastRemoteName = settings.lastRemoteDeviceName
                updateDeviceSelection(_readiness.value)
            }
        }
        bridgeClient.startMonitoring { refreshEnvironmentState() }
        refreshEnvironmentState()
    }

    fun refreshEnvironmentState() {
        val currentReadiness = bridgeClient.readiness()
        _readiness.value = currentReadiness
        updateDeviceSelection(currentReadiness)

        val micGrantedNow = app.hasPermission(Manifest.permission.RECORD_AUDIO)
        _blocker.value = deriveRemoteBlocker(
            readiness = currentReadiness,
            hasMicPermission = micGrantedNow,
            hasApiKey = _hasApiKey.value
        )

        viewModelScope.launch {
            val keyPresent = withContext(Dispatchers.IO) {
                !app.apiKeyStore.load().isNullOrBlank()
            }
            _hasApiKey.value = keyPresent

            val latestReadiness = bridgeClient.readiness()
            _readiness.value = latestReadiness
            updateDeviceSelection(latestReadiness)

            val micGranted = app.hasPermission(Manifest.permission.RECORD_AUDIO)
            _blocker.value = deriveRemoteBlocker(
                readiness = latestReadiness,
                hasMicPermission = micGranted,
                hasApiKey = keyPresent
            )

            val currentDictation = _dictationState.value
            if (currentDictation is DictationState.Error) {
                val shouldClear = when (currentDictation.error) {
                    VoiceSessionError.NO_KEY -> keyPresent
                    VoiceSessionError.NO_PERMISSION -> micGranted
                    else -> false
                }
                if (shouldClear) {
                    _dictationState.value = DictationState.Idle
                }
            }

            if (keyPresent && micGranted) {
                session.warmUp()
            }
        }

        reconcilePendingConnect(currentReadiness)
    }

    fun refreshBondedDevices() {
        refreshEnvironmentState()
    }

    @SuppressLint("MissingPermission")
    fun onDeviceSelected(device: BluetoothDevice) {
        _selectedDevice.value = device
        val name = safeDeviceName(device)
        lastRemoteAddress = device.address
        lastRemoteName = name
        viewModelScope.launch {
            app.settingsRepository.setLastRemoteDevice(device.address, name)
        }
    }

    fun onConnectClicked() {
        _deliveryError.value = null
        _connectNotice.value = null
        val currentReadiness = bridgeClient.readiness()
        _readiness.value = currentReadiness
        updateDeviceSelection(currentReadiness)

        when (currentReadiness) {
            is BluetoothReadiness.Ready -> {
                val target = _selectedDevice.value
                    ?: currentReadiness.devices.firstOrNull { it.address == lastRemoteAddress }
                    ?: currentReadiness.devices.firstOrNull()
                if (target != null) {
                    connectToDevice(target)
                }
            }
            is BluetoothReadiness.BluetoothDisabled -> {
                val rememberedAddr = lastRemoteAddress ?: return
                btEnableWaitJob?.cancel()
                pendingConnect = PendingConnect(
                    address = rememberedAddr,
                    expiresAtElapsedMs = SystemClock.elapsedRealtime() + PENDING_CONNECT_TTL_MS
                )
                _pendingConnectActive.value = true
                _uiRequestEnableBt.trySend(Unit)
            }
            else -> Unit
        }
    }

    fun onEnableBluetoothClicked() {
        _connectNotice.value = null
        clearPendingConnect()
        _uiRequestEnableBt.trySend(Unit)
    }

    fun onBluetoothEnableResult(granted: Boolean) {
        if (!granted) {
            val hadPending = pendingConnect != null
            clearPendingConnect()
            if (hadPending && !bridgeClient.isBluetoothEnabled()) {
                _connectNotice.value = RemoteConnectNotice.BT_ENABLE_DENIED
            }
            refreshEnvironmentState()
            return
        }

        _connectNotice.value = null
        if (bridgeClient.isBluetoothEnabled()) {
            refreshEnvironmentState()
        } else {
            refreshEnvironmentState()
            if (pendingConnect != null) {
                startBtEnableWaitTimer()
            }
        }
    }

    fun cancelConnect() {
        clearPendingConnect()
        bridgeClient.cancelConnect()
    }

    fun disconnect() {
        clearPendingConnect()
        bridgeClient.disconnect()
    }

    fun setPreferBuiltInMic(preferBuiltIn: Boolean) {
        _preferBuiltInMic.value = preferBuiltIn
        viewModelScope.launch {
            app.settingsRepository.setRemotePreferBuiltInMic(preferBuiltIn)
        }
    }

    fun onMicPressed() {
        _deliveryError.value = null
        _connectNotice.value = null
        session.onMicPressed()
    }

    fun onMicReleased() {
        session.onMicReleased()
    }

    fun sendKey(key: BridgeKey) {
        _deliveryError.value = null
        _connectNotice.value = null
        viewModelScope.launch {
            val result = bridgeClient.sendCommand(BridgeCommand.key(key))
            result.onFailure { err ->
                _deliveryError.value = mapDeliveryError(err)
            }
        }
    }

    fun sendClipboardText(text: String): Boolean {
        val sanitized = prepareClipboardTextForPc(text)
        if (sanitized.isEmpty()) return false
        if (connectionState.value !is ConnectionState.Connected) return false
        if (resendJob?.isActive == true) return true
        _deliveryError.value = null
        _connectNotice.value = null
        resendJob = viewModelScope.launch {
            var remaining = sanitized
            val result = sendChunksLoop(
                readRemaining = { remaining },
                onChunkAcked = { chunk ->
                    remaining = trimAcknowledgedPrefix(remaining, chunk)
                }
            )
            if (result.error != null) {
                _deliveryError.value = mapDeliveryError(result.error)
            }
        }
        return true
    }

    fun resendPending() {
        if (_pendingResendText.value.isEmpty()) return
        if (resendJob?.isActive == true) return
        _deliveryError.value = null
        _connectNotice.value = null
        resendJob = viewModelScope.launch {
            val result = sendChunksLoop(
                readRemaining = { _pendingResendText.value },
                onChunkAcked = { chunk ->
                    _pendingResendText.value = trimAcknowledgedPrefix(_pendingResendText.value, chunk)
                }
            )
            if (result.sentText.isNotEmpty()) {
                recordRecentTranscript(result.sentText)
            }
            if (result.error != null) {
                _deliveryError.value = mapDeliveryError(result.error)
            }
        }
    }

    fun clearPending() {
        resendJob?.cancel()
        _pendingResendText.value = ""
        _deliveryError.value = null
    }

    fun resendRecent(item: RecentTranscriptItem) {
        if (connectionState.value !is ConnectionState.Connected) return
        if (resendJob?.isActive == true) return
        val sanitized = VoiceDictationSession.sanitizeSingleLine(item.text)
        if (sanitized.isEmpty()) return
        _deliveryError.value = null
        _connectNotice.value = null
        resendJob = viewModelScope.launch {
            var remaining = sanitized
            val result = sendChunksLoop(
                readRemaining = { remaining },
                onChunkAcked = { chunk ->
                    remaining = trimAcknowledgedPrefix(remaining, chunk)
                }
            )
            if (result.error != null) {
                _deliveryError.value = mapDeliveryError(result.error)
            }
        }
    }

    fun clearRecent() {
        _recentTranscripts.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val name = safeDeviceName(device)
        lastRemoteAddress = device.address
        lastRemoteName = name
        viewModelScope.launch {
            app.settingsRepository.setLastRemoteDevice(device.address, name)
            bridgeClient.connect(device)
        }
    }

    private fun startBtEnableWaitTimer() {
        btEnableWaitJob?.cancel()
        btEnableWaitJob = viewModelScope.launch {
            delay(BT_ENABLE_WAIT_TIMEOUT_MS)
            if (pendingConnect != null) {
                clearPendingConnect()
                _connectNotice.value = RemoteConnectNotice.BT_ENABLE_TIMEOUT
                refreshEnvironmentState()
            }
        }
    }

    private fun reconcilePendingConnect(readiness: BluetoothReadiness) {
        val pending = pendingConnect ?: return
        val devices = (readiness as? BluetoothReadiness.Ready)?.devices.orEmpty()
        val matchingDevice = devices.firstOrNull { it.address == pending.address }
        when (
            val decision = evaluatePendingConnect(
                pending = pending,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                readiness = readiness,
                hasMatchingDevice = matchingDevice != null
            )
        ) {
            PendingConnectDecision.None,
            PendingConnectDecision.KeepWaiting -> Unit
            PendingConnectDecision.Expired,
            PendingConnectDecision.Clear -> clearPendingConnect()
            PendingConnectDecision.DeviceMissing -> {
                clearPendingConnect()
                _connectNotice.value = RemoteConnectNotice.DEVICE_UNAVAILABLE
            }
            is PendingConnectDecision.ConnectAddress -> {
                clearPendingConnect()
                if (matchingDevice != null) {
                    connectToDevice(matchingDevice)
                } else {
                    _connectNotice.value = RemoteConnectNotice.DEVICE_UNAVAILABLE
                }
            }
        }
    }

    private fun clearPendingConnect() {
        pendingConnect = null
        _pendingConnectActive.value = false
        btEnableWaitJob?.cancel()
        btEnableWaitJob = null
    }

    private fun updateDeviceSelection(readiness: BluetoothReadiness) {
        when (readiness) {
            is BluetoothReadiness.Ready -> {
                val devices = readiness.devices
                _bondedDevices.value = devices
                _offlineTargetName.value = null
                val remembered = lastRemoteAddress?.let { addr ->
                    devices.firstOrNull { it.address == addr }
                }
                val current = _selectedDevice.value?.let { sel ->
                    devices.firstOrNull { it.address == sel.address }
                }
                _selectedDevice.value = remembered ?: current ?: devices.firstOrNull()
            }
            is BluetoothReadiness.BluetoothDisabled -> {
                _bondedDevices.value = emptyList()
                _selectedDevice.value = null
                _offlineTargetName.value = lastRemoteName ?: lastRemoteAddress
            }
            is BluetoothReadiness.NoHardware,
            is BluetoothReadiness.PermissionMissing,
            is BluetoothReadiness.NoPairedDevices -> {
                _bondedDevices.value = emptyList()
                _selectedDevice.value = null
                _offlineTargetName.value = null
            }
        }
    }

    private fun handleFinalTranscript(text: String) {
        _interimText.value = ""
        val sanitized = VoiceDictationSession.sanitizeSingleLine(text)
        if (sanitized.isEmpty()) return

        if (connectionState.value !is ConnectionState.Connected) {
            appendPendingText(sanitized)
            return
        }

        viewModelScope.launch {
            var remaining = sanitized
            val result = sendChunksLoop(
                readRemaining = { remaining },
                onChunkAcked = { chunk ->
                    remaining = trimAcknowledgedPrefix(remaining, chunk)
                }
            )
            if (result.sentText.isNotEmpty()) {
                recordRecentTranscript(result.sentText)
            }
            if (result.error != null) {
                if (remaining.isNotEmpty()) {
                    appendPendingText(remaining)
                }
                _deliveryError.value = mapDeliveryError(result.error)
            }
        }
    }

    private data class SendChunksResult(
        val sentText: String,
        val error: Throwable?
    )

    private suspend fun sendChunksLoop(
        readRemaining: () -> String,
        onChunkAcked: (String) -> Unit
    ): SendChunksResult {
        val sentText = StringBuilder()
        while (true) {
            val current = readRemaining()
            if (current.isEmpty()) break
            val chunk = nextPendingChunk(current)
            if (chunk.isEmpty()) break
            val safeChunk = VoiceDictationSession.sanitizeSingleLine(chunk)
            if (safeChunk.isEmpty()) {
                onChunkAcked(chunk)
                continue
            }
            val result = bridgeClient.sendCommand(BridgeCommand.text(safeChunk))
            if (result.isSuccess) {
                sentText.append(safeChunk)
                onChunkAcked(chunk)
            } else {
                return SendChunksResult(
                    sentText = sentText.toString(),
                    error = result.exceptionOrNull()
                )
            }
        }
        return SendChunksResult(sentText = sentText.toString(), error = null)
    }

    private fun recordRecentTranscript(text: String) {
        val nextId = nextRecentId
        val updated = appendRecentTranscript(_recentTranscripts.value, nextId, text)
        if (updated !== _recentTranscripts.value) {
            nextRecentId = nextId + 1L
            _recentTranscripts.value = updated
        }
    }

    private fun appendPendingText(text: String) {
        _pendingResendText.value = appendPendingUtterance(_pendingResendText.value, text)
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    override fun onCleared() {
        btEnableWaitJob?.cancel()
        resendJob?.cancel()
        bridgeClient.stopMonitoring()
        session.close()
        bridgeClient.disconnect()
        super.onCleared()
    }

    class Factory(private val app: AKeyboardApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RemoteDictationViewModel(app) as T
        }
    }

    companion object {
        const val PENDING_CONNECT_TTL_MS = 15_000L
        const val BT_ENABLE_WAIT_TIMEOUT_MS = 5_000L
        const val MAX_RESEND_CHUNK_BYTES = 16_384
        const val MAX_RECENT_TRANSCRIPTS = 3

        fun shouldKeepScreenOn(
            isMicHeld: Boolean,
            isListening: Boolean,
            isFinalizing: Boolean
        ): Boolean = isMicHeld || isListening || isFinalizing

        fun appendRecentTranscript(
            current: List<RecentTranscriptItem>,
            nextId: Long,
            text: String,
            maxItems: Int = MAX_RECENT_TRANSCRIPTS
        ): List<RecentTranscriptItem> {
            val sanitized = VoiceDictationSession.sanitizeSingleLine(text).trim()
            if (sanitized.isEmpty() || maxItems <= 0) return current
            return (listOf(RecentTranscriptItem(id = nextId, text = sanitized)) + current)
                .take(maxItems)
        }

        fun prepareClipboardTextForPc(text: String?): String {
            if (text.isNullOrBlank()) return ""
            return VoiceDictationSession.sanitizeSingleLine(text)
        }

        internal fun deriveRemoteBlocker(
            readiness: BluetoothReadiness,
            hasMicPermission: Boolean,
            hasApiKey: Boolean?
        ): RemoteBlocker? {
            if (readiness is BluetoothReadiness.NoHardware) {
                return RemoteBlocker.NoHardware
            }
            val missingBt = readiness is BluetoothReadiness.PermissionMissing
            val missingMic = !hasMicPermission
            if (missingBt || missingMic) {
                return RemoteBlocker.PermissionsMissing(
                    missingBt = missingBt,
                    missingMic = missingMic
                )
            }
            if (readiness is BluetoothReadiness.BluetoothDisabled) {
                return RemoteBlocker.BluetoothDisabled(turningOn = readiness.turningOn)
            }
            if (readiness is BluetoothReadiness.NoPairedDevices) {
                return RemoteBlocker.NoPairedDevices
            }
            if (hasApiKey == false) {
                return RemoteBlocker.ApiKeyMissing
            }
            return null
        }

        internal fun evaluatePendingConnect(
            pending: PendingConnect?,
            nowElapsedMs: Long,
            readiness: BluetoothReadiness,
            hasMatchingDevice: Boolean
        ): PendingConnectDecision {
            if (pending == null) return PendingConnectDecision.None
            if (pending.isExpired(nowElapsedMs)) return PendingConnectDecision.Expired
            return when (readiness) {
                is BluetoothReadiness.Ready -> {
                    if (hasMatchingDevice) {
                        PendingConnectDecision.ConnectAddress(pending.address)
                    } else {
                        PendingConnectDecision.DeviceMissing
                    }
                }
                is BluetoothReadiness.NoPairedDevices -> PendingConnectDecision.DeviceMissing
                is BluetoothReadiness.NoHardware,
                is BluetoothReadiness.PermissionMissing -> PendingConnectDecision.Clear
                is BluetoothReadiness.BluetoothDisabled -> PendingConnectDecision.KeepWaiting
            }
        }

        internal fun mapDeliveryError(err: Throwable?): BridgeDeliveryError = when (err) {
            is BridgeDeliveryException -> err.error
            else -> BridgeDeliveryError.SEND_FAILED
        }

        internal fun appendPendingUtterance(current: String, addition: String): String {
            val cleanAddition = VoiceDictationSession.sanitizeSingleLine(addition)
            if (cleanAddition.isEmpty()) return current
            val cleanCurrent = VoiceDictationSession.sanitizeSingleLine(current)
            return if (cleanCurrent.isEmpty()) cleanAddition else "$cleanCurrent $cleanAddition"
        }

        internal fun nextPendingChunk(
            text: String,
            maxBytes: Int = MAX_RESEND_CHUNK_BYTES
        ): String {
            if (text.isEmpty()) return ""
            return takeSafeUtf8Prefix(text, maxBytes)
        }

        internal fun trimAcknowledgedPrefix(fullText: String, ackedPrefix: String): String {
            if (ackedPrefix.isEmpty()) return fullText
            return if (fullText.startsWith(ackedPrefix)) {
                fullText.substring(ackedPrefix.length)
            } else {
                fullText
            }
        }

        internal fun splitPendingForResend(
            text: String,
            maxBytes: Int = MAX_RESEND_CHUNK_BYTES
        ): List<String> {
            val chunks = mutableListOf<String>()
            var remaining = text
            while (remaining.isNotEmpty()) {
                val chunk = nextPendingChunk(remaining, maxBytes)
                if (chunk.isEmpty()) break
                chunks += chunk
                remaining = trimAcknowledgedPrefix(remaining, chunk)
            }
            return chunks
        }

        private fun takeSafeUtf8Prefix(text: String, maxBytes: Int): String {
            if (text.isEmpty()) return ""
            var byteCount = 0
            var index = 0
            while (index < text.length) {
                val ch = text[index]
                val isSurrogatePair = ch.isHighSurrogate() &&
                    index + 1 < text.length &&
                    text[index + 1].isLowSurrogate()
                val charCount = if (isSurrogatePair) 2 else 1
                val codePoint = if (isSurrogatePair) {
                    Character.toCodePoint(ch, text[index + 1])
                } else {
                    ch.code
                }
                val utf8Bytes = utf8ByteLength(codePoint)
                if (index > 0 && byteCount + utf8Bytes > maxBytes) {
                    break
                }
                byteCount += utf8Bytes
                index += charCount
            }
            return text.substring(0, index)
        }

        private fun utf8ByteLength(codePoint: Int): Int = when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
    }
}
