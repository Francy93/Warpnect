package io.warpnect.platform.privileged.runtime

import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.session.capability.AndroidCapabilityProbeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRootHostOwnershipTest {
    @Test fun `passive observation never starts provider resources`() = withOwner { fixture ->
        assertEquals(17L, DirectRootHostOwnership.passiveSnapshot()?.generation)
        assertEquals(0, fixture.acquired)
    }

    @Test fun `generation mismatch fails before resource acquisition`() = withOwner { fixture ->
        assertNull(DirectRootHostOwnership.revalidate(18))
        assertEquals(0, fixture.acquired)
    }

    @Test fun `authenticated current generation can acquire and release Host resources`() = withOwner { fixture ->
        assertEquals(17L, DirectRootHostOwnership.acquire(17, emptyList()).generation)
        DirectRootHostOwnership.release()
        assertEquals(1, fixture.acquired)
        assertEquals(1, fixture.closed)
        assertEquals(1, fixture.released)
    }

    @Test fun `lost helper cannot be replaced with cached READY`() = withOwner { fixture ->
        fixture.alive = false
        assertNull(DirectRootHostOwnership.revalidate(17))
        assertNull(DirectRootHostOwnership.passiveSnapshot())
        assertEquals(DirectRootState.HELPER_LOST, fixture.owner.snapshot.state)
    }

    @Test fun `double release closes capability owner once`() = withOwner { fixture ->
        DirectRootHostOwnership.acquire(17, emptyList())
        DirectRootHostOwnership.release()
        DirectRootHostOwnership.release()
        assertEquals(1, fixture.closed)
    }

    @Test fun `stale clear cannot release another runtime`() = withOwner { fixture ->
        DirectRootHostOwnership.clear(null)
        assertTrue(DirectRootHostOwnership.passiveSnapshot()?.ready == true)
        assertEquals(0, fixture.closed)
    }

    private fun withOwner(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            fixture.owner.acquireAuthority()
            DirectRootHostOwnership.register(fixture.owner, { _, _ ->
                fixture.acquired++
                object : DirectRootHostResources {
                    override val generation = 17L
                    override val snapshot = AndroidCapabilityProbeSnapshot(lanSecurePathAvailable = true)
                    override val matrix = PrivilegedCapabilityMatrix.complete { provider, capability ->
                        CapabilityQualification.unavailable(
                            provider,
                            capability,
                            CapabilityQualificationState.NOT_AVAILABLE,
                            "unit_resource_owner_only",
                            CapabilityQualificationProvenance(CapabilityQualificationSource.PROVIDER_ADAPTER),
                        )
                    }
                    override fun close() {
                        fixture.closed++
                    }
                }
            }, { fixture.released++ })
            test(fixture)
        } finally {
            DirectRootHostOwnership.clear(fixture.owner)
            fixture.owner.close()
        }
    }

    private class Fixture {
        var alive = true
        var acquired = 0
        var closed = 0
        var released = 0
        val owner = DirectRootRuntime(object : RootRuntimeBootstrap {
            override val provenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
            override val generation = 17L
            override val expectedArtifactSha256 = "a".repeat(64)
            override fun cancel() = Unit
            override fun acquire() = object : RootControlSession {
                override val identity = RootHelperIdentity(
                    50, 0, 0, "u:r:su:s0", 26, 17,
                    DirectRootRuntime.PROTOCOL_VERSION, DirectRootRuntime.IMPLEMENTATION, "a".repeat(64),
                )
                override fun ping() {
                    check(alive)
                }
                override fun shutdown() = Unit
                override fun close() = Unit
            }
        })
    }
}
