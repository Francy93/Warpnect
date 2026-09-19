package io.warpnect.debug.directshell

import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectShellRemoteE2eAudioContractTest {
    @Test
    fun acceptsTheExistingFiveMillisecondSystemAudioContract() {
        val request = AudioCaptureRequest(
            source = AudioCaptureSource.SystemAudio,
            preferredSampleRateHz = 48_000,
            channelCount = 2,
            targetChunkDurationUs = 5_000L,
            sharedRingSlotCount = 8,
        )

        assertEquals(AudioCaptureError.None, DirectShellRemoteE2eAudioContract.validate(request, apiLevel = 31))
        assertEquals(240, DirectShellRemoteE2eAudioContract.format().targetFramesPerChunk)
    }

    @Test
    fun rejectsARequestThatCannotUseTheExistingDirectShellRing() {
        val base = AudioCaptureRequest(source = AudioCaptureSource.SystemAudio)

        assertEquals(
            AudioCaptureError.UnsupportedPlatform,
            DirectShellRemoteE2eAudioContract.validate(base, apiLevel = 30),
        )
        assertEquals(
            AudioCaptureError.InvalidChunkDuration,
            DirectShellRemoteE2eAudioContract.validate(base.copy(targetChunkDurationUs = 10_000L), apiLevel = 31),
        )
        assertEquals(
            AudioCaptureError.InvalidRequest,
            DirectShellRemoteE2eAudioContract.validate(base.copy(sharedRingSlotCount = 7), apiLevel = 31),
        )
    }
}
