package io.warpnect.debug.storage

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.warpnect.platform.privileged.directshell.pairing.AndroidDirectShellCredentialFileSystem
import io.warpnect.platform.privileged.directshell.pairing.AndroidDirectShellCredentialKeyStore
import io.warpnect.platform.privileged.directshell.pairing.DirectShellCredentialLease
import io.warpnect.platform.privileged.directshell.pairing.DirectShellCredentialStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Non-exported DEBUG service. One fixture owner; no Host, coordinator, key provisioning or publication API. */
class PairingStorageFixtureService : Service() {
    private var root: Path? = null
    private var token: String? = null
    private var fs: AndroidDirectShellCredentialFileSystem? = null
    private var lease: DirectShellCredentialLease? = null
    private var worker: Thread? = null
    private var finished: CountDownLatch? = null

    @Volatile private var result = "NOT_ARMED"
    private val gate = Any()

    override fun onBind(intent: Intent?): IBinder? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        check(processName() == PairingStorageFixtureProtocol.processName(packageName))
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (!PairingStorageFixtureProtocol.allowed(code)) return super.onTransact(code, data, reply, flags)
                check(getCallingUid() == Process.myUid())
                data.enforceInterface(PairingStorageFixtureProtocol.DESCRIPTOR)
                val output = try {
                    execute(code, data)
                } catch (_: Exception) {
                    Bundle().apply { putString("result", "FIXTURE_ERROR") }
                }
                reply!!.writeNoException()
                reply.writeBundle(output)
                if (code == PairingStorageFixtureProtocol.EXIT && output.getString("result") == "EXITING") {
                    // The test has already verified PID, UID, process name and owns all fixture artifacts.
                    Process.killProcess(Process.myPid())
                }
                return true
            }
        }
    }

    private fun execute(code: Int, data: Parcel): Bundle {
        if (code == PairingStorageFixtureProtocol.RESULT) {
            check(finished?.await(PairingStorageFixtureProtocol.DEADLINE_MS, TimeUnit.MILLISECONDS) == true)
            worker!!.join(PairingStorageFixtureProtocol.DEADLINE_MS)
            check(worker?.isAlive == false)
            worker = null
            return evidence(result)
        }
        return synchronized(gate) {
            when (code) {
                PairingStorageFixtureProtocol.CONFIGURE -> {
                    check(root == null && lease == null && worker == null)
                    val value = data.readString()
                    check(PairingStorageFixtureProtocol.validToken(value))
                    val owner = noBackupFilesDir.toPath().toRealPath()
                    val candidate = owner.resolve(PairingStorageFixtureProtocol.PREFIX + value)
                    check(Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
                    check(candidate.toRealPath() == candidate && candidate.parent == owner)
                    val base = candidate.resolve("no_backup")
                    check(base.toRealPath() == base)
                    check(!Files.exists(owner.resolve(DirectShellCredentialStorage.DIRECTORY)))
                    token = value
                    root = candidate
                    fs = AndroidDirectShellCredentialFileSystem(base)
                    evidence("CONFIGURED")
                }
                PairingStorageFixtureProtocol.IDENTITY -> evidence("IDENTITY")
                PairingStorageFixtureProtocol.ACQUIRE -> {
                    check(worker?.isAlive != true && lease == null)
                    acquire()
                    evidence(result)
                }
                PairingStorageFixtureProtocol.RELEASE -> {
                    check(worker?.isAlive != true)
                    check(fs!!.resourcesDrained())
                    check(lease?.close() != false)
                    lease = null
                    evidence("DRAINED")
                }
                PairingStorageFixtureProtocol.INSPECT -> {
                    check(worker?.isAlive != true && lease == null)
                    val store = DirectShellCredentialStorage(
                        root!!.resolve("no_backup"),
                        AndroidDirectShellCredentialKeyStore(PairingStorageFixtureProtocol.ALIAS + token),
                        fs!!,
                    )
                    val inspected = store.inspect().status.name
                    check(store.close())
                    evidence(inspected)
                }
                PairingStorageFixtureProtocol.ARM -> {
                    check(worker == null && lease == null)
                    val pipe = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val ready = CountDownLatch(1)
                    val done = CountDownLatch(1)
                    finished = done
                    worker = Thread({
                        try {
                            pipe.use {
                                val poll = StructPollfd().apply {
                                    fd = it.fileDescriptor
                                    events = OsConstants.POLLIN.toShort()
                                }
                                ready.countDown()
                                check(Os.poll(arrayOf(poll), PairingStorageFixtureProtocol.DEADLINE_MS.toInt()) == 1)
                                check(Os.read(it.fileDescriptor, ByteArray(1), 0, 1) == 1)
                                synchronized(gate) { acquire() }
                            }
                        } catch (_: Exception) {
                            result = "BARRIER_FAILED"
                        } finally {
                            done.countDown()
                        }
                    }, "pairing-fixture-barrier").also { it.start() }
                    check(ready.await(PairingStorageFixtureProtocol.DEADLINE_MS, TimeUnit.MILLISECONDS))
                    evidence("ARMED")
                }
                PairingStorageFixtureProtocol.DESCRIPTORS -> evidence("DESCRIPTORS")
                PairingStorageFixtureProtocol.EXIT -> evidence("EXITING")
                else -> error("fixture_operation")
            }
        }
    }

    private fun acquire() {
        val directory = root!!.resolve("no_backup").resolve(DirectShellCredentialStorage.DIRECTORY)
        lease = fs!!.acquireLease(directory, false)
        result = if (lease == null) "BUSY" else "OWNED"
    }

    private fun evidence(status: String): Bundle = Bundle().apply {
        val path = root
        putString("result", status)
        putInt("pid", Process.myPid())
        putInt("uid", Process.myUid())
        putString("process", processName())
        putString("domain", File("/proc/self/attr/current").readText().trim().trimEnd('\u0000'))
        putString("namespace", path?.resolve("no_backup")?.toString())
        if (path != null) {
            val lock = path.resolve("no_backup").resolve(DirectShellCredentialStorage.DIRECTORY)
                .resolve(DirectShellCredentialStorage.LOCK)
            val stat = Os.lstat(lock.toString())
            putLong("device", stat.st_dev)
            putLong("inode", stat.st_ino)
            val descriptors = requireNotNull(File("/proc/self/fd").listFiles())
            check(descriptors.size <= 512)
            putInt(
                "owned_fds",
                descriptors.count {
                    val link = try {
                        Os.readlink(it.path)
                    } catch (_: android.system.ErrnoException) {
                        ""
                    }
                    link == path.toString() || link.startsWith("$path/")
                },
            )
        }
        putBoolean("lease", lease?.valid() == true)
        putBoolean("worker", worker?.isAlive == true)
        putBoolean("os_drained", fs?.resourcesDrained() != false)
    }

    private fun processName(): String = File("/proc/self/cmdline").readText().trimEnd('\u0000')

    override fun onDestroy() {
        check(worker?.isAlive != true) { "fixture_worker_unresolved" }
        check(fs?.resourcesDrained() != false && lease?.close() != false) { "fixture_drain_unresolved" }
        lease = null
        super.onDestroy()
    }
}
