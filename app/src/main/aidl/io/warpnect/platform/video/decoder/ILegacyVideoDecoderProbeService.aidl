package io.warpnect.platform.video.decoder;

interface ILegacyVideoDecoderProbeService {
    int probe(String codecName, int qualificationAlgorithmVersion);
}
