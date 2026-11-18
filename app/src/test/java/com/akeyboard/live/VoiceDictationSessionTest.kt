package com.akeyboard.live

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.akeyboard.audio.AudioCaptureManager
import com.akeyboard.audio.AudioCaptureResult
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
}
