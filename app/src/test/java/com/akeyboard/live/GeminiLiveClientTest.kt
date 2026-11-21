package com.akeyboard.live

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GeminiLiveClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun streamsSetupBinaryInterimAndFinalEvents() = runBlocking {
        val receivedFrames = mutableListOf<String>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedFrames += text
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        } else if (text.contains("activityEnd")) {
                            webSocket.send(
                                "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"Hello\"}}}".encodeUtf8()
                            )
                            webSocket.send(
                                "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Hello world.\"},\"generationComplete\":true}}".encodeUtf8()
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
                .build()
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/ws"
        val okHttp = OkHttpClient()
        val client = GeminiLiveClient(
            okHttpClient = okHttp,
            baseWsUrl = wsUrl,
            base64Encoder = { Base64.getEncoder().encodeToString(it) }
        )

        val collected = mutableListOf<LiveEvent>()
        withTimeout(5_000) {
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.take(4).toList(collected)
            }

            val audio = Channel<ByteArray>(capacity = 8)
            audio.send(ByteArray(16) { 1 })
            audio.close()

            client.startUtterance(this, "secret-key", "gemini-3.5-transcribe-live", audio)
            delay(300)
            client.finishUtterance(this)
            collectJob.join()
            client.close()
        }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()

        assertTrue(receivedFrames.first().contains("gemini-3.5-transcribe-live"))
        assertTrue(receivedFrames.any { it.contains("activityStart") })
        assertTrue(receivedFrames.any { it.contains("activityEnd") })
        assertEquals(
            listOf(
                LiveEvent.Ready,
                LiveEvent.Interim("Hello"),
                LiveEvent.Final("Hello world."),
                LiveEvent.TurnComplete
            ),
            collected
        )
    }

    @Test
    fun emitsFailedWithRegionNotSupportedWhenServerErrorIndicatesLocationBlock() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send(
                                """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}""".encodeUtf8()
                            )
                            webSocket.close(1000, "error sent")
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
                .build()
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/ws"
        val okHttp = OkHttpClient()
        val client = GeminiLiveClient(
            okHttpClient = okHttp,
            baseWsUrl = wsUrl,
            base64Encoder = { Base64.getEncoder().encodeToString(it) }
        )

        val collected = mutableListOf<LiveEvent>()
        withTimeout(5_000) {
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.take(1).toList(collected)
            }

            val audio = Channel<ByteArray>(capacity = 1)
            audio.close()
            client.startUtterance(this, "test-key", "gemini-3.5-transcribe-live", audio)
            collectJob.join()
            client.close()
        }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()

        assertEquals(listOf(LiveEvent.Failed(LiveError.RegionNotSupported)), collected)
    }

    @Test
    fun sanitizeKeyRedactsQueryParam() {
        val raw = "Failed connecting to wss://example.com/ws?key=AIzaSySecret123&foo=1"
        val cleaned = GeminiLiveClient.sanitizeKey(raw)
        assertFalse(cleaned.contains("AIzaSySecret123"))
        assertTrue(cleaned.contains("?key=REDACTED"))
    }
}
