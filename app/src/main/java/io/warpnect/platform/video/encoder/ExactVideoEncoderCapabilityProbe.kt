package io.warpnect.platform.video.encoder

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.os.Build
import android.os.Looper
import io.warpnect.video.encoder.VideoBitrateMode
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    val qualificationAlgorithmVersion: Int = ExactVideoEncoderQualificationProfile.ALGORITHM_VERSION,
    val probeWorkloadVersion: String = ExactVideoEncoderQualificationProfile.PROBE_WORKLOAD_VERSION,
    val targetProfileVersion: String = ExactVideoEncoderQualificationProfile.TARGET_PROFILE_VERSION,
    val buildFingerprint: String = "",
    val mediaRuntimeCompatibilityVersion: String =
        ExactVideoEncoderQualificationProfile.MEDIA_RUNTIME_COMPATIBILITY_VERSION,
) {
    val storageKey: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(
                listOf(
                    qualificationAlgorithmVersion.toString(),
                    probeWorkloadVersion,
                    targetProfileVersion,
                    codecName,
                    mimeType,
                    width.toString(),
                    height.toString(),
                    frameRate.toString(),
                    bitrateBps.toString(),
                    bitrateMode,
                    iFrameIntervalBits.toString(),
                    buildFingerprint,
                    mediaRuntimeCompatibilityVersion,
                ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

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
                buildFingerprint = Build.FINGERPRINT.orEmpty(),
            )
    }
}

/** Explicit cache invalidators for RFC-002B's exact normal-app codec workload. */
internal object ExactVideoEncoderQualificationProfile {
    const val ALGORITHM_VERSION = 1
    const val PROBE_WORKLOAD_VERSION = "rfc002b-exact-lifecycle-v1"
    const val TARGET_PROFILE_VERSION = "avc-1280x720-60-cbr-8mbps-iframe-1-v1"
    const val MEDIA_RUNTIME_COMPATIBILITY_VERSION = "android-mediacodec-v1"
}

internal enum class CbrCapabilityDecisionSource {
    Metadata,
    NotEligible,
    ActiveProbe,
    CurrentProcessProbeCache,
    PersistentProbeCache,
    CurrentProcessQuarantine,
}

internal enum class ExactVideoEncoderCapabilityProbeResult(val code: Int) {
    Supported(0),
    MainThreadRejected(1),
    CodecCreationFailed(2),
    ConfigureFailed(3),
    InputSurfaceFailed(4),
    StartFailed(5),
    ProbeServiceUnavailable(6),
    ProbeProcessDied(7),
    ProbeTimedOut(8),
    ;

    companion object {
        fun fromCode(code: Int): ExactVideoEncoderCapabilityProbeResult =
            fromPersistedCode(code) ?: ProbeServiceUnavailable

        fun fromPersistedCode(code: Int): ExactVideoEncoderCapabilityProbeResult? =
            entries.firstOrNull { it.code == code }
    }
}

internal data class CbrCapabilityDecision(
    val supported: Boolean,
    val source: CbrCapabilityDecisionSource,
    val probeResult: ExactVideoEncoderCapabilityProbeResult? = null,
)

internal interface ExactVideoEncoderCapabilityProbe {
    fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision
}

internal interface ExactVideoEncoderQualificationStore {
    fun read(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult?
    fun write(key: ExactVideoEncoderCapabilityKey, result: ExactVideoEncoderCapabilityProbeResult)
}

internal object NoOpExactVideoEncoderQualificationStore : ExactVideoEncoderQualificationStore {
    override fun read(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult? = null

    override fun write(key: ExactVideoEncoderCapabilityKey, result: ExactVideoEncoderCapabilityProbeResult) = Unit
}

/**
 * App-private exact-key storage for durable positive RFC-002B evidence. A synchronous cold-path
 * commit makes the result available to the next app process before this caller returns.
 */
internal class SharedPreferencesExactVideoEncoderQualificationStore(
    context: Context,
) : ExactVideoEncoderQualificationStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "exact_video_encoder_qualification",
        Context.MODE_PRIVATE,
    )

