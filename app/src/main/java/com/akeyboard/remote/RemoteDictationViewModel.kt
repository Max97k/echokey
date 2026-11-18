package com.akeyboard.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.akeyboard.AKeyboardApp
import com.akeyboard.audio.AudioCaptureManager
import com.akeyboard.bridge.BluetoothBridgeClient
import com.akeyboard.bridge.BridgeCommand
import com.akeyboard.bridge.BridgeDeliveryException
import com.akeyboard.bridge.BridgeKey
import com.akeyboard.bridge.ConnectionState
import com.akeyboard.live.GeminiLiveClient
import com.akeyboard.live.VoiceDictationSession
import com.akeyboard.live.VoiceSessionError
import com.akeyboard.live.VoiceSessionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DictationState {
    data object Idle : DictationState
    data object Listening : DictationState
    data object Finalizing : DictationState
    data class Error(val error: VoiceSessionError) : DictationState
}

class RemoteDictationViewModel(
    private val app: AKeyboardApp
) : ViewModel() {

    private val bridgeClient = BluetoothBridgeClient(app)
    private val audioManager = AudioCaptureManager(context = app, preferBuiltInMic = true)
    private val liveClient = GeminiLiveClient(app.okHttpClient)

    private val _dictationState = MutableStateFlow<DictationState>(DictationState.Idle)
    val dictationState: StateFlow<DictationState> = _dictationState.asStateFlow()

    private val _interimText = MutableStateFlow("")
    val interimText: StateFlow<String> = _interimText.asStateFlow()

    private val _pendingResendText = MutableStateFlow("")
    val pendingResendText: StateFlow<String> = _pendingResendText.asStateFlow()

    private val _deliveryError = MutableStateFlow<String?>(null)
    val deliveryError: StateFlow<String?> = _deliveryError.asStateFlow()

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = bridgeClient.connectionState

    private val session = VoiceDictationSession(
        scope = viewModelScope,
        audioManager = audioManager,
        liveClient = liveClient,
        apiKeyStore = app.apiKeyStore,
        settingsRepository = app.settingsRepository,
        hasMicPermission = ::hasMicPermission,
        isNetworkAvailable = ::isNetworkAvailable,
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
        refreshBondedDevices()
        session.warmUp()
    }

    fun refreshBondedDevices() {
        _bondedDevices.value = bridgeClient.bondedDevices()
    }

    fun connect(device: BluetoothDevice) {
        _deliveryError.value = null
        viewModelScope.launch {
            bridgeClient.connect(device)
        }
    }

    fun disconnect() {
        bridgeClient.disconnect()
    }

    fun onMicPressed() {
        _deliveryError.value = null
        session.onMicPressed()
    }

    fun onMicReleased() {
        session.onMicReleased()
    }

    fun sendKey(key: BridgeKey) {
        _deliveryError.value = null
        viewModelScope.launch {
            val result = bridgeClient.sendCommand(BridgeCommand.key(key))
            result.onFailure { err ->
                _deliveryError.value = formatDeliveryError(err)
            }
        }
    }

    fun resendPending() {
        val text = _pendingResendText.value
        if (text.isEmpty()) return
        _deliveryError.value = null
        viewModelScope.launch {
            val result = bridgeClient.sendCommand(BridgeCommand.text(text))
            result.onSuccess {
                _pendingResendText.value = ""
            }.onFailure { err ->
                _deliveryError.value = formatDeliveryError(err)
            }
        }
    }

    fun clearPending() {
        _pendingResendText.value = ""
        _deliveryError.value = null
    }

    private fun handleFinalTranscript(text: String) {
        _interimText.value = ""
        if (text.isEmpty()) return

        if (connectionState.value !is ConnectionState.Connected) {
            appendPendingText(text)
            return
        }

        viewModelScope.launch {
            val result = bridgeClient.sendCommand(BridgeCommand.text(text))
            result.onFailure { err ->
                appendPendingText(text)
                _deliveryError.value = formatDeliveryError(err)
            }
        }
    }

    private fun appendPendingText(text: String) {
        val current = _pendingResendText.value
        _pendingResendText.value = if (current.isEmpty()) text else "$current\n$text"
    }

    private fun formatDeliveryError(err: Throwable): String {
        return when {
            err is BridgeDeliveryException && err.code == "uipi" -> "uipi"
            else -> err.message ?: "Send failed"
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @SuppressLint("MissingPermission")
    override fun onCleared() {
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
}
