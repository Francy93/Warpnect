package io.warpnect.platform.privileged.directshell.pairing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.debug.storage.PairingStorageFixtureProtocol as Wire
import io.warpnect.debug.storage.PairingStorageFixtureService
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import java.io.File
import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real same-UID Binder process, production FileChannel lease/inspection/JNI. No real pairing or Host. */
class AndroidMultiprocessStorageInstrumentationTest {
    private lateinit var f: AndroidPairingStorageFixture
    private lateinit var fs: AndroidDirectShellCredentialFileSystem
    private lateinit var remote: Remote
    private var held: DirectShellCredentialLease? = null
    private var killed = false
    private var inode = 0L

    @Before fun setup() {
        f = AndroidPairingStorageFixture(multiprocess = true)
        fs = AndroidDirectShellCredentialFileSystem(f.base)
        fs.prepareNamespace(f.base)
        held = fs.acquireLease(f.directory, true)
        assertTrue(held!!.close())
        held = null
        inode = Os.lstat(f.directory.resolve(DirectShellCredentialStorage.LOCK).toString()).st_ino
        remote = Remote()
        assertEquals("CONFIGURED", remote.call(Wire.CONFIGURE) { writeString(f.token) }.getString("result"))
        verifyIdentity()
    }

    @After fun cleanup() {
        if (::remote.isInitialized) {
            if (!killed) {
                assertEquals("DRAINED", remote.call(Wire.RELEASE).getString("result"))
                val state = remote.call(Wire.DESCRIPTORS)
                assertEquals(0, state.getInt("owned_fds"))
                assertFalse(state.getBoolean("lease"))
                assertFalse(state.getBoolean("worker"))
                assertTrue(state.getBoolean("os_drained"))
            }
            remote.close()
        }
        assertTrue(held?.close() != false)
        held = null
        if (::f.isInitialized) {
            assertEquals(inode, Os.lstat(f.directory.resolve(DirectShellCredentialStorage.LOCK).toString()).st_ino)
            f.emit("MULTIPROCESS cleanup=verified remote_drained_or_dead=true stable_inode=$inode")
            f.close()
        }
    }

    private fun verifyIdentity() {
        val b = remote.call(Wire.IDENTITY)
        val domain = File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
        assertEquals(Process.myUid(), b.getInt("uid"))
        assertNotEquals(Process.myPid(), b.getInt("pid"))
        assertEquals(remote.expectedProcess, b.getString("process"))
        assertEquals(domain, b.getString("domain"))
        assertTrue(domain.startsWith("u:r:untrusted_app:"))
        assertEquals(f.base.toString(), b.getString("namespace"))
        val lock = Os.lstat(f.directory.resolve(DirectShellCredentialStorage.LOCK).toString())
        assertEquals(lock.st_ino, b.getLong("inode"))
        assertEquals(lock.st_dev, b.getLong("device"))
        f.emit(
            "MULTIPROCESS A=${Process.myPid()} B=${b.getInt("pid")} uid=${b.getInt("uid")} domain=$domain inode=$inode",
        )
    }

    @Test fun m01IndependentIdentityAndM12BoundedCleanup() {
        verifyIdentity()
        assertEquals(0, remote.call(Wire.DESCRIPTORS).getInt("owned_fds"))
    }

    @Test fun m02OwnerAExcludesBWithoutWriterOrPublication() {
        held = requireNotNull(fs.acquireLease(f.directory, false))
        val before = files()
        assertEquals("BUSY", remote.call(Wire.ACQUIRE).getString("result"))
        assertEquals("BUSY", remote.call(Wire.INSPECT).getString("result"))
        assertEquals(before, files())
        assertTrue(held!!.valid())
    }

    @Test fun m03OwnerBExcludesAInReverseDirection() {
        assertEquals("OWNED", remote.call(Wire.ACQUIRE).getString("result"))
        assertNull(fs.acquireLease(f.directory, false))
        assertEquals(DirectShellCredentialStorage.Status.BUSY, f.store().inspect().status)
        assertEquals(setOf(DirectShellCredentialStorage.LOCK), files())
    }

