package com.akeyboard.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelRegistryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ModelRegistryClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ModelRegistryClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun paginatesFiltersAndPrioritizesTranscribeModels() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                {
                  "models": [
                    {
                      "name": "models/gemini-3.8-live",
                      "displayName": "Gemini 3.8 Live",
                      "supportedGenerationMethods": ["bidiGenerateContent"]
                    },
                    {
                      "name": "models/gemini-text-only",
                      "displayName": "Text Only",
                      "supportedGenerationMethods": ["generateContent"]
                    }
                  ],
                  "nextPageToken": "page2"
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                {
                  "models": [
                    {
                      "name": "models/gemini-3.5-transcribe-live",
                      "displayName": "Gemini 3.5 Transcribe Live",
                      "supportedGenerationMethods": ["bidiGenerateContent"]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val res = client.fetchLiveModels("test-key-123")
        assertTrue(res.isSuccess)
        val models = res.getOrThrow()
        assertEquals(2, models.size)
        assertEquals("gemini-3.5-transcribe-live", models[0].id)
        assertTrue(models[0].isTranscribeModel)
        assertEquals("gemini-3.8-live", models[1].id)

        val req1 = server.takeRequest()
        assertEquals("test-key-123", req1.headers["x-goog-api-key"])
    }

    @Test
    fun maps400ApiKeyInvalidToInvalidKey() = runTest {
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
            )
        )

        val res = client.fetchLiveModels("bad-key")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is ModelRegistryException.InvalidKey)
    }
}
