package com.akeyboard.util

import android.app.ApplicationExitInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticLogTest {

    @Before
    fun setUp() {
        DiagnosticLog.clear()
    }

    @After
    fun tearDown() {
        DiagnosticLog.clear()
    }

    @Test
    fun `ring buffer drops oldest entries when exceeding MAX_ENTRIES`() {
        repeat(DiagnosticLog.MAX_ENTRIES + 15) { idx ->
            DiagnosticLog.record("Live", "event", "seq=$idx")
        }
        val entries = DiagnosticLog.entries()
        assertEquals(DiagnosticLog.MAX_ENTRIES, entries.size)
        assertFalse(entries.first().contains("seq=0"))
        assertTrue(entries.first().contains("seq=15"))
        assertTrue(entries.last().contains("seq=${DiagnosticLog.MAX_ENTRIES + 14}"))
    }

    @Test
    fun `sanitizeDetail redacts keys, bearer tokens, flattens newlines, and truncates`() {
        val raw = "Failed wss://host/ws?key=AIzaSySecretKey123&mode=1\n" +
            "Bearer super-secret-token and bare AIzaSyAnotherKey999"
        val cleaned = DiagnosticLog.sanitizeDetail(raw)
        assertFalse(cleaned.contains("AIzaSySecretKey123"))
        assertFalse(cleaned.contains("AIzaSyAnotherKey999"))
        assertFalse(cleaned.contains("super-secret-token"))
        assertFalse(cleaned.contains("\n"))
        assertTrue(cleaned.contains("?key=REDACTED"))
        assertTrue(cleaned.contains("Bearer REDACTED"))
        assertTrue(cleaned.contains("REDACTED_KEY"))

        val longDetail = "x".repeat(300)
        val truncated = DiagnosticLog.sanitizeDetail(longDetail)
        assertEquals(160, truncated.length)
        assertTrue(truncated.endsWith("…"))
    }

    @Test
    fun `formatExitReasonName maps ApplicationExitInfo constants`() {
        assertEquals("CRASH", DiagnosticLog.formatExitReasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("CRASH_NATIVE", DiagnosticLog.formatExitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", DiagnosticLog.formatExitReasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("LOW_MEMORY", DiagnosticLog.formatExitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("FREEZER", DiagnosticLog.formatExitReasonName(ApplicationExitInfo.REASON_FREEZER))
        assertEquals("REASON_999", DiagnosticLog.formatExitReasonName(999))
    }

    @Test
    fun `formatReport formats device, settings, exits, and events cleanly`() {
        val emptyReport = DiagnosticLog.formatReport(
            appVersionName = "0.3.0",
            appVersionCode = 2L,
            androidRelease = "16",
            sdkInt = 36,
            deviceModel = "Google Pixel 8 Pro",
            selectedModelId = "gemini-3.5-transcribe-live",
            smartModeEnabled = true,
            languageCodes = emptyList(),
            exitSummaries = emptyList(),
            logEntries = emptyList()
        )
        assertTrue(emptyReport.contains("App Version: 0.3.0 (2)"))
        assertTrue(emptyReport.contains("Android: 16 (SDK 36)"))
        assertTrue(emptyReport.contains("Device: Google Pixel 8 Pro"))
        assertTrue(emptyReport.contains("Languages: auto"))
        assertTrue(emptyReport.contains("Recent Process Exits:\n- (none)"))
        assertTrue(emptyReport.contains("Recent Events:\n- (none)"))

        val populated = DiagnosticLog.formatReport(
            appVersionName = "0.3.0",
            appVersionCode = 2L,
            androidRelease = "16",
            sdkInt = 36,
            deviceModel = "Google Pixel 8 Pro",
            selectedModelId = "gemini-3.5-transcribe-live",
            smartModeEnabled = false,
            languageCodes = listOf("zh-TW", "en-US"),
            exitSummaries = listOf("2026-11-20T06:00:00Z CRASH status=1"),
            logEntries = listOf("[06:01:00.000] Live/ws_failure: http=503")
        )
        assertTrue(populated.contains("Languages: zh-TW,en-US"))
        assertTrue(populated.contains("2026-11-20T06:00:00Z CRASH status=1"))
        assertTrue(populated.contains("[06:01:00.000] Live/ws_failure: http=503"))
    }
}
