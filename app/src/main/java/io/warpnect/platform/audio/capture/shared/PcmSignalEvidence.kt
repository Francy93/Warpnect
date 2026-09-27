package io.warpnect.platform.audio.capture.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Bounded diagnostic observer. No PCM storage or network/control payload. */
internal class PcmSignalEvidence(private val frequency: Double = 997.0) {
    var samples = 0L
        private set
    var nonZero = 0L
        private set
    var peak = 0
        private set
    var clips = 0L
        private set
    private var squares = 0.0
    private var real = 0.0
    private var imaginary = 0.0
    private var monoFrames = 0L

    fun accept(buffer: ByteBuffer, offset: Int, bytes: Int, firstFrame: Long) {
        require(offset >= 0 && bytes >= 0 && bytes % 4 == 0 && offset.toLong() + bytes <= buffer.capacity())
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        repeat(bytes / 2) { index ->
            val value = pcm.getShort(offset + index * 2).toInt()
            samples++
            if (value != 0) nonZero++
            peak = maxOf(peak, abs(value))
            if (abs(value) >= 32767) clips++
            squares += value.toDouble() * value
            if (index % 2 == 0) {
                val phase = 2.0 * Math.PI * frequency * (firstFrame + index / 2) / 48_000.0
                real += value * cos(phase)
                imaginary += value * sin(phase)
                monoFrames++
            }
        }
    }

    val rms: Double get() = if (samples == 0L) 0.0 else sqrt(squares / samples)
    val toneAmplitude: Double
        get() = if (monoFrames == 0L) 0.0 else 2 * sqrt(real * real + imaginary * imaginary) / monoFrames

    fun validTone(silence: PcmSignalEvidence): Boolean = samples >= 48_000 && nonZero > samples / 2 &&
        rms > 1000 && rms > silence.rms * 5 + 500 && toneAmplitude > 1000
}
