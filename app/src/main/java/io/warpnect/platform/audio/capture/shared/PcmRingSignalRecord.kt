package io.warpnect.platform.audio.capture.shared

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmRingSignalRecord(
    val slotIndex: Int,
    val generation: Int,
) {
    companion object {
        const val WIRE_SIZE = 8

        fun write(
            output: OutputStream,
            record: PcmRingSignalRecord,
            scratch: ByteArray = ByteArray(WIRE_SIZE),
        ): Boolean = try {
            ByteBuffer.wrap(scratch)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(record.slotIndex)
                .putInt(record.generation)
            output.write(scratch)
            output.flush()
            true
        } catch (_: Exception) {
            false
        }

        fun read(input: InputStream, scratch: ByteArray = ByteArray(WIRE_SIZE)): PcmRingSignalRecord? {
            var offset = 0
            while (offset < WIRE_SIZE) {
                val read = try {
                    input.read(scratch, offset, WIRE_SIZE - offset)
                } catch (_: Exception) {
                    return null
                }
                if (read < 0) {
                    return null
                }
                offset += read
            }
            val buffer = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)
            return PcmRingSignalRecord(
                slotIndex = buffer.int,
                generation = buffer.int,
            )
        }
    }
}
