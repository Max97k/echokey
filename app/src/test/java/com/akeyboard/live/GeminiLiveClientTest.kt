// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.live

import com.akeyboard.data.UsageDelta
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun parsesStandaloneAndCumulativeUsageMetadataTrustingTopLevelTotals() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        } else if (text.contains("activityEnd")) {
                            webSocket.send(
                                """{"usageMetadata":{"promptTokenCount":120,"responseTokenCount":10,"totalTokenCount":130,"promptTokensDetails":[{"modality":"AUDIO","tokenCount":100},{"modality":"TEXT","tokenCount":10}]}}""".encodeUtf8()
                            )
                            webSocket.send(
                                """{"usageMetadata":{"promptTokenCount":150,"responseTokenCount":25,"thoughtsTokenCount":15,"cachedContentTokenCount":20,"totalTokenCount":190,"promptTokensDetails":[{"modality":"AUDIO","tokenCount":125}]}}""".encodeUtf8()
                            )
                            webSocket.send(
                                """{"serverContent":{"inputTranscription":{"text":"Done"},"turnComplete":true}}""".encodeUtf8()
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
        val deltas = CopyOnWriteArrayList<UsageDelta>()
        val client = GeminiLiveClient(
            okHttpClient = okHttp,
            baseWsUrl = wsUrl,
            base64Encoder = { Base64.getEncoder().encodeToString(it) },
            onUsageReported = { deltas += it }
        )

        val collected = mutableListOf<LiveEvent>()
        withTimeout(5_000) {
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.take(3).toList(collected)
            }

            val audio = Channel<ByteArray>(capacity = 4)
            audio.send(ByteArray(16) { 1 })
            audio.close()

            client.startUtterance(this, "secret-key", "models/gemini-3.8-live-extended-thinking", audio)
            delay(250)
            client.finishUtterance(this)
            collectJob.join()
            client.close()
        }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()

        assertEquals(
            listOf(
                LiveEvent.Ready,
                LiveEvent.Final("Done"),
                LiveEvent.TurnComplete
            ),
            collected
        )
        assertEquals(
            listOf(
                UsageDelta(
                    modelId = "gemini-3.8-live-extended-thinking",
                    requests = 1L,
                    promptTokens = 120L,
                    responseTokens = 10L,
                    thoughtsTokens = 0L,
                    cachedTokens = 0L,
                    totalTokens = 130L,
                    audioInputTokens = 100L
                ),
                UsageDelta(
                    modelId = "gemini-3.8-live-extended-thinking",
                    requests = 0L,
                    promptTokens = 30L,
                    responseTokens = 15L,
                    thoughtsTokens = 15L,
                    cachedTokens = 20L,
                    totalTokens = 60L,
                    audioInputTokens = 25L
                )
            ),
            deltas
        )
    }

    @Test
    fun drainAndDetachCapturesTrailingUsageMetadataWithoutEmittingEventsToNewConnection() = runBlocking {
        val serverSocket1 = CompletableDeferred<WebSocket>()
        val socket1Closed = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket1.complete(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        socket1Closed.complete(Unit)
                        webSocket.close(code, reason)
                    }
                })
                .build()
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/ws"
        val okHttp = OkHttpClient()
        val deltas = CopyOnWriteArrayList<UsageDelta>()
        val usageReceived = CompletableDeferred<UsageDelta>()
        val client = GeminiLiveClient(
            okHttpClient = okHttp,
            baseWsUrl = wsUrl,
            base64Encoder = { Base64.getEncoder().encodeToString(it) },
            onUsageReported = {
                deltas += it
                usageReceived.complete(it)
            },
            drainTimeoutMs = 1_500L
        )

        val collected = CopyOnWriteArrayList<LiveEvent>()
        withTimeout(5_000) {
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { collected += it }
            }

            client.warmUp(this, "secret-key", "gemini-3.5-transcribe-live")
            val ws1 = serverSocket1.await()
            delay(150)

            client.drainAndDetach()

            ws1.send(
                """{"serverContent":{"turnComplete":true},"usageMetadata":{"promptTokenCount":200,"responseTokenCount":12,"totalTokenCount":212,"promptTokensDetails":[{"modality":"AUDIO","tokenCount":190}]}}""".encodeUtf8()
            )
            val delta = usageReceived.await()
            socket1Closed.await()

            assertEquals(
                UsageDelta(
                    modelId = "gemini-3.5-transcribe-live",
                    requests = 1L,
                    promptTokens = 200L,
                    responseTokens = 12L,
                    totalTokens = 212L,
                    audioInputTokens = 190L
                ),
                delta
            )
            assertEquals(listOf(LiveEvent.Ready), collected)

            collectJob.cancel()
            client.close()
        }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    @Test
    fun emitsEstimatedFallbackOnDrainWhenUsageMetadataIsMissing() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        } else if (text.contains("activityEnd")) {
                            webSocket.send(
                                """{"serverContent":{"inputTranscription":{"text":"你好世界"}}}""".encodeUtf8()
                            )
                            webSocket.send(
                                """{"serverContent":{"generationComplete":true}}""".encodeUtf8()
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
        val deltas = CopyOnWriteArrayList<UsageDelta>()
        val usageReceived = CompletableDeferred<UsageDelta>()
        val client = GeminiLiveClient(
            okHttpClient = okHttp,
            baseWsUrl = wsUrl,
            base64Encoder = { Base64.getEncoder().encodeToString(it) },
            onUsageReported = {
                deltas += it
                usageReceived.complete(it)
            },
            drainTimeoutMs = 150L
        )

        val collected = mutableListOf<LiveEvent>()
        withTimeout(5_000) {
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.take(3).toList(collected)
            }

            val audio = Channel<ByteArray>(capacity = 4)
            audio.send(ByteArray(32_000) { 1 })
            audio.close()

            client.startUtterance(this, "secret-key", "gemini-3.5-transcribe-live", audio)
            delay(250)
            client.finishUtterance(this)
            collectJob.join()
            client.drainAndDetach()

            val delta = usageReceived.await()
            assertEquals(
                UsageDelta(
                    modelId = "gemini-3.5-transcribe-live",
                    requests = 1L,
                    promptTokens = 25L,
                    responseTokens = 2L,
                    totalTokens = 27L,
                    audioInputTokens = 25L,
                    isEstimated = true
                ),
                delta
            )
            assertEquals(1, deltas.size)
            client.close()
        }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    @Test
    fun doesNotEmitFallbackWhenOfficialUsageMetadataWasReceived() = runBlocking {
        val socketClosed = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                        } else if (text.contains("activityEnd")) {
                            webSocket.send(
                                """{"serverContent":{"inputTranscription":{"text":"測試"},"turnComplete":true},"usageMetadata":{"promptTokenCount":80,"responseTokenCount":5,"totalTokenCount":85,"promptTokensDetails":[{"modality":"AUDIO","tokenCount":75}]}}""".encodeUtf8()
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        socketClosed.complete(Unit)
                        webSocket.close(code, reason)
                    }
                })
                .build()
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/ws"
        val okHttp = OkHttpClient()
        val deltas = CopyOnWriteArrayList<UsageDelta>()
        val client = GeminiLiveClient(
            okHttpClient = okHttp,
            baseWsUrl = wsUrl,
            base64Encoder = { Base64.getEncoder().encodeToString(it) },
            onUsageReported = { deltas += it },
            drainTimeoutMs = 150L
        )

        withTimeout(5_000) {
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.take(3).toList()
            }

            val audio = Channel<ByteArray>(capacity = 4)
            audio.send(ByteArray(32_000) { 1 })
            audio.close()

            client.startUtterance(this, "secret-key", "gemini-2.5-flash-native-audio-latest", audio)
            delay(250)
            client.finishUtterance(this)
            collectJob.join()
            client.drainAndDetach()
            socketClosed.await()
            delay(100)
            client.close()
        }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()

        assertEquals(1, deltas.size)
        assertFalse(deltas.first().isEstimated)
        assertEquals(85L, deltas.first().totalTokens)
    }

    @Test
    fun consecutiveWarmUpCallsWhileConnectingReuseSingleConnection() = runBlocking {
        val openCount = AtomicInteger(0)
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        openCount.incrementAndGet()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"setup\"")) {
                            Thread {
                                Thread.sleep(150)
                                webSocket.send("{\"setupComplete\":{}}".encodeUtf8())
                            }.start()
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
            client.warmUp(this, "secret-key", "gemini-3.5-transcribe-live")
            client.warmUp(this, "secret-key", "gemini-3.5-transcribe-live")
            collectJob.join()
            client.close()
        }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()

        assertEquals(1, openCount.get())
        assertEquals(listOf(LiveEvent.Ready), collected)
    }
}
