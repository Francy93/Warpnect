package io.warpnect.platform.video.decoder

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import io.warpnect.video.decoder.VideoDecoderCodec
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Runs only inside the private normal-UID decoder-probe process. */
internal fun runLegacyDecoderQualification(
    context: android.content.Context,
    codecName: String,
): LegacyDecoderProbeExecution {
    val fixture = when (val loaded = LegacyDecoderQualificationFixture.load(context)) {
        is FixtureLoadResult.Available -> loaded.fixture
        FixtureLoadResult.IntegrityFailure -> return LegacyDecoderProbeExecution(
            LegacyDecoderProbeResult.FixtureIntegrityFailure,
        )
        FixtureLoadResult.Unavailable -> return LegacyDecoderProbeExecution(LegacyDecoderProbeResult.FixtureUnavailable)
    }
    return ProbeOutputSurface(fixture.width, fixture.height).use { surface ->
        val codec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (_: Throwable) {
            return@use LegacyDecoderProbeExecution(LegacyDecoderProbeResult.NormalRejection)
        }
        try {
            val metrics = DecoderProbeMetrics()
            val codecThread = HandlerThread("WarpnectDecoderProbeCallbacks").apply { start() }
            val codecHandler = Handler(codecThread.looper)
            try {
                codec.setOnFrameRenderedListener(
                    MediaCodec.OnFrameRenderedListener { _, _, _ -> metrics.recordPresentation() },
                    codecHandler,
                )
                try {
                    codec.configure(fixture.toFormat(), surface.surface, null, 0)
                } catch (_: Throwable) {
                    return@use LegacyDecoderProbeExecution(LegacyDecoderProbeResult.ConfigureFailure)
                }
                try {
                    codec.start()
                } catch (_: Throwable) {
                    return@use LegacyDecoderProbeExecution(LegacyDecoderProbeResult.StartFailure)
                }
                val startedAtMs = SystemClock.elapsedRealtime()
                metrics.start(startedAtMs)
                fixture.accessUnits.forEachIndexed { index, unit ->
                    if (!queueAccessUnit(codec, unit, metrics)) {
                        return@use metrics.execution(LegacyDecoderProbeResult.InsufficientPerformance)
                    }
                    drain(codec, metrics)
                    waitForPacing(startedAtMs, index + 1)
                    if (SystemClock.elapsedRealtime() - startedAtMs > EXECUTION_DEADLINE_MS) {
                        return@use metrics.execution(LegacyDecoderProbeResult.InsufficientPerformance)
                    }
                }
                if (!queueEndOfStream(codec, metrics)) {
                    return@use metrics.execution(LegacyDecoderProbeResult.InsufficientPerformance)
                }
                while (!metrics.endOfStream && SystemClock.elapsedRealtime() - startedAtMs <= EXECUTION_DEADLINE_MS) {
                    drain(codec, metrics)
                    if (!metrics.endOfStream) SystemClock.sleep(1)
                }
                if (!metrics.endOfStream) return@use metrics.execution(LegacyDecoderProbeResult.InsufficientPerformance)

                val presentationDeadlineMs = startedAtMs + EXECUTION_DEADLINE_MS
                while (metrics.presentations.get() < MIN_PRESENTATIONS &&
                    SystemClock.elapsedRealtime() < presentationDeadlineMs
                ) {
                    SystemClock.sleep(1)
                }
                val result = if (metrics.meetsAcceptedThresholds()) {
                    LegacyDecoderProbeResult.Pass
                } else {
                    LegacyDecoderProbeResult.InsufficientPerformance
                }
                metrics.execution(result)
            } finally {
                runCatching { codec.setOnFrameRenderedListener(null, null) }
                runCatching { codec.stop() }
                codecThread.quitSafely()
            }
        } catch (_: Throwable) {
            LegacyDecoderProbeExecution(LegacyDecoderProbeResult.NormalRejection)
        } finally {
            runCatching { codec.release() }
        }
    }
}

private fun queueAccessUnit(
    codec: MediaCodec,
    accessUnit: LegacyDecoderQualificationFixture.AccessUnit,
    metrics: DecoderProbeMetrics,
): Boolean {
    val waitStartedMs = SystemClock.elapsedRealtime()
    var inputIndex = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
    while (inputIndex < 0 && SystemClock.elapsedRealtime() - waitStartedMs < INPUT_WAIT_LIMIT_MS) {
        drain(codec, metrics)
        inputIndex = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
    }
    if (inputIndex < 0) return false
    metrics.recordInputWait(SystemClock.elapsedRealtime() - waitStartedMs)
    val input = codec.getInputBuffer(inputIndex) ?: return false
    if (accessUnit.bytes.size > input.capacity()) return false
    input.clear()
    input.put(accessUnit.bytes)
    codec.queueInputBuffer(
        inputIndex,
        0,
        accessUnit.bytes.size,
        accessUnit.presentationTimeUs,
        accessUnit.flags,
    )
    metrics.inputs += 1
    return true
}