    @Test fun m04ConcurrentAcquireHasExactlyOneWinner() {
        val pipe = ParcelFileDescriptor.createPipe()
        val a = AtomicReference<DirectShellCredentialLease?>()
        val failure = AtomicReference<Throwable?>()
        val ready = CountDownLatch(1)
        val thread = Thread {
            try {
                val poll = StructPollfd().apply {
                    fd = pipe[0].fileDescriptor
                    events = OsConstants.POLLIN.toShort()
                }
                ready.countDown()
                check(Os.poll(arrayOf(poll), Wire.DEADLINE_MS.toInt()) == 1)
                check(Os.read(pipe[0].fileDescriptor, ByteArray(1), 0, 1) == 1)
                a.set(fs.acquireLease(f.directory, false))
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
        try {
            assertEquals("ARMED", remote.call(Wire.ARM) { pipe[0].writeToParcel(this, 0) }.getString("result"))
            thread.start()
            assertTrue(ready.await(Wire.DEADLINE_MS, TimeUnit.MILLISECONDS))
            assertEquals(2, Os.write(pipe[1].fileDescriptor, byteArrayOf(1, 1), 0, 2))
            thread.join(Wire.DEADLINE_MS)
            assertFalse(thread.isAlive)
            failure.get()?.let { throw it }
            held = a.get()
            val b = remote.call(Wire.RESULT).getString("result")
            assertTrue(b in setOf("OWNED", "BUSY"))
            assertEquals(1, (if (held != null) 1 else 0) + (if (b == "OWNED") 1 else 0))
            f.emit("MULTIPROCESS barrier=pipe A_owned=${held != null} B=$b winners=1 attempts_per_process=1")
        } finally {
            pipe.forEach { it.close() }
            thread.join(Wire.DEADLINE_MS)
            if (held == null) held = a.get()
            check(!thread.isAlive)
        }
    }

    @Test fun m05CleanDrainAndReacquisitionKeepStableInode() {
        held = requireNotNull(fs.acquireLease(f.directory, false))
        assertEquals("BUSY", remote.call(Wire.ACQUIRE).getString("result"))
        assertTrue(held!!.close())
        held = null
        assertEquals("OWNED", remote.call(Wire.ACQUIRE).getString("result"))
        assertEquals("DRAINED", remote.call(Wire.RELEASE).getString("result"))
        held = requireNotNull(fs.acquireLease(f.directory, false))
        assertTrue(held!!.valid())
    }

    @Test fun m06CommittedRecordBlocksReplacementAcrossProcesses() {
        f.provision()
        val store = f.store()
        val c = f.begin(store)
        val receipt = store.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
        assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, receipt.durability)
        assertTrue(store.close())
        val bytes = Files.readAllBytes(f.record)
        assertEquals("COMMITTED_RECONCILIATION", remote.call(Wire.INSPECT).getString("result"))
        assertEquals("OWNED", remote.call(Wire.ACQUIRE).getString("result"))
        assertEquals("DRAINED", remote.call(Wire.RELEASE).getString("result"))
        assertEquals(
            DirectShellCredentialStorage.Status.COMMITTED_RECONCILIATION,
            f.store().begin(f.ticket, AndroidPairingStorageFixture.identity),
        )
        assertArrayEquals(bytes, Files.readAllBytes(f.record))
    }

    @Test fun m07IncompleteEnrollmentRequiresReadOnlyReconciliation() {
        f.provision()
        val store = f.store()
        f.begin(store)
        assertTrue(store.close())
        val before = recordHashes()
        assertEquals("PREPARED_RECONCILIATION", remote.call(Wire.INSPECT).getString("result"))
        assertEquals(before, recordHashes())
        assertFalse(Files.exists(f.record))
    }

    @Test fun m08ProcessDeathReleasesOsLockButNotArtifacts() {
        f.provision()
        val store = f.store()
        assertEquals(
            DirectShellCredentialStorage.Status.RECOVERY_READY,
            store.begin(f.ticket, AndroidPairingStorageFixture.identity),
        )
        assertTrue(store.close())
        val before = recordHashes()
        assertEquals("OWNED", remote.call(Wire.ACQUIRE).getString("result"))
        assertNull(fs.acquireLease(f.directory, false))
        val b = remote.call(Wire.IDENTITY)
        assertEquals(Process.myUid(), b.getInt("uid"))
        assertEquals(remote.expectedProcess, b.getString("process"))
        assertNotEquals(Process.myPid(), b.getInt("pid"))
        KernelReleaseBarrier(f.directory.resolve(DirectShellCredentialStorage.LOCK)).use { barrier ->
            remote.terminate()
            killed = true
            barrier.awaitDrain()
        }
        held = requireNotNull(fs.acquireLease(f.directory, false))
        assertTrue(held!!.close())
        held = null
        assertEquals(DirectShellCredentialStorage.Status.RECOVERY_RECONCILIATION, f.store().inspect().status)
        assertEquals(before, recordHashes())
        assertEquals(
            DirectShellCredentialStorage.Status.RECOVERY_RECONCILIATION,
            f.store().begin(f.ticket, AndroidPairingStorageFixture.identity),
        )
        f.emit("MULTIPROCESS old_B=${b.getInt("pid")} dead=true os_lock_reacquired=true artifacts_retained=true")
    }

    @Test fun m09LostPublicationReceiptCannotAuthorizeReplacement() {
        f.provision()
        val native = AndroidCredentialOs()
        val fault = object : DirectShellCredentialOs by native {
            override fun publish(directory: FileDescriptor): DirectShellCredentialPublishResult {
                assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, native.publish(directory))
                throw java.io.IOException("fixture_lost_ack")
            }
        }
        val store = f.store(AndroidDirectShellCredentialFileSystem(f.base, fault))
        val c = f.begin(store)
        val receipt = store.publish(c, DirectShellPairingCommitReservation.issue(c.prepared))
        assertEquals(DirectShellPairingPublication.UNKNOWN, receipt.publication)
        assertEquals(DirectShellPairingDurability.UNCONFIRMED, receipt.durability)
        assertEquals("BUSY", remote.call(Wire.INSPECT).getString("result"))
        assertTrue(store.close())
        val before = recordHashes()
        assertEquals("COMMITTED_CLEANUP_PENDING", remote.call(Wire.INSPECT).getString("result"))
        assertEquals(before, recordHashes())
        assertEquals(DirectShellPairingPublication.UNKNOWN, receipt.publication)
    }

