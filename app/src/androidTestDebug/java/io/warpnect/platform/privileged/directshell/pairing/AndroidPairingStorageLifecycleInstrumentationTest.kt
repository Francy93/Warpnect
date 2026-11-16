package io.warpnect.platform.privileged.directshell.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import java.io.FileDescriptor
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android storage/Keystore, fake authenticated protocol and manually advanced worker. */
@RunWith(AndroidJUnit4::class)
class AndroidPairingStorageLifecycleInstrumentationTest {
    private lateinit var fixture: AndroidPairingStorageFixture
    private var harness: Harness? = null

    @Before fun createFixture() {
        fixture = AndroidPairingStorageFixture()
    }

    @After fun releaseFixture() {
        harness?.owner?.close()
        fixture.close()
    }
    private fun ready(): Harness {
        fixture.provision()
        return Harness(fixture).also {
            harness = it
            it.start()
            it.prepare()
        }
    }

    @Test
    fun cancelBeforeReservationRetainsRecoveryButNeverPublishes() {
        val f = ready()
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(f.ticket))
        assertNull(f.control.reserve(f.candidate.prepared))
        f.drain()
        assertFalse(Files.exists(fixture.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun cancelAfterReservationAbstainsUnclaimedPublication() {
        val f = ready()
        f.reserve()
        assertEquals(DirectShellPairingStop.COMMIT_PENDING_NO_ROLLBACK_GUARANTEE, f.owner.cancel(f.ticket))
        f.publish()
        f.drain()
        assertFalse(Files.exists(fixture.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun cancelInsideClaimedPublicationCannotPromiseRollback() {
        val f = ready()
        f.reserve()
        f.beforeLink = { f.owner.cancel(f.ticket) }
        f.publish()
        f.drain()
        assertTrue(Files.exists(fixture.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun cancelAfterPublicationPreservesCommittedRecord() {
        val f = ready()
        f.reserve()
        f.afterLink = { f.owner.cancel(f.ticket) }
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertTrue(Files.exists(fixture.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun duplicateReceiptHasOnePublicationAndNoHostSideEffect() {
        val f = ready()
        f.reserve()
        val first = f.publish()
        assertEquals(first, f.publish())
        f.drain()
        assertEquals(1, f.links)
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun timeoutDuringStorageDoesNotReleaseUndrainedOperation() {
        val f = ready()
        f.reserve()
        f.beforeLink = {
            f.now += DirectShellPairingCoordinator.WORK_MS
            f.timers()
            assertFalse(f.store.close())
            assertNull(f.owner.request(1))
        }
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun lateReceiptAfterDisposalCannotReviveConsent() {
        val f = ready()
        f.reserve()
        f.beforeLink = { f.owner.close() }
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun receiptWithoutDrainNeverAllowsSecondOperation() {
        val f = ready()
        f.reserve()
        f.publish()
        f.now += DirectShellPairingCoordinator.CLEANUP_MS
        f.timers()
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        assertNull(f.owner.request(1))
        f.drain()
    }

    private class Harness(private val fixture: AndroidPairingStorageFixture) {
        var now = 1L
        private val scheduled = mutableListOf<Timer>()
        private var environment = DirectShellPairingEnvironment(true, true, 1, 0)
        var beforeLink: () -> Unit = {}
        var afterLink: () -> Unit = {}
        var links = 0
        private val native = AndroidCredentialOs()
        private val fs =
            AndroidDirectShellCredentialFileSystem(
                fixture.base,
                object : DirectShellCredentialOs by native {
                    override fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult {
                        links++
                        beforeLink()
                        val result = native.publish(directory)
                        afterLink()
                        return result
                    }
                },
            )
        val store = fixture.store(fs)
        lateinit var ticket: DirectShellPairingTicket
        lateinit var candidate: DirectShellCredentialStorage.Candidate
        lateinit var control: DirectShellPairingCommitControl
        private lateinit var reservation: DirectShellPairingCommitReservation
        private lateinit var observer: DirectShellPairingObserver
        private var drained: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        private val operation = object : DirectShellPairingOperation {
            override fun start(code: DirectShellPairingCode) = Unit
            override fun cancel() = Unit
            override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                this@Harness.drained = drained
            }
        }
        val owner: DirectShellPairingCoordinator = DirectShellPairingCoordinator(
            readiness = { readiness() },
            environment = { environment },
            clock = { now },
            scheduler = { deadline, action -> Timer(deadline, action).also(scheduled::add) },
            backend = { _, callback ->
                observer = callback
                operation
            },
            committer = { _, _, port -> control = port },
            ownerEpoch = "instrumentation-owner",
            nextNonce = { "instrumentation-attempt" },
        )
        val phase get() = owner.state.value.phase
        fun start() {
            ticket = owner.request(1)!!
            assertTrue(owner.submitCode(ticket, "582913".toCharArray()))
            assertFalse(Files.exists(fixture.directory))
        }
        fun prepare() {
            assertFalse(Thread.holdsLock(owner))
            assertEquals(
                DirectShellCredentialStorage.Status.RECOVERY_READY,
                store.begin(ticket, AndroidPairingStorageFixture.identity),
            )
            assertTrue(observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
            val auth = DirectShellStagedAuthorization {}
            candidate = store.prepare(fixture.evidence(ticket), auth).candidate!!
            observer.prepared(auth)
        }
        fun reserve() {
            reservation = control.reserve(candidate.prepared)!!
        }
        fun publish(): DirectShellPairingStoreReceipt {
            assertFalse(Thread.holdsLock(owner))
            val result = store.publish(candidate, reservation)
            if (result.publication == DirectShellPairingPublication.PUBLISHED) {
                environment = environment.copy(credentialRevision = 1)
            }
            control.completed(reservation, result)
            return result
        }
        fun drain() {
            assertTrue(store.close())
            drained!!.invoke(
                DirectShellPairingDrainReceipt(
                    DirectShellPairingExternalEffect.AUTHENTICATED,
                    if (Files.exists(fixture.record)) {
                        DirectShellPairingRecovery.COMMITTED_RECORD_PRESENT
                    } else {
                        DirectShellPairingRecovery.RETAINED_UNCONFIRMED
                    },
                ),
            )
        }
        fun timers() {
            scheduled.toList().filter { !it.cancelled && it.deadline <= now }.forEach {
                it.cancelled = true
                it.action()
            }
        }
    }

    private class Timer(val deadline: Long, val action: () -> Unit) : DirectShellPairingTimer {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    companion object {
        private fun readiness(): HostReadinessUiState {
            val runtime = PrivilegedRuntimeStateSnapshot(
                1,
                1,
                PrivilegedRuntimeAvailability(
                    PrivilegedRuntimeKind.DIRECT_ROOT,
                    PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
                ),
                PrivilegedRuntimeAvailability(
                    PrivilegedRuntimeKind.DIRECT_SHELL,
                    PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
                    PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED,
                ),
                PrivilegedRuntimeAvailability(
                    PrivilegedRuntimeKind.SHIZUKU,
                    PrivilegedRuntimeAvailabilityState.UNKNOWN,
                ),
            )
            return HostReadinessUiState(
                HostReadinessState.AuthorizationRequired(
                    1,
                    runtime,
                    PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED,
                ),
                hostActionVisible = true,
            )
        }
    }
}
