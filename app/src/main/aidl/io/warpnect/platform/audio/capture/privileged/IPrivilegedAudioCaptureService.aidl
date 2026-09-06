package io.warpnect.platform.audio.capture.privileged;

import android.os.Bundle;

interface IPrivilegedAudioCaptureService {
    Bundle querySystemAudioCapabilities() = 0;

    Bundle prepareSystemAudioCapture(
        int sampleRateHz,
        int channelCount,
        int targetChunkFrames,
        long targetChunkDurationUs,
        int sharedRingSlotCount,
        int targetUid
    ) = 1;

    int startSystemAudioCapture() = 2;

    int stopSystemAudioCapture() = 3;

    Bundle getSystemAudioState() = 4;

    void destroy() = 16777114;
}
