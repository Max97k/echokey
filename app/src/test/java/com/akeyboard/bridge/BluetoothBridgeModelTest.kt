package com.akeyboard.bridge

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothBridgeModelTest {

    @Test
    fun `text command serializes Traditional Chinese and escapes newlines`() {
        val cmd = BridgeCommand.text("請確認\n\"PR\"")
        val encoded = BluetoothBridgeClient.bridgeJson.encodeToString(cmd)
        assertEquals("""{"type":"text","value":"請確認\n\"PR\""}""", encoded)
    }

    @Test
    fun `key command serializes ENTER and BACKSPACE`() {
        val enter = BluetoothBridgeClient.bridgeJson.encodeToString(BridgeCommand.key(BridgeKey.ENTER))
        val back = BluetoothBridgeClient.bridgeJson.encodeToString(BridgeCommand.key(BridgeKey.BACKSPACE))
        assertEquals("""{"type":"key","key":"ENTER"}""", enter)
        assertEquals("""{"type":"key","key":"BACKSPACE"}""", back)
    }

    @Test
    fun `response decodes ok and uipi error`() {
        val ok = BluetoothBridgeClient.bridgeJson.decodeFromString<BridgeResponse>("""{"ok":true}""")
        assertTrue(ok.ok)

        val uipi = BluetoothBridgeClient.bridgeJson.decodeFromString<BridgeResponse>("""{"ok":false,"err":"uipi"}""")
        assertFalse(uipi.ok)
        assertEquals("uipi", uipi.err)
    }
}