private fun queueEndOfStream(codec: MediaCodec, metrics: DecoderProbeMetrics): Boolean {
    val waitStartedMs = SystemClock.elapsedRealtime()
    var inputIndex = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
    while (inputIndex < 0 && SystemClock.elapsedRealtime() - waitStartedMs < INPUT_WAIT_LIMIT_MS) {
        drain(codec, metrics)
        inputIndex = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
    }
    if (inputIndex < 0) return false
    metrics.recordInputWait(SystemClock.elapsedRealtime() - waitStartedMs)
    codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    return true
}

private fun drain(codec: MediaCodec, metrics: DecoderProbeMetrics) {
    val info = MediaCodec.BufferInfo()
    while (true) {
        when (val index = codec.dequeueOutputBuffer(info, 0L)) {
            MediaCodec.INFO_TRY_AGAIN_LATER,
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            -> return
            else -> if (index >= 0) {
                val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) metrics.recordOutput()
                codec.releaseOutputBuffer(index, info.size > 0)
                if (endOfStream) {
                    metrics.endOfStream = true
                    return
                }
            }
        }
    }
}

private fun waitForPacing(startedAtMs: Long, submittedFrames: Int) {
    val targetMs = startedAtMs + submittedFrames * 1_000L / LegacyDecoderQualificationFixture.FRAME_RATE
    while (SystemClock.elapsedRealtime() < targetMs) SystemClock.sleep(1)
}

internal data class LegacyDecoderProbeExecution(
    val result: LegacyDecoderProbeResult,
    val metrics: LegacyDecoderProbeMetrics? = null,
)

/** Structural diagnostics only; no frame payload or screen content is retained. */
internal data class LegacyDecoderProbeMetrics(
    val inputs: Int,
    val outputs: Int,
    val presentations: Int,
    val maxInputWaitMs: Long,
    val maxOutputGapMs: Long,
    val maxPresentationGapMs: Long,
    val endOfStream: Boolean,
    val elapsedMs: Long,
)

private class DecoderProbeMetrics {
    var inputs = 0
    var outputs = 0
    var endOfStream = false
    val presentations = AtomicInteger(0)
    private val maxInputWaitMs = AtomicLong(0L)
    private val maxOutputGapMs = AtomicLong(0L)
    private val maxPresentationGapMs = AtomicLong(0L)
    private val lastOutputMs = AtomicLong(-1L)
    private val lastPresentationMs = AtomicLong(-1L)
    private var startedAtMs = -1L

    fun start(startedAtMs: Long) {
        this.startedAtMs = startedAtMs
    }

    fun recordInputWait(waitMs: Long) {
        maxInputWaitMs.updateAndGet { maxOf(it, waitMs) }
    }

    fun recordOutput() {
        val nowMs = SystemClock.elapsedRealtime()
        recordGap(lastOutputMs, maxOutputGapMs, nowMs)
        outputs += 1
    }

    fun recordPresentation() {
        val nowMs = SystemClock.elapsedRealtime()
        recordGap(lastPresentationMs, maxPresentationGapMs, nowMs)
        presentations.incrementAndGet()
    }

    fun meetsAcceptedThresholds(): Boolean = snapshot().let { metrics ->
        inputs == LegacyDecoderQualificationFixture.ACCESS_UNIT_COUNT &&
            outputs == LegacyDecoderQualificationFixture.ACCESS_UNIT_COUNT &&
            metrics.presentations >= MIN_PRESENTATIONS &&
            metrics.endOfStream &&
            metrics.maxInputWaitMs < INPUT_WAIT_LIMIT_MS &&
            metrics.maxOutputGapMs <= MAX_GAP_MS &&
            metrics.maxPresentationGapMs <= MAX_GAP_MS &&
            startedAtMs >= 0L &&
            metrics.elapsedMs <= EXECUTION_DEADLINE_MS
    }

    fun execution(result: LegacyDecoderProbeResult): LegacyDecoderProbeExecution =
        LegacyDecoderProbeExecution(result, snapshot())

