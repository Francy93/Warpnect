package io.warpnect.platform.privileged.directshell.pairing

import android.os.Build
import android.os.Looper
import io.warpnect.platform.privileged.directshell.pairing.AndroidCompositionPeerFixture.Mode
import io.warpnect.platform.privileged.directshell.pairing.DirectShellSyntheticPairingComposition.Stage
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import java.io.FileDescriptor
import java.io.IOException
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Exact API30 fixture only. Real Android P/S; clocks/timer dispatch and named fault acknowledgements are modeled. */
class AndroidPairingCompositionBoundaryInstrumentationTest {
    private val owners = mutableListOf<Owner>()

    @After fun cleanup() = owners.forEach { it.close() }

    private fun owner(mode: Mode = Mode.SUCCESS) = Owner(mode).also(owners::add)

    @Test fun af01PassiveOwnerDoesNotTouchAndroidOrDispatch() {
        val o = owner()
        repeat(3) {
            o.p1.state.value
            o.handoff.state()
        }
        o.request()
        assertNull(o.fixture)
        assertNull(o.peer)
        assertEquals(0, o.observers.get())
        assertEquals(0, o.sockets.get())
    }

    @Test fun af02ExistingOnlyKeyReaderNeverProvisions() {
        val f = owner().setup(provision = false)
        assertNull(f.keys.existingAead())
        assertEquals(DirectShellKeyAvailability.MISSING, f.keys.evidence.availability)
        assertFalse(Files.exists(f.directory))
        assertTrue(f.alias.startsWith("warpnect.test.directshell.pairing.aes256.v1."))
        assertFalse(f.alias == AndroidDirectShellCredentialKeyStore.PRODUCTION_ALIAS)
    }

    @Test fun af03RepeatedAliasProvisionIsRejectedWithoutReplacingCipherKey() {
        val f = owner().setup()
        val key = requireNotNull(f.keys.existingAead())
        val bytes = DirectShellCredentialCodec.encode(
            DirectShellCredentialCodec.Record(
                DirectShellCredentialCodec.Kind.RECOVERY,
                DirectShellCredentialCodec.binding(f.ticket),
                AndroidPairingStorageFixture.identity,
                null,
            ),
            key,
        )
        rejects { f.provision() }
        assertNull(f.syntheticKey().encoded)
        val decoded = DirectShellCredentialCodec.decode(bytes, requireNotNull(f.keys.existingAead()))
        assertArrayEquals(
            AndroidPairingStorageFixture.identity.certificate.encoded,
            decoded.identity.certificate.encoded,
        )
    }

    @Test fun af04RecoveryIsAuthenticatedAndSyncedBeforeSocketOrPending() {
        val o = owner()
        o.hold(Stage.RECOVERY_READY)
        o.submit()
        o.awaitHeld()
        val f = requireNotNull(o.fixture)
        val anchor = Files.readAllBytes(f.directory.resolve(DirectShellCredentialStorage.ANCHOR))
        val decoded = DirectShellCredentialCodec.decode(anchor, requireNotNull(f.keys.existingAead()))
        assertEquals(DirectShellCredentialCodec.Kind.RECOVERY, decoded.kind)
        assertNull(decoded.peerDigest)
        assertArrayEquals(DirectShellCredentialCodec.binding(o.ticket), decoded.binding)
        assertEquals(DirectShellStorageObservation.OBSERVED, o.fs!!.evidence.dataSync)
        assertEquals(DirectShellStorageObservation.OBSERVED, o.fs!!.evidence.directorySync)
        assertEquals(DirectShellStorageObservation.OBSERVED, o.fs!!.evidence.parentDirectorySync)
        assertEquals(0, o.sockets.get())
        assertNull(o.peer)
        assertFalse(Files.exists(f.directory.resolve(DirectShellCredentialStorage.PENDING)))
        assertSame(o.ticket, o.delegateOperation().workContext!!.ticket)
        assertSame(o.clock, o.delegateOperation().workContext!!.clock)
        assertSame(o.clock.scheduler, o.delegateOperation().workContext!!.scheduler)
        o.release()
        o.finish()
    }

