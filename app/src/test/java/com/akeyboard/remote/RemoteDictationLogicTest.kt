package com.akeyboard.remote

import com.akeyboard.R
import com.akeyboard.bridge.BluetoothReadiness
import com.akeyboard.bridge.BridgeDeliveryError
import com.akeyboard.bridge.BridgeDeliveryException
import com.akeyboard.live.VoiceSessionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemoteDictationLogicTest {

    @Test
    fun `deriveRemoteBlocker follows strict priority order`() {
        // 1. NoHardware beats everything
        assertEquals(
            RemoteBlocker.NoHardware,
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.NoHardware,
                hasMicPermission = false,
                hasApiKey = false
            )
        )

        // 2. PermissionsMissing beats BluetoothDisabled / NoPairedDevices / ApiKeyMissing
        assertEquals(
            RemoteBlocker.PermissionsMissing(missingBt = true, missingMic = true),
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.PermissionMissing,
                hasMicPermission = false,
                hasApiKey = false
            )
        )
        assertEquals(
            RemoteBlocker.PermissionsMissing(missingBt = false, missingMic = true),
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.BluetoothDisabled(turningOn = false),
                hasMicPermission = false,
                hasApiKey = false
            )
        )

        // 3. BluetoothDisabled beats NoPairedDevices and ApiKeyMissing
        assertEquals(
            RemoteBlocker.BluetoothDisabled(turningOn = true),
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.BluetoothDisabled(turningOn = true),
                hasMicPermission = true,
                hasApiKey = false
            )
        )

        // 4. NoPairedDevices beats ApiKeyMissing
        assertEquals(
            RemoteBlocker.NoPairedDevices,
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.NoPairedDevices,
                hasMicPermission = true,
                hasApiKey = false
            )
        )

        // 5. ApiKeyMissing only when hasApiKey is explicitly false (null = still loading)
        assertNull(
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.Ready(emptyList()),
                hasMicPermission = true,
                hasApiKey = null
            )
        )
        assertEquals(
            RemoteBlocker.ApiKeyMissing,
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.Ready(emptyList()),
                hasMicPermission = true,
                hasApiKey = false
            )
        )
        assertNull(
            RemoteDictationViewModel.deriveRemoteBlocker(
                readiness = BluetoothReadiness.Ready(emptyList()),
                hasMicPermission = true,
                hasApiKey = true
            )
        )
    }

    @Test
    fun `PendingConnect TTL expiry and reconciliation decisions`() {
        val pending = PendingConnect(address = "AA:BB:CC:DD:EE:FF", expiresAtElapsedMs = 20_000L)
        assertFalse(pending.isExpired(19_999L))
        assertTrue(pending.isExpired(20_000L))
        assertTrue(pending.isExpired(25_000L))

        assertEquals(
            PendingConnectDecision.None,
            RemoteDictationViewModel.evaluatePendingConnect(
                pending = null,
                nowElapsedMs = 10_000L,
                readiness = BluetoothReadiness.Ready(emptyList()),
                hasMatchingDevice = true
            )
        )
        assertEquals(
            PendingConnectDecision.Expired,
            RemoteDictationViewModel.evaluatePendingConnect(
                pending = pending,
                nowElapsedMs = 20_000L,
                readiness = BluetoothReadiness.Ready(emptyList()),
                hasMatchingDevice = true
            )
        )
        assertEquals(
            PendingConnectDecision.ConnectAddress("AA:BB:CC:DD:EE:FF"),
            RemoteDictationViewModel.evaluatePendingConnect(
                pending = pending,
                nowElapsedMs = 15_000L,
                readiness = BluetoothReadiness.Ready(emptyList()),
                hasMatchingDevice = true
            )
        )
        assertEquals(
            PendingConnectDecision.DeviceMissing,
            RemoteDictationViewModel.evaluatePendingConnect(
                pending = pending,
                nowElapsedMs = 15_000L,
                readiness = BluetoothReadiness.Ready(emptyList()),
                hasMatchingDevice = false
            )
        )
        assertEquals(
            PendingConnectDecision.DeviceMissing,
            RemoteDictationViewModel.evaluatePendingConnect(
                pending = pending,
                nowElapsedMs = 15_000L,
                readiness = BluetoothReadiness.NoPairedDevices,
                hasMatchingDevice = false
            )
        )
        assertEquals(
            PendingConnectDecision.KeepWaiting,
            RemoteDictationViewModel.evaluatePendingConnect(
                pending = pending,
                nowElapsedMs = 15_000L,
                readiness = BluetoothReadiness.BluetoothDisabled(turningOn = true),
                hasMatchingDevice = false
            )
        )
        assertEquals(
            PendingConnectDecision.Clear,
            RemoteDictationViewModel.evaluatePendingConnect(
                pending = pending,
                nowElapsedMs = 15_000L,
                readiness = BluetoothReadiness.PermissionMissing,
                hasMatchingDevice = false
            )
        )
    }

    @Test
    fun `appendPendingUtterance joins with space and strips newlines across chunks`() {
        var queued = ""
        queued = RemoteDictationViewModel.appendPendingUtterance(queued, "第一句\n")
        queued = RemoteDictationViewModel.appendPendingUtterance(queued, "第二句\r\n第三句")
        queued = RemoteDictationViewModel.appendPendingUtterance(queued, "\n\r\n")
        assertEquals("第一句 第二句 第三句", queued)
        assertFalse(queued.contains("\n"))
        assertFalse(queued.contains("\r"))

        val chunks = RemoteDictationViewModel.splitPendingForResend(queued)
        assertEquals(listOf("第一句 第二句 第三句"), chunks)
        val trimmed = RemoteDictationViewModel.trimAcknowledgedPrefix(queued, chunks[0])
        assertEquals("", trimmed)

        // Newly appended utterance stays intact after trimming acknowledged prefix
        var pending = "第一句 第二句"
        val firstChunk = RemoteDictationViewModel.nextPendingChunk(pending, maxBytes = 9)
        pending = RemoteDictationViewModel.appendPendingUtterance(pending, "第三句")
        pending = RemoteDictationViewModel.trimAcknowledgedPrefix(pending, firstChunk)
        assertEquals(" 第二句 第三句", pending)
    }

    @Test
    fun `resendPending chunks oversized text under 16 KB without splitting surrogate pairs`() {
        val hugeAsciiText = "a".repeat(20_000) + " next"
        val asciiChunks = RemoteDictationViewModel.splitPendingForResend(hugeAsciiText)
        assertEquals(2, asciiChunks.size)
        assertEquals(16_384, asciiChunks[0].toByteArray(Charsets.UTF_8).size)
        assertEquals("a".repeat(3_616) + " next", asciiChunks[1])
        assertEquals(hugeAsciiText, asciiChunks.joinToString(""))

        // Surrogate pair boundary check: emoji is 4 UTF-8 bytes (2 UTF-16 chars)
        val emoji = "\uD83D\uDE00"
        val textWithEmoji = "ab${emoji}c"
        val smallChunks = RemoteDictationViewModel.splitPendingForResend(textWithEmoji, maxBytes = 5)
        assertEquals(listOf("ab", "${emoji}c"), smallChunks)
        assertEquals(textWithEmoji, smallChunks.joinToString(""))
        smallChunks.forEach { chunk ->
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= 5)
        }
    }

    @Test
    fun `error mapping handles all BridgeDeliveryError, RemoteConnectNotice, and VoiceSessionError values`() {
        assertEquals(
            BridgeDeliveryError.UIPI,
            RemoteDictationViewModel.mapDeliveryError(BridgeDeliveryException(BridgeDeliveryError.UIPI, "uipi"))
        )
        assertEquals(
            BridgeDeliveryError.NO_FOCUS,
            RemoteDictationViewModel.mapDeliveryError(BridgeDeliveryException(BridgeDeliveryError.NO_FOCUS, "no_focus"))
        )
        assertEquals(
            BridgeDeliveryError.PC_DISCONNECTED,
            RemoteDictationViewModel.mapDeliveryError(BridgeDeliveryException(BridgeDeliveryError.PC_DISCONNECTED, "eof"))
        )
        assertEquals(
            BridgeDeliveryError.SEND_FAILED,
            RemoteDictationViewModel.mapDeliveryError(IOException("Ack timeout"))
        )

        assertEquals(R.string.remote_error_uipi, mapDeliveryErrorRes(BridgeDeliveryError.UIPI))
        assertEquals(R.string.remote_error_no_focus, mapDeliveryErrorRes(BridgeDeliveryError.NO_FOCUS))
        assertEquals(R.string.remote_error_send_failed, mapDeliveryErrorRes(BridgeDeliveryError.SEND_FAILED))
        assertEquals(R.string.remote_error_pc_disconnected, mapDeliveryErrorRes(BridgeDeliveryError.PC_DISCONNECTED))

        assertEquals(R.string.remote_error_bt_enable_denied, mapConnectNoticeRes(RemoteConnectNotice.BT_ENABLE_DENIED))
        assertEquals(R.string.remote_error_bt_enable_timeout, mapConnectNoticeRes(RemoteConnectNotice.BT_ENABLE_TIMEOUT))
        assertEquals(R.string.remote_no_paired_devices, mapConnectNoticeRes(RemoteConnectNotice.DEVICE_UNAVAILABLE))

        assertEquals(R.string.err_no_permission, mapVoiceErrorRes(VoiceSessionError.NO_PERMISSION))
        assertEquals(R.string.remote_error_no_key, mapVoiceErrorRes(VoiceSessionError.NO_KEY))
        assertEquals(R.string.remote_error_no_network, mapVoiceErrorRes(VoiceSessionError.NO_NETWORK))
        assertEquals(R.string.err_mic_busy, mapVoiceErrorRes(VoiceSessionError.MIC_BUSY))
        assertEquals(R.string.remote_error_invalid_key, mapVoiceErrorRes(VoiceSessionError.INVALID_KEY))
        assertEquals(R.string.remote_error_quota, mapVoiceErrorRes(VoiceSessionError.QUOTA_EXCEEDED))
        assertEquals(R.string.remote_error_max_duration, mapVoiceErrorRes(VoiceSessionError.MAX_DURATION))
        assertEquals(R.string.remote_error_generic, mapVoiceErrorRes(VoiceSessionError.GENERIC))

        // Priority: deliveryError > connectNotice > dictationError
        assertEquals(
            R.string.remote_error_no_focus,
            resolveRemoteErrorBannerRes(
                deliveryError = BridgeDeliveryError.NO_FOCUS,
                connectNotice = RemoteConnectNotice.BT_ENABLE_TIMEOUT,
                dictationError = VoiceSessionError.MIC_BUSY
            )
        )
        assertNull(resolveRemoteErrorBannerRes(null, null, null))
    }
}
