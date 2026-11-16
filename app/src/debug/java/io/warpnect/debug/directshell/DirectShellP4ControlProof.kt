package io.warpnect.debug.directshell

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Offline witness checks only. Does not launch, query or signal a process. */
internal class DirectShellP4ControlProof(
    private val process: ProcessOwner,
    private val generation: Long,
    private val artifactSha256: String,
    secret: ByteArray,
    private val identityRequest: Long,
    private val shutdownRequest: Long,
) : AutoCloseable {
    class ProcessOwner

    /** The future monitor must tie these observations to one exact process creation identity. */
    class MeasuredProcess(
        val owner: ProcessOwner,
        val generation: Long,
        val uid: Int,
        val artifactSha256: String,
    )

    enum class Phase { WAITING, AUTHENTICATED, STOP_ACKNOWLEDGED, RELEASED, UNRESOLVED }

    init {
        require(generation > 0 && identityRequest > 0 && shutdownRequest > identityRequest)
        require(artifactSha256.matches(SHA256))
    }

    private val key = DirectShellProbeProtocol.requireSecret(secret)

    @Volatile
    var phase: Phase = Phase.WAITING
        private set

    @Synchronized
    fun authenticate(measured: MeasuredProcess, reply: ByteArray): Boolean {
        if (phase != Phase.WAITING || !matches(measured)) return false
        val payload = response(reply, identityRequest) ?: return false
        val uidLine = payload.lineSequence().filter { it.startsWith("uid=") }.singleOrNull()
        if (uidLine != "uid=2000") return false
        phase = Phase.AUTHENTICATED
        return true
    }

    @Synchronized
    fun acknowledgeStop(measured: MeasuredProcess, reply: ByteArray): Boolean {
        if (phase != Phase.AUTHENTICATED || !matches(measured)) return false
        if (response(reply, shutdownRequest) != "SHUTDOWN_ACCEPTED") return false
        phase = Phase.STOP_ACKNOWLEDGED
        return true
    }

    @Synchronized
    fun finish(owner: ProcessOwner, terminated: Boolean, channelClosed: Boolean, secretsRemoved: Boolean): Boolean {
        if (phase != Phase.STOP_ACKNOWLEDGED || owner !== process || !terminated || !channelClosed || !secretsRemoved) {
            return false
        }
        phase = Phase.RELEASED
        key.fill(0)
        return true
    }

    @Synchronized
    override fun close() {
        if (phase != Phase.RELEASED) phase = Phase.UNRESOLVED
        key.fill(0)
    }

    private fun matches(measured: MeasuredProcess): Boolean = measured.owner === process &&
        measured.generation == generation && measured.uid == 2000 && measured.artifactSha256 == artifactSha256

    private fun response(frame: ByteArray, request: Long): String? {
        if (frame.size !in 1..(Int.SIZE_BYTES + DirectShellProbeProtocol.MAX_FRAME_BYTES)) return null
        return try {
            DataInputStream(ByteArrayInputStream(frame)).use { input ->
                val response = DirectShellProbeProtocol.readResponse(input, key)
                if (input.available() != 0 || response.requestId != request ||
                    response.status != DirectShellProbeProtocol.Status.OK
                ) {
                    null
                } else {
                    response.payload.toString(Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
