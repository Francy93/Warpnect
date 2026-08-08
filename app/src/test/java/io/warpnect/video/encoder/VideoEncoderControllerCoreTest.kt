package io.warpnect.video.encoder

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEncoderControllerCoreTest {
    private var nowUs = 1_000L
    private val request = VideoEncoderRequest(
        width = 1280,
        height = 720,
        frameRate = 60,
        bitrateBps = 8_000_000,
        iFrameIntervalSeconds = 1f,
    )

    @Test
    fun successfulPrepareStartStopLifecycle() {
        val core = VideoEncoderControllerCore { nowUs }
        val capabilities = supportedCapabilities()

        assertEquals(VideoEncoderError.None, core.beginPrepare(request))
        nowUs += 250
        core.completePrepare(VideoEncoderError.None, capabilities)
        assertEquals(VideoEncoderState.Prepared, core.snapshot().state)
        assertEquals(250L, core.snapshot().prepareLatencyUs)

        assertEquals(VideoEncoderError.None, core.beginStart())
        core.completeStart(VideoEncoderError.None)
        assertEquals(VideoEncoderState.Running, core.snapshot().state)

        core.beginStop()
        assertEquals(VideoEncoderState.Draining, core.snapshot().state)
        core.completeStop(VideoEncoderError.None)
        assertEquals(VideoEncoderState.Stopped, core.snapshot().state)
    }

    @Test
    fun prepareRollbackLeavesError() {
        val core = VideoEncoderControllerCore { nowUs }

        assertEquals(VideoEncoderError.None, core.beginPrepare(request))
        core.completePrepare(VideoEncoderError.CodecConfigurationFailed, null)

        assertEquals(VideoEncoderState.Error, core.snapshot().state)
        assertEquals(VideoEncoderError.CodecConfigurationFailed, core.snapshot().lastError)
    }

    @Test
    fun duplicatePrepareIsRejected() {
        val core = VideoEncoderControllerCore { nowUs }
        core.beginPrepare(request)
        core.completePrepare(VideoEncoderError.None, supportedCapabilities())

        val duplicate = core.beginPrepare(request)

        assertEquals(VideoEncoderError.AlreadyPrepared, duplicate)
        assertEquals(VideoEncoderState.Prepared, core.snapshot().state)
    }

    @Test
    fun duplicateStartIsRejected() {
        val core = runningCore()

        val duplicate = core.beginStart()

        assertEquals(VideoEncoderError.AlreadyRunning, duplicate)
        assertEquals(VideoEncoderState.Running, core.snapshot().state)
    }

    @Test
    fun stopWhileStoppedIsNoOp() {
        val core = VideoEncoderControllerCore { nowUs }

        assertEquals(VideoEncoderError.None, core.beginStop())
        core.completeStop(VideoEncoderError.None)

        assertEquals(VideoEncoderState.Stopped, core.snapshot().state)
    }

    @Test
    fun formatChangeRecordsCsdMetadataAndReorderDepth() {
        val core = runningCore()
        val format = VideoEncoderOutputFormat(
            codec = VideoCodec.Avc,
            mimeType = "video/avc",
            width = 1280,
            height = 720,
            frameRate = 60,
            bitrateBps = 8_000_000,
            profile = 1,
            level = 512,
            outputReorderDepth = 0,
            reportedLatencyFrames = 1,
            codecSpecificData = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)),
        )

        val error = core.recordOutputFormat(format)

        assertEquals(VideoEncoderError.None, error)
        assertEquals(1, core.snapshot().outputFormatChanges)
        assertEquals(1, core.snapshot().reportedLatencyFrames)
        assertEquals(0, core.snapshot().outputReorderDepth)
    }

    @Test
    fun unexpectedOutputReorderDepthIsExplicit() {
        val core = runningCore()

        val error = core.recordOutputFormat(
            VideoEncoderOutputFormat(
                codec = VideoCodec.Avc,
                mimeType = "video/avc",
                width = 1280,
                height = 720,
                frameRate = null,
                bitrateBps = null,
                profile = null,
                level = null,
                outputReorderDepth = 2,
                reportedLatencyFrames = null,
                codecSpecificData = emptyList(),
            ),
        )

        assertEquals(VideoEncoderError.UnexpectedOutputReordering, error)
        assertEquals(VideoEncoderState.Error, core.snapshot().state)
    }

    @Test
    fun codecConfigAndKeyframeOutputCountersAreTracked() {
        val core = runningCore()

        core.recordCodecConfig(11)
        core.recordAccessUnit(size = 100, presentationTimeUs = 10_000, keyFrame = true)
        core.recordAccessUnit(size = 50, presentationTimeUs = 20_000, keyFrame = false)

        assertEquals(1L, core.snapshot().codecConfigBuffers)
        assertEquals(2L, core.snapshot().accessUnitsEncoded)
        assertEquals(161L, core.snapshot().encodedBytes)
        assertEquals(1L, core.snapshot().keyFramesEncoded)
        assertEquals(20_000L, core.snapshot().lastPresentationTimeUs)
    }

    @Test
    fun ptsRegressionIsReportedAsReordering() {
        val core = runningCore()

        core.recordAccessUnit(size = 10, presentationTimeUs = 20_000, keyFrame = false)
        core.recordAccessUnit(size = 10, presentationTimeUs = 10_000, keyFrame = false)

        assertEquals(VideoEncoderState.Error, core.snapshot().state)
        assertEquals(VideoEncoderError.UnexpectedOutputReordering, core.snapshot().lastError)
    }

    @Test
    fun keyframeRequestRequiresRunningState() {
        val core = VideoEncoderControllerCore { nowUs }

        assertEquals(VideoEncoderError.NotRunning, core.canRequestKeyFrame())

        val running = runningCore()
        assertEquals(VideoEncoderError.None, running.canRequestKeyFrame())
    }

    @Test
    fun bitrateUpdateValidatesRunningStateAndRange() {
        val core = runningCore()

        assertEquals(VideoEncoderError.UnsupportedBitrate, core.canUpdateBitrate(0, 1, 10))
        assertEquals(VideoEncoderError.UnsupportedBitrate, core.canUpdateBitrate(11, 1, 10))
        assertEquals(VideoEncoderError.None, core.canUpdateBitrate(5, 1, 10))

        core.recordBitrate(5)
        assertEquals(5, core.snapshot().targetBitrateBps)
    }

    @Test
    fun codecErrorDiagnosticsAreStored() {
        val core = runningCore()

        core.fail(
            error = VideoEncoderError.CodecRuntimeError,
            diagnosticInfo = "codec-error",
            recoverable = true,
            transient = false,
        )

        assertEquals(VideoEncoderState.Error, core.snapshot().state)
        assertEquals("codec-error", core.snapshot().lastCodecDiagnosticInfo)
        assertEquals(true, core.snapshot().lastCodecErrorRecoverable)
        assertEquals(false, core.snapshot().lastCodecErrorTransient)
    }

    @Test
    fun drainTimeoutIsExplicit() {
        val core = runningCore()

        core.beginStop()
        core.completeStop(VideoEncoderError.DrainTimeout)

        assertEquals(VideoEncoderState.Error, core.snapshot().state)
        assertEquals(VideoEncoderError.DrainTimeout, core.snapshot().lastError)
    }

    private fun runningCore(): VideoEncoderControllerCore {
        val core = VideoEncoderControllerCore { nowUs }
        core.beginPrepare(request)
        core.completePrepare(VideoEncoderError.None, supportedCapabilities())
        core.beginStart()
        core.completeStart(VideoEncoderError.None)
        return core
    }

    private fun supportedCapabilities(): VideoEncoderCapabilities = VideoEncoderCapabilities(
        request = request,
        selectedCodec = VideoEncoderCodecInfo(
            codecName = "hardware-avc",
            canonicalName = "hardware-avc",
            hardwareAcceleration = VideoEncoderHardwareAcceleration.Hardware,
            softwareOnly = false,
            vendor = true,
            alias = false,
        ),
        support = VideoEncoderSupport(
            widthSupported = true,
            heightSupported = true,
            sizeSupported = true,
            sizeAndRateSupported = true,
            bitrateSupported = true,
            bitrateModeSupported = true,
            surfaceInputSupported = true,
            widthAlignment = 2,
            heightAlignment = 2,
            minWidth = 16,
            maxWidth = 4096,
            minHeight = 16,
            maxHeight = 4096,
            minBitrateBps = 1,
            maxBitrateBps = 100_000_000,
        ),
    )
}