    @Test fun m10HeldDescriptorPreventsFalseDrainAndSecondOwner() {
        f.provision()
        val native = AndroidCredentialOs()
        var descriptor: FileDescriptor? = null
        val fault = object : DirectShellCredentialOs by native {
            override fun resourcesDrained(): Boolean = native.resourcesDrained() && descriptor == null
        }
        val store = f.store(AndroidDirectShellCredentialFileSystem(f.base, fault))
        LifecycleProbe(f, store).use { probe ->
            val receipt = probe.publish()
            assertEquals(DirectShellPairingPublication.PUBLISHED, receipt.publication)
            descriptor = Os.open(f.directory.toString(), OsConstants.O_RDONLY, 0)
            try {
                probe.receive(receipt)
                probe.advance(DirectShellPairingCoordinator.CLEANUP_MS)
                assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, probe.owner.state.value.phase)
                assertNull(probe.owner.request(1))
                assertFalse(store.close())
                assertEquals("BUSY", remote.call(Wire.ACQUIRE).getString("result"))
                assertEquals("BUSY", remote.call(Wire.INSPECT).getString("result"))
                assertTrue(Files.exists(f.record))
            } finally {
                Os.close(descriptor)
                descriptor = null
            }
            assertTrue(store.close())
            probe.drain()
            assertEquals("COMMITTED_RECONCILIATION", remote.call(Wire.INSPECT).getString("result"))
        }
    }

    @Test fun m11FixtureMessagesNeverRestoreOrCreateP1Consent() {
        f.provision()
        LifecycleProbe(f, f.store()).use { probe ->
            val before = probe.owner.state.value
            assertEquals(DirectShellPairingPhase.IDLE, before.phase)
            assertEquals("EMPTY", remote.call(Wire.INSPECT).getString("result"))
            assertEquals(before, probe.owner.state.value)
            val ticket = requireNotNull(probe.owner.request(1))
            probe.advance(DirectShellPairingCoordinator.CONSENT_MS)
            assertEquals(DirectShellPairingPhase.EXPIRED, probe.owner.state.value.phase)
            val expired = probe.owner.state.value
            assertEquals("OWNED", remote.call(Wire.ACQUIRE).getString("result"))
            assertEquals("DRAINED", remote.call(Wire.RELEASE).getString("result"))
            assertEquals(expired, probe.owner.state.value)
            assertFalse(probe.owner.submitCode(ticket, "000000".toCharArray()))
            assertEquals(0, probe.backendStarts)
            assertEquals(0, probe.commitRequests)
        }
    }

    private fun files(): Set<String> = Files.newDirectoryStream(f.directory).use { stream ->
        stream.map { it.fileName.toString() }.toSet()
    }

    private fun recordHashes(): Map<String, String> = files().associateWith {
        DirectShellCredentialCodec.digest(
            Files.readAllBytes(f.directory.resolve(it)),
        ).joinToString("") { b -> "%02x".format(b) }
    }

    /** Measurement only: kernel grant/release is the barrier, not Binder death or repeated tryLock. */
    private class KernelReleaseBarrier(path: Path) : AutoCloseable {
        private val ready = CountDownLatch(1)
        private val drained = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val observer = Thread({
            try {
                FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    ready.countDown()
                    channel.lock().use { lock -> check(lock.isValid) }
                }
            } catch (problem: Throwable) {
                failure.set(problem)
            } finally {
                ready.countDown()
                drained.countDown()
            }
        }, "fixture-kernel-release-observer").apply { start() }
        init {
            check(ready.await(Wire.DEADLINE_MS, TimeUnit.MILLISECONDS))
            failure.get()?.let { throw it }
        }
        fun awaitDrain() {
            check(drained.await(Wire.DEADLINE_MS, TimeUnit.MILLISECONDS))
            observer.join(Wire.DEADLINE_MS)
            check(!observer.isAlive)
            failure.get()?.let { throw it }
        }
        override fun close() {
            if (observer.isAlive) observer.interrupt()
            observer.join(Wire.DEADLINE_MS)
            check(!observer.isAlive && drained.count == 0L)
            failure.get()?.let { throw it }
        }
    }

    /** Actual P1; protocol/timers are controlled, not an ADB backend or a second runtime owner. */
    private class LifecycleProbe(
        val fixture: AndroidPairingStorageFixture,
        val store: DirectShellCredentialStorage,
    ) : AutoCloseable {
        private var now = 1L
        private var environment = DirectShellPairingEnvironment(true, true, 1, 0)
        private val timers = mutableListOf<Timer>()
        var backendStarts = 0
        var commitRequests = 0
        private lateinit var observer: DirectShellPairingObserver
        private lateinit var control: DirectShellPairingCommitControl
        private lateinit var reservation: DirectShellPairingCommitReservation
        private var drained: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        val owner = DirectShellPairingCoordinator(
            readiness = { readiness() },
            environment = { environment },
            clock = { now },
            scheduler = { deadline, action -> Timer(deadline, action).also(timers::add) },
            backend = { _, callback ->
                backendStarts++
                observer = callback
                object : DirectShellPairingOperation {
                    override fun start(code: DirectShellPairingCode) = Unit
                    override fun cancel() = Unit
                    override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                        this@LifecycleProbe.drained = drained
                    }
                }
            },
            committer = { _, _, port ->
                commitRequests++
                control = port
            },
            ownerEpoch = "multiprocess-owner",
            nextNonce = { "multiprocess-attempt" },
        )
        fun publish(): DirectShellPairingStoreReceipt {
            val ticket = requireNotNull(owner.request(1))
            check(owner.submitCode(ticket, "000000".toCharArray()))
            val started = store.begin(ticket, AndroidPairingStorageFixture.identity)
            check(started == DirectShellCredentialStorage.Status.RECOVERY_READY)
            check(observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
            val authorization = DirectShellStagedAuthorization {}
            val candidate = requireNotNull(store.prepare(fixture.evidence(ticket), authorization).candidate)
            observer.prepared(authorization)
            reservation = requireNotNull(control.reserve(candidate.prepared))
            check(!Thread.holdsLock(owner))
            return store.publish(candidate, reservation)
        }
        fun receive(receipt: DirectShellPairingStoreReceipt) {
            environment = environment.copy(credentialRevision = 1)
            control.completed(reservation, receipt)
        }
        fun advance(amount: Long) {
            now += amount
            timers.toList().filter { !it.cancelled && it.deadline <= now }.forEach {
                it.cancelled = true
                it.action()
            }
        }
        fun drain() {
            requireNotNull(drained).invoke(
                DirectShellPairingDrainReceipt(
                    DirectShellPairingExternalEffect.AUTHENTICATED,
                    DirectShellPairingRecovery.COMMITTED_RECORD_PRESENT,
                ),
            )
        }
        override fun close() = owner.close()
        private class Timer(val deadline: Long, val action: () -> Unit) : DirectShellPairingTimer {
            var cancelled = false
            override fun cancel() {
                cancelled = true
            }
        }
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

    private class Remote : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedProcess = Wire.processName(context.packageName)
        private val connected = CountDownLatch(1)
        private lateinit var binder: IBinder
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        init {
            check(
                context.bindService(
                    Intent(context, PairingStorageFixtureService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
            check(connected.await(Wire.DEADLINE_MS, TimeUnit.MILLISECONDS))
        }
        fun call(operation: Int, fill: Parcel.() -> Unit = {}): Bundle {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(Wire.DESCRIPTOR)
                request.fill()
                check(binder.transact(operation, request, reply, 0))
                reply.readException()
                val result = requireNotNull(reply.readBundle(javaClass.classLoader))
                check(result.getString("result") != "FIXTURE_ERROR")
                return result
            } finally {
                request.recycle()
                reply.recycle()
            }
        }
        fun terminate() {
            val death = CountDownLatch(1)
            binder.linkToDeath({ death.countDown() }, 0)
            try {
                call(Wire.EXIT)
            } catch (_: DeadObjectException) {
                // Death notification, not a successful normal-close receipt, is authoritative here.
            }
            check(death.await(Wire.DEADLINE_MS, TimeUnit.MILLISECONDS))
        }
        override fun close() {
            context.unbindService(connection)
        }
    }
}
