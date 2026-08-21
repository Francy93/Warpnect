package io.warpnect.platform.audio.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureControllerCore
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureResult
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioCaptureTimestampTracker
import io.warpnect.audio.capture.AudioCaptureValidation
import io.warpnect.audio.capture.AudioChunkPlanner
import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampAnchor
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.telemetry.AudioSenderTelemetry
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidMicrophoneAudioCaptureController(
    private val context: Context,
    private val clockNs: () -> Long = AudioCaptureClock::monotonicNs,
    private val telemetry: AudioSenderTelemetry? = null,
) : AudioCaptureController {
    private val lock = Any()
    private val core = AudioCaptureControllerCore()

    @Volatile
    private var closed = false

    @Volatile
    private var running = false

    private var audioRecord: AudioRecord? = null
    private var format: AudioCaptureFormat? = null
    private var sink: PcmAudioSink? = null
    private var buffers: List<ByteBuffer> = emptyList()
    private var captureThread: Thread? = null
    private var timestampTracker: AudioCaptureTimestampTracker? = null

    override fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities {
        if (request.source != AudioCaptureSource.MicrophoneAudio) {
            return AudioCaptureCapabilities(
                source = request.source,
                available = false,
                lastError = AudioCaptureError.InvalidRequest,
            )
        }
        val sampleRate = requestedOrDefaultSampleRate(request)
        val channelCount = request.channelCount ?: AudioCaptureValidation.defaultChannelCount(request.source)
        val bytesPerFrame = channelCount * AudioPcmEncoding.Pcm16.bytesPerSample
        val chunkFrames = AudioChunkPlanner.targetFramesPerChunk(sampleRate, request.targetChunkDurationUs)
        val chunkBytes = AudioChunkPlanner.chunkBytes(chunkFrames, bytesPerFrame)
        val channelMask = inputChannelMask(channelCount) ?: 0
        val minBytes = if (channelMask != 0) {
            AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        } else {
            AudioRecord.ERROR_BAD_VALUE
        }
        val permission = hasRecordAudioPermission()
        return AudioCaptureCapabilities(
            source = request.source,
            available = permission && minBytes > 0,
            supportedSampleRatesHz = listOf(sampleRate),
            selectedSampleRateHz = sampleRate,
            channelCount = channelCount,
            encoding = AudioPcmEncoding.Pcm16,
            unprocessedMicrophoneSupported = isUnprocessedMicrophoneSupported(),
            requestedBufferSizeBytes = maxOf(minBytes, chunkBytes),
            actualBufferSizeFrames = 0,
            timestampSupport = AudioTimestampQuality.AudioRecordTimestamp,
            lastError = when {
                !permission -> AudioCaptureError.PermissionDenied
                minBytes <= 0 -> AudioCaptureError.UnsupportedFormat
                else -> AudioCaptureError.None
            },
        )
    }

    override suspend fun prepare(request: AudioCaptureRequest, sink: PcmAudioSink): AudioCaptureResult =
        synchronized(lock) {
            if (closed) {
                return@synchronized AudioCaptureResult(AudioCaptureError.Closed, core.snapshot())
            }
            if (request.source != AudioCaptureSource.MicrophoneAudio) {
                core.fail(AudioCaptureError.InvalidRequest)
                return@synchronized AudioCaptureResult(AudioCaptureError.InvalidRequest, core.snapshot())
            }
            val beginError = core.beginPrepare(request)
            if (beginError != AudioCaptureError.None) {
                return@synchronized AudioCaptureResult(beginError, core.snapshot())
            }
            if (!hasRecordAudioPermission()) {
                return@synchronized prepareFailure(AudioCaptureError.PermissionDenied)
            }

            val prepared = createAudioRecord(request)
            if (prepared.error != AudioCaptureError.None || prepared.audioRecord == null || prepared.format == null) {
                return@synchronized prepareFailure(prepared.error)
            }

            try {
                sink.onFormatChanged(prepared.format)
            } catch (_: RuntimeException) {
                prepared.audioRecord.release()
                return@synchronized prepareFailure(AudioCaptureError.SinkFailure)
            }

            audioRecord = prepared.audioRecord
            format = prepared.format
            this.sink = sink
            timestampTracker = AudioCaptureTimestampTracker(prepared.format.sampleRateHz)
            buffers = List(MICROPHONE_BUFFER_COUNT) {
                ByteBuffer.allocateDirect(prepared.chunkBytes).order(ByteOrder.nativeOrder())
            }
            core.completePrepare(
                error = AudioCaptureError.None,
                format = prepared.format,
                actualBufferFrames = prepared.audioRecord.bufferSizeInFrames,
            )
            AudioCaptureResult(AudioCaptureError.None, core.snapshot())
        }

    override suspend fun start(): AudioCaptureResult = synchronized(lock) {
        if (closed) {
            return@synchronized AudioCaptureResult(AudioCaptureError.Closed, core.snapshot())
        }
        val beginError = core.beginStart()
        if (beginError != AudioCaptureError.None) {
            return@synchronized AudioCaptureResult(beginError, core.snapshot())
        }
        val record = audioRecord ?: return@synchronized startFailure(AudioCaptureError.NotPrepared)
        return try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                startFailure(AudioCaptureError.AudioRecordStartFailed)
            } else {
                running = true
                val thread = Thread(::captureLoop, MICROPHONE_THREAD_NAME)
                captureThread = thread
                thread.start()
                core.completeStart(AudioCaptureError.None)
                AudioCaptureResult(AudioCaptureError.None, core.snapshot())
            }
        } catch (_: RuntimeException) {
            startFailure(AudioCaptureError.AudioRecordStartFailed)
        }
    }

    override suspend fun stop(): AudioCaptureResult {
        val record: AudioRecord?
        val thread: Thread?
        synchronized(lock) {
            if (closed) {
                return AudioCaptureResult(AudioCaptureError.None, core.snapshot())
            }
            core.beginStop()
            running = false
            record = audioRecord
            thread = captureThread
        }
        runCatching { record?.stop() }
        val stopped = joinCaptureThread(thread)
        synchronized(lock) {
            releasePreparedResources()
            return core.completeStop(if (stopped) AudioCaptureError.None else AudioCaptureError.ThreadStopFailed)
        }
    }

    override fun snapshot() = core.snapshot()

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        running = false
        val record: AudioRecord?
        val thread: Thread?
        synchronized(lock) {
            record = audioRecord
            thread = captureThread
        }
        runCatching { record?.stop() }
        joinCaptureThread(thread)
        synchronized(lock) {
            releasePreparedResources()
            core.close()
        }
    }

    private fun captureLoop() {
        AndroidAudioThreadPriority.applyCapturePriority()
        var bufferIndex = 0
        val localRecord = audioRecord ?: return
        val localFormat = format ?: return
        val localSink = sink ?: return
        val localTracker = timestampTracker ?: return
        val requestedBytes = localFormat.targetFramesPerChunk * localFormat.bytesPerFrame
        val timestamp = AudioTimestamp()
        while (running) {
            val buffer = buffers[bufferIndex]
            buffer.clear()
            val readBytes = try {
                localRecord.read(buffer, requestedBytes, AudioRecord.READ_BLOCKING)
            } catch (_: RuntimeException) {
                failFromCaptureThread(AudioCaptureError.AudioRecordReadFailed)
                return
            }
            val completionNs = clockNs()
            if (readBytes < 0) {
                failFromCaptureThread(audioRecordReadError(readBytes))
                return
            }
            val alignedBytes = readBytes - (readBytes % localFormat.bytesPerFrame)
            if (alignedBytes > 0) {
                updateTimestampAnchor(localRecord, timestamp, localTracker)
                val frameCount = alignedBytes / localFormat.bytesPerFrame
                val timing = localTracker.recordChunk(frameCount, completionNs)
                try {
                    localSink.onPcmChunk(
                        buffer = buffer,
                        offset = 0,
                        sizeBytes = alignedBytes,
                        frameCount = frameCount,
                        firstFramePosition = timing.firstFramePosition,
                        captureTimeNs = timing.captureTimeNs,
                        timestampQuality = timing.timestampQuality,
                    )
                    core.recordChunk(
                        sizeBytes = alignedBytes,
                        frameCount = frameCount,
                        firstFramePosition = timing.firstFramePosition,
                        captureTimeNs = timing.captureTimeNs,
                        timestampQuality = timing.timestampQuality,
                    )
                    telemetry?.capturedSamples?.add(frameCount.toULong())
                } catch (_: RuntimeException) {
                    core.recordSinkFailure()
                    failFromCaptureThread(AudioCaptureError.SinkFailure)
                    return
                }
            }
            bufferIndex = (bufferIndex + 1) % buffers.size
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(request: AudioCaptureRequest): PreparedAudioRecord {
        val sampleRate = requestedOrDefaultSampleRate(request)
        val channelCount = request.channelCount ?: AudioCaptureValidation.defaultChannelCount(request.source)
        val channelMask = inputChannelMask(channelCount) ?: return PreparedAudioRecord(
            error = AudioCaptureError.UnsupportedFormat,
        )
        val actualFormat = AudioCaptureValidation.buildFormat(request, sampleRate, channelCount)
            ?: return PreparedAudioRecord(error = AudioCaptureError.UnsupportedFormat)
        val chunkBytes = actualFormat.targetFramesPerChunk * actualFormat.bytesPerFrame
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            return PreparedAudioRecord(error = AudioCaptureError.UnsupportedFormat)
        }
        val bufferSizeBytes = maxOf(minBufferBytes, chunkBytes * 2)
        val source = microphoneAudioSource()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
        } catch (_: SecurityException) {
            return PreparedAudioRecord(error = AudioCaptureError.PermissionDenied)
        } catch (_: RuntimeException) {
            return PreparedAudioRecord(error = AudioCaptureError.AudioRecordCreationFailed)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return PreparedAudioRecord(error = AudioCaptureError.AudioRecordUninitialized)
        }
        if (request.requireStrictFormat && record.sampleRate != sampleRate) {
            record.release()
            return PreparedAudioRecord(error = AudioCaptureError.UnsupportedFormat)
        }
        return PreparedAudioRecord(
            error = AudioCaptureError.None,
            audioRecord = record,
            format = actualFormat.copy(
                sampleRateHz = record.sampleRate,
                channelCount = record.channelCount,
            ),
            chunkBytes = chunkBytes,
        )
    }

    private fun updateTimestampAnchor(
        record: AudioRecord,
        timestamp: AudioTimestamp,
        tracker: AudioCaptureTimestampTracker,
    ) {
        val result = try {
            record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
        } catch (_: RuntimeException) {
            AudioRecord.ERROR
        }
        if (result == AudioRecord.SUCCESS) {
            tracker.updateAnchor(
                AudioTimestampAnchor(
                    framePosition = timestamp.framePosition,
                    nanoTime = timestamp.nanoTime,
                ),
            )
        }
    }

    private fun failFromCaptureThread(error: AudioCaptureError) {
        running = false
        core.fail(error)
        runCatching { sink?.onCaptureError(error) }
    }

    private fun prepareFailure(error: AudioCaptureError): AudioCaptureResult {
        releasePreparedResources()
        core.completePrepare(error, null)
        return AudioCaptureResult(error, core.snapshot())
    }

    private fun startFailure(error: AudioCaptureError): AudioCaptureResult {
        core.completeStart(error)
        return AudioCaptureResult(error, core.snapshot())
    }

    private fun releasePreparedResources() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        format = null
        sink = null
        buffers = emptyList()
        captureThread = null
        timestampTracker?.reset()
        timestampTracker = null
        running = false
    }

    private fun joinCaptureThread(thread: Thread?): Boolean {
        if (thread == null || thread == Thread.currentThread()) {
            return true
        }
        return try {
            thread.join(STOP_JOIN_TIMEOUT_MS)
            !thread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestedOrDefaultSampleRate(request: AudioCaptureRequest): Int =
        request.preferredSampleRateHz ?: deviceDefaultSampleRateHz()

    private fun deviceDefaultSampleRateHz(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val property = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return property?.toIntOrNull()?.takeIf { it > 0 }
            ?: AudioCaptureRequest.DEFAULT_SAMPLE_RATE_HZ
    }

    private fun isUnprocessedMicrophoneSupported(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.equals("true", ignoreCase = true) == true
    }

    private fun microphoneAudioSource(): Int = if (isUnprocessedMicrophoneSupported()) {
        MediaRecorder.AudioSource.UNPROCESSED
    } else {
        MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun inputChannelMask(channelCount: Int): Int? = when (channelCount) {
        1 -> AudioFormat.CHANNEL_IN_MONO
        2 -> AudioFormat.CHANNEL_IN_STEREO
        else -> null
    }

    private fun audioRecordReadError(errorCode: Int): AudioCaptureError = when (errorCode) {
        AudioRecord.ERROR_DEAD_OBJECT -> AudioCaptureError.AudioRecordDead
        AudioRecord.ERROR_BAD_VALUE,
        AudioRecord.ERROR_INVALID_OPERATION,
        AudioRecord.ERROR,
        -> AudioCaptureError.AudioRecordReadFailed
        else -> AudioCaptureError.AudioRecordReadFailed
    }

    private data class PreparedAudioRecord(
        val error: AudioCaptureError,
        val audioRecord: AudioRecord? = null,
        val format: AudioCaptureFormat? = null,
        val chunkBytes: Int = 0,
    )

    companion object {
        private const val MICROPHONE_THREAD_NAME = "WarpnectMicrophoneCapture"
        private const val MICROPHONE_BUFFER_COUNT = 3
        private const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