    override fun read(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult? {
        if (!preferences.contains(key.storageKey)) return null
        val result = runCatching {
            ExactVideoEncoderCapabilityProbeResult.fromPersistedCode(
                preferences.getInt(key.storageKey, Int.MIN_VALUE),
            )
        }.getOrNull()
        if (result?.isPersistableQualificationResult() == true) return result
        preferences.edit().remove(key.storageKey).apply()
        return null
    }

    override fun write(key: ExactVideoEncoderCapabilityKey, result: ExactVideoEncoderCapabilityProbeResult) {
        if (!result.isPersistableQualificationResult()) return
        preferences.edit().putInt(key.storageKey, result.code).commit()
    }

    /** Debug/test tooling removes one hashed exact key without touching pairing or app state. */
    internal fun removeForDebug(key: ExactVideoEncoderCapabilityKey): Boolean =
        preferences.edit().remove(key.storageKey).commit()
}

/**
 * Keeps transient execution outcomes and crash containment local to one caller process while
 * reusing only exact persisted positive evidence across app process restarts.
 */
internal class CachedExactVideoEncoderCapabilityProbe(
    private val delegate: ExactVideoEncoderCapabilityProbe,
    private val store: ExactVideoEncoderQualificationStore = NoOpExactVideoEncoderQualificationStore,
    private val onActiveProbeStarted: () -> Unit = {},
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
    private var processDeathQuarantined = false

    init {
        require(capacity > 0)
    }

    override fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision = synchronized(cache) {
        cache[key]?.let { result ->
            return cachedDecision(result, CbrCapabilityDecisionSource.CurrentProcessProbeCache)
        }

        runCatching { store.read(key) }.getOrNull()?.let { result ->
            cache[key] = result
            return cachedDecision(result, CbrCapabilityDecisionSource.PersistentProbeCache)
        }

        if (processDeathQuarantined) {
            return currentProcessQuarantineDecision(
                key,
                ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied,
            )
        }

        onActiveProbeStarted()
        val decision = delegate.probe(key)
        val result = decision.probeResult
        if (result != null && result != ExactVideoEncoderCapabilityProbeResult.MainThreadRejected) {
            cache[key] = result
            if (result.isPersistableQualificationResult()) {
                runCatching { store.write(key, result) }
            }
            if (result == ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied) {
                processDeathQuarantined = true
            }
        }
        decision
    }

    private fun cachedDecision(
        result: ExactVideoEncoderCapabilityProbeResult,
        source: CbrCapabilityDecisionSource,
    ): CbrCapabilityDecision = CbrCapabilityDecision(
        supported = result == ExactVideoEncoderCapabilityProbeResult.Supported,
        source = source,
        probeResult = result,
    )

    private fun currentProcessQuarantineDecision(
        key: ExactVideoEncoderCapabilityKey,
        result: ExactVideoEncoderCapabilityProbeResult,
    ): CbrCapabilityDecision = cachedDecision(
        result,
        CbrCapabilityDecisionSource.CurrentProcessQuarantine,
    ).also { cache[key] = result }

    private companion object {
        const val DEFAULT_CACHE_CAPACITY = 32
    }
}

private fun ExactVideoEncoderCapabilityProbeResult.isPersistableQualificationResult(): Boolean =
    this == ExactVideoEncoderCapabilityProbeResult.Supported

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
        return decision(codecFactory?.let { ExactFormatEncoderProbeRunner.run(it) } ?: runExactProbe(key))
    }

    private fun decision(result: ExactVideoEncoderCapabilityProbeResult) = CbrCapabilityDecision(
        supported = result == ExactVideoEncoderCapabilityProbeResult.Supported,
        source = CbrCapabilityDecisionSource.ActiveProbe,
        probeResult = result,
    )
}

/** Shared exact production-format lifecycle used by the disposable app-UID probe process. */
internal fun runExactProbe(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult {
    if (key.mimeType != VideoCodec.Avc.mimeType || key.bitrateMode != VideoBitrateMode.Cbr.name) {
        return ExactVideoEncoderCapabilityProbeResult.ConfigureFailed
    }
    val request = VideoEncoderRequest(
        width = key.width,
        height = key.height,
        frameRate = key.frameRate,
        bitrateBps = key.bitrateBps,
        iFrameIntervalSeconds = Float.fromBits(key.iFrameIntervalBits),
    )
    return ExactFormatEncoderProbeRunner.run {
        MediaCodecExactFormatEncoderProbeCodec(
            MediaCodec.createByCodecName(key.codecName),
            AndroidVideoEncoderFormatFactory.create(request),
        )
    }
}

internal class MediaCodecExactFormatEncoderProbeCodec(
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
