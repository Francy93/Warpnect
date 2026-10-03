package io.warpnect.debug.directshell

import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface RemoteMicrophonePcmDestination {
    fun start(): Boolean
    fun submit(buffer: ByteBuffer, offset: Int, frames: Int)
}

/** Session-local PCM handoff over the existing authenticated, purpose-specific feature channel. */
internal class DirectShellRemoteMicrophoneSource(
    port: Int,
    private val generation: Long,
    secret: ByteArray,
) : AutoCloseable, RemoteMicrophonePcmDestination {
    private val client = DirectShellPcmRingProbeClient.connectLoopback(port, secret)
    private val queue = RemoteMicrophonePcmQueue(16)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val submitted = AtomicLong()
    private val written = AtomicLong()
    private val rejected = AtomicLong()
    private val hwm = AtomicLong()
    private val absentDropped = AtomicLong()
    private val staleDropped = AtomicLong()
    private val transitionDropped = AtomicLong()
    private val rejectionSamples = AtomicLong()

    @Volatile private var lastFeedResult = Int.MIN_VALUE

    @Volatile private var lastFeedAtNs = 0L

    private var maxAgeNs = 0L
    private var maxDequeueAgeNs = 0L
    private val dequeueAgeBins = LongArray(9)
    private var calls = 0L
    private var zeroWrites = 0L
    private var maxCallUs = 0L
    private var slowCallSamples = 0
    private var worker: Thread? = null

    fun prepare(): String = client.call(DirectShellProbeProtocol.Command.MICROPHONE_PREPARE).payload

    override fun start(): Boolean {
        if (running.get()) return true
        if (closed.get() || client.call(DirectShellProbeProtocol.Command.MICROPHONE_START).payload != "SUPPORTED") {
            return false
        }
        if (!running.compareAndSet(false, true)) return true
        worker = Thread(::drain, "DirectShellRemoteMicPcm").also { it.start() }
        return true
    }

    override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) {
        if (!running.get() || closed.get() || frames !in 1..960) return
        val payload = ByteArray(HEADER_BYTES + frames * 2)
        val target = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        target.putLong(generation).putInt(frames).putLong(System.nanoTime())
        val input = buffer.duplicate().apply {
            position(offset)
            limit(offset + frames * 2)
        }
        target.put(input)
        if (queue.offer(payload, System.nanoTime())) {
            submitted.addAndGet(frames.toLong())
            hwm.accumulateAndGet(queue.size.toLong(), ::maxOf)
        } else {
            rejected.addAndGet(frames.toLong())
            if (rejectionSamples.getAndIncrement() < 8) {
                Log.i(
                    TAG,
                    "REMOTE_MIC_QUEUE_REJECT generation=$generation local_ns=${System.nanoTime()} " +
                        "frames=$frames queue_size=${queue.size} last_feed_result=$lastFeedResult " +
                        "last_feed_at_ns=$lastFeedAtNs",
                )
            }
            payload.fill(0)
        }
    }

    private fun drain() {
        var nextSample = System.nanoTime() + 5_000_000_000L
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (running.get()) {
                val payload = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val dequeuedAge = System.nanoTime() -
                    ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getLong(12)
                maxDequeueAgeNs = maxOf(maxDequeueAgeNs, dequeuedAge)
                val ageBin = DEQUEUE_AGE_LIMITS_NS.indexOfFirst { dequeuedAge < it }
                dequeueAgeBins[if (ageBin < 0) dequeueAgeBins.lastIndex else ageBin]++
                try {
                    var remaining = payload
                    while (running.get()) {
                        val before = System.nanoTime()
                        val enqueued = ByteBuffer.wrap(remaining).order(ByteOrder.LITTLE_ENDIAN).getLong(12)
                        val frames = (remaining.size - HEADER_BYTES) / 2
                        val age = before - enqueued
                        if (!DirectShellMicrophoneFrame.isFresh(enqueued, before)) {
                            staleDropped.addAndGet(frames.toLong())
                            break
                        }
                        val response = client.call(DirectShellProbeProtocol.Command.MICROPHONE_FEED, remaining)
                        val callNs = System.nanoTime() - before
                        maxCallUs = maxOf(maxCallUs, callNs / 1_000)
                        if (callNs >= 20_000_000L && slowCallSamples++ < 16) {
                            Log.i(
                                TAG,
                                "REMOTE_MIC_CALL_TIMING generation=$generation request_id=${response.requestId} " +
                                    "elapsed_ns=$callNs queue=${queue.size} pcm_age_ns=$age",
                            )
                        }
                        calls++
                        val count = response.payload.toIntOrNull() ?: error("invalid_feed_response")
                        check(
                            response.status == DirectShellProbeProtocol.Status.OK &&
                                (
                                    count in 0..frames || count == DROP_NO_CONSUMER || count == DROP_STALE ||
                                        count == DROP_CONSUMER_TRANSITION
                                    ),
                        )
                        lastFeedResult = count
                        lastFeedAtNs = System.nanoTime()
                        if (count == DROP_NO_CONSUMER) absentDropped.addAndGet(frames.toLong())
                        if (count == DROP_STALE) staleDropped.addAndGet(frames.toLong())
                        if (count == DROP_CONSUMER_TRANSITION) transitionDropped.addAndGet(frames.toLong())
                        if (count >= 0) {
                            maxAgeNs = maxOf(maxAgeNs, age)
                            written.addAndGet(count.toLong())
                        }
                        if (count == 0) zeroWrites++
                        if (System.nanoTime() >= nextSample) {
                            Log.i(
                                TAG,
                                "REMOTE_MIC_PROGRESS generation=$generation submitted=${submitted.get()} " +
                                    "written=${written.get()} queue_rejected=${rejected.get()} " +
                                    "queue_size=${queue.size} queue_hwm=${hwm.get()} " +
                                    "no_consumer_dropped=${absentDropped.get()} " +
                                    "stale_dropped=${staleDropped.get() + queue.expiredFrames} " +
                                    "admission_expired=${queue.expiredFrames} " +
                                    "transition_dropped=${transitionDropped.get()} " +
                                    "max_pcm_age_ns=$maxAgeNs " +
                                    "max_dequeue_age_ns=$maxDequeueAgeNs " +
                                    "dequeue_age_bins_lt1_3_5_10_20_30_40_80_else_ms=" +
                                    "${dequeueAgeBins.joinToString(",")} " +
                                    "calls=$calls zero_writes=$zeroWrites max_call_us=$maxCallUs",
                            )
                            nextSample = System.nanoTime() + 5_000_000_000L
                        }
                        if (count < 0) break
                        if (count == frames) break
                        if (count == 0) {
                            Thread.sleep(1)
                        } else {
                            val tail = ByteArray(HEADER_BYTES + (frames - count) * 2)
                            ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                                .putLong(generation).putInt(frames - count).putLong(enqueued)
                                .put(remaining, HEADER_BYTES + count * 2, tail.size - HEADER_BYTES)
                            if (remaining !== payload) remaining.fill(0)
                            remaining = tail
                        }
                    }
                    if (remaining !== payload) remaining.fill(0)
                } finally {
                    payload.fill(0)
                }
            }
        } catch (failure: Exception) {
            if (running.get()) {
                Log.w(
                    TAG,
                    "REMOTE_MIC_FAILURE generation=$generation type=${failure.javaClass.simpleName}",
                )
            }
        } finally {
            running.set(false)
            runCatching { client.close() }
            clearQueue()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { client.close() }
        worker?.interrupt()
        worker?.join(2_000)
        clearQueue()
        Log.i(
            TAG,
            "REMOTE_MIC_RELEASE generation=$generation submitted=${submitted.get()} written=${written.get()} " +
                "queue_rejected=${rejected.get()} queue_hwm=${hwm.get()} " +
                "no_consumer_dropped=${absentDropped.get()} " +
                "stale_dropped=${staleDropped.get() + queue.expiredFrames} " +
                "admission_expired=${queue.expiredFrames} " +
                "transition_dropped=${transitionDropped.get()} " +
                "max_pcm_age_ns=$maxAgeNs max_dequeue_age_ns=$maxDequeueAgeNs " +
                "dequeue_age_bins_lt1_3_5_10_20_30_40_80_else_ms=${dequeueAgeBins.joinToString(",")} uid_rules=NONE",
        )
    }

    private fun clearQueue() {
        while (true) (queue.poll() ?: break).fill(0)
    }

    private companion object {
        const val HEADER_BYTES = DirectShellMicrophoneFrame.HEADER_BYTES
        const val DROP_NO_CONSUMER = DirectShellMicrophoneFrame.DROP_NO_CONSUMER
        const val DROP_STALE = DirectShellMicrophoneFrame.DROP_STALE
        const val DROP_CONSUMER_TRANSITION = DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION
        const val TAG = "WarpnectRemoteMic"
        val DEQUEUE_AGE_LIMITS_NS = longArrayOf(1, 3, 5, 10, 20, 30, 40, 80).map { it * 1_000_000L }
    }
}
