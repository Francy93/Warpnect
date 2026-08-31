package io.warpnect.platform.video.encoder

import android.media.MediaCodec
import android.os.Looper
import io.warpnect.video.encoder.VideoEncoderRequest
import java.util.LinkedHashMap

/**
 * A bounded cold-path adjudicator for vendor codec metadata that disagrees with an exact
 * production encoder configuration. It is never a bitrate-mode fallback.
 */
internal class CbrCapabilityFallback(
    private val activeProbe: ExactVideoEncoderCapabilityProbe,
) {
    fun resolve(
        metadataSupported: Boolean,
        allOtherRequirementsSupported: Boolean,
        key: ExactVideoEncoderCapabilityKey,
    ): CbrCapabilityDecision = when {
        metadataSupported -> CbrCapabilityDecision(true, CbrCapabilityDecisionSource.Metadata)
        !allOtherRequirementsSupported -> CbrCapabilityDecision(false, CbrCapabilityDecisionSource.NotEligible)
        else -> activeProbe.probe(key)
    }
}

internal data class ExactVideoEncoderCapabilityKey(
    val codecName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrateBps: Int,
    val bitrateMode: String,
    val iFrameIntervalBits: Int,
) {
    companion object {
        fun from(codecName: String, request: VideoEncoderRequest): ExactVideoEncoderCapabilityKey =
            ExactVideoEncoderCapabilityKey(
                codecName = codecName,
                mimeType = request.codec.mimeType,
                width = request.width,
                height = request.height,
                frameRate = request.frameRate,
                bitrateBps = request.bitrateBps,
                bitrateMode = request.bitrateMode.name,
                iFrameIntervalBits = request.iFrameIntervalSeconds.toBits(),
            )
    }
}

internal enum class CbrCapabilityDecisionSource {
    Metadata,
    NotEligible,
    ActiveProbe,
    ActiveProbeCache,
}

internal enum class ExactVideoEncoderCapabilityProbeResult {
    Supported,
    MainThreadRejected,
    CodecCreationFailed,
    ConfigureFailed,
    InputSurfaceFailed,
    StartFailed,
}

internal data class CbrCapabilityDecision(
    val supported: Boolean,
    val source: CbrCapabilityDecisionSource,
    val probeResult: ExactVideoEncoderCapabilityProbeResult? = null,
)

internal interface ExactVideoEncoderCapabilityProbe {
    fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision
}

/** Process-local, fixed-size cache for exact MediaCodec configuration results. */
internal class CachedExactVideoEncoderCapabilityProbe(
    private val delegate: ExactVideoEncoderCapabilityProbe,
    private val capacity: Int = DEFAULT_CACHE_CAPACITY,
) : ExactVideoEncoderCapabilityProbe {
    private val cache = object : LinkedHashMap<ExactVideoEncoderCapabilityKey, ExactVideoEncoderCapabilityProbeResult>(
        capacity,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ExactVideoEncoderCapabilityKey, ExactVideoEncoderCapabilityProbeResult>,
        ): Boolean = size > capacity
    }

    init {
        require(capacity > 0)
    }

    override fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision = synchronized(cache) {
        cache[key]?.let { result ->
            return CbrCapabilityDecision(
                supported = result == ExactVideoEncoderCapabilityProbeResult.Supported,
                source = CbrCapabilityDecisionSource.ActiveProbeCache,
                probeResult = result,
            )
        }

        val decision = delegate.probe(key)
        val result = decision.probeResult
        if (result != null && result != ExactVideoEncoderCapabilityProbeResult.MainThreadRejected) {
            cache[key] = result
        }
        decision
    }

    private companion object {
        const val DEFAULT_CACHE_CAPACITY = 32
    }
}

/** Runs the exact cold codec lifecycle and guarantees best-effort cleanup at every failure stage. */
internal object ExactFormatEncoderProbeRunner {
    fun run(factory: ExactFormatEncoderProbeCodecFactory): ExactVideoEncoderCapabilityProbeResult {
        val codec = try {
            factory.create()
        } catch (_: Throwable) {
            return ExactVideoEncoderCapabilityProbeResult.CodecCreationFailed
        }
        var surfaceCreated = false
        var started = false
        try {
            try {
                codec.configure()
            } catch (_: Throwable) {
                return ExactVideoEncoderCapabilityProbeResult.ConfigureFailed
            }
            try {
                codec.createInputSurface()
                surfaceCreated = true
            } catch (_: Throwable) {
                return ExactVideoEncoderCapabilityProbeResult.InputSurfaceFailed
            }
            try {
                codec.start()
                started = true
            } catch (_: Throwable) {
                return ExactVideoEncoderCapabilityProbeResult.StartFailed
            }
            return ExactVideoEncoderCapabilityProbeResult.Supported
        } finally {
            if (started) runCatching { codec.stop() }
            if (surfaceCreated) runCatching { codec.releaseInputSurface() }
            runCatching { codec.release() }
        }
    }
}

internal fun interface ExactFormatEncoderProbeCodecFactory {
    fun create(): ExactFormatEncoderProbeCodec
}

internal interface ExactFormatEncoderProbeCodec {
    fun configure()
    fun createInputSurface()
    fun start()
    fun stop()
    fun releaseInputSurface()
    fun release()
}

internal class AndroidExactVideoEncoderCapabilityProbe(
    private val codecFactory: ExactFormatEncoderProbeCodecFactory? = null,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) : ExactVideoEncoderCapabilityProbe {
    override fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision {
        if (isMainThread()) {
            return decision(ExactVideoEncoderCapabilityProbeResult.MainThreadRejected)
        }
        val factory = codecFactory ?: codecFactoryFor(key)
        return decision(ExactFormatEncoderProbeRunner.run(factory))
    }

    private fun codecFactoryFor(key: ExactVideoEncoderCapabilityKey): ExactFormatEncoderProbeCodecFactory =
        ExactFormatEncoderProbeCodecFactory {
            val request = VideoEncoderRequest(
                width = key.width,
                height = key.height,
                frameRate = key.frameRate,
                bitrateBps = key.bitrateBps,
                iFrameIntervalSeconds = Float.fromBits(key.iFrameIntervalBits),
            )
            MediaCodecExactFormatEncoderProbeCodec(
                MediaCodec.createByCodecName(key.codecName),
                AndroidVideoEncoderFormatFactory.create(request),
            )
        }

    private fun decision(result: ExactVideoEncoderCapabilityProbeResult) = CbrCapabilityDecision(
        supported = result == ExactVideoEncoderCapabilityProbeResult.Supported,
        source = CbrCapabilityDecisionSource.ActiveProbe,
        probeResult = result,
    )
}

private class MediaCodecExactFormatEncoderProbeCodec(
    private val codec: MediaCodec,
    private val format: android.media.MediaFormat,
) : ExactFormatEncoderProbeCodec {
    private var inputSurface: android.view.Surface? = null

    override fun configure() {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    override fun createInputSurface() {
        inputSurface = codec.createInputSurface()
    }

    override fun start() {
        codec.start()
    }

    override fun stop() {
        codec.stop()
    }

    override fun releaseInputSurface() {
        inputSurface?.release()
        inputSurface = null
    }

    override fun release() {
        codec.release()
    }
}
