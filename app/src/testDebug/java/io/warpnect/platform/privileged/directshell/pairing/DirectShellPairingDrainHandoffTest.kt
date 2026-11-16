package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellSyntheticPairingComposition.Stage
import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real delegate throughout; only positive directory sync and explicit fault ports are modeled. */
class DirectShellPairingDrainHandoffTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    @After fun cleanup() = fixtures.forEach { it.close() }

    private fun fixture(mode: OwnedPairingPeerFixture.Mode = OwnedPairingPeerFixture.Mode.SUCCESS): Fixture =
        Fixture(temporary.newFolder().toPath(), mode).also(fixtures::add)

    @Test fun h01PassiveConstructionAndRequest() {
        val f = fixture()
        repeat(5) { f.handoff.state() }
        f.request()
        assertEquals(0, f.observers.get())
        assertEquals(0, f.sockets.get())
        assertNull(f.store)
        assertFalse(Files.exists(f.directory))
        assertFalse(f.handoff.state().occupied)
    }

    @Test fun h02ExactContextAndObservedPortRequired() {
        val f = fixture()
        val ticket = f.request()
        val legacy = object : DirectShellPairingObserver {
            override fun pairingStarted(recovery: DirectShellPairingRecovery) = false
            override fun prepared(authorization: DirectShellStagedAuthorization) = error("legacy_forbidden")
            override fun failed(reason: DirectShellPairingReason) = Unit
        }
        rejects { f.handoff.create(ticket, legacy) }
        f.hold(Stage.BEFORE_RECOVERY)
        f.submit()
        f.awaitHeld()
        val original = f.delegateOperation().workContext!!
        assertSame(ticket, original.ticket)
        assertSame(f.clock, original.clock)
        assertSame(f.clock.scheduler, original.scheduler)
        assertEquals(60_000L, original.deadlineElapsedRealtimeMs)
        DirectShellPairingCode.take(CODE.toCharArray())!!.use {
            rejects { f.operation.start(it) }
            rejects {
                f.operation.start(it, DirectShellPairingWorkContext(ticket, 60_000, { 0 }, f.clock.scheduler))
            }
        }
        f.owner.cancel(ticket)
        f.release()
        f.finish()
    }

    @Test fun h03OneObserverAcrossCleanAttempts() {
        val f = fixture()
        f.beforeRecovery = { error("fixture_pre_io_failure") }
        f.submit()
        f.finish()
        val first = f.ticket
        f.submit()
        f.finish()
        assertNotSame(first, f.ticket)
        assertEquals(1, f.observers.get())
        assertEquals(2, f.drains.get())
        assertTrue(f.handoff.state().observerAlive)
        assertFalse(f.handoff.state().occupied)
    }

    @Test fun h04CancelBeforeDelegateWorkerCreation() {
        val f = fixture()
        f.onObserverCreation = { f.operation.cancel() }
        assertFalse(f.submit())
        f.finish()
        assertNull(f.worker())
        assertNull(f.store)
        assertEquals(0, f.sockets.get())
        assertEquals(1, f.drains.get())
    }

    @Test fun h05ExitedWorkerBeforeObserverDispatch() {
        val f = fixture()
        f.pauseObserver = true
        f.submit()
        f.awaitObserver()
        f.joinWorker()
        assertFalse(f.owner.state.value.drained)
        f.dispatch.countDown()
        f.finish()
        assertTrue(f.owner.state.value.drained)
    }

    @Test fun h06ConcurrentCancellationAndCloseRegistration() {
        val f = fixture(OwnedPairingPeerFixture.Mode.PAKE_STALL)
        f.submit()
        f.awaitPeer()
        val go = CountDownLatch(1)
        val results = List(2) {
            FutureTask {
                await(go)
                f.owner.cancel(f.ticket)
            }
        }
        val threads = results.map { Thread(it).also { thread -> thread.start() } }
        go.countDown()
        results.forEach { it.get(3, TimeUnit.SECONDS) }
        threads.forEach {
            it.join(3_000)
            assertFalse(it.isAlive)
        }
        f.finish()
        assertEquals(1, f.drains.get())
        assertEquals(1, f.observers.get())
        assertTrue(f.socket!!.isClosed)
    }

    @Test fun h07HeldWorkerIsNotTermination() {
        val f = fixture()
        f.pauseObserver = true
        f.hold(Stage.BEFORE_EXIT)
        f.submit()
        f.awaitHeld()
        assertTrue(f.socket!!.isClosed)
        f.fs.assertReleased()
        f.expireObservationAndStop()
        assertTrue(f.worker()!!.isAlive)
        assertEquals(0, f.drains.get())
        assertTrue(f.handoff.state().occupied)
        assertFalse(f.owner.state.value.drained)
        assertNull(f.owner.request(1))
        f.release()
        f.joinWorker()
        assertEquals(0, f.drains.get())
        assertTrue(f.handoff.state().occupied)
    }

    @Test fun h08HeldStorageLeaseAfterNetworkExit() {
        val f = fixture()
        f.pauseObserver = true
        f.fs.drained = false
        f.submit()
        f.awaitObserver()
        f.joinWorker()
        assertTrue(f.socket!!.isClosed)
        assertTrue(f.fs.hasLiveLease())
        f.expireObservationAndStop()
        assertFalse(f.owner.state.value.drained)
        assertTrue(f.handoff.state().occupied)
        val competitor = DirectShellCredentialStorage(f.base, { StorageFixture.key }, ModeledDirectorySync())
        try {
            assertEquals(
                DirectShellCredentialStorage.Status.BUSY,
                competitor.begin(DirectShellPairingTicket("other", "other", 1, 1, 0), StorageFixture.identity),
            )
        } finally {
            assertTrue(competitor.close())
        }
        assertNull(f.owner.request(1))
    }

    @Test fun h09DispatchDelayConsumesWindowWithoutRearm() {
        val f = fixture()
        f.pauseObserver = true
        f.hold(Stage.BEFORE_EXIT)
        f.submit()
        f.awaitHeld()
        f.expireObservationAndStop()
        assertTrue(f.handoff.state().observationComplete)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.owner.state.value.phase)
        f.release()
        f.joinWorker()
        assertEquals(0, f.drains.get())
        assertTrue(f.handoff.state().occupied)
    }

    @Test fun h10DuplicateCloseAndCancelAreInert() {
        val f = fixture()
        f.pauseObserver = true
        f.hold(Stage.BEFORE_RECOVERY)
        f.submit()
        f.awaitHeld()
        f.owner.cancel(f.ticket)
        repeat(4) {
            f.operation.cancel()
            f.operation.close { fail("duplicate_callback") }
            f.owner.cancel(f.ticket)
        }
        f.release()
        f.dispatch.countDown()
        f.finish()
        assertEquals(1, f.drains.get())
        assertEquals(1, f.observers.get())
    }

    @Test fun h11ReentrantNewGenerationSurvivesOldFinally() {
        val f = fixture()
        f.beforeRecovery = { error("fixture_pre_io_failure") }
        val newStarted = CountDownLatch(1)
        f.afterDrain = {
            if (f.drains.get() == 1) {
                f.beforeRecovery = {}
                f.hold(Stage.BEFORE_RECOVERY)
                assertTrue(f.submit())
                newStarted.countDown()
            }
        }
        f.submit()
        val previous = f.operations.first()
        await(newStarted)
        f.awaitHeld()
        assertNotSame(previous.ticket, f.ticket)
        assertTrue(f.handoff.state().occupied)
        previous.cancel()
        previous.close { fail("old_callback") }
        assertTrue(f.handoff.state().occupied)
        f.release()
        f.finish()
        assertEquals(2, f.drains.get())
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.owner.state.value.phase)
    }

    @Test fun h12UnknownPublicationAndLateConflictAreNotSuccess() {
        val unknown = fixture()
        unknown.fs.unknownPublication = true
        unknown.submit()
        unknown.finish()
        assertTrue(unknown.owner.state.value.drained)
        assertEquals(DirectShellPairingPublication.UNKNOWN, unknown.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, unknown.owner.state.value.phase)
        assertNull(unknown.owner.request(1))
        val late = fixture()
        late.submit()
        late.finish()
        // Deliberately conflicting trusted-port metadata, not a fabricated successful exchange or D.
        late.observer.prepared(
            DirectShellStagedAuthorization {},
            DirectShellPairingProtocolObservation(
                late.ticket,
                DirectShellPairingProtocolVerification.UNCONFIRMED,
                DirectShellPairingExternalEffect.NONE_POSSIBLE,
            ),
        )
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, late.owner.state.value.phase)
        assertNull(late.owner.request(1))
    }

    @Test fun h13InfrastructureHasIndependentTermination() {
        val f = fixture()
        f.submit()
        f.finish()
        assertTrue(f.owner.state.value.drained)
        assertTrue(f.handoff.state().observerAlive)
        assertFalse(f.handoff.awaitObserverTermination(0))
        f.handoff.stopObserver()
        assertTrue(f.handoff.awaitObserverTermination())
        assertFalse(f.observerThread!!.isAlive)
    }

    @Test fun h14FailedDispatchKeepsOwnerFenced() {
        val f = fixture()
        f.failDispatch = true
        assertFalse(f.submit())
        assertTrue(f.handoff.state().observerFailed)
        assertTrue(f.handoff.state().occupied)
        assertFalse(f.owner.state.value.drained)
        assertNull(f.store)
        assertEquals(0, f.sockets.get())
        f.clock.advance(3_000)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.owner.state.value.phase)
        assertNull(f.owner.request(1))
        f.handoff.stopObserver()
        assertTrue(f.handoff.awaitObserverTermination())
        rejects { f.handoff.create(f.ticket, f.observer) }
    }

    @Test fun h14ShutdownRequestIsNotInfrastructureTermination() {
        val f = fixture()
        f.pauseObserver = true
        f.hold(Stage.BEFORE_RECOVERY)
        f.submit()
        f.awaitHeld()
        f.awaitObserver()
        f.owner.cancel(f.ticket)
        f.handoff.stopObserver()
        assertFalse(f.handoff.awaitObserverTermination(0))
        assertTrue(f.handoff.state().occupied)
        assertFalse(f.owner.state.value.drained)
        f.release()
        f.dispatch.countDown()
        f.finish()
        assertTrue(f.handoff.awaitObserverTermination())
    }

    @Test fun h15AutomaticActualSyntheticRpsdf() {
        val f = fixture()
        assertTrue(f.submit())
        f.finish()
        val state = f.owner.state.value
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, state.phase)
        assertEquals(DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED, state.protocolVerification)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, state.externalEffect)
        assertEquals(DirectShellPairingPublication.PUBLISHED, state.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, state.durability)
        assertTrue(state.drained)
        assertEquals(1, f.sockets.get())
        assertTrue(f.socket!!.isClosed)
        assertEquals(1, f.borrowReleased.get())
        assertFalse(f.delegateOperation().workerAlive())
        assertTrue(f.handoff.state().observerAlive)
        f.fs.assertReleased()
        val decoded = DirectShellCredentialCodec.decode(
            Files.readAllBytes(f.directory.resolve("identity.record")),
            StorageFixture.key,
        )
        assertTrue(
            decoded.peerDigest!!.contentEquals(
                DirectShellCredentialCodec.digest(StorageFixture.identity.certificate.encoded),
            ),
        )
        assertEquals(
            DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION,
            DirectShellCredentialStorage(f.base, { StorageFixture.key }, ModeledDirectorySync()).inspect().status,
        )
        assertNull(f.owner.request(1))
        assertFalse(state.toString().contains(CODE))
    }

    @Test fun h16ActualTlsCancellationAndAutomaticDrain() = cancelProtocol(OwnedPairingPeerFixture.Mode.TLS_STALL)

    @Test fun h16ActualPakeCancellationAndAutomaticDrain() = cancelProtocol(OwnedPairingPeerFixture.Mode.PAKE_STALL)

    private fun cancelProtocol(mode: OwnedPairingPeerFixture.Mode) {
        val f = fixture(mode)
        f.submit()
        f.awaitPeer()
        f.owner.cancel(f.ticket)
        f.finish()
        assertTrue(f.socket!!.isClosed)
        assertFalse(f.delegateOperation().workerAlive())
        assertTrue(f.owner.state.value.drained)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.owner.state.value.externalEffect)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.owner.state.value.phase)
        assertFalse(Files.exists(f.directory.resolve("identity.record")))
    }

    private class Clock : DirectShellPairingClock {
        @Volatile var now = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val scheduler = DirectShellPairingScheduler { deadline, action ->
            val entry = deadline to action
            synchronized(timers) { timers.add(entry) }
            DirectShellPairingTimer {
                synchronized(timers) { timers.remove(entry) }
                Unit
            }
        }
        override fun elapsedRealtimeMs() = now
        fun advance(amount: Long) {
            now += amount
            val actions = synchronized(timers) {
                timers.filter { it.first <= now }.also { timers.removeAll(it.toSet()) }.map { it.second }
            }
            actions.forEach { it() }
        }
    }

    private class Fixture(val base: Path, val mode: OwnedPairingPeerFixture.Mode) : AutoCloseable {
        val clock = Clock()
        val fs = FileSystem()
        val observers = AtomicInteger()
        val sockets = AtomicInteger()
        val drains = AtomicInteger()
        val borrowReleased = AtomicInteger()
        val operations = java.util.Collections.synchronizedList(
            mutableListOf<DirectShellPairingDrainHandoff.Operation>(),
        )
        val dispatch = CountDownLatch(1)
        private val observerEntered = CountDownLatch(1)
        private val held = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        private val peerReady = CountDownLatch(1)

        @Volatile private var done = CountDownLatch(1)

        @Volatile var pauseObserver = false

        @Volatile var failDispatch = false

        @Volatile var beforeRecovery: () -> Unit = {}

        @Volatile var onObserverCreation: () -> Unit = {}

        @Volatile var afterDrain: () -> Unit = {}

        @Volatile var heldStage: Stage? = null

        @Volatile var store: DirectShellCredentialStorage? = null

        @Volatile var peer: OwnedPairingPeerFixture? = null

        @Volatile var socket: Socket? = null

        @Volatile var observerThread: Thread? = null
        lateinit var operation: DirectShellPairingDrainHandoff.Operation
        lateinit var ticket: DirectShellPairingTicket
        lateinit var observer: ObservedDirectShellPairingObserver
        val directory: Path get() = base.resolve(DirectShellCredentialStorage.DIRECTORY)
        lateinit var owner: DirectShellPairingCoordinator
        private val adapter = DirectShellSyntheticPairingComposition(
            clock,
            clock.scheduler,
            {
                outside()
                DirectShellCredentialStorage(base, { StorageFixture.key }, fs).also { store = it }
            },
            {
                outside()
                DirectShellSyntheticPairingComposition.IdentityBorrow(StorageFixture.identity) {
                    outside()
                    borrowReleased.incrementAndGet()
                    true
                }
            },
            {
                outside()
                OwnedPairingPeerFixture(mode).also {
                    peer = it
                    peerReady.countDown()
                }.endpoint
            },
            {
                outside()
                sockets.incrementAndGet()
                Socket().also { socket = it }
            },
            { stage ->
                outside()
                if (stage == Stage.BEFORE_RECOVERY) beforeRecovery()
                if (stage == heldStage) {
                    held.countDown()
                    await(proceed)
                }
            },
        )
        val handoff = DirectShellPairingDrainHandoff(adapter, clock, clock.scheduler) { runnable ->
            observers.incrementAndGet()
            if (failDispatch) error("fixture_observer_dispatch_failed")
            onObserverCreation()
            Thread({
                observerEntered.countDown()
                if (pauseObserver) await(dispatch)
                runnable.run()
            }, "test-owned-drain-infrastructure").also { observerThread = it }
        }

        init {
            fs.checkWorker = ::outside
            owner = DirectShellPairingCoordinator(
                { pairingRequiredUiState(1) }, { DirectShellPairingEnvironment(true, true, 1, 0) },
                clock, clock.scheduler,
                { t, o ->
                    observer = o as ObservedDirectShellPairingObserver
                    operation = handoff.create(t, o)
                    operations.add(operation)
                    object : DeadlineAwarePairingOperation {
                        private val exact = operation
                        override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) =
                            exact.start(code, work)
                        override fun cancel() = exact.cancel()
                        override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                            val completion = done
                            exact.close {
                                drained(it)
                                drains.incrementAndGet()
                                afterDrain()
                                completion.countDown()
                            }
                        }
                    }
                }, handoff, "drain-test-owner", { "attempt-${drains.get()}" },
            )
        }
        fun outside() = check(!Thread.holdsLock(owner)) { "blocking_under_p1" }
        fun request() = requireNotNull(owner.request(1)).also { ticket = it }
        fun submit(): Boolean {
            done = CountDownLatch(1)
            if (!::ticket.isInitialized || drains.get() > 0) request()
            return owner.submitCode(ticket, CODE.toCharArray())
        }
        fun delegateOperation(): DirectShellSyntheticPairingComposition.Operation =
            operation.javaClass.getDeclaredField("operation").apply { isAccessible = true }.get(operation) as
                DirectShellSyntheticPairingComposition.Operation
        fun worker(): Thread? = delegateOperation().javaClass.getDeclaredField("worker")
            .apply { isAccessible = true }.get(delegateOperation()) as Thread?
        fun joinWorker() {
            worker()?.let {
                it.join(3_000)
                assertFalse(it.isAlive)
            }
        }
        fun hold(stage: Stage) {
            heldStage = stage
        }
        fun awaitHeld() = await(held)
        fun awaitObserver() = await(observerEntered)
        fun release() = proceed.countDown()
        fun awaitPeer() {
            await(peerReady)
            peer!!.await()
        }
        fun finish() {
            await(done)
            assertFalse(delegateOperation().workerAlive())
        }
        fun expireObservationAndStop() {
            awaitObserver()
            clock.advance(3_000)
            dispatch.countDown()
            handoff.stopObserver()
            assertTrue(handoff.awaitObserverTermination())
        }
        override fun close() {
            release()
            dispatch.countDown()
            owner.close()
            handoff.stopObserver()
            assertTrue(handoff.awaitObserverTermination())
            if (::operation.isInitialized) joinWorker()
            fs.drained = true
            assertTrue(store?.close() ?: true)
            fs.assertReleased()
            peer?.close()
        }
    }

    private class FileSystem : ModeledDirectorySync() {
        var checkWorker: () -> Unit = {}

        @Volatile var drained = true
        var unknownPublication = false
        private val leases = mutableListOf<DirectShellCredentialLease>()
        override fun canonicalBase(path: Path): Path {
            checkWorker()
            return super.canonicalBase(path)
        }
        override fun acquireLease(directory: Path, create: Boolean): DirectShellCredentialLease? =
            super.acquireLease(directory, create)?.also { leases.add(it) }
        override fun publishNoReplace(record: Path, prepared: Path): DirectShellCredentialPublishResult {
            val result = super.publishNoReplace(record, prepared)
            return if (unknownPublication && result == DirectShellCredentialPublishResult.PUBLISHED_RETAINED) {
                DirectShellCredentialPublishResult.UNKNOWN_EFFECT
            } else {
                result
            }
        }
        override fun resourcesDrained() = drained
        fun hasLiveLease() = leases.any { (field(it, "lock") as FileLock).isValid }
        fun assertReleased() {
            leases.forEach {
                assertFalse((field(it, "lock") as FileLock).isValid)
                assertFalse((field(it, "channel") as FileChannel).isOpen)
                val registry = DirectShellCredentialLease::class.java.getDeclaredField("owners")
                    .apply { isAccessible = true }.get(null) as Map<*, *>
                assertNull(registry[field(it, "path")])
            }
        }
        private fun field(lease: DirectShellCredentialLease, name: String) =
            DirectShellCredentialLease::class.java.getDeclaredField(name).apply { isAccessible = true }.get(lease)
    }

    private companion object {
        const val CODE = DirectShellPairingSyntheticCompositionTest.CODE
        fun await(latch: CountDownLatch) = check(latch.await(10, TimeUnit.SECONDS)) { "fixture_boundary_timeout" }
        fun rejects(action: () -> Unit) {
            try {
                action()
                fail("expected_fail_closed")
            } catch (_: IllegalStateException) {
            } catch (_: IllegalArgumentException) { }
        }
    }
}
