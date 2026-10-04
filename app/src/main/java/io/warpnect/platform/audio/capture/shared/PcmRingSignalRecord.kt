package io.warpnect.platform.audio.capture.shared

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmRingSignalRecord(
    val slotIndex: Int,
    val generation: Int,
) {
    /** A single worker's fixed-size wire scratch; no per-notification buffer or ACK record. */
    class Codec {
        private val scratch = ByteArray(WIRE_SIZE)
        private val buffer = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)

        fun write(output: OutputStream, slotIndex: Int, generation: Int): Boolean = try {
            buffer.putInt(0, slotIndex)
            buffer.putInt(4, generation)
            output.write(scratch)
            output.flush()
            true
        } catch (_: Exception) {
            false
        }

        fun discardAck(input: InputStream): Boolean {
            var offset = 0
            while (offset < WIRE_SIZE) {
                val read = try {
                    input.read(scratch, offset, WIRE_SIZE - offset)
                } catch (_: Exception) {
                    return false
                }
                if (read <= 0) return false
                offset += read
            }
            return true
        }
    }

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
