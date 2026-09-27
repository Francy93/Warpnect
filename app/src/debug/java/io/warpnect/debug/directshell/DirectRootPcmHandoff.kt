package io.warpnect.debug.directshell

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.warpnect.platform.audio.capture.RootAudioRequest
import io.warpnect.platform.audio.capture.RootAudioRequestCodec
import io.warpnect.platform.audio.capture.RootPcmDescriptorLease
import io.warpnect.platform.audio.capture.shared.LegacyAshmemPcmMemory
import io.warpnect.platform.privileged.runtime.RootHelperIdentity

internal object DirectRootPcmHandoff {
    const val METHOD = "open_root_pcm_descriptor_set_v1"
    private var lease: RootPcmDescriptorLease? = null
    private var memory: LegacyAshmemPcmMemory? = null
    private var notify: ParcelFileDescriptor? = null
    private var ack: ParcelFileDescriptor? = null
    private val issuedCopies = mutableListOf<ParcelFileDescriptor>()
    private var stagedRequest: RootAudioRequest? = null

    @Synchronized
    fun stage(
        request: RootAudioRequest,
        region: LegacyAshmemPcmMemory,
        notifyWrite: ParcelFileDescriptor,
        ackRead: ParcelFileDescriptor,
    ) {
        check(stagedRequest == null) { "root_pcm_owner_conflict" }
        stagedRequest = request
        memory = region
        notify = notifyWrite
        ack = ackRead
    }

    @Synchronized
    fun arm(identity: RootHelperIdentity, key: ByteArray, request: RootAudioRequest) {
        check(identity.uid == 0 && identity.generation == request.generation && stagedRequest == request)
        check(lease == null) { "root_pcm_lease_already_armed" }
        lease = RootPcmDescriptorLease(request, identity.pid, key, SystemClock.elapsedRealtime() + 10_000)
    }

    fun verifyAppCallerRejected(request: RootAudioRequest, key: ByteArray) {
        val context = Class.forName(
            "android.app.ActivityThread",
        ).getMethod("currentApplication").invoke(null) as Context
        val proof = RootPcmDescriptorLease.proof(key, request)
        try {
            val extras = Bundle().apply {
                putString("provider", "DIRECT_ROOT")
                putByteArray("request", RootAudioRequestCodec.encode(request))
                putByteArray("proof", proof)
                putInt("region_bytes", RootPcmDescriptorLease.REGION_BYTES)
            }
            val rejected = context.contentResolver.call(
                Uri.parse("content://${DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_AUTHORITY}"),
                METHOD,
                null,
                extras,
            )
            check(rejected?.getBoolean("accepted") != true) { "APP_UID_PCM_ACCESS_ACCEPTED" }
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_pcm_wrong_caller result=REJECTED uid=${android.os.Process.myUid()}",
            )
        } finally {
            proof.fill(0)
        }
    }

    @Synchronized
    fun call(extras: Bundle?): Bundle {
        val result = Bundle().apply { putBoolean("accepted", false) }
        if (extras == null) return result
        val owner = lease ?: return result
        val allowed = extras.getInt("region_bytes") == RootPcmDescriptorLease.REGION_BYTES && owner.accept(
            Binder.getCallingUid(), Binder.getCallingPid(), extras.getString("provider"),
            extras.getByteArray("request") ?: byteArrayOf(),
            extras.getByteArray("proof"),
            SystemClock.elapsedRealtime(),
        )
        if (!allowed) {
            Log.i("WarpnectDirectRoot", "direct_root_pcm_handoff result=REJECTED")
            return result
        }
        val created = mutableListOf<ParcelFileDescriptor>()
        try {
            val shared = requireNotNull(memory).duplicateDescriptor().also { created += it }
            val notifyCopy = ParcelFileDescriptor.dup(requireNotNull(notify).fileDescriptor).also { created += it }
            val ackCopy = ParcelFileDescriptor.dup(requireNotNull(ack).fileDescriptor).also { created += it }
            result.putParcelable("memory", shared)
            result.putParcelable("notify", notifyCopy)
            result.putParcelable("ack", ackCopy)
            result.putBoolean("accepted", true)
            owner.issued()
            issuedCopies += created
            Log.i("WarpnectDirectRoot", "direct_root_pcm_handoff result=PASS uid=${Binder.getCallingUid()}")
            // Bundle/Parcelable owns these copies until the framework marshals its response.
            return result
        } catch (error: Exception) {
            created.forEach { runCatching { it.close() } }
            throw error
        }
    }

    @Synchronized
    fun release(request: RootAudioRequest? = null) {
        if (request != null && stagedRequest != request) return
        lease?.close()
        issuedCopies.forEach { runCatching { it.close() } }
        issuedCopies.clear()
        lease = null
        memory = null
        notify = null
        ack = null
        stagedRequest = null
    }

    @Synchronized
    fun active(): Boolean = lease != null
}
