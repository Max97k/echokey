package com.akeyboard.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate

class UsageRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private object TestOkioSerializer : OkioSerializer<UsageStore> {
        override val defaultValue: UsageStore = UsageStoreSerializer.defaultValue

        override suspend fun readFrom(source: BufferedSource): UsageStore {
            return UsageStoreSerializer.readFrom(source.inputStream())
        }

        override suspend fun writeTo(t: UsageStore, sink: BufferedSink) {
            UsageStoreSerializer.writeTo(t, sink.outputStream())
        }
    }

    private fun createTestDataStore(
        fileName: String,
        scope: CoroutineScope
    ): DataStore<UsageStore> {
        return DataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = TestOkioSerializer
            ) {
                File(tempFolder.root, fileName).toOkioPath()
            },
            corruptionHandler = ReplaceFileCorruptionHandler { UsageStore() },
            scope = scope
        )
    }

    @Test
    fun `records across multiple dates and models into daily and allTime, then clears`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var currentDay = LocalDate.of(2026, 11, 20)
        val dataStore = createTestDataStore("usage_multi.json", scope)
        val repo = UsageRepository(dataStore, todayProvider = { currentDay })

        try {
            repo.record(
                UsageDelta(
                    modelId = "models/gemini-3.5-transcribe-live",
                    requests = 1L,
                    promptTokens = 100L,
                    responseTokens = 20L,
                    totalTokens = 120L,
                    audioInputTokens = 95L
                )
            )
            repo.record(
                UsageDelta(
                    modelId = "gemini-3.5-transcribe-live",
                    requests = 1L,
                    promptTokens = 50L,
                    responseTokens = 10L,
                    totalTokens = 60L,
                    audioInputTokens = 45L
                )
            )

            currentDay = LocalDate.of(2026, 11, 23)
            repo.record(
                UsageDelta(
                    modelId = "gemini-3.8-live-extended-thinking",
                    requests = 1L,
                    promptTokens = 200L,
                    responseTokens = 40L,
                    thoughtsTokens = 30L,
                    cachedTokens = 15L,
                    totalTokens = 270L,
                    audioInputTokens = 180L
                )
            )

            val store = repo.usage.first()
            assertEquals(2, store.daily.size)
            assertEquals(
                ModelUsageStats(
                    requests = 2L,
                    promptTokens = 150L,
                    responseTokens = 30L,
                    totalTokens = 180L,
                    audioInputTokens = 140L
                ),
                store.daily["2026-11-20"]?.get("gemini-3.5-transcribe-live")
            )
            assertEquals(
                ModelUsageStats(
                    requests = 1L,
                    promptTokens = 200L,
                    responseTokens = 40L,
                    thoughtsTokens = 30L,
                    cachedTokens = 15L,
                    totalTokens = 270L,
                    audioInputTokens = 180L
                ),
                store.allTime["gemini-3.8-live-extended-thinking"]
            )

            repo.clear()
            val cleared = repo.usage.first()
            assertEquals(UsageStore(), cleared)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `prunes daily entries older than retention window while preserving allTime totals`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var currentDay = LocalDate.of(2026, 8, 1)
        val dataStore = createTestDataStore("usage_retention.json", scope)
        val repo = UsageRepository(dataStore, todayProvider = { currentDay }, retentionDays = 90L)

        try {
            repo.record(
                UsageDelta(
                    modelId = "gemini-3.5-transcribe-live",
                    requests = 1L,
                    promptTokens = 100L,
                    responseTokens = 10L,
                    totalTokens = 110L
                )
            )

            currentDay = LocalDate.of(2026, 11, 23)
            repo.record(
                UsageDelta(
                    modelId = "gemini-3.5-transcribe-live",
                    requests = 1L,
                    promptTokens = 50L,
                    responseTokens = 5L,
                    totalTokens = 55L
                )
            )

            val store = repo.usage.first()
            assertFalse(store.daily.containsKey("2026-08-01"))
            assertTrue(store.daily.containsKey("2026-11-23"))
            assertEquals(165L, store.allTime["gemini-3.5-transcribe-live"]?.totalTokens)
            assertEquals(2L, store.allTime["gemini-3.5-transcribe-live"]?.requests)
        } finally {
            scope.cancel()
        }
    }

    @Test(expected = CorruptionException::class)
    fun `serializer throws CorruptionException on malformed json`() = runBlocking {
        UsageStoreSerializer.readFrom(ByteArrayInputStream("{not valid json".toByteArray()))
        Unit
    }

    @Test
    fun `ReplaceFileCorruptionHandler recovers cleanly when file on disk is corrupt`() = runBlocking {
        File(tempFolder.root, "corrupt_usage.json").writeText("{corrupted!!!")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = createTestDataStore("corrupt_usage.json", scope)
        val repo = UsageRepository(dataStore, todayProvider = { LocalDate.of(2026, 11, 23) })

        try {
            assertEquals(UsageStore(), repo.usage.first())
            repo.record(
                UsageDelta(
                    modelId = "gemini-3.5-transcribe-live",
                    requests = 1L,
                    totalTokens = 42L
                )
            )
            assertEquals(42L, repo.usage.first().allTime["gemini-3.5-transcribe-live"]?.totalTokens)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `toSummary computes today, last 30 days, all time, and descending sorting`() {
        val today = LocalDate.of(2026, 11, 23)
        val store = UsageStore(
            daily = mapOf(
                "2026-10-15" to mapOf(
                    "model-a" to ModelUsageStats(requests = 5L, totalTokens = 500L)
                ),
                "2026-10-25" to mapOf(
                    "model-b" to ModelUsageStats(requests = 2L, totalTokens = 200L)
                ),
                "2026-11-23" to mapOf(
                    "model-a" to ModelUsageStats(requests = 1L, totalTokens = 100L),
                    "model-b" to ModelUsageStats(requests = 3L, totalTokens = 300L)
                )
            ),
            allTime = mapOf(
                "model-a" to ModelUsageStats(requests = 6L, totalTokens = 600L),
                "model-b" to ModelUsageStats(requests = 5L, totalTokens = 500L)
            )
        )

        val summary = store.toSummary(today = today)
        assertEquals(400L, summary.todayTokens)
        assertEquals(4L, summary.todayRequests)
        assertEquals(600L, summary.last30DaysTokens)
        assertEquals(6L, summary.last30DaysRequests)
        assertEquals(1100L, summary.allTimeTokens)
        assertEquals(11L, summary.allTimeRequests)

        assertEquals(listOf("2026-11-23", "2026-10-25", "2026-10-15"), summary.dailyEntries.map { it.date })
        assertEquals(listOf("model-b", "model-a"), summary.dailyEntries.first().models.map { it.modelId })
        assertEquals(listOf("model-a", "model-b"), summary.modelTotals.map { it.modelId })
    }
}
