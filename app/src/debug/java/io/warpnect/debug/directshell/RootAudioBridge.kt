package io.warpnect.debug.directshell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.warpnect.platform.audio.capture.RootAudioRequest
import io.warpnect.platform.audio.capture.RootAudioRequestCodec
import io.warpnect.platform.audio.capture.RootPcmDescriptorLease
import io.warpnect.platform.audio.capture.shared.LegacyAshmemPcmMemory
import io.warpnect.platform.audio.capture.shared.PcmRingProducerCore
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** UID0 owner for the same ring contract. No Context impersonation, AudioPolicy or PCM RPC. */
internal class RootAudioBridge(private val generation: Long, private val key: ByteArray) : AutoCloseable {
    private var request: RootAudioRequest? = null
    private var memory: LegacyAshmemPcmMemory? = null
    private var ring: ByteBuffer? = null
    private var notify: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var ack: ParcelFileDescriptor.AutoCloseInputStream? = null
    private var provider: RootVideoProviderClient? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private var core: PcmRingProducerCore? = null

    @Volatile private var boundary = "NOT_PREPARED"
    private var bufferBytes = 0
    private var packageName: String? = null
    private var opPackageName: String? = null

    @Synchronized
    fun dispatch(command: DirectShellProbeProtocol.Command, bytes: ByteArray): ByteArray {
        val parsed = RootAudioRequestCodec.decode(bytes)
        require(parsed.generation == generation)
        if (command != DirectShellProbeProtocol.Command.PCM_RING_AUDIO_PREPARE) {
            require(request == parsed) { "audio_generation_mismatch" }
        }
        try {
            when (command) {
                DirectShellProbeProtocol.Command.PCM_RING_AUDIO_PREPARE -> prepare(parsed)
                DirectShellProbeProtocol.Command.PCM_RING_AUDIO_START -> start()
                DirectShellProbeProtocol.Command.PCM_RING_AUDIO_STOP -> stop()
                DirectShellProbeProtocol.Command.PCM_RING_CLOSE -> close()
                DirectShellProbeProtocol.Command.GET_PCM_RING_DIAGNOSTICS -> Unit
                else -> error("unsupported_root_audio_operation")
            }
        } catch (error: Exception) {
            boundary = error.message?.take(120) ?: error.javaClass.simpleName
            close()
        }
        return status().toString().toByteArray()
    }

