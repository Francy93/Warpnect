package io.warpnect.debug.directshell;

import android.app.AppOpsManager;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEBUG-only app_process experiment for direct shell REMOTE_SUBMIX capture.
 *
 * <p>This is deliberately separate from {@link WarpnectPrivilegedServer}. It does not use
 * Shizuku, AudioPolicy, media rings, Session, SCL, codecs, or transport.</p>
 */
public final class WarpnectPrivilegedSystemAudioProbeServer {
    private static final String TAG = "WarpnectDirectShellAudio";
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 20_000L;
    private static final long MIN_IDLE_TIMEOUT_MILLIS = 3_000L;
    private static final long MAX_IDLE_TIMEOUT_MILLIS = 60_000L;
    private static final int CONTROL_ACCEPT_TIMEOUT_MILLIS = 250;

    private WarpnectPrivilegedSystemAudioProbeServer() {}

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            ServerArguments arguments = ServerArguments.parse(args);
            new ProbeServer(arguments).run();
        } catch (IllegalArgumentException exception) {
            log("START_FAILURE result=INVALID_ARGUMENT message=" + safe(exception.getMessage()));
            exitCode = 2;
        } catch (IOException exception) {
            log(
                    "START_FAILURE result=CONTROL_START_FAILED type=" +
                            exception.getClass().getSimpleName() +
                            " message=" + safe(exception.getMessage())
            );
            exitCode = 3;
        } catch (Throwable throwable) {
            log(
                    "START_FAILURE result=UNEXPECTED type=" + throwable.getClass().getSimpleName() +
                            " message=" + safe(throwable.getMessage())
            );
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
        private final DirectShellAudioCapture capture = new DirectShellAudioCapture();
        private volatile FileDescriptor listenerDescriptor;
        private Thread idleWatchdog;

        ProbeServer(ServerArguments arguments) {
            this.arguments = arguments;
        }

        void run() throws IOException {
            if (Process.myUid() != Process.SHELL_UID) {
                throw new IllegalStateException("IDENTITY_UNAVAILABLE_NOT_SHELL_UID");
            }
            try {
                listenerDescriptor = DirectShellProbeIpv4Socket.bindListener(arguments.port);
            } catch (IOException exception) {
                log(
                        "START_FAILURE result=ENDPOINT_BIND_FAILED transport=LOOPBACK_TCP endpoint=" +
                                endpoint() + " type=" + exception.getClass().getSimpleName()
                );
                throw exception;
            }
            log(
                    "START result=READY pid=" + Process.myPid() +
                            " uid=" + Process.myUid() +
                            " gid=" + currentGid() +
                            " uid_kind=SHELL api=" + Build.VERSION.SDK_INT +
                            " selinux_context=" + readSelinuxContext() +
                            " endpoint=" + endpoint() + " transport=LOOPBACK_TCP"
            );
            startIdleWatchdog();
            try {
                while (running.get()) {
                    try {
                        DirectShellProbeIpv4Socket client = DirectShellProbeIpv4Socket.accept(
                                listenerDescriptor,
                                CONTROL_ACCEPT_TIMEOUT_MILLIS
                        );
                        if (client == null) continue;
                        log("CONTROL result=CONNECTED endpoint=" + endpoint());
                        handleControl(client);
                    } catch (IOException exception) {
                        if (running.get()) {
                            log("CONTROL_ACCEPT_FAILURE type=" + exception.getClass().getSimpleName());
                        }
                    }
                }
            } finally {
                capture.stopForShutdown();
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
                        writeResponse(
                                output,
                                request.requestId,
                                DirectShellProbeProtocol.Status.OUT_OF_ORDER,
                                "request_id_not_strictly_increasing"
                        );
                        log(
                                "REQUEST id=" + request.requestId + " command=" + request.command.name() +
                                        " result=OUT_OF_ORDER"
                        );
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
            DirectShellAudioCapture.Operation operation;
            switch (request.command) {
                case PING:
                    writeResponse(output, request.requestId, DirectShellProbeProtocol.Status.OK, "PONG");
                    log("REQUEST id=" + request.requestId + " command=PING result=OK");
                    return false;
                case GET_RUNTIME_INFO:
                    writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.OK,
                            runtimeInfoPayload()
                    );
                    log("REQUEST id=" + request.requestId + " command=GET_RUNTIME_INFO result=OK");
                    return false;
                case AUDIO_PREPARE:
                    operation = capture.prepare();
                    writeOperation(output, request, operation);
                    return false;
                case AUDIO_START:
                    operation = capture.start();
                    writeOperation(output, request, operation);
                    return false;
                case AUDIO_SAMPLE:
                    operation = capture.sample(parseSampleDurationMillis(request.payload));
                    writeOperation(output, request, operation);
                    return false;
                case AUDIO_STOP:
                    operation = capture.stop();
                    writeOperation(output, request, operation);
                    return false;
                case GET_AUDIO_DIAGNOSTICS:
                    operation = capture.diagnostics();
                    writeOperation(output, request, operation);
                    return false;
                case SHUTDOWN:
                    writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.OK,
                            "SHUTDOWN_ACCEPTED"
                    );
                    log("REQUEST id=" + request.requestId + " command=SHUTDOWN result=OK");
                    return true;
                default:
                    writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.UNSUPPORTED,
                            "unsupported_command"
                    );
                    log("REQUEST id=" + request.requestId + " command=UNKNOWN result=UNSUPPORTED");
                    return false;
            }
        }

        private void writeOperation(
                DataOutputStream output,
                DirectShellProbeProtocol.Request request,
                DirectShellAudioCapture.Operation operation
        ) throws IOException {
            writeResponse(output, request.requestId, operation.status, operation.payload);
            log(
                    "REQUEST id=" + request.requestId + " command=" + request.command.name() +
                            " result=" + operation.classification
            );
            if (request.command == DirectShellProbeProtocol.Command.AUDIO_SAMPLE) {
                log("AUDIO_SAMPLE_METRICS " + capture.metricSummary());
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
                    bytes(payload),
                    arguments.secret
            );
        }

        private String runtimeInfoPayload() {
            return "pid=" + Process.myPid() + '\n' +
                    "uid=" + Process.myUid() + '\n' +
                    "gid=" + currentGid() + '\n' +
                    "uid_kind=" + (Process.myUid() == Process.SHELL_UID ? "SHELL" : "OTHER") + '\n' +
                    "api=" + Build.VERSION.SDK_INT + '\n' +
                    "selinux_context=" + readSelinuxContext() + '\n' +
                    "java_class_path=" + safe(System.getProperty("java.class.path", "")) + '\n' +
                    "audio_probe_state=" + capture.stateName() + '\n' +
                    capture.identityPayload();
        }

        private int parseSampleDurationMillis(byte[] payload) {
            String value = new String(payload, StandardCharsets.UTF_8);
            if (value.isEmpty()) return DirectShellAudioCapture.DEFAULT_SAMPLE_DURATION_MILLIS;
            if (!value.matches("duration_ms=[0-9]{1,4}")) {
                return DirectShellAudioCapture.DEFAULT_SAMPLE_DURATION_MILLIS;
            }
            int parsed = Integer.parseInt(value.substring("duration_ms=".length()));
            return Math.max(
                    DirectShellAudioCapture.MIN_SAMPLE_DURATION_MILLIS,
                    Math.min(DirectShellAudioCapture.MAX_SAMPLE_DURATION_MILLIS, parsed)
            );
        }

        private void startIdleWatchdog() {
            idleWatchdog = new Thread(
                    () -> {
                        while (running.get()) {
                            try {
                                Thread.sleep(250L);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            if (SystemClock.elapsedRealtime() - lastAuthenticatedActivityMillis.get() >=
                                    arguments.idleTimeoutMillis) {
                                requestShutdown("idle_timeout");
                                return;
                            }
                        }
                    },
                    "WarpnectDirectShellAudioIdleWatchdog"
            );
            idleWatchdog.setDaemon(true);
            idleWatchdog.start();
        }

        private void requestShutdown(String reason) {
            if (!running.compareAndSet(true, false)) return;
            capture.stopForShutdown();
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

    private static final class DirectShellAudioCapture {
        static final int DEFAULT_SAMPLE_DURATION_MILLIS = 1_500;
        static final int MIN_SAMPLE_DURATION_MILLIS = 100;
        static final int MAX_SAMPLE_DURATION_MILLIS = 5_000;

        private static final int SAMPLE_RATE_HZ = 48_000;
        private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO;
        private static final int CHANNEL_COUNT = 2;
        private static final int PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
        private static final String CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT";
        private static final String RECORD_AUDIO = "android.permission.RECORD_AUDIO";

        private State state = State.NEW;
        private AudioRecord record;
        private DirectShellShellAudioContext.Result identity =
                DirectShellShellAudioContext.Result.failure("NOT_PREPARED");
        private Configuration configuration;
        private DirectShellAudioProbeMetrics.Snapshot lastMetrics;
        private String lastClassification = "NOT_PREPARED";
        private long lastSampleIndex;

        @TargetApi(Build.VERSION_CODES.S)
        @SuppressLint("WrongConstant") // REMOTE_SUBMIX is intentionally restricted to the shell runtime.
        synchronized Operation prepare() {
            if (state == State.RECORDING) return Operation.badState(state, "AUDIO_STOP_REQUIRED");
            releaseRecord();
            identity = DirectShellShellAudioContext.create();
            if (!identity.isAvailable()) {
                state = State.FAILED;
                lastClassification = "CONTEXT_UNAVAILABLE";
                return Operation.failed(lastClassification, identity.toPayload());
            }
            Context context = identity.context;
            if (context.checkSelfPermission(CAPTURE_AUDIO_OUTPUT) != PackageManager.PERMISSION_GRANTED) {
                state = State.FAILED;
                lastClassification = "PERMISSION_DENIED_CAPTURE_AUDIO_OUTPUT";
                return Operation.failed(lastClassification, identity.toPayload());
            }
            if (context.checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                state = State.FAILED;
                lastClassification = "PERMISSION_DENIED_RECORD_AUDIO";
                return Operation.failed(lastClassification, identity.toPayload());
            }
            int minimumBufferBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    CHANNEL_MASK,
                    PCM_ENCODING
            );
            if (minimumBufferBytes <= 0) {
                state = State.FAILED;
                lastClassification = "AUDIORECORD_CONSTRUCTION_FAILED_MIN_BUFFER_" + minimumBufferBytes;
                return Operation.failed(lastClassification, identity.toPayload());
            }
            int bufferBytes = Math.max(minimumBufferBytes * 2, SAMPLE_RATE_HZ * CHANNEL_COUNT * Short.BYTES / 50);
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(PCM_ENCODING)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(CHANNEL_MASK)
                        .build();
                record = new AudioRecord.Builder()
                        .setContext(context)
                        .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferBytes)
                        .build();
            } catch (SecurityException exception) {
                state = State.FAILED;
                lastClassification = "PERMISSION_DENIED_" + exception.getClass().getSimpleName();
                return Operation.failed(lastClassification, identity.toPayload());
            } catch (UnsupportedOperationException | IllegalArgumentException exception) {
                state = State.FAILED;
                lastClassification = "AUDIORECORD_CONSTRUCTION_FAILED_" +
                        exception.getClass().getSimpleName();
                return Operation.failed(lastClassification, identity.toPayload());
            } catch (RuntimeException exception) {
                state = State.FAILED;
                lastClassification = "AUDIORECORD_CONSTRUCTION_FAILED_" +
                        exception.getClass().getSimpleName();
                return Operation.failed(lastClassification, identity.toPayload());
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecord();
                state = State.FAILED;
                lastClassification = "AUDIORECORD_UNINITIALIZED";
                return Operation.failed(lastClassification, identity.toPayload());
            }
            configuration = new Configuration(
                    record.getSampleRate(),
                    record.getFormat().getChannelCount(),
                    record.getFormat().getEncoding(),
                    record.getBufferSizeInFrames(),
                    bufferBytes,
                    minimumBufferBytes,
                    record.getAudioSessionId(),
                    appOpMode(context)
            );
            state = State.PREPARED;
            lastClassification = "AUDIO_PREPARED";
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation start() {
            if (state != State.PREPARED || record == null) return Operation.badState(state, "AUDIO_PREPARE_REQUIRED");
            try {
                record.startRecording();
            } catch (SecurityException exception) {
                state = State.FAILED;
                lastClassification = "PERMISSION_DENIED_START_RECORDING";
                return Operation.failed(lastClassification, diagnosticsPayload());
            } catch (IllegalStateException exception) {
                state = State.FAILED;
                lastClassification = "START_RECORDING_FAILED_ILLEGAL_STATE";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                state = State.FAILED;
                lastClassification = "START_RECORDING_FAILED_STATE_" + record.getRecordingState();
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            state = State.RECORDING;
            lastClassification = "AUDIO_RECORDING";
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation sample(int durationMillis) {
            if (state != State.RECORDING || record == null || configuration == null) {
                return Operation.badState(state, "AUDIO_START_REQUIRED");
            }
            lastSampleIndex++;
            DirectShellAudioProbeMetrics.Accumulator metrics = new DirectShellAudioProbeMetrics.Accumulator(
                    configuration.sampleRateHz,
                    configuration.channelCount
            );
            int frameBytes = configuration.channelCount * Short.BYTES;
            byte[] buffer = new byte[Math.max(frameBytes, Math.min(configuration.bufferBytes, 65_536))];
            long deadline = SystemClock.elapsedRealtime() + durationMillis;
            String readFailure = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                int read;
                try {
                    read = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                } catch (RuntimeException exception) {
                    metrics.recordReadError();
                    readFailure = "READ_FAILED_" + exception.getClass().getSimpleName();
                    break;
                }
                if (read > 0) {
                    metrics.acceptPcm16Le(buffer, read);
                } else if (read < 0) {
                    metrics.recordReadError();
                    readFailure = "READ_FAILED_" + read;
                    break;
                }
            }
            lastMetrics = metrics.snapshot();
            if (readFailure != null) {
                lastClassification = readFailure;
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            lastClassification = lastMetrics.hasRealPcm() ? "REAL_PCM_CAPTURED" : "PCM_ZERO_ONLY";
            return Operation.ok(lastClassification, "sample_duration_ms=" + durationMillis + '\n' + diagnosticsPayload());
        }

        synchronized Operation stop() {
            if (state == State.NEW || state == State.STOPPED) return Operation.ok("AUDIO_ALREADY_STOPPED", diagnosticsPayload());
            String stopError = null;
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
                } catch (IllegalStateException exception) {
                    stopError = "AUDIO_STOP_FAILED_" + exception.getClass().getSimpleName();
                }
            }
            releaseRecord();
            state = State.STOPPED;
            lastClassification = stopError == null ? "AUDIO_STOPPED" : stopError;
            return stopError == null
                    ? Operation.ok(lastClassification, diagnosticsPayload())
                    : Operation.failed(lastClassification, diagnosticsPayload());
        }

        synchronized Operation diagnostics() {
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized void stopForShutdown() {
            if (record == null) return;
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
            } catch (IllegalStateException ignored) {
                // Shutdown always releases the record after a failed stop attempt.
            }
            releaseRecord();
            state = State.STOPPED;
        }

        synchronized String stateName() {
            return state.name();
        }

        synchronized String identityPayload() {
            return identity.toPayload();
        }

        synchronized String metricSummary() {
            if (lastMetrics == null) return "result=NOT_CAPTURED";
            return "sample_index=" + lastSampleIndex +
                    " classification=" + lastClassification +
                    " frames=" + lastMetrics.framesRead +
                    " bytes=" + lastMetrics.bytesRead +
                    " non_zero=" + lastMetrics.nonZeroSamples +
                    " peak=" + lastMetrics.peak +
                    " rms=" + String.format(java.util.Locale.ROOT, "%.3f", lastMetrics.rms) +
                    " positive_zero_crossing_hz=" +
                    String.format(java.util.Locale.ROOT, "%.3f", lastMetrics.positiveZeroCrossingHz) +
                    " tone_997hz_amplitude=" +
                    String.format(java.util.Locale.ROOT, "%.3f", lastMetrics.toneAmplitude) +
                    " tone_997hz_energy_ratio=" +
                    String.format(java.util.Locale.ROOT, "%.3f", lastMetrics.toneEnergyRatio) +
                    " read_errors=" + lastMetrics.readErrors +
                    " real_pcm=" + lastMetrics.hasRealPcm();
        }

        private void releaseRecord() {
            if (record != null) {
                try {
                    record.release();
                } catch (RuntimeException ignored) {
                    // The lifecycle state is still reset deterministically.
                }
            }
            record = null;
            configuration = null;
        }

        private String diagnosticsPayload() {
            StringBuilder output = new StringBuilder();
            output.append("state=").append(state.name()).append('\n');
            output.append("classification=").append(lastClassification).append('\n');
            output.append(identity.toPayload()).append('\n');
            if (configuration == null) {
                output.append("audio_record=UNAVAILABLE");
            } else {
                output.append(configuration.toPayload());
            }
            if (lastMetrics != null) {
                output.append('\n').append(lastMetrics.toPayload());
            }
            return output.toString();
        }

        private static String appOpMode(Context context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "API_LT_29";
            try {
                AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
                if (appOps == null) return "UNAVAILABLE";
                return String.valueOf(appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        DirectShellShellAudioContext.SHELL_PACKAGE_NAME
                ));
            } catch (RuntimeException exception) {
                return "UNAVAILABLE_" + exception.getClass().getSimpleName();
            }
        }

        private enum State {
            NEW,
            PREPARED,
            RECORDING,
            STOPPED,
            FAILED,
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

            static Operation badState(State state, String detail) {
                return new Operation(
                        DirectShellProbeProtocol.Status.BAD_STATE,
                        "BAD_STATE_" + state.name(),
                        "state=" + state.name() + '\n' + "detail=" + detail
                );
            }
        }

        private static final class Configuration {
            final int sampleRateHz;
            final int channelCount;
            final int encoding;
            final int bufferFrames;
            final int bufferBytes;
            final int minimumBufferBytes;
            final int sessionId;
            final String recordAudioAppOpMode;

            Configuration(
                    int sampleRateHz,
                    int channelCount,
                    int encoding,
                    int bufferFrames,
                    int bufferBytes,
                    int minimumBufferBytes,
                    int sessionId,
                    String recordAudioAppOpMode
            ) {
                this.sampleRateHz = sampleRateHz;
                this.channelCount = channelCount;
                this.encoding = encoding;
                this.bufferFrames = bufferFrames;
                this.bufferBytes = bufferBytes;
                this.minimumBufferBytes = minimumBufferBytes;
                this.sessionId = sessionId;
                this.recordAudioAppOpMode = recordAudioAppOpMode;
            }

            String toPayload() {
                return "audio_record=AVAILABLE\n" +
                        "source=REMOTE_SUBMIX\n" +
                        "sample_rate_hz=" + sampleRateHz + '\n' +
                        "channel_count=" + channelCount + '\n' +
                        "encoding=" + encoding + '\n' +
                        "buffer_frames=" + bufferFrames + '\n' +
                        "buffer_bytes=" + bufferBytes + '\n' +
                        "minimum_buffer_bytes=" + minimumBufferBytes + '\n' +
                        "session_id=" + sessionId + '\n' +
                        "record_audio_app_op_mode=" + recordAudioAppOpMode;
            }
        }
    }

    private static final class ServerArguments {
        final int port;
        final byte[] secret;
        final long idleTimeoutMillis;

        private ServerArguments(int port, byte[] secret, long idleTimeoutMillis) {
            this.port = port;
            this.secret = secret;
            this.idleTimeoutMillis = idleTimeoutMillis;
        }

        static ServerArguments parse(String[] args) {
            int port = -1;
            String encodedSecret = null;
            long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--port".equals(argument)) {
                    port = Integer.parseInt(requireValue(args, ++index, argument));
                } else if ("--secret-base64".equals(argument)) {
                    encodedSecret = requireValue(args, ++index, argument);
                } else if ("--idle-timeout-ms".equals(argument)) {
                    idleTimeoutMillis = Long.parseLong(requireValue(args, ++index, argument));
                } else {
                    throw new IllegalArgumentException("unknown_argument");
                }
            }
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("invalid_port");
            if (idleTimeoutMillis < MIN_IDLE_TIMEOUT_MILLIS || idleTimeoutMillis > MAX_IDLE_TIMEOUT_MILLIS) {
                throw new IllegalArgumentException("invalid_idle_timeout");
            }
            try {
                byte[] secret = Base64.getUrlDecoder().decode(encodedSecret == null ? "" : encodedSecret);
                return new ServerArguments(port, DirectShellProbeProtocol.requireSecret(secret), idleTimeoutMillis);
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
