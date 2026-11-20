package com.akeyboard.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.akeyboard.live.GeminiLiveClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DiagnosticLog {
    const val MAX_ENTRIES = 100
    private const val MAX_DETAIL_LEN = 160
    private const val MAX_EXITS = 5

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    private val API_KEY_REGEX = Regex("""AIza[0-9A-Za-z_-]{10,}""")
    private val BEARER_REGEX = Regex("""(?i)Bearer\s+\S+""")
    private val NEWLINE_REGEX = Regex("""[\r\n]+""")

    fun record(tag: String, event: String, detail: String = "") {
        val timestamp = timeFormatter.format(Instant.now())
        val cleaned = sanitizeDetail(detail)
        val line = if (cleaned.isNotEmpty()) {
            "[$timestamp] $tag/$event: $cleaned"
        } else {
            "[$timestamp] $tag/$event"
        }
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
        }
    }

    fun entries(): List<String> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    fun buildReport(
        context: Context,
        appVersionName: String,
        appVersionCode: Long,
        selectedModelId: String,
        smartModeEnabled: Boolean,
        languageCodes: List<String>
    ): String {
        val exitSummaries = try {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                ?.map { info ->
                    val ts = isoFormatter.format(Instant.ofEpochMilli(info.timestamp))
                    val reasonName = formatExitReasonName(info.reason)
                    val desc = sanitizeDetail(info.description.orEmpty())
                    if (desc.isNotEmpty()) {
                        "$ts $reasonName status=${info.status} $desc"
                    } else {
                        "$ts $reasonName status=${info.status}"
                    }
                }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        return formatReport(
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            selectedModelId = selectedModelId,
            smartModeEnabled = smartModeEnabled,
            languageCodes = languageCodes,
            exitSummaries = exitSummaries,
            logEntries = entries()
        )
    }

    internal fun sanitizeDetail(detail: String): String {
        if (detail.isBlank()) return ""
        var cleaned = GeminiLiveClient.sanitizeKey(detail)
        cleaned = API_KEY_REGEX.replace(cleaned, "REDACTED_KEY")
        cleaned = BEARER_REGEX.replace(cleaned, "Bearer REDACTED")
        cleaned = NEWLINE_REGEX.replace(cleaned, " ").trim()
        return if (cleaned.length > MAX_DETAIL_LEN) {
            cleaned.take(MAX_DETAIL_LEN - 1) + "…"
        } else {
            cleaned
        }
    }

    internal fun formatExitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "REASON_$reason"
    }

    internal fun formatReport(
        appVersionName: String,
        appVersionCode: Long,
        androidRelease: String,
        sdkInt: Int,
        deviceModel: String,
        selectedModelId: String,
        smartModeEnabled: Boolean,
        languageCodes: List<String>,
        exitSummaries: List<String>,
        logEntries: List<String>
    ): String = buildString {
        appendLine("AKeyboard Diagnostics")
        appendLine("=====================")
        appendLine()
        appendLine("Device & Settings:")
        appendLine("- App Version: $appVersionName ($appVersionCode)")
        appendLine("- Android: $androidRelease (SDK $sdkInt)")
        appendLine("- Device: $deviceModel")
        appendLine("- Model: $selectedModelId")
        appendLine("- Smart Mode: $smartModeEnabled")
        val langs = if (languageCodes.isEmpty()) "auto" else languageCodes.joinToString(",")
        appendLine("- Languages: $langs")
        appendLine()
        appendLine("Recent Process Exits:")
        if (exitSummaries.isEmpty()) {
            appendLine("- (none)")
        } else {
            exitSummaries.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Recent Events:")
        if (logEntries.isEmpty()) {
            appendLine("- (none)")
        } else {
            logEntries.forEach { appendLine("- $it") }
        }
    }.trimEnd()
}
