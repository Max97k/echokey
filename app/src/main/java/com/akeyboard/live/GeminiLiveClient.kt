package com.akeyboard.live

import android.util.Base64
import com.akeyboard.util.DiagnosticLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed class LiveError {
    data object InvalidKey : LiveError()
    data object QuotaExceeded : LiveError()
    data class Network(val detail: String) : LiveError()
}

sealed class LiveEvent {
    data object Ready : LiveEvent()
    data class Interim(val text: String) : LiveEvent()
    data class Final(val text: String) : LiveEvent()
    data object TurnComplete : LiveEvent()
    data class Failed(val error: LiveError) : LiveEvent()
}

class GeminiLiveClient(
    private val okHttpClient: OkHttpClient,
    private val baseWsUrl: String = DEFAULT_WS_URL,
    private val idleTtlMs: Long = DEFAULT_IDLE_TTL_MS,
    private val finalizeTimeoutMs: Long = DEFAULT_FINALIZE_TIMEOUT_MS,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    private val base64Encoder: (ByteArray) -> String = { bytes ->
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
) {
    private var webSocket: WebSocket? = null
    private var currentKey: String? = null
    private var currentModelId: String? = null
    private var currentLanguageCodes: List<String>? = null
    private var currentSmartMode: Boolean? = null
    private var setupCompleteDeferred: CompletableDeferred<Boolean>? = null
    private var idleTtlJob: Job? = null
    private var streamJob: Job? = null
    private var isUtteranceActive = false

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: Flow<LiveEvent> = _events.asSharedFlow()

    @Synchronized
    fun isWarmFor(
        apiKey: String,
        modelId: String,
        languageCodes: List<String> = emptyList(),
        smartMode: Boolean = true
    ): Boolean {
        return webSocket != null &&
            currentKey == apiKey &&
            currentModelId == modelId &&
            currentLanguageCodes == languageCodes &&
            currentSmartMode == smartMode &&
            setupCompleteDeferred?.isCompleted == true
    }

    fun warmUp(
        scope: CoroutineScope,
        apiKey: String,
        modelId: String,
        languageCodes: List<String> = emptyList(),
        smartMode: Boolean = true
    ) {
        if (isWarmFor(apiKey, modelId, languageCodes, smartMode)) {
            resetIdleTtl(scope)
            return
        }
        close()
        openConnection(scope, apiKey, modelId, languageCodes, smartMode)
        resetIdleTtl(scope)
    }

    fun startUtterance(
        scope: CoroutineScope,
        apiKey: String,
        modelId: String,
        audioFrames: ReceiveChannel<ByteArray>,
        languageCodes: List<String> = emptyList(),
        smartMode: Boolean = true
    ) {
        idleTtlJob?.cancel()
        streamJob?.cancel()
        isUtteranceActive = true

        val deferred = if (isWarmFor(apiKey, modelId, languageCodes, smartMode)) {
            setupCompleteDeferred!!
        } else {
            closeInternal(preserveUtteranceFlag = true)
            openConnection(scope, apiKey, modelId, languageCodes, smartMode)
        }

        streamJob = scope.launch(Dispatchers.IO) {
            val ready = withTimeoutOrNull(SETUP_TIMEOUT_MS) { deferred.await() } == true
            if (!ready) {
                DiagnosticLog.record("Live", "setup_timeout")
                isUtteranceActive = false
                return@launch
            }
            val ws = synchronized(this@GeminiLiveClient) { webSocket } ?: run {
                isUtteranceActive = false
                return@launch
            }
            ws.send(json.encodeToString(RealtimeActivityStartEnvelope()))
            for (frame in audioFrames) {
                if (!isUtteranceActive) break
                val b64 = base64Encoder(frame)
                val envelope = RealtimeAudioEnvelope(
                    realtimeInput = RealtimeAudioInput(
                        audio = RealtimeAudioBlob(data = b64)
                    )
                )
                ws.send(json.encodeToString(envelope))
            }
        }
    }

    suspend fun finishUtterance(scope: CoroutineScope): Boolean {
        isUtteranceActive = false
        val ws = synchronized(this) { webSocket }
        if (ws != null && setupCompleteDeferred?.isCompleted == true) {
            ws.send(json.encodeToString(RealtimeActivityEndEnvelope()))
        }
        streamJob?.join()
        resetIdleTtl(scope)
        return ws != null
    }

    fun finalizeTimeout(): Long = finalizeTimeoutMs

    fun close() {
        closeInternal(preserveUtteranceFlag = false)
    }

    @Synchronized
    private fun closeInternal(preserveUtteranceFlag: Boolean) {
        if (!preserveUtteranceFlag) {
            isUtteranceActive = false
        }
        idleTtlJob?.cancel()
        idleTtlJob = null
        streamJob?.cancel()
        streamJob = null
        setupCompleteDeferred?.cancel()
        setupCompleteDeferred = null
        currentKey = null
        currentModelId = null
        currentLanguageCodes = null
        currentSmartMode = null
        webSocket?.close(1000, "closed")
        webSocket = null
    }

    private fun resetIdleTtl(scope: CoroutineScope) {
        idleTtlJob?.cancel()
        idleTtlJob = scope.launch {
            delay(idleTtlMs)
            if (!isUtteranceActive) {
                close()
            }
        }
    }

    @Synchronized
    private fun openConnection(
        scope: CoroutineScope,
        apiKey: String,
        modelId: String,
        languageCodes: List<String>,
        smartMode: Boolean
    ): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        setupCompleteDeferred = deferred
        currentKey = apiKey
        currentModelId = modelId
        currentLanguageCodes = languageCodes
        currentSmartMode = smartMode

        val cleanModel = modelId.removePrefix("models/")
        val isTranscribe = cleanModel.contains("transcribe", ignoreCase = true)
        DiagnosticLog.record("Live", "connect", "model=$cleanModel transcribe=$isTranscribe")
        val setupEnvelope = BidiSetupEnvelope(
            setup = BidiSetup(
                model = "models/$cleanModel",
                generationConfig = BidiGenerationConfig(
                    responseModalities = if (isTranscribe) listOf("TEXT") else listOf("AUDIO")
                ),
                inputAudioTranscription = if (isTranscribe) {
                    InputAudioTranscriptionConfig(
                        languageCodes = languageCodes,
                        mode = if (smartMode) "SMART" else "VERBATIM"
                    )
                } else {
                    InputAudioTranscriptionConfig()
                },
                realtimeInputConfig = RealtimeInputConfig(
                    automaticActivityDetection = AutomaticActivityDetectionConfig(disabled = true)
                )
            )
        )

        val url = "$baseWsUrl?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(json.encodeToString(setupEnvelope))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerPayload(text, deferred)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerPayload(bytes.utf8(), deferred)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) {
                    DiagnosticLog.record("Live", "ws_closing", "code=$code reason=$reason")
                    val err = mapCloseError(code, reason)
                    deferred.complete(false)
                    _events.tryEmit(LiveEvent.Failed(err))
                }
                webSocket.close(1000, null)
                synchronized(this@GeminiLiveClient) {
                    if (this@GeminiLiveClient.webSocket === webSocket) {
                        this@GeminiLiveClient.webSocket = null
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DiagnosticLog.record(
                    "Live",
                    "ws_failure",
                    "http=${response?.code ?: 0} ${t.message ?: t::class.simpleName.orEmpty()}"
                )
                val err = mapFailure(t, response)
                deferred.complete(false)
                synchronized(this@GeminiLiveClient) {
                    if (this@GeminiLiveClient.webSocket === webSocket) {
                        this@GeminiLiveClient.webSocket = null
                    }
                }
                _events.tryEmit(LiveEvent.Failed(err))
            }
        })
        return deferred
    }

    private fun handleServerPayload(raw: String, deferred: CompletableDeferred<Boolean>) {
        val envelope = try {
            json.decodeFromString<ServerEnvelope>(raw)
        } catch (_: Exception) {
            return
        }
        if (envelope.error != null) {
            val code = envelope.error.code ?: 0
            val msg = sanitizeKey(envelope.error.message.orEmpty())
            DiagnosticLog.record(
                "Live",
                "server_error",
                "code=$code status=${envelope.error.status.orEmpty()} $msg"
            )
            val err = when {
                code == 401 || code == 403 || msg.contains("API_KEY_INVALID", ignoreCase = true) ->
                    LiveError.InvalidKey
                code == 429 -> LiveError.QuotaExceeded
                else -> LiveError.Network(msg.ifBlank { "Server error $code" })
            }
            deferred.complete(false)
            _events.tryEmit(LiveEvent.Failed(err))
            return
        }
        if (envelope.setupComplete != null) {
            deferred.complete(true)
            _events.tryEmit(LiveEvent.Ready)
        }
        val content = envelope.serverContent ?: return
        content.interimInputTranscription?.text?.takeIf { it.isNotEmpty() }?.let {
            _events.tryEmit(LiveEvent.Interim(it))
        }
        content.inputTranscription?.text?.takeIf { it.isNotEmpty() }?.let {
            _events.tryEmit(LiveEvent.Final(it))
        }
        if (content.generationComplete == true || content.turnComplete == true) {
            _events.tryEmit(LiveEvent.TurnComplete)
        }
    }

    private fun mapCloseError(code: Int, reason: String): LiveError {
        val clean = sanitizeKey(reason)
        return when {
            (code == 1007 || code == 1008) &&
                (clean.contains("API key", ignoreCase = true) ||
                    clean.contains("API_KEY_INVALID", ignoreCase = true) ||
                    clean.contains("permission", ignoreCase = true) ||
                    clean.contains("unauthorized", ignoreCase = true)) -> LiveError.InvalidKey
            clean.contains("quota", ignoreCase = true) || clean.contains("429") -> LiveError.QuotaExceeded
            else -> LiveError.Network("WS $code: $clean")
        }
    }

    private fun mapFailure(t: Throwable, response: Response?): LiveError {
        val code = response?.code
        val detail = sanitizeKey(t.message ?: t::class.simpleName.orEmpty())
        return when (code) {
            401, 403 -> LiveError.InvalidKey
            400 -> if (detail.contains("API_KEY_INVALID", ignoreCase = true)) {
                LiveError.InvalidKey
            } else {
                LiveError.Network("HTTP 400: $detail")
            }
            429 -> LiveError.QuotaExceeded
            else -> LiveError.Network(detail.ifBlank { "Connection failed" })
        }
    }

    companion object {
        const val DEFAULT_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val DEFAULT_IDLE_TTL_MS = 45_000L
        const val DEFAULT_FINALIZE_TIMEOUT_MS = 2_000L
        private const val SETUP_TIMEOUT_MS = 10_000L

        private val KEY_QUERY_REGEX = Regex("([?&]key=)[^&\\s]+", RegexOption.IGNORE_CASE)

        fun sanitizeKey(text: String): String = KEY_QUERY_REGEX.replace(text, "$1REDACTED")
    }
}
