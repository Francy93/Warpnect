package io.warpnect.platform.capture.experimental

import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureGeometry
import io.warpnect.capture.CaptureRequest
import io.warpnect.platform.capture.privileged.SurfaceControlDisplayCaptureApi
import io.warpnect.platform.input.injection.ReflectivePrivilegedInputManagerApi
import io.warpnect.platform.video.encoder.AndroidExactVideoEncoderCapabilityProbe
import io.warpnect.platform.video.encoder.AndroidVideoEncoderDiscovery
import io.warpnect.platform.video.encoder.AndroidVideoEncoderFormatFactory
import io.warpnect.platform.video.encoder.ExactVideoEncoderCapabilityKey
import io.warpnect.video.encoder.VideoEncoderRequest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** A new debug-only UserService class forces Shizuku to load the current experimental bytecode. */
class ExperimentalDisplayMirrorUserServiceV2 : IExperimentalDisplayMirrorService.Stub() {
    private val lock = Any()

    override fun runProbe(probeKind: Int): Bundle = synchronized(lock) {
        val kind = ExperimentalDisplayMirrorProbeKind.fromCode(probeKind)
        val result = try {
            when (kind) {
                ExperimentalDisplayMirrorProbeKind.Resolution -> runResolution()
                ExperimentalDisplayMirrorProbeKind.MirrorLifecycle -> runMirrorLifecycle()
                ExperimentalDisplayMirrorProbeKind.EncoderFrame -> runEncoderFrame()
                ExperimentalDisplayMirrorProbeKind.VideoCapability -> runVideoCapability()
                ExperimentalDisplayMirrorProbeKind.InputCapability -> runInputCapability()
                ExperimentalDisplayMirrorProbeKind.LegacyLifecycle -> runLegacyLifecycle()
                ExperimentalDisplayMirrorProbeKind.LegacyCompatibility -> runLegacyCompatibility()
                ExperimentalDisplayMirrorProbeKind.LegacyEncoderFrame -> runLegacyEncoderFrame()
                ExperimentalDisplayMirrorProbeKind.LegacyVbrEncoderFrame -> runLegacyVbrEncoderFrame()
                ExperimentalDisplayMirrorProbeKind.VideoMetadata -> runVideoMetadata()
            }
        } catch (throwable: Throwable) {
            initialResult(kind).failure(Failure.from(throwable))
        }
        result.apply {
            putString(KEY_PROBE_REVISION, PROBE_REVISION)
        }
    }

