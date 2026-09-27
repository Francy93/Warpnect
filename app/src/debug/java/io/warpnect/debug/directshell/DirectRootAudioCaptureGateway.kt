package io.warpnect.debug.directshell

import android.os.ParcelFileDescriptor
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioCaptureValidation
import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.platform.audio.capture.DirectRootSystemAudioCapability
import io.warpnect.platform.audio.capture.DirectRootSystemAudioGateway
import io.warpnect.platform.audio.capture.PrivilegedAudioCaptureGateway
import io.warpnect.platform.audio.capture.RootAudioRequest
import io.warpnect.platform.audio.capture.RootAudioStatusError
import io.warpnect.platform.audio.capture.RootPcmDescriptorLease
import io.warpnect.platform.audio.capture.privileged.PrivilegedSystemAudioSetup
import io.warpnect.platform.audio.capture.shared.LegacyAshmemPcmMemory
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import java.security.SecureRandom
import org.json.JSONObject

/** LAB transport adapter for the unchanged normal audio controller, drain and encoder. */
internal class DirectRootAudioCaptureGateway(
    private val runtime: DirectRootRuntime,
    private val onServiceDied: () -> Unit,
) : PrivilegedAudioCaptureGateway {
    private var owner: RingOwner? = null
    private var closed = false
    private val observation = runtime.observe {
        if (!closed && !runtime.snapshot.ready) {
            owner?.closeWriters()
            onServiceDied()
        }
    }

    override fun supportsSharedPcm(api: Int): Boolean = api >= 26

    override suspend fun querySystemAudioCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities {
        val error = when {
            closed -> AudioCaptureError.Closed
            !runtime.snapshot.ready -> AudioCaptureError.PrivilegedServiceDied
            !valid(request) -> AudioCaptureError.UnsupportedFormat
            !DirectRootSystemAudioCapability.qualification(
                runtime.snapshot,
            ).state.isUsable -> AudioCaptureError.UnsupportedPlatform
            else -> AudioCaptureError.None
        }
        return AudioCaptureCapabilities(
            source = AudioCaptureSource.SystemAudio,
            available = error == AudioCaptureError.None,
            supportedSampleRatesHz = listOf(48_000),
            selectedSampleRateHz = 48_000,
            channelCount = 2,
            privilegedBackendAvailable = error == AudioCaptureError.None,
            timestampSupport = AudioTimestampQuality.EstimatedFromReadCompletion,
            lastError = error,
        )
    }

    override suspend fun prepareSystemAudioCapture(request: AudioCaptureRequest): PrivilegedSystemAudioSetup {
        val error = querySystemAudioCapabilities(request).lastError
        if (error != AudioCaptureError.None) return failed(error)
        owner?.close()
        owner = null
        return try {
            val ring = RingOwner(runtime).also { owner = it }
            DirectRootPcmHandoff.stage(ring.request, ring.memory, ring.notify[1], ring.ack[0])
            val prepared = JSONObject(ring.gateway.prepare(ring.request))
            val preparedError = RootAudioStatusError.classify(prepared.getString("boundary"), "PREPARED")
            if (preparedError != AudioCaptureError.None) {
                ring.close()
                owner = null
                return failed(preparedError)
            }
            PrivilegedSystemAudioSetup(
                AudioCaptureError.None,
                AudioCaptureValidation.buildFormat(request, 48_000, 2),
                prepared.getInt("buffer_bytes") / 4,
                8,
                null,
                ring.notify[0],
                ring.ack[1],
                ring.memory,
            )
        } catch (_: Exception) {
            owner?.close()
            owner = null
            failed(if (runtime.snapshot.ready) AudioCaptureError.Unknown else AudioCaptureError.PrivilegedServiceDied)
        }
    }

    override suspend fun startSystemAudioCapture(): AudioCaptureError = control("RECORDING") {
        it.gateway.start(it.request)
    }

    override suspend fun stopSystemAudioCapture(): AudioCaptureError {
        val ring = owner ?: return AudioCaptureError.None
        if (ring.stopped) return AudioCaptureError.None
        val stopped = control("STOPPED") { it.gateway.stop(it.request) }
        ring.gateway.close()
        DirectRootPcmHandoff.release(ring.request)
        ring.closeWriters()
        ring.stopped = true
        return stopped
    }

    private fun control(expected: String, operation: (RingOwner) -> String): AudioCaptureError {
        val ring = owner ?: return AudioCaptureError.NotPrepared
        return try {
            RootAudioStatusError.classify(JSONObject(operation(ring)).getString("boundary"), expected)
        } catch (_: Exception) {
            AudioCaptureError.PrivilegedServiceDied
        }
    }

    override fun snapshot() = AudioCaptureSnapshot(
        lastError = if (runtime.snapshot.ready) AudioCaptureError.None else AudioCaptureError.PrivilegedServiceDied,
    )

    override fun close() {
        if (closed) return
        closed = true
        observation.close()
        owner?.close()
        owner = null
    }

    private fun valid(request: AudioCaptureRequest): Boolean =
        AudioCaptureValidation.validate(request) == AudioCaptureError.None &&
            request.source == AudioCaptureSource.SystemAudio &&
            request.preferredSampleRateHz in listOf(null, 48_000) && request.channelCount in listOf(null, 2) &&
            request.encoding == AudioPcmEncoding.Pcm16 && request.targetChunkDurationUs == 5_000L &&
            request.sharedRingSlotCount == 8 && request.targetUid == null

    private fun failed(error: AudioCaptureError) = PrivilegedSystemAudioSetup(error, null, 0, 0, null, null, null)

    private class RingOwner(runtime: DirectRootRuntime) : AutoCloseable {
        var stopped = false
        val request =
            RootAudioRequest(
                requireNotNull(runtime.snapshot.generation),
                (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(2),
            )
        val memory = LegacyAshmemPcmMemory.create("warpnect-root-audio", RootPcmDescriptorLease.REGION_BYTES)
        val notify: Array<ParcelFileDescriptor>
        val ack: Array<ParcelFileDescriptor>
        val gateway = DirectRootSystemAudioGateway(runtime)
        init {
            var pipe: Array<ParcelFileDescriptor>? = null
            try {
                val mapped = memory.mapReadWrite()
                try {
                    check(SharedPcmAudioRingLayout.initialize(mapped, 8, 960))
                } finally {
                    memory.unmap(mapped)
                }
                pipe = ParcelFileDescriptor.createPipe()
                ack = ParcelFileDescriptor.createPipe()
                notify = pipe
            } catch (error: Exception) {
                pipe?.forEach { runCatching { it.close() } }
                memory.close()
                gateway.close()
                throw error
            }
        }
        fun closeWriters() {
            runCatching { notify[1].close() }
            runCatching { ack[0].close() }
        }
        override fun close() {
            gateway.close()
            DirectRootPcmHandoff.release(request)
            (notify + ack).forEach { runCatching { it.close() } }
            memory.close()
        }
    }
}