    private fun snapshot(): LegacyDecoderProbeMetrics = LegacyDecoderProbeMetrics(
        inputs = inputs,
        outputs = outputs,
        presentations = presentations.get(),
        maxInputWaitMs = maxInputWaitMs.get(),
        maxOutputGapMs = maxOutputGapMs.get(),
        maxPresentationGapMs = maxPresentationGapMs.get(),
        endOfStream = endOfStream,
        elapsedMs = if (startedAtMs < 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs,
    )

    private fun recordGap(last: AtomicLong, maximum: AtomicLong, nowMs: Long) {
        val previous = last.getAndSet(nowMs)
        if (previous >= 0L) maximum.updateAndGet { maxOf(it, nowMs - previous) }
    }
}

private sealed interface FixtureLoadResult {
    data class Available(val fixture: LegacyDecoderQualificationFixture) : FixtureLoadResult
    data object IntegrityFailure : FixtureLoadResult
    data object Unavailable : FixtureLoadResult
}

private data class LegacyDecoderQualificationFixture(
    val width: Int,
    val height: Int,
    val codecSpecificData: List<ByteArray>,
    val accessUnits: List<AccessUnit>,
) {
    data class AccessUnit(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    fun toFormat(): MediaFormat = MediaFormat.createVideoFormat(VideoDecoderCodec.Avc.mimeType, width, height).apply {
        codecSpecificData.forEachIndexed { index, bytes ->
            setByteBuffer(
                "csd-$index",
                java.nio.ByteBuffer.wrap(bytes),
            )
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, accessUnits.maxOf { it.bytes.size })
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val ACCESS_UNIT_COUNT = 360
        private const val MAGIC = 0x574E4932
        private const val VERSION = 2
        private const val ASSET_PATH = "video/rfc002i-avc-720p60-full-v2.fixture"

        fun load(context: android.content.Context): FixtureLoadResult {
            val bytes = runCatching { context.assets.open(ASSET_PATH).use { it.readBytes() } }.getOrNull()
                ?: return FixtureLoadResult.Unavailable
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
            if (digest != LegacyDecoderQualificationProfile.FIXTURE_SHA256.lowercase()) {
                return FixtureLoadResult.IntegrityFailure
            }
            return runCatching { FixtureLoadResult.Available(parse(bytes)) }.getOrElse { FixtureLoadResult.Unavailable }
        }

        private fun parse(bytes: ByteArray): LegacyDecoderQualificationFixture =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == VERSION)
                require(input.readInt() == WIDTH)
                require(input.readInt() == HEIGHT)
                require(input.readInt() == FRAME_RATE)
                input.readInt() // Observed AVC profile IDC; fixture metadata, not a negotiated contract.
                input.readInt() // Observed AVC level IDC; fixture metadata, not a negotiated contract.
                val csdCount = input.readInt()
                require(csdCount in 1..4)
                val csd = List(csdCount) {
                    val size = input.readInt()
                    require(size in 1..MAX_ENTRY_BYTES)
                    ByteArray(size).also(input::readFully)
                }
                val accessUnitCount = input.readInt()
                require(accessUnitCount == ACCESS_UNIT_COUNT)
                val units = List(accessUnitCount) {
                    val size = input.readInt()
                    require(size in 1..MAX_ENTRY_BYTES)
                    val presentationTimeUs = input.readLong()
                    val flags = input.readInt()
                    AccessUnit(ByteArray(size).also(input::readFully), presentationTimeUs, flags)
                }
                require(input.available() == 0)
                LegacyDecoderQualificationFixture(WIDTH, HEIGHT, csd, units)
            }

        private const val MAX_ENTRY_BYTES = 1_048_576
    }
}

private class ProbeOutputSurface(
    width: Int,
    height: Int,
) : AutoCloseable {
    private val thread = HandlerThread("WarpnectDecoderProbeSurface").apply { start() }
    private val initialized = CountDownLatch(1)
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var eglSurface: EGLSurface
    private lateinit var texture: SurfaceTexture
    lateinit var surface: Surface
        private set

    init {
        Handler(thread.looper).post {
            runCatching {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY)
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1))
                val config = chooseConfig(display)
                context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
                eglSurface = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0,
                )
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                texture = SurfaceTexture(textures[0]).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ runCatching { updateTexImage() } }, Handler(thread.looper))
                }
                surface = Surface(texture)
            }
            initialized.countDown()
        }
        check(initialized.await(SURFACE_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        check(::surface.isInitialized)
    }

    override fun close() {
        val complete = CountDownLatch(1)
        Handler(thread.looper).post {
            runCatching { surface.release() }
            runCatching { texture.release() }
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
            complete.countDown()
            thread.quitSafely()
        }
        complete.await(SURFACE_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                1,
                count,
                0,
            ),
        )
        return requireNotNull(configs[0])
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private const val OUTPUT_WAIT_US = 1_000L
private const val INPUT_WAIT_LIMIT_MS = 1_000L
private const val EXECUTION_DEADLINE_MS = 6_500L
private const val MIN_PRESENTATIONS = 342
private const val MAX_GAP_MS = 125L
private const val SURFACE_INIT_TIMEOUT_MS = 1_000L