    /** Reports only the relevant Surface-targeted DisplayManager method shapes. */
    private fun runResolution(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.Resolution)
        inspectModernMirrorMethodShapes(result)
        try {
            resolveMirror(result)
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        }
        return result
    }

    private fun runMirrorLifecycle(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.MirrorLifecycle)
        var reader: ImageReader? = null
        var virtualDisplay: Any? = null
        try {
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, IMAGE_READER_BUFFERS)
            val surface = reader.surface
            result.putBoolean(KEY_SURFACE_ATTACHED, surface.isValid)
            val mirror = resolveMirror(result) ?: return result
            virtualDisplay = invokeMirror(mirror, surface, result)
            result.putBoolean(KEY_CREATE_MIRROR_DISPLAY, virtualDisplay != null)
            if (virtualDisplay == null) return result.failure(Failure.VirtualDisplayCreationFailed)
            result.putBoolean(KEY_MIRROR_LIFECYCLE_SUCCEEDED, true)
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            val virtualDisplayReleased = releaseVirtualDisplay(virtualDisplay)
            val readerReleased = runCatching { reader?.close() }.isSuccess
            result.putBoolean(KEY_RELEASE_SUCCEEDED, virtualDisplayReleased && readerReleased)
            if (!result.getBoolean(KEY_RELEASE_SUCCEEDED) && !result.containsKey(KEY_FAILURE)) {
                result.failure(Failure.ReleaseFailed)
            }
        }
        return result
    }

    private fun runEncoderFrame(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.EncoderFrame)
        val request = videoRequest()
        val capabilities = AndroidVideoEncoderDiscovery().query(request)
        if (!capabilities.isSupported) return result.failure(Failure.EncoderUnavailable)
        val codecName = capabilities.selectedCodec?.codecName ?: return result.failure(Failure.EncoderUnavailable)
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var virtualDisplay: Any? = null
        var codecStarted = false
        try {
            codec = MediaCodec.createByCodecName(codecName)
            result.putString(KEY_ENCODER_STAGE, "configure_started")
            codec.configure(
                AndroidVideoEncoderFormatFactory.create(request),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            result.putBoolean(KEY_ENCODER_CONFIGURED, true)
            result.putString(KEY_ENCODER_STAGE, "input_surface_started")
            inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true
            result.putBoolean(KEY_ENCODER_STARTED, true)

            val mirror = resolveMirror(result) ?: return result
            val captureStartMs = SystemClock.elapsedRealtime()
            virtualDisplay = invokeMirror(mirror, checkNotNull(inputSurface), result)
            result.putBoolean(KEY_CREATE_MIRROR_DISPLAY, virtualDisplay != null)
            result.putBoolean(KEY_MIRROR_CREATED, virtualDisplay != null)
            result.putBoolean(KEY_CAPTURE_STARTED, virtualDisplay != null)
            if (virtualDisplay == null) return result.failure(Failure.VirtualDisplayCreationFailed)

            val bufferInfo = MediaCodec.BufferInfo()
            val deadlineMs = SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val hasFrame = bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    codec.releaseOutputBuffer(index, false)
                    if (hasFrame) {
                        result.putBoolean(KEY_FIRST_REAL_FRAME_ENCODED, true)
                        result.putLong(KEY_FIRST_FRAME_ELAPSED_MS, SystemClock.elapsedRealtime() - captureStartMs)
                        break
                    }
                }
            }
            if (!result.getBoolean(KEY_FIRST_REAL_FRAME_ENCODED, false)) {
                result.failure(Failure.EncoderOutputTimeout)
            }
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            var released = releaseVirtualDisplay(virtualDisplay)
            if (codecStarted) released = runCatching { codec?.stop() }.isSuccess && released
            released = runCatching { inputSurface?.release() }.isSuccess && released
            released = runCatching { codec?.release() }.isSuccess && released
            result.putBoolean(KEY_RELEASE_SUCCEEDED, released)
            if (!released && !result.containsKey(KEY_FAILURE)) result.failure(Failure.ReleaseFailed)
        }
        return result
    }

    /**
     * Inventory only. The exact production discovery query is retained as the authoritative
     * availability result; the raw rows explain vendor metadata without admitting a new profile.
     */
    private fun runVideoCapability(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.VideoCapability)
        try {
            val request = videoRequest()
            val rows = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } }
                .map { info -> rawAvcRow(info, request, allowActiveProbe = true) }
                .toList()
            val capabilities = AndroidVideoEncoderDiscovery().query(request)
            result.putInt(KEY_AVC_ENCODER_COUNT, rows.size)
            result.putString(KEY_AVC_ENCODER_INVENTORY, rows.joinToString("|"))
            result.putBoolean(KEY_VIDEO_ENCODER_AVAILABLE, capabilities.isSupported)
            result.putString(KEY_VIDEO_ENCODER_REASON, capabilities.error.name)
            capabilities.selectedCodec?.let { result.putString(KEY_VIDEO_SELECTED_CODEC, it.codecName) }
        } catch (throwable: Throwable) {
            result.putString(KEY_VIDEO_PROBE_FAILURE, Failure.from(throwable).name)
            result.failure(Failure.from(throwable))
        }
        return result
    }

    /** Reads raw Android metadata only and never instantiates a temporary codec. */
    private fun runVideoMetadata(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.VideoMetadata)
        val request = videoRequest()
        val rows = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } }
            .map { info -> rawAvcRow(info, request, allowActiveProbe = false) }
            .toList()
        result.putInt(KEY_AVC_ENCODER_COUNT, rows.size)
        result.putString(KEY_AVC_ENCODER_INVENTORY, rows.joinToString("|"))
        result.putBoolean(KEY_VIDEO_METADATA_ONLY, true)
        return result
    }

    /** Resolves the existing privileged InputManager bridge without injecting an input event. */
    private fun runInputCapability(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.InputCapability)
        val capabilities = ReflectivePrivilegedInputManagerApi().resolve()
        val available = capabilities.apiResolved &&
            capabilities.asyncInjectionSupported &&
            capabilities.displayTargetingSupported
        result.putBoolean(KEY_INPUT_API_RESOLVED, capabilities.apiResolved)
        result.putBoolean(KEY_INPUT_ASYNC_SUPPORTED, capabilities.asyncInjectionSupported)
        result.putBoolean(KEY_INPUT_DISPLAY_SUPPORTED, capabilities.displayTargetingSupported)
        result.putBoolean(KEY_INPUT_AVAILABLE, available)
        result.putString(
            KEY_INPUT_REASON,
            when {
                available -> "None"
                !capabilities.apiResolved -> capabilities.lastError.name
                !capabilities.asyncInjectionSupported -> "InputApiUnavailable"
                else -> "DisplayTargetingUnsupported"
            },
        )
        return result
    }

    /** Runs the unchanged production legacy SurfaceControl bridge against a temporary Surface. */
    private fun runLegacyLifecycle(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.LegacyLifecycle)
        var reader: ImageReader? = null
        val captureApi = SurfaceControlDisplayCaptureApi()
        try {
            resolveLegacyStructure(result)
            if (!diagnoseLegacyCreateDisplay(result)) return result
            val capabilities = captureApi.queryCapabilities()
            result.putBoolean(KEY_LEGACY_SURFACECONTROL_AVAILABLE, capabilities.backendAvailable)
            result.putString(KEY_LEGACY_RESOLUTION_ERROR, capabilities.lastError.name)
            if (!capabilities.backendAvailable) return result

            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, IMAGE_READER_BUFFERS)
            val start = captureApi.startCapture(CaptureRequest(DISPLAY_ID, WIDTH, HEIGHT), reader.surface)
            result.putString(KEY_LEGACY_START_ERROR, start.name)
            result.putBoolean(KEY_LEGACY_DISPLAY_CREATED, start == CaptureError.None)
            result.putBoolean(KEY_LEGACY_SURFACE_ATTACHED, start == CaptureError.None && reader.surface.isValid)
            if (start == CaptureError.ProjectionConfigurationFailed &&
                result.getString(KEY_LEGACY_CREATE_DISPLAY_OUTCOME) == "TokenReturned"
            ) {
                diagnoseLegacyConfiguration(result, reader.surface)
            }
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            val stopped = runCatching { captureApi.stopCapture() }.getOrNull() == CaptureError.None
            val readerReleased = runCatching { reader?.close() }.isSuccess
            result.putBoolean(KEY_LEGACY_RELEASE_SUCCEEDED, stopped && readerReleased)
        }
        return result
    }

    /**
     * Debug-only comparison of the existing no-transaction invocation with the scrcpy-style
     * transaction ordering. It never starts a Session or retains a display after the probe.
     */
    private fun runLegacyCompatibility(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.LegacyCompatibility)
        var reader: ImageReader? = null
        try {
            val methods = resolveLegacyCompatibilityMethods(result) ?: return result
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, IMAGE_READER_BUFFERS)
            result.putBoolean(KEY_LEGACY_COMPATIBILITY_SURFACE_VALID, reader.surface.isValid)
            result.putBoolean(KEY_LEGACY_COMPATIBILITY_SURFACE_RELEASED, false)
            val displayState = resolveLegacyDisplayState(methods) ?: return result
            val rows = listOf(
                runLegacyCompatibilityVariant(
                    methods = methods,
                    displayState = displayState,
                    surface = reader.surface,
                    secure = true,
                    transaction = false,
                ),
                runLegacyCompatibilityVariant(
                    methods = methods,
                    displayState = displayState,
                    surface = reader.surface,
                    secure = true,
                    transaction = true,
                ),
                runLegacyCompatibilityVariant(
                    methods = methods,
                    displayState = displayState,
                    surface = reader.surface,
                    secure = false,
                    transaction = true,
                ),
            )
            result.putString(KEY_LEGACY_COMPATIBILITY_MATRIX, rows.joinToString("|"))
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            val released = runCatching { reader?.close() }.isSuccess
            result.putBoolean(KEY_LEGACY_COMPATIBILITY_RELEASE_SUCCEEDED, released)
        }
        return result
    }

    /**
     * Frame proof only for the legacy capture bridge. It uses an AVC VBR mode advertised by the
     * local codec and is deliberately separate from Warpnect's strict-CBR production profile.
     */
    private fun runLegacyVbrEncoderFrame(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.LegacyVbrEncoderFrame)
        val methods = resolveLegacyCompatibilityMethods(result) ?: return result
        val displayState = resolveLegacyDisplayState(methods) ?: return result
        val codecInfo = findAdvertisedVbrAvcEncoder() ?: return result.failure(Failure.EncoderUnavailable)
        result.putBoolean(KEY_LEGACY_VBR_ADVERTISED, true)
        result.putBoolean(KEY_CAPTURE_BACKEND_FRAME_PROOF_ONLY, true)
        result.putString(KEY_LEGACY_VBR_CODEC, codecInfo.name)

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var codecStarted = false
        var mirror: LegacyVbrMirror? = null
        try {
            codec = MediaCodec.createByCodecName(codecInfo.name)
            codec.configure(
                legacyVbrFormat(),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            result.putBoolean(KEY_ENCODER_CONFIGURED, true)
            inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true
            result.putBoolean(KEY_ENCODER_STARTED, true)

            val mirrorAttempt = createLegacyVbrMirror(methods, displayState, checkNotNull(inputSurface))
            result.putString(KEY_LEGACY_VBR_MIRROR_OUTCOME, mirrorAttempt.outcome)
            mirror = mirrorAttempt.mirror
            result.putBoolean(KEY_LEGACY_DISPLAY_CREATED, mirror != null)
            result.putBoolean(KEY_LEGACY_SURFACE_ATTACHED, mirror != null)
            if (mirror == null) return result.failure(Failure.DisplayConfigurationRejected)
            result.putBoolean(KEY_CAPTURE_STARTED, true)
            result.putString(KEY_LEGACY_VBR_SECURE, mirror.secure.toString())

            val captureStartMs = SystemClock.elapsedRealtime()
            val bufferInfo = MediaCodec.BufferInfo()
            val deadlineMs = captureStartMs + ENCODE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val hasFrame = bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    codec.releaseOutputBuffer(index, false)
                    if (hasFrame) {
                        result.putBoolean(KEY_LEGACY_FIRST_REAL_FRAME_ENCODED, true)
                        result.putLong(
                            KEY_LEGACY_FIRST_FRAME_ELAPSED_MS,
                            SystemClock.elapsedRealtime() - captureStartMs,
                        )
                        break
                    }
                }
            }
            if (!result.getBoolean(KEY_LEGACY_FIRST_REAL_FRAME_ENCODED, false)) {
                result.failure(Failure.EncoderOutputTimeout)
            }
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            var released = mirror?.release() ?: true
            if (codecStarted) released = runCatching { codec?.stop() }.isSuccess && released
            released = runCatching { inputSurface?.release() }.isSuccess && released
            released = runCatching { codec?.release() }.isSuccess && released
            result.putBoolean(KEY_LEGACY_RELEASE_SUCCEEDED, released)
        }
        return result
    }

    /**
     * Uses the exact existing encoder surface and unchanged legacy capture bridge. No Session,
     * network transport, or encoded output persistence is involved.
     */
    private fun runLegacyEncoderFrame(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.LegacyEncoderFrame)
        val request = videoRequest()
        val capabilities = AndroidVideoEncoderDiscovery().query(request)
        if (!capabilities.isSupported) return result.failure(Failure.EncoderUnavailable)
        val codecName = capabilities.selectedCodec?.codecName ?: return result.failure(Failure.EncoderUnavailable)
        val captureApi = SurfaceControlDisplayCaptureApi()
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var codecStarted = false
        try {
            resolveLegacyStructure(result)
            val legacyCapabilities = captureApi.queryCapabilities()
            result.putBoolean(KEY_LEGACY_SURFACECONTROL_AVAILABLE, legacyCapabilities.backendAvailable)
            result.putString(KEY_LEGACY_RESOLUTION_ERROR, legacyCapabilities.lastError.name)
            if (!legacyCapabilities.backendAvailable) return result

            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(
                AndroidVideoEncoderFormatFactory.create(request),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            result.putBoolean(KEY_ENCODER_CONFIGURED, true)
            inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true
            result.putBoolean(KEY_ENCODER_STARTED, true)

            val captureStartMs = SystemClock.elapsedRealtime()
            val start = captureApi.startCapture(
                CaptureRequest(DISPLAY_ID, WIDTH, HEIGHT),
                checkNotNull(inputSurface),
            )
            result.putString(KEY_LEGACY_START_ERROR, start.name)
            result.putBoolean(KEY_LEGACY_DISPLAY_CREATED, start == CaptureError.None)
            result.putBoolean(KEY_LEGACY_SURFACE_ATTACHED, start == CaptureError.None)
            if (start != CaptureError.None) return result

            val bufferInfo = MediaCodec.BufferInfo()
            val deadlineMs = SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val hasFrame = bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    codec.releaseOutputBuffer(index, false)
                    if (hasFrame) {
                        result.putBoolean(KEY_LEGACY_FIRST_REAL_FRAME_ENCODED, true)
                        result.putLong(
                            KEY_LEGACY_FIRST_FRAME_ELAPSED_MS,
                            SystemClock.elapsedRealtime() - captureStartMs,
                        )
                        break
                    }
                }
            }
            if (!result.getBoolean(KEY_LEGACY_FIRST_REAL_FRAME_ENCODED, false)) {
                result.failure(Failure.EncoderOutputTimeout)
            }
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            var released = runCatching { captureApi.stopCapture() }.getOrNull() == CaptureError.None
            if (codecStarted) released = runCatching { codec?.stop() }.isSuccess && released
            released = runCatching { inputSurface?.release() }.isSuccess && released
            released = runCatching { codec?.release() }.isSuccess && released
            result.putBoolean(KEY_LEGACY_RELEASE_SUCCEEDED, released)
        }
        return result
    }

    private fun rawAvcRow(info: MediaCodecInfo, request: VideoEncoderRequest, allowActiveProbe: Boolean): String {
        val capabilities = runCatching { info.getCapabilitiesForType(AVC_MIME) }.getOrNull()
            ?: return "${info.name},capabilities=false"
        val video = capabilities.videoCapabilities
        val encoder = capabilities.encoderCapabilities
        val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
        val software = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly
        val vendor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isVendor
        val alias = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias
        val surface = capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        val width = video.supportedWidths.contains(request.width)
        val height = video.supportedHeights.contains(request.height)
        val size = runCatching { video.isSizeSupported(request.width, request.height) }.getOrDefault(false)
        val frameRate = runCatching {
            video.areSizeAndRateSupported(request.width, request.height, request.frameRate.toDouble())
        }.getOrDefault(false)
        val bitrate = video.bitrateRange.contains(request.bitrateBps)
        val cbr = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val cbrFd = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)
        val format = runCatching { capabilities.isFormatSupported(AndroidVideoEncoderFormatFactory.create(request)) }
            .getOrDefault(false)
        val eligible = hardware && !software && surface && width && height && size && frameRate && bitrate
        val activeProbe = if (!allowActiveProbe) {
            "NotRun"
        } else if (!cbr && eligible) {
            AndroidExactVideoEncoderCapabilityProbe()
                .probe(ExactVideoEncoderCapabilityKey.from(info.name, request))
                .probeResult
                ?.name ?: "None"
        } else {
            "NotNeeded"
        }
        return listOf(
            info.name,
            "hw=$hardware",
            "sw=$software",
            "vendor=$vendor",
            "alias=$alias",
            "surface=$surface",
            "width=$width",
            "height=$height",
            "size=$size",
            "fps60=$frameRate",
            "bitrate8m=$bitrate",
            "cbr=$cbr",
            "cbrfd=$cbrFd",
            "format=$format",
            "probe=$activeProbe",
            "profiles=${capabilities.profileLevels.size}",
        ).joinToString(",")
    }

    private fun resolveLegacyStructure(result: Bundle) {
        val surfaceControl = runCatching { Class.forName(LEGACY_SURFACE_CONTROL) }.getOrNull()
        result.putBoolean(KEY_LEGACY_SURFACECONTROL_CLASS_AVAILABLE, surfaceControl != null)
        val displayManagerGlobal = runCatching { Class.forName(DISPLAY_MANAGER_GLOBAL) }.getOrNull()
        val displayInfo = runCatching { Class.forName(LEGACY_DISPLAY_INFO) }.getOrNull()
        result.putBoolean(KEY_LEGACY_DISPLAY_MANAGER_GLOBAL_CLASS_AVAILABLE, displayManagerGlobal != null)
        result.putBoolean(KEY_LEGACY_DISPLAY_INFO_CLASS_AVAILABLE, displayInfo != null)
        val createDisplay = surfaceControl?.declaredMethod(
            LEGACY_CREATE_DISPLAY,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        result.putBoolean(KEY_LEGACY_CREATE_DISPLAY_AVAILABLE, createDisplay != null)
        result.putBoolean(
            KEY_LEGACY_DESTROY_DISPLAY_AVAILABLE,
            surfaceControl?.declaredMethod(LEGACY_DESTROY_DISPLAY, IBinder::class.java) != null,
        )
        result.putBoolean(
            KEY_LEGACY_SET_DISPLAY_SURFACE_AVAILABLE,
            surfaceControl?.declaredMethod(
                LEGACY_SET_DISPLAY_SURFACE,
                IBinder::class.java,
                Surface::class.java,
            ) != null,
        )
        result.putBoolean(
            KEY_LEGACY_SET_DISPLAY_PROJECTION_AVAILABLE,
            surfaceControl?.declaredMethod(
                LEGACY_SET_DISPLAY_PROJECTION,
                IBinder::class.java,
                Int::class.javaPrimitiveType!!,
                android.graphics.Rect::class.java,
                android.graphics.Rect::class.java,
            ) != null,
        )
        result.putBoolean(
            KEY_LEGACY_SET_DISPLAY_LAYER_STACK_AVAILABLE,
            surfaceControl?.declaredMethod(
                LEGACY_SET_DISPLAY_LAYER_STACK,
                IBinder::class.java,
                Int::class.javaPrimitiveType!!,
            ) != null,
        )
        result.putBoolean(
            KEY_LEGACY_GET_INSTANCE_AVAILABLE,
            displayManagerGlobal?.declaredMethod(GET_INSTANCE) != null,
        )
        result.putBoolean(
            KEY_LEGACY_GET_DISPLAY_INFO_AVAILABLE,
            displayManagerGlobal?.declaredMethod(
                GET_DISPLAY_INFO,
                Int::class.javaPrimitiveType!!,
            ) != null,
        )
        result.putBoolean(
            KEY_LEGACY_LOGICAL_WIDTH_FIELD_AVAILABLE,
            displayInfo?.declaredField("logicalWidth") != null,
        )
        result.putBoolean(
            KEY_LEGACY_LOGICAL_HEIGHT_FIELD_AVAILABLE,
            displayInfo?.declaredField("logicalHeight") != null,
        )
        result.putBoolean(
            KEY_LEGACY_ROTATION_FIELD_AVAILABLE,
            displayInfo?.declaredField("rotation") != null,
        )
    }

    /**
     * Calls exactly the legacy production createDisplay signature, without configuring capture.
     * A non-null token is destroyed before the unchanged bridge lifecycle is exercised below.
     */
    private fun diagnoseLegacyCreateDisplay(result: Bundle): Boolean {
        val surfaceControl = runCatching { Class.forName(LEGACY_SURFACE_CONTROL) }.getOrNull()
            ?: return true
        val createDisplay = surfaceControl.declaredMethod(
            LEGACY_CREATE_DISPLAY,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: return true
        val destroyDisplay = surfaceControl.declaredMethod(
            LEGACY_DESTROY_DISPLAY,
            IBinder::class.java,
        )
        if (destroyDisplay == null) {
            result.putString(KEY_LEGACY_CREATE_DISPLAY_OUTCOME, "DestroyUnavailable")
            return false
        }

        result.putInt(KEY_LEGACY_METHOD_PARAMETER_COUNT, createDisplay.parameterCount)
        result.putInt(KEY_LEGACY_ARGUMENT_COUNT, LEGACY_CREATE_DISPLAY_ARGUMENT_COUNT)
        result.putBoolean(
            KEY_LEGACY_ARGUMENT_COUNT_MATCH,
            createDisplay.parameterCount == LEGACY_CREATE_DISPLAY_ARGUMENT_COUNT,
        )
        val arguments = arrayOf(LEGACY_DISPLAY_NAME, LEGACY_DISPLAY_SECURE)
        val assignable = createDisplay.parameterTypes.zip(arguments).mapIndexed {
                index,
                (expected, value),
            ->
            expected.accepts(value).also { accepted ->
                result.putBoolean(
                    "$KEY_LEGACY_ARGUMENT_ASSIGNABLE_PREFIX$index$KEY_ARGUMENT_ASSIGNABLE_SUFFIX",
                    accepted,
                )
            }
        }
        result.putBoolean(KEY_LEGACY_ARGUMENT_TYPES_MATCH, assignable.all { it })
        if (!result.getBoolean(KEY_LEGACY_ARGUMENT_COUNT_MATCH) || !assignable.all { it }) {
            result.putString(KEY_LEGACY_CREATE_DISPLAY_OUTCOME, "ReflectionArgument")
            return true
        }

        result.putString(KEY_LEGACY_CREATE_DISPLAY_STAGE, "invoke_started")
        val token = try {
            createDisplay.invoke(null, *arguments) as? IBinder
        } catch (exception: InvocationTargetException) {
            result.putBoolean(KEY_LEGACY_REFLECTION_INVOCATION_ACCEPTED, true)
            result.putString(KEY_LEGACY_CREATE_DISPLAY_OUTCOME, legacyInvocationOutcome(exception.targetException))
            return true
        } catch (exception: IllegalArgumentException) {
            result.putBoolean(KEY_LEGACY_REFLECTION_INVOCATION_ACCEPTED, false)
            result.putString(KEY_LEGACY_CREATE_DISPLAY_OUTCOME, "ReflectionArgument")
            return true
        }
        result.putBoolean(KEY_LEGACY_REFLECTION_INVOCATION_ACCEPTED, true)
        result.putBoolean(KEY_LEGACY_DIRECT_TOKEN_RETURNED, token != null)
        if (token == null) {
            result.putString(KEY_LEGACY_CREATE_DISPLAY_OUTCOME, "NullToken")
            return true
        }
        result.putString(KEY_LEGACY_CREATE_DISPLAY_OUTCOME, "TokenReturned")
        val destroyed = runCatching { destroyDisplay.invoke(null, token) }.isSuccess
        result.putBoolean(
            KEY_LEGACY_DIRECT_DESTROY_SUCCEEDED,
            destroyed,
        )
        return destroyed
    }

    /**
     * Replays the existing configuration call order only after the unchanged bridge reports a
     * projection configuration failure. This owns and releases a separate temporary display.
     */
    private fun diagnoseLegacyConfiguration(result: Bundle, targetSurface: Surface) {
        result.putBoolean(KEY_LEGACY_CONFIGURATION_ATTEMPTED, true)
        val surfaceControl = runCatching { Class.forName(LEGACY_SURFACE_CONTROL) }.getOrNull() ?: return
        val createDisplay = surfaceControl.declaredMethod(
            LEGACY_CREATE_DISPLAY,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: return
        val destroyDisplay = surfaceControl.declaredMethod(LEGACY_DESTROY_DISPLAY, IBinder::class.java) ?: return
        val setDisplaySurface = surfaceControl.declaredMethod(
            LEGACY_SET_DISPLAY_SURFACE,
            IBinder::class.java,
            Surface::class.java,
        ) ?: return
        val setDisplayLayerStack = surfaceControl.declaredMethod(
            LEGACY_SET_DISPLAY_LAYER_STACK,
            IBinder::class.java,
            Int::class.javaPrimitiveType!!,
        ) ?: return
        val setDisplayProjection = surfaceControl.declaredMethod(
            LEGACY_SET_DISPLAY_PROJECTION,
            IBinder::class.java,
            Int::class.javaPrimitiveType!!,
            Rect::class.java,
            Rect::class.java,
        ) ?: return
        val displayManagerGlobal = runCatching { Class.forName(DISPLAY_MANAGER_GLOBAL) }.getOrNull() ?: return
        val getInstance = displayManagerGlobal.declaredMethod(GET_INSTANCE) ?: return
        val getDisplayInfo = displayManagerGlobal.declaredMethod(
            GET_DISPLAY_INFO,
            Int::class.javaPrimitiveType!!,
        ) ?: return

        val token = try {
            createDisplay.invoke(null, LEGACY_CONFIGURATION_DISPLAY_NAME, LEGACY_DISPLAY_SECURE) as? IBinder
        } catch (exception: InvocationTargetException) {
            result.putString(
                KEY_LEGACY_CONFIGURATION_CREATE_OUTCOME,
                legacyInvocationOutcome(exception.targetException),
            )
            return
        } catch (exception: IllegalArgumentException) {
            result.putString(KEY_LEGACY_CONFIGURATION_CREATE_OUTCOME, "ReflectionArgument")
            return
        }
        if (token == null) {
            result.putString(KEY_LEGACY_CONFIGURATION_CREATE_OUTCOME, "NullToken")
            return
        }
        result.putString(KEY_LEGACY_CONFIGURATION_CREATE_OUTCOME, "TokenReturned")

        try {
            val manager = getInstance.invoke(null) ?: return
            val displayInfo = getDisplayInfo.invoke(manager, DISPLAY_ID)
            result.putBoolean(KEY_LEGACY_CONFIGURATION_DISPLAY_INFO_AVAILABLE, displayInfo != null)
            if (displayInfo == null) return
            val infoClass = displayInfo.javaClass
            val width = infoClass.declaredField("logicalWidth")?.getInt(displayInfo) ?: return
            val height = infoClass.declaredField("logicalHeight")?.getInt(displayInfo) ?: return
            val rotation = infoClass.declaredField("rotation")?.getInt(displayInfo) ?: return
            val layerStack = infoClass.declaredField("layerStack")?.getInt(displayInfo) ?: DISPLAY_ID
            val projection = try {
                CaptureGeometry.computeProjection(
                    sourceWidth = width,
                    sourceHeight = height,
                    sourceRotation = rotation,
                    targetWidth = WIDTH,
                    targetHeight = HEIGHT,
                )
            } catch (_: IllegalArgumentException) {
                result.putString(KEY_LEGACY_CONFIGURATION_PROJECTION_RESULT, "GeometryRejected")
                return
            }
            if (!invokeLegacyConfigurationStep(
                    result,
                    KEY_LEGACY_CONFIGURATION_SURFACE_RESULT,
                    setDisplaySurface,
                    token,
                    targetSurface,
                )
            ) {
                return
            }
            if (!invokeLegacyConfigurationStep(
                    result,
                    KEY_LEGACY_CONFIGURATION_LAYER_STACK_RESULT,
                    setDisplayLayerStack,
                    token,
                    layerStack,
                )
            ) {
                return
            }
            invokeLegacyConfigurationStep(
                result,
                KEY_LEGACY_CONFIGURATION_PROJECTION_RESULT,
                setDisplayProjection,
                token,
                projection.orientation,
                Rect(
                    projection.sourceCrop.left,
                    projection.sourceCrop.top,
                    projection.sourceCrop.right,
                    projection.sourceCrop.bottom,
                ),
                Rect(
                    projection.targetRect.left,
                    projection.targetRect.top,
                    projection.targetRect.right,
                    projection.targetRect.bottom,
                ),
            )
        } finally {
            runCatching { setDisplaySurface.invoke(null, token, null) }
            result.putBoolean(
                KEY_LEGACY_CONFIGURATION_RELEASE_SUCCEEDED,
                runCatching { destroyDisplay.invoke(null, token) }.isSuccess,
            )
        }
    }

    private fun invokeLegacyConfigurationStep(
        result: Bundle,
        key: String,
        method: Method,
        vararg arguments: Any,
    ): Boolean = try {
        method.invoke(null, *arguments)
        result.putString(key, "Succeeded")
        true
    } catch (exception: InvocationTargetException) {
        result.putString(key, legacyInvocationOutcome(exception.targetException))
        false
    } catch (exception: IllegalArgumentException) {
        result.putString(key, "ReflectionArgument")
        false
    }

    private fun resolveLegacyCompatibilityMethods(result: Bundle): LegacyCompatibilityMethods? {
        val surfaceControl = runCatching { Class.forName(LEGACY_SURFACE_CONTROL) }.getOrNull() ?: return null
        val displayManagerGlobal = runCatching { Class.forName(DISPLAY_MANAGER_GLOBAL) }.getOrNull() ?: return null
        val methods = LegacyCompatibilityMethods(
            createDisplay = surfaceControl.declaredMethod(
                LEGACY_CREATE_DISPLAY,
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
            ) ?: return null,
            destroyDisplay = surfaceControl.declaredMethod(LEGACY_DESTROY_DISPLAY, IBinder::class.java) ?: return null,
            setDisplaySurface = surfaceControl.declaredMethod(
                LEGACY_SET_DISPLAY_SURFACE,
                IBinder::class.java,
                Surface::class.java,
            ) ?: return null,
            setDisplayProjection = surfaceControl.declaredMethod(
                LEGACY_SET_DISPLAY_PROJECTION,
                IBinder::class.java,
                Int::class.javaPrimitiveType!!,
                Rect::class.java,
                Rect::class.java,
            ) ?: return null,
            setDisplayLayerStack = surfaceControl.declaredMethod(
                LEGACY_SET_DISPLAY_LAYER_STACK,
                IBinder::class.java,
                Int::class.javaPrimitiveType!!,
            ) ?: return null,
            openTransaction = surfaceControl.declaredMethod(LEGACY_OPEN_TRANSACTION) ?: return null,
            closeTransaction = surfaceControl.declaredMethod(LEGACY_CLOSE_TRANSACTION) ?: return null,
            getInstance = displayManagerGlobal.declaredMethod(GET_INSTANCE) ?: return null,
            getDisplayInfo = displayManagerGlobal.declaredMethod(
                GET_DISPLAY_INFO,
                Int::class.javaPrimitiveType!!,
            ) ?: return null,
        )
        result.putBoolean(KEY_LEGACY_COMPATIBILITY_METHODS_RESOLVED, true)
        result.putBoolean(KEY_LEGACY_COMPATIBILITY_ARGUMENTS_ASSIGNABLE, true)
        return methods
    }

    private fun resolveLegacyDisplayState(methods: LegacyCompatibilityMethods): LegacyDisplayState? {
        val manager = methods.getInstance.invoke(null) ?: return null
        val info = methods.getDisplayInfo.invoke(manager, DISPLAY_ID) ?: return null
        val owner = info.javaClass
        val width = owner.declaredField("logicalWidth")?.getInt(info) ?: return null
        val height = owner.declaredField("logicalHeight")?.getInt(info) ?: return null
        val rotation = owner.declaredField("rotation")?.getInt(info) ?: return null
        val layerStack = owner.declaredField("layerStack")?.getInt(info) ?: DISPLAY_ID
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = width,
            sourceHeight = height,
            sourceRotation = rotation,
            targetWidth = WIDTH,
            targetHeight = HEIGHT,
        )
        return LegacyDisplayState(
            layerStack = layerStack,
            orientation = projection.orientation,
            sourceRect = Rect(
                projection.sourceCrop.left,
                projection.sourceCrop.top,
                projection.sourceCrop.right,
                projection.sourceCrop.bottom,
            ),
            targetRect = Rect(
                projection.targetRect.left,
                projection.targetRect.top,
                projection.targetRect.right,
                projection.targetRect.bottom,
            ),
        )
    }

    private fun runLegacyCompatibilityVariant(
        methods: LegacyCompatibilityMethods,
        displayState: LegacyDisplayState,
        surface: Surface,
        secure: Boolean,
        transaction: Boolean,
    ): String {
        val label = "secure=$secure,transaction=$transaction"
        val token = try {
            methods.createDisplay.invoke(null, LEGACY_COMPATIBILITY_DISPLAY_NAME, secure) as? IBinder
        } catch (exception: InvocationTargetException) {
            return "$label,token=${legacyInvocationOutcome(exception.targetException)},release=NotNeeded"
        } catch (exception: IllegalArgumentException) {
            return "$label,token=ReflectionArgument,release=NotNeeded"
        }
        if (token == null) return "$label,token=NullToken,release=NotNeeded"

        var opened = false
        var transactionResult = if (transaction) "NotOpened" else "NotRequested"
        var surfaceResult = "NotReached"
        var projectionResult = "NotReached"
        var layerStackResult = "NotReached"
        var released = false
        try {
            if (transaction) {
                transactionResult = invokeLegacyCompatibilityStep(methods.openTransaction)
                opened = transactionResult == "Succeeded"
            }
            if (!transaction || opened) {
                surfaceResult = invokeLegacyCompatibilityStep(methods.setDisplaySurface, token, surface)
                if (surfaceResult == "Succeeded") {
                    projectionResult = invokeLegacyCompatibilityStep(
                        methods.setDisplayProjection,
                        token,
                        displayState.orientation,
                        displayState.sourceRect,
                        displayState.targetRect,
                    )
                }
                if (projectionResult == "Succeeded") {
                    layerStackResult = invokeLegacyCompatibilityStep(
                        methods.setDisplayLayerStack,
                        token,
                        displayState.layerStack,
                    )
                }
            }
        } finally {
            if (opened) runCatching { methods.closeTransaction.invoke(null) }
            runCatching { methods.setDisplaySurface.invoke(null, token, null) }
            released = runCatching { methods.destroyDisplay.invoke(null, token) }.isSuccess
        }
        return listOf(
            label,
            "token=TokenReturned",
            "transaction=$transactionResult",
            "surface=$surfaceResult",
            "projection=$projectionResult",
            "layer_stack=$layerStackResult",
            "release=${if (released) "Succeeded" else "Failed"}",
        ).joinToString(",")
    }

    private fun findAdvertisedVbrAvcEncoder(): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) }) {
                return@firstOrNull false
            }
            val capabilities = runCatching { info.getCapabilitiesForType(AVC_MIME) }.getOrNull()
                ?: return@firstOrNull false
            val video = capabilities.videoCapabilities
            val encoder = capabilities.encoderCapabilities
            val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
            val surface = capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            hardware &&
                !info.isSoftwareOnly &&
                surface &&
                video.isSizeSupported(WIDTH, HEIGHT) &&
                video.areSizeAndRateSupported(WIDTH, HEIGHT, FRAME_RATE.toDouble()) &&
                video.bitrateRange.contains(BITRATE_BPS) &&
                encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) &&
                capabilities.isFormatSupported(legacyVbrFormat())
        }

    private fun legacyVbrFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(AVC_MIME, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
    }

    private fun createLegacyVbrMirror(
        methods: LegacyCompatibilityMethods,
        displayState: LegacyDisplayState,
        surface: Surface,
    ): LegacyVbrMirrorAttempt {
        var latestOutcome = "NoVariantSucceeded"
        for (secure in listOf(true, false)) {
            val token = try {
                methods.createDisplay.invoke(null, LEGACY_COMPATIBILITY_DISPLAY_NAME, secure) as? IBinder
            } catch (exception: InvocationTargetException) {
                latestOutcome = "secure=$secure,token=${legacyInvocationOutcome(exception.targetException)}"
                continue
            } catch (exception: IllegalArgumentException) {
                latestOutcome = "secure=$secure,token=ReflectionArgument"
                continue
            }
            if (token == null) {
                latestOutcome = "secure=$secure,token=NullToken"
                continue
            }

            var opened = false
            var completed = false
            var closeResult = "NotReached"
            var transactionResult = "NotReached"
            var surfaceResult = "NotReached"
            var projectionResult = "NotReached"
            var layerStackResult = "NotReached"
            try {
                transactionResult = invokeLegacyCompatibilityStep(methods.openTransaction)
                opened = transactionResult == "Succeeded"
                if (opened) {
                    surfaceResult = invokeLegacyCompatibilityStep(methods.setDisplaySurface, token, surface)
                    if (surfaceResult == "Succeeded") {
                        projectionResult = invokeLegacyCompatibilityStep(
                            methods.setDisplayProjection,
                            token,
                            displayState.orientation,
                            displayState.sourceRect,
                            displayState.targetRect,
                        )
                    }
                    if (projectionResult == "Succeeded") {
                        layerStackResult = invokeLegacyCompatibilityStep(
                            methods.setDisplayLayerStack,
                            token,
                            displayState.layerStack,
                        )
                    }
                    completed = layerStackResult == "Succeeded"
                }
                latestOutcome = listOf(
                    "secure=$secure",
                    "transaction=$transactionResult",
                    "surface=$surfaceResult",
                    "projection=$projectionResult",
                    "layer_stack=$layerStackResult",
                ).joinToString(",")
            } finally {
                if (opened) closeResult = invokeLegacyCompatibilityStep(methods.closeTransaction)
                if (!completed || closeResult != "Succeeded") {
                    runCatching { methods.setDisplaySurface.invoke(null, token, null) }
                    runCatching { methods.destroyDisplay.invoke(null, token) }
                }
            }
            latestOutcome = "$latestOutcome,close=$closeResult"
            if (completed && closeResult == "Succeeded") {
                return LegacyVbrMirrorAttempt(
                    mirror = LegacyVbrMirror(methods, token, secure),
                    outcome = latestOutcome,
                )
            }
        }
        return LegacyVbrMirrorAttempt(mirror = null, outcome = latestOutcome)
    }

    private fun invokeLegacyCompatibilityStep(method: Method, vararg arguments: Any): String = try {
        method.invoke(null, *arguments)
        "Succeeded"
    } catch (exception: InvocationTargetException) {
        legacyInvocationOutcome(exception.targetException)
    } catch (exception: IllegalArgumentException) {
        "ReflectionArgument"
    }

    private fun inspectModernMirrorMethodShapes(result: Bundle) {
        val manager = runCatching { Class.forName(DISPLAY_MANAGER) }.getOrNull() ?: return
        val shapes = manager.declaredMethods.asSequence()
            .filter { method -> method.name == CREATE_VIRTUAL_DISPLAY }
            .filter { method -> method.parameterTypes.any { type -> type == Surface::class.java } }
            .map { method ->
                val receiver = if (Modifier.isStatic(method.modifiers)) "static" else "instance"
                "$receiver(${method.parameterTypes.joinToString(",") { it.simpleName }})"
            }
            .distinct()
            .sorted()
            .joinToString("|")
        result.putString(KEY_MODERN_MIRROR_METHOD_SHAPES, shapes.ifEmpty { "None" })
    }

    private fun Class<*>.declaredMethod(name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true } }.getOrNull()

    private fun Class<*>.declaredField(name: String): java.lang.reflect.Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun Class<*>.accepts(value: Any): Boolean = when {
        !isPrimitive -> isInstance(value)
        this == Int::class.javaPrimitiveType -> value is Int
        this == Boolean::class.javaPrimitiveType -> value is Boolean
        else -> false
    }

    private fun legacyInvocationOutcome(throwable: Throwable): String = when (throwable) {
        is SecurityException -> "InvocationTargetSecurityException"
        is IllegalArgumentException -> "InvocationTargetIllegalArgumentException"
        is IllegalStateException -> "InvocationTargetIllegalStateException"
        is UnsupportedOperationException -> "InvocationTargetUnsupportedOperationException"
        is NullPointerException -> "InvocationTargetNullPointerException"
        is android.os.RemoteException -> "InvocationTargetRemoteException"
        is RuntimeException -> "InvocationTargetRuntimeException"
        is Error -> "InvocationTargetError"
        else -> "InvocationTargetOther"
    }

    private data class LegacyCompatibilityMethods(
        val createDisplay: Method,
        val destroyDisplay: Method,
        val setDisplaySurface: Method,
        val setDisplayProjection: Method,
        val setDisplayLayerStack: Method,
        val openTransaction: Method,
        val closeTransaction: Method,
        val getInstance: Method,
        val getDisplayInfo: Method,
    )

    private data class LegacyDisplayState(
        val layerStack: Int,
        val orientation: Int,
        val sourceRect: Rect,
        val targetRect: Rect,
    )

    private data class LegacyVbrMirror(
        private val methods: LegacyCompatibilityMethods,
        private val token: IBinder,
        val secure: Boolean,
    ) {
        fun release(): Boolean {
            val detached = runCatching { methods.setDisplaySurface.invoke(null, token, null) }.isSuccess
            val destroyed = runCatching { methods.destroyDisplay.invoke(null, token) }.isSuccess
            return detached && destroyed
        }
    }

    private data class LegacyVbrMirrorAttempt(
        val mirror: LegacyVbrMirror?,
        val outcome: String,
    )

    private fun videoRequest() = VideoEncoderRequest(
        width = WIDTH,
        height = HEIGHT,
        frameRate = FRAME_RATE,
        bitrateBps = BITRATE_BPS,
        iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
    )

    private fun initialResult(kind: ExperimentalDisplayMirrorProbeKind): Bundle = Bundle().apply {
        putString(KEY_PROBE, kind.name)
        putInt(KEY_UID, Process.myUid())
        putString(KEY_IDENTITY_MODE, if (Process.myUid() == Process.SHELL_UID) "shell" else "other")
        putString(KEY_SELINUX_CONTEXT, selinuxContext())
    }

    private fun resolveMirror(result: Bundle): Method? {
        val displayManagerGlobal = Class.forName(DISPLAY_MANAGER_GLOBAL)
        val global = displayManagerGlobal.getDeclaredMethod(GET_INSTANCE).invoke(null)
        result.putBoolean(KEY_DISPLAY_MANAGER_SERVICE_AVAILABLE, global != null)
        val displayInfo = global.javaClass
            .getMethod(GET_DISPLAY_INFO, Int::class.javaPrimitiveType!!)
            .invoke(global, DISPLAY_ID)
        result.putBoolean(KEY_DISPLAY_0_AVAILABLE, displayInfo != null)
        if (displayInfo == null) {
            result.failure(Failure.DisplayUnavailable)
            return null
        }
        val mirror = Class.forName(DISPLAY_MANAGER).getMethod(
            CREATE_VIRTUAL_DISPLAY,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Surface::class.java,
        )
        result.putBoolean(KEY_MIRROR_METHOD_AVAILABLE, true)
        val expected = Modifier.isStatic(mirror.modifiers) && mirror.returnType.name == VIRTUAL_DISPLAY
        result.putBoolean(KEY_EXPECTED_SIGNATURE_AVAILABLE, expected)
        if (!expected) {
            result.failure(Failure.SignatureMismatch)
            return null
        }
        return mirror
    }

    private fun invokeMirror(mirror: Method, surface: Surface, result: Bundle): Any? {
        result.putString(KEY_REFLECTION_STAGE, "arguments_checked")
        result.putInt(KEY_METHOD_PARAMETER_COUNT, MIRROR_ARGUMENT_COUNT)
        result.putInt(KEY_MIRROR_ARGUMENT_COUNT, MIRROR_ARGUMENT_COUNT)
        result.putBoolean(KEY_MIRROR_ARGUMENT_COUNT_MATCH, true)
        repeat(MIRROR_ARGUMENT_COUNT) { index ->
            result.putBoolean("$KEY_ARGUMENT_ASSIGNABLE_PREFIX$index$KEY_ARGUMENT_ASSIGNABLE_SUFFIX", true)
        }
        result.putBoolean(KEY_MIRROR_ARGUMENT_TYPES_MATCH, true)
        result.putString(KEY_REFLECTION_STAGE, "invoke_started")
        return try {
            mirror.invoke(null, DISPLAY_NAME, WIDTH, HEIGHT, DISPLAY_ID, surface).also {
                result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, true)
            }
        } catch (exception: InvocationTargetException) {
            result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, true)
            throw exception
        } catch (exception: IllegalArgumentException) {
            result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, false)
            throw exception
        }
    }

    private fun releaseVirtualDisplay(virtualDisplay: Any?): Boolean {
        return if (virtualDisplay == null) {
            true
        } else {
            runCatching { virtualDisplay.javaClass.getMethod(RELEASE).invoke(virtualDisplay) }.isSuccess
        }
    }

    private fun selinuxContext(): String = runCatching {
        Class.forName("android.os.SELinux").getMethod("getContext").invoke(null) as? String
    }.getOrNull() ?: "unavailable"

    private fun Bundle.failure(failure: Failure): Bundle = apply {
        putString(KEY_FAILURE, failure.name)
        putString(KEY_FAILURE_STAGE, failure.name)
    }

    @Suppress("unused")
    fun destroy() = Unit

    private companion object {
        const val PROBE_REVISION = "v2-a41-legacy-vbr-frame-1"
        const val KEY_PROBE_REVISION = "probe_revision"
        const val DISPLAY_MANAGER_GLOBAL = "android.hardware.display.DisplayManagerGlobal"
        const val DISPLAY_MANAGER = "android.hardware.display.DisplayManager"
        const val LEGACY_SURFACE_CONTROL = "android.view.SurfaceControl"
        const val LEGACY_DISPLAY_INFO = "android.view.DisplayInfo"
        const val VIRTUAL_DISPLAY = "android.hardware.display.VirtualDisplay"
        const val GET_INSTANCE = "getInstance"
        const val GET_DISPLAY_INFO = "getDisplayInfo"
        const val CREATE_VIRTUAL_DISPLAY = "createVirtualDisplay"
        const val LEGACY_CREATE_DISPLAY = "createDisplay"
        const val LEGACY_DESTROY_DISPLAY = "destroyDisplay"
        const val LEGACY_SET_DISPLAY_SURFACE = "setDisplaySurface"
        const val LEGACY_SET_DISPLAY_PROJECTION = "setDisplayProjection"
        const val LEGACY_SET_DISPLAY_LAYER_STACK = "setDisplayLayerStack"
        const val LEGACY_OPEN_TRANSACTION = "openTransaction"
        const val LEGACY_CLOSE_TRANSACTION = "closeTransaction"
        const val RELEASE = "release"
        const val DISPLAY_NAME = "WarpnectCaptureExperiment"
        const val LEGACY_DISPLAY_NAME = "WarpnectCaptureExperimentLegacy"
        const val LEGACY_CONFIGURATION_DISPLAY_NAME = "WarpnectCaptureExperimentLegacyConfig"
        const val LEGACY_COMPATIBILITY_DISPLAY_NAME = "WarpnectCaptureExperimentLegacyCompatibility"
        const val LEGACY_DISPLAY_SECURE = true
        const val DISPLAY_ID = 0
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val BITRATE_BPS = 8_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1f
        const val IMAGE_READER_BUFFERS = 2
        const val MIRROR_ARGUMENT_COUNT = 5
        const val LEGACY_CREATE_DISPLAY_ARGUMENT_COUNT = 2
        const val ENCODE_TIMEOUT_MS = 3_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L

        const val KEY_PROBE = "probe"
        const val KEY_UID = "uid"
        const val KEY_IDENTITY_MODE = "identity_mode"
        const val KEY_SELINUX_CONTEXT = "selinux_context"
        const val KEY_DISPLAY_MANAGER_SERVICE_AVAILABLE = "display_manager_service_available"
        const val KEY_DISPLAY_0_AVAILABLE = "display_0_available"
        const val KEY_MODERN_MIRROR_METHOD_SHAPES = "modern_mirror_method_shapes"
        const val KEY_MIRROR_METHOD_AVAILABLE = "mirror_method_available"
        const val KEY_EXPECTED_SIGNATURE_AVAILABLE = "expected_signature_available"
        const val KEY_SURFACE_ATTACHED = "surface_attached"
        const val KEY_METHOD_PARAMETER_COUNT = "method_parameter_count"
        const val KEY_MIRROR_ARGUMENT_COUNT = "mirror_argument_count"
        const val KEY_MIRROR_ARGUMENT_COUNT_MATCH = "mirror_argument_count_match"
        const val KEY_ARGUMENT_ASSIGNABLE_PREFIX = "arg_"
        const val KEY_ARGUMENT_ASSIGNABLE_SUFFIX = "_assignable"
        const val KEY_MIRROR_ARGUMENT_TYPES_MATCH = "mirror_argument_types_match"
        const val KEY_REFLECTION_STAGE = "reflection_stage"
        const val KEY_REFLECTION_INVOCATION_ACCEPTED = "reflection_invocation_accepted"
        const val KEY_CREATE_MIRROR_DISPLAY = "create_mirror_display"
        const val KEY_MIRROR_LIFECYCLE_SUCCEEDED = "mirror_lifecycle_succeeded"
        const val KEY_MIRROR_CREATED = "mirror_created"
        const val KEY_CAPTURE_STARTED = "capture_started"
        const val KEY_ENCODER_STAGE = "encoder_stage"
        const val KEY_ENCODER_CONFIGURED = "encoder_configured"
        const val KEY_ENCODER_STARTED = "encoder_started"
        const val KEY_FIRST_REAL_FRAME_ENCODED = "first_real_frame_encoded"
        const val KEY_FIRST_FRAME_ELAPSED_MS = "first_frame_elapsed_ms"
        const val KEY_RELEASE_SUCCEEDED = "release_succeeded"
        const val KEY_FAILURE = "failure"
        const val KEY_FAILURE_STAGE = "failure_stage"
        const val KEY_AVC_ENCODER_COUNT = "avc_encoder_count"
        const val KEY_AVC_ENCODER_INVENTORY = "avc_encoder_inventory"
        const val KEY_VIDEO_ENCODER_AVAILABLE = "video_encoder_available"
        const val KEY_VIDEO_ENCODER_REASON = "video_encoder_reason"
        const val KEY_VIDEO_SELECTED_CODEC = "video_selected_codec"
        const val KEY_VIDEO_PROBE_FAILURE = "video_probe_failure"
        const val KEY_VIDEO_METADATA_ONLY = "video_metadata_only"
        const val KEY_INPUT_API_RESOLVED = "input_api_resolved"
        const val KEY_INPUT_ASYNC_SUPPORTED = "input_async_supported"
        const val KEY_INPUT_DISPLAY_SUPPORTED = "input_display_supported"
        const val KEY_INPUT_AVAILABLE = "input_available"
        const val KEY_INPUT_REASON = "input_reason"
        const val KEY_LEGACY_SURFACECONTROL_CLASS_AVAILABLE = "legacy_surfacecontrol_class_available"
        const val KEY_LEGACY_DISPLAY_MANAGER_GLOBAL_CLASS_AVAILABLE = "legacy_display_manager_global_class_available"
        const val KEY_LEGACY_DISPLAY_INFO_CLASS_AVAILABLE = "legacy_display_info_class_available"
        const val KEY_LEGACY_CREATE_DISPLAY_AVAILABLE = "legacy_create_display_available"
        const val KEY_LEGACY_DESTROY_DISPLAY_AVAILABLE = "legacy_destroy_display_available"
        const val KEY_LEGACY_SET_DISPLAY_SURFACE_AVAILABLE = "legacy_set_display_surface_available"
        const val KEY_LEGACY_SET_DISPLAY_PROJECTION_AVAILABLE = "legacy_set_display_projection_available"
        const val KEY_LEGACY_SET_DISPLAY_LAYER_STACK_AVAILABLE = "legacy_set_display_layer_stack_available"
        const val KEY_LEGACY_GET_INSTANCE_AVAILABLE = "legacy_get_instance_available"
        const val KEY_LEGACY_GET_DISPLAY_INFO_AVAILABLE = "legacy_get_display_info_available"
        const val KEY_LEGACY_LOGICAL_WIDTH_FIELD_AVAILABLE = "legacy_logical_width_field_available"
        const val KEY_LEGACY_LOGICAL_HEIGHT_FIELD_AVAILABLE = "legacy_logical_height_field_available"
        const val KEY_LEGACY_ROTATION_FIELD_AVAILABLE = "legacy_rotation_field_available"
        const val KEY_LEGACY_METHOD_PARAMETER_COUNT = "legacy_method_parameter_count"
        const val KEY_LEGACY_ARGUMENT_COUNT = "legacy_argument_count"
        const val KEY_LEGACY_ARGUMENT_COUNT_MATCH = "legacy_argument_count_match"
        const val KEY_LEGACY_ARGUMENT_TYPES_MATCH = "legacy_argument_types_match"
        const val KEY_LEGACY_ARGUMENT_ASSIGNABLE_PREFIX = "legacy_arg_"
        const val KEY_LEGACY_CREATE_DISPLAY_STAGE = "legacy_create_display_stage"
        const val KEY_LEGACY_REFLECTION_INVOCATION_ACCEPTED = "legacy_reflection_invocation_accepted"
        const val KEY_LEGACY_CREATE_DISPLAY_OUTCOME = "legacy_create_display_outcome"
        const val KEY_LEGACY_DIRECT_TOKEN_RETURNED = "legacy_direct_token_returned"
        const val KEY_LEGACY_DIRECT_DESTROY_SUCCEEDED = "legacy_direct_destroy_succeeded"
        const val KEY_LEGACY_CONFIGURATION_ATTEMPTED = "legacy_configuration_attempted"
        const val KEY_LEGACY_CONFIGURATION_CREATE_OUTCOME = "legacy_configuration_create_outcome"
        const val KEY_LEGACY_CONFIGURATION_DISPLAY_INFO_AVAILABLE = "legacy_configuration_display_info_available"
        const val KEY_LEGACY_CONFIGURATION_SURFACE_RESULT = "legacy_configuration_surface_result"
        const val KEY_LEGACY_CONFIGURATION_LAYER_STACK_RESULT = "legacy_configuration_layer_stack_result"
        const val KEY_LEGACY_CONFIGURATION_PROJECTION_RESULT = "legacy_configuration_projection_result"
        const val KEY_LEGACY_CONFIGURATION_RELEASE_SUCCEEDED = "legacy_configuration_release_succeeded"
        const val KEY_LEGACY_COMPATIBILITY_METHODS_RESOLVED = "legacy_compatibility_methods_resolved"
        const val KEY_LEGACY_COMPATIBILITY_ARGUMENTS_ASSIGNABLE = "legacy_compatibility_arguments_assignable"
        const val KEY_LEGACY_COMPATIBILITY_SURFACE_VALID = "legacy_compatibility_surface_valid"
        const val KEY_LEGACY_COMPATIBILITY_SURFACE_RELEASED = "legacy_compatibility_surface_released"
        const val KEY_LEGACY_COMPATIBILITY_MATRIX = "legacy_compatibility_matrix"
        const val KEY_LEGACY_COMPATIBILITY_RELEASE_SUCCEEDED = "legacy_compatibility_release_succeeded"
        const val KEY_LEGACY_VBR_ADVERTISED = "legacy_vbr_advertised"
        const val KEY_LEGACY_VBR_CODEC = "legacy_vbr_codec"
        const val KEY_LEGACY_VBR_SECURE = "legacy_vbr_secure"
        const val KEY_LEGACY_VBR_MIRROR_OUTCOME = "legacy_vbr_mirror_outcome"
        const val KEY_CAPTURE_BACKEND_FRAME_PROOF_ONLY = "capture_backend_frame_proof_only"
        const val KEY_LEGACY_SURFACECONTROL_AVAILABLE = "legacy_surfacecontrol_available"
        const val KEY_LEGACY_RESOLUTION_ERROR = "legacy_resolution_error"
        const val KEY_LEGACY_START_ERROR = "legacy_start_error"
        const val KEY_LEGACY_DISPLAY_CREATED = "legacy_display_created"
        const val KEY_LEGACY_SURFACE_ATTACHED = "legacy_surface_attached"
        const val KEY_LEGACY_RELEASE_SUCCEEDED = "legacy_release_succeeded"
        const val KEY_LEGACY_FIRST_REAL_FRAME_ENCODED = "legacy_first_real_frame_encoded"
        const val KEY_LEGACY_FIRST_FRAME_ELAPSED_MS = "legacy_first_frame_elapsed_ms"
        const val AVC_MIME = "video/avc"
    }
}

private enum class Failure {
    None,
    ClassNotFound,
    MethodNotFound,
    SignatureMismatch,
    DisplayUnavailable,
    PermissionDenied,
    ReflectionArgumentMismatch,
    DisplayConfigurationRejected,
    BinderCallRejected,
    PlatformInvocationFailed,
    VirtualDisplayCreationFailed,
    EncoderUnavailable,
    EncoderConfigurationFailed,
    EncoderStartFailed,
    EncoderOutputTimeout,
    ReleaseFailed,
    ;

    companion object {
        fun from(throwable: Throwable): Failure {
            val cause = if (throwable is InvocationTargetException) throwable.targetException else throwable
            return when (cause) {
                is ClassNotFoundException -> ClassNotFound
                is NoSuchMethodException -> MethodNotFound
                is SecurityException,
                is IllegalAccessException,
                -> PermissionDenied
                is IllegalArgumentException -> {
                    if (throwable is InvocationTargetException) {
                        DisplayConfigurationRejected
                    } else {
                        ReflectionArgumentMismatch
                    }
                }
                is android.os.RemoteException -> BinderCallRejected
                is MediaCodec.CodecException -> EncoderStartFailed
                else -> PlatformInvocationFailed
            }
        }
    }
}
