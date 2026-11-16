package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.LinkAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellWifiEpochFenceTest {
    private val facts = DirectShellWifiEpochFence.Link("wlan0", listOf(LinkAddress("192.0.2.11", 24)))

    @Test
    fun incompleteBlockedAndForeignCallbacksCannotPublishEpoch() {
        DirectShellWifiEpochFence<String>().use { fence ->
            fence.available("wifi")
            fence.capabilities("wifi", true)
            fence.link("other", facts)
            fence.blocked("wifi", false)
            assertNull(fence.current())
            fence.link("wifi", facts)
            assertEquals(1L, fence.current()?.number)
            fence.blocked("wifi", true)
            assertNull(fence.current())
            fence.lost("other")
            fence.blocked("wifi", false)
            assertEquals(2L, fence.current()?.number)
        }
    }

    @Test
    fun observationChangeStopsExactWatchOnceWithoutRetargeting() {
        DirectShellWifiEpochFence<String>().use { fence ->
            admit(fence, "one")
            val first = checkNotNull(fence.current())
            var losses = 0
            val old = checkNotNull(fence.watch(first) { losses++ })
            assertNull(fence.watch(first) { error("duplicate operation") })
            fence.link("one", facts)
            assertSame(first, fence.current())
            admit(fence, "two")
            assertEquals(1, losses)
            assertNull(fence.watch(first) { error("stale admission") })
            val second = checkNotNull(fence.current())
            checkNotNull(fence.watch(second) { losses++ })
            old.close()
            fence.lost("one")
            assertSame(second, fence.current())
            fence.lost("two")
            assertEquals(2, losses)
            assertNull(fence.current())
        }
    }

    @Test
    fun closeFencesLateCapabilitiesAndInvalidatesItsWatch() {
        val fence = DirectShellWifiEpochFence<String>()
        admit(fence, "one")
        var losses = 0
        checkNotNull(fence.watch(checkNotNull(fence.current())) { losses++ })
        fence.close()
        fence.close()
        admit(fence, "one")
        assertEquals(1, losses)
        assertNull(fence.current())
    }

    @Test
    fun independentlySampledLinkMustMatchAndStillBelongToTheOriginalEpoch() {
        DirectShellWifiEpochFence<String>().use { fence ->
            admit(fence, "one")
            val original = checkNotNull(fence.current())
            assertTrue(fence.verify(original) { name -> facts.also { assertEquals("one", name) } })
            assertFalse(fence.verify(original) { null })
            assertFalse(fence.verify(original) { facts.copy(interfaceName = "wlan1") })
            val raced = fence.verify(original) {
                fence.lost("one")
                admit(fence, "one")
                facts
            }
            assertFalse(raced)
            assertFalse(fence.verify(original) { error("superseded_epoch_must_not_resample") })
            val replacement = checkNotNull(fence.current())
            assertEquals(2L, replacement.number)
            assertTrue(fence.verify(replacement) { facts })
        }
    }

    private fun admit(fence: DirectShellWifiEpochFence<String>, name: String) {
        fence.available(name)
        fence.capabilities(name, true)
        fence.link(name, facts)
        fence.blocked(name, false)
    }
}
