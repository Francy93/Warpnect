package io.warpnect.platform.video.encoder;

interface IExactVideoEncoderProbeService {
    int probe(
        String codecName,
        String mimeType,
        int width,
        int height,
        int frameRate,
        int bitrateBps,
        String bitrateMode,
        int iFrameIntervalBits
    );
}
