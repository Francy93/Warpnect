package io.warpnect.debug.directshell

import java.io.IOException

/** One ordered authenticated control connection for a single DirectShell Input Session generation. */
internal class DirectShellInputSessionClient private constructor(
    private val socket: DirectShellProbeIpv4Socket,
    private val secret: ByteArray,
) : AutoCloseable {
    private val input = socket.input()
    private val output = socket.output()
    private var nextRequestId = 1L

    fun call(command: DirectShellProbeProtocol.Command, payload: ByteArray = ByteArray(0)): Response {
        val requestId = nextRequestId++
        DirectShellProbeProtocol.writeRequest(output, requestId, command, payload, secret)
        val response = DirectShellProbeProtocol.readResponse(input, secret)
        if (response.requestId != requestId) {
            throw IOException("DirectShell Input Session response request ID mismatch")
        }
        return Response(response.status, response.payload)
    }

    override fun close() {
        socket.close()
    }

    data class Response(
        val status: DirectShellProbeProtocol.Status,
        val payload: ByteArray,
    )

    companion object {
        fun connectLoopback(port: Int, secret: ByteArray): DirectShellInputSessionClient =
            DirectShellInputSessionClient(
                DirectShellProbeIpv4Socket.connect(port),
                DirectShellProbeProtocol.requireSecret(secret),
            )
    }
}
