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
    fun submitTimed(buffer: ByteBuffer, offset: Int, frames: Int, firstFramePosition: Long, admittedNs: Long) =
        submit(buffer, offset, frames)
}

/** PCM sending and result authentication have separate, Session-owned progress. */
internal class DirectShellRemoteMicrophoneSource(
    port: Int,
    private val generation: Long,
    secret: ByteArray,
) : AutoCloseable, RemoteMicrophonePcmDestination {
    private val client = DirectShellPcmRingProbeClient.connectLoopback(port, secret)
    private val resultChannel =
        try {
            DirectShellMicrophoneResultChannel.receiver(secret, generation)
        } catch (failure: Exception) {
            client.close()
            throw failure
        }
    private val queue = RemoteMicrophonePcmQueue(16)
    private val admissionLock = Any()
    private val resultLock = Any()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val submitted = AtomicLong()
    private val written = AtomicLong()
    private val rejected = AtomicLong()
    private val hwm = AtomicLong()
    private val absentDropped = AtomicLong()
    private val staleDropped = AtomicLong()
    private val senderExpired = AtomicLong()
    private val transitionDropped = AtomicLong()
    private val destinationCapacityDropped = AtomicLong()
    private val cancelled = AtomicLong()
    private val calls = AtomicLong()
    private val results = AtomicLong()
    private val zeroWrites = AtomicLong()
    private val partialWrites = AtomicLong()
    private val maxSubmitLockWaitNs = AtomicLong()
    private val maxResultLockHoldNs = AtomicLong()

    private val sentFrames = AtomicLong()
    private var lastTotals: DirectShellMicrophoneFeedTotals.Snapshot? = null
    private var maxSnapshotGapNs = 0L
    private var maxDequeueAgeNs = 0L
    private val dequeueAgeBins = LongArray(9)
    private var nextRequestId = 0L
    private var worker: Thread? = null
    private var reader: Thread? = null

    fun prepare(): String = client.call(DirectShellProbeProtocol.Command.MICROPHONE_PREPARE).payload

    override fun start(): Boolean {
        if (running.get()) return true
        if (closed.get()) return false
        val prepared = client.call(
            DirectShellProbeProtocol.Command.MICROPHONE_START,
            resultChannel.attachment(),
        )
        if (prepared.payload != "SUPPORTED") {
            return false
        }
        nextRequestId = client.beginMicrophoneStream()
        if (!running.compareAndSet(false, true)) return true
        reader = Thread(::readResults, "DirectShellRemoteMicResults").also { it.start() }
        worker = Thread(::send, "DirectShellRemoteMicPcm").also { it.start() }
        return true
    }

    override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) {
        val started = System.nanoTime()
        synchronized(admissionLock) {
            maxSubmitLockWaitNs.accumulateAndGet(System.nanoTime() - started, ::maxOf)
            if (!running.get() || closed.get() || frames !in 1..960) return
            val payload = ByteArray(HEADER_BYTES + frames * 2)
            val target = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            target.putLong(generation).putInt(frames).putLong(started)
            target.put(
                buffer.duplicate().apply {
                    position(offset)
                    limit(offset + frames * 2)
                },
            )
            if (queue.offer(payload, System.nanoTime())) {
                submitted.addAndGet(frames.toLong())
                hwm.accumulateAndGet(queue.size.toLong(), ::maxOf)
            } else {
                rejected.addAndGet(frames.toLong())
                payload.fill(0)
            }
        }
    }

    private fun send() {
        var nextSample = System.nanoTime() + 5_000_000_000L
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (running.get()) {
                val payload = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val frames = (payload.size - HEADER_BYTES) / 2
                var accounted = false
                try {
                    val enqueued = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getLong(12)
                    val age = System.nanoTime() - enqueued
                    maxDequeueAgeNs = maxOf(maxDequeueAgeNs, age)
                    val bin = DEQUEUE_AGE_LIMITS_NS.indexOfFirst { age < it }
                    dequeueAgeBins[if (bin < 0) dequeueAgeBins.lastIndex else bin]++
                    if (DirectShellMicrophoneFrame.isFresh(enqueued, System.nanoTime()) && running.get()) {
                        // Publish accounting before sending; a fast signed result cannot outrun it.
                        sentFrames.addAndGet(frames.toLong())
                        calls.incrementAndGet()
                        accounted = true
                        client.sendMicrophone(nextRequestId++, payload)
                    } else if (running.get()) {
                        senderExpired.addAndGet(frames.toLong())
                        accounted = true
                    } else {
                        cancelled.addAndGet(frames.toLong())
                        accounted = true
                    }
                    if (System.nanoTime() >= nextSample) {
                        Log.i(TAG, "REMOTE_MIC_PROGRESS ${metrics()}")
                        nextSample = System.nanoTime() + 5_000_000_000L
                    }
                } catch (failure: Exception) {
                    if (!accounted) cancelled.addAndGet(frames.toLong())
                    throw failure
                } finally {
                    payload.fill(0)
                }
            }
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    private fun readResults() {
        try {
            var previous = 0L
            var lastResultNs = System.nanoTime()
            while (running.get()) {
                val response = resultChannel.read(previous)
                synchronized(resultLock) {
                    val lockStarted = System.nanoTime()
                    if (closed.get() || !running.get()) return
                    val totals = DirectShellMicrophoneFeedTotals.decode(
                        response.payload,
                        lastTotals,
                        calls.get(),
                        sentFrames.get(),
                    )
                    check(response.requestId == totals.completed()) { "snapshot_identity_mismatch" }
                    lastTotals = totals
                    previous = totals.completed()
                    written.set(totals.values[1])
                    absentDropped.set(totals.values[2])
                    staleDropped.set(totals.values[3])
                    transitionDropped.set(totals.values[4])
                    destinationCapacityDropped.set(totals.values[5])
                    zeroWrites.set(totals.values[7])
                    partialWrites.set(totals.values[8])
                    results.set(totals.completed())
                    val now = System.nanoTime()
                    maxSnapshotGapNs = maxOf(maxSnapshotGapNs, now - lastResultNs)
                    lastResultNs = now
                    maxResultLockHoldNs.accumulateAndGet(System.nanoTime() - lockStarted, ::maxOf)
                }
            }
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    private fun fail(failure: Exception) {
        if (running.compareAndSet(true, false)) {
            Log.w(TAG, "REMOTE_MIC_FAILURE generation=$generation type=${failure.javaClass.simpleName}")
            runCatching { client.close() }
            resultChannel.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(admissionLock) {
            running.set(false)
        }
        runCatching { client.close() }
        resultChannel.close()
        worker?.interrupt()
        reader?.interrupt()
        worker?.join(2_000)
        reader?.join(2_000)
        while (true) {
            val payload = queue.poll() ?: break
            cancelled.addAndGet(((payload.size - HEADER_BYTES) / 2).toLong())
            payload.fill(0)
        }
        Log.i(TAG, "REMOTE_MIC_RELEASE ${metrics()} uid_rules=NONE")
    }

    private fun metrics() = "generation=$generation submitted=${submitted.get()} written=${written.get()} " +
        "queue_rejected=${rejected.get()} queue_size=${queue.size} queue_hwm=${hwm.get()} " +
        "no_consumer_dropped=${absentDropped.get()} " +
        "stale_dropped=${staleDropped.get() + senderExpired.get() + queue.expiredFrames} " +
        "sender_expired=${senderExpired.get()} " +
        "admission_expired=${queue.expiredFrames} transition_dropped=${transitionDropped.get()} " +
        "destination_capacity_dropped=${destinationCapacityDropped.get()} " +
        "cancelled_unresolved=${cancelled.get() + unconfirmedFrames()} " +
        "max_pcm_age_budget_ns=${DirectShellMicrophoneFrame.MAX_AGE_NS} " +
        "max_dequeue_age_ns=$maxDequeueAgeNs " +
        "dequeue_age_bins_lt1_3_5_10_20_30_40_80_else_ms=${dequeueAgeBins.joinToString(",")} " +
        "calls=${calls.get()} results=${results.get()} sent_frames=${sentFrames.get()} result_mailbox_slots=1 " +
        "result_transport=SESSION_LOOPBACK_UDP " +
        "zero_writes=${zeroWrites.get()} partial_writes=${partialWrites.get()} max_snapshot_gap_ns=$maxSnapshotGapNs " +
        "max_submit_lock_wait_ns=${maxSubmitLockWaitNs.get()} max_result_lock_hold_ns=${maxResultLockHoldNs.get()}"

    private fun unconfirmedFrames() = maxOf(
        0L,
        sentFrames.get() - written.get() - absentDropped.get() - staleDropped.get() -
            transitionDropped.get() - destinationCapacityDropped.get(),
    )

    private companion object {
        const val HEADER_BYTES = DirectShellMicrophoneFrame.HEADER_BYTES
        const val TAG = "WarpnectRemoteMic"
        val DEQUEUE_AGE_LIMITS_NS = longArrayOf(1, 3, 5, 10, 20, 30, 40, 80).map { it * 1_000_000L }
    }
}
