package io.warpnect.debug.directshell;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Executes the wrong-caller negative test against the same DEBUG provider used by shell. */
final class DirectShellFdProbeLocalProviderClient {
    private DirectShellFdProbeLocalProviderClient() {}

    static String verifyNormalAppCallerRejected(Context context, long generationId, byte[] secret) {
        Bundle request = new Bundle();
        request.putInt(DirectShellFdProbeBinderProtocol.KEY_MAGIC,
                DirectShellFdProbeBinderProtocol.MAGIC);
        request.putInt(DirectShellFdProbeBinderProtocol.KEY_VERSION,
                DirectShellFdProbeBinderProtocol.VERSION);
        request.putLong(DirectShellFdProbeBinderProtocol.KEY_GENERATION_ID, generationId);
        request.putInt(DirectShellFdProbeBinderProtocol.KEY_REGION_BYTES,
                DirectShellFdProbeBinderProtocol.REGION_BYTES);
        request.putByteArray(DirectShellFdProbeBinderProtocol.KEY_PROOF,
                DirectShellFdProbeBinderProtocol.createProof(
                        secret,
                        generationId,
                        DirectShellFdProbeBinderProtocol.MAGIC,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES
                ));
        try {
            Bundle response = context.getContentResolver().call(
                    Uri.parse("content://" + DirectShellFdProbeBinderProtocol.PROVIDER_AUTHORITY),
                    DirectShellFdProbeBinderProtocol.PROVIDER_METHOD_OPEN_SHARED_MEMORY,
                    null,
                    request
            );
            if (response == null) return "NULL_RESPONSE";
            return DirectShellFdProbeBinderProtocol.Result.fromCode(response.getInt(
                    DirectShellFdProbeBinderProtocol.KEY_RESULT_CODE,
                    DirectShellFdProbeBinderProtocol.Result.INTERNAL_ERROR.code
            )).name();
        } catch (RuntimeException exception) {
            return "CALL_FAILED_" + exception.getClass().getSimpleName();
        }
    }
}
