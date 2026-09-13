package io.warpnect.debug.directshell

import android.annotation.TargetApi
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * DEBUG-only, one-operation endpoint for a production PCM-ring descriptor set.
 *
 * It has no data or file API. The provider only duplicates the active generation's memory and
 * complementary pipe ends after the real Binder caller is shell and the per-launch HMAC proves
 * possession of the current generation secret.
 */
@TargetApi(Build.VERSION_CODES.S)
class DirectShellPcmRingProbeContentProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Log.i(TAG, "PCM_RING_PROVIDER_CREATED")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return response(
                DirectShellPcmRingProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                null,
                "unsupported_platform_api31_required",
            )
        }
        if (method != DirectShellPcmRingProbeBinderProtocol.PROVIDER_METHOD_OPEN_PCM_RING) {
            return response(
                DirectShellPcmRingProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                null,
                "unsupported_method",
            )
        }
        if (extras == null) {
            return response(
                DirectShellPcmRingProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                null,
                "missing_extras",
            )
        }
        val callerUid = Binder.getCallingUid()
        val callerPid = Binder.getCallingPid()
        val callerPackage = try {
            // Framework/AppOps verifies the supplied AttributionSource before this is returned.
            callingPackage
        } catch (_: SecurityException) {
            Log.i(
                TAG,
                "PCM_RING_PROVIDER_REJECT result=ATTRIBUTION_REJECTED " +
                    "caller_uid=$callerUid caller_pid=$callerPid",
            )
            return response(
                DirectShellPcmRingProbeBinderProtocol.Result.CALLER_UID_REJECTED,
                null,
                "attribution_rejected",
            )
        }
        val reply = DirectShellPcmRingProbeSession.issueDescriptorSet(
            callerUid = callerUid,
            callerPid = callerPid,
            magic = extras.getInt(DirectShellPcmRingProbeBinderProtocol.KEY_MAGIC),
            version = extras.getInt(DirectShellPcmRingProbeBinderProtocol.KEY_VERSION),
            generationId = extras.getLong(DirectShellPcmRingProbeBinderProtocol.KEY_GENERATION_ID),
            regionBytes = extras.getInt(DirectShellPcmRingProbeBinderProtocol.KEY_REGION_BYTES),
            slotCount = extras.getInt(DirectShellPcmRingProbeBinderProtocol.KEY_SLOT_COUNT),
            payloadCapacityBytes = extras.getInt(
                DirectShellPcmRingProbeBinderProtocol.KEY_PAYLOAD_CAPACITY_BYTES,
            ),
            proof = extras.getByteArray(DirectShellPcmRingProbeBinderProtocol.KEY_PROOF),
        )
        Log.i(
            TAG,
            "PCM_RING_PROVIDER_CALL result=${reply.result.name} caller_uid=$callerUid " +
                "caller_pid=$callerPid caller_package=${safe(callerPackage)}",
        )
        return response(reply.result, reply, reply.detail)
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = throw UnsupportedOperationException("DirectShell PCM ring probe has no query API")

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("DirectShell PCM ring probe has no insert API")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("DirectShell PCM ring probe has no delete API")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("DirectShell PCM ring probe has no update API")
    }

    private fun response(
        result: DirectShellPcmRingProbeBinderProtocol.Result,
        reply: DirectShellPcmRingProbeSession.DescriptorReply?,
        detail: String,
    ): Bundle = Bundle().apply {
        putInt(DirectShellPcmRingProbeBinderProtocol.KEY_RESULT_CODE, result.code)
        putString(DirectShellPcmRingProbeBinderProtocol.KEY_DETAIL, detail)
        reply?.sharedMemory?.let {
            putParcelable(DirectShellPcmRingProbeBinderProtocol.KEY_SHARED_MEMORY, it)
        }
        reply?.notifyWriteFd?.let {
            putParcelable(DirectShellPcmRingProbeBinderProtocol.KEY_NOTIFY_WRITE_FD, it)
        }
        reply?.ackReadFd?.let {
            putParcelable(DirectShellPcmRingProbeBinderProtocol.KEY_ACK_READ_FD, it)
        }
    }

    private fun safe(value: String?): String = value
        ?.replace('\n', '_')
        ?.replace('\r', '_')
        ?.replace('\u0000', '_')
        ?.take(120)
        ?: "none"

    private companion object {
        const val TAG = "WarpnectDirectShellRing"
    }
}
