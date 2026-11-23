package com.akeyboard.live

import android.util.Base64
import com.akeyboard.data.UsageDelta
import com.akeyboard.util.DiagnosticLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    data object RegionNotSupported : LiveError()
    data class Network(val detail: String) : LiveError()
}

sealed class LiveEvent {
    data object Ready : LiveEvent()
    data class Interim(val text: String) : LiveEvent()
    data class Final(val text: String) : LiveEvent()
    data object TurnComplete : LiveEvent()
    data class Failed(val error: LiveError) : LiveEvent()
}

private class ConnectionUsageTracker(private val modelId: String) {
    private var lastPrompt = 0L
    private var lastResponse = 0L
    private var lastThoughts = 0L
    private var lastCached = 0L
    private var lastTotal = 0L
    private var lastAudioInput = 0L
    private var hasReportedRequest = false

    @Synchronized
    fun computeDelta(meta: UsageMetadata): UsageDelta? {
        val prompt = (meta.promptTokenCount ?: 0).toLong().coerceAtLeast(0L)
        val response = (meta.responseTokenCount ?: 0).toLong().coerceAtLeast(0L)
        val thoughts = (meta.thoughtsTokenCount ?: 0).toLong().coerceAtLeast(0L)
        val cached = (meta.cachedContentTokenCount ?: 0).toLong().coerceAtLeast(0L)
        val rawTotal = (meta.totalTokenCount ?: 0).toLong().coerceAtLeast(0L)
        val total = if (rawTotal > 0L) rawTotal else (prompt + response + thoughts + cached)
        val audioInput = meta.promptTokensDetails.orEmpty()
            .filter { it.modality.equals("AUDIO", ignoreCase = true) }
            .sumOf { (it.tokenCount ?: 0).toLong().coerceAtLeast(0L) }

        val dPrompt = (prompt - lastPrompt).coerceAtLeast(0L)
        val dResponse = (response - lastResponse).coerceAtLeast(0L)
        val dThoughts = (thoughts - lastThoughts).coerceAtLeast(0L)
        val dCached = (cached - lastCached).coerceAtLeast(0L)
        val dTotal = (total - lastTotal).coerceAtLeast(0L)
        val dAudioInput = (audioInput - lastAudioInput).coerceAtLeast(0L)

        lastPrompt = maxOf(lastPrompt, prompt)
        lastResponse = maxOf(lastResponse, response)
        lastThoughts = maxOf(lastThoughts, thoughts)
        lastCached = maxOf(lastCached, cached)
        lastTotal = maxOf(lastTotal, total)
        lastAudioInput = maxOf(lastAudioInput, audioInput)

        val anyPositive = dPrompt > 0L || dResponse > 0L || dThoughts > 0L ||
            dCached > 0L || dTotal > 0L || dAudioInput > 0L
        if (!anyPositive) return null

        val req = if (!hasReportedRequest) {
            hasReportedRequest = true
            1L
        } else {
            0L
        }
        return UsageDelta(
            modelId = modelId,
            requests = req,
            promptTokens = dPrompt,
            responseTokens = dResponse,
            thoughtsTokens = dThoughts,
            cachedTokens = dCached,
            totalTokens = dTotal,
            audioInputTokens = dAudioInput
        )
    }
}

