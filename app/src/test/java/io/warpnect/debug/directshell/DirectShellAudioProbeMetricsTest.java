package io.warpnect.debug.directshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DirectShellAudioProbeMetricsTest {
    @Test
    public void deterministic997HzStereoToneProducesBoundedSignalMetrics() {
        int sampleRateHz = 48_000;
        int frames = sampleRateHz;
        byte[] pcm = new byte[frames * 2 * Short.BYTES];
        for (int frame = 0; frame < frames; frame++) {
            short value = (short) Math.round(
                    Math.sin(2.0 * Math.PI * DirectShellAudioProbeMetrics.VALIDATION_TONE_HZ * frame / sampleRateHz) *
                            8_192.0
            );
            int offset = frame * 4;
            pcm[offset] = (byte) value;
            pcm[offset + 1] = (byte) (value >>> 8);
            pcm[offset + 2] = (byte) value;
            pcm[offset + 3] = (byte) (value >>> 8);
        }

        DirectShellAudioProbeMetrics.Accumulator accumulator =
                new DirectShellAudioProbeMetrics.Accumulator(sampleRateHz, 2);
        accumulator.acceptPcm16Le(pcm, pcm.length);
        DirectShellAudioProbeMetrics.Snapshot snapshot = accumulator.snapshot();

        assertEquals(frames, snapshot.framesRead);
        assertEquals((long) frames * 2, snapshot.sampleCount);
        assertTrue(snapshot.nonZeroSamples > 0L);
        assertEquals(8_192, snapshot.peak);
        assertTrue(snapshot.rms > 5_700.0 && snapshot.rms < 5_900.0);
        assertTrue(snapshot.positiveZeroCrossingHz > 996.0 && snapshot.positiveZeroCrossingHz < 998.0);
        assertTrue(snapshot.toneAmplitude > 8_100.0 && snapshot.toneAmplitude < 8_300.0);
        assertTrue(snapshot.toneEnergyRatio > 0.99);
        assertTrue(snapshot.hasRealPcm());
    }

    @Test
    public void zeroPcmDoesNotMasqueradeAsCapturedDeviceAudio() {
        DirectShellAudioProbeMetrics.Accumulator accumulator =
                new DirectShellAudioProbeMetrics.Accumulator(48_000, 2);
        accumulator.acceptPcm16Le(new byte[4_800], 4_800);
        accumulator.recordReadError();
        DirectShellAudioProbeMetrics.Snapshot snapshot = accumulator.snapshot();

        assertEquals(1_200L, snapshot.framesRead);
        assertEquals(0L, snapshot.nonZeroSamples);
        assertEquals(0, snapshot.peak);
        assertEquals(0.0, snapshot.rms, 0.0);
        assertEquals(1L, snapshot.readErrors);
        assertFalse(snapshot.hasRealPcm());
    }
}
