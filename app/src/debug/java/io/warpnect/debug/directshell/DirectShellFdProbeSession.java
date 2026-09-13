package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Process;
import android.os.SharedMemory;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Normal-app ownership for exactly one DEBUG SharedMemory probe generation. */
@TargetApi(Build.VERSION_CODES.S)
final class DirectShellFdProbeSession {
    private static final String TAG = "WarpnectDirectShellFd";
    private static final DirectShellFdProbeSession INSTANCE = new DirectShellFdProbeSession();

    private ActiveGeneration active;

    private DirectShellFdProbeSession() {}

    static DirectShellFdProbeSession instance() {
        return INSTANCE;
    }

    synchronized Result arm(long generationId, byte[] secret) {
        if (generationId <= 0L) return Result.failure("INVALID_GENERATION");
        byte[] safeSecret;
        try {
            safeSecret = DirectShellProbeProtocol.requireSecret(secret);
        } catch (IllegalArgumentException exception) {
            return Result.failure("INVALID_SECRET");
        }
        releaseLocked("replaced");
        SharedMemory memory = null;
        ByteBuffer mapped = null;
        try {
            memory = SharedMemory.create(
                    "warpnect_directshell_fd_" + generationId,
                    DirectShellFdProbeBinderProtocol.REGION_BYTES
            );
            mapped = memory.mapReadWrite();
            DirectShellFdSharedMemoryLayout.initialize(mapped, generationId);
            active = new ActiveGeneration(generationId, safeSecret, memory, mapped, fdCount());
            String payload = payloadLocked("ARMED");
            log("APP_ARM generation=" + generationId + " " + payload);
            return Result.success(payload);
        } catch (Exception exception) {
            if (mapped != null) {
                runCatchingUnmap(mapped);
            }
            if (memory != null) {
                runCatchingClose(memory);
            }
            Arrays.fill(safeSecret, (byte) 0);
            return Result.failure("SHARED_MEMORY_CREATE_FAILED_" + exception.getClass().getSimpleName());
        }
    }

    synchronized Result closeOriginal(long generationId) {
        ActiveGeneration current = currentGeneration(generationId);
        if (current == null) return Result.failure("GENERATION_NOT_ACTIVE");
        if (!current.descriptorIssued) return Result.failure("DESCRIPTOR_NOT_ISSUED");
        if (!current.originalClosed) {
            runCatchingClose(current.sharedMemory);
            current.originalClosed = true;
        }
        String payload = payloadLocked("ORIGINAL_DESCRIPTOR_CLOSED");
        log("APP_CLOSE_ORIGINAL generation=" + generationId + " " + payload);
        return Result.success(payload);
    }

    synchronized Result writeAppC(long generationId) {
        ActiveGeneration current = currentGeneration(generationId);
        if (current == null) return Result.failure("GENERATION_NOT_ACTIVE");
        DirectShellFdSharedMemoryLayout.write(
                current.mapping,
                generationId,
                3L,
                DirectShellFdSharedMemoryLayout.Pattern.APP_C
        );
        String payload = payloadLocked("APP_C_WRITTEN");
        log("APP_WRITE_C generation=" + generationId + " " + payload);
        return Result.success(payload);
    }

    synchronized Result verifyShellB(long generationId) {
        ActiveGeneration current = currentGeneration(generationId);
        if (current == null) return Result.failure("GENERATION_NOT_ACTIVE");
        DirectShellFdSharedMemoryLayout.Verification verification = DirectShellFdSharedMemoryLayout.verify(
                current.mapping,
                generationId,
                2L,
                DirectShellFdSharedMemoryLayout.Pattern.SHELL_B
        );
        String payload = payloadLocked("VERIFY_SHELL_B");
        log("APP_VERIFY_B generation=" + generationId + " " + payload);
        return verification == DirectShellFdSharedMemoryLayout.Verification.OK
                ? Result.success(payload)
                : Result.failure(verification.name() + " " + payload);
    }

    synchronized Result finish(long generationId) {
        ActiveGeneration current = currentGeneration(generationId);
        if (current == null) return Result.failure("GENERATION_NOT_ACTIVE");
        int fdBeforeRelease = fdCount();
        releaseLocked("finished");
        String payload = "generation=" + generationId +
                " fd_before_release=" + fdBeforeRelease +
                " fd_after_release=" + fdCount();
        log("APP_FINISH " + payload);
        return Result.success(payload);
    }

    synchronized Result diagnostics(long generationId) {
        ActiveGeneration current = currentGeneration(generationId);
        return current == null ? Result.failure("GENERATION_NOT_ACTIVE") : Result.success(payloadLocked("DIAGNOSTICS"));
    }

