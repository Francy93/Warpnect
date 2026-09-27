package io.warpnect.platform.session.capability

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.LocalCapabilityAvailability
import io.warpnect.video.decoder.VideoDecoderCapabilities
import io.warpnect.video.decoder.VideoDecoderCodecInfo
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderError
import io.warpnect.video.decoder.VideoDecoderHardwareAcceleration
import io.warpnect.video.decoder.VideoDecoderQualification
import io.warpnect.video.decoder.VideoDecoderSupport
import io.warpnect.video.encoder.VideoEncoderCapabilities
import io.warpnect.video.encoder.VideoEncoderCodecInfo
import io.warpnect.video.encoder.VideoEncoderHardwareAcceleration
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.encoder.VideoEncoderSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalCapabilityCollectorTest {
    @Test fun `explicit acquired lab Surface codec is never advertised as hardware`() {
        val encoder = VideoEncoderCapabilities(
            VideoEncoderRequest(
                width = 640,
                height = 360,
                frameRate = 30,
                bitrateBps = 1_000_000,
                iFrameIntervalSeconds = 1f,
            ),
            VideoEncoderCodecInfo("lab-avc", null, VideoEncoderHardwareAcceleration.Unknown, null, null, null),
            VideoEncoderSupport(true, true, true, true, true, true, true, 2, 2, 2, 640, 2, 360, 1, 1_000_000),
        )
        val raw = AndroidCapabilityProbeSnapshot(lanSecurePathAvailable = true, videoEncoder = encoder)
        val ordinary = raw.toLocalSnapshot(SessionRole.Host, 1)
        assertEquals(LocalCapabilityAvailability.SupportedButUnavailable, ordinary.localAvailability["video"])
        val lab = raw.copy(laboratorySurfaceEncoderQualified = true).toLocalSnapshot(SessionRole.Host, 1)
        assertEquals(LocalCapabilityAvailability.Available, lab.localAvailability["video"])
        assertEquals(0, lab.video.videoFlags and CapabilityBits.VIDEO_HARDWARE_ENCODE)
        assertTrue(lab.video.videoFlags and CapabilityBits.VIDEO_SURFACE_INPUT_ENCODE != 0)
        assertEquals(1, lab.video.videoPayloadVersion)
    }

    @Test
    fun directDiscoveryDoesNotAdvertiseAPathWithoutProductionBackend() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            directPathBackendImplemented = false,
            directPathAvailable = true,
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertEquals(CapabilityBits.PATH_LAN, snapshot.paths.implementedPathKinds)
        assertEquals(CapabilityBits.PATH_LAN, snapshot.paths.availablePathKinds)
        assertEquals(LocalCapabilityAvailability.Unsupported, snapshot.localAvailability["directPath"])
    }

    @Test
    fun implementedDirectBackendCanRemainRuntimeUnavailable() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            directPathBackendImplemented = true,
            directPathAvailable = false,
            standbyPathSupported = true,
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertTrue(snapshot.paths.implementedPathKinds and CapabilityBits.PATH_DIRECT != 0)
        assertEquals(0, snapshot.paths.availablePathKinds and CapabilityBits.PATH_DIRECT)
        assertEquals(LocalCapabilityAvailability.SupportedButUnavailable, snapshot.localAvailability["directPath"])
    }

    @Test
    fun availableDirectBackendAdvertisesBoundedStandbyModel() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            directPathBackendImplemented = true,
            directPathAvailable = true,
            standbyPathSupported = true,
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertEquals(
            CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT,
            snapshot.paths.availablePathKinds,
        )
        assertEquals(2, snapshot.paths.maxPaths)
        assertEquals(CapabilityBits.PATH_STANDBY_SUPPORTED, snapshot.paths.pathFlags)
        assertEquals(LocalCapabilityAvailability.Available, snapshot.localAvailability["directPath"])
    }

    @Test
    fun unavailableSystemAudioIsNotAdvertisedIntoCapabilityNegotiation() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            systemAudioCapture = AudioCaptureCapabilities(
                source = AudioCaptureSource.SystemAudio,
                available = false,
                lastError = AudioCaptureError.PermissionDenied,
            ),
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertEquals(0, snapshot.audio.audioFlags and CapabilityBits.AUDIO_SYSTEM_CAPTURE)
        assertEquals(LocalCapabilityAvailability.SupportedButUnavailable, snapshot.localAvailability["systemAudio"])
    }

    @Test
    fun unsupportedSystemAudioPlatformIsNotAdvertisedIntoCapabilityNegotiation() {
        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            systemAudioCapture = AudioCaptureCapabilities(
                source = AudioCaptureSource.SystemAudio,
                available = false,
                lastError = AudioCaptureError.UnsupportedPlatform,
            ),
        ).toLocalSnapshot(SessionRole.Host, 1)

        assertEquals(0, snapshot.audio.audioFlags and CapabilityBits.AUDIO_SYSTEM_CAPTURE)
        assertEquals(LocalCapabilityAvailability.SupportedButUnavailable, snapshot.localAvailability["systemAudio"])
    }

    @Test
    fun activelyQualifiedLegacyDecoderMakesClientVideoAvailableWithoutHostCapabilities() {
        val decoder = VideoDecoderCapabilities(
            config = VideoDecoderConfig(
                width = 1280,
                height = 720,
                expectedFrameRate = 60,
                configGeneration = 1L,
                codecSpecificData = listOf(byteArrayOf(1)),
            ),
            selectedCodec = VideoDecoderCodecInfo(
                codecName = "legacy-avc",
                canonicalName = null,
                hardwareAcceleration = VideoDecoderHardwareAcceleration.Unknown,
                softwareOnly = null,
                vendor = null,
                alias = null,
                lowLatencyFeatureSupported = null,
            ),
            support = VideoDecoderSupport(
                widthSupported = true,
                heightSupported = true,
                sizeSupported = true,
                sizeAndRateSupported = true,
                lowLatencyFeatureSupported = null,
                widthAlignment = 2,
                heightAlignment = 2,
                minWidth = 16,
                maxWidth = 1920,
                minHeight = 16,
                maxHeight = 1080,
            ),
            error = VideoDecoderError.None,
            qualification = VideoDecoderQualification.ActivePass,
        )

        val snapshot = AndroidCapabilityProbeSnapshot(
            lanSecurePathAvailable = true,
            videoDecoder = decoder,
        ).toLocalSnapshot(SessionRole.Client, 1)

        assertEquals(LocalCapabilityAvailability.Available, snapshot.localAvailability["video"])
        assertTrue(snapshot.video.videoFlags and CapabilityBits.VIDEO_HARDWARE_DECODE != 0)
        assertTrue(snapshot.video.videoFlags and CapabilityBits.VIDEO_SURFACE_OUTPUT_DECODE != 0)
    }
}
