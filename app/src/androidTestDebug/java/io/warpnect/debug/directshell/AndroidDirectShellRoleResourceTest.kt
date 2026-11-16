package io.warpnect.debug.directshell

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Layout
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Owner
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Resource
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Resources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real local Android handles, not a Binder caller-identity or production media test. */
class AndroidDirectShellRoleResourceTest {
    private val generation = 3L
    private val fixtureKey = ByteArray(32) { it.toByte() }

    @Test
    fun pcmLocalParcelDuplicatesThreeDirectionalHandlesAndDrainsOwnedCopies() {
        verifyFixture()
        val owner = Owner()
        val ledger = Resources.Pcm(Layout.Pcm(4096, 8, 256), Resource(), Resource(), Resource())
        val transfer = DirectShellRoleTransfer(owner, generation, ledger, fixtureKey)
        val proof = DirectShellRoleTransfer.proof(ledger.layout, generation, fixtureKey)
        assertNull(transfer.reserve(2000, owner, generation + 1, ledger.layout, proof))
        assertNull(transfer.reserve(10000, owner, generation, ledger.layout, proof))
        val grant = checkNotNull(transfer.reserve(2000, owner, generation, ledger.layout, proof))
        assertFalse(transfer.handoff(DirectShellRoleTransfer.Grant()))
        SharedMemory.create("warpnect-test-pcm", ledger.layout.bytes).use { memory ->
            val notify = ParcelFileDescriptor.createPipe()
            val ack = ParcelFileDescriptor.createPipe()
            try {
                duplicate(memory, SharedMemory.CREATOR).use { backing ->
                    duplicate(notify[1], ParcelFileDescriptor.CREATOR).use { send ->
                        duplicate(ack[0], ParcelFileDescriptor.CREATOR).use { receive ->
                            assertTrue(transfer.handoff(grant))
                            demonstratePcm(memory, backing, notify, ack, send, receive)
                            val drain = checkNotNull(
                                DirectShellRoleDrain.pcm(
                                    transfer,
                                    owner,
                                    ledger,
                                    DirectShellRoleDrain.Handle(ledger.backing, backing::close) { closed(backing) },
                                    descriptor(ledger.notifyWrite, send),
                                    descriptor(ledger.ackRead, receive),
                                ),
                            )
                            assertTrue(drain.closeOwned())
                            assertFalse(drain.observe(generation + 1, workerExited = true))
                            assertFalse(drain.observe(generation, workerExited = false))
                            assertTrue(drain.observe(generation, workerExited = true))
                            assertTrue(notify.all { it.fileDescriptor.valid() })
                            assertTrue(ack.all { it.fileDescriptor.valid() })
                            val remaining = memory.mapReadOnly()
                            SharedMemory.unmap(remaining)
                        }
                    }
                }
            } finally {
                (notify + ack).forEach(ParcelFileDescriptor::close)
            }
        }
    }

    @Test
    fun videoLocalSurfaceParcelDoesNotReleaseUntilWorkerExitAndExactGeneration() {
        verifyFixture()
        val owner = Owner()
        val ledger = Resources.Video(Layout.Video(16, 16), Resource())
        val transfer = DirectShellRoleTransfer(owner, generation, ledger, fixtureKey)
        val proof = DirectShellRoleTransfer.proof(ledger.layout, generation, fixtureKey)
        val grant = checkNotNull(transfer.reserve(2000, owner, generation, ledger.layout, proof))
        ImageReader.newInstance(16, 16, ImageFormat.RGB_565, 1).use { reader ->
            val duplicate = duplicate(reader.surface, Surface.CREATOR)
            try {
                assertTrue(transfer.handoff(grant))
                assertFalse(transfer.handoff(grant))
                assertTrue(duplicate.isValid)
                val entered = CountDownLatch(1)
                val stop = CountDownLatch(1)
                val worker = Thread({
                    entered.countDown()
                    stop.await()
                }, "fixture-surface-owner").apply { start() }
                val drain = checkNotNull(
                    DirectShellRoleDrain.video(
                        transfer,
                        owner,
                        ledger,
                        DirectShellRoleDrain.Handle(ledger.surface, duplicate::release) { !duplicate.isValid },
                    ),
                )
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    assertTrue(drain.closeOwned())
                    assertFalse(drain.observe(generation, workerExited = !worker.isAlive))
                    assertTrue(reader.surface.isValid)
                } finally {
                    stop.countDown()
                    worker.join(2_000)
                }
                assertFalse(worker.isAlive)
                assertTrue(drain.observe(generation, workerExited = !worker.isAlive))
            } finally {
                if (duplicate.isValid) duplicate.release()
            }
        }
    }

    private fun demonstratePcm(
        memory: SharedMemory,
        backing: SharedMemory,
        notify: Array<ParcelFileDescriptor>,
        ack: Array<ParcelFileDescriptor>,
        send: ParcelFileDescriptor,
        receive: ParcelFileDescriptor,
    ) {
        val write = memory.mapReadWrite()
        try {
            write.putInt(0, 109)
            val read = backing.mapReadOnly()
            try {
                assertEquals(109, read.getInt(0))
            } finally {
                SharedMemory.unmap(read)
            }
        } finally {
            SharedMemory.unmap(write)
        }
        pipe(send, notify[0], 19)
        pipe(ack[1], receive, 29)
    }

    private fun pipe(write: ParcelFileDescriptor, read: ParcelFileDescriptor, value: Byte) {
        assertEquals(1, Os.write(write.fileDescriptor, byteArrayOf(value), 0, 1))
        val ready = StructPollfd().apply {
            fd = read.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        assertEquals(1, Os.poll(arrayOf(ready), 1_000))
        val result = ByteArray(1)
        assertEquals(1, Os.read(read.fileDescriptor, result, 0, 1))
        assertEquals(value, result.single())
    }

    private fun descriptor(resource: Resource, fd: ParcelFileDescriptor): DirectShellRoleDrain.Handle {
        val witness = fd.fileDescriptor
        return DirectShellRoleDrain.Handle(resource, fd::close) { !witness.valid() }
    }

    private fun closed(memory: SharedMemory): Boolean = try {
        SharedMemory.unmap(memory.mapReadOnly())
        false
    } catch (_: IllegalStateException) {
        true
    }

    private fun <T : Parcelable> duplicate(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun verifyFixture() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.warpnect.privatesetupgate")
    }
}
