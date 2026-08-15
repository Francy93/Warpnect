package io.warpnect.video.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDecoderControllerCoreTest {
    private var nowUs = 1_000L
    private val config = VideoDecoderConfig(
        width = 1280,
        height = 720,
        configGeneration = 1,
        codecSpecificData = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)),
    )

    @Test
    fun successfulPrepareStartStopLifecycle() {
        val core = VideoDecoderControllerCore { nowUs }

        assertEquals(VideoDecoderError.None, core.beginPrepare(config))
        nowUs += 500
        core.completePrepare(VideoDecoderError.None, supportedCapabilities(), lowLatencyRequested = true)
        assertEquals(VideoDecoderState.Prepared, core.snapshot().state)
        assertEquals(500L, core.snapshot().prepareLatencyUs)
        assertEquals(true, core.snapshot().lowLatencyRequested)

        assertEquals(VideoDecoderError.None, core.beginStart())
        core.completeStart(VideoDecoderError.None)
        assertEquals(VideoDecoderState.Running, core.snapshot().state)

        core.beginStop()
        assertEquals(VideoDecoderState.Draining, core.snapshot().state)
        core.completeStop(VideoDecoderError.None)
        assertEquals(VideoDecoderState.Stopped, core.snapshot().state)
    }

    @Test
    fun duplicatePrepareAndStartAreDeterministic() {
        val core = preparedCore()

        assertEquals(VideoDecoderError.AlreadyPrepared, core.beginPrepare(config))
        assertEquals(VideoDecoderError.None, core.beginStart())
        core.completeStart(VideoDecoderError.None)
        assertEquals(VideoDecoderError.AlreadyRunning, core.beginStart())
    }

    @Test
    fun generationMismatchRequiresReconfiguration() {
        val core = runningCore()

        val error = core.validateAccessUnit(
            VideoDecoderInputResult.AccessUnit(
                size = 12,
                presentationTimeUs = 10_000,
                configGeneration = 2,
                frameId = 1,
                isKeyFrame = false,
            ),
            capacity = 64,
        )

        assertEquals(VideoDecoderError.ReconfigurationRequired, error)
    }

    @Test
    fun inputValidationRejectsGenerationZeroAndOversizedAu() {
        val core = runningCore()

        assertEquals(
            VideoDecoderError.InvalidConfigGeneration,
            core.validateAccessUnit(accessUnit(configGeneration = 0), capacity = 64),
        )
        assertEquals(
            VideoDecoderError.InputBufferTooSmall,
            core.validateAccessUnit(accessUnit(size = 128), capacity = 64),
        )
    }

    @Test
    fun inputPtsIsPreservedInSnapshot() {
        val core = runningCore()

        core.recordInputQueued(size = 100, presentationTimeUs = 33_333)

        assertEquals(1L, core.snapshot().accessUnitsQueued)
        assertEquals(100L, core.snapshot().encodedBytesQueued)
        assertEquals(33_333L, core.snapshot().lastInputPtsUs)
    }

    @Test
    fun outputActionsUpdateCounters() {
        val core = runningCore()

        core.recordOutput(DecodedVideoOutputAction.RenderNow, presentationTimeUs = 1)
        core.recordOutput(DecodedVideoOutputAction.Drop, presentationTimeUs = 2)
        core.recordOutput(DecodedVideoOutputAction.RenderAt(timestampNs = 3), presentationTimeUs = 3)

        assertEquals(3L, core.snapshot().decodedOutputBuffers)
        assertEquals(2L, core.snapshot().framesRenderedRequested)
        assertEquals(1L, core.snapshot().framesDropped)
        assertEquals(1L, core.snapshot().framesRenderScheduled)
        assertEquals(3L, core.snapshot().lastOutputPtsUs)
    }

    @Test
    fun codecErrorDiagnosticsAreStored() {
        val core = runningCore()

        core.fail(
            error = VideoDecoderError.CodecRuntimeError,
            diagnosticInfo = "decoder-error",
            recoverable = false,
            transient = true,
        )

        assertEquals(VideoDecoderState.Error, core.snapshot().state)
        assertEquals("decoder-error", core.snapshot().lastCodecDiagnosticInfo)
        assertEquals(false, core.snapshot().lastCodecErrorRecoverable)
        assertEquals(true, core.snapshot().lastCodecErrorTransient)
    }

    @Test
    fun drainTimeoutIsExplicit() {
        val core = runningCore()

        core.beginStop()
        core.completeStop(VideoDecoderError.DrainTimeout)

        assertEquals(VideoDecoderState.Error, core.snapshot().state)
        assertEquals(VideoDecoderError.DrainTimeout, core.snapshot().lastError)
    }

    private fun accessUnit(size: Int = 12, configGeneration: Long = 1): VideoDecoderInputResult.AccessUnit =
        VideoDecoderInputResult.AccessUnit(
            size = size,
            presentationTimeUs = 10_000,
            configGeneration = configGeneration,
            frameId = 1,
            isKeyFrame = false,
        )

    private fun preparedCore(): VideoDecoderControllerCore {
        val core = VideoDecoderControllerCore { nowUs }
        core.beginPrepare(config)
        core.completePrepare(VideoDecoderError.None, supportedCapabilities())
        return core
    }

    private fun runningCore(): VideoDecoderControllerCore {
        val core = preparedCore()
        core.beginStart()
        core.completeStart(VideoDecoderError.None)
        return core
    }

    private fun supportedCapabilities(): VideoDecoderCapabilities = VideoDecoderCapabilities(
        config = config,
        selectedCodec = VideoDecoderCodecInfo(
            codecName = "hardware-avc-decoder",
            canonicalName = "hardware-avc-decoder",
            hardwareAcceleration = VideoDecoderHardwareAcceleration.Hardware,
            softwareOnly = false,
            vendor = true,
            alias = false,
            lowLatencyFeatureSupported = true,
        ),
        support = VideoDecoderSupport(
            widthSupported = true,
            heightSupported = true,
            sizeSupported = true,
            sizeAndRateSupported = true,
            lowLatencyFeatureSupported = true,
            widthAlignment = 2,
            heightAlignment = 2,
            minWidth = 16,
            maxWidth = 4096,
            minHeight = 16,
            maxHeight = 4096,
        ),
    )
}
