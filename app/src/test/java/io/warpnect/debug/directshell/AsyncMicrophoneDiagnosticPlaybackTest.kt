package io.warpnect.debug.directshell

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackResult
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioPlaybackState
import io.warpnect.audio.playback.AudioPresentationTimestampResult
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import io.warpnect.audio.playback.MicrophonePcmRingAdmission
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncMicrophoneDiagnosticPlaybackTest {
    @Test fun sdkStartConfirmationDoesNotBlockInputOrQueueDebugPcm() {
        val executor = Initializer()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val speaker = Speaker().apply {
            beforeStart = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val admission = object : AudioPlaybackController by speaker, MicrophonePcmRingAdmission {
            override fun admitMicrophonePcm(
                buffer: ByteBuffer,
                offset: Int,
                sizeBytes: Int,
                frameCount: Int,
                metadata: DecodedPcmMetadata,
            ) = speaker.submitPcm(buffer, offset, sizeBytes, frameCount, metadata)
        }
        val async = AsyncMicrophoneDiagnosticPlayback(admission, executor)
        var inputFrames = 0
        val input = object : RemoteMicrophonePcmDestination {
            override fun start() = true
            override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) {
                inputFrames += frames
            }
        }
        val playback = DirectShellMicrophonePlayback(async) { input }
        playback.prepare(config(1))
        playback.start()
        val owner = Thread { executor.runAll() }
        owner.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            playback.submitPcm(ByteBuffer.allocateDirect(480), 0, 480, 240, pcm(1))
            assertEquals(240, inputFrames)
            assertEquals(0, speaker.frames)
        } finally {
            release.countDown()
            owner.join(2000)
        }
        assertEquals(0, speaker.frames)
        playback.submitPcm(ByteBuffer.allocateDirect(480), 0, 480, 240, pcm(1, 240))
        assertEquals(480, inputFrames)
        assertEquals(240, speaker.frames)
        playback.close()
        executor.runAll()
    }

    @Test fun nativeRingAdmissionDoesNotQueueBehindTheSdkOwner() {
        val executor = Initializer()
        val speaker = Speaker()
        val admission = object : AudioPlaybackController by speaker, MicrophonePcmRingAdmission {
            override fun admitMicrophonePcm(
                buffer: ByteBuffer,
                offset: Int,
                sizeBytes: Int,
                frameCount: Int,
                metadata: DecodedPcmMetadata,
            ) = speaker.submitPcm(buffer, offset, sizeBytes, frameCount, metadata)
        }
        val async = AsyncMicrophoneDiagnosticPlayback(admission, executor)
        async.prepare(config(1))
        async.start()
        executor.runAll()
        // A runnable-but-descheduled SDK owner must not impose the old four-slot limit.
        repeat(10) {
            assertEquals(
                AudioPlaybackError.None,
                async.submitPcm(ByteBuffer.allocateDirect(480), 0, 480, 240, pcm(1)).error,
            )
        }
        assertEquals(2400, speaker.frames)
        assertTrue(executor.tasks.isEmpty())
        speaker.writeError = AudioPlaybackError.PlaybackRingFull
        assertEquals(
            AudioPlaybackError.PlaybackRingFull,
            async.submitPcm(ByteBuffer.allocateDirect(480), 0, 480, 240, pcm(1)).error,
        )
        assertEquals(AudioPlaybackError.PlaybackRingFull, async.snapshot().lastError)
        async.stop()
        assertEquals(
            AudioPlaybackError.ConfigGenerationMismatch,
            async.submitPcm(ByteBuffer.allocateDirect(480), 0, 480, 240, pcm(1)).error,
        )
        async.close()
        executor.runAll()
        assertEquals(1, speaker.closes)
    }

    @Test fun readySpeakerWriteRunsOnlyOnItsOwnerNotTheReceiver() {
        val executor = Initializer()
        val speaker = Speaker()
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        async.prepare(config(1))
        async.start()
        executor.runAll()
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        assertEquals(0, speaker.frames)
        executor.runAll()
        assertEquals(240, speaker.frames)
        async.close()
        executor.runAll()
    }

    @Test fun ownerPriorityIsConfiguredOffReceiverBeforeAnyNativePreparation() {
        val executor = Initializer()
        val speaker = Speaker()
        var calls = 0
        val async = AsyncMicrophoneDiagnosticPlayback(
            speaker,
            executor,
            configureOwner = {
                calls++
                true
            },
        )
        async.prepare(config(1))
        async.start()
        assertEquals(0, calls)
        assertEquals(0, speaker.prepares)
        executor.runAll()
        assertEquals(1, calls)
        assertEquals(1, speaker.prepares)
        async.close()
        executor.runAll()
    }

    private class Initializer : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(task: Runnable) {
            check(!stopped)
            tasks.add(task)
        }
        override fun shutdown() {
            stopped = true
        }
        override fun shutdownNow(): MutableList<Runnable> = tasks.toMutableList().also {
            tasks.clear()
            stopped = true
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
    private class Speaker : AudioPlaybackController {
        var prepares = 0
        var starts = 0
        var closes = 0
        var frames = 0
        var generation = 0L
        var writeError = AudioPlaybackError.None
        var throwOnWrite = false
        var beforeWrite: () -> Unit = {}
        var beforeStart: () -> Unit = {}
        val firstBytes = mutableListOf<Byte>()
        val discontinuities = mutableListOf<Boolean>()
        private var state = AudioPlaybackState.Stopped
        private fun result() = AudioPlaybackResult(AudioPlaybackError.None, snapshot())
        override fun prepare(config: AudioPlaybackConfig): AudioPlaybackResult {
            prepares++
            generation = config.configGeneration
            state = AudioPlaybackState.Prepared
            return result()
        }
        override fun start(): AudioPlaybackResult {
            beforeStart()
            starts++
            state = AudioPlaybackState.Running
            return result()
        }
        override fun stop(): AudioPlaybackResult {
            state = AudioPlaybackState.Stopped
            return result()
        }
        override fun close() {
            closes++
            state = AudioPlaybackState.Closed
        }
        override fun snapshot() = AudioPlaybackSnapshot(state = state, configGeneration = generation)
        override fun queryPresentationTimestamp() = AudioPresentationTimestampResult(AudioPlaybackError.None)
        override fun querySourcePresentationAnchor() = AudioSourcePresentationAnchorResult(AudioPlaybackError.None)
        override fun submitPcm(
            b: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            frameCount: Int,
            m: DecodedPcmMetadata,
        ): AudioPlaybackResult {
            beforeWrite()
            if (throwOnWrite) throw IllegalStateException("write failed")
            if (writeError != AudioPlaybackError.None) return AudioPlaybackResult(writeError, snapshot())
            firstBytes.add(b.get(offset))
            discontinuities.add(m.discontinuityBefore)
            frames += frameCount
            return result()
        }
    }
    private fun config(generation: Long) = AudioPlaybackConfig(
        AudioCaptureSource.MicrophoneAudio,
        generation,
        48_000,
        1,
        5_000,
        240,
    )
    private fun pcm(generation: Long, position: Long = 0L) = DecodedPcmMetadata(
        generation,
        position,
        0,
        AudioTimestampQuality.Unavailable,
        false,
        DecodedAudioFrameKind.Normal,
    )

    @Test fun coldSpeakerDoesNotBlockOrRetainAndroidInputPcm() {
        val executor = Initializer()
        val speaker = Speaker()
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        var inputFrames = 0
        val input = object : RemoteMicrophonePcmDestination {
            override fun start() = true
            override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) {
                inputFrames += frames
            }
        }
        val playback = DirectShellMicrophonePlayback(async) { input }
        playback.prepare(config(1))
        playback.start()
        playback.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        assertEquals(240, inputFrames)
        assertEquals(0, speaker.prepares)
        assertEquals(0, speaker.frames)
        executor.runAll()
        assertEquals(0, speaker.frames)
        playback.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        assertEquals(480, inputFrames)
        assertEquals(0, speaker.frames)
        executor.runAll()
        assertEquals(240, speaker.frames)
        playback.close()
        executor.runAll()
        assertEquals(1, speaker.closes)
    }

    @Test fun stopAndCloseCancelColdInitializationWithoutStartingAnOrphanClock() {
        val executor = Initializer()
        val speaker = Speaker()
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        async.prepare(config(1))
        async.start()
        async.stop()
        async.close()
        executor.runAll()
        assertEquals(0, speaker.prepares)
        assertEquals(0, speaker.starts)
        assertEquals(1, speaker.closes)
        assertEquals(AudioPlaybackError.Closed, async.start().error)
    }

    @Test fun replacementGenerationRejectsOldPcmAndSkipsOldInitialization() {
        val executor = Initializer()
        val speaker = Speaker()
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        async.prepare(config(1))
        async.start()
        async.prepare(config(2))
        async.start()
        assertEquals(
            AudioPlaybackError.ConfigGenerationMismatch,
            async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1)).error,
        )
        executor.runAll()
        assertEquals(1, speaker.prepares)
        assertEquals(1, speaker.starts)
        assertEquals(2L, speaker.generation)
        async.close()
        executor.runAll()
    }

    @Test fun pendingSpeakerWorkIsBoundedAndCapacityLossRemainsVisible() {
        val executor = Initializer()
        val speaker = Speaker()
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        async.prepare(config(1))
        async.start()
        executor.runAll()
        repeat(4) {
            assertEquals(AudioPlaybackError.None, async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1)).error)
        }
        assertEquals(
            AudioPlaybackError.PlaybackRingFull,
            async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1)).error,
        )
        assertEquals(1, executor.tasks.size)
        executor.runAll()
        assertEquals(960, speaker.frames)
        async.close()
        executor.runAll()
    }

    @Test fun staleAndPreviousGenerationPendingSpeakerPcmAreNeverReplayed() {
        val executor = Initializer()
        val speaker = Speaker()
        var now = 1L
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor) { now }
        async.prepare(config(1))
        async.start()
        executor.runAll()
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        now += 40_000_000L
        executor.runAll()
        assertEquals(0, speaker.frames)
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        async.prepare(config(2))
        async.start()
        executor.runAll()
        assertEquals(0, speaker.frames)
        assertEquals(2L, speaker.generation)
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(2))
        async.close()
        executor.runAll()
        assertEquals(0, speaker.frames)
    }

    @Test fun asynchronousNativeRejectionIsNotReportedAsSuccessfulPlayback() {
        val executor = Initializer()
        val speaker = Speaker().apply { writeError = AudioPlaybackError.PlaybackRingFull }
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        async.prepare(config(1))
        async.start()
        executor.runAll()
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        executor.runAll()
        assertEquals(0, speaker.frames)
        assertEquals(AudioPlaybackError.PlaybackRingFull, async.snapshot().lastError)
        speaker.writeError = AudioPlaybackError.None
        assertEquals(
            AudioPlaybackError.None,
            async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1)).error,
        )
        executor.runAll()
        assertEquals(240, speaker.frames)
        assertEquals(AudioPlaybackError.None, async.snapshot().lastError)
        async.close()
        executor.runAll()
    }

    @Test fun asynchronousWriterExceptionRemainsVisibleAndStillClosesTheOwner() {
        val executor = Initializer()
        val speaker = Speaker().apply { throwOnWrite = true }
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        async.prepare(config(1))
        async.start()
        executor.runAll()
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        executor.runAll()
        assertEquals(AudioPlaybackState.Error, async.snapshot().state)
        assertEquals(0, speaker.frames)
        async.close()
        executor.runAll()
        assertEquals(1, speaker.closes)
    }

    @Test fun inFlightNativeWriteCannotBlockReceiverAndStillCountsTowardTheBound() {
        val executor = Initializer()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val speaker = Speaker().apply {
            beforeWrite = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor, nowNs = { 1L })
        async.prepare(config(1))
        async.start()
        executor.runAll()
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        val owner = Thread { executor.runAll() }
        owner.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(3) {
                assertEquals(
                    AudioPlaybackError.None,
                    async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1)).error,
                )
            }
            assertEquals(
                AudioPlaybackError.PlaybackRingFull,
                async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1)).error,
            )
        } finally {
            release.countDown()
            owner.join(2000)
        }
        assertTrue(!owner.isAlive)
        assertEquals(960, speaker.frames)
        async.close()
        executor.runAll()
    }

    @Test fun receiverBufferReuseCannotChangeQueuedSpeakerPcm() {
        val executor = Initializer()
        val speaker = Speaker()
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor)
        async.prepare(config(1))
        async.start()
        executor.runAll()
        val buffer = ByteBuffer.allocate(480).put(0, 42.toByte())
        async.submitPcm(buffer, 0, 480, 240, pcm(1))
        buffer.put(0, 7.toByte())
        executor.runAll()
        assertEquals(listOf(42.toByte()), speaker.firstBytes)
        async.close()
        executor.runAll()
    }

    @Test fun localDiscardRestoresExistingPrimingOnlyAtTheFirstFrameAfterTheGap() {
        val executor = Initializer()
        val speaker = Speaker()
        val async = AsyncMicrophoneDiagnosticPlayback(speaker, executor, nowNs = { 1L })
        async.prepare(config(1))
        async.start()
        executor.runAll()
        repeat(4) { async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1, it * 240L)) }
        assertEquals(
            AudioPlaybackError.PlaybackRingFull,
            async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1, 960)).error,
        )
        executor.runAll()
        assertEquals(listOf(false, false, false, false), speaker.discontinuities)
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1, 1200))
        executor.runAll()
        async.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1, 1440))
        executor.runAll()
        assertEquals(listOf(false, false, false, false, true, false), speaker.discontinuities)
        async.close()
        executor.runAll()
    }
}
