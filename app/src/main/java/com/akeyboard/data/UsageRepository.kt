package com.akeyboard.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.akeyboard.util.DiagnosticLog
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Serializable
data class UsageStore(
    val schemaVersion: Int = 1,
    val daily: Map<String, Map<String, ModelUsageStats>> = emptyMap(),
    val allTime: Map<String, ModelUsageStats> = emptyMap()
)

@Serializable
data class ModelUsageStats(
    val requests: Long = 0L,
    val promptTokens: Long = 0L,
    val responseTokens: Long = 0L,
    val thoughtsTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val audioInputTokens: Long = 0L
) {
    operator fun plus(other: ModelUsageStats): ModelUsageStats = ModelUsageStats(
        requests = requests + other.requests,
        promptTokens = promptTokens + other.promptTokens,
        responseTokens = responseTokens + other.responseTokens,
        thoughtsTokens = thoughtsTokens + other.thoughtsTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
        audioInputTokens = audioInputTokens + other.audioInputTokens
    )
}

data class UsageDelta(
    val modelId: String,
    val requests: Long = 0L,
    val promptTokens: Long = 0L,
    val responseTokens: Long = 0L,
    val thoughtsTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val audioInputTokens: Long = 0L
) {
    val hasAnyUsage: Boolean
        get() = requests > 0L ||
            promptTokens > 0L ||
            responseTokens > 0L ||
            thoughtsTokens > 0L ||
            cachedTokens > 0L ||
            totalTokens > 0L ||
            audioInputTokens > 0L
}

object UsageStoreSerializer : Serializer<UsageStore> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override val defaultValue: UsageStore = UsageStore()

    override suspend fun readFrom(input: InputStream): UsageStore {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            json.decodeFromString<UsageStore>(text)
        } catch (e: SerializationException) {
            DiagnosticLog.record("Usage", "corrupt", e::class.simpleName.orEmpty())
            throw CorruptionException("Cannot read usage store.", e)
        } catch (e: IllegalArgumentException) {
            DiagnosticLog.record("Usage", "corrupt", e::class.simpleName.orEmpty())
            throw CorruptionException("Cannot read usage store.", e)
        }
    }

    override suspend fun writeTo(t: UsageStore, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

class UsageRepository(
    private val dataStore: DataStore<UsageStore>,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    private val retentionDays: Long = DEFAULT_RETENTION_DAYS
) {
    val usage: Flow<UsageStore> = dataStore.data

    suspend fun record(delta: UsageDelta) {
        if (!delta.hasAnyUsage) return
        val modelId = delta.modelId.removePrefix("models/").ifBlank { "unknown" }
        val increment = ModelUsageStats(
            requests = delta.requests.coerceAtLeast(0L),
            promptTokens = delta.promptTokens.coerceAtLeast(0L),
            responseTokens = delta.responseTokens.coerceAtLeast(0L),
            thoughtsTokens = delta.thoughtsTokens.coerceAtLeast(0L),
            cachedTokens = delta.cachedTokens.coerceAtLeast(0L),
            totalTokens = delta.totalTokens.coerceAtLeast(0L),
            audioInputTokens = delta.audioInputTokens.coerceAtLeast(0L)
        )

        var recordedSummary = ""
        dataStore.updateData { current ->
            val today = todayProvider()
            val todayKey = today.toString()
            val cutoff = today.minusDays((retentionDays - 1L).coerceAtLeast(0L))

            val updatedDaily = current.daily.mapNotNull { (dateKey, models) ->
                val parsed = parseLocalDateOrNull(dateKey)
                if (parsed == null || parsed.isBefore(cutoff)) {
                    null
                } else {
                    dateKey to models
                }
            }.toMap(LinkedHashMap())

            val todayModels = updatedDaily[todayKey].orEmpty().toMutableMap()
            val updatedDayModel = (todayModels[modelId] ?: ModelUsageStats()) + increment
            todayModels[modelId] = updatedDayModel
            updatedDaily[todayKey] = todayModels

            val updatedAllTime = current.allTime.toMutableMap()
            val updatedAllTimeModel = (updatedAllTime[modelId] ?: ModelUsageStats()) + increment
            updatedAllTime[modelId] = updatedAllTimeModel

            recordedSummary = "date=$todayKey model=$modelId +total=${increment.totalTokens} " +
                "dayTotal=${updatedDayModel.totalTokens} allTimeTotal=${updatedAllTimeModel.totalTokens}"

            current.copy(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                daily = updatedDaily,
                allTime = updatedAllTime
            )
        }
        if (recordedSummary.isNotEmpty()) {
            DiagnosticLog.record("Usage", "recorded", recordedSummary)
        }
    }

    suspend fun clear() {
        dataStore.updateData { UsageStore() }
        DiagnosticLog.record("Usage", "cleared")
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_RETENTION_DAYS = 90L
    }
}

data class ModelUsageEntry(
    val modelId: String,
    val stats: ModelUsageStats
)

data class DailyUsageEntry(
    val date: String,
    val models: List<ModelUsageEntry>
)

data class UsageSummary(
    val todayTokens: Long = 0L,
    val todayRequests: Long = 0L,
    val last30DaysTokens: Long = 0L,
    val last30DaysRequests: Long = 0L,
    val allTimeTokens: Long = 0L,
    val allTimeRequests: Long = 0L,
    val dailyEntries: List<DailyUsageEntry> = emptyList(),
    val modelTotals: List<ModelUsageEntry> = emptyList()
)

fun UsageStore.toSummary(today: LocalDate = LocalDate.now()): UsageSummary {
    val todayKey = today.toString()
    val thirtyDaysCutoff = today.minusDays(29)

    val todayModels = daily[todayKey]?.values.orEmpty()
    val todayTokens = todayModels.sumOf { it.totalTokens }
    val todayRequests = todayModels.sumOf { it.requests }

    var last30Tokens = 0L
    var last30Requests = 0L
    for ((dateKey, models) in daily) {
        val parsed = parseLocalDateOrNull(dateKey) ?: continue
        if (!parsed.isBefore(thirtyDaysCutoff) && !parsed.isAfter(today)) {
            for (stats in models.values) {
                last30Tokens += stats.totalTokens
                last30Requests += stats.requests
            }
        }
    }

    val allTimeTokens = allTime.values.sumOf { it.totalTokens }
    val allTimeRequests = allTime.values.sumOf { it.requests }

    val modelComparator = compareByDescending<ModelUsageEntry> { it.stats.totalTokens }
        .thenBy { it.modelId }

    val dailyEntries = daily.entries
        .mapNotNull { (dateKey, models) ->
            val entries = models.entries
                .map { (modelId, stats) -> ModelUsageEntry(modelId, stats) }
                .sortedWith(modelComparator)
            if (entries.isEmpty()) null else DailyUsageEntry(date = dateKey, models = entries)
        }
        .sortedByDescending { it.date }

    val modelTotals = allTime.entries
        .map { (modelId, stats) -> ModelUsageEntry(modelId, stats) }
        .sortedWith(modelComparator)

    return UsageSummary(
        todayTokens = todayTokens,
        todayRequests = todayRequests,
        last30DaysTokens = last30Tokens,
        last30DaysRequests = last30Requests,
        allTimeTokens = allTimeTokens,
        allTimeRequests = allTimeRequests,
        dailyEntries = dailyEntries,
        modelTotals = modelTotals
    )
}

private fun parseLocalDateOrNull(raw: String): LocalDate? = try {
    LocalDate.parse(raw)
} catch (_: DateTimeParseException) {
    null
}
