package io.warpnect.debug.directshell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * DEBUG-only shell-gated endpoint for one active app-owned encoder Surface.
 *
 * The endpoint has no query, file, or arbitrary-Surface API. It only parcels the active probe
 * generation's Surface after the framework has authenticated the Binder caller as shell and the
 * generation HMAC is valid.
 */
class DirectShellVideoCaptureProbeContentProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Log.i(TAG, "VIDEO_SURFACE_PROVIDER_CREATED")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == DirectRootSurfaceHandoff.METHOD) {
            return DirectRootSurfaceHandoff.issue(Binder.getCallingUid(), Binder.getCallingPid(), extras)
        }
        if (method != DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_METHOD_OPEN_ENCODER_SURFACE) {
            return response(
                DirectShellVideoCaptureProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                null,
                "unsupported_method",
            )
        }
        if (extras == null) {
            return response(
                DirectShellVideoCaptureProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                null,
                "missing_extras",
            )
        }
        val callerUid = Binder.getCallingUid()
        val callerPid = Binder.getCallingPid()
        val callerPackage = try {
            callingPackage
        } catch (_: SecurityException) {
            Log.i(
                TAG,
                "VIDEO_SURFACE_PROVIDER_REJECT result=ATTRIBUTION_REJECTED " +
                    "caller_uid=$callerUid caller_pid=$callerPid",
            )
            return response(
                DirectShellVideoCaptureProbeBinderProtocol.Result.CALLER_UID_REJECTED,
                null,
                "attribution_rejected",
            )
        }
        if (callerUid != Process.SHELL_UID) {
            Log.i(
                TAG,
                "VIDEO_SURFACE_PROVIDER_REJECT result=CALLER_UID_REJECTED " +
                    "caller_uid=$callerUid caller_pid=$callerPid",
            )
            return response(
                DirectShellVideoCaptureProbeBinderProtocol.Result.CALLER_UID_REJECTED,
                null,
                "caller_uid_rejected",
            )
        }
        val reply = DirectShellVideoCaptureProbeSession.issueSurface(
            callerUid = callerUid,
            callerPid = callerPid,
            magic = extras.getInt(DirectShellVideoCaptureProbeBinderProtocol.KEY_MAGIC),
            version = extras.getInt(DirectShellVideoCaptureProbeBinderProtocol.KEY_VERSION),
            generationId = extras.getLong(DirectShellVideoCaptureProbeBinderProtocol.KEY_GENERATION_ID),
            width = extras.getInt(DirectShellVideoCaptureProbeBinderProtocol.KEY_WIDTH),
            height = extras.getInt(DirectShellVideoCaptureProbeBinderProtocol.KEY_HEIGHT),
            proof = extras.getByteArray(DirectShellVideoCaptureProbeBinderProtocol.KEY_PROOF),
        )
        Log.i(
            TAG,
            "VIDEO_SURFACE_PROVIDER_CALL result=${reply.result.name} caller_uid=$callerUid " +
                "caller_pid=$callerPid caller_package=${safe(callerPackage)}",
        )
        return response(reply.result, reply.surface, reply.detail)
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = throw UnsupportedOperationException("DirectShell video probe has no query API")

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("DirectShell video probe has no insert API")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("DirectShell video probe has no delete API")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("DirectShell video probe has no update API")

    private fun response(
        result: DirectShellVideoCaptureProbeBinderProtocol.Result,
        surface: android.view.Surface?,
        detail: String,
    ): Bundle = Bundle().apply {
        putInt(DirectShellVideoCaptureProbeBinderProtocol.KEY_RESULT_CODE, result.code)
        putString(DirectShellVideoCaptureProbeBinderProtocol.KEY_DETAIL, detail)
        surface?.let { putParcelable(DirectShellVideoCaptureProbeBinderProtocol.KEY_SURFACE, it) }
    }

    private fun safe(value: String?): String = value
        ?.replace('\n', '_')
        ?.replace('\r', '_')
        ?.replace('\u0000', '_')
        ?.take(120)
        ?: "none"

    private companion object {
        const val TAG = "WarpnectDirectShellVideo"
    }
}
