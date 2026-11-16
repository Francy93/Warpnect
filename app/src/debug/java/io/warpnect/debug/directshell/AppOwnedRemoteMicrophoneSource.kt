package io.warpnect.debug.directshell

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Only policy identity/window crosses authenticated control. PCM stays in the active Session app. */
internal class AppOwnedRemoteMicrophoneSource(
    private val context: Context,
    port: Int,
    private val generation: Long,
    secret: ByteArray,
) : RemoteMicrophonePcmDestination, AutoCloseable {
    private val client = DirectShellPcmRingProbeClient.connectLoopback(port, secret)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val submitted = AtomicLong()
    private val invalid = AtomicLong()

    @Volatile private var clock: AppOwnedMicrophoneClock? = null
    private var worker: Thread? = null
    private var state: DirectShellAppMicrophoneState? = null

    fun prepare(): String = client.call(DirectShellProbeProtocol.Command.MICROPHONE_APP_PREPARE).payload

    override fun start(): Boolean {
        if (closed.get()) return false
        if (running.get()) return true
        return try {
            val initial = readState()
            val owned = AppOwnedMicrophoneClock.open(context, initial.registration, generation)
            clock = owned
            state = initial
            owned.window(initial.sinceNs)
            owned.start()
            running.set(true)
            worker = Thread(::observe, "AppRemoteMicPolicyControl").also { it.start() }
            true
        } catch (failure: Throwable) {
            Log.i(TAG, "APP_MIC_FAILURE stage=start;type=${failure.javaClass.simpleName};detail=${failure.message}")
            close()
            false
        }
    }

    override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) {
        submitTimed(buffer, offset, frames, -1, System.nanoTime())
    }

    override fun submitTimed(buffer: ByteBuffer, offset: Int, frames: Int, firstFramePosition: Long, admittedNs: Long) {
        if (!running.get() || closed.get() || frames !in 1..960) return
        val owned = clock ?: return
        val pcm = ShortArray(frames)
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(offset)
            limit(offset + frames * 2)
        }.asShortBuffer().get(pcm)
        if (owned.offer(pcm, admittedNs, firstFramePosition)) {
            submitted.addAndGet(frames.toLong())
        } else {
            invalid.addAndGet(frames.toLong())
        }
    }

    private fun readState() = DirectShellAppMicrophoneState.parse(
        client.call(DirectShellProbeProtocol.Command.MICROPHONE_APP_STATE).payload,
        generation,
    )

    private fun observe() {
        var nextSample = 0L
        try {
            while (running.get()) {
                val fresh = readState()
                val previous = checkNotNull(state)
                fresh.requireContinuation(previous)
                val owned = clock ?: break
                if (fresh.closeEpoch != previous.closeEpoch) owned.window(0)
                if (fresh.sinceNs != previous.sinceNs || fresh.closeEpoch != previous.closeEpoch) {
                    owned.window(fresh.sinceNs)
                    Log.i(
                        TAG,
                        "APP_MIC_WINDOW generation=$generation;since_ns=${fresh.sinceNs};" +
                            "close_epoch=${fresh.closeEpoch}",
                    )
                }
                state = fresh
                val v = owned.snapshot()
                if (v[18] != 0L) error("app_clock_error_${v[18]}")
                if (System.nanoTime() >= nextSample) {
                    logMetrics(v, fresh)
                    nextSample = System.nanoTime() + 1_000_000_000L
                }
                Thread.sleep(20)
            }
        } catch (failure: Exception) {
            if (!closed.get()) {
                Log.i(
                    TAG,
                    "APP_MIC_FAILURE stage=control;type=${failure.javaClass.simpleName};detail=${failure.message}",
                )
            }
        } finally {
            running.set(false)
            try {
                clock?.let {
                    try {
                        logMetrics(it.snapshot(), state)
                    } finally {
                        it.close()
                    }
                }
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun logMetrics(v: LongArray, window: DirectShellAppMicrophoneState?) {
        Log.i(
            TAG,
            "APP_MIC_PROGRESS generation=$generation;submitted=${submitted.get()};invalid=${invalid.get()};" +
                "clock_mode=PUBLIC_NONBLOCKING_PUMP;" +
                "consumer_since_ns=${window?.sinceNs ?: 0};retained=${v[9]};hwm=${v[10]};max_age_ns=${v[11]};" +
                "active_silence=${v[12]};idle_silence=${v[13]};max_callback_gap_ns=${v[14]};callbacks=${v[15]};" +
                "callback_frames=${v[16]};xrun=${v[17]};error=${v[18]};scheduler=${v[19]};nice=${v[20]};" +
                "platform_written=${v[21]};platform_read=${v[22]};max_body_ns=${v[23]};max_return_age_ns=${v[24]};" +
                "estimated_sdk_ahead_frames=${v[33]};" +
                "estimated_empty_episodes=${v[34]};" +
                "late_callback_frames=${v[25]};post_copy_stale=${v[26]};" +
                "zero_writes=${v[27]};partial_writes=${v[28]};max_write_ns=${v[29]};" +
                "max_counter_probe_ns=${v[30]};max_active_loop_gap_ns=${v[31]};" +
                "accepted_despite_full_estimate=${v[32]};" +
                "totals=${DirectShellMicrophoneFeedTotals.Snapshot(v.copyOf(9)).encode()}",
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        // Unblock control before joining; no SDK/Binder operation occurs on the PCM producer.
        runCatching { client.close() }
        try {
            worker?.takeIf { it !== Thread.currentThread() }?.join(3_500)
        } finally {
            clock?.close()
        }
    }

    private companion object {
        const val TAG = "WarpnectRemoteMic"
    }
}
