package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellSyntheticPairingComposition.Stage
import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real P1/storage/socket/pinned protocol; only positive directory-sync acknowledgement is modeled. */
class DirectShellPairingSyntheticCompositionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    @After
    fun cleanup() {
        fixtures.forEach { it.close() }
    }

    private fun fixture(mode: OwnedPairingPeerFixture.Mode = OwnedPairingPeerFixture.Mode.SUCCESS): Fixture =
        Fixture(temporary.newFolder().toPath(), mode).also(fixtures::add)

    @Test fun c01PassiveConstruction() {
        val f = fixture()
        repeat(5) { f.owner.state.value }
        f.request()
        assertEquals(0, f.created.get())
        assertEquals(0, f.fs.calls.get())
        assertNull(f.peer)
        assertFalse(Files.exists(f.directory))
    }

    @Test fun c02ExactWorkAndOptionalObserver() {
        val f = fixture()
        val t = f.request()
        val unobserved = object : DirectShellPairingObserver {
            override fun pairingStarted(recovery: DirectShellPairingRecovery) = false
            override fun prepared(authorization: DirectShellStagedAuthorization) = error("legacy_fallback")
            override fun failed(reason: DirectShellPairingReason) = Unit
        }
        assertFails { f.adapter.create(t, unobserved) }
        assertFalse(f.owner.submitCode(DirectShellPairingTicket("cloned", "ticket", 1, 1, 0), CODE.toCharArray()))
        assertEquals(0, f.created.get())
        f.hold(Stage.BEFORE_RECOVERY)
        f.submit()
        f.awaitHeld()
        val original = f.operation.workContext!!
        DirectShellPairingCode.take(CODE.toCharArray())!!.use { code ->
            assertFails {
                f.operation.start(
                    code,
                    DirectShellPairingWorkContext(t, original.deadlineElapsedRealtimeMs, { 0 }, f.scheduler),
                )
            }
        }
        assertEquals(0, f.fs.calls.get())
        f.owner.cancel(t)
        f.release()
        f.finish()
    }

    @Test fun c03RecoveryBeforeProtocol() {
        val f = fixture()
        f.hold(Stage.RECOVERY_READY)
        f.submit()
        f.awaitHeld()
        val anchor = f.record(DirectShellCredentialStorage.ANCHOR)
        val decoded = DirectShellCredentialCodec.decode(Files.readAllBytes(anchor), StorageFixture.key)
        assertEquals(DirectShellCredentialCodec.Kind.RECOVERY, decoded.kind)
        assertNull(decoded.peerDigest)
        assertTrue(f.fs.syncs >= 3)
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.PENDING)))
        assertNull(f.peer)
        assertEquals(0, f.sockets.get())
        f.release()
        f.finish()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.state.phase)
    }

    @Test fun c04DurabilityFailureNoProtocol() {
        val f = fixture()
        f.fs.failSync = 1
        f.submit()
        f.finish()
        assertNull(f.peer)
        assertEquals(0, f.sockets.get())
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.RECORD)))
        assertNotEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.state.phase)
    }

    @Test fun c03LostLeaseBlocksProtocol() {
        val f = fixture()
        f.hold(Stage.RECOVERY_READY)
        f.submit()
        f.awaitHeld()
        assertTrue(f.ownedStore!!.close())
        f.release()
        f.finish()
        assertNull(f.peer)
        assertEquals(0, f.sockets.get())
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.PENDING)))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
    }

    @Test fun c03ChangedRecoveryBlocksProtocol() {
        val f = fixture()
        f.hold(Stage.RECOVERY_READY)
        f.submit()
        f.awaitHeld()
        val anchor = f.record(DirectShellCredentialStorage.ANCHOR)
        val bytes = Files.readAllBytes(anchor)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(anchor, bytes)
        f.release()
        f.finish()
        assertNull(f.peer)
        assertEquals(0, f.sockets.get())
        assertArrayEquals(bytes, Files.readAllBytes(anchor))
        assertEquals(DirectShellCredentialStorage.Status.CORRUPT, f.inspect())
        assertNull(f.owner.request(1))
    }

    @Test fun c05UncertainRecoveryRetained() {
        val f = fixture()
        f.fs.failSync = 3
        f.submit()
        f.finish()
        assertTrue(Files.exists(f.record(DirectShellCredentialStorage.ANCHOR)))
        assertNull(f.peer)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
        assertNull(f.owner.request(1))
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_RECONCILIATION, f.inspect())
    }

    @Test fun c06ExistingNamespaceOwner() {
        val f = fixture()
        val other = f.store()
        try {
            assertEquals(
                DirectShellCredentialStorage.Status.RECOVERY_READY,
                other.begin(otherTicket(), StorageFixture.identity),
            )
            f.submit()
            f.finish()
            assertNull(f.peer)
            assertEquals(0, f.sockets.get())
        } finally {
            assertTrue(other.close())
        }
    }

    @Test fun c07RevisionAndCommittedIdentityNoReplacement() {
        val f = fixture()
        f.environment = f.environment.copy(credentialRevision = 1)
        f.submit()
        f.finish()
        assertNull(f.peer)
        val committed = fixture()
        committed.submit()
        committed.finish()
        val bytes = Files.readAllBytes(committed.record(DirectShellCredentialStorage.RECORD))
        val next = DirectShellCredentialStorage(committed.base, { StorageFixture.key }, ModeledDirectorySync())
        try {
            assertEquals(
                DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION,
                next.begin(otherTicket(), StorageFixture.identity),
            )
            assertArrayEquals(bytes, Files.readAllBytes(committed.record(DirectShellCredentialStorage.RECORD)))
        } finally {
            assertTrue(next.close())
        }
    }

    @Test fun c08AuthenticProtocolAndDistinctCertificates() {
        val f = fixture()
        f.submit()
        f.finish()
        assertEquals(1, f.sockets.get())
        assertArrayEquals(StorageFixture.identity.certificate.encoded, f.peer!!.clientCertificate.get())
        assertTrue(f.stages.contains(Stage.PEER_BOUND))
        val decoded = DirectShellCredentialCodec.decode(
            Files.readAllBytes(f.record(DirectShellCredentialStorage.RECORD)),
            StorageFixture.key,
        )
        assertArrayEquals(DirectShellCredentialCodec.digest(SERVER.certificate.encoded), decoded.peerDigest)
        assertFalse(
            decoded.peerDigest!!.contentEquals(DirectShellCredentialCodec.digest(decoded.identity.certificate.encoded)),
        )
    }

    @Test fun c09WrongCodeNoAuthenticatedCandidate() {
        val f = fixture(OwnedPairingPeerFixture.Mode.WRONG_CODE)
        f.submit()
        f.finish()
        assertFalse(f.stages.contains(Stage.PEER_BOUND))
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.PENDING)))
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.RECORD)))
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
        assertEquals(DirectShellPairingProtocolVerification.UNCONFIRMED, f.state.protocolVerification)
    }

    @Test fun c10InvalidPeerAndChangedRecoveryBinding() {
        val f = fixture(OwnedPairingPeerFixture.Mode.BAD_GUID_TYPE)
        f.submit()
        f.finish()
        assertFalse(f.stages.contains(Stage.PEER_BOUND))
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.PENDING)))
        val changed = fixture()
        changed.hold(Stage.PEER_BOUND)
        changed.submit()
        changed.awaitHeld()
        val anchor = changed.record(DirectShellCredentialStorage.ANCHOR)
        val record = DirectShellCredentialCodec.decode(Files.readAllBytes(anchor), StorageFixture.key)
        Files.write(
            anchor,
            DirectShellCredentialCodec.encode(
                DirectShellCredentialCodec.Record(record.kind, record.binding, SERVER, null),
                StorageFixture.key,
            ),
        )
        changed.release()
        changed.finish()
        assertFalse(Files.exists(changed.record(DirectShellCredentialStorage.RECORD)))
        assertNull(changed.operation.storeReceipt)
        assertNotEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, changed.state.phase)
    }

    @Test fun c11CancelBeforeAdmission() {
        val f = fixture()
        f.hold(Stage.BEFORE_RECOVERY)
        f.submit()
        f.awaitHeld()
        f.owner.cancel(f.ticket)
        f.release()
        f.finish()
        assertEquals(0, f.fs.calls.get())
        assertNull(f.peer)
        assertEquals(DirectShellPairingPhase.CANCELLED, f.state.phase)
    }

    @Test fun c10ExactBindingRejectsForeignWorkSocketEndpointAndIdentity() {
        val f = fixture()
        f.hold(Stage.PEER_BOUND)
        f.submit()
        f.awaitHeld()
        // Bounded read-only inspection of the exact live fixture operation; no synthetic peer result.
        val io = DirectShellSyntheticPairingComposition.Operation::class.java.getDeclaredField("transport")
            .apply { isAccessible = true }.get(f.operation) as DirectShellPairingTransport
        val protocol = DirectShellPairingTransport::class.java.getDeclaredField("protocol")
            .apply { isAccessible = true }.get(io) as DirectShellOwnedPairingProtocol
        val peer = requireNotNull(protocol.verifiedPeer())
        val work = requireNotNull(f.operation.workContext)
        val endpoint = f.peer!!.endpoint
        fun bind(
            ticket: DirectShellPairingTicket = f.ticket,
            provided: DirectShellPairingWorkContext = work,
            socket: Socket? = f.socket,
            address: InetSocketAddress = endpoint,
            identity: DirectShellCredentialIdentity = StorageFixture.identity,
        ) = DirectShellSyntheticPairingComposition.bindEvidence(ticket, provided, work, socket, address, identity, peer)
        assertTrue(bind().matches(StorageFixture.identity.certificate))
        assertArrayEquals(peer.remoteCertificateSha256, bind().peerDigestCopy())
        assertFails { bind(ticket = otherTicket()) }
        assertFails {
            bind(
                provided = DirectShellPairingWorkContext(
                    f.ticket,
                    work.deadlineElapsedRealtimeMs,
                    f.clock,
                    f.scheduler,
                ),
            )
        }
        assertFails { bind(socket = null) }
        assertFails {
            bind(
                address = InetSocketAddress(endpoint.address, if (endpoint.port == 65535) 65534 else endpoint.port + 1),
            )
        }
        assertFails { bind(identity = SERVER) }
        f.release()
        f.finish()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.state.phase)
    }

    @Test fun c12CancelDuringRecoveryWrite() {
        val f = fixture()
        f.fs.afterWrite = { path ->
            if (path.fileName.toString() == DirectShellCredentialStorage.ANCHOR) f.owner.cancel(f.ticket)
        }
        f.submit()
        f.finish()
        assertNull(f.peer)
        assertTrue(Files.exists(f.record(DirectShellCredentialStorage.ANCHOR)))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
    }

    @Test fun c13CancelTlsActualDrain() = cancelAtPeer(OwnedPairingPeerFixture.Mode.TLS_STALL)

    @Test fun c14CancelPakeActualDrain() = cancelAtPeer(OwnedPairingPeerFixture.Mode.PAKE_STALL)

    private fun cancelAtPeer(mode: OwnedPairingPeerFixture.Mode) {
        val f = fixture(mode)
        f.submit()
        f.awaitPeer()
        f.peer!!.await()
        f.owner.cancel(f.ticket)
        f.finish()
        assertTrue(f.socket!!.isClosed)
        assertFalse(f.operation.workerAlive())
        assertTrue(f.state.drained)
        assertTrue(f.borrowClosed.get() == 1)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
    }

    @Test fun c15CancelAfterPeerInfoPossibleNotRollback() {
        val f = fixture(OwnedPairingPeerFixture.Mode.HOLD_PEER_REPLY)
        f.submit()
        f.awaitPeer()
        f.peer!!.await(f.peer!!.peerInfo)
        f.owner.cancel(f.ticket)
        f.peer!!.proceed.countDown()
        f.finish()
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.RECORD)))
    }

    @Test fun c16OriginalDeadlineAcrossStorageAndProtocol() {
        val f = fixture()
        f.hold(Stage.RECOVERY_READY)
        f.submit()
        f.awaitHeld()
        val deadline = f.operation.workContext!!.deadlineElapsedRealtimeMs
        f.clock.advance(50_000)
        f.release()
        f.finish()
        assertEquals(deadline, f.operation.workContext!!.deadlineElapsedRealtimeMs)
        assertEquals(60_000L, deadline)
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.state.phase)
    }

    @Test fun c17ExpiredBeforeProtocol() {
        val f = fixture()
        f.hold(Stage.RECOVERY_READY)
        f.submit()
        f.awaitHeld()
        f.clock.advance(60_000)
        f.release()
        f.finish()
        assertNull(f.peer)
        assertEquals(0, f.sockets.get())
        assertNull(f.operation.storeReceipt)
        assertTrue(Files.exists(f.record(DirectShellCredentialStorage.ANCHOR)))
    }

    @Test fun c18ExpiryDuringProtocol() {
        val f = fixture(OwnedPairingPeerFixture.Mode.PAKE_STALL)
        f.submit()
        f.awaitPeer()
        f.peer!!.await()
        f.clock.advance(60_000)
        f.finish()
        assertTrue(f.socket!!.isClosed)
        assertTrue(f.state.drained)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
        assertNull(f.operation.storeReceipt)
    }

    @Test fun c19LateVerifiedKnowledgeDoesNotRegainR() {
        val f = fixture()
        f.hold(Stage.PEER_BOUND)
        f.submit()
        f.awaitHeld()
        f.owner.cancel(f.ticket)
        f.release()
        f.finish()
        assertEquals(DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED, f.state.protocolVerification)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
        assertNull(f.operation.storeReceipt)
        assertFalse(f.stages.contains(Stage.RESERVED))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
    }

    @Test fun c20OldGenerationCannotAffectNewOwner() {
        val old = fixture()
        old.hold(Stage.BEFORE_RECOVERY)
        old.submit()
        old.awaitHeld()
        old.owner.cancel(old.ticket)
        old.release()
        old.finish()
        val previous = old.operation
        val previousTicket = old.ticket
        old.request()
        old.submit()
        assertNotSame(previousTicket, old.ticket)
        previous.cancel()
        assertTrue(previous.awaitDrain())
        old.finish()
        assertSame(old.ticket, old.operation.workContext!!.ticket)
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, old.state.phase)
        assertEquals(1, old.fs.links.get())
    }

    @Test fun c21OperationWorkerMustActuallyTerminate() {
        val f = fixture()
        f.hold(Stage.BEFORE_EXIT)
        f.submit()
        f.awaitHeld()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.state.publication)
        assertTrue(f.operation.workerAlive())
        assertFalse(f.operation.awaitDrain(0))
        assertFalse(f.state.drained)
        assertNull(f.owner.request(1))
        f.release()
        f.finish()
        assertTrue(f.state.drained)
    }

    @Test fun c22StorageDrainNotImpliedByNetworkClosure() {
        val f = fixture()
        f.fs.drain = false
        f.submit()
        assertFalse(f.operation.awaitDrain())
        assertFalse(f.operation.workerAlive())
        assertTrue(f.socket!!.isClosed)
        assertFalse(f.state.drained)
        assertTrue(f.fs.hasLiveLease())
        assertNull(f.owner.request(1))
        f.clock.advance(3_000)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.state.phase)
        val competitor = DirectShellCredentialStorage(f.base, { StorageFixture.key }, ModeledDirectorySync())
        try {
            assertEquals(
                DirectShellCredentialStorage.Status.BUSY,
                competitor.begin(otherTicket(), StorageFixture.identity),
            )
        } finally {
            assertTrue(competitor.close())
        }
        // Explicit fixture-owned resource release; never turn an unproven close acknowledgement into D.
        f.fs.drain = true
        assertTrue(f.ownedStore!!.close())
    }

    @Test fun c22KnownPublicationUnconfirmedSyncStillDrained() {
        val f = fixture()
        f.fs.failSync = 5
        f.submit()
        f.finish()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.state.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, f.state.durability)
        assertTrue(f.state.drained)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING, f.inspect())
        assertNull(f.owner.request(1))
    }

    @Test fun c22UnknownPublicationDoesNotBecomeSuccessFromD() {
        val f = fixture()
        f.fs.unknownPublication = true
        f.submit()
        f.finish()
        assertEquals(DirectShellPairingPublication.UNKNOWN, f.state.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, f.state.durability)
        assertTrue(f.state.drained)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.state.phase)
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_CLEANUP_PENDING, f.inspect())
        assertNull(f.owner.request(1))
        assertTrue(Files.exists(f.record(DirectShellCredentialStorage.ANCHOR)))
        assertTrue(Files.exists(f.record(DirectShellCredentialStorage.PENDING)))
    }

    @Test fun c23ControlledRestartHasNoConsentOrAdoption() {
        val f = fixture()
        f.hold(Stage.PREPARED)
        f.submit()
        f.awaitHeld()
        f.owner.cancel(f.ticket)
        f.release()
        f.finish()
        val before = Files.readAllBytes(f.record(DirectShellCredentialStorage.PENDING))
        assertEquals(DirectShellCredentialStorage.Status.PREPARED_RECONCILIATION, f.inspect())
        assertArrayEquals(before, Files.readAllBytes(f.record(DirectShellCredentialStorage.PENDING)))
        assertNull(f.owner.request(1))
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.RECORD)))
    }

    @Test fun c24IndependentLiveProcessExclusion() {
        val f = fixture()
        val initial = f.store()
        assertEquals(
            DirectShellCredentialStorage.Status.RECOVERY_READY,
            initial.begin(otherTicket(), StorageFixture.identity),
        )
        assertTrue(initial.close())
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes = Path.of(
            requireNotNull(DirectShellStorageProcess::class.java.protectionDomain).codeSource.location.toURI(),
        )
        val child = ProcessBuilder(
            java,
            "-cp",
            classes.toString(),
            DirectShellStorageProcess::class.java.name,
            "hold",
            f.directory.toString(),
        ).start()
        val reader = child.inputStream.bufferedReader()
        val line = FutureTask { reader.readLine() }
        val observer = Thread(line, "synthetic-fixture-child-observer").also { it.start() }
        try {
            assertEquals("HELD", line.get(10, TimeUnit.SECONDS))
            f.submit()
            f.finish()
            assertTrue(child.isAlive)
            assertNull(f.peer)
            assertEquals(0, f.sockets.get())
        } finally {
            child.outputStream.write(1)
            child.outputStream.flush()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            reader.close()
            observer.join(3_000)
            assertFalse(observer.isAlive)
            println(
                "COMPOSITION_FIXTURE childPid=${Process::class.java.getMethod("pid").invoke(child)} terminated=true",
            )
        }
    }

    @Test fun c25FullSyntheticRpsdf() {
        val f = fixture()
        f.submit()
        f.finish()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.state.phase)
        assertEquals(DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED, f.state.protocolVerification)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.state.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, f.state.durability)
        assertTrue(f.state.drained)
        assertEquals(1, f.created.get())
        assertEquals(1, f.sockets.get())
        assertEquals(1, f.fs.links.get())
        assertEquals(1, f.borrowClosed.get())
        assertEquals(DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION, f.inspect())
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.ANCHOR)))
        assertFalse(Files.exists(f.record(DirectShellCredentialStorage.PENDING)))
        assertTrue(f.socket!!.isClosed)
        f.fs.assertReleased()
        assertEquals(0, f.clock.pending())
        assertTrue(f.stages.indexOf(Stage.RECOVERY_READY) < f.stages.indexOf(Stage.PEER_BOUND))
        assertTrue(f.stages.indexOf(Stage.PEER_BOUND) < f.stages.indexOf(Stage.PREPARED))
        assertTrue(f.stages.indexOf(Stage.RESERVED) < f.stages.indexOf(Stage.PUBLISHED))
    }

    @Test fun c26NoAuthorityOrPassiveSideEffects() {
        val f = fixture()
        f.submit()
        f.finish()
        assertNull(f.owner.request(1))
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
        assertFalse(f.state.toString().contains(CODE))
        assertFalse(f.state.toString().contains("PRIVATE"))
        assertTrue(Files.list(f.base).use { it.count() } == 1L)
        assertEquals(1, f.sockets.get())
        // The adapter has no Host/Session/provider/NSD ports; compiled/source isolation is checked separately.
    }

    private class Clock : DirectShellPairingClock {
        @Volatile var now = 0L
        private val timers = mutableListOf<Entry>()
        private class Entry(val deadline: Long, var action: (() -> Unit)?)
        val scheduler = DirectShellPairingScheduler { deadline, action ->
            val entry = Entry(deadline, action)
            synchronized(timers) { timers.add(entry) }
            DirectShellPairingTimer {
                synchronized(timers) {
                    entry.action = null
                    timers.remove(entry)
                }
            }
        }
        override fun elapsedRealtimeMs() = now
        fun pending(): Int = synchronized(timers) { timers.size }
        fun advance(amount: Long) {
            now += amount
            val due = synchronized(timers) {
                val entries = timers.filter { it.deadline <= now }
                entries.mapNotNull { entry -> entry.action.also { entry.action = null } }.also {
                    timers.removeAll(entries.toSet())
                }
            }
            due.forEach { it() }
        }
    }

    private class Fixture(val base: Path, val mode: OwnedPairingPeerFixture.Mode) : AutoCloseable {
        val clock = Clock()
        val scheduler = clock.scheduler
        val created = AtomicInteger()
        val sockets = AtomicInteger()
        val borrowClosed = AtomicInteger()
        val stages = java.util.Collections.synchronizedList(mutableListOf<Stage>())
        val fs = FileSystem()
        var environment = DirectShellPairingEnvironment(true, true, 1, 0)
        lateinit var owner: DirectShellPairingCoordinator
        lateinit var operation: DirectShellSyntheticPairingComposition.Operation
        lateinit var ticket: DirectShellPairingTicket

        @Volatile var peer: OwnedPairingPeerFixture? = null

        @Volatile var socket: Socket? = null

        @Volatile var ownedStore: DirectShellCredentialStorage? = null
        private var heldStage: Stage? = null
        private val entered = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        private val peerCreated = CountDownLatch(1)
        val directory: Path get() = base.resolve(DirectShellCredentialStorage.DIRECTORY)
        val state get() = owner.state.value
        fun record(name: String): Path = directory.resolve(name)
        fun outside() = check(!Thread.holdsLock(owner)) { "blocking_under_p1_monitor" }
        fun store() = DirectShellCredentialStorage(base, {
            outside()
            StorageFixture.key
        }, fs)
        fun inspect(): DirectShellCredentialStorage.Status =
            DirectShellCredentialStorage(base, { StorageFixture.key }, ModeledDirectorySync()).inspect().status

        val adapter = DirectShellSyntheticPairingComposition(
            clock,
            scheduler,
            {
                outside()
                store().also { ownedStore = it }
            },
            {
                outside()
                DirectShellSyntheticPairingComposition.IdentityBorrow(StorageFixture.identity) {
                    outside()
                    borrowClosed.incrementAndGet()
                    true
                }
            },
            {
                outside()
                OwnedPairingPeerFixture(mode, SERVER).also {
                    peer = it
                    peerCreated.countDown()
                }.endpoint
            },
            {
                outside()
                sockets.incrementAndGet()
                Socket().also { socket = it }
            },
            { stage ->
                outside()
                check(operation.workerAlive()) { "composition_worker_not_published" }
                stages.add(stage)
                if (stage == heldStage) {
                    entered.countDown()
                    check(proceed.await(3, TimeUnit.SECONDS)) { "composition_fixture_boundary" }
                }
            },
        )

        init {
            fs.checkWorker = ::outside
            owner = DirectShellPairingCoordinator(
                { pairingRequiredUiState(1) }, { environment }, clock, scheduler,
                { ticket, observer ->
                    created.incrementAndGet()
                    adapter.create(ticket, observer).also { operation = it }
                },
                adapter, "synthetic-owner", { "synthetic-attempt" },
            )
        }
        fun request(): DirectShellPairingTicket = requireNotNull(owner.request(1)).also { ticket = it }
        fun submit() {
            if (!::ticket.isInitialized) request()
            assertTrue(owner.submitCode(ticket, CODE.toCharArray()))
        }
        fun hold(stage: Stage) {
            heldStage = stage
        }
        fun awaitHeld() = check(entered.await(3, TimeUnit.SECONDS)) { "composition_fixture_boundary" }
        fun awaitPeer() = check(peerCreated.await(3, TimeUnit.SECONDS)) { "composition_fixture_peer" }
        fun release() {
            proceed.countDown()
        }
        fun finish() {
            assertTrue(operation.awaitDrain())
            assertFalse(operation.workerAlive())
        }
        override fun close() {
            release()
            owner.close()
            if (::operation.isInitialized) {
                operation.cancel()
                operation.awaitDrain()
                assertFalse(operation.workerAlive())
            }
            fs.drain = true
            assertTrue(ownedStore?.close() ?: true)
            fs.assertReleased()
            peer?.close()
        }
    }

    private class FileSystem : ModeledDirectorySync() {
        val calls = AtomicInteger()
        val links = AtomicInteger()
        var checkWorker: () -> Unit = {}
        var failSync = -1

        @Volatile var drain = true
        var unknownPublication = false
        private val leases = mutableListOf<DirectShellCredentialLease>()
        var afterWrite: (Path) -> Unit = {}
        override fun canonicalBase(path: Path): Path {
            checkWorker()
            calls.incrementAndGet()
            return super.canonicalBase(path)
        }
        override fun syncDirectory(path: Path) {
            checkWorker()
            super.syncDirectory(path)
            if (syncs == failSync) error("fixture_sync_unconfirmed")
        }
        override fun writeNew(path: Path, bytes: ByteArray) {
            checkWorker()
            super.writeNew(path, bytes)
            afterWrite(path)
        }
        override fun linkNoReplace(record: Path, prepared: Path) {
            checkWorker()
            links.incrementAndGet()
            super.linkNoReplace(record, prepared)
        }
        override fun publishNoReplace(record: Path, prepared: Path): DirectShellCredentialPublishResult {
            val actual = super.publishNoReplace(record, prepared)
            return if (unknownPublication && actual == DirectShellCredentialPublishResult.PUBLISHED_RETAINED) {
                DirectShellCredentialPublishResult.UNKNOWN_EFFECT
            } else {
                actual
            }
        }
        override fun acquireLease(directory: Path, create: Boolean): DirectShellCredentialLease? =
            super.acquireLease(directory, create)?.also { leases.add(it) }
        fun hasLiveLease() = leases.any { (field(it, "lock") as FileLock).isValid }
        fun assertReleased() {
            leases.forEach { lease ->
                assertFalse((field(lease, "lock") as FileLock).isValid)
                assertFalse((field(lease, "channel") as FileChannel).isOpen)
                val path = field(lease, "path") as Path
                val registry = DirectShellCredentialLease::class.java.getDeclaredField(
                    "owners",
                ).apply { isAccessible = true }
                    .get(null) as Map<*, *>
                assertNull(registry[path])
            }
        }
        private fun field(lease: DirectShellCredentialLease, name: String): Any? =
            DirectShellCredentialLease::class.java.getDeclaredField(name).apply { isAccessible = true }.get(lease)
        override fun resourcesDrained() = drain
    }

    companion object {
        const val CODE = "314159"
        private fun otherTicket() = DirectShellPairingTicket("fixture-other", "fixture-other", 1, 1, 0)
        private val SERVER by lazy {
            val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(8, 6, 7, 5, 3, 0, 9)) }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            val name = X500Name("CN=Synthetic Localhost Peer")
            val cert = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger.TWO, Date(0), Date(4102444800000L), name, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)),
            )
            DirectShellCredentialIdentity(pair.private, cert)
        }
        private fun assertFails(action: () -> Unit) {
            try {
                action()
                fail("expected_closed_rejection")
            } catch (
                _: IllegalStateException,
            ) { } catch (_: IllegalArgumentException) { }
        }
    }
}
