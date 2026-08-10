package io.warpnect.platform.audio.capture.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

object SharedPcmAudioRingLayout {
    const val VERSION = 1
    const val MAGIC = 0x57415043
    const val HEADER_BYTES = 64
    const val SLOT_METADATA_BYTES = 64

    const val SLOT_STATE_FREE = 0
    const val SLOT_STATE_WRITING = 1
    const val SLOT_STATE_FILLED = 2
    const val SLOT_STATE_READING = 3

    private const val HEADER_MAGIC = 0
    private const val HEADER_VERSION = 4
    private const val HEADER_SLOT_COUNT = 8
    private const val HEADER_PAYLOAD_CAPACITY = 12
    private const val HEADER_SLOT_STRIDE = 16
    private const val HEADER_OCCUPANCY = 20
    private const val HEADER_HIGH_WATER = 24
    private const val HEADER_OVERRUNS = 28

    private const val SLOT_STATE = 0
    private const val SLOT_GENERATION = 4
    private const val SLOT_SEQUENCE = 8
    private const val SLOT_VALID_BYTES = 16
    private const val SLOT_FRAME_COUNT = 20
    private const val SLOT_FIRST_FRAME = 24
    private const val SLOT_CAPTURE_TIME_NS = 32
    private const val SLOT_TIMESTAMP_QUALITY = 40
    private const val SLOT_FLAGS = 44
    private const val SLOT_PUBLISH_TIME_NS = 48

    fun totalBytes(slotCount: Int, payloadCapacityBytes: Int): Int {
        if (slotCount <= 0 || payloadCapacityBytes <= 0) {
            return 0
        }
        val slotStride = SLOT_METADATA_BYTES.toLong() + payloadCapacityBytes.toLong()
        val total = HEADER_BYTES.toLong() + slotStride * slotCount.toLong()
        return if (total > Int.MAX_VALUE) 0 else total.toInt()
    }

    fun initialize(memory: ByteBuffer, slotCount: Int, payloadCapacityBytes: Int): Boolean {
        val total = totalBytes(slotCount, payloadCapacityBytes)
        if (total <= 0 || memory.capacity() < total) {
            return false
        }
        val buffer = ordered(memory)
        buffer.putInt(HEADER_MAGIC, MAGIC)
        buffer.putInt(HEADER_VERSION, VERSION)
        buffer.putInt(HEADER_SLOT_COUNT, slotCount)
        buffer.putInt(HEADER_PAYLOAD_CAPACITY, payloadCapacityBytes)
        buffer.putInt(HEADER_SLOT_STRIDE, SLOT_METADATA_BYTES + payloadCapacityBytes)
        buffer.putInt(HEADER_OCCUPANCY, 0)
        buffer.putInt(HEADER_HIGH_WATER, 0)
        buffer.putInt(HEADER_OVERRUNS, 0)
        repeat(slotCount) { slot ->
            val base = slotMetadataOffset(slot, payloadCapacityBytes)
            buffer.putInt(base + SLOT_STATE, SLOT_STATE_FREE)
            buffer.putInt(base + SLOT_GENERATION, 0)
            buffer.putLong(base + SLOT_SEQUENCE, 0L)
            buffer.putInt(base + SLOT_VALID_BYTES, 0)
            buffer.putInt(base + SLOT_FRAME_COUNT, 0)
            buffer.putLong(base + SLOT_FIRST_FRAME, 0L)
            buffer.putLong(base + SLOT_CAPTURE_TIME_NS, 0L)
            buffer.putInt(base + SLOT_TIMESTAMP_QUALITY, 0)
            buffer.putInt(base + SLOT_FLAGS, 0)
            buffer.putLong(base + SLOT_PUBLISH_TIME_NS, 0L)
        }
        return true
    }

    fun validate(memory: ByteBuffer): Boolean {
        val buffer = ordered(memory)
        if (buffer.capacity() < HEADER_BYTES) {
            return false
        }
        if (buffer.getInt(HEADER_MAGIC) != MAGIC || buffer.getInt(HEADER_VERSION) != VERSION) {
            return false
        }
        val slotCount = slotCount(buffer)
        val payloadCapacity = payloadCapacity(buffer)
        val stride = buffer.getInt(HEADER_SLOT_STRIDE)
        return slotCount > 0 &&
            payloadCapacity > 0 &&
            stride == SLOT_METADATA_BYTES + payloadCapacity &&
            buffer.capacity() >= totalBytes(slotCount, payloadCapacity)
    }

