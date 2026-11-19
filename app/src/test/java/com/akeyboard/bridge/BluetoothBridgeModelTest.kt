package com.akeyboard.bridge

import android.bluetooth.BluetoothAdapter
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `response decodes ok and error codes`() {
        val ok = BluetoothBridgeClient.bridgeJson.decodeFromString<BridgeResponse>("""{"ok":true}""")
        assertTrue(ok.ok)

        val uipi = BluetoothBridgeClient.bridgeJson.decodeFromString<BridgeResponse>("""{"ok":false,"err":"uipi"}""")
        assertFalse(uipi.ok)
        assertEquals("uipi", uipi.err)

        val noFocus = BluetoothBridgeClient.bridgeJson.decodeFromString<BridgeResponse>("""{"ok":false,"err":"no_focus"}""")
        assertEquals("no_focus", noFocus.err)

        val lineTooLong = BluetoothBridgeClient.bridgeJson.decodeFromString<BridgeResponse>("""{"ok":false,"err":"line_too_long"}""")
        assertEquals("line_too_long", lineTooLong.err)
    }

    @Test
    fun `mapErrorCode maps all protocol errors to BridgeDeliveryError`() {
        assertEquals(BridgeDeliveryError.UIPI, BluetoothBridgeClient.mapErrorCode("uipi"))
        assertEquals(BridgeDeliveryError.NO_FOCUS, BluetoothBridgeClient.mapErrorCode("no_focus"))
        assertEquals(BridgeDeliveryError.SEND_FAILED, BluetoothBridgeClient.mapErrorCode("send_failed"))
        assertEquals(BridgeDeliveryError.SEND_FAILED, BluetoothBridgeClient.mapErrorCode("line_too_long"))
        assertEquals(BridgeDeliveryError.SEND_FAILED, BluetoothBridgeClient.mapErrorCode("unknown"))
        assertEquals(BridgeDeliveryError.SEND_FAILED, BluetoothBridgeClient.mapErrorCode(null))
    }

    @Test
    fun `evaluateReadiness and evaluateReadinessStatus enforce priority order`() {
        assertEquals(
            BluetoothReadiness.NoHardware,
            BluetoothBridgeClient.evaluateReadiness(
                hasHardware = false,
                hasPermission = false,
                adapterState = BluetoothAdapter.STATE_OFF,
                bondedDevices = emptyList()
            )
        )
        assertEquals(
            BluetoothReadiness.PermissionMissing,
            BluetoothBridgeClient.evaluateReadiness(
                hasHardware = true,
                hasPermission = false,
                adapterState = BluetoothAdapter.STATE_OFF,
                bondedDevices = emptyList()
            )
        )
        assertEquals(
            BluetoothReadiness.BluetoothDisabled(turningOn = false),
            BluetoothBridgeClient.evaluateReadiness(
                hasHardware = true,
                hasPermission = true,
                adapterState = BluetoothAdapter.STATE_OFF,
                bondedDevices = emptyList()
            )
        )
        assertEquals(
            BluetoothReadiness.BluetoothDisabled(turningOn = true),
            BluetoothBridgeClient.evaluateReadiness(
                hasHardware = true,
                hasPermission = true,
                adapterState = BluetoothAdapter.STATE_TURNING_ON,
                bondedDevices = emptyList()
            )
        )
        assertEquals(
            BluetoothReadiness.NoPairedDevices,
            BluetoothBridgeClient.evaluateReadiness(
                hasHardware = true,
                hasPermission = true,
                adapterState = BluetoothAdapter.STATE_ON,
                bondedDevices = emptyList()
            )
        )
        assertEquals(
            ReadinessStatus.READY,
            BluetoothBridgeClient.evaluateReadinessStatus(
                hasHardware = true,
                hasPermission = true,
                adapterState = BluetoothAdapter.STATE_ON,
                hasPairedDevices = true
            )
        )
    }

    @Test
    fun `cancelConnect transitions blocking connect to Disconnected instead of Error`() = runBlocking(Dispatchers.IO) {
        val client = BluetoothBridgeClient(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.IO,
            ackTimeoutMs = 200L
        )
        val connectEntered = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)

        val blockingTransport = object : BridgeTransport {
            override val inputStream: InputStream = PipedInputStream()
            override val outputStream: OutputStream = PipedOutputStream()
            override val isConnected: Boolean = false

            override fun connect() {
                connectEntered.countDown()
                if (!closedLatch.await(3, TimeUnit.SECONDS)) {
                    throw IOException("Test timeout")
                }
                throw IOException("Socket closed")
            }

            override fun close() {
                closedLatch.countDown()
            }
        }

        val connectDeferred = async(Dispatchers.IO) {
            client.connectTransport("Office-PC") { blockingTransport }
        }
        assertTrue(connectEntered.await(2, TimeUnit.SECONDS))
        client.cancelConnect()

        val result = withTimeout(2_000) { connectDeferred.await() }
        assertTrue(result.isFailure)
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        client.disconnect()
    }

    @Test
    fun `idle EOF disconnects immediately without sendCommand`() = runBlocking(Dispatchers.IO) {
        val client = BluetoothBridgeClient(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.IO,
            ackTimeoutMs = 200L
        )
        val transport = PipeBridgeTransport()
        val connectRes = client.connectTransport("Office-PC") { transport }
        assertTrue(connectRes.isSuccess)
        assertEquals(ConnectionState.Connected("Office-PC"), client.connectionState.value)

        // Simulate PC bridge closing socket while idle (EOF -1)
        transport.serverOut.close()

        withTimeout(2_000) {
            while (client.connectionState.value != ConnectionState.Disconnected) {
                delay(15)
            }
        }
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        client.disconnect()
    }

    @Test
    fun `EOF during sendCommand returns PC_DISCONNECTED`() = runBlocking(Dispatchers.IO) {
        val client = BluetoothBridgeClient(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.IO,
            ackTimeoutMs = 1_500L
        )
        val transport = PipeBridgeTransport()
        assertTrue(client.connectTransport("Office-PC") { transport }.isSuccess)

        val sendDeferred = async(Dispatchers.IO) {
            client.sendCommand(BridgeCommand.text("hello"))
        }
        val outbound = withTimeout(2_000) {
            withContext(Dispatchers.IO) { transport.serverReader.readLine() }
        }
        assertEquals("""{"type":"text","value":"hello"}""", outbound)
        transport.serverOut.close()

        val result = withTimeout(2_000) { sendDeferred.await() }
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as BridgeDeliveryException
        assertEquals(BridgeDeliveryError.PC_DISCONNECTED, ex.error)
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        client.disconnect()
    }

    @Test
    fun `sendCommand timeout closes socket and allows subsequent connection without deadlock`() = runBlocking(Dispatchers.IO) {
        val client = BluetoothBridgeClient(
            context = ContextWrapper(null),
            ioDispatcher = Dispatchers.IO,
            ackTimeoutMs = 120L
        )
        val firstTransport = PipeBridgeTransport()
        assertTrue(client.connectTransport("PC-1") { firstTransport }.isSuccess)

        val timeoutResult = client.sendCommand(BridgeCommand.key(BridgeKey.ENTER))
        assertTrue(timeoutResult.isFailure)
        assertTrue(timeoutResult.exceptionOrNull() is IOException)
        assertFalse(timeoutResult.exceptionOrNull() is BridgeDeliveryException)
        assertTrue(firstTransport.closed.get())
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)

        // Reconnect and verify client is not deadlocked
        val secondTransport = PipeBridgeTransport()
        assertTrue(client.connectTransport("PC-2") { secondTransport }.isSuccess)
        val sendDeferred = async(Dispatchers.IO) { client.sendCommand(BridgeCommand.text("ok")) }
        val outbound = withTimeout(2_000) {
            withContext(Dispatchers.IO) { secondTransport.serverReader.readLine() }
        }
        assertEquals("""{"type":"text","value":"ok"}""", outbound)
        secondTransport.sendServerLine("""{"ok":false,"err":"no_focus"}""")

        val secondResult = withTimeout(2_000) { sendDeferred.await() }
        assertTrue(secondResult.isFailure)
        val err = secondResult.exceptionOrNull() as BridgeDeliveryException
        assertEquals(BridgeDeliveryError.NO_FOCUS, err.error)
        client.disconnect()
    }

    private class PipeBridgeTransport : BridgeTransport {
        val closed = AtomicBoolean(false)

        private val clientIn = PipedInputStream(4096)
        val serverOut = PipedOutputStream(clientIn)

        private val serverIn = PipedInputStream(4096)
        private val clientOut = PipedOutputStream(serverIn)
        val serverReader = BufferedReader(InputStreamReader(serverIn, Charsets.UTF_8))

        override val inputStream: InputStream get() = clientIn
        override val outputStream: OutputStream get() = clientOut
        override val isConnected: Boolean get() = !closed.get()

        override fun connect() = Unit

        fun sendServerLine(line: String) {
            serverOut.write((line + "\n").toByteArray(Charsets.UTF_8))
            serverOut.flush()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { serverOut.close() }
                runCatching { clientOut.close() }
                runCatching { clientIn.close() }
                runCatching { serverIn.close() }
            }
        }
    }
}
