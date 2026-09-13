package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Process;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEBUG-only app_process probe for a framework-provider-transferred SharedMemory descriptor.
 *
 * <p>It intentionally does not capture or stream PCM. Loopback TCP carries only authenticated
 * command orchestration; the app-to-shell data proof uses the FD embedded in SharedMemory's
 * Parcelable representation returned through framework Binder IPC.</p>
 */
public final class WarpnectPrivilegedFdSharedMemoryProbeServer {
    private static final String TAG = "WarpnectDirectShellFd";
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000L;
    private static final long MIN_IDLE_TIMEOUT_MILLIS = 5_000L;
    private static final long MAX_IDLE_TIMEOUT_MILLIS = 90_000L;
    private static final int CONTROL_ACCEPT_TIMEOUT_MILLIS = 250;

    private WarpnectPrivilegedFdSharedMemoryProbeServer() {}

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            ServerArguments arguments = ServerArguments.parse(args);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                log("START_FAILURE result=UNSUPPORTED_PLATFORM_API31_REQUIRED api=" +
                        Build.VERSION.SDK_INT);
                exitCode = 4;
            } else {
                new ProbeServer(arguments).run();
            }
        } catch (IllegalArgumentException exception) {
            log("START_FAILURE result=INVALID_ARGUMENT message=" + safe(exception.getMessage()));
            exitCode = 2;
        } catch (IOException exception) {
            log("START_FAILURE result=CONTROL_START_FAILED type=" +
                    exception.getClass().getSimpleName() + " message=" + safe(exception.getMessage()));
            exitCode = 3;
        } catch (Throwable throwable) {
            log("START_FAILURE result=UNEXPECTED type=" + throwable.getClass().getSimpleName() +
                    " message=" + safe(throwable.getMessage()));
            exitCode = 3;
        }
        log("PROCESS_EXIT code=" + exitCode);
        System.out.flush();
        System.exit(exitCode);
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static final class ProbeServer {
        private final ServerArguments arguments;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean endpointReleased = new AtomicBoolean(false);
        private final AtomicLong lastAuthenticatedActivityMillis =
                new AtomicLong(SystemClock.elapsedRealtime());
        private final DirectShellFdProbeFrameworkBridge framework =
                new DirectShellFdProbeFrameworkBridge();
        private volatile FileDescriptor listenerDescriptor;
        private Thread idleWatchdog;
        private DirectShellFdProbeExternalProviderClient externalProvider;
        private SharedMemory remoteMemory;
        private ByteBuffer remoteMapping;
        private int fdBeforeBind = -1;
        private int fdAfterHandoff = -1;
        private int fdAfterRemoteClose = -1;

        ProbeServer(ServerArguments arguments) {
            this.arguments = arguments;
        }

        void run() throws IOException {
            if (Process.myUid() != Process.SHELL_UID) {
                throw new IllegalStateException("IDENTITY_UNAVAILABLE_NOT_SHELL_UID");
            }
            DirectShellFdProbeFrameworkBridge.StartResult frameworkResult = framework.start();
            try {
                listenerDescriptor = DirectShellProbeIpv4Socket.bindListener(arguments.port);
            } catch (IOException exception) {
                log("START_FAILURE result=ENDPOINT_BIND_FAILED transport=LOOPBACK_TCP endpoint=" +
                        endpoint() + " type=" + exception.getClass().getSimpleName());
                framework.close();
                throw exception;
            }
            log("START result=READY pid=" + Process.myPid() +
                    " uid=" + Process.myUid() +
                    " gid=" + currentGid() +
                    " uid_kind=SHELL api=" + Build.VERSION.SDK_INT +
                    " selinux_context=" + readSelinuxContext() +
                    " endpoint=" + endpoint() +
                    " transport=LOOPBACK_TCP framework_context=" + frameworkResult.classification +
                    " fd_count=" + fdCount());
            log("FRAMEWORK_CONTEXT " + frameworkResult.payload().replace('\n', ' '));
            startIdleWatchdog();
            try {
                while (running.get()) {
                    try {
                        DirectShellProbeIpv4Socket client = DirectShellProbeIpv4Socket.accept(
                                listenerDescriptor,
                                CONTROL_ACCEPT_TIMEOUT_MILLIS
                        );
                        if (client == null) continue;
                        handleControl(client);
                    } catch (IOException exception) {
                        if (running.get()) {
                            log("CONTROL_ACCEPT_FAILURE type=" + exception.getClass().getSimpleName());
                        }
                    }
                }
            } finally {
                closeRemoteMemory();
                framework.close();
                releaseEndpoint();
                joinWatchdog();
            }
        }

        private void handleControl(DirectShellProbeIpv4Socket client) {
            DirectShellProbeProtocol.RequestOrder order = new DirectShellProbeProtocol.RequestOrder();
            try (DirectShellProbeIpv4Socket ignored = client) {
                DataInputStream input = client.input();
                DataOutputStream output = client.output();
                while (running.get()) {
                    DirectShellProbeProtocol.Request request;
                    try {
                        request = DirectShellProbeProtocol.readRequest(input, arguments.secret);
                    } catch (DirectShellProbeProtocol.ProtocolException exception) {
                        log("AUTH result=" + exception.kind.name() + " endpoint=" + endpoint());
                        return;
                    }
                    if (!order.accept(request.requestId)) {
                        writeResponse(output, request.requestId, DirectShellProbeProtocol.Status.OUT_OF_ORDER,
                                "request_id_not_strictly_increasing");
                        log("REQUEST id=" + request.requestId + " command=" + request.command.name() +
                                " result=OUT_OF_ORDER");
                        continue;
                    }
                    lastAuthenticatedActivityMillis.set(SystemClock.elapsedRealtime());
                    log("AUTH result=SUCCESS request_id=" + request.requestId);
                    if (dispatch(request, output)) {
                        requestShutdown("authenticated_shutdown");
                        return;
                    }
                }
            } catch (IOException exception) {
                if (running.get()) {
                    String result = exception instanceof EOFException ? "NORMAL_EOF" : "IO_FAILURE";
                    log("CLIENT_DISCONNECTED result=" + result + " type=" +
                            exception.getClass().getSimpleName());
                }
            }
        }

        private boolean dispatch(DirectShellProbeProtocol.Request request, DataOutputStream output)
                throws IOException {
            Operation operation;
            switch (request.command) {
                case PING:
                    operation = Operation.ok("PONG");
                    break;
                case GET_RUNTIME_INFO:
                    operation = Operation.ok(runtimeInfoPayload());
                    break;
                case FD_BIND_SHARED_MEMORY:
                    operation = bindSharedMemory();
                    break;
                case FD_VERIFY_APP_A:
                    operation = verify(1L, DirectShellFdSharedMemoryLayout.Pattern.APP_A, "APP_A");
                    break;
                case FD_WRITE_SHELL_B:
                    operation = writeShellB();
                    break;
                case FD_VERIFY_APP_C:
                    operation = verify(3L, DirectShellFdSharedMemoryLayout.Pattern.APP_C, "APP_C");
                    break;
                case FD_CLOSE_SHARED_MEMORY:
                    operation = closeRemoteMemory();
                    break;
                case GET_FD_DIAGNOSTICS:
                    operation = Operation.ok(diagnosticsPayload());
                    break;
                case FD_REQUEST_STALE_GENERATION:
                    operation = expectRejected(
                            "STALE_GENERATION",
                            requestDescriptor(arguments.generationId + 1L,
                                    DirectShellFdProbeBinderProtocol.MAGIC,
                                    DirectShellFdProbeBinderProtocol.VERSION,
                                    DirectShellFdProbeBinderProtocol.REGION_BYTES),
                            DirectShellFdProbeBinderProtocol.Result.GENERATION_REJECTED
                    );
                    break;
                case FD_REQUEST_INVALID_MAGIC:
                    operation = expectRejected(
                            "INVALID_MAGIC",
                            requestDescriptor(arguments.generationId, 0,
                                    DirectShellFdProbeBinderProtocol.VERSION,
                                    DirectShellFdProbeBinderProtocol.REGION_BYTES),
                            DirectShellFdProbeBinderProtocol.Result.PROTOCOL_REJECTED
                    );
                    break;
                case FD_REQUEST_OVERSIZED_METADATA:
                    operation = expectRejected(
                            "OVERSIZED_METADATA",
                            requestDescriptor(arguments.generationId,
                                    DirectShellFdProbeBinderProtocol.MAGIC,
                                    DirectShellFdProbeBinderProtocol.VERSION,
                                    Integer.MAX_VALUE),
                            DirectShellFdProbeBinderProtocol.Result.METADATA_REJECTED
                    );
                    break;
                case FD_REQUEST_REPLAY:
                    operation = expectRejected(
                            "REPLAY",
                            requestDescriptor(arguments.generationId,
                                    DirectShellFdProbeBinderProtocol.MAGIC,
                                    DirectShellFdProbeBinderProtocol.VERSION,
                                    DirectShellFdProbeBinderProtocol.REGION_BYTES),
                            DirectShellFdProbeBinderProtocol.Result.GENERATION_REPLAY_REJECTED
                    );
                    break;
                case FD_REQUEST_INVALID_PROOF:
                    operation = expectRejected(
                            "INVALID_PROOF",
                            requestDescriptorWithProof(
                                    arguments.generationId,
                                    DirectShellFdProbeBinderProtocol.MAGIC,
                                    DirectShellFdProbeBinderProtocol.VERSION,
                                    DirectShellFdProbeBinderProtocol.REGION_BYTES,
                                    new byte[DirectShellFdProbeBinderProtocol.PROOF_BYTES]
                            ),
                            DirectShellFdProbeBinderProtocol.Result.AUTH_REJECTED
                    );
                    break;
                case SHUTDOWN:
                    writeResponse(output, request.requestId, DirectShellProbeProtocol.Status.OK,
                            "SHUTDOWN_ACCEPTED");
                    log("REQUEST id=" + request.requestId + " command=SHUTDOWN result=OK");
                    return true;
                default:
                    operation = Operation.unsupported("unsupported_command");
                    break;
            }
            writeResponse(output, request.requestId, operation.status, operation.payload);
            log("REQUEST id=" + request.requestId + " command=" + request.command.name() +
                    " result=" + operation.classification + " detail=" + safe(operation.payload));
            return false;
        }

        private Operation bindSharedMemory() {
            if (remoteMemory != null || remoteMapping != null) {
                return Operation.badState("REMOTE_DESCRIPTOR_ALREADY_MAPPED");
            }
            fdBeforeBind = fdCount();
            if (framework.shellIdentityContext() == null) {
                return Operation.failed("EXTERNAL_PROVIDER_CONTEXT_UNAVAILABLE");
            }
            DirectShellFdProbeExternalProviderClient.OpenResult opened =
                    DirectShellFdProbeExternalProviderClient.open(framework.shellIdentityContext());
            if (!opened.isSuccess()) {
                return Operation.failed(opened.detail);
            }
            externalProvider = opened.client;
            DescriptorResponse response = requestDescriptor(
                    arguments.generationId,
                    DirectShellFdProbeBinderProtocol.MAGIC,
                    DirectShellFdProbeBinderProtocol.VERSION,
                    DirectShellFdProbeBinderProtocol.REGION_BYTES
            );
            if (response.result != DirectShellFdProbeBinderProtocol.Result.OK || response.sharedMemory == null) {
                closeExternalProvider();
                return Operation.failed("DESCRIPTOR_HANDOFF_" + response.result.name() + " " + response.detail);
            }
            try {
                remoteMemory = response.sharedMemory;
                remoteMapping = remoteMemory.mapReadWrite();
                if (remoteMemory.getSize() != DirectShellFdProbeBinderProtocol.REGION_BYTES) {
                    closeRemoteMemory();
                    return Operation.failed("SHARED_MEMORY_SIZE_MISMATCH");
                }
                DirectShellFdSharedMemoryLayout.Verification verification = DirectShellFdSharedMemoryLayout.verify(
                        remoteMapping,
                        arguments.generationId,
                        1L,
                        DirectShellFdSharedMemoryLayout.Pattern.APP_A
                );
                if (verification != DirectShellFdSharedMemoryLayout.Verification.OK) {
                    closeRemoteMemory();
                    return Operation.failed("APP_A_VERIFY_" + verification.name());
                }
                fdAfterHandoff = fdCount();
                return Operation.ok("FD_HANDOFF_OK transport=CONTENT_PROVIDER_EXTERNAL reply_has_fd=" +
                        response.replyHasFd +
                        " fd_before_bind=" + fdBeforeBind +
                        " fd_after_handoff=" + fdAfterHandoff +
                        " " + DirectShellFdSharedMemoryLayout.summary(remoteMapping));
            } catch (Exception exception) {
                closeRemoteMemory();
                return Operation.failed("SHARED_MEMORY_MAP_FAILED_" + exception.getClass().getSimpleName());
            }
        }

        private Operation writeShellB() {
            if (remoteMapping == null) return Operation.badState("REMOTE_MAPPING_NOT_ACTIVE");
            DirectShellFdSharedMemoryLayout.Verification before = DirectShellFdSharedMemoryLayout.verify(
                    remoteMapping,
                    arguments.generationId,
                    1L,
                    DirectShellFdSharedMemoryLayout.Pattern.APP_A
            );
            if (before != DirectShellFdSharedMemoryLayout.Verification.OK) {
                return Operation.failed("PRE_WRITE_APP_A_" + before.name());
            }
            DirectShellFdSharedMemoryLayout.write(
                    remoteMapping,
                    arguments.generationId,
                    2L,
                    DirectShellFdSharedMemoryLayout.Pattern.SHELL_B
            );
            return Operation.ok("SHELL_B_WRITTEN " + DirectShellFdSharedMemoryLayout.summary(remoteMapping));
        }

        private Operation verify(long sequence, DirectShellFdSharedMemoryLayout.Pattern pattern, String label) {
            if (remoteMapping == null) return Operation.badState("REMOTE_MAPPING_NOT_ACTIVE");
            DirectShellFdSharedMemoryLayout.Verification verification = DirectShellFdSharedMemoryLayout.verify(
                    remoteMapping,
                    arguments.generationId,
                    sequence,
                    pattern
            );
            return verification == DirectShellFdSharedMemoryLayout.Verification.OK
                    ? Operation.ok(label + "_VERIFIED " + DirectShellFdSharedMemoryLayout.summary(remoteMapping))
                    : Operation.failed(label + "_VERIFY_" + verification.name() + " " +
                            DirectShellFdSharedMemoryLayout.summary(remoteMapping));
        }

        private Operation expectRejected(
                String name,
                DescriptorResponse response,
                DirectShellFdProbeBinderProtocol.Result expected
        ) {
            return response.result == expected
                    ? Operation.ok(name + "_REJECTED result=" + response.result.name())
                    : Operation.failed(name + "_UNEXPECTED result=" + response.result.name() +
                            " detail=" + response.detail);
        }

        private DescriptorResponse requestDescriptor(
                long generationId,
                int magic,
                int version,
                int regionBytes
        ) {
            DirectShellFdProbeExternalProviderClient provider = externalProvider;
            if (provider == null) {
                return DescriptorResponse.failure(
                        DirectShellFdProbeBinderProtocol.Result.NOT_ARMED,
                        "EXTERNAL_PROVIDER_NOT_CONNECTED"
                );
            }
            DirectShellFdProbeExternalProviderClient.DescriptorResult response =
                    provider.requestDescriptor(arguments.secret, generationId, magic, version, regionBytes);
            return response.result == DirectShellFdProbeBinderProtocol.Result.OK &&
                    response.sharedMemory != null
                    ? DescriptorResponse.success(response.sharedMemory, true)
                    : DescriptorResponse.failure(response.result, response.detail);
        }

        private DescriptorResponse requestDescriptorWithProof(
                long generationId,
                int magic,
                int version,
                int regionBytes,
                byte[] proof
        ) {
            DirectShellFdProbeExternalProviderClient provider = externalProvider;
            if (provider == null) {
                return DescriptorResponse.failure(
                        DirectShellFdProbeBinderProtocol.Result.NOT_ARMED,
                        "EXTERNAL_PROVIDER_NOT_CONNECTED"
                );
            }
            DirectShellFdProbeExternalProviderClient.DescriptorResult response =
                    provider.requestDescriptorWithProof(generationId, magic, version, regionBytes, proof);
            return response.result == DirectShellFdProbeBinderProtocol.Result.OK &&
                    response.sharedMemory != null
                    ? DescriptorResponse.success(response.sharedMemory, true)
                    : DescriptorResponse.failure(response.result, response.detail);
        }

        private Operation closeRemoteMemory() {
            if (remoteMapping == null && remoteMemory == null && externalProvider == null) {
                return Operation.ok("REMOTE_ALREADY_CLOSED fd_now=" + fdCount());
            }
            if (remoteMapping != null) {
                try {
                    SharedMemory.unmap(remoteMapping);
                } catch (RuntimeException ignored) {
                    // Keep the resource state transition deterministic even if unmap reports late.
                }
                remoteMapping = null;
            }
            if (remoteMemory != null) {
                try {
                    remoteMemory.close();
                } catch (RuntimeException ignored) {
                    // The descriptor was already owned by this process and is being released.
                }
                remoteMemory = null;
            }
            fdAfterRemoteClose = fdCount();
            String providerRelease = closeExternalProvider();
            return Operation.ok("REMOTE_CLOSED fd_after_remote_close=" + fdAfterRemoteClose +
                    " provider_release=" + providerRelease);
        }

        private String closeExternalProvider() {
            DirectShellFdProbeExternalProviderClient provider = externalProvider;
            externalProvider = null;
            return provider == null ? "EXTERNAL_PROVIDER_ALREADY_RELEASED" : provider.release();
        }

        private String diagnosticsPayload() {
            return "generation=" + arguments.generationId +
                    "\nremote_mapping_active=" + (remoteMapping != null) +
                    "\nfd_before_bind=" + fdBeforeBind +
                    "\nfd_after_handoff=" + fdAfterHandoff +
                    "\nfd_after_remote_close=" + fdAfterRemoteClose +
                    "\nfd_now=" + fdCount() +
                    (remoteMapping == null ? "" : "\n" + DirectShellFdSharedMemoryLayout.summary(remoteMapping));
        }

        private String runtimeInfoPayload() {
            DirectShellFdProbeFrameworkBridge.StartResult result = framework.start();
            return "pid=" + Process.myPid() +
                    "\nuid=" + Process.myUid() +
                    "\ngid=" + currentGid() +
                    "\nuid_kind=" + (Process.myUid() == Process.SHELL_UID ? "SHELL" : "OTHER") +
                    "\napi=" + Build.VERSION.SDK_INT +
                    "\nselinux_context=" + readSelinuxContext() +
                    "\njava_class_path=" + safe(System.getProperty("java.class.path", "")) +
                    "\nclass_loader=" + classLoaderName() +
                    "\n" + result.payload() +
                    "\n" + diagnosticsPayload();
        }

        private void startIdleWatchdog() {
            idleWatchdog = new Thread(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (SystemClock.elapsedRealtime() - lastAuthenticatedActivityMillis.get() >=
                            arguments.idleTimeoutMillis) {
                        requestShutdown("idle_timeout");
                        return;
                    }
                }
            }, "WarpnectDirectShellFdIdleWatchdog");
            idleWatchdog.setDaemon(true);
            idleWatchdog.start();
        }

        private void requestShutdown(String reason) {
            if (!running.compareAndSet(true, false)) return;
            log("SHUTDOWN result=REQUESTED reason=" + reason);
            releaseEndpoint();
        }

        private void releaseEndpoint() {
            if (!endpointReleased.compareAndSet(false, true)) return;
            FileDescriptor listener = listenerDescriptor;
            listenerDescriptor = null;
            if (listener != null) {
                try {
                    DirectShellProbeIpv4Socket.closeListener(listener);
                } catch (IOException exception) {
                    log("ENDPOINT_RELEASE_FAILED type=" + exception.getClass().getSimpleName());
                }
            }
            log("ENDPOINT_RELEASED transport=LOOPBACK_TCP endpoint=" + endpoint());
        }

        private void joinWatchdog() {
            Thread watchdog = idleWatchdog;
            if (watchdog == null || watchdog == Thread.currentThread()) return;
            watchdog.interrupt();
            try {
                watchdog.join(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private void writeResponse(
                DataOutputStream output,
                long requestId,
                DirectShellProbeProtocol.Status status,
                String payload
        ) throws IOException {
            DirectShellProbeProtocol.writeResponse(
                    output,
                    requestId,
                    status,
                    payload.getBytes(StandardCharsets.UTF_8),
                    arguments.secret
            );
        }

        private String endpoint() {
            return "127.0.0.1:" + arguments.port;
        }
    }

    private static final class DescriptorResponse {
        final DirectShellFdProbeBinderProtocol.Result result;
        final SharedMemory sharedMemory;
        final boolean replyHasFd;
        final String detail;

        private DescriptorResponse(
                DirectShellFdProbeBinderProtocol.Result result,
                SharedMemory sharedMemory,
                boolean replyHasFd,
                String detail
        ) {
            this.result = result;
            this.sharedMemory = sharedMemory;
            this.replyHasFd = replyHasFd;
            this.detail = detail;
        }

        static DescriptorResponse success(SharedMemory sharedMemory, boolean replyHasFd) {
            return new DescriptorResponse(
                    DirectShellFdProbeBinderProtocol.Result.OK,
                    sharedMemory,
                    replyHasFd,
                    "none"
            );
        }

        static DescriptorResponse failure(DirectShellFdProbeBinderProtocol.Result result, String detail) {
            return new DescriptorResponse(result, null, false, detail);
        }
    }

    private static final class Operation {
        final DirectShellProbeProtocol.Status status;
        final String classification;
        final String payload;

        private Operation(DirectShellProbeProtocol.Status status, String classification, String payload) {
            this.status = status;
            this.classification = classification;
            this.payload = payload;
        }

        static Operation ok(String payload) {
            return new Operation(DirectShellProbeProtocol.Status.OK, "OK", payload);
        }

        static Operation failed(String payload) {
            return new Operation(DirectShellProbeProtocol.Status.INTERNAL_ERROR, "FAILED", payload);
        }

        static Operation badState(String payload) {
            return new Operation(DirectShellProbeProtocol.Status.BAD_STATE, "BAD_STATE", payload);
        }

        static Operation unsupported(String payload) {
            return new Operation(DirectShellProbeProtocol.Status.UNSUPPORTED, "UNSUPPORTED", payload);
        }
    }

    private static final class ServerArguments {
        final int port;
        final byte[] secret;
        final long generationId;
        final long idleTimeoutMillis;

        private ServerArguments(int port, byte[] secret, long generationId, long idleTimeoutMillis) {
            this.port = port;
            this.secret = secret;
            this.generationId = generationId;
            this.idleTimeoutMillis = idleTimeoutMillis;
        }

        static ServerArguments parse(String[] args) {
            int port = -1;
            String encodedSecret = null;
            long generationId = -1L;
            long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--port".equals(argument)) {
                    port = Integer.parseInt(requireValue(args, ++index, argument));
                } else if ("--secret-base64".equals(argument)) {
                    encodedSecret = requireValue(args, ++index, argument);
                } else if ("--generation".equals(argument)) {
                    generationId = Long.parseLong(requireValue(args, ++index, argument));
                } else if ("--idle-timeout-ms".equals(argument)) {
                    idleTimeoutMillis = Long.parseLong(requireValue(args, ++index, argument));
                } else {
                    throw new IllegalArgumentException("unknown_argument");
                }
            }
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("invalid_port");
            if (generationId <= 0L) throw new IllegalArgumentException("invalid_generation");
            if (idleTimeoutMillis < MIN_IDLE_TIMEOUT_MILLIS || idleTimeoutMillis > MAX_IDLE_TIMEOUT_MILLIS) {
                throw new IllegalArgumentException("invalid_idle_timeout");
            }
            try {
                return new ServerArguments(
                        port,
                        DirectShellProbeProtocol.requireSecret(Base64.getUrlDecoder().decode(
                                encodedSecret == null ? "" : encodedSecret
                        )),
                        generationId,
                        idleTimeoutMillis
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid_secret");
            }
        }

        private static String requireValue(String[] args, int index, String argument) {
            if (index >= args.length) throw new IllegalArgumentException("missing_value_" + argument);
            return args[index];
        }
    }

    private static String classLoaderName() {
        ClassLoader loader = WarpnectPrivilegedFdSharedMemoryProbeServer.class.getClassLoader();
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }

    private static String currentGid() {
        try {
            return String.valueOf(Os.getgid());
        } catch (RuntimeException exception) {
            return "unavailable";
        }
    }

    private static String readSelinuxContext() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/attr/current"))) {
            String line = reader.readLine();
            return line == null ? "unavailable" : safe(line);
        } catch (IOException exception) {
            return "unavailable";
        }
    }

    private static int fdCount() {
        String[] entries = new File("/proc/self/fd").list();
        return entries == null ? -1 : entries.length;
    }

    private static void log(String message) {
        Log.i(TAG, message);
        System.out.println(message);
    }

    private static String safe(String value) {
        if (value == null) return "none";
        String sanitized = value.replace('\n', '_').replace('\r', '_').replace('\u0000', '_');
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300) + "...";
    }
}
