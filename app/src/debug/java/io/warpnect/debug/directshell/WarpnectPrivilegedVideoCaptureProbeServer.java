package io.warpnect.debug.directshell;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import android.view.Surface;

import io.warpnect.capture.CaptureCapabilities;
import io.warpnect.capture.CaptureError;
import io.warpnect.capture.CaptureRequest;
import io.warpnect.capture.CaptureSessionSnapshot;
import io.warpnect.platform.capture.privileged.QualifiedPrivilegedDisplayCaptureApi;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEBUG-only app_process server that mirrors a physical Android display into an app-owned encoder
 * Surface. MediaCodec, AVC emission, Session, and all network code remain in the normal process.
 */
public final class WarpnectPrivilegedVideoCaptureProbeServer {
    private static final String TAG = "WarpnectDirectShellVideo";
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000L;
    private static final long MIN_IDLE_TIMEOUT_MILLIS = 3_000L;
    // The timeout bounds pre-owner bootstrap; an attached Host owner has no idle expiry.
    private static final long MAX_IDLE_TIMEOUT_MILLIS = 900_000L;
    private static final int CONTROL_ACCEPT_TIMEOUT_MILLIS = 250;

    private WarpnectPrivilegedVideoCaptureProbeServer() {}

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            new ProbeServer(ServerArguments.parse(args)).run();
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

    private static final class ProbeServer {
        private final ServerArguments arguments;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean endpointReleased = new AtomicBoolean(false);
        private final AtomicLong lastAuthenticatedActivityMillis =
                new AtomicLong(SystemClock.elapsedRealtime());
        private final DirectShellOwnerLease ownerLease = new DirectShellOwnerLease();
        private final DirectShellDisplayCapture capture;
        private volatile FileDescriptor listenerDescriptor;
        private Thread idleWatchdog;

        ProbeServer(ServerArguments arguments) {
            this.arguments = arguments;
            capture = new DirectShellDisplayCapture(arguments.generationId);
        }

        void run() throws IOException {
            if (Process.myUid() != Process.SHELL_UID) {
                throw new IllegalStateException("IDENTITY_UNAVAILABLE_NOT_SHELL_UID");
            }
            listenerDescriptor = DirectShellProbeIpv4Socket.bindListener(arguments.port);
            log("START result=READY pid=" + Process.myPid() + " uid=" + Process.myUid() +
                    " gid=" + currentGid() + " uid_kind=SHELL api=" + DirectShellRuntimeApi.sdkInt() +
                    " api_source=" + DirectShellRuntimeApi.source() + " selinux_context=" +
                    readSelinuxContext() + " generation=" + arguments.generationId +
                    " endpoint=" + endpoint() + " transport=LOOPBACK_TCP");
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
                capture.stopForShutdown();
                ownerLease.close();
                releaseEndpoint();
                joinWatchdog();
            }
        }

