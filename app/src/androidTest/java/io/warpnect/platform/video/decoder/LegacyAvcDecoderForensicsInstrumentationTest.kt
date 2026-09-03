package io.warpnect.platform.video.decoder

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.encoder.SyntheticEglSurfaceProducer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only RFC-002I instrumentation for collecting MediaCodec evidence on API levels where the
 * framework does not expose hardware classification. It intentionally does not decide eligibility.
 */
@RunWith(AndroidJUnit4::class)
class LegacyAvcDecoderForensicsInstrumentationTest {
    @Test
    fun inventoryAvcDecodersAtWarpnectProfile() {
        val requiredFormat = MediaFormat.createVideoFormat(AVC_MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        }
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } }
            .toList()

        assertTrue("No regular AVC decoder candidates", candidates.isNotEmpty())
        println(
            "RFC002I_DECODER_FORENSICS profile=${WIDTH}x$HEIGHT@$FRAME_RATE " +
                "api=${Build.VERSION.SDK_INT} candidate_count=${candidates.size}",
        )
        candidates.forEach { info -> printCandidate(info, requiredFormat) }

        val currentSelection = AndroidVideoDecoderDiscovery().query(
            VideoDecoderConfig(
                width = WIDTH,
                height = HEIGHT,
                expectedFrameRate = FRAME_RATE,
                configGeneration = 1,
                codecSpecificData = listOf(byteArrayOf(1)),
            ),
        )
        println(
            "RFC002I_WARPNNECT_SELECTION error=${currentSelection.error} " +
                "selected=${currentSelection.selectedCodec?.codecName ?: "none"}",
        )
    }

    @Test
    fun activeSurfaceDecodeReportsWarpnectProfilePacing() {
        val decoderInfo = requestedDecoder() ?: avcDecoders().firstOrNull { info ->
            isNotSoftwareFamily(info.name) && supportsProfile(info)
        }
        assumeTrue("No non-software-family AVC decoder supports the test profile", decoderInfo != null)
        val fixture = fixtureFromInstrumentationArgument() ?: run {
            val encoderInfo = fixtureEncoder() ?: error("No surface AVC encoder can create the temporary test stream")
            encodeTemporaryAvcFixture(encoderInfo)
        }
        FixtureOutputSurface(WIDTH, HEIGHT).use { output ->
            val results = decodeToSurface(
                decoderInfo = requireNotNull(decoderInfo),
                fixture = fixture,
                output = output,
            )
            assertTrue("Decoder did not release any output to its Surface", results.outputFrames > 0)
            assertTrue("Decoder did not present a Surface frame", output.awaitFrame(FIRST_FRAME_TIMEOUT_MS))
            val firstSurfaceMs = output.firstFrameMs.get().let { timestampMs ->
                if (timestampMs >= 0L) timestampMs - results.startedAtMs else null
            }
            println(
                "RFC002I_ACTIVE_DECODE decoder=${decoderInfo.name} " +
                    "configured=true started=true inputs=${fixture.accessUnits.size} " +
                    "outputs=${results.outputFrames} surface_frames=${output.frameCount.get()} " +
                    "first_output_ms=${results.firstOutputMs} first_surface_ms=$firstSurfaceMs " +
                    "input_stalls=${results.inputStalls} max_input_wait_ms=${results.maxInputWaitMs} " +
                    "source_bytes=${fixture.byteCount} source_target_bps=$TARGET_BITRATE_BPS " +
                    "source_actual_bps=${fixture.actualBitrateBps()} " +
                    "eos=${results.endOfStream} elapsed_ms=${results.elapsedMs} " +
                    "test_process_cpu_ms=${results.processCpuMs}",
            )
        }
    }

    @Test
    fun createTemporaryAvcFixture() {
        val encoderInfo = fixtureEncoder()
        assumeTrue("No surface AVC encoder can create the temporary test stream", encoderInfo != null)
        val fixture = encodeTemporaryAvcFixture(requireNotNull(encoderInfo))
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(targetContext.externalCacheDir ?: targetContext.cacheDir, FIXTURE_FILE_NAME)
        destination.outputStream().buffered().use { stream ->
            DataOutputStream(stream).use { output -> writeFixture(output, fixture) }
        }
        println(
            "RFC002I_FIXTURE_CREATED name=${destination.name} bytes=${destination.length()} " +
                "source_bytes=${fixture.byteCount} source_target_bps=$TARGET_BITRATE_BPS",
        )
    }

    private fun avcDecoders(): List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .filter { !it.isEncoder }
        .filter { info -> info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } }

    private fun fixtureEncoder(): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .firstOrNull { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } &&
                supportsSurfaceInputProfile(info)
        }

    private fun fixtureFromInstrumentationArgument(): EncodedFixture? {
        val path = InstrumentationRegistry.getArguments().getString(FIXTURE_PATH_ARGUMENT) ?: return null
        return DataInputStream(File(path).inputStream().buffered()).use(::readFixture)
    }

    private fun requestedDecoder(): MediaCodecInfo? {
        val name = InstrumentationRegistry.getArguments().getString(DECODER_NAME_ARGUMENT) ?: return null
        return avcDecoders().firstOrNull { it.name == name && supportsProfile(it) }
    }

    private fun supportsProfile(info: MediaCodecInfo): Boolean {
        val capabilities = runCatching { info.getCapabilitiesForType(AVC_MIME) }.getOrNull() ?: return false
        val video = capabilities.videoCapabilities
        return runCatching {
            video.isSizeSupported(WIDTH, HEIGHT) &&
                video.areSizeAndRateSupported(WIDTH, HEIGHT, FRAME_RATE.toDouble())
        }.getOrDefault(false)
    }

    private fun supportsSurfaceInputProfile(info: MediaCodecInfo): Boolean {
        val capabilities = runCatching { info.getCapabilitiesForType(AVC_MIME) }.getOrNull() ?: return false
        if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) return false
        return supportsProfile(info)
    }

    private fun encodeTemporaryAvcFixture(encoderInfo: MediaCodecInfo): EncodedFixture {
        val codec = MediaCodec.createByCodecName(encoderInfo.name)
        var inputSurface: Surface? = null
        try {
            codec.configure(
                MediaFormat.createVideoFormat(AVC_MIME, WIDTH, HEIGHT).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE_BPS)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            inputSurface = codec.createInputSurface()
            codec.start()

            val accessUnits = mutableListOf<EncodedAccessUnit>()
            var format: MediaFormat? = null
            SyntheticEglSurfaceProducer(requireNotNull(inputSurface), WIDTH, HEIGHT).use { producer ->
                val startedAtMs = SystemClock.elapsedRealtime()
                repeat(TEST_FRAME_COUNT) { frame ->
                    producer.drawFrame(frame, frame * FRAME_INTERVAL_US)
                    drainEncoder(codec, accessUnits) { outputFormat -> format = outputFormat }
                    waitForFramePacing(startedAtMs, frame + 1)
                }
            }
            codec.signalEndOfInputStream()
            drainEncoderUntilEndOfStream(codec, accessUnits) { outputFormat -> format = outputFormat }
            val outputFormat = requireNotNull(format) { "Encoder did not emit an output format" }
            return EncodedFixture(
                codecSpecificData = codecSpecificData(outputFormat),
                accessUnits = accessUnits,
                byteCount = accessUnits.sumOf { it.bytes.size.toLong() },
            )
        } finally {
            inputSurface?.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        accessUnits: MutableList<EncodedAccessUnit>,
        onFormat: (MediaFormat) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                else -> if (index >= 0) {
                    copyEncoderOutput(codec, index, info, accessUnits)
                }
            }
        }
    }

    private fun drainEncoderUntilEndOfStream(
        codec: MediaCodec,
        accessUnits: MutableList<EncodedAccessUnit>,
        onFormat: (MediaFormat) -> Unit,
    ) {
        val deadlineMs = SystemClock.elapsedRealtime() + CODEC_DRAIN_TIMEOUT_MS
        val info = MediaCodec.BufferInfo()
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            when (val index = codec.dequeueOutputBuffer(info, OUTPUT_WAIT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                else -> if (index >= 0) {
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    copyEncoderOutput(codec, index, info, accessUnits)
                    if (endOfStream) return
                }
            }
        }
        error("Encoder did not drain before timeout")
    }

    private fun copyEncoderOutput(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        accessUnits: MutableList<EncodedAccessUnit>,
    ) {
        try {
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val output = requireNotNull(codec.getOutputBuffer(index)).duplicate().apply {
                    position(info.offset)
                    limit(info.offset + info.size)
                }
                val bytes = ByteArray(info.size)
                output.get(bytes)
                accessUnits += EncodedAccessUnit(bytes, info.presentationTimeUs, info.flags)
            }
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun decodeToSurface(
        decoderInfo: MediaCodecInfo,
        fixture: EncodedFixture,
        output: FixtureOutputSurface,
    ): DecodeResults {
        val codec = MediaCodec.createByCodecName(decoderInfo.name)
        try {
            codec.configure(
                MediaFormat.createVideoFormat(AVC_MIME, WIDTH, HEIGHT).apply {
                    fixture.codecSpecificData.forEachIndexed { index, bytes ->
                        setByteBuffer("csd-$index", java.nio.ByteBuffer.wrap(bytes))
                    }
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, fixture.accessUnits.maxOf { it.bytes.size })
                },
                output.surface,
                null,
                0,
            )
            codec.start()
            val startedAtMs = SystemClock.elapsedRealtime()
            val processCpuStartedMs = Process.getElapsedCpuTime()
            val results = DecodeAccumulator(startedAtMs)
            fixture.accessUnits.forEachIndexed { frame, unit ->
                queueDecoderInput(codec, unit, results)
                drainDecoder(codec, results)
                waitForFramePacing(startedAtMs, frame + 1)
            }
            queueDecoderEndOfStream(codec, results)
            drainDecoderUntilEndOfStream(codec, results)
            return results.freeze(
                finishedAtMs = SystemClock.elapsedRealtime(),
                processCpuMs = Process.getElapsedCpuTime() - processCpuStartedMs,
            )
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun queueDecoderInput(codec: MediaCodec, unit: EncodedAccessUnit, results: DecodeAccumulator) {
        val waitStartedMs = SystemClock.elapsedRealtime()
        val deadlineMs = waitStartedMs + INPUT_WAIT_TIMEOUT_MS
        var index = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
        while (index < 0 && SystemClock.elapsedRealtime() < deadlineMs) {
            drainDecoder(codec, results)
            index = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
        }
        check(index >= 0) { "Decoder input buffer did not become available" }
        val waitedMs = SystemClock.elapsedRealtime() - waitStartedMs
        results.recordInputWait(waitedMs)
        val input = requireNotNull(codec.getInputBuffer(index))
        check(unit.bytes.size <= input.capacity()) { "Decoder input buffer is too small" }
        input.clear()
        input.put(unit.bytes)
        codec.queueInputBuffer(index, 0, unit.bytes.size, unit.presentationTimeUs, unit.flags)
    }

    private fun queueDecoderEndOfStream(codec: MediaCodec, results: DecodeAccumulator) {
        val deadlineMs = SystemClock.elapsedRealtime() + INPUT_WAIT_TIMEOUT_MS
        var index = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
        while (index < 0 && SystemClock.elapsedRealtime() < deadlineMs) {
            drainDecoder(codec, results)
            index = codec.dequeueInputBuffer(OUTPUT_WAIT_US)
        }
        check(index >= 0) { "Decoder input buffer unavailable for end of stream" }
        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainDecoder(codec: MediaCodec, results: DecodeAccumulator) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                -> return
                else -> if (index >= 0) {
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) results.recordOutput(SystemClock.elapsedRealtime())
                    codec.releaseOutputBuffer(index, info.size > 0)
                    if (endOfStream) {
                        results.endOfStream = true
                        return
                    }
                }
            }
        }
    }

    private fun drainDecoderUntilEndOfStream(codec: MediaCodec, results: DecodeAccumulator) {
        val deadlineMs = SystemClock.elapsedRealtime() + CODEC_DRAIN_TIMEOUT_MS
        while (!results.endOfStream && SystemClock.elapsedRealtime() < deadlineMs) {
            drainDecoder(codec, results)
            if (!results.endOfStream) SystemClock.sleep(1)
        }
        check(results.endOfStream) { "Decoder did not drain before timeout" }
    }

    private fun codecSpecificData(format: MediaFormat): List<ByteArray> = buildList {
        var index = 0
        while (format.containsKey("csd-$index")) {
            val buffer = requireNotNull(format.getByteBuffer("csd-$index")).duplicate()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            add(bytes)
            index += 1
        }
    }.also { require(it.isNotEmpty()) { "Encoder output format did not include CSD" } }

    private fun writeFixture(output: DataOutputStream, fixture: EncodedFixture) {
        output.writeInt(FIXTURE_MAGIC)
        output.writeInt(fixture.codecSpecificData.size)
        fixture.codecSpecificData.forEach { bytes ->
            output.writeInt(bytes.size)
            output.write(bytes)
        }
        output.writeInt(fixture.accessUnits.size)
        fixture.accessUnits.forEach { accessUnit ->
            output.writeInt(accessUnit.bytes.size)
            output.writeLong(accessUnit.presentationTimeUs)
            output.writeInt(accessUnit.flags)
            output.write(accessUnit.bytes)
        }
    }

    private fun readFixture(input: DataInputStream): EncodedFixture {
        require(input.readInt() == FIXTURE_MAGIC) { "Unsupported temporary AVC fixture" }
        val codecSpecificData = List(input.readInt()) {
            ByteArray(input.readInt()).also(input::readFully)
        }
        val accessUnits = List(input.readInt()) {
            val size = input.readInt()
            val presentationTimeUs = input.readLong()
            val flags = input.readInt()
            EncodedAccessUnit(
                bytes = ByteArray(size).also(input::readFully),
                presentationTimeUs = presentationTimeUs,
                flags = flags,
            )
        }
        require(codecSpecificData.isNotEmpty()) { "Temporary AVC fixture has no CSD" }
        require(accessUnits.isNotEmpty()) { "Temporary AVC fixture has no access units" }
        return EncodedFixture(
            codecSpecificData = codecSpecificData,
            accessUnits = accessUnits,
            byteCount = accessUnits.sumOf { it.bytes.size.toLong() },
        )
    }

    private fun waitForFramePacing(startedAtMs: Long, completedFrames: Int) {
        val targetMs = startedAtMs + completedFrames * 1_000L / FRAME_RATE
        val remainingMs = targetMs - SystemClock.elapsedRealtime()
        if (remainingMs > 0L) SystemClock.sleep(remainingMs)
    }

    private fun printCandidate(info: MediaCodecInfo, requiredFormat: MediaFormat) {
        val capabilities = info.getCapabilitiesForType(AVC_MIME)
        val video = capabilities.videoCapabilities
        val legacyNameEvidence = legacyNameEvidence(info.name)
        val frameworkClassification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "hardware=${info.isHardwareAccelerated},software=${info.isSoftwareOnly},vendor=${info.isVendor}"
        } else {
            "unavailable"
        }
        val profileLevels = capabilities.profileLevels.joinToString(",") { "${it.profile}:${it.level}" }
        val colorFormats = capabilities.colorFormats.joinToString(",")
        val supportsSize = safeBoolean { video.isSizeSupported(WIDTH, HEIGHT) }
        val supportsRate = safeBoolean {
            video.areSizeAndRateSupported(WIDTH, HEIGHT, FRAME_RATE.toDouble())
        }
        val supportsFormat = safeBoolean { capabilities.isFormatSupported(requiredFormat) }
        val adaptive = capabilities.isFeatureSupported(
            MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,
        )
        println(
            "RFC002I_AVC_DECODER " +
                "name=${info.name} " +
                "framework_classification=$frameworkClassification " +
                "legacy_name_evidence=$legacyNameEvidence " +
                "width=${video.supportedWidths.lower}..${video.supportedWidths.upper} " +
                "height=${video.supportedHeights.lower}..${video.supportedHeights.upper} " +
                "alignment=${video.widthAlignment}x${video.heightAlignment} " +
                "size_720p=$supportsSize " +
                "rate_720p60=$supportsRate " +
                "format_720p60=$supportsFormat " +
                "adaptive=$adaptive " +
                "profiles=$profileLevels color_formats=$colorFormats",
        )
    }

    private fun legacyNameEvidence(codecName: String): String {
        val name = codecName.lowercase(Locale.US)
        val softwareFamily = name.startsWith("omx.google.") ||
            name.startsWith("omx.ffmpeg.") ||
            (name.startsWith("omx.sec.") && name.contains(".sw.")) ||
            name.startsWith("c2.android.") ||
            name.startsWith("c2.google.") ||
            (!name.startsWith("omx.") && !name.startsWith("c2."))
        return if (softwareFamily) "software_family" else "not_software_family"
    }

    private fun isNotSoftwareFamily(codecName: String): Boolean = legacyNameEvidence(codecName) == "not_software_family"

    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)

    private data class EncodedFixture(
        val codecSpecificData: List<ByteArray>,
        val accessUnits: List<EncodedAccessUnit>,
        val byteCount: Long,
    ) {
        fun actualBitrateBps(): Long = byteCount * 8L * FRAME_RATE / accessUnits.size
    }

    private data class EncodedAccessUnit(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private data class DecodeResults(
        val startedAtMs: Long,
        val outputFrames: Int,
        val firstOutputMs: Long?,
        val inputStalls: Int,
        val maxInputWaitMs: Long,
        val endOfStream: Boolean,
        val elapsedMs: Long,
        val processCpuMs: Long,
    )

    private class DecodeAccumulator(
        private val startedAtMs: Long,
    ) {
        private var outputFrames = 0
        private var firstOutputMs: Long? = null
        private var inputStalls = 0
        private var maxInputWaitMs = 0L
        var endOfStream = false

        fun recordInputWait(waitedMs: Long) {
            if (waitedMs > FRAME_BUDGET_MS) inputStalls += 1
            maxInputWaitMs = maxOf(maxInputWaitMs, waitedMs)
        }

        fun recordOutput(nowMs: Long) {
            outputFrames += 1
            if (firstOutputMs == null) firstOutputMs = nowMs - startedAtMs
        }

        fun freeze(finishedAtMs: Long, processCpuMs: Long): DecodeResults = DecodeResults(
            startedAtMs = startedAtMs,
            outputFrames = outputFrames,
            firstOutputMs = firstOutputMs,
            inputStalls = inputStalls,
            maxInputWaitMs = maxInputWaitMs,
            endOfStream = endOfStream,
            elapsedMs = finishedAtMs - startedAtMs,
            processCpuMs = processCpuMs,
        )
    }

    private class FixtureOutputSurface(
        width: Int,
        height: Int,
    ) : AutoCloseable {
        private val thread = HandlerThread("WarpnectDecoderForensicsSurface").apply { start() }
        private val initialized = CountDownLatch(1)
        private val initializationError = AtomicLong(0L)
        private lateinit var display: EGLDisplay
        private lateinit var context: EGLContext
        private lateinit var eglSurface: EGLSurface
        private lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
            private set
        val frameCount = AtomicInteger(0)
        val firstFrameMs = AtomicLong(-1L)

        init {
            Handler(thread.looper).post {
                runCatching {
                    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    check(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
                    val version = IntArray(2)
                    check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize failed" }
                    val config = chooseEglConfig(display)
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
                    check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL make-current failed" }
                    val textures = IntArray(1)
                    GLES20.glGenTextures(1, textures, 0)
                    texture = SurfaceTexture(textures[0]).apply {
                        setDefaultBufferSize(width, height)
                        setOnFrameAvailableListener({
                            updateTexImage()
                            if (firstFrameMs.compareAndSet(-1L, SystemClock.elapsedRealtime())) Unit
                            frameCount.incrementAndGet()
                        }, Handler(thread.looper))
                    }
                    surface = Surface(texture)
                }.onFailure {
                    initializationError.set(1L)
                }
                initialized.countDown()
            }
            check(
                initialized.await(FIRST_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            ) { "Surface initialization timed out" }
            check(initializationError.get() == 0L) { "Surface initialization failed" }
        }

        fun awaitFrame(timeoutMs: Long): Boolean {
            val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                if (frameCount.get() > 0) return true
                SystemClock.sleep(1)
            }
            return false
        }

        override fun close() {
            val completed = CountDownLatch(1)
            Handler(thread.looper).post {
                runCatching { surface.release() }
                runCatching { texture.release() }
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                }
                runCatching { EGL14.eglDestroySurface(display, eglSurface) }
                runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglTerminate(display) }
                completed.countDown()
                thread.quitSafely()
            }
            completed.await(FIRST_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        private fun chooseEglConfig(display: EGLDisplay): EGLConfig {
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
            ) { "EGL config selection failed" }
            return requireNotNull(configs[0]) { "No EGL config selected" }
        }
    }

    private companion object {
        const val AVC_MIME = "video/avc"
        const val DECODER_NAME_ARGUMENT = "rfc002iDecoderName"
        const val FIXTURE_PATH_ARGUMENT = "rfc002iFixturePath"
        const val FIXTURE_FILE_NAME = "rfc002i-avc-720p60.fixture"
        const val FIXTURE_MAGIC = 0x574E4931
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val TARGET_BITRATE_BPS = 8_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val TEST_FRAME_COUNT = 180
        const val FRAME_INTERVAL_US = 16_667L
        const val FRAME_BUDGET_MS = 17L
        const val OUTPUT_WAIT_US = 10_000L
        const val INPUT_WAIT_TIMEOUT_MS = 1_000L
        const val CODEC_DRAIN_TIMEOUT_MS = 5_000L
        const val FIRST_FRAME_TIMEOUT_MS = 5_000L
    }
}