    @SuppressLint("WrongConstant")
    private fun prepare(parsed: RootAudioRequest) {
        if (request != null && boundary == "STOPPED") {
            close()
            request = null
        }
        check(request == null) { "AUDIO_ALREADY_PREPARED" }
        request = parsed
        provider = RootVideoProviderClient.open()
        val external = requireNotNull(provider)
        val stale = external.pcm(parsed.copy(audioGeneration = parsed.audioGeneration - 1), key)
        check(!stale.getBoolean("accepted")) { "STALE_AUDIO_ACCEPTED" }
        val wrong = key.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        try {
            check(!external.pcm(parsed, wrong).getBoolean("accepted")) { "WRONG_HMAC_ACCEPTED" }
        } finally {
            wrong.fill(0)
        }
        for (negative in RootVideoProviderClient.PcmNegative.entries) {
            check(!external.pcm(parsed, key, negative).getBoolean("accepted")) { "INVALID_PCM_METADATA_ACCEPTED" }
        }
        val reply = external.pcm(parsed, key)
        reply.classLoader = ParcelFileDescriptor::class.java.classLoader
        check(reply.getBoolean("accepted")) { "FD_HANDOFF_FAILED" }
        val fds = listOf("memory", "notify", "ack").mapNotNull { reply.getParcelable<ParcelFileDescriptor>(it) }
        if (fds.size != 3) {
            fds.forEach { runCatching { it.close() } }
            error("FD_SET_INVALID")
        }
        try {
            check(!external.pcm(parsed, key).getBoolean("accepted")) { "FD_REPLAY_ACCEPTED" }
            check(OsConstants.S_ISFIFO(Os.fstat(fds[1].fileDescriptor).st_mode)) { "NOTIFY_NOT_PIPE" }
            check(OsConstants.S_ISFIFO(Os.fstat(fds[2].fileDescriptor).st_mode)) { "ACK_NOT_PIPE" }
            check(
                descriptorAccess(fds[1].fileDescriptor) ==
                    OsConstants.O_WRONLY,
            )
            check(
                descriptorAccess(fds[2].fileDescriptor) ==
                    OsConstants.O_RDONLY,
            )
            memory = LegacyAshmemPcmMemory.receive(fds[0], RootPcmDescriptorLease.REGION_BYTES)
            ring = requireNotNull(memory).mapReadWrite()
            check(SharedPcmAudioRingLayout.validate(requireNotNull(ring))) { "RING_SETUP_FAILED" }
            notify = ParcelFileDescriptor.AutoCloseOutputStream(fds[1])
            ack = ParcelFileDescriptor.AutoCloseInputStream(fds[2])
        } catch (error: Exception) {
            fds.forEach { runCatching { it.close() } }
            throw error
        }
        val minimum = AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "AUDIO_FORMAT_UNAVAILABLE_$minimum" }
        bufferBytes = maxOf(minimum * 2, 7680)
        initializeFrameworkIdentity()
        boundary = "AUDIORECORD_CONSTRUCTION_FAILED"
        // API26 Builder forwards ActivityThread's op-package; bare app_process has none.
        record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(bufferBytes).build()
        check(record?.state == AudioRecord.STATE_INITIALIZED) { "AUDIORECORD_UNINITIALIZED" }
        check(record?.sampleRate == 48_000 && record?.channelCount == 2) { "AUDIO_FORMAT_MISMATCH" }
        core = PcmRingProducerCore(requireNotNull(ring), requireNotNull(notify), requireNotNull(ack), { target, size ->
            val read = requireNotNull(record).read(target, size, AudioRecord.READ_BLOCKING)
            if (read < 0 && !running.get()) 0 else read
        })
        boundary = "PREPARED"
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_pcm_security stale=REJECTED hmac=REJECTED replay=REJECTED " +
                "magic=REJECTED version=REJECTED bounds=REJECTED provider=REJECTED",
        )
    }

    private fun initializeFrameworkIdentity() {
        val framework = Class.forName("android.app.ActivityThread")
        if (framework.getMethod("currentActivityThread").invoke(null) == null) {
            if (Looper.myLooper() == null) Looper.prepare()
            framework.getMethod("systemMain").invoke(null)
        }
        val application = framework.getMethod("currentApplication").invoke(null) as? Context
            ?: error("AUDIO_FRAMEWORK_APPLICATION_UNAVAILABLE")
        packageName = application.packageName
        opPackageName = framework.getMethod("currentOpPackageName").invoke(null) as? String
        check(!opPackageName.isNullOrEmpty()) { "AUDIO_OP_PACKAGE_UNAVAILABLE" }
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_audio_identity strategy=FRAMEWORK_SYSTEM_MAIN " +
                "uid=${android.os.Process.myUid()} package=$packageName op_package=$opPackageName",
        )
    }

    private fun descriptorAccess(descriptor: FileDescriptor): Int {
        // Present in the qualified API26 framework, public SDK only since API30.
        val flags = Os::class.java.getMethod(
            "fcntlInt",
            FileDescriptor::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(null, descriptor, OsConstants.F_GETFL, 0) as Int
        return flags and OsConstants.O_ACCMODE
    }

    private fun start() {
        check(boundary == "PREPARED" && !running.get()) { "AUDIO_NOT_PREPARED" }
        record?.startRecording()
        check(record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "START_RECORDING_FAILED" }
        running.set(true)
        boundary = "RECORDING"
        worker = Thread({
            try {
                while (running.get()) requireNotNull(core).step()
            } catch (error: Exception) {
                if (running.get()) boundary = "READ_FAILED_${error.javaClass.simpleName}"
                running.set(false)
            }
        }, "WarpnectRootPcmProducer").apply { start() }
    }

    private fun stop() {
        running.set(false)
        runCatching { record?.stop() }
        worker?.let { if (it != Thread.currentThread()) it.join(1500) }
        check(worker?.isAlive != true) { "PRODUCER_STOP_TIMEOUT" }
        worker = null
        runCatching { core?.drainAcks() }
        if (boundary == "RECORDING" || boundary == "PREPARED") boundary = "STOPPED"
    }

    private fun status(): JSONObject = JSONObject().apply {
        put("generation", generation)
        put("audio_generation", request?.audioGeneration)
        put("boundary", boundary)
        put("record_state", record?.state ?: -1)
        put("recording_state", record?.recordingState ?: -1)
        put("session_id", record?.audioSessionId ?: -1)
        put("buffer_bytes", bufferBytes)
        put("package", packageName ?: JSONObject.NULL)
        put("op_package", opPackageName ?: JSONObject.NULL)
        put("captured", core?.counters?.captured?.get() ?: 0)
        put("written", core?.counters?.written?.get() ?: 0)
        put("notifications", core?.counters?.notifications?.get() ?: 0)
        put("acks", core?.counters?.acks?.get() ?: 0)
        put("drops", core?.counters?.dropped?.get() ?: 0)
        put("errors", core?.counters?.errors?.get() ?: 0)
        put("hwm", ring?.let(SharedPcmAudioRingLayout::highWater) ?: -1)
        put("overruns", ring?.let(SharedPcmAudioRingLayout::overruns) ?: -1)
        put("occupancy", ring?.let(SharedPcmAudioRingLayout::occupancy) ?: -1)
    }

    @Synchronized
    override fun close() {
        stop()
        record?.release()
        record = null
        runCatching { notify?.close() }
        runCatching { ack?.close() }
        notify = null
        ack = null
        memory?.close()
        memory = null
        ring = null
        provider?.close()
        provider = null
        Log.i("WarpnectDirectRoot", "direct_root_audio_cleanup record=false ring=false pipes=false provider=false")
    }
}
