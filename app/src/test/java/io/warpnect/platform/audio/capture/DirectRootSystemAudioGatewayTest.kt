package io.warpnect.platform.audio.capture

import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.DirectRootState
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import io.warpnect.platform.privileged.runtime.RootControlSession
import io.warpnect.platform.privileged.runtime.RootHelperIdentity
import io.warpnect.platform.privileged.runtime.RootRuntimeBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRootSystemAudioGatewayTest {
    @Test fun `ready runtime delegates finite ordered audio operations`() {
        val (runtime, peer) = ready()
        val gateway = DirectRootSystemAudioGateway(runtime)
        gateway.prepare(request)
        gateway.start(request)
        gateway.status(request)
        gateway.stop(request)
        gateway.close()
        gateway.close()
        assertEquals(listOf("prepare", "start", "status", "stop", "close"), peer.calls)
        assertTrue(runtime.snapshot.ready)
        runtime.close()
    }

    @Test fun `unacquired runtime rejects without sending audio`() {
        val peer = Peer()
        val runtime = runtime(peer)
        assertTrue(runCatching { DirectRootSystemAudioGateway(runtime).prepare(request) }.isFailure)
        assertTrue(peer.calls.isEmpty())
        runtime.close()
    }

    @Test fun `wrong generation never reaches peer`() {
        val (runtime, peer) = ready()
        assertTrue(
            runCatching { DirectRootSystemAudioGateway(runtime).prepare(request.copy(generation = 11)) }.isFailure,
        )
        assertTrue(peer.calls.isEmpty())
        assertTrue(runtime.snapshot.ready)
        runtime.close()
    }

    @Test fun `stale released gateway cannot reach next runtime`() {
        val (old, _) = ready()
        val gateway = DirectRootSystemAudioGateway(old)
        old.close()
        val (next, peer) = ready(11)
        assertTrue(runCatching { gateway.start(request) }.isFailure)
        assertTrue(peer.calls.isEmpty())
        next.close()
    }

    @Test fun `closed gateway rejects and never closes runtime`() {
        val (runtime, peer) = ready()
        val gateway = DirectRootSystemAudioGateway(runtime)
        gateway.close()
        assertTrue(runCatching { gateway.start(request) }.isFailure)
        assertTrue(peer.calls.isEmpty())
        assertTrue(runtime.snapshot.ready)
        runtime.close()
    }

    @Test fun `transport failure marks root runtime lost and stops further operations`() {
        val (runtime, peer) = ready()
        val gateway = DirectRootSystemAudioGateway(runtime)
        gateway.prepare(request)
        peer.lost = true
        assertTrue(runCatching { gateway.start(request) }.isFailure)
        assertEquals(DirectRootState.HELPER_LOST, runtime.snapshot.state)
        assertTrue(runCatching { gateway.prepare(request) }.isFailure)
        runtime.close()
    }

    @Test fun `different audio generation cannot use a prepared gateway`() {
        val (runtime, peer) = ready()
        val gateway = DirectRootSystemAudioGateway(runtime)
        gateway.prepare(request)
        assertTrue(runCatching { gateway.start(request.copy(audioGeneration = 21)) }.isFailure)
        assertEquals(listOf("prepare"), peer.calls)
        assertTrue(runtime.snapshot.ready)
        gateway.close()
        runtime.close()
    }

    @Test fun `unprepared capture cannot start or stop or query peer`() {
        val (runtime, peer) = ready()
        val gateway = DirectRootSystemAudioGateway(runtime)
        assertTrue(runCatching { gateway.start(request) }.isFailure)
        assertTrue(runCatching { gateway.stop(request) }.isFailure)
        assertTrue(runCatching { gateway.status(request) }.isFailure)
        assertTrue(peer.calls.isEmpty())
        assertTrue(runtime.snapshot.ready)
        gateway.close()
        runtime.close()
    }

    @Test fun `UID2000 helper fails before any audio operation`() {
        val peer = Peer(uid = 2000)
        val runtime = runtime(peer)
        assertFalse(runtime.acquireAuthority().ready)
        assertTrue(runCatching { DirectRootSystemAudioGateway(runtime).prepare(request) }.isFailure)
        assertTrue(peer.calls.isEmpty())
        runtime.close()
    }
    private val request = RootAudioRequest(10, 20)
    private fun ready(generation: Long = 10): Pair<DirectRootRuntime, Peer> {
        val peer = Peer(generation)
        return runtime(peer).also { assertTrue(it.acquireAuthority().ready) } to peer
    }
    private fun runtime(peer: Peer) = DirectRootRuntime(object : RootRuntimeBootstrap {
        override val provenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
        override val generation = peer.identity.generation
        override val expectedArtifactSha256 = "a".repeat(64)
        override fun acquire(): RootControlSession = peer
        override fun cancel() = Unit
    })
    private class Peer(generation: Long = 10, uid: Int = 0) : RootControlSession, RootAudioControl {
        override val identity = RootHelperIdentity(
            123, uid, uid, "u:r:su:s0", 26, generation,
            DirectRootRuntime.PROTOCOL_VERSION, DirectRootRuntime.IMPLEMENTATION, "a".repeat(64),
        )
        var lost = false
        val calls = mutableListOf<String>()
        private fun call(name: String): String {
            check(!lost)
            calls += name
            return name
        }
        override fun ping() {
            check(!lost)
        }
        override fun shutdown() = Unit
        override fun close() = Unit
        override fun prepareAudio(request: RootAudioRequest) = call("prepare")
        override fun startAudio(request: RootAudioRequest) = call("start")
        override fun audioStatus(request: RootAudioRequest) = call("status")
        override fun stopAudio(request: RootAudioRequest) = call("stop")
        override fun closeAudio(request: RootAudioRequest) = call("close")
    }
}