    @Test fun af05UnavailableKeyPreventsProtocolAndPublication() {
        val o = owner()
        o.setup(provision = false)
        o.submit()
        o.finish()
        assertEquals(0, o.sockets.get())
        assertNull(o.peer)
        assertFalse(Files.exists(o.fixture!!.record))
        assertFalse(o.p1.state.value.phase == DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY)
    }

    @Test fun af06LiveStorageOwnerExcludesCompositionInSameProcess() {
        val o = owner()
        val f = o.setup()
        val prior = f.adapter().storage
        assertEquals(
            DirectShellCredentialStorage.Status.RECOVERY_READY,
            prior.begin(f.ticket, AndroidPairingStorageFixture.identity),
        )
        o.submit()
        o.finish()
        assertNull(o.peer)
        assertEquals(0, o.sockets.get())
        assertFalse(Files.exists(f.record))
        assertTrue(prior.close())
    }

    @Test fun af07NativeNoReplacePreservesExistingWholeCiphertext() {
        val o = owner()
        o.hold(Stage.BEFORE_R)
        o.submit()
        o.awaitHeld()
        val f = o.fixture!!
        val pending = f.directory.resolve(DirectShellCredentialStorage.PENDING)
        val ciphertext = Files.readAllBytes(pending)
        Files.copy(pending, f.record)
        o.release()
        o.finish()
        assertArrayEquals(ciphertext, Files.readAllBytes(f.record))
        assertEquals(17, o.fs!!.evidence.publicationErrno)
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, o.p1.state.value.publication)
        assertFalse(o.p1.state.value.phase == DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY)
    }

    @Test fun af08ActualAndroidSyntheticRpsdfAndReadOnlyRestart() {
        val o = owner()
        o.submit()
        o.finish()
        val s = o.p1.state.value
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, s.phase)
        assertEquals(DirectShellPairingPublication.PUBLISHED, s.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, s.durability)
        assertEquals(DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED, s.protocolVerification)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, s.externalEffect)
        val f = o.fixture!!
        val bytes = Files.readAllBytes(f.record)
        val decoded = DirectShellCredentialCodec.decode(bytes, requireNotNull(f.keys.existingAead()))
        assertArrayEquals(DirectShellCredentialCodec.digest(o.peerIdentity!!.certificate.encoded), decoded.peerDigest)
        assertFalse(
            decoded.peerDigest!!.contentEquals(DirectShellCredentialCodec.digest(decoded.identity.certificate.encoded)),
        )
        assertArrayEquals(decoded.identity.certificate.encoded, o.peer!!.clientCertificate.get())
        assertEquals(DirectShellCredentialSource.CONSUMED, o.fs!!.evidence.publicationSource)
        assertFalse(Files.exists(f.directory.resolve(DirectShellCredentialStorage.PENDING)))
        assertFalse(Files.exists(f.directory.resolve(DirectShellCredentialStorage.ANCHOR)))
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION, f.adapter().storage.inspect().status)
        assertArrayEquals(bytes, Files.readAllBytes(f.record))
        assertEquals(1, o.sockets.get())
        assertEquals(1, o.borrowReleased.get())
        assertTrue(o.socket!!.isClosed)
        assertTrue(o.fs!!.resourcesDrained())
        assertFalse(o.delegateOperation().workerAlive())
        assertTrue(o.handoff.state().observerAlive)
        assertNull(o.p1.request(1))
        assertFalse(s.toString().contains(AndroidCompositionPeerFixture.CODE))
    }

    @Test fun af09CancellationClosesActualStalledTls() = cancelProtocol(Mode.TLS_STALL)

    @Test fun af10CancellationClosesActualStalledPake() = cancelProtocol(Mode.PAKE_STALL)

    private fun cancelProtocol(mode: Mode) {
        val o = owner(mode)
        o.submit()
        o.awaitPeer()
        o.p1.cancel(o.ticket)
        o.finish()
        assertTrue(o.socket!!.isClosed)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, o.p1.state.value.externalEffect)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, o.p1.state.value.phase)
        assertFalse(Files.exists(o.fixture!!.record))
    }

    @Test fun af11CancelAfterClientPeerInfoRetainsPossibleEffect() {
        val o = owner(Mode.HOLD_PEER_REPLY)
        o.submit()
        o.awaitPeer()
        o.peer!!.await(o.peer!!.peerInfo)
        o.p1.cancel(o.ticket)
        o.peer!!.proceed.countDown()
        o.finish()
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, o.p1.state.value.externalEffect)
        assertNull(o.p1.request(1))
        assertFalse(Files.exists(o.fixture!!.record))
    }

    @Test fun af12LostNativePublicationAckRemainsUnknownAfterRealRename() {
        val o = owner()
        o.fault = Fault.LOST_PUBLICATION_ACK
        o.submit()
        o.finish()
        assertTrue(Files.exists(o.fixture!!.record))
        assertFalse(Files.exists(o.fixture!!.directory.resolve(DirectShellCredentialStorage.PENDING)))
        assertEquals(DirectShellPairingPublication.UNKNOWN, o.p1.state.value.publication)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, o.p1.state.value.phase)
        assertTrue(o.p1.state.value.drained)
        assertNull(o.p1.request(1))
    }

    @Test fun af13LostPostPublicationSyncAckCannotFinalizeSuccess() {
        val o = owner()
        o.fault = Fault.LOST_SYNC_ACK
        o.submit()
        o.finish()
        assertEquals(DirectShellPairingPublication.PUBLISHED, o.p1.state.value.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, o.p1.state.value.durability)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, o.p1.state.value.phase)
        assertTrue(Files.exists(o.fixture!!.record))
        assertNull(o.p1.request(1))
    }

    @Test fun af14RealHeldRecoveryLeaseAndWorkerCannotBecomeDrainFromTimeout() {
        val o = owner()
        o.pauseObserver = true
        o.hold(Stage.RECOVERY_READY)
        o.submit()
        o.awaitHeld()
        assertEquals(DirectShellCredentialStorage.Status.BUSY, o.fixture!!.adapter().storage.inspect().status)
        o.p1.cancel(o.ticket)
        o.clock.advance(3_000)
        o.dispatch.countDown()
        o.handoff.stopObserver()
        assertTrue(o.handoff.awaitObserverTermination())
        assertTrue(o.delegateOperation().workerAlive())
        assertFalse(o.p1.state.value.drained)
        assertTrue(o.handoff.state().occupied)
        assertNull(o.p1.request(1))
        o.release()
        o.joinWorker()
        assertFalse(o.p1.state.value.drained)
    }

    @Test fun af15ModeledMissingStorageDrainAckBlocksFDespiteNetworkClosure() {
        val o = owner()
        o.pauseObserver = true
        o.modelStoreDrainFailure = true
        o.submit()
        o.joinWorker()
        assertTrue(o.socket!!.isClosed)
        assertFalse(o.fs!!.resourcesDrained())
        assertEquals(DirectShellCredentialStorage.Status.BUSY, o.fixture!!.adapter().storage.inspect().status)
        o.dispatch.countDown()
        o.handoff.stopObserver()
        assertTrue(o.handoff.awaitObserverTermination())
        assertFalse(o.p1.state.value.drained)
        assertTrue(o.handoff.state().occupied)
        assertNull(o.p1.request(1))
    }

    @Test fun af16StorageAndProtocolShareTheOriginalWorkDeadline() {
        val o = owner()
        o.hold(Stage.RECOVERY_READY)
        o.submit()
        o.awaitHeld()
        val work = o.delegateOperation().workContext!!
        o.clock.advance(20_000)
        assertEquals(60_000, work.deadlineElapsedRealtimeMs)
        assertEquals(40_000, work.deadlineElapsedRealtimeMs - o.clock.elapsedRealtimeMs())
        o.release()
        o.finish()
        assertSame(work, o.delegateOperation().workContext)
    }

    @Test fun af17ExpiryBeforeProtocolLeavesRecoveryButNoSocket() {
        val o = owner()
        o.hold(Stage.RECOVERY_READY)
        o.submit()
        o.awaitHeld()
        o.clock.advance(60_000)
        o.release()
        o.finish()
        assertEquals(0, o.sockets.get())
        assertTrue(Files.exists(o.fixture!!.directory.resolve(DirectShellCredentialStorage.ANCHOR)))
        assertFalse(Files.exists(o.fixture!!.record))
    }

    @Test fun af18ExpiryDuringTlsStopsTheSameOwnedSocket() {
        val o = owner(Mode.TLS_STALL)
        o.submit()
        o.awaitPeer()
        o.clock.advance(60_000)
        o.finish()
        assertTrue(o.socket!!.isClosed)
        assertEquals(60_000, o.delegateOperation().workContext!!.deadlineElapsedRealtimeMs)
        assertFalse(o.p1.state.value.phase == DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY)
    }

    @Test fun af19OldStopAndCloseCannotAffectNewTicketAfterPreIoCancellation() {
        val o = owner()
        o.hold(Stage.BEFORE_RECOVERY)
        o.submit()
        o.awaitHeld()
        val old = o.operation
        val ticket = o.ticket
        o.p1.cancel(ticket)
        o.release()
        o.finish()
        o.hold(Stage.BEFORE_RECOVERY)
        o.submit()
        o.awaitHeld()
        assertNotSame(ticket, o.ticket)
        old.cancel()
        old.close { fail("old_callback_repeated") }
        assertEquals(DirectShellPairingStop.STALE_OR_FINISHED, o.p1.cancel(ticket))
        assertSame(o.ticket, o.delegateOperation().workContext!!.ticket)
        assertTrue(o.handoff.state().occupied)
        o.p1.cancel(o.ticket)
        o.release()
        o.finish()
        assertEquals(0, o.sockets.get())
    }

    @Test fun af20ConsentExpiryAndClonedTicketNeverDispatch() {
        val o = owner()
        o.request()
        val clone = DirectShellPairingTicket("foreign", "foreign", 1, 1, 0)
        assertFalse(o.p1.submitCode(clone, AndroidCompositionPeerFixture.CODE.toCharArray()))
        o.clock.advance(120_000)
        assertFalse(o.p1.submitCode(o.ticket, AndroidCompositionPeerFixture.CODE.toCharArray()))
        assertNull(o.fixture)
        assertEquals(0, o.observers.get())
    }

    @Test fun af21KeyLostAfterRecoveryRetainsCiphertextAndDeniesProtocol() {
        val o = owner()
        o.hold(Stage.RECOVERY_READY)
        o.submit()
        o.awaitHeld()
        val f = o.fixture!!
        val path = f.directory.resolve(DirectShellCredentialStorage.ANCHOR)
        val bytes = Files.readAllBytes(path)
        f.removeOwnKey()
        o.release()
        o.finish()
        assertArrayEquals(bytes, Files.readAllBytes(path))
        assertEquals(0, o.sockets.get())
        assertEquals(DirectShellCredentialStorage.Status.KEY_UNAVAILABLE, f.adapter().storage.inspect().status)
    }

    @Test fun af22AuthenticatedChannelWithInvalidPeerInfoCannotPrepare() {
        val o = owner(Mode.BAD_GUID_TYPE)
        o.submit()
        o.finish()
        assertEquals(DirectShellPairingProtocolVerification.UNCONFIRMED, o.p1.state.value.protocolVerification)
        assertFalse(Files.exists(o.fixture!!.directory.resolve(DirectShellCredentialStorage.PENDING)))
        assertFalse(Files.exists(o.fixture!!.record))
    }

    @Test fun af23RequiredRecoverySyncFailureNeverEntersProtocol() {
        val o = owner()
        o.fault = Fault.RECOVERY_SYNC_FAILURE
        o.submit()
        o.finish()
        assertEquals(0, o.sockets.get())
        assertNull(o.peer)
        assertEquals(DirectShellPairingExternalEffect.NONE_POSSIBLE, o.p1.state.value.externalEffect)
        assertFalse(Files.exists(o.fixture!!.record))
    }

    @Test fun af24WrongCodeNeverAdoptsPeerOrPublishes() {
        val o = owner(Mode.WRONG_CODE)
        o.submit()
        o.finish()
        assertEquals(DirectShellPairingProtocolVerification.UNCONFIRMED, o.p1.state.value.protocolVerification)
        assertFalse(Files.exists(o.fixture!!.record))
        assertTrue(o.p1.state.value.drained)
    }

    private enum class Fault { NONE, RECOVERY_SYNC_FAILURE, LOST_PUBLICATION_ACK, LOST_SYNC_ACK }

    /** A manually dispatched port, not an Android elapsed/Handler scheduler implementation. */
    private class Clock : DirectShellPairingClock {
        @Volatile private var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val scheduler = DirectShellPairingScheduler { deadline, action ->
            val entry = deadline to action
            synchronized(tasks) {
                check(tasks.size < 8)
                tasks.add(entry)
            }
            DirectShellPairingTimer {
                synchronized(tasks) { tasks.remove(entry) }
                Unit
            }
        }
        override fun elapsedRealtimeMs() = now
        fun advance(amount: Long) {
            require(amount in 0..120_000)
            now += amount
            val actions = synchronized(tasks) {
                tasks.filter { it.first <= now }.also { tasks.removeAll(it.toSet()) }.map { it.second }
            }
            actions.forEach { it() }
        }
    }

    private class Owner(private val mode: Mode) : AutoCloseable {
        val clock = Clock()
        val observers = AtomicInteger()
        val sockets = AtomicInteger()
        val borrowReleased = AtomicInteger()
        private val nonces = AtomicInteger()
        val dispatch = CountDownLatch(1)

        @Volatile var fixture: AndroidPairingStorageFixture? = null

        @Volatile var peer: AndroidCompositionPeerFixture? = null

        @Volatile var peerIdentity: DirectShellCredentialIdentity? = null

        @Volatile var fs: AndroidDirectShellCredentialFileSystem? = null

        @Volatile var socket: Socket? = null

        @Volatile var store: DirectShellCredentialStorage? = null

        @Volatile var pauseObserver = false

        @Volatile var holdStoreDrain = false

        @Volatile var modelStoreDrainFailure = false

        @Volatile var fault = Fault.NONE

        @Volatile private var published = false

        @Volatile private var done = CountDownLatch(1)
        private val peerReady = CountDownLatch(1)
        private class Barrier(val stage: Stage) {
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
        }

        @Volatile private var barrier: Barrier? = null
        lateinit var ticket: DirectShellPairingTicket
        lateinit var operation: DirectShellPairingDrainHandoff.Operation
        lateinit var p1: DirectShellPairingCoordinator

        private val delegate = DirectShellSyntheticPairingComposition(
            clock,
            clock.scheduler,
            {
                outside()
                val f = requireNotNull(fixture)
                val adapter = f.adapter()
                val filesystem = if (fault == Fault.NONE && !modelStoreDrainFailure) {
                    adapter.filesystem
                } else {
                    val native = AndroidCredentialOs()
                    val port = object : DirectShellCredentialOs by native {
                        override fun sync(fd: FileDescriptor) {
                            if (fault == Fault.RECOVERY_SYNC_FAILURE) throw IOException("fixture_sync_failure")
                            native.sync(fd)
                            if (published && fault == Fault.LOST_SYNC_ACK) throw IOException("fixture_sync_ack_lost")
                        }
                        override fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult {
                            val result = native.publish(directory)
                            check(result == DirectShellCredentialPublishResult.PUBLISHED_CONSUMED)
                            published = true
                            if (fault == Fault.LOST_PUBLICATION_ACK) throw IOException("fixture_publication_ack_lost")
                            return result
                        }
                        override fun resourcesDrained() = !holdStoreDrain && native.resourcesDrained()
                    }
                    AndroidDirectShellCredentialFileSystem(f.base, port)
                }
                fs = filesystem
                (if (filesystem === adapter.filesystem) adapter.storage else f.store(filesystem)).also { store = it }
            },
            {
                outside()
                DirectShellSyntheticPairingComposition.IdentityBorrow(AndroidPairingStorageFixture.identity) {
                    outside()
                    borrowReleased.incrementAndGet()
                    true
                }
            },
            {
                outside()
                val remote = requireNotNull(peerIdentity)
                AndroidCompositionPeerFixture(mode, remote, AndroidPairingStorageFixture.identity).also {
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
                if (stage == Stage.PUBLISHED && modelStoreDrainFailure) holdStoreDrain = true
                barrier?.takeIf { it.stage == stage }?.let {
                    it.entered.countDown()
                    await(it.proceed)
                }
            },
        )
        val handoff = DirectShellPairingDrainHandoff(delegate, clock, clock.scheduler) { runnable ->
            observers.incrementAndGet()
            Thread({
                if (pauseObserver) await(dispatch)
                runnable.run()
            }, "api30-fixture-owner-observer")
        }

        init {
            p1 = DirectShellPairingCoordinator(
                ::readiness, { DirectShellPairingEnvironment(true, true, 1, 0) }, clock, clock.scheduler,
                { t, observer ->
                    val exact = handoff.create(t, observer)
                    operation = exact
                    object : DeadlineAwarePairingOperation {
                        override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) =
                            exact.start(code, work)
                        override fun cancel() = exact.cancel()
                        override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                            val completion = done
                            exact.close { receipt ->
                                drained(receipt)
                                completion.countDown()
                            }
                        }
                    }
                }, handoff, "api30-synthetic-test-owner", { "attempt-${nonces.incrementAndGet()}" },
            )
        }

        fun setup(provision: Boolean = true): AndroidPairingStorageFixture {
            outside()
            check(Build.VERSION.SDK_INT == 30) { "fixture_requires_exact_api30" }
            fixture?.let { return it }
            return AndroidPairingStorageFixture().also {
                fixture = it
                if (provision) it.provision()
                peerIdentity = AndroidPairingStorageFixture.newIdentity()
            }
        }
        fun request() = requireNotNull(p1.request(1)).also { ticket = it }
        fun submit() {
            setup()
            request()
            done = CountDownLatch(1)
            check(p1.submitCode(ticket, AndroidCompositionPeerFixture.CODE.toCharArray()))
        }
        fun hold(stage: Stage) {
            barrier = Barrier(stage)
        }
        fun awaitHeld() = await(requireNotNull(barrier).entered)
        fun release() {
            barrier?.proceed?.countDown()
        }
        fun awaitPeer() {
            await(peerReady)
            peer!!.await()
        }
        fun finish() {
            outside()
            await(done)
            check(!delegateOperation().workerAlive())
        }
        fun delegateOperation(): DirectShellSyntheticPairingComposition.Operation =
            operation.javaClass.getDeclaredField("operation").apply { isAccessible = true }.get(operation) as
                DirectShellSyntheticPairingComposition.Operation
        fun joinWorker() {
            outside()
            val worker = delegateOperation().javaClass.getDeclaredField("worker").apply { isAccessible = true }
                .get(delegateOperation()) as Thread?
            worker?.join(3_000)
            check(worker?.isAlive != true) { "fixture_operation_worker_held" }
        }
        private fun outside() = check(!Thread.holdsLock(p1) && Looper.myLooper() !== Looper.getMainLooper()) {
            "fixture_io_under_p1_or_ui"
        }

        override fun close() {
            outside()
            release()
            dispatch.countDown()
            p1.close()
            try {
                if (::operation.isInitialized) joinWorker()
            } finally {
                handoff.stopObserver()
                check(handoff.awaitObserverTermination()) { "fixture_observer_not_terminated" }
            }
            peer?.close()
            // Explicit test teardown can release its injected acknowledgement fault, never inventing D/F.
            holdStoreDrain = false
            check(store?.close() != false) { "fixture_store_not_drained" }
            fixture?.close()
        }
    }

    companion object {
        private fun await(latch: CountDownLatch) =
            check(latch.await(10, TimeUnit.SECONDS)) { "fixture_barrier_timeout" }
        private fun rejects(action: () -> Unit) {
            try {
                action()
                fail("fixture_expected_rejection")
            } catch (_: IllegalStateException) {
                Unit
            }
        }
        private fun readiness(): HostReadinessUiState {
            val reason = PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED
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
                    reason,
                ),
                PrivilegedRuntimeAvailability(
                    PrivilegedRuntimeKind.SHIZUKU,
                    PrivilegedRuntimeAvailabilityState.UNKNOWN,
                ),
            )
            return HostReadinessUiState(
                HostReadinessState.AuthorizationRequired(1, runtime, reason),
                hostActionVisible = true,
            )
        }
    }
}
