package io.warpnect.platform.audio.capture.privileged

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import io.warpnect.audio.capture.AudioCaptureControllerCore
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioCaptureTimestampTracker
import io.warpnect.audio.capture.AudioCaptureValidation
import io.warpnect.audio.capture.AudioChunkPlanner
import io.warpnect.platform.audio.capture.AndroidAudioThreadPriority
import io.warpnect.platform.audio.capture.AudioCaptureClock
import io.warpnect.platform.audio.capture.shared.PcmRingSignalRecord
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PrivilegedAudioCaptureUserService : IPrivilegedAudioCaptureService.Stub() {
    private val audioPolicyApi: PrivilegedAudioPolicyCaptureApi = ReflectivePrivilegedAudioPolicyCaptureApi()
    private val core = AudioCaptureControllerCore()

    @Volatile
    private var running = false

    private var preparedFormat: io.warpnect.audio.capture.AudioCaptureFormat? = null
    private var sharedMemory: SharedMemory? = null
    private var mappedRing: ByteBuffer? = null
    private var notifyWriteFd: ParcelFileDescriptor? = null
    private var ackReadFd: ParcelFileDescriptor? = null
    private var notifyOutput: OutputStream? = null
    private var ackInput: InputStream? = null
    private var captureThread: Thread? = null
    private var discardBuffer: ByteBuffer? = null
    private var timestampTracker: AudioCaptureTimestampTracker? = null
    private var nextSequence = 1L

    override fun querySystemAudioCapabilities(): Bundle {
        val request = AudioCaptureRequest(source = AudioCaptureSource.SystemAudio)
        return audioPolicyApi.queryCapabilities(request).toBundle()
    }

    @SuppressLint("NewApi")
    override fun prepareSystemAudioCapture(
        sampleRateHz: Int,
        channelCount: Int,
        targetChunkFrames: Int,
        targetChunkDurationUs: Long,
        sharedRingSlotCount: Int,
        targetUid: Int,
    ): Bundle {
        stopSystemAudioCapture()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return setupFailure(AudioCaptureError.UnsupportedPlatform)
        }
        val request = AudioCaptureRequest(
            source = AudioCaptureSource.SystemAudio,
            preferredSampleRateHz = sampleRateHz.takeIf { it > 0 },
            channelCount = channelCount.takeIf { it > 0 },
            targetChunkDurationUs = targetChunkDurationUs,
            sharedRingSlotCount = sharedRingSlotCount,
            targetUid = targetUid.takeIf { it >= 0 },
        )
        val beginError = core.beginPrepare(request)
        if (beginError != AudioCaptureError.None) {
            return setupFailure(beginError)
        }
        val actualSampleRate = request.preferredSampleRateHz ?: AudioCaptureRequest.DEFAULT_SAMPLE_RATE_HZ
        val actualChannelCount = request.channelCount ?: AudioCaptureValidation.defaultChannelCount(request.source)
        val format = AudioCaptureValidation.buildFormat(request, actualSampleRate, actualChannelCount)
            ?: return prepareFailureBundle(AudioCaptureError.UnsupportedFormat)
        val chunkFrames = if (targetChunkFrames > 0) targetChunkFrames else format.targetFramesPerChunk
        val bytesPerChunk = AudioChunkPlanner.chunkBytes(chunkFrames, format.bytesPerFrame)
        if (bytesPerChunk <= 0) {
            return prepareFailureBundle(AudioCaptureError.UnsupportedFormat)
        }
        val policyResult = audioPolicyApi.prepareSystemAudioCapture(
            PrivilegedSystemAudioPrepareRequest(
                request = request,
                format = format.copy(targetFramesPerChunk = chunkFrames),
                audioRecordBufferSizeBytes = bytesPerChunk * 2,
            ),
        )
        if (policyResult.error != AudioCaptureError.None) {
            return prepareFailureBundle(policyResult.error)
        }

        val shared = try {
            SharedMemory.create(
                "warpnect_system_audio_pcm",
                SharedPcmAudioRingLayout.totalBytes(sharedRingSlotCount, bytesPerChunk),
            )
        } catch (_: Exception) {
            audioPolicyApi.stopSystemAudioCapture()
            return prepareFailureBundle(AudioCaptureError.SharedMemoryCreationFailed)
        }
        val mapped = try {
            shared.mapReadWrite()
        } catch (_: Exception) {
            shared.close()
            audioPolicyApi.stopSystemAudioCapture()
            return prepareFailureBundle(AudioCaptureError.SharedMemoryMappingFailed)
        }.order(ByteOrder.nativeOrder())
        if (!SharedPcmAudioRingLayout.initialize(mapped, sharedRingSlotCount, bytesPerChunk)) {
            cleanupSharedMemory(shared, mapped)
            audioPolicyApi.stopSystemAudioCapture()
            return prepareFailureBundle(AudioCaptureError.SharedRingCorrupt)
        }

        val notifyPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: Exception) {
            cleanupSharedMemory(shared, mapped)
            audioPolicyApi.stopSystemAudioCapture()
            return prepareFailureBundle(AudioCaptureError.NotificationChannelFailed)
        }
        val ackPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: Exception) {
            closePipe(notifyPipe)
            cleanupSharedMemory(shared, mapped)
            audioPolicyApi.stopSystemAudioCapture()
            return prepareFailureBundle(AudioCaptureError.NotificationChannelFailed)
        }

        sharedMemory = shared
        mappedRing = mapped
        notifyWriteFd = notifyPipe[1]
        ackReadFd = ackPipe[0]
        notifyOutput = ParcelFileDescriptor.AutoCloseOutputStream(notifyWriteFd)
        ackInput = ParcelFileDescriptor.AutoCloseInputStream(ackReadFd)
        preparedFormat = format.copy(targetFramesPerChunk = chunkFrames)
        discardBuffer = ByteBuffer.allocateDirect(bytesPerChunk).order(ByteOrder.nativeOrder())
        timestampTracker = AudioCaptureTimestampTracker(format.sampleRateHz)
        core.completePrepare(
            error = AudioCaptureError.None,
            format = preparedFormat,
            actualBufferFrames = policyResult.actualBufferSizeFrames,
            ringCapacity = sharedRingSlotCount,
        )

        return Bundle().apply {
            putInt(PrivilegedAudioCaptureContract.KEY_ERROR, AudioCaptureError.None.code)
            preparedFormat?.let(::putFormat)
            putInt(
                PrivilegedAudioCaptureContract.KEY_ACTUAL_BUFFER_SIZE_FRAMES,
                policyResult.actualBufferSizeFrames,
            )
            putInt(PrivilegedAudioCaptureContract.KEY_RING_CAPACITY, sharedRingSlotCount)
            putParcelable(PrivilegedAudioCaptureContract.KEY_SHARED_MEMORY, shared)
            putParcelable(PrivilegedAudioCaptureContract.KEY_NOTIFY_READ_FD, notifyPipe[0])
            putParcelable(PrivilegedAudioCaptureContract.KEY_ACK_WRITE_FD, ackPipe[1])
        }
    }

    override fun startSystemAudioCapture(): Int {
        if (running) {
            return AudioCaptureError.AlreadyRunning.code
        }
        val beginError = core.beginStart()
        if (beginError != AudioCaptureError.None) {
            return beginError.code
        }
        val startError = audioPolicyApi.startRecording()
        if (startError != AudioCaptureError.None) {
            core.completeStart(startError)
            return startError.code
        }
        running = true
        captureThread = Thread(::captureLoop, SYSTEM_AUDIO_THREAD_NAME).apply { start() }
        core.completeStart(AudioCaptureError.None)
        return AudioCaptureError.None.code
    }

    override fun stopSystemAudioCapture(): Int {
        running = false
        val thread = captureThread
        runCatching { audioPolicyApi.stopSystemAudioCapture() }
        runCatching { notifyWriteFd?.close() }
        if (thread != null && thread != Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
        releaseResources()
        core.completeStop(AudioCaptureError.None)
        return AudioCaptureError.None.code
    }

    override fun getSystemAudioState(): Bundle = core.snapshot().toBundle()

    @Suppress("unused")
    fun destroy() {
        stopSystemAudioCapture()
    }

    private fun captureLoop() {
        AndroidAudioThreadPriority.applyCapturePriority()
        val ring = mappedRing ?: return
        val format = preparedFormat ?: return
        val output = notifyOutput ?: return
        val tracker = timestampTracker ?: return
        val chunkBytes = format.targetFramesPerChunk * format.bytesPerFrame
        val notifyScratch = ByteArray(PcmRingSignalRecord.WIRE_SIZE)
        val ackScratch = ByteArray(PcmRingSignalRecord.WIRE_SIZE)
        while (running) {
            drainAcks(ackScratch)
            val slot = SharedPcmAudioRingLayout.beginWrite(ring)
            val target = if (slot >= 0) {
                SharedPcmAudioRingLayout.payloadSlice(ring, slot)
            } else {
                discardBuffer ?: return
            }
            target.clear()
            val readBytes = audioPolicyApi.read(target, chunkBytes)
            val completionNs = AudioCaptureClock.monotonicNs()
            if (readBytes < 0) {
                core.fail(audioRecordReadError(readBytes))
                running = false
                return
            }
            val alignedBytes = readBytes - (readBytes % format.bytesPerFrame)
            val frameCount = alignedBytes / format.bytesPerFrame
            val anchor = audioPolicyApi.latestTimestampAnchor()
            if (anchor != null) {
                tracker.updateAnchor(anchor)
            }
            val timing = tracker.recordChunk(frameCount, completionNs)
            if (slot < 0) {
                core.recordDroppedChunk(frameCount)
                continue
            }
            if (alignedBytes <= 0) {
                SharedPcmAudioRingLayout.cancelWrite(ring, slot)
                continue
            }
            val published = SharedPcmAudioRingLayout.publish(
                memory = ring,
                slot = slot,
                sequence = nextSequence++,
                validBytes = alignedBytes,
                frameCount = frameCount,
                firstFramePosition = timing.firstFramePosition,
                captureTimeNs = timing.captureTimeNs,
                timestampQualityCode = timing.timestampQuality.ordinal,
                flags = 0,
                publishTimeNs = completionNs,
            )
            if (!published) {
                core.recordDroppedChunk(frameCount)
                continue
            }
            core.recordChunk(
                sizeBytes = alignedBytes,
                frameCount = frameCount,
                firstFramePosition = timing.firstFramePosition,
                captureTimeNs = timing.captureTimeNs,
                timestampQuality = timing.timestampQuality,
            )
            core.recordRingState(
                occupancy = SharedPcmAudioRingLayout.occupancy(ring),
                highWaterMark = SharedPcmAudioRingLayout.highWater(ring),
            )
            val generation = SharedPcmAudioRingLayout.slotGeneration(ring, slot)
            if (!PcmRingSignalRecord.write(output, PcmRingSignalRecord(slot, generation), notifyScratch)) {
                core.fail(AudioCaptureError.NotificationChannelFailed)
                running = false
                return
            }
        }
    }

    private fun drainAcks(scratch: ByteArray) {
        val input = ackInput ?: return
        while (true) {
            val available = try {
                input.available()
            } catch (_: Exception) {
                return
            }
            if (available < PcmRingSignalRecord.WIRE_SIZE) {
                return
            }
            PcmRingSignalRecord.read(input, scratch) ?: return
        }
    }

    private fun prepareFailureBundle(error: AudioCaptureError): Bundle {
        releaseResources()
        audioPolicyApi.stopSystemAudioCapture()
        core.completePrepare(error, null)
        return setupFailure(error)
    }

    private fun setupFailure(error: AudioCaptureError): Bundle = Bundle().apply {
        putInt(PrivilegedAudioCaptureContract.KEY_ERROR, error.code)
    }

    @SuppressLint("NewApi")
    private fun releaseResources() {
        captureThread = null
        preparedFormat = null
        discardBuffer = null
        timestampTracker?.reset()
        timestampTracker = null
        notifyOutput = null
        ackInput = null
        runCatching { notifyWriteFd?.close() }
        runCatching { ackReadFd?.close() }
        notifyWriteFd = null
        ackReadFd = null
        mappedRing?.let { buffer ->
            runCatching { SharedMemory.unmap(buffer) }
        }
        mappedRing = null
        runCatching { sharedMemory?.close() }
        sharedMemory = null
    }

    @SuppressLint("NewApi")
    private fun cleanupSharedMemory(sharedMemory: SharedMemory, mapped: ByteBuffer) {
        runCatching { SharedMemory.unmap(mapped) }
        runCatching { sharedMemory.close() }
    }

    private fun closePipe(pipe: Array<ParcelFileDescriptor>) {
        pipe.forEach { descriptor -> runCatching { descriptor.close() } }
    }

    private fun audioRecordReadError(errorCode: Int): AudioCaptureError = when (errorCode) {
        AudioRecord.ERROR_DEAD_OBJECT -> AudioCaptureError.AudioRecordDead
        AudioRecord.ERROR_BAD_VALUE,
        AudioRecord.ERROR_INVALID_OPERATION,
        AudioRecord.ERROR,
        -> AudioCaptureError.AudioRecordReadFailed
        else -> AudioCaptureError.AudioRecordReadFailed
    }

    private companion object {
        const val SYSTEM_AUDIO_THREAD_NAME = "WarpnectSystemAudioCapture"
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
