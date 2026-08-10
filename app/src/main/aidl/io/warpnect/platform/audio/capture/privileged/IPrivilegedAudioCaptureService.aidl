package io.warpnect.platform.audio.capture.privileged;

import android.os.Bundle;

interface IPrivilegedAudioCaptureService {
    Bundle querySystemAudioCapabilities();

    Bundle prepareSystemAudioCapture(
        int sampleRateHz,
        int channelCount,
        int targetChunkFrames,
        long targetChunkDurationUs,
        int sharedRingSlotCount,
        int targetUid
    );

    int startSystemAudioCapture();

    int stopSystemAudioCapture();

    Bundle getSystemAudioState();
}
