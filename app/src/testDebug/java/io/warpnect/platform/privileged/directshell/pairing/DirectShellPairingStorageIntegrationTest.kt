package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One manually advanced operation worker, actual P1 opaque reservations, real FS except modeled directory sync. */
@RunWith(Parameterized::class)
class DirectShellPairingStorageIntegrationTest(private val consumed: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sourceConsumed={0}")
        fun publicationModes(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val temporary = TemporaryFolder()
    private var fixture: Fixture? = null

    @After
    fun cleanup() {
        fixture?.let {
            it.owner.close()
            assertTrue(it.storage.close())
        }
    }

    private fun start(): Fixture = Fixture(temporary.root.toPath(), consumed).also {
        fixture = it
        it.submit()
    }

    @Test
    fun `passive state and request never run storage or create worker`() {
        val f = Fixture(temporary.root.toPath(), consumed).also { fixture = it }
        repeat(10) { f.owner.state.value }
        assertNotNull(f.owner.request(1))
        assertEquals(0, f.fs.calls)
        assertFalse(Files.exists(f.record.parent))
        assertEquals(0, f.operationStarts)
    }

    @Test
    fun `storage runs outside monitor through R P S receipt D and F`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.publish()
        assertEquals(DirectShellPairingPhase.CLOSING, f.phase)
        assertFalse(f.owner.state.value.drained)
        f.drain()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
        assertTrue(f.owner.state.value.drained)
        assertEquals(1, f.operation.starts)
        assertEquals(1, f.fs.links)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `cancel before worker preparation never starts persistence`() {
        val f = start()
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(f.ticket))
        f.drain()
        assertEquals(0, f.fs.calls)
        assertFalse(Files.exists(f.record))
    }

    @Test
    fun `cancel before R denies authorization retains encrypted recovery`() {
        val f = start()
        f.prepare()
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(f.ticket))
        assertNull(f.control.reserve(f.candidate.prepared))
        f.drain()
        assertFalse(Files.exists(f.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `cancel immediately after R abstains unclaimed IO`() {
        val f = start()
        f.prepare()
        f.reserve()
        assertEquals(DirectShellPairingStop.COMMIT_PENDING_NO_ROLLBACK_GUARANTEE, f.owner.cancel(f.ticket))
        f.publish()
        f.drain()
        assertEquals(0, f.fs.links)
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `cancel after claim immediately before P cannot promise rollback`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.fs.atLink = {
            assertEquals(DirectShellPairingStop.COMMIT_PENDING_NO_ROLLBACK_GUARANTEE, f.owner.cancel(f.ticket))
        }
        f.publish()
        f.drain()
        assertTrue(Files.exists(f.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `cancel immediately after P preserves stored credentials`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.fs.afterLink = { f.owner.cancel(f.ticket) }
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertTrue(Files.exists(f.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `expiry during publication does not revive consent after late receipt`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.fs.atLink = {
            f.now += DirectShellPairingCoordinator.WORK_MS
            f.runTimers()
            assertFalse(f.storage.close())
        }
        f.publish()
        f.drain()
        assertTrue(Files.exists(f.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `network and permission invalidation before R deny storage publication`() {
        val f = start()
        f.prepare()
        f.environment = f.environment.copy(networkEpoch = 99, permissionsGranted = false)
        f.owner.environmentChanged()
        assertNull(f.control.reserve(f.candidate.prepared))
        f.drain()
        assertFalse(Files.exists(f.record))
    }

    @Test
    fun `network invalidation after claim permits settlement but no eligible success`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.fs.atLink = {
            f.environment = f.environment.copy(networkEpoch = 99)
            f.owner.environmentChanged()
        }
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertTrue(Files.exists(f.record))
    }

    @Test
    fun `permission loss after P cannot erase a committed identity`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.fs.afterLink = {
            f.environment = f.environment.copy(permissionsGranted = false)
            f.owner.environmentChanged()
        }
        f.publish()
        f.drain()
        assertTrue(Files.exists(f.record))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `foreign revision before R never obtains a store reservation`() {
        val f = start()
        f.prepare()
        f.environment = f.environment.copy(credentialRevision = 4)
        assertNull(f.control.reserve(f.candidate.prepared))
        f.drain()
        assertEquals(0, f.fs.links)
    }

    @Test
    fun `completed storage publication returns the same receipt to actual P1`() {
        val f = start()
        f.prepare()
        f.reserve()
        val first = f.publish()
        val repeated = f.publish()
        assertEquals(first, repeated)
        assertEquals(1, f.fs.links)
        f.drain()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
    }

    @Test
    fun `duplicate identical receipt is inert conflicting receipt fails closed`() {
        val f = start()
        f.prepare()
        f.reserve()
        val receipt = f.publish()
        f.control.completed(f.reservation, receipt)
        assertEquals(1, f.operation.closes)
        f.control.completed(f.reservation, receipt.copy(durability = DirectShellPairingDurability.UNCONFIRMED))
        f.drain()
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertTrue(Files.exists(f.record))
    }

    @Test
    fun `positive receipt without D never releases operation slot`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.publish()
        f.now += DirectShellPairingCoordinator.CLEANUP_MS
        f.runTimers()
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        assertNull(f.owner.request(1))
        assertEquals(1, f.operation.starts)
        f.drain()
    }

    @Test
    fun `owner disposal during P admits no automatic replacement or Host effect`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.fs.atLink = { f.owner.close() }
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertNull(f.owner.request(1))
        assertEquals(1, f.fs.links)
    }

    @Test
    fun `wrapping key loss after R reports known no publication without invalid receipt`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.key = null
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertEquals(DirectShellPairingReason.COMMIT_FAILED, f.owner.state.value.reason)
        assertFalse(Files.exists(f.record))
    }

    @Test
    fun `post R invalidation before claim abstains without filesystem publication`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.environment = f.environment.copy(networkEpoch = 99)
        f.owner.environmentChanged()
        f.publish()
        f.drain()
        assertEquals(0, f.fs.links)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `permission loss alone before R denies a grant`() {
        val f = start()
        f.prepare()
        f.environment = f.environment.copy(permissionsGranted = false)
        assertNull(f.control.reserve(f.candidate.prepared))
        f.drain()
        assertFalse(Files.exists(f.record))
    }

    @Test
    fun `work deadline before R is enforced even without timer notification`() {
        val f = start()
        f.prepare()
        f.now += DirectShellPairingCoordinator.WORK_MS
        assertNull(f.control.reserve(f.candidate.prepared))
        f.drain()
        assertFalse(Files.exists(f.record))
    }

    @Test
    fun `sync failure after P remains unconfirmed in actual coordinator`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.fs.afterLink = { f.fs.failSync = true }
        f.publish()
        f.drain()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, f.owner.state.value.durability)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `cancel during blocked claimed publication cannot promise rollback or release lease`() {
        val f = start()
        f.prepare()
        f.reserve()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.fs.atLink = {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        val task = FutureTask { f.publish() }
        val worker = Thread(task).apply { start() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(DirectShellPairingStop.COMMIT_PENDING_NO_ROLLBACK_GUARANTEE, f.owner.cancel(f.ticket))
            assertFalse(f.storage.close())
            assertNull(f.owner.request(1))
        } finally {
            proceed.countDown()
            worker.join(10_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(DirectShellPairingPublication.PUBLISHED, task.get().publication)
        f.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `truthful drain before delayed receipt does not finalize authorization early`() {
        val f = start()
        f.prepare()
        f.reserve()
        val receipt = f.publish(deliver = false)
        f.owner.cancel(f.ticket)
        f.drain()
        assertNull(f.owner.request(1))
        f.control.completed(f.reservation, receipt)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
    }

    @Test
    fun `contradictory receipt cannot erase known encrypted publication`() {
        val f = start()
        f.prepare()
        f.reserve()
        f.publish()
        f.control.completed(
            f.reservation,
            DirectShellPairingStoreReceipt(
                DirectShellPairingPublication.NOT_PUBLISHED,
                DirectShellPairingDurability.NOT_APPLICABLE,
            ),
        )
        f.drain()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertNull(f.owner.request(1))
        assertTrue(Files.exists(f.record))
    }

    private class Fixture(base: Path, consumed: Boolean) {
        var now = 100L
        var environment = DirectShellPairingEnvironment(true, true, 7, 0)
        var key: javax.crypto.SecretKey? = StorageFixture.key
        val tasks = mutableListOf<Timer>()
        val fs = GuardedFileSystem(consumed) { assertFalse("I/O under P1 monitor", Thread.holdsLock(owner)) }
        val storage = DirectShellCredentialStorage(base, {
            assertFalse(Thread.holdsLock(owner))
            key
        }, fs)
        lateinit var operation: Operation
        val operationStarts: Int get() = if (::operation.isInitialized) operation.starts else 0
        lateinit var ticket: DirectShellPairingTicket
        lateinit var candidate: DirectShellCredentialStorage.Candidate
        lateinit var control: DirectShellPairingCommitControl
        lateinit var reservation: DirectShellPairingCommitReservation
        var authenticated = false
        var began = false
        val record = base.resolve(DirectShellCredentialStorage.DIRECTORY).resolve(DirectShellCredentialStorage.RECORD)
        val owner: DirectShellPairingCoordinator = DirectShellPairingCoordinator(
            readiness = { pairingRequiredUiState() },
            environment = { environment },
            clock = { now },
            scheduler = { deadline, action -> Timer(deadline, action).also(tasks::add) },
            backend = { _, observer -> Operation(observer).also { operation = it } },
            committer = { _, _, port -> control = port },
            ownerEpoch = "integration-owner",
            nextNonce = { "integration-nonce" },
        )
        val phase get() = owner.state.value.phase

        fun submit() {
            ticket = owner.request(1)!!
            val code = "193842".toCharArray()
            assertTrue(owner.submitCode(ticket, code))
            assertTrue(code.all { it == '\u0000' })
            assertFalse(owner.submitCode(ticket, "193842".toCharArray()))
            assertEquals(0, fs.calls)
        }

        fun prepare() {
            assertEquals(
                DirectShellCredentialStorage.Status.RECOVERY_READY,
                storage.begin(ticket, StorageFixture.identity),
            )
            began = true
            assertTrue(operation.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
            val authorization = DirectShellStagedAuthorization {}
            candidate = storage.prepare(StorageFixture.evidence(ticket), authorization).candidate!!
            authenticated = true
            operation.observer.prepared(authorization)
        }

        fun reserve() {
            reservation = control.reserve(candidate.prepared)!!
            assertNull(control.reserve(candidate.prepared))
        }

        fun publish(deliver: Boolean = true): DirectShellPairingStoreReceipt = storage.publish(
            candidate,
            reservation,
        ).also {
            if (it.publication == DirectShellPairingPublication.PUBLISHED) {
                environment = environment.copy(credentialRevision = 1)
            }
            if (deliver) control.completed(reservation, it)
        }

        fun drain() {
            assertTrue(storage.close())
            operation.drained?.invoke(
                DirectShellPairingDrainReceipt(
                    if (authenticated) {
                        DirectShellPairingExternalEffect.AUTHENTICATED
                    } else {
                        DirectShellPairingExternalEffect.NONE_POSSIBLE
                    },
                    when {
                        Files.exists(record) -> DirectShellPairingRecovery.COMMITTED_RECORD_PRESENT
                        began -> DirectShellPairingRecovery.RETAINED_UNCONFIRMED
                        else -> DirectShellPairingRecovery.NONE
                    },
                ),
            )
        }

        fun runTimers() {
            tasks.toList().filter { !it.cancelled && it.deadline <= now }.forEach {
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

    private class Operation(val observer: DirectShellPairingObserver) : DirectShellPairingOperation {
        var starts = 0
        var closes = 0
        var drained: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        override fun start(code: DirectShellPairingCode) {
            starts++
        }
        override fun cancel() = Unit
        override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
            closes++
            this.drained = drained
        }
    }

    private class GuardedFileSystem(private val consumed: Boolean, private val checkWorker: () -> Unit) :
        ModeledDirectorySync() {
        var calls = 0
        var links = 0
        var failSync = false
        var atLink: () -> Unit = {}
        var afterLink: () -> Unit = {}
        override fun publishNoReplace(record: Path, prepared: Path): DirectShellCredentialPublishResult {
            if (!consumed) return super.publishNoReplace(record, prepared)
            checkWorker()
            links++
            atLink()
            return try {
                super.linkNoReplace(record, prepared)
                Files.delete(prepared) // Modeled rename source effect, not an Android atomicity claim.
                afterLink()
                DirectShellCredentialPublishResult.PUBLISHED_CONSUMED
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                DirectShellCredentialPublishResult.COLLISION
            } catch (_: Exception) {
                DirectShellCredentialPublishResult.UNKNOWN
            }
        }
        override fun syncDirectory(path: Path) {
            checkWorker()
            calls++
            if (failSync) throw java.io.IOException("fixture")
            super.syncDirectory(path)
        }
        override fun writeNew(path: Path, bytes: ByteArray) {
            checkWorker()
            calls++
            super.writeNew(path, bytes)
        }
        override fun linkNoReplace(record: Path, prepared: Path) {
            checkWorker()
            links++
            atLink()
            super.linkNoReplace(record, prepared)
            afterLink()
        }
    }
}
