package io.warpnect.debug.directshell

import java.io.IOException

/** Authenticated normal-app control peer for the DEBUG-only DirectShell Input probe. */
internal class DirectShellInputProbeClient private constructor(
    private val socket: DirectShellProbeIpv4Socket,
    private val secret: ByteArray,
) : AutoCloseable {
    private val input = socket.input()
    private val output = socket.output()
    private var nextRequestId = 1L

    fun call(command: DirectShellProbeProtocol.Command, payload: String = ""): Response {
        val requestId = nextRequestId++
        DirectShellProbeProtocol.writeRequest(output, requestId, command, payload.encodeToByteArray(), secret)
        val response = DirectShellProbeProtocol.readResponse(input, secret)
        if (response.requestId != requestId) {
            throw IOException("DirectShell Input probe response request ID mismatch")
        }
        return Response(response.status, response.payload.toString(Charsets.UTF_8))
    }

    override fun close() {
        socket.close()
    }

    data class Response(
        val status: DirectShellProbeProtocol.Status,
        val payload: String,
    )

    companion object {
        fun connectLoopback(port: Int, secret: ByteArray): DirectShellInputProbeClient = DirectShellInputProbeClient(
            DirectShellProbeIpv4Socket.connect(port),
            DirectShellProbeProtocol.requireSecret(secret),
        )
    }
}
