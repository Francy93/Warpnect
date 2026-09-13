package io.warpnect.debug.directshell;

import java.util.Locale;

/** Bounded PCM-only diagnostics for the DEBUG DirectShell REMOTE_SUBMIX experiment. */
final class DirectShellAudioProbeMetrics {
    static final double VALIDATION_TONE_HZ = 997.0;

    private DirectShellAudioProbeMetrics() {}

    static final class Accumulator {
        private final int sampleRateHz;
        private final int channelCount;
        private long bytesRead;
        private long framesRead;
        private long sampleCount;
        private long nonZeroSamples;
        private long clippingSamples;
        private long readErrors;
        private double sampleSquareSum;
        private int peak;
        private double toneCosineSum;
        private double toneSineSum;
        private boolean hasPreviousLeftSample;
        private int previousLeftSample;
        private long positiveZeroCrossings;

        Accumulator(int sampleRateHz, int channelCount) {
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
        }

        void acceptPcm16Le(byte[] data, int byteCount) {
            if (data == null || byteCount <= 0) return;
            int boundedBytes = Math.min(byteCount, data.length);
            int frameBytes = channelCount * Short.BYTES;
            int completeBytes = boundedBytes - boundedBytes % frameBytes;
            bytesRead += completeBytes;
            for (int offset = 0; offset < completeBytes; offset += frameBytes) {
                int left = pcm16Le(data, offset);
                accumulateToneFrame(left, framesRead);
                for (int channel = 0; channel < channelCount; channel++) {
                    int sample = pcm16Le(data, offset + channel * Short.BYTES);
                    int magnitude = sample == Short.MIN_VALUE
                            ? Short.MAX_VALUE + 1
                            : Math.abs(sample);
                    sampleCount++;
                    sampleSquareSum += (double) sample * sample;
                    if (sample != 0) nonZeroSamples++;
                    if (magnitude >= Short.MAX_VALUE) clippingSamples++;
                    if (magnitude > peak) peak = magnitude;
                }
                framesRead++;
            }
        }

        void recordReadError() {
            readErrors++;
        }

        Snapshot snapshot() {
            double rms = sampleCount == 0L ? 0.0 : Math.sqrt(sampleSquareSum / sampleCount);
            double toneAmplitude = framesRead == 0L
                    ? 0.0
                    : 2.0 * Math.hypot(toneCosineSum, toneSineSum) / framesRead;
            double toneEnergyRatio = rms == 0.0
                    ? 0.0
                    : Math.min(1.0, (toneAmplitude * toneAmplitude / 2.0) / (rms * rms));
            double positiveZeroCrossingHz = framesRead == 0L
                    ? 0.0
                    : positiveZeroCrossings * (double) sampleRateHz / framesRead;
            return new Snapshot(
                    sampleRateHz,
                    channelCount,
                    bytesRead,
                    framesRead,
                    sampleCount,
                    nonZeroSamples,
                    clippingSamples,
                    readErrors,
                    peak,
                    rms,
                    toneAmplitude,
                    toneEnergyRatio,
                    positiveZeroCrossingHz
            );
        }

        private void accumulateToneFrame(int sample, long frameIndex) {
            if (hasPreviousLeftSample && previousLeftSample <= 0 && sample > 0) {
                positiveZeroCrossings++;
            }
            previousLeftSample = sample;
            hasPreviousLeftSample = true;
            double phase = 2.0 * Math.PI * VALIDATION_TONE_HZ * frameIndex / sampleRateHz;
            toneCosineSum += sample * Math.cos(phase);
            toneSineSum += sample * Math.sin(phase);
        }

        private static int pcm16Le(byte[] data, int offset) {
            return (short) ((data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8));
        }
    }

    static final class Snapshot {
        final int sampleRateHz;
        final int channelCount;
        final long bytesRead;
        final long framesRead;
        final long sampleCount;
        final long nonZeroSamples;
        final long clippingSamples;
        final long readErrors;
        final int peak;
        final double rms;
        final double toneAmplitude;
        final double toneEnergyRatio;
        final double positiveZeroCrossingHz;

        Snapshot(
                int sampleRateHz,
                int channelCount,
                long bytesRead,
                long framesRead,
                long sampleCount,
                long nonZeroSamples,
                long clippingSamples,
                long readErrors,
                int peak,
                double rms,
                double toneAmplitude,
                double toneEnergyRatio,
                double positiveZeroCrossingHz
        ) {
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
            this.bytesRead = bytesRead;
            this.framesRead = framesRead;
            this.sampleCount = sampleCount;
            this.nonZeroSamples = nonZeroSamples;
            this.clippingSamples = clippingSamples;
            this.readErrors = readErrors;
            this.peak = peak;
            this.rms = rms;
            this.toneAmplitude = toneAmplitude;
            this.toneEnergyRatio = toneEnergyRatio;
            this.positiveZeroCrossingHz = positiveZeroCrossingHz;
        }

        boolean hasRealPcm() {
            return framesRead > 0L && nonZeroSamples > 0L && rms > 1.0;
        }

        String toPayload() {
            return "sample_rate_hz=" + sampleRateHz + '\n' +
                    "channel_count=" + channelCount + '\n' +
                    "bytes_read=" + bytesRead + '\n' +
                    "frames_read=" + framesRead + '\n' +
                    "sample_count=" + sampleCount + '\n' +
                    "non_zero_samples=" + nonZeroSamples + '\n' +
                    "clipping_samples=" + clippingSamples + '\n' +
                    "read_errors=" + readErrors + '\n' +
                    "peak=" + peak + '\n' +
                    "rms=" + decimal(rms) + '\n' +
                    "positive_zero_crossing_hz=" + decimal(positiveZeroCrossingHz) + '\n' +
                    "tone_997hz_amplitude=" + decimal(toneAmplitude) + '\n' +
                    "tone_997hz_energy_ratio=" + decimal(toneEnergyRatio) + '\n' +
                    "real_pcm=" + hasRealPcm();
        }

        private static String decimal(double value) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
    }
}