class GeminiLiveClient(
    private val okHttpClient: OkHttpClient,
    private val baseWsUrl: String = DEFAULT_WS_URL,
    private val idleTtlMs: Long = DEFAULT_IDLE_TTL_MS,
    private val finalizeTimeoutMs: Long = DEFAULT_FINALIZE_TIMEOUT_MS,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
    private val base64Encoder: (ByteArray) -> String = { bytes ->
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    },
    private val onUsageReported: (UsageDelta) -> Unit = {},
    private val drainTimeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS,
    private val drainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private var hasSentActivityStart = false
    private val drainingSockets = mutableMapOf<WebSocket, Job>()

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
        closeInternal(preserveUtteranceFlag = false, closeDraining = false)
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
        hasSentActivityStart = false

        val deferred = if (isWarmFor(apiKey, modelId, languageCodes, smartMode)) {
            setupCompleteDeferred!!
        } else {
            closeInternal(preserveUtteranceFlag = true, closeDraining = false)
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
            hasSentActivityStart = true
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
        streamJob?.join()
        val ws = synchronized(this) { webSocket }
        if (ws != null && hasSentActivityStart && setupCompleteDeferred?.isCompleted == true) {
            ws.send(json.encodeToString(RealtimeActivityEndEnvelope()))
        }
        resetIdleTtl(scope)
        return ws != null
    }

    fun finalizeTimeout(): Long = finalizeTimeoutMs

    fun drainAndDetach() {
        synchronized(this) {
            val oldWs = webSocket
            webSocket = null
            isUtteranceActive = false
            hasSentActivityStart = false
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
            if (oldWs != null) {
                val timeoutJob = drainScope.launch(start = CoroutineStart.LAZY) {
                    delay(drainTimeoutMs)
                    finishDrainingSocket(oldWs)
                }
                drainingSockets[oldWs] = timeoutJob
                timeoutJob.start()
            }
        }
    }

    fun close() {
        closeInternal(preserveUtteranceFlag = false, closeDraining = true)
    }

    private fun finishDrainingSocket(ws: WebSocket) {
        val timeoutJob = synchronized(this) {
            drainingSockets.remove(ws)
        }
        timeoutJob?.cancel()
        runCatching { ws.close(1000, "drained") }
    }

    private fun closeInternal(preserveUtteranceFlag: Boolean, closeDraining: Boolean) {
        val activeWs: WebSocket?
        val drainedList: List<Pair<WebSocket, Job>>
        synchronized(this) {
            if (!preserveUtteranceFlag) {
                isUtteranceActive = false
            }
            hasSentActivityStart = false
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
            activeWs = webSocket
            webSocket = null
            if (closeDraining && drainingSockets.isNotEmpty()) {
                drainedList = drainingSockets.entries.map { it.key to it.value }
                drainingSockets.clear()
            } else {
                drainedList = emptyList()
            }
        }
        activeWs?.let { runCatching { it.close(1000, "closed") } }
        drainedList.forEach { (ws, job) ->
            job.cancel()
            runCatching { ws.close(1000, "closed") }
        }
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
        val tracker = ConnectionUsageTracker(cleanModel)
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
                handleServerPayload(webSocket, text, deferred, tracker)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerPayload(webSocket, bytes.utf8(), deferred, tracker)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val isActive = synchronized(this@GeminiLiveClient) {
                    this@GeminiLiveClient.webSocket === webSocket
                }
                if (!isActive) {
                    finishDrainingSocket(webSocket)
                    return
                }
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
                val isActive = synchronized(this@GeminiLiveClient) {
                    this@GeminiLiveClient.webSocket === webSocket
                }
                if (!isActive) {
                    finishDrainingSocket(webSocket)
                    return
                }
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

    private fun handleServerPayload(
        ws: WebSocket,
        raw: String,
        deferred: CompletableDeferred<Boolean>,
        tracker: ConnectionUsageTracker
    ) {
        val isActive: Boolean
        val isDraining: Boolean
        synchronized(this) {
            isActive = webSocket === ws
            isDraining = !isActive && drainingSockets.containsKey(ws)
        }
        if (!isActive && !isDraining) return

        val envelope = try {
            json.decodeFromString<ServerEnvelope>(raw)
        } catch (_: Exception) {
            return
        }

        envelope.usageMetadata?.let { meta ->
            tracker.computeDelta(meta)?.let(onUsageReported)
        }

        if (!isActive) {
            if (envelope.usageMetadata != null ||
                envelope.serverContent?.turnComplete == true ||
                envelope.serverContent?.generationComplete == true ||
                envelope.error != null
            ) {
                finishDrainingSocket(ws)
            }
            return
        }

        if (envelope.error != null) {
            val code = envelope.error.code ?: 0
            val status = envelope.error.status.orEmpty()
            val msg = sanitizeKey(envelope.error.message.orEmpty())
            DiagnosticLog.record(
                "Live",
                "server_error",
                "code=$code status=$status $msg"
            )
            val isRegion = msg.contains("location is not supported", ignoreCase = true) ||
                msg.contains("Regional restrictions", ignoreCase = true) ||
                (status.equals("FAILED_PRECONDITION", ignoreCase = true) && msg.contains("location", ignoreCase = true))

            val err = when {
                isRegion -> LiveError.RegionNotSupported
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
        val isRegion = clean.contains("location is not supported", ignoreCase = true) ||
            clean.contains("Regional restrictions", ignoreCase = true) ||
            (clean.contains("FAILED_PRECONDITION", ignoreCase = true) && clean.contains("location", ignoreCase = true))
        if (isRegion) return LiveError.RegionNotSupported

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
        val isRegion = detail.contains("location is not supported", ignoreCase = true) ||
            detail.contains("Regional restrictions", ignoreCase = true) ||
            (detail.contains("FAILED_PRECONDITION", ignoreCase = true) && detail.contains("location", ignoreCase = true))
        if (isRegion) return LiveError.RegionNotSupported

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
        const val DEFAULT_DRAIN_TIMEOUT_MS = 900L
        private const val SETUP_TIMEOUT_MS = 10_000L

        private val KEY_QUERY_REGEX = Regex("([?&]key=)[^&\\s]+", RegexOption.IGNORE_CASE)

        fun sanitizeKey(text: String): String = KEY_QUERY_REGEX.replace(text, "$1REDACTED")
    }
}