    synchronized DescriptorReply issueDescriptor(
            int callerUid,
            int callerPid,
            int magic,
            int version,
            long generationId,
            int regionBytes,
            byte[] proof,
            String transport
    ) {
        DirectShellFdProbeBinderProtocol.Result result;
        if (callerUid != Process.SHELL_UID) {
            result = DirectShellFdProbeBinderProtocol.Result.CALLER_UID_REJECTED;
        } else if (active == null) {
            result = DirectShellFdProbeBinderProtocol.Result.NOT_ARMED;
        } else {
            result = DirectShellFdProbeBinderProtocol.validate(
                    active.generationId,
                    active.descriptorIssued,
                    magic,
                    version,
                    generationId,
                    regionBytes,
                    proof,
                    active.secret
            );
        }
        if (result != DirectShellFdProbeBinderProtocol.Result.OK) {
            log(
                    "BRIDGE_REJECT transport=" + transport +
                            " result=" + result.name() +
                            " caller_uid=" + callerUid +
                            " caller_pid=" + callerPid +
                            " generation=" + generationId
            );
            return DescriptorReply.failure(result, "descriptor_rejected");
        }
        ActiveGeneration current = active;
        current.descriptorIssued = true;
        log(
                "BRIDGE_DESCRIPTOR_ISSUED transport=" + transport +
                        " generation=" + generationId +
                        " caller_uid=" + callerUid +
                        " caller_pid=" + callerPid +
                        " " + payloadLocked("DESCRIPTOR_ISSUED")
        );
        return DescriptorReply.success(current.sharedMemory, "descriptor_issued");
    }

    private ActiveGeneration currentGeneration(long generationId) {
        return active != null && active.generationId == generationId ? active : null;
    }

    private String payloadLocked(String event) {
        ActiveGeneration current = active;
        if (current == null) return "event=" + event + " active_generation=none";
        return "event=" + event +
                " generation=" + current.generationId +
                " descriptor_issued=" + current.descriptorIssued +
                " original_closed=" + current.originalClosed +
                " app_fd_before_arm=" + current.fdBeforeArm +
                " app_fd_now=" + fdCount() +
                " " + DirectShellFdSharedMemoryLayout.summary(current.mapping);
    }

    private void releaseLocked(String reason) {
        ActiveGeneration current = active;
        active = null;
        if (current == null) return;
        runCatchingUnmap(current.mapping);
        if (!current.originalClosed) {
            runCatchingClose(current.sharedMemory);
        }
        Arrays.fill(current.secret, (byte) 0);
        log("APP_RELEASE reason=" + reason + " generation=" + current.generationId);
    }

    private static int fdCount() {
        String[] entries = new File("/proc/self/fd").list();
        return entries == null ? -1 : entries.length;
    }

    private static void runCatchingUnmap(ByteBuffer mapping) {
        try {
            SharedMemory.unmap(mapping);
        } catch (RuntimeException ignored) {
            // Cleanup diagnostics must not obscure the first probe boundary.
        }
    }

    private static void runCatchingClose(SharedMemory memory) {
        try {
            memory.close();
        } catch (RuntimeException ignored) {
            // SharedMemory.close is best-effort during DEBUG probe teardown.
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
    }

    static final class Result {
        final boolean success;
        final String payload;

        private Result(boolean success, String payload) {
            this.success = success;
            this.payload = payload;
        }

        static Result success(String payload) {
            return new Result(true, payload);
        }

        static Result failure(String payload) {
            return new Result(false, payload);
        }
    }

    static final class DescriptorReply {
        final DirectShellFdProbeBinderProtocol.Result result;
        final SharedMemory sharedMemory;
        final String detail;

        private DescriptorReply(
                DirectShellFdProbeBinderProtocol.Result result,
                SharedMemory sharedMemory,
                String detail
        ) {
            this.result = result;
            this.sharedMemory = sharedMemory;
            this.detail = detail;
        }

        static DescriptorReply success(SharedMemory sharedMemory, String detail) {
            return new DescriptorReply(DirectShellFdProbeBinderProtocol.Result.OK, sharedMemory, detail);
        }

        static DescriptorReply failure(DirectShellFdProbeBinderProtocol.Result result, String detail) {
            return new DescriptorReply(result, null, detail);
        }
    }

    private static final class ActiveGeneration {
        final long generationId;
        final byte[] secret;
        final SharedMemory sharedMemory;
        final ByteBuffer mapping;
        final int fdBeforeArm;
        boolean descriptorIssued;
        boolean originalClosed;

        ActiveGeneration(
                long generationId,
                byte[] secret,
                SharedMemory sharedMemory,
                ByteBuffer mapping,
                int fdBeforeArm
        ) {
            this.generationId = generationId;
            this.secret = secret;
            this.sharedMemory = sharedMemory;
            this.mapping = mapping;
            this.fdBeforeArm = fdBeforeArm;
        }
    }
}
