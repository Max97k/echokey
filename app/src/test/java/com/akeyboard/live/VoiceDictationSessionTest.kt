package com.akeyboard.live

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.akeyboard.audio.AudioCaptureManager
import com.akeyboard.audio.AudioCaptureResult
import com.akeyboard.audio.AudioFailureReason
import com.akeyboard.data.ApiKeyStore
import com.akeyboard.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class VoiceDictationSessionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        serverSockets.forEach { runCatching { it.close(1000, null) } }
        serverSockets.clear()
        runCatching { server.close() }
    }

    @Test
    fun `rotates websocket connection after each finalized utterance`() = runBlocking {
        val connectionCounter = AtomicInteger(0)
        repeat(3) {
            server.enqueue(
                MockResponse.Builder()
                    .webSocketUpgrade(object : WebSocketListener() {
                        private val connIndex = connectionCounter.incrementAndGet()
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            serverSockets += webSocket
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.contains("\"setup\"")) {
                                webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                            } else if (text.contains("activityEnd")) {
                                val phrase = if (connIndex <= 1) "第一句" else "第二句"
                                webSocket.send(
                                    "{\"serverContent\":{\"inputTranscription\":{\"text\":\"$phrase\"},\"turnComplete\":true}}".encodeUtf8()
                                )
                            }
                        }
                    })
                    .build()
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.root, "test.preferences_pb")
        }
        val apiKeyStore = ApiKeyStore(dataStore)
        val settingsRepo = SettingsRepository(dataStore)

        val fakeAudio = object : AudioCaptureManager() {
            private var ch: Channel<ByteArray>? = null
            override fun start(scope: CoroutineScope): AudioCaptureResult {
                ch = Channel<ByteArray>(capacity = 4).also { it.trySend(ByteArray(32)) }
                return AudioCaptureResult.Started(ch!!)
            }

            override fun stop() {
                ch?.close()
                ch = null
            }
        }

        val okHttpClient = OkHttpClient()
        val liveClient = GeminiLiveClient(
            okHttpClient = okHttpClient,
            baseWsUrl = server.url("/ws").toString().replace("http://", "ws://"),
            base64Encoder = { bytes -> Base64.getEncoder().encodeToString(bytes) }
        )

        val commits = mutableListOf<String>()
        var nextCommit = CompletableDeferred<Unit>()
        val session = VoiceDictationSession(
            scope = scope,
            audioManager = fakeAudio,
            liveClient = liveClient,
            apiKeyStore = apiKeyStore,
            settingsRepository = settingsRepo,
            hasMicPermission = { true },
            isNetworkAvailable = { true },
            onPhaseChanged = {},
            onInterim = {},
            onFinal = { text ->
                commits += text
                nextCommit.complete(Unit)
            },
            onFinishComposing = {},
            onError = { err -> throw AssertionError("Unexpected error: $err") },
            loadApiKey = { "test-key" }
        )

        try {
            session.onMicPressed()
            kotlinx.coroutines.delay(150)
            session.onMicReleased()
            withTimeout(3_000) { nextCommit.await() }

            // Wait briefly for rotateWarmConnection() to open the second WebSocket
            kotlinx.coroutines.delay(200)
            nextCommit = CompletableDeferred()

            session.onMicPressed()
            kotlinx.coroutines.delay(150)
            session.onMicReleased()
            withTimeout(3_000) { nextCommit.await() }

            assertEquals(listOf("第一句", "第二句"), commits)
        } finally {
            session.close()
            scope.cancel()
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
            kotlinx.coroutines.delay(100)
        }
    }

    @Test
    fun `mid-stream audio capture error stops session, clears buffer, emits MIC_BUSY, and allows recovery`() = runBlocking {
        val socket1Closed = CompletableDeferred<Unit>()
        val socket2ActivityStarted = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        serverSockets += webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        } else if (text.contains("activityStart")) {
                            webSocket.send(
                                "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"未完成文字\"}}}".encodeUtf8()
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        socket1Closed.complete(Unit)
                        webSocket.close(code, reason)
                    }
                })
                .build()
        )
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        serverSockets += webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        } else if (text.contains("activityStart")) {
                            socket2ActivityStarted.complete(Unit)
                        } else if (text.contains("activityEnd")) {
                            webSocket.send(
                                "{\"serverContent\":{\"inputTranscription\":{\"text\":\"恢復成功\"},\"turnComplete\":true}}".encodeUtf8()
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
                .build()
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.root, "capture_error_test.preferences_pb")
        }
        val apiKeyStore = ApiKeyStore(dataStore)
        val settingsRepo = SettingsRepository(dataStore)

        val stopCount = AtomicInteger(0)
        val fakeAudio = object : AudioCaptureManager() {
            private var ch: Channel<ByteArray>? = null
            override fun start(scope: CoroutineScope): AudioCaptureResult {
                ch = Channel<ByteArray>(capacity = 4).also { it.trySend(ByteArray(32)) }
                return AudioCaptureResult.Started(ch!!)
            }

            override fun stop() {
                stopCount.incrementAndGet()
                ch?.close()
                ch = null
            }
        }

        val okHttpClient = OkHttpClient()
        val liveClient = GeminiLiveClient(
            okHttpClient = okHttpClient,
            baseWsUrl = server.url("/ws").toString().replace("http://", "ws://"),
            base64Encoder = { bytes -> Base64.getEncoder().encodeToString(bytes) }
        )

        val phases = CopyOnWriteArrayList<VoiceSessionPhase>()
        val commits = CopyOnWriteArrayList<String>()
        val finishComposingCount = AtomicInteger(0)
        var recordingDeferred = CompletableDeferred<Unit>()
        val interimDeferred = CompletableDeferred<Unit>()
        val errorDeferred = CompletableDeferred<VoiceSessionError>()
        val finalCommitDeferred = CompletableDeferred<Unit>()
        var failOnError = false

        val session = VoiceDictationSession(
            scope = scope,
            audioManager = fakeAudio,
            liveClient = liveClient,
            apiKeyStore = apiKeyStore,
            settingsRepository = settingsRepo,
            hasMicPermission = { true },
            isNetworkAvailable = { true },
            onPhaseChanged = { phase ->
                phases += phase
                if (phase == VoiceSessionPhase.RECORDING) {
                    recordingDeferred.complete(Unit)
                }
            },
            onInterim = { text ->
                if (text.isNotEmpty()) {
                    interimDeferred.complete(Unit)
                }
            },
            onFinal = { text ->
                commits += text
                finalCommitDeferred.complete(Unit)
            },
            onFinishComposing = {
                finishComposingCount.incrementAndGet()
            },
            onError = { err ->
                if (failOnError) {
                    throw AssertionError("Unexpected error during recovery: $err")
                }
                errorDeferred.complete(err)
            },
            loadApiKey = { "test-key" }
        )

        try {
            session.onMicPressed()
            withTimeout(3_000) { recordingDeferred.await() }
            withTimeout(3_000) { interimDeferred.await() }

            fakeAudio.onCaptureError?.invoke(AudioFailureReason.MIC_UNAVAILABLE)

            val err = withTimeout(3_000) { errorDeferred.await() }
            withTimeout(3_000) { socket1Closed.await() }

            assertEquals(VoiceSessionError.MIC_BUSY, err)
            assertEquals(VoiceSessionPhase.IDLE, phases.last())
            assertEquals(1, finishComposingCount.get())
            assertEquals(1, stopCount.get())
            assertEquals(emptyList<String>(), commits)

            // Verify subsequent hold-to-speak recovers cleanly
            failOnError = true
            recordingDeferred = CompletableDeferred()
            session.onMicPressed()
            withTimeout(3_000) { recordingDeferred.await() }
            withTimeout(3_000) { socket2ActivityStarted.await() }
            session.onMicReleased()
            withTimeout(3_000) { finalCommitDeferred.await() }

            assertEquals(listOf("恢復成功"), commits)
        } finally {
            session.close()
            scope.cancel()
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
            kotlinx.coroutines.delay(100)
        }
    }

    @Test
    fun `sanitizeSingleLine strips edge and inner line breaks while preserving plain spaces`() {
        assertEquals("你好世界", VoiceDictationSession.sanitizeSingleLine("你好世界\n"))
        assertEquals("你好世界", VoiceDictationSession.sanitizeSingleLine("\r\n  你好世界  \r\n"))
        assertEquals("第一句 第二句", VoiceDictationSession.sanitizeSingleLine("第一句 \n 第二句"))
        assertEquals("A B C D", VoiceDictationSession.sanitizeSingleLine("A\u2028B\u2029C\u0085D"))
        assertEquals("", VoiceDictationSession.sanitizeSingleLine(" \r\n\t\n "))
        assertEquals("hello ", VoiceDictationSession.sanitizeSingleLine("hello "))
    }

    @Test
    fun `strips line breaks from Gemini interim and final transcripts`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        serverSockets += webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        } else if (text.contains("activityStart")) {
                            webSocket.send(
                                "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"早安\\n\"}}}".encodeUtf8()
                            )
                        } else if (text.contains("activityEnd")) {
                            webSocket.send(
                                "{\"serverContent\":{\"inputTranscription\":{\"text\":\"早安\\n大家\\r\\n\"},\"turnComplete\":true}}".encodeUtf8()
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
                .build()
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.root, "sanitize_transcript_test.preferences_pb")
        }
        val apiKeyStore = ApiKeyStore(dataStore)
        val settingsRepo = SettingsRepository(dataStore)

        val fakeAudio = object : AudioCaptureManager() {
            private var ch: Channel<ByteArray>? = null
            override fun start(scope: CoroutineScope): AudioCaptureResult {
                ch = Channel<ByteArray>(capacity = 4).also { it.trySend(ByteArray(32)) }
                return AudioCaptureResult.Started(ch!!)
            }

            override fun stop() {
                ch?.close()
                ch = null
            }
        }

        val okHttpClient = OkHttpClient()
        val liveClient = GeminiLiveClient(
            okHttpClient = okHttpClient,
            baseWsUrl = server.url("/ws").toString().replace("http://", "ws://"),
            base64Encoder = { bytes -> Base64.getEncoder().encodeToString(bytes) }
        )

        val interims = CopyOnWriteArrayList<String>()
        val finals = CopyOnWriteArrayList<String>()
        val interimDeferred = CompletableDeferred<Unit>()
        val finalDeferred = CompletableDeferred<Unit>()

        val session = VoiceDictationSession(
            scope = scope,
            audioManager = fakeAudio,
            liveClient = liveClient,
            apiKeyStore = apiKeyStore,
            settingsRepository = settingsRepo,
            hasMicPermission = { true },
            isNetworkAvailable = { true },
            onPhaseChanged = {},
            onInterim = { text ->
                interims += text
                interimDeferred.complete(Unit)
            },
            onFinal = { text ->
                finals += text
                finalDeferred.complete(Unit)
            },
            onFinishComposing = {},
            onError = { err -> throw AssertionError("Unexpected error: $err") },
            loadApiKey = { "test-key" }
        )

        try {
            session.onMicPressed()
            withTimeout(3_000) { interimDeferred.await() }
            session.onMicReleased()
            withTimeout(3_000) { finalDeferred.await() }

            assertEquals(listOf("早安"), interims)
            assertEquals(listOf("早安 大家"), finals)
        } finally {
            session.close()
            scope.cancel()
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
            kotlinx.coroutines.delay(100)
        }
    }
}
