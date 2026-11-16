package io.warpnect.debug.directshell

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellP4ControlProofTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val artifact = "ab".repeat(32)
    private val owner = DirectShellP4ControlProof.ProcessOwner()

    private fun measured(
        process: DirectShellP4ControlProof.ProcessOwner = owner,
        generation: Long = 7,
        uid: Int = 2000,
        digest: String = artifact,
    ) = DirectShellP4ControlProof.MeasuredProcess(process, generation, uid, digest)

    @Test fun authenticateAndAcknowledgeBeforeIndependentResourceProof() {
        DirectShellP4ControlProof(owner, 7, artifact, secret, 61, 62).use { proof ->
            assertTrue(proof.authenticate(measured(), frame(61, "uid=2000\n")))
            assertFalse(proof.authenticate(measured(), frame(61, "uid=2000\n")))
            assertFalse(proof.finish(owner, terminated = true, channelClosed = true, secretsRemoved = true))
            assertFalse(proof.acknowledgeStop(measured(), frame(61, "SHUTDOWN_ACCEPTED")))
            assertTrue(proof.acknowledgeStop(measured(), frame(62, "SHUTDOWN_ACCEPTED")))
            assertEquals(DirectShellP4ControlProof.Phase.STOP_ACKNOWLEDGED, proof.phase)
            assertFalse(proof.finish(owner, terminated = false, channelClosed = true, secretsRemoved = true))
            assertFalse(proof.finish(owner, terminated = true, channelClosed = false, secretsRemoved = true))
            assertFalse(proof.finish(owner, terminated = true, channelClosed = true, secretsRemoved = false))
            assertFalse(proof.finish(DirectShellP4ControlProof.ProcessOwner(), true, true, true))
            assertTrue(proof.finish(owner, terminated = true, channelClosed = true, secretsRemoved = true))
            assertEquals(DirectShellP4ControlProof.Phase.RELEASED, proof.phase)
        }
    }

    @Test fun rejectsForeignOwnerUnexpectedUidGenerationArtifactAndUnauthenticatedFrame() {
        DirectShellP4ControlProof(owner, 7, artifact, secret, 61, 62).use { proof ->
            val signed = frame(61, "uid=2000")
            assertFalse(proof.authenticate(measured(process = DirectShellP4ControlProof.ProcessOwner()), signed))
            assertFalse(proof.authenticate(measured(uid = 0), signed))
            assertFalse(proof.authenticate(measured(generation = 8), signed))
            assertFalse(proof.authenticate(measured(digest = "0".repeat(64)), signed))
            assertFalse(proof.authenticate(measured(), frame(61, "uid=0\nuid=2000")))
            assertFalse(proof.authenticate(measured(), frame(61, "uid=2000", ByteArray(32))))
            assertFalse(proof.authenticate(measured(), signed + 0.toByte()))
            assertEquals(DirectShellP4ControlProof.Phase.WAITING, proof.phase)
            proof.close()
            assertEquals(DirectShellP4ControlProof.Phase.UNRESOLVED, proof.phase)
            assertFalse(proof.authenticate(measured(), signed))
        }
    }

    private fun frame(request: Long, payload: String, key: ByteArray = secret): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            DirectShellProbeProtocol.writeResponse(
                it,
                request,
                DirectShellProbeProtocol.Status.OK,
                payload.toByteArray(Charsets.UTF_8),
                key,
            )
        }
        return bytes.toByteArray()
    }
}