        private void handleControl(DirectShellProbeIpv4Socket client) {
            DirectShellProbeProtocol.RequestOrder order = new DirectShellProbeProtocol.RequestOrder();
            boolean ownerTransferred = false;
            try {
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
                        respond(output, request.requestId, DirectShellProbeProtocol.Status.OUT_OF_ORDER,
                                "request_id_not_strictly_increasing");
                        log("REQUEST id=" + request.requestId + " command=" + request.command.name() +
                                " result=OUT_OF_ORDER");
                        continue;
                    }
                    lastAuthenticatedActivityMillis.set(SystemClock.elapsedRealtime());
                    if (request.command == DirectShellProbeProtocol.Command.HOST_OWNER_ATTACH) {
                        ownerTransferred = ownerLease.attach(client, request, output, arguments.secret,
                                arguments.generationId, () -> requestShutdown("owner_connection_lost"));
                        log("HOST_OWNER result=" + (ownerTransferred ? "ATTACHED" : "REJECTED") +
                                " generation=" + arguments.generationId);
                        return;
                    }
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
            } finally {
                if (!ownerTransferred) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                        // Preserve the authenticated operation result.
                    }
                }
            }
        }

        private boolean dispatch(DirectShellProbeProtocol.Request request, DataOutputStream output)
                throws IOException {
            DirectShellDisplayCapture.Operation operation;
            switch (request.command) {
                case PING:
                    respond(output, request.requestId, DirectShellProbeProtocol.Status.OK, "PONG");
                    logRequest(request, "OK");
                    return false;
                case GET_RUNTIME_INFO:
                    respond(output, request.requestId, DirectShellProbeProtocol.Status.OK, runtimeInfoPayload());
                    logRequest(request, "OK");
                    return false;
                case VIDEO_QUALIFY:
                    operation = capture.qualify();
                    writeOperation(output, request, operation);
                    return false;
                case VIDEO_START:
                    operation = capture.start(arguments.secret, parseCaptureRequest(request.payload));
                    writeOperation(output, request, operation);
                    return false;
                case VIDEO_UPDATE:
                    operation = capture.update(parseCaptureRequest(request.payload));
                    writeOperation(output, request, operation);
                    return false;
                case VIDEO_STOP:
                    operation = capture.stop();
                    writeOperation(output, request, operation);
                    return false;
                case GET_VIDEO_DIAGNOSTICS:
                    operation = capture.diagnostics();
                    writeOperation(output, request, operation);
                    return false;
                case SHUTDOWN:
                    respond(output, request.requestId, DirectShellProbeProtocol.Status.OK, "SHUTDOWN_ACCEPTED");
                    logRequest(request, "OK");
                    return true;
                default:
                    respond(output, request.requestId, DirectShellProbeProtocol.Status.UNSUPPORTED,
                            "unsupported_command");
                    logRequest(request, "UNSUPPORTED");
                    return false;
            }
        }

        private CaptureRequest parseCaptureRequest(byte[] payload) {
            String raw = new String(payload, StandardCharsets.UTF_8);
            if (raw.length() > 256) throw new IllegalArgumentException("capture_payload_too_large");
            Map<String, String> values = new HashMap<>();
            for (String part : raw.split(";", -1)) {
                String[] pair = part.split("=", -1);
                if (pair.length != 2 || pair[0].isEmpty() || values.put(pair[0], pair[1]) != null) {
                    throw new IllegalArgumentException("capture_payload_invalid");
                }
            }
            if (values.size() != 4 || !values.containsKey("display_id") ||
                    !values.containsKey("width") || !values.containsKey("height") ||
                    !values.containsKey("follow_rotation")) {
                throw new IllegalArgumentException("capture_payload_fields");
            }
            int displayId = Integer.parseInt(values.get("display_id"));
            int width = Integer.parseInt(values.get("width"));
            int height = Integer.parseInt(values.get("height"));
            String followValue = values.get("follow_rotation");
            if (displayId < 0 || width <= 0 || height <= 0 ||
                    (!"true".equals(followValue) && !"false".equals(followValue))) {
                throw new IllegalArgumentException("capture_payload_values");
            }
            return new CaptureRequest(displayId, width, height, Boolean.parseBoolean(followValue));
        }

        private void writeOperation(
                DataOutputStream output,
                DirectShellProbeProtocol.Request request,
                DirectShellDisplayCapture.Operation operation
        ) throws IOException {
            respond(output, request.requestId, operation.status, operation.payload);
            logRequest(request, operation.classification);
        }

        private void respond(DataOutputStream output, long requestId, DirectShellProbeProtocol.Status status,
                String payload) throws IOException {
            DirectShellProbeProtocol.writeResponse(
                    output,
                    requestId,
                    status,
                    bytes(payload),
                    arguments.secret
            );
        }

        private void logRequest(DirectShellProbeProtocol.Request request, String result) {
            log("REQUEST id=" + request.requestId + " command=" + request.command.name() +
                    " result=" + result);
        }

        private String runtimeInfoPayload() {
            return "pid=" + Process.myPid() + '\n' +
                    "uid=" + Process.myUid() + '\n' +
                    "gid=" + currentGid() + '\n' +
                    "uid_kind=" + (Process.myUid() == Process.SHELL_UID ? "SHELL" : "OTHER") + '\n' +
                    "api=" + DirectShellRuntimeApi.sdkInt() + '\n' +
                    "selinux_context=" + readSelinuxContext() + '\n' +
                    capture.diagnostics().payload;
        }

        private void startIdleWatchdog() {
            idleWatchdog = new Thread(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (DirectShellOwnerLease.bootstrapExpired(SystemClock.elapsedRealtime(),
                            lastAuthenticatedActivityMillis.get(), arguments.idleTimeoutMillis,
                            ownerLease.attached())) {
                        requestShutdown("bootstrap_owner_timeout");
                        return;
                    }
                }
            }, "WarpnectDirectShellVideoIdleWatchdog");
            idleWatchdog.setDaemon(true);
            idleWatchdog.start();
        }

        private void requestShutdown(String reason) {
            if (!running.compareAndSet(true, false)) return;
            capture.stopForShutdown();
            ownerLease.close();
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
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private String endpoint() {
            return "127.0.0.1:" + arguments.port;
        }
    }

    private static final class DirectShellDisplayCapture {
        private final long generationId;
        private State state = State.NEW;
        private DirectShellShellAudioContext.Result identity =
                DirectShellShellAudioContext.Result.failure("NOT_PREPARED");
        private QualifiedPrivilegedDisplayCaptureApi captureApi;
        private DirectShellVideoCaptureProbeExternalProviderClient providerClient;
        private Surface targetSurface;
        private CaptureRequest activeRequest;
        private CaptureCapabilities capabilities;
        private CaptureError lastError = CaptureError.None;
        private String lastClassification = "NOT_PREPARED";

        DirectShellDisplayCapture(long generationId) {
            this.generationId = generationId;
        }

        synchronized Operation qualify() {
            if (state == State.RUNNING) return Operation.badState(state, "VIDEO_STOP_REQUIRED");
            if (!ensureIdentity()) return Operation.failed(lastClassification, diagnosticsPayload());
            CaptureCapabilities qualified = captureApi.queryCapabilities();
            capabilities = qualified;
            lastError = qualified.getLastError();
            if (!qualified.getBackendAvailable()) {
                state = State.FAILED;
                lastClassification = "CAPTURE_QUALIFICATION_" + lastError.name();
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            state = State.QUALIFIED;
            lastClassification = "VIDEO_CAPTURE_QUALIFIED";
            log("VIDEO_QUALIFIED backend=" + qualified.getBackend().name() +
                    " source_displays=" + qualified.getSupportedSourceDisplays().size());
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation start(byte[] secret, CaptureRequest request) {
            if (state == State.RUNNING) return Operation.badState(state, "VIDEO_ALREADY_RUNNING");
            if (request == null) return Operation.failed("CAPTURE_REQUEST_INVALID", diagnosticsPayload());
            Operation qualification = qualify();
            if (qualification.status != DirectShellProbeProtocol.Status.OK) return qualification;
            DirectShellVideoCaptureProbeExternalProviderClient.OpenResult open =
                    DirectShellVideoCaptureProbeExternalProviderClient.open(identity.context);
            if (!open.isSuccess()) {
                state = State.FAILED;
                lastClassification = open.detail;
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            providerClient = open.client;
            DirectShellVideoCaptureProbeExternalProviderClient.SurfaceResult surfaceResult =
                    providerClient.requestSurface(
                            secret,
                            generationId,
                            request.getOutputWidth(),
                            request.getOutputHeight()
                    );
            if (surfaceResult.result != DirectShellVideoCaptureProbeBinderProtocol.Result.OK ||
                    surfaceResult.surface == null) {
                closeProviderOnly();
                state = State.FAILED;
                lastClassification = "SURFACE_HANDOFF_" + surfaceResult.result.name();
                return Operation.failed(lastClassification + " detail=" + surfaceResult.detail, diagnosticsPayload());
            }
            targetSurface = surfaceResult.surface;
            if (!targetSurface.isValid()) {
                closeRemoteResources();
                state = State.FAILED;
                lastClassification = "SURFACE_HANDOFF_INVALID_SURFACE";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            CaptureError error = captureApi.startCapture(request, targetSurface);
            lastError = error;
            if (error != CaptureError.None) {
                closeRemoteResources();
                state = State.FAILED;
                lastClassification = "DISPLAY_MIRROR_START_" + error.name();
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            activeRequest = request;
            state = State.RUNNING;
            lastClassification = "DISPLAY_MIRROR_RUNNING";
            log("VIDEO_MIRROR_STARTED generation=" + generationId + " backend=" +
                    captureApi.snapshot().getBackend().name() + " target_surface_valid=true");
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation update(CaptureRequest request) {
            if (state != State.RUNNING) return Operation.badState(state, "VIDEO_NOT_RUNNING");
            CaptureError error = captureApi.updateCapture(request);
            lastError = error;
            if (error != CaptureError.None) {
                lastClassification = "DISPLAY_MIRROR_UPDATE_" + error.name();
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            activeRequest = request;
            lastClassification = "DISPLAY_MIRROR_UPDATED";
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation stop() {
            CaptureError error = CaptureError.None;
            if (state == State.RUNNING && captureApi != null) {
                error = captureApi.stopCapture();
            }
            closeRemoteResources();
            lastError = error;
            state = error == CaptureError.None ? State.STOPPED : State.FAILED;
            lastClassification = error == CaptureError.None ? "DISPLAY_MIRROR_STOPPED" :
                    "DISPLAY_MIRROR_STOP_" + error.name();
            log("VIDEO_MIRROR_STOPPED generation=" + generationId + " error=" + error.name());
            return error == CaptureError.None ? Operation.ok(lastClassification, diagnosticsPayload()) :
                    Operation.failed(lastClassification, diagnosticsPayload());
        }

        synchronized Operation diagnostics() {
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized void stopForShutdown() {
            if (state == State.RUNNING && captureApi != null) {
                lastError = captureApi.stopCapture();
            }
            closeRemoteResources();
            if (state != State.FAILED) state = State.STOPPED;
        }

        private boolean ensureIdentity() {
            if (identity.isAvailable() && captureApi != null) return true;
            identity = DirectShellShellAudioContext.create();
            if (!identity.isAvailable()) {
                state = State.FAILED;
                lastClassification = "CONTEXT_UNAVAILABLE";
                return false;
            }
            captureApi = new QualifiedPrivilegedDisplayCaptureApi();
            return true;
        }

        private void closeProviderOnly() {
            DirectShellVideoCaptureProbeExternalProviderClient client = providerClient;
            providerClient = null;
            if (client != null) client.close();
        }

        private void closeRemoteResources() {
            Surface surface = targetSurface;
            targetSurface = null;
            activeRequest = null;
            if (surface != null) surface.release();
            closeProviderOnly();
        }

        private String diagnosticsPayload() {
            CaptureSessionSnapshot snapshot = captureApi == null ? null : captureApi.snapshot();
            String identityPayload = identity.toPayload();
            return "video_state=" + state.name() + '\n' +
                    "generation=" + generationId + '\n' +
                    "classification=" + lastClassification + '\n' +
                    "last_error=" + lastError.name() + '\n' +
                    "backend=" + (snapshot == null ? "None" : snapshot.getBackend().name()) + '\n' +
                    "capture_state=" + (snapshot == null ? "Stopped" : snapshot.getState().name()) + '\n' +
                    "target_surface_valid=" + (targetSurface != null && targetSurface.isValid()) + '\n' +
                    "source_display=" + (activeRequest == null ? "none" : activeRequest.getSourceDisplayId()) + '\n' +
                    "output_width=" + (activeRequest == null ? "none" : activeRequest.getOutputWidth()) + '\n' +
                    "output_height=" + (activeRequest == null ? "none" : activeRequest.getOutputHeight()) + '\n' +
                    identityPayload;
        }

        private enum State {
            NEW,
            QUALIFIED,
            RUNNING,
            STOPPED,
            FAILED
        }

        static final class Operation {
            final DirectShellProbeProtocol.Status status;
            final String classification;
            final String payload;

            private Operation(DirectShellProbeProtocol.Status status, String classification, String payload) {
                this.status = status;
                this.classification = classification;
                this.payload = payload;
            }

            static Operation ok(String classification, String payload) {
                return new Operation(DirectShellProbeProtocol.Status.OK, classification, payload);
            }

            static Operation failed(String classification, String payload) {
                return new Operation(DirectShellProbeProtocol.Status.INTERNAL_ERROR, classification, payload);
            }

            static Operation badState(State state, String classification) {
                return new Operation(DirectShellProbeProtocol.Status.BAD_STATE, classification,
                        "video_state=" + state.name() + "\nclassification=" + classification);
            }
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
            String secretFile = null;
            long generationId = -1L;
            long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--port".equals(argument)) {
                    port = Integer.parseInt(requireValue(args, ++index, argument));
                } else if ("--secret-base64".equals(argument)) {
                    encodedSecret = requireValue(args, ++index, argument);
                } else if ("--secret-file".equals(argument)) {
                    secretFile = requireValue(args, ++index, argument);
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
                if ((encodedSecret == null) == (secretFile == null)) {
                    throw new IllegalArgumentException("invalid_secret_source");
                }
                byte[] secret = encodedSecret != null
                        ? Base64.getUrlDecoder().decode(encodedSecret)
                        : DirectShellSecretFile.readAndDelete(secretFile);
                return new ServerArguments(
                        port,
                        DirectShellProbeProtocol.requireSecret(secret),
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

    private static String currentGid() {
        try {
            return String.valueOf(Os.getgid());
        } catch (Throwable throwable) {
            return "UNAVAILABLE_" + throwable.getClass().getSimpleName();
        }
    }

    private static String readSelinuxContext() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/attr/current"))) {
            String value = reader.readLine();
            return value == null || value.isEmpty() ? "UNAVAILABLE" : safe(value);
        } catch (IOException exception) {
            return "UNAVAILABLE_" + exception.getClass().getSimpleName();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        if (value == null) return "none";
        String sanitized = value.replace('\n', '_').replace('\r', '_').replace('\u0000', '_');
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512) + "...";
    }

    private static void log(String message) {
        Log.i(TAG, message);
        System.out.println(TAG + " " + message);
        System.out.flush();
    }
}