    fun slotCount(memory: ByteBuffer): Int = ordered(memory).getInt(HEADER_SLOT_COUNT)

    fun payloadCapacity(memory: ByteBuffer): Int = ordered(memory).getInt(HEADER_PAYLOAD_CAPACITY)

    fun occupancy(memory: ByteBuffer): Int = ordered(memory).getInt(HEADER_OCCUPANCY)

    fun highWater(memory: ByteBuffer): Int = ordered(memory).getInt(HEADER_HIGH_WATER)

    fun overruns(memory: ByteBuffer): Int = ordered(memory).getInt(HEADER_OVERRUNS)

    fun incrementOverruns(memory: ByteBuffer) {
        val buffer = ordered(memory)
        buffer.putInt(HEADER_OVERRUNS, buffer.getInt(HEADER_OVERRUNS) + 1)
    }

    fun slotState(memory: ByteBuffer, slot: Int): Int {
        val buffer = ordered(memory)
        return buffer.getInt(slotMetadataOffset(slot, payloadCapacity(buffer)) + SLOT_STATE)
    }

    fun slotGeneration(memory: ByteBuffer, slot: Int): Int {
        val buffer = ordered(memory)
        return buffer.getInt(slotMetadataOffset(slot, payloadCapacity(buffer)) + SLOT_GENERATION)
    }

    fun beginWrite(memory: ByteBuffer): Int {
        val buffer = ordered(memory)
        val count = slotCount(buffer)
        val payloadCapacity = payloadCapacity(buffer)
        for (slot in 0 until count) {
            val base = slotMetadataOffset(slot, payloadCapacity)
            if (buffer.getInt(base + SLOT_STATE) == SLOT_STATE_FREE) {
                buffer.putInt(base + SLOT_STATE, SLOT_STATE_WRITING)
                buffer.putInt(base + SLOT_GENERATION, buffer.getInt(base + SLOT_GENERATION) + 1)
                return slot
            }
        }
        incrementOverruns(buffer)
        return -1
    }

    fun publish(
        memory: ByteBuffer,
        slot: Int,
        sequence: Long,
        validBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQualityCode: Int,
        flags: Int,
        publishTimeNs: Long,
    ): Boolean {
        val buffer = ordered(memory)
        val payloadCapacity = payloadCapacity(buffer)
        if (slot !in 0 until slotCount(buffer) || validBytes < 0 || validBytes > payloadCapacity) {
            return false
        }
        val base = slotMetadataOffset(slot, payloadCapacity)
        if (buffer.getInt(base + SLOT_STATE) != SLOT_STATE_WRITING) {
            return false
        }
        buffer.putLong(base + SLOT_SEQUENCE, sequence)
        buffer.putInt(base + SLOT_VALID_BYTES, validBytes)
        buffer.putInt(base + SLOT_FRAME_COUNT, frameCount)
        buffer.putLong(base + SLOT_FIRST_FRAME, firstFramePosition)
        buffer.putLong(base + SLOT_CAPTURE_TIME_NS, captureTimeNs)
        buffer.putInt(base + SLOT_TIMESTAMP_QUALITY, timestampQualityCode)
        buffer.putInt(base + SLOT_FLAGS, flags)
        buffer.putLong(base + SLOT_PUBLISH_TIME_NS, publishTimeNs)
        buffer.putInt(base + SLOT_STATE, SLOT_STATE_FILLED)
        val occupancy = buffer.getInt(HEADER_OCCUPANCY) + 1
        buffer.putInt(HEADER_OCCUPANCY, occupancy)
        buffer.putInt(HEADER_HIGH_WATER, maxOf(buffer.getInt(HEADER_HIGH_WATER), occupancy))
        return true
    }

