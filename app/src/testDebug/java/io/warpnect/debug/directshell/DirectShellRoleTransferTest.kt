package io.warpnect.debug.directshell

import io.warpnect.debug.directshell.DirectShellRoleTransfer.Layout
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Owner
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Phase
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Resource
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Resources
import java.io.IOException
import java.nio.channels.Pipe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellRoleTransferTest {
    private val key = ByteArray(32) { (it + 31).toByte() }
    private val generation = 27L

    @Test
    fun onlyRoleCommandsAreAdmitted() {
        val all = 0..255
        assertEquals((67..76).toList(), all.filter { DirectShellRoleCommands.input(it) != null })
        assertEquals(listOf(40, 42, 43, 44, 46), all.filter { DirectShellRoleCommands.pcm(it) != null })
        assertEquals((80..83).toList(), all.filter { DirectShellRoleCommands.video(it) != null })
    }

    @Test
    fun inputEnvelopeRequiresTheOriginalTypedGenerationHeader() {
        val frame = DirectShellInputSessionCodec.generationRequest(generation)
        assertEquals(DirectShellRoleCommands.Input.START, DirectShellRoleCommands.inputEnvelope(69, frame, generation))
        assertNull(DirectShellRoleCommands.inputEnvelope(80, frame, generation))
        assertNull(DirectShellRoleCommands.inputEnvelope(69, frame, generation + 1))
        assertNull(DirectShellRoleCommands.inputEnvelope(69, frame.copyOf(15), generation))
    }

    @Test
    fun roleProofsMatchTheExistingFixedWireCodecs() {
        val pcm = Layout.Pcm(4096, 8, 256)
        val video = Layout.Video(640, 480)
        assertArrayEquals(
            DirectShellPcmRingProbeBinderProtocol.createProof(key, generation, 0x574E5052, 1, 4096, 8, 256),
            DirectShellRoleTransfer.proof(pcm, generation, key),
        )
        assertArrayEquals(
            DirectShellVideoCaptureProbeBinderProtocol.createProof(key, generation, 0x574E5653, 1, 640, 480),
            DirectShellRoleTransfer.proof(video, generation, key),
        )
    }

    @Test
    fun pcmRequiresExactCallerGenerationAndAllThreePhysicalWitnesses() {
        val owner = Owner()
        val layout = Layout.Pcm(4096, 8, 256)
        val backing = Resource()
        val notify = Resource()
        val ack = Resource()
        val pcm = DirectShellRoleTransfer(owner, generation, Resources.Pcm(layout, backing, notify, ack), key)
        val proof = DirectShellRoleTransfer.proof(layout, generation, key)
        assertNull(pcm.reserve(0, owner, generation, layout, proof))
        assertNull(pcm.reserve(2000, Owner(), generation, layout, proof))
        assertNull(pcm.reserve(2000, owner, generation - 1, layout, proof))
        assertNull(pcm.reserve(2000, owner, generation, layout.copy(slots = 7), proof))
        assertNull(pcm.reserve(2000, owner, generation, layout, ByteArray(32)))
        val grant = checkNotNull(pcm.reserve(2000, owner, generation, layout, proof))
        assertNull(pcm.reserve(2000, owner, generation, layout, proof))
        assertTrue(pcm.handoff(grant))
        assertFalse(pcm.handoff(grant))
        assertTrue(pcm.beginRelease(owner))
        assertFalse(pcm.observeRelease(owner, generation, setOf(backing, notify), true))
        assertFalse(pcm.observeRelease(owner, generation, setOf(backing, notify, ack), false))
        assertEquals(Phase.RELEASE_PENDING, pcm.phase())
        assertTrue(pcm.observeRelease(owner, generation, setOf(backing, notify, ack), true))
    }

    @Test
    fun videoCannotRedeemPcmProofAndCancellationCannotRearm() {
        val owner = Owner()
        val layout = Layout.Video(640, 480)
        val surface = Resource()
        val video = DirectShellRoleTransfer(owner, generation, Resources.Video(layout, surface), key)
        val crossRole = DirectShellRoleTransfer.proof(Layout.Pcm(4096, 8, 256), generation, key)
        assertNull(video.reserve(2000, owner, generation, layout, crossRole))
        val grant = checkNotNull(
            video.reserve(2000, owner, generation, layout, DirectShellRoleTransfer.proof(layout, generation, key)),
        )
        assertTrue(video.beginRelease(owner))
        assertFalse(video.handoff(grant))
        assertFalse(video.observeRelease(owner, generation, setOf(Resource()), true))
        assertFalse(video.observeRelease(owner, generation + 1, setOf(surface), true))
        assertTrue(video.observeRelease(owner, generation, setOf(surface), true))
        assertNull(video.reserve(2000, owner, generation, layout, crossRole))
    }

    @Test
    fun pcmDrainClosesLocalPipeEndsButRefusesToPromoteMissingBackingWitness() {
        val owner = Owner()
        val ledger = Resources.Pcm(Layout.Pcm(4096, 8, 256), Resource(), Resource(), Resource())
        val pcm = DirectShellRoleTransfer(owner, generation, ledger, key)
        val notify = Pipe.open()
        val ack = Pipe.open()
        notify.source().use {
            notify.sink().use { write ->
                ack.sink().use {
                    ack.source().use { read ->
                        var backingReleased = false
                        val backing = DirectShellRoleDrain.Handle(
                            ledger.backing,
                            { throw IOException("test_uncertain_mapping_release") },
                            { backingReleased },
                        )
                        val notifier = DirectShellRoleDrain.Handle(ledger.notifyWrite, write::close) { !write.isOpen }
                        val acknowledger = DirectShellRoleDrain.Handle(ledger.ackRead, read::close) { !read.isOpen }
                        assertNull(DirectShellRoleDrain.pcm(pcm, owner, ledger, backing, acknowledger, notifier))
                        val drain = requireNotNull(
                            DirectShellRoleDrain.pcm(pcm, owner, ledger, backing, notifier, acknowledger),
                        )
                        assertFalse(drain.closeOwned())
                        assertFalse(write.isOpen)
                        assertFalse(read.isOpen)
                        assertFalse(drain.observe(generation, workerExited = true))
                        assertEquals(Phase.RELEASE_PENDING, pcm.phase())
                        assertFalse(drain.closeOwned())
                        backingReleased = true
                        assertFalse(drain.observe(generation, workerExited = false))
                        assertTrue(drain.observe(generation, workerExited = true))
                    }
                }
            }
        }
    }

    @Test
    fun videoDrainRequiresExactSurfaceLedgerReleaseAndWorkerEvidence() {
        val owner = Owner()
        val ledger = Resources.Video(Layout.Video(640, 480), Resource())
        val video = DirectShellRoleTransfer(owner, generation, ledger, key)
        var closes = 0
        var queries = 0
        var released = false
        val surface = DirectShellRoleDrain.Handle(ledger.surface, { closes++ }) {
            queries++
            released
        }
        assertNull(DirectShellRoleDrain.video(video, Owner(), ledger, surface))
        assertNull(DirectShellRoleDrain.video(video, owner, Resources.Video(ledger.layout, ledger.surface), surface))
        val drain = requireNotNull(DirectShellRoleDrain.video(video, owner, ledger, surface))
        assertFalse(drain.observe(generation, workerExited = true))
        assertTrue(drain.closeOwned())
        assertFalse(drain.closeOwned())
        assertEquals(1, closes)
        assertFalse(drain.observe(generation - 1, workerExited = true))
        assertEquals(0, queries)
        assertFalse(drain.observe(generation, workerExited = true))
        released = true
        assertFalse(drain.observe(generation, workerExited = false))
        assertTrue(drain.observe(generation, workerExited = true))
        assertEquals(Phase.RELEASED, video.phase())
    }
}
