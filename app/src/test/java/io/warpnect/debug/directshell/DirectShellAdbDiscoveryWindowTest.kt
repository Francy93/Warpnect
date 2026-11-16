package io.warpnect.debug.directshell

import io.warpnect.debug.directshell.DirectShellSelfAdbEndpointDiscovery.DiscoveryException
import io.warpnect.debug.directshell.DirectShellSelfAdbEndpointDiscovery.DiscoveryRejection
import io.warpnect.debug.directshell.DirectShellSelfAdbEndpointDiscovery.Failure
import io.warpnect.platform.discovery.AndroidDiscoveryMulticastLockPlanner
import io.warpnect.platform.discovery.AndroidMulticastLockEnvironment
import io.warpnect.platform.discovery.AndroidMulticastLockPlan
import io.warpnect.session.discovery.DiscoveryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DirectShellAdbDiscoveryWindowTest {
    private val discovery = DirectShellSelfAdbEndpointDiscovery

    @Test
    fun api31RequiresCompatibilityLock() {
        assertEquals(
            AndroidMulticastLockPlan.AcquireCompatibilityLock,
            AndroidDiscoveryMulticastLockPlanner().plan(AndroidMulticastLockEnvironment(31, 0)),
        )
    }

    @Test
    fun exactGuidGetsMostOfTheOriginalDeadlineAndNoExtraTime() {
        var now = ns(300)
        val waits = mutableListOf<Long>()
        val queries = mutableListOf<Long>()
        assertFalse(
            discovery.awaitDiscoveryWithinBudget(0, 30_000, { now }, { budget ->
                waits += budget
                now += budget
                false
            }, {
                queries += now
                now += ns(25)
            }),
        )
        assertEquals(listOf(ns(1_700), ns(27_975)), waits)
        assertEquals(listOf(ns(2_000)), queries)
        assertEquals(ns(30_000), now)
    }

    @Test
    fun browseResultDoesNotStartExactQuery() {
        assertTrue(
            discovery.awaitDiscoveryWithinBudget(
                0, 30_000, { 0 }, { it == ns(2_000) }, { error("query after browse result") },
            ),
        )
    }

    @Test
    fun expiredBudgetStartsNeitherAwaitNorExactQuery() {
        assertFalse(
            discovery.awaitDiscoveryWithinBudget(
                0, 30_000, { ns(30_000) }, { error("wait after deadline") }, { error("query after deadline") },
            ),
        )
    }

    @Test
    fun unauthenticatedBrowseHasOneWaitAndNoDirectQuery() {
        val waits = mutableListOf<Long>()
        assertFalse(discovery.awaitDiscoveryWithinBudget(0, 8_000, { 0 }, { waits += it; false }, null))
        assertEquals(listOf(ns(8_000)), waits)
    }

    @Test
    fun scopedMulticastAcquiresBeforeDiscoveryAndClosesAfterSuccess() {
        val events = mutableListOf<String>()
        val result = discovery.withDiscoveryMulticast(AutoCloseable { events += "close" }, {
            events += "acquire"
            DiscoveryError.None
        }) {
            events += "discover"
            42
        }
        assertEquals(42, result)
        assertEquals(listOf("acquire", "discover", "close"), events)
    }

    @Test
    fun multicastScopeClosesOnTimeoutCancellationOrDiscoveryError() {
        val failures = listOf(
            DiscoveryException(Failure.CONNECTION_SERVICE_NOT_DISCOVERED),
            InterruptedException(),
            IllegalArgumentException(),
        )
        for (first in failures) {
            var releases = 0
            val actual = assertThrows(first.javaClass) {
                discovery.withDiscoveryMulticast(AutoCloseable { releases++ }, { DiscoveryError.None }) {
                    throw first
                }
            }
            assertSame(first, actual)
            assertEquals(1, releases)
        }
    }

    @Test
    fun multicastPermissionOrServiceFailureNeverDispatchesDiscovery() {
        var closes = 0
        val close = AutoCloseable { closes++ }
        val denied = assertThrows(DiscoveryException::class.java) {
            discovery.withDiscoveryMulticast(close, { throw SecurityException() }) { error("unauthorized discovery") }
        }
        assertEquals(Failure.DISCOVERY_SECURITY_ERROR, denied.failure)
        val unavailable = assertThrows(DiscoveryException::class.java) {
            discovery.withDiscoveryMulticast(close, { DiscoveryError.DiscoveryFailed }) { error("unavailable discovery") }
        }
        assertEquals(Failure.DISCOVERY_MULTICAST_UNAVAILABLE, unavailable.failure)
        assertEquals(2, closes)
    }

    @Test
    fun diagnosesTypeAndGuidIndependentlyWithoutReturningAnEndpoint() {
        val type = "_adb-tls-connect._tcp"
        val guid = "authenticated-guid"
        assertEquals(DiscoveryRejection.TYPE_MISMATCH, discovery.discoveryIdentityRejection(type, "_adb._tcp", guid, guid))
        assertEquals(DiscoveryRejection.GUID_MISMATCH, discovery.discoveryIdentityRejection(type, type, "not-mine", guid))
        assertNull(discovery.discoveryIdentityRejection(type, ".$type.", guid, guid))
    }

    @Test
    fun separatesMissingInvalidForeignAndWrongPairedAddress() {
        val paired = InetAddress.getByName("192.0.2.10")
        val secondLocal = InetAddress.getByName("192.0.2.11")
        val foreign = InetAddress.getByName("192.0.2.12")
        val reasons = mutableListOf<DiscoveryRejection>()
        fun reject(host: InetAddress?, port: Int) {
            assertNull(
                discovery.selectLocalEndpoint(
                    host, port, listOf(paired, secondLocal), "authenticated-guid",
                    requiredHost = paired.hostAddress, onRejected = { reasons += it },
                ),
            )
        }
        reject(null, 40_000)
        reject(paired, 0)
        reject(foreign, 40_000)
        reject(secondLocal, 40_000)
        assertEquals(
            listOf(
                DiscoveryRejection.MISSING_ADDRESS, DiscoveryRejection.INVALID_PORT,
                DiscoveryRejection.NONLOCAL_ADDRESS, DiscoveryRejection.PAIRED_ADDRESS_MISMATCH,
            ),
            reasons,
        )
    }

    private fun ns(ms: Long): Long = TimeUnit.MILLISECONDS.toNanos(ms)
}