    fun cancelWrite(memory: ByteBuffer, slot: Int): Boolean {
        val buffer = ordered(memory)
        val payloadCapacity = payloadCapacity(buffer)
        if (slot !in 0 until slotCount(buffer)) {
            return false
        }
        val base = slotMetadataOffset(slot, payloadCapacity)
        if (buffer.getInt(base + SLOT_STATE) != SLOT_STATE_WRITING) {
            return false
        }
        buffer.putInt(base + SLOT_VALID_BYTES, 0)
        buffer.putInt(base + SLOT_FRAME_COUNT, 0)
        buffer.putInt(base + SLOT_STATE, SLOT_STATE_FREE)
        return true
    }

    fun beginRead(memory: ByteBuffer, slot: Int, generation: Int): PcmSlotView? {
        val buffer = ordered(memory)
        val payloadCapacity = payloadCapacity(buffer)
        if (slot !in 0 until slotCount(buffer)) {
            return null
        }
        val base = slotMetadataOffset(slot, payloadCapacity)
        if (buffer.getInt(base + SLOT_STATE) != SLOT_STATE_FILLED) {
            return null
        }
        if (buffer.getInt(base + SLOT_GENERATION) != generation) {
            return null
        }
        buffer.putInt(base + SLOT_STATE, SLOT_STATE_READING)
        buffer.putInt(HEADER_OCCUPANCY, (buffer.getInt(HEADER_OCCUPANCY) - 1).coerceAtLeast(0))
        return PcmSlotView(
            slotIndex = slot,
            generation = generation,
            sequence = buffer.getLong(base + SLOT_SEQUENCE),
            validBytes = buffer.getInt(base + SLOT_VALID_BYTES),
            frameCount = buffer.getInt(base + SLOT_FRAME_COUNT),
            firstFramePosition = buffer.getLong(base + SLOT_FIRST_FRAME),
            captureTimeNs = buffer.getLong(base + SLOT_CAPTURE_TIME_NS),
            timestampQualityCode = buffer.getInt(base + SLOT_TIMESTAMP_QUALITY),
            flags = buffer.getInt(base + SLOT_FLAGS),
            publishTimeNs = buffer.getLong(base + SLOT_PUBLISH_TIME_NS),
            payload = payloadSlice(buffer, slot, payloadCapacity),
        )
    }

    fun completeRead(memory: ByteBuffer, slot: Int, generation: Int): Boolean {
        val buffer = ordered(memory)
        val payloadCapacity = payloadCapacity(buffer)
        if (slot !in 0 until slotCount(buffer)) {
            return false
        }
        val base = slotMetadataOffset(slot, payloadCapacity)
        if (buffer.getInt(base + SLOT_GENERATION) != generation) {
            return false
        }
        buffer.putInt(base + SLOT_VALID_BYTES, 0)
        buffer.putInt(base + SLOT_FRAME_COUNT, 0)
        buffer.putInt(base + SLOT_STATE, SLOT_STATE_FREE)
        return true
    }

    fun payloadSlice(memory: ByteBuffer, slot: Int): ByteBuffer {
        val buffer = ordered(memory)
        return payloadSlice(buffer, slot, payloadCapacity(buffer))
    }

    private fun payloadSlice(memory: ByteBuffer, slot: Int, payloadCapacity: Int): ByteBuffer {
        val duplicate = memory.duplicate().order(ByteOrder.nativeOrder())
        val offset = slotPayloadOffset(slot, payloadCapacity)
        duplicate.position(offset)
        duplicate.limit(offset + payloadCapacity)
        return duplicate.slice().order(ByteOrder.nativeOrder())
    }

    private fun slotMetadataOffset(slot: Int, payloadCapacityBytes: Int): Int =
        HEADER_BYTES + slot * (SLOT_METADATA_BYTES + payloadCapacityBytes)

    private fun slotPayloadOffset(slot: Int, payloadCapacityBytes: Int): Int =
        slotMetadataOffset(slot, payloadCapacityBytes) + SLOT_METADATA_BYTES

    private fun ordered(memory: ByteBuffer): ByteBuffer = memory.duplicate().order(ByteOrder.LITTLE_ENDIAN)
}

data class PcmSlotView(
    val slotIndex: Int,
    val generation: Int,
    val sequence: Long,
    val validBytes: Int,
    val frameCount: Int,
    val firstFramePosition: Long,
    val captureTimeNs: Long,
    val timestampQualityCode: Int,
    val flags: Int,
    val publishTimeNs: Long,
    val payload: ByteBuffer,
)
