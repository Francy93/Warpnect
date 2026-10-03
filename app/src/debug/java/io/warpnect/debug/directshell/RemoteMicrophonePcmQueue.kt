package io.warpnect.debug.directshell

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Expired microphone history must not displace fresh live PCM at admission. */
internal class RemoteMicrophonePcmQueue(capacity: Int) {
    private val queue = ArrayBlockingQueue<ByteArray>(capacity)
    private val expired = AtomicLong()
    val expiredFrames: Long get() = expired.get()
    val size: Int get() = queue.size

    fun offer(packet: ByteArray, nowNs: Long): Boolean {
        repeat(queue.size) {
            val oldest = queue.peek() ?: return@repeat
            val enqueued = ByteBuffer.wrap(oldest).order(ByteOrder.LITTLE_ENDIAN).getLong(12)
            if (DirectShellMicrophoneFrame.isFresh(enqueued, nowNs)) return queue.offer(packet)
            if (queue.remove(oldest)) {
                expired.addAndGet(((oldest.size - DirectShellMicrophoneFrame.HEADER_BYTES) / 2).toLong())
                oldest.fill(0)
            }
        }
        return queue.offer(packet)
    }

    fun poll(timeout: Long, unit: TimeUnit): ByteArray? = queue.poll(timeout, unit)
    fun poll(): ByteArray? = queue.poll()
}
