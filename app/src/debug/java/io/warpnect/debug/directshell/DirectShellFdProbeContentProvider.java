package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * DEBUG-only, single-purpose descriptor endpoint for the DirectShell feasibility probe.
 *
 * <p>The provider exposes no files or data API. Its sole operation returns the one armed
 * SharedMemory Parcelable after the real Binder caller identity, per-launch HMAC, and generation
 * checks have all succeeded.</p>
 */
@TargetApi(Build.VERSION_CODES.S)
public final class DirectShellFdProbeContentProvider extends ContentProvider {
    private static final String TAG = "WarpnectDirectShellFd";

    @Override
    public boolean onCreate() {
        Log.i(TAG, "FD_PROVIDER_CREATED");
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return response(DirectShellFdProbeBinderProtocol.Result.PROTOCOL_REJECTED, null,
                    "unsupported_platform_api31_required");
        }
        if (!DirectShellFdProbeBinderProtocol.PROVIDER_METHOD_OPEN_SHARED_MEMORY.equals(method)) {
            return response(DirectShellFdProbeBinderProtocol.Result.PROTOCOL_REJECTED, null,
                    "unsupported_method");
        }
        if (extras == null) {
            return response(DirectShellFdProbeBinderProtocol.Result.PROTOCOL_REJECTED, null,
                    "missing_extras");
        }
        final int callerUid = Binder.getCallingUid();
        final int callerPid = Binder.getCallingPid();
        final String callerPackage;
        try {
            // This also lets framework AppOps verify the supplied AttributionSource against UID.
            callerPackage = getCallingPackage();
        } catch (SecurityException exception) {
            Log.i(TAG, "PROVIDER_REJECT result=ATTRIBUTION_REJECTED caller_uid=" + callerUid +
                    " caller_pid=" + callerPid);
            return response(DirectShellFdProbeBinderProtocol.Result.CALLER_UID_REJECTED, null,
                    "attribution_rejected");
        }
        final DirectShellFdProbeSession.DescriptorReply reply;
        try {
            reply = DirectShellFdProbeSession.instance().issueDescriptor(
                    callerUid,
                    callerPid,
                    extras.getInt(DirectShellFdProbeBinderProtocol.KEY_MAGIC),
                    extras.getInt(DirectShellFdProbeBinderProtocol.KEY_VERSION),
                    extras.getLong(DirectShellFdProbeBinderProtocol.KEY_GENERATION_ID),
                    extras.getInt(DirectShellFdProbeBinderProtocol.KEY_REGION_BYTES),
                    extras.getByteArray(DirectShellFdProbeBinderProtocol.KEY_PROOF),
                    "provider"
            );
        } catch (RuntimeException exception) {
            Log.i(TAG, "PROVIDER_REJECT result=METADATA_REJECTED caller_uid=" + callerUid +
                    " caller_pid=" + callerPid);
            return response(DirectShellFdProbeBinderProtocol.Result.METADATA_REJECTED, null,
                    "invalid_metadata");
        }
        Log.i(TAG, "PROVIDER_CALL result=" + reply.result.name() + " caller_uid=" + callerUid +
                " caller_pid=" + callerPid + " caller_package=" + safe(callerPackage));
        return response(reply.result, reply.sharedMemory, reply.detail);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("DirectShell FD probe has no query API");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("DirectShell FD probe has no insert API");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("DirectShell FD probe has no delete API");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("DirectShell FD probe has no update API");
    }

    private static Bundle response(
            DirectShellFdProbeBinderProtocol.Result result,
            android.os.SharedMemory memory,
            String detail
    ) {
        Bundle response = new Bundle();
        response.putInt(DirectShellFdProbeBinderProtocol.KEY_RESULT_CODE, result.code);
        response.putString(DirectShellFdProbeBinderProtocol.KEY_DETAIL, detail);
        if (memory != null) {
            response.putParcelable(DirectShellFdProbeBinderProtocol.KEY_SHARED_MEMORY, memory);
        }
        return response;
    }

    private static String safe(String value) {
        if (value == null) return "none";
        String sanitized = value.replace('\n', '_').replace('\r', '_').replace('\u0000', '_');
        return sanitized.length() <= 120 ? sanitized : sanitized.substring(0, 120) + "...";
    }
}
