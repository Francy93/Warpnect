package io.warpnect.platform.session.bootstrap

import io.warpnect.session.handshake.HandshakeTransportEndpoint
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBootstrapDatagramRouterTest {
    @Test
    fun rawWncpAndWnsnNeverReachSecureControlButWnsdRoutesByContext() {
        val receiveSocket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val sendSocket = DatagramSocket()
        val router = AndroidBootstrapDatagramRouter(receiveSocket)
        val received = CountDownLatch(1)
        val contextId = 0x0102030405060708L
        router.setSecureControlListener(contextId) { _: HandshakeTransportEndpoint, _: ByteArray ->
            received.countDown()
        }

        try {
            send(sendSocket, receiveSocket.localPort, "WNCP".encodeToByteArray() + ByteArray(16))
            assertFalse(received.await(100, TimeUnit.MILLISECONDS))
            send(sendSocket, receiveSocket.localPort, "WNSN".encodeToByteArray() + ByteArray(16))
            assertFalse(received.await(100, TimeUnit.MILLISECONDS))

            val wnsd = ByteArray(28)
            "WNSD".encodeToByteArray().copyInto(wnsd)
            for (index in 0 until 8) {
                wnsd[8 + index] = (contextId ushr (56 - index * 8)).toByte()
            }
            send(sendSocket, receiveSocket.localPort, wnsd)
            assertTrue(received.await(2, TimeUnit.SECONDS))
        } finally {
            router.close()
            sendSocket.close()
        }
    }

    private fun send(socket: DatagramSocket, port: Int, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
    }
}
