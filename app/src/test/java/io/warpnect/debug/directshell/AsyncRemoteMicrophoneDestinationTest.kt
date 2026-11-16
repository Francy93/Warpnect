package io.warpnect.debug.directshell

import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncRemoteMicrophoneDestinationTest {
    private class Control : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
        override fun shutdown() {
            stopped = true
        }
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private class Input : RemoteMicrophonePcmDestination {
        var starts = 0
        var frames = 0
        override fun start(): Boolean {
            starts++
            return true
        }
        override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) {
            this.frames += frames
        }
    }

    @Test fun ingressNeverRunsPolicyAcquisitionOrActivation() {
        val control = Control()
        val input = Input()
        var acquired = 0
        val destination = AsyncRemoteMicrophoneDestination({
            acquired++
            input to AutoCloseable {}
        }, control)
        assertTrue(destination.start())
        destination.submit(ByteBuffer.allocate(480), 0, 240)
        assertEquals(0, acquired)
        assertEquals(0, input.starts)
        control.drain()
        destination.submit(ByteBuffer.allocate(480), 0, 240)
        assertEquals(1, acquired)
        assertEquals(1, input.starts)
        assertEquals(240, input.frames)
        destination.close()
        control.drain()
    }

    @Test fun closeBeforeAcquisitionReleasesLateOwnerWithoutActivation() {
        val control = Control()
        val input = Input()
        var releases = 0
        val destination = AsyncRemoteMicrophoneDestination({ input to AutoCloseable { releases++ } }, control)
        destination.start()
        destination.close()
        assertFalse(destination.start())
        control.drain()
        assertEquals(0, input.starts)
        assertEquals(1, releases)
    }

    @Test fun stopRejectsLatePcmAndReleasesOnceOffIngress() {
        val control = Control()
        val input = Input()
        var releases = 0
        val destination = AsyncRemoteMicrophoneDestination({ input to AutoCloseable { releases++ } }, control)
        destination.start()
        control.drain()
        destination.close()
        destination.close()
        destination.submit(ByteBuffer.allocate(480), 0, 240)
        assertEquals(0, releases)
        assertEquals(0, input.frames)
        control.drain()
        assertEquals(1, releases)
    }

    @Test fun permissionUnavailableDoesNotInventActiveDestination() {
        val control = Control()
        val destination = AsyncRemoteMicrophoneDestination({ null }, control)
        assertTrue(destination.start())
        control.drain()
        destination.submit(ByteBuffer.allocate(480), 0, 240)
        destination.close()
        control.drain()
        assertTrue(control.isTerminated)
    }

    @Test fun controlWrapperPreservesOriginalFramePositionAndAdmissionClock() {
        val control = Control()
        var observed: Pair<Long, Long>? = null
        val input = object : RemoteMicrophonePcmDestination {
            override fun start() = true
            override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) = error("untimed handoff")
            override fun submitTimed(
                buffer: ByteBuffer,
                offset: Int,
                frames: Int,
                firstFramePosition: Long,
                admittedNs: Long,
            ) {
                observed = firstFramePosition to admittedNs
            }
        }
        val destination = AsyncRemoteMicrophoneDestination({ input to AutoCloseable {} }, control)
        destination.start()
        control.drain()
        destination.submitTimed(ByteBuffer.allocate(480), 0, 240, 123_456, 7_000_000_000)
        assertEquals(123_456L to 7_000_000_000L, observed)
        destination.close()
        observed = null
        destination.submitTimed(ByteBuffer.allocate(480), 0, 240, 123_696, 7_005_000_000)
        assertEquals(null, observed)
        control.drain()
    }
}
