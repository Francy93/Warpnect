package io.warpnect.debug.directshell;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import io.warpnect.audio.capture.AudioTimestampQuality;
import io.warpnect.platform.audio.capture.shared.PcmRingSignalRecord;
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEBUG-only DirectShell integration spike.
 *
 * <p>It never starts Shizuku, Session, Opus, SCL, or transport. The server receives only one
 * authenticated generation's production PCM-ring descriptor set and writes shell-captured
 * REMOTE_SUBMIX PCM into the existing V1 layout. The normal app drains it with the real
 * SharedPcmAudioDrain.</p>
 */
public final class WarpnectPrivilegedPcmSharedRingProbeServer {
    private static final String TAG = "WarpnectDirectShellRing";
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 45_000L;
    private static final long MIN_IDLE_TIMEOUT_MILLIS = 5_000L;
    private static final long MAX_IDLE_TIMEOUT_MILLIS = 90_000L;
    private static final int CONTROL_ACCEPT_TIMEOUT_MILLIS = 250;

    private WarpnectPrivilegedPcmSharedRingProbeServer() {}

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            ServerArguments arguments = ServerArguments.parse(args);
            new ProbeServer(arguments).run();
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
        private final DirectShellPcmRingProducer producer;
        private volatile FileDescriptor listenerDescriptor;
        private Thread idleWatchdog;

        ProbeServer(ServerArguments arguments) {
            this.arguments = arguments;
            this.producer = new DirectShellPcmRingProducer(arguments.generationId);
        }

        void run() throws IOException {
            if (Process.myUid() != Process.SHELL_UID) {
                throw new IllegalStateException("IDENTITY_UNAVAILABLE_NOT_SHELL_UID");
            }
            listenerDescriptor = DirectShellProbeIpv4Socket.bindListener(arguments.port);
            log("START result=READY pid=" + Process.myPid() + " uid=" + Process.myUid() +
                    " gid=" + currentGid() + " uid_kind=SHELL api=" + DirectShellRuntimeApi.sdkInt() +
                    " api_source=" + DirectShellRuntimeApi.source() +
                    " selinux_context=" + readSelinuxContext() + " generation=" +
                    arguments.generationId + " endpoint=" + endpoint() + " transport=LOOPBACK_TCP");
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
                producer.closeForShutdown();
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
            DirectShellPcmRingProducer.Operation operation;
            switch (request.command) {
                case PING:
                    writeResponse(output, request.requestId, DirectShellProbeProtocol.Status.OK, "PONG");
                    log("REQUEST id=" + request.requestId + " command=PING result=OK");
                    return false;
                case GET_RUNTIME_INFO:
                    writeResponse(output, request.requestId, DirectShellProbeProtocol.Status.OK,
                            runtimeInfoPayload());
                    log("REQUEST id=" + request.requestId +
                            " command=GET_RUNTIME_INFO result=OK");
                    return false;
                case PCM_RING_REQUEST_STALE_GENERATION:
                    operation = producer.negativeStaleGeneration(arguments.secret);
                    return writeOperation(output, request, operation);
                case PCM_RING_REQUEST_INVALID_MAGIC:
                    operation = producer.negativeInvalidMagic(arguments.secret);
                    return writeOperation(output, request, operation);
                case PCM_RING_REQUEST_OVERSIZED_METADATA:
                    operation = producer.negativeOversizedMetadata(arguments.secret);
                    return writeOperation(output, request, operation);
                case PCM_RING_REQUEST_INVALID_PROOF:
                    operation = producer.negativeInvalidProof(arguments.secret);
                    return writeOperation(output, request, operation);
                case PCM_RING_BIND:
                    operation = producer.bind(arguments.secret);
                    return writeOperation(output, request, operation);
                case PCM_RING_REQUEST_REPLAY:
                    operation = producer.negativeReplay(arguments.secret);
                    return writeOperation(output, request, operation);
                case PCM_RING_SIGNAL_TEST:
                    operation = producer.signalAndAwaitAck();
                    return writeOperation(output, request, operation);
                case PCM_RING_AUDIO_PREPARE:
                    operation = producer.prepareAudioRecord();
                    return writeOperation(output, request, operation);
                case PCM_RING_AUDIO_START:
                    operation = producer.startAudioRecord();
                    return writeOperation(output, request, operation);
                case PCM_RING_AUDIO_STOP:
                    operation = producer.stopAudioRecord();
                    return writeOperation(output, request, operation);
                case GET_PCM_RING_DIAGNOSTICS:
                    operation = producer.diagnostics();
                    return writeOperation(output, request, operation);
                case PCM_RING_CLOSE:
                    operation = producer.closeRemoteDescriptors();
                    return writeOperation(output, request, operation);
                case SHUTDOWN:
                    writeResponse(output, request.requestId, DirectShellProbeProtocol.Status.OK,
                            "SHUTDOWN_ACCEPTED");
                    log("REQUEST id=" + request.requestId + " command=SHUTDOWN result=OK");
                    return true;
                default:
                    writeResponse(output, request.requestId, DirectShellProbeProtocol.Status.UNSUPPORTED,
                            "unsupported_command");
                    log("REQUEST id=" + request.requestId + " command=" + request.command.name() +
                            " result=UNSUPPORTED");
                    return false;
            }
        }

        private boolean writeOperation(
                DataOutputStream output,
                DirectShellProbeProtocol.Request request,
                DirectShellPcmRingProducer.Operation operation
        ) throws IOException {
            writeResponse(output, request.requestId, operation.status, operation.payload);
            log("REQUEST id=" + request.requestId + " command=" + request.command.name() +
                    " result=" + operation.classification);
            return false;
        }

        private void writeResponse(
                DataOutputStream output,
                long requestId,
                DirectShellProbeProtocol.Status status,
                String payload
        ) throws IOException {
            DirectShellProbeProtocol.writeResponse(output, requestId, status, bytes(payload),
                    arguments.secret);
        }

        private String runtimeInfoPayload() {
            return "pid=" + Process.myPid() + '\n' +
                    "uid=" + Process.myUid() + '\n' +
                    "gid=" + currentGid() + '\n' +
                    "uid_kind=" + (Process.myUid() == Process.SHELL_UID ? "SHELL" : "OTHER") + '\n' +
                    "api=" + DirectShellRuntimeApi.sdkInt() + '\n' +
                    "api_source=" + DirectShellRuntimeApi.source() + '\n' +
                    "selinux_context=" + readSelinuxContext() + '\n' +
                    "java_class_path=" + safe(System.getProperty("java.class.path", "")) + '\n' +
                    producer.diagnosticsPayload();
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
                    "WarpnectDirectShellRingIdleWatchdog"
            );
            idleWatchdog.setDaemon(true);
            idleWatchdog.start();
        }

        private void requestShutdown(String reason) {
            if (!running.compareAndSet(true, false)) return;
            producer.closeForShutdown();
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

    @TargetApi(Build.VERSION_CODES.S)
    private static final class DirectShellPcmRingProducer {
        private static final int SAMPLE_RATE_HZ = 48_000;
        private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO;
        private static final int CHANNEL_COUNT = 2;
        private static final int PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
        private static final int BYTES_PER_FRAME = CHANNEL_COUNT * Short.BYTES;
        private static final int TARGET_CHUNK_FRAMES = 240;
        private static final int PAYLOAD_CAPACITY_BYTES = TARGET_CHUNK_FRAMES * BYTES_PER_FRAME;
        private static final int SLOT_COUNT = 8;
        private static final int ACK_TIMEOUT_MILLIS = 2_000;
        private static final int STOP_JOIN_TIMEOUT_MILLIS = 750;
        private static final String CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT";
        private static final String RECORD_AUDIO = "android.permission.RECORD_AUDIO";

        private final long generationId;
        private final AtomicBoolean captureRunning = new AtomicBoolean(false);
        private final AtomicLong framesCaptured = new AtomicLong();
        private final AtomicLong framesWritten = new AtomicLong();
        private final AtomicLong bytesWritten = new AtomicLong();
        private final AtomicLong chunksWritten = new AtomicLong();
        private final AtomicLong chunksDropped = new AtomicLong();
        private final AtomicLong notificationsSent = new AtomicLong();
        private final AtomicLong acknowledgementsObserved = new AtomicLong();
        private final AtomicLong readErrors = new AtomicLong();

        private State state = State.NEW;
        private DirectShellShellAudioContext.Result identity =
                DirectShellShellAudioContext.Result.failure("NOT_BOUND");
        private DirectShellPcmRingProbeExternalProviderClient providerClient;
        private SharedMemory sharedMemory;
        private ByteBuffer mappedRing;
        private ParcelFileDescriptor notifyWriteFd;
        private ParcelFileDescriptor ackReadFd;
        private OutputStream notifyOutput;
        private InputStream ackInput;
        private AudioRecord audioRecord;
        private ByteBuffer discardBuffer;
        private Thread captureThread;
        private long nextSequence = 1L;
        private long nextFramePosition;
        private int regionBytes;
        private int fdBeforeBind = -1;
        private int fdAfterHandoff = -1;
        private String lastClassification = "NOT_BOUND";

        DirectShellPcmRingProducer(long generationId) {
            this.generationId = generationId;
        }

        synchronized Operation negativeStaleGeneration(byte[] secret) {
            return expectProviderRejection(
                    secret,
                    generationId - 1L,
                    DirectShellPcmRingProbeBinderProtocol.MAGIC,
                    DirectShellPcmRingProbeBinderProtocol.VERSION,
                    expectedRegionBytes(),
                    SLOT_COUNT,
                    PAYLOAD_CAPACITY_BYTES,
                    null,
                    DirectShellPcmRingProbeBinderProtocol.Result.GENERATION_REJECTED,
                    "STALE_GENERATION_REJECTED"
            );
        }

        synchronized Operation negativeInvalidMagic(byte[] secret) {
            return expectProviderRejection(
                    secret,
                    generationId,
                    0,
                    DirectShellPcmRingProbeBinderProtocol.VERSION,
                    expectedRegionBytes(),
                    SLOT_COUNT,
                    PAYLOAD_CAPACITY_BYTES,
                    null,
                    DirectShellPcmRingProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                    "INVALID_MAGIC_REJECTED"
            );
        }

        synchronized Operation negativeOversizedMetadata(byte[] secret) {
            return expectProviderRejection(
                    secret,
                    generationId,
                    DirectShellPcmRingProbeBinderProtocol.MAGIC,
                    DirectShellPcmRingProbeBinderProtocol.VERSION,
                    expectedRegionBytes() + 1,
                    SLOT_COUNT,
                    PAYLOAD_CAPACITY_BYTES,
                    null,
                    DirectShellPcmRingProbeBinderProtocol.Result.METADATA_REJECTED,
                    "OVERSIZED_METADATA_REJECTED"
            );
        }

        synchronized Operation negativeInvalidProof(byte[] secret) {
            return expectProviderRejection(
                    secret,
                    generationId,
                    DirectShellPcmRingProbeBinderProtocol.MAGIC,
                    DirectShellPcmRingProbeBinderProtocol.VERSION,
                    expectedRegionBytes(),
                    SLOT_COUNT,
                    PAYLOAD_CAPACITY_BYTES,
                    new byte[DirectShellPcmRingProbeBinderProtocol.PROOF_BYTES],
                    DirectShellPcmRingProbeBinderProtocol.Result.AUTH_REJECTED,
                    "INVALID_PROOF_REJECTED"
            );
        }

        synchronized Operation bind(byte[] secret) {
            if (state != State.NEW) return Operation.badState(state, "PCM_RING_BIND_ONCE_PER_SERVER");
            identity = DirectShellShellAudioContext.create();
            if (!identity.isAvailable()) {
                state = State.FAILED;
                lastClassification = "CONTEXT_UNAVAILABLE";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            DirectShellPcmRingProbeExternalProviderClient.OpenResult open =
                    DirectShellPcmRingProbeExternalProviderClient.open(identity.context);
            if (!open.isSuccess()) {
                state = State.FAILED;
                lastClassification = open.detail;
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            providerClient = open.client;
            fdBeforeBind = fdCount();
            DirectShellPcmRingProbeExternalProviderClient.DescriptorSetResult descriptors =
                    providerClient.requestDescriptorSet(
                            secret,
                            generationId,
                            DirectShellPcmRingProbeBinderProtocol.MAGIC,
                            DirectShellPcmRingProbeBinderProtocol.VERSION,
                            expectedRegionBytes(),
                            SLOT_COUNT,
                            PAYLOAD_CAPACITY_BYTES
                    );
            if (descriptors.result != DirectShellPcmRingProbeBinderProtocol.Result.OK) {
                closeProviderOnly();
                state = State.FAILED;
                lastClassification = "FD_HANDOFF_FAILED_" + descriptors.result.name();
                return Operation.failed(lastClassification, descriptors.detail + '\n' + diagnosticsPayload());
            }
            try {
                sharedMemory = descriptors.sharedMemory;
                notifyWriteFd = descriptors.notifyWriteFd;
                ackReadFd = descriptors.ackReadFd;
                mappedRing = sharedMemory.mapReadWrite().order(ByteOrder.nativeOrder());
                if (!SharedPcmAudioRingLayout.INSTANCE.validate(mappedRing) ||
                        SharedPcmAudioRingLayout.INSTANCE.slotCount(mappedRing) != SLOT_COUNT ||
                        SharedPcmAudioRingLayout.INSTANCE.payloadCapacity(mappedRing) !=
                                PAYLOAD_CAPACITY_BYTES) {
                    throw new IllegalStateException("production_ring_layout_mismatch");
                }
                notifyOutput = new ParcelFileDescriptor.AutoCloseOutputStream(notifyWriteFd);
                ackInput = new ParcelFileDescriptor.AutoCloseInputStream(ackReadFd);
                discardBuffer = ByteBuffer.allocateDirect(PAYLOAD_CAPACITY_BYTES)
                        .order(ByteOrder.nativeOrder());
                regionBytes = expectedRegionBytes();
                fdAfterHandoff = fdCount();
                state = State.BOUND;
                lastClassification = "PCM_RING_BOUND";
                log("PCM_RING_FD_HANDOFF_OK transport=CONTENT_PROVIDER_EXTERNAL generation=" +
                        generationId + " memory=true notify_write=true ack_read=true fd_before_bind=" +
                        fdBeforeBind + " fd_after_handoff=" + fdAfterHandoff);
                return Operation.ok(lastClassification, diagnosticsPayload());
            } catch (Throwable throwable) {
                closeRemoteResources();
                state = State.FAILED;
                lastClassification = "RING_MAP_FAILED_" + throwable.getClass().getSimpleName();
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
        }

        synchronized Operation negativeReplay(byte[] secret) {
            if (state != State.BOUND && state != State.PREPARED && state != State.RECORDING) {
                return Operation.badState(state, "PCM_RING_BIND_REQUIRED");
            }
            DirectShellPcmRingProbeExternalProviderClient.DescriptorSetResult response =
                    providerClient.requestDescriptorSet(
                            secret,
                            generationId,
                            DirectShellPcmRingProbeBinderProtocol.MAGIC,
                            DirectShellPcmRingProbeBinderProtocol.VERSION,
                            regionBytes,
                            SLOT_COUNT,
                            PAYLOAD_CAPACITY_BYTES
                    );
            try {
                if (response.result != DirectShellPcmRingProbeBinderProtocol.Result.GENERATION_REPLAY_REJECTED) {
                    lastClassification = "REPLAY_NOT_REJECTED_" + response.result.name();
                    return Operation.failed(lastClassification, response.detail + '\n' + diagnosticsPayload());
                }
                lastClassification = "REPLAY_REJECTED";
                return Operation.ok(lastClassification, response.detail + '\n' + diagnosticsPayload());
            } finally {
                response.close();
            }
        }

        synchronized Operation signalAndAwaitAck() {
            if (state != State.BOUND && state != State.PREPARED) {
                return Operation.badState(state, "PCM_RING_BIND_REQUIRED");
            }
            int slot = SharedPcmAudioRingLayout.INSTANCE.beginWrite(mappedRing);
            if (slot < 0) {
                lastClassification = "SIGNAL_RING_FULL";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            ByteBuffer payload = SharedPcmAudioRingLayout.INSTANCE.payloadSlice(mappedRing, slot);
            payload.clear();
            payload.putInt(0, 0);
            int generation = SharedPcmAudioRingLayout.INSTANCE.slotGeneration(mappedRing, slot);
            boolean published = SharedPcmAudioRingLayout.INSTANCE.publish(
                    mappedRing,
                    slot,
                    nextSequence++,
                    BYTES_PER_FRAME,
                    1,
                    nextFramePosition++,
                    System.nanoTime(),
                    AudioTimestampQuality.EstimatedFromReadCompletion.ordinal(),
                    0,
                    System.nanoTime()
            );
            if (!published) {
                SharedPcmAudioRingLayout.INSTANCE.cancelWrite(mappedRing, slot);
                lastClassification = "SIGNAL_PUBLISH_FAILED";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            log("SHELL_WRITE generation=" + generationId + " slot=" + slot +
                    " slot_generation=" + generation + " marker=ZERO_PCM_FRAME");
            boolean notified = PcmRingSignalRecord.Companion.write(
                    notifyOutput,
                    new PcmRingSignalRecord(slot, generation),
                    new byte[PcmRingSignalRecord.WIRE_SIZE]
            );
            if (!notified) {
                lastClassification = "NOTIFY_WRITE_FAILED";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            notificationsSent.incrementAndGet();
            log("NOTIFY generation=" + generationId + " slot=" + slot + " slot_generation=" + generation);
            PcmRingSignalRecord acknowledgement = awaitAck();
            if (acknowledgement == null || acknowledgement.getSlotIndex() != slot ||
                    acknowledgement.getGeneration() != generation) {
                lastClassification = "ACK_NOT_OBSERVED";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            acknowledgementsObserved.incrementAndGet();
            log("SHELL_ACK_OBSERVED generation=" + generationId + " slot=" + slot +
                    " slot_generation=" + generation);
            lastClassification = "SIGNAL_ACK_OK";
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        @SuppressLint("WrongConstant")
        synchronized Operation prepareAudioRecord() {
            if (state != State.BOUND) return Operation.badState(state, "PCM_RING_BOUND_REQUIRED");
            Context context = identity.context;
            if (context == null) {
                lastClassification = "CONTEXT_UNAVAILABLE";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            if (context.checkSelfPermission(CAPTURE_AUDIO_OUTPUT) != PackageManager.PERMISSION_GRANTED) {
                lastClassification = "PERMISSION_DENIED_CAPTURE_AUDIO_OUTPUT";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            if (context.checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lastClassification = "PERMISSION_DENIED_RECORD_AUDIO";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            int minimumBufferBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    CHANNEL_MASK,
                    PCM_ENCODING
            );
            if (minimumBufferBytes <= 0) {
                lastClassification = "AUDIORECORD_CONSTRUCTION_FAILED_MIN_BUFFER_" + minimumBufferBytes;
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            int bufferBytes = Math.max(minimumBufferBytes * 2,
                    SAMPLE_RATE_HZ * CHANNEL_COUNT * Short.BYTES / 50);
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(PCM_ENCODING)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(CHANNEL_MASK)
                        .build();
                audioRecord = new AudioRecord.Builder()
                        .setContext(context)
                        .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferBytes)
                        .build();
            } catch (SecurityException exception) {
                lastClassification = "PERMISSION_DENIED_" + exception.getClass().getSimpleName();
                return Operation.failed(lastClassification, diagnosticsPayload());
            } catch (RuntimeException exception) {
                lastClassification = "AUDIORECORD_CONSTRUCTION_FAILED_" +
                        exception.getClass().getSimpleName();
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseAudioRecord();
                lastClassification = "AUDIORECORD_UNINITIALIZED";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            if (audioRecord.getSampleRate() != SAMPLE_RATE_HZ ||
                    audioRecord.getFormat().getChannelCount() != CHANNEL_COUNT ||
                    audioRecord.getFormat().getEncoding() != PCM_ENCODING) {
                releaseAudioRecord();
                lastClassification = "AUDIORECORD_FORMAT_MISMATCH";
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            state = State.PREPARED;
            lastClassification = "AUDIO_PREPARED";
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation startAudioRecord() {
            if (state != State.PREPARED || audioRecord == null) {
                return Operation.badState(state, "AUDIO_PREPARE_REQUIRED");
            }
            try {
                audioRecord.startRecording();
            } catch (SecurityException exception) {
                lastClassification = "PERMISSION_DENIED_START_RECORDING";
                state = State.FAILED;
                return Operation.failed(lastClassification, diagnosticsPayload());
            } catch (IllegalStateException exception) {
                lastClassification = "START_RECORDING_FAILED_ILLEGAL_STATE";
                state = State.FAILED;
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                lastClassification = "START_RECORDING_FAILED_STATE_" +
                        audioRecord.getRecordingState();
                state = State.FAILED;
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            captureRunning.set(true);
            state = State.RECORDING;
            captureThread = new Thread(this::captureLoop, "WarpnectDirectShellPcmRingCapture");
            captureThread.start();
            lastClassification = "PCM_RING_AUDIO_RECORDING";
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation stopAudioRecord() {
            if (state == State.BOUND) return Operation.ok("AUDIO_ALREADY_STOPPED", diagnosticsPayload());
            if (state != State.PREPARED && state != State.RECORDING && state != State.FAILED) {
                return Operation.badState(state, "PCM_RING_BOUND_REQUIRED");
            }
            stopCaptureWorker();
            releaseAudioRecord();
            if (state != State.FAILED) state = State.BOUND;
            lastClassification = state == State.FAILED ? lastClassification : "PCM_RING_AUDIO_STOPPED";
            return state == State.FAILED
                    ? Operation.failed(lastClassification, diagnosticsPayload())
                    : Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation closeRemoteDescriptors() {
            if (state == State.CLOSED) return Operation.ok("REMOTE_ALREADY_CLOSED", diagnosticsPayload());
            stopCaptureWorker();
            releaseAudioRecord();
            closeRemoteResources();
            state = State.CLOSED;
            lastClassification = "REMOTE_CLOSED";
            log("REMOTE_CLOSED fd_after_remote_close=" + fdCount());
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized Operation diagnostics() {
            return Operation.ok(lastClassification, diagnosticsPayload());
        }

        synchronized String diagnosticsPayload() {
            int occupancy = mappedRing == null ? -1 : SharedPcmAudioRingLayout.INSTANCE.occupancy(mappedRing);
            int highWater = mappedRing == null ? -1 : SharedPcmAudioRingLayout.INSTANCE.highWater(mappedRing);
            int overruns = mappedRing == null ? -1 : SharedPcmAudioRingLayout.INSTANCE.overruns(mappedRing);
            StringBuilder output = new StringBuilder();
            output.append("state=").append(state.name()).append('\n');
            output.append("classification=").append(lastClassification).append('\n');
            output.append(identity.toPayload()).append('\n');
            output.append("generation=").append(generationId).append('\n');
            output.append("ring_region_bytes=").append(regionBytes).append('\n');
            output.append("ring_slot_count=").append(SLOT_COUNT).append('\n');
            output.append("ring_payload_capacity_bytes=").append(PAYLOAD_CAPACITY_BYTES).append('\n');
            output.append("shell_frames_captured=").append(framesCaptured.get()).append('\n');
            output.append("shell_frames_written=").append(framesWritten.get()).append('\n');
            output.append("shell_bytes_written=").append(bytesWritten.get()).append('\n');
            output.append("shell_chunks_written=").append(chunksWritten.get()).append('\n');
            output.append("shell_chunks_dropped=").append(chunksDropped.get()).append('\n');
            output.append("shell_notifications_sent=").append(notificationsSent.get()).append('\n');
            output.append("shell_acknowledgements_observed=").append(acknowledgementsObserved.get()).append('\n');
            output.append("shell_read_errors=").append(readErrors.get()).append('\n');
            output.append("ring_occupancy=").append(occupancy).append('\n');
            output.append("ring_high_water=").append(highWater).append('\n');
            output.append("ring_overruns=").append(overruns).append('\n');
            output.append("shell_fd_before_bind=").append(fdBeforeBind).append('\n');
            output.append("shell_fd_after_handoff=").append(fdAfterHandoff).append('\n');
            output.append("shell_fd_now=").append(fdCount());
            if (audioRecord != null) {
                output.append('\n').append("audio_record_state=").append(audioRecord.getState());
                output.append('\n').append("audio_recording_state=")
                        .append(audioRecord.getRecordingState());
                output.append('\n').append("audio_sample_rate_hz=").append(audioRecord.getSampleRate());
                output.append('\n').append("audio_channel_count=")
                        .append(audioRecord.getFormat().getChannelCount());
                output.append('\n').append("audio_buffer_frames=")
                        .append(audioRecord.getBufferSizeInFrames());
            }
            return output.toString();
        }

        synchronized void closeForShutdown() {
            stopCaptureWorker();
            releaseAudioRecord();
            closeRemoteResources();
            state = State.CLOSED;
        }

        private void captureLoop() {
            ByteBuffer localRing = mappedRing;
            AudioRecord localRecord = audioRecord;
            OutputStream localNotify = notifyOutput;
            if (localRing == null || localRecord == null || localNotify == null) {
                failCapture("CAPTURE_RESOURCES_UNAVAILABLE");
                return;
            }
            byte[] notifyScratch = new byte[PcmRingSignalRecord.WIRE_SIZE];
            byte[] ackScratch = new byte[PcmRingSignalRecord.WIRE_SIZE];
            while (captureRunning.get()) {
                drainAcks(ackScratch);
                int slot = SharedPcmAudioRingLayout.INSTANCE.beginWrite(localRing);
                ByteBuffer target = slot >= 0
                        ? SharedPcmAudioRingLayout.INSTANCE.payloadSlice(localRing, slot)
                        : discardBuffer;
                target.clear();
                int read;
                try {
                    read = localRecord.read(target, PAYLOAD_CAPACITY_BYTES, AudioRecord.READ_BLOCKING);
                } catch (RuntimeException exception) {
                    readErrors.incrementAndGet();
                    failCapture("READ_FAILED_" + exception.getClass().getSimpleName());
                    return;
                }
                long completionNs = System.nanoTime();
                if (read < 0) {
                    readErrors.incrementAndGet();
                    failCapture("READ_FAILED_" + read);
                    return;
                }
                int alignedBytes = read - (read % BYTES_PER_FRAME);
                int frameCount = alignedBytes / BYTES_PER_FRAME;
                framesCaptured.addAndGet(frameCount);
                if (slot < 0) {
                    chunksDropped.incrementAndGet();
                    continue;
                }
                if (alignedBytes <= 0) {
                    SharedPcmAudioRingLayout.INSTANCE.cancelWrite(localRing, slot);
                    continue;
                }
                int slotGeneration = SharedPcmAudioRingLayout.INSTANCE.slotGeneration(localRing, slot);
                boolean published = SharedPcmAudioRingLayout.INSTANCE.publish(
                        localRing,
                        slot,
                        nextSequence++,
                        alignedBytes,
                        frameCount,
                        nextFramePosition,
                        completionNs,
                        AudioTimestampQuality.EstimatedFromReadCompletion.ordinal(),
                        0,
                        completionNs
                );
                nextFramePosition += frameCount;
                if (!published) {
                    SharedPcmAudioRingLayout.INSTANCE.cancelWrite(localRing, slot);
                    chunksDropped.incrementAndGet();
                    continue;
                }
                boolean notified = PcmRingSignalRecord.Companion.write(
                        localNotify,
                        new PcmRingSignalRecord(slot, slotGeneration),
                        notifyScratch
                );
                if (!notified) {
                    failCapture("NOTIFICATION_CHANNEL_FAILED");
                    return;
                }
                framesWritten.addAndGet(frameCount);
                bytesWritten.addAndGet(alignedBytes);
                chunksWritten.incrementAndGet();
                notificationsSent.incrementAndGet();
            }
        }

        private void drainAcks(byte[] scratch) {
            InputStream input = ackInput;
            if (input == null) return;
            while (true) {
                int available;
                try {
                    available = input.available();
                } catch (IOException exception) {
                    return;
                }
                if (available < PcmRingSignalRecord.WIRE_SIZE) return;
                PcmRingSignalRecord record = PcmRingSignalRecord.Companion.read(input, scratch);
                if (record == null) return;
                acknowledgementsObserved.incrementAndGet();
            }
        }

        private PcmRingSignalRecord awaitAck() {
            InputStream input = ackInput;
            if (input == null) return null;
            FutureTask<PcmRingSignalRecord> task = new FutureTask<>(
                    () -> PcmRingSignalRecord.Companion.read(
                            input,
                            new byte[PcmRingSignalRecord.WIRE_SIZE]
                    )
            );
            Thread waiter = new Thread(task, "WarpnectDirectShellRingAckWait");
            waiter.setDaemon(true);
            waiter.start();
            try {
                return task.get(ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            } catch (TimeoutException exception) {
                return null;
            } catch (Exception exception) {
                return null;
            }
        }

        private Operation expectProviderRejection(
                byte[] secret,
                long requestedGeneration,
                int magic,
                int version,
                int requestedRegionBytes,
                int requestedSlotCount,
                int requestedPayloadCapacityBytes,
                byte[] explicitProof,
                DirectShellPcmRingProbeBinderProtocol.Result expected,
                String successClassification
        ) {
            DirectShellShellAudioContext.Result localIdentity = DirectShellShellAudioContext.create();
            if (!localIdentity.isAvailable()) {
                lastClassification = "CONTEXT_UNAVAILABLE";
                return Operation.failed(lastClassification, localIdentity.toPayload());
            }
            DirectShellPcmRingProbeExternalProviderClient.OpenResult open =
                    DirectShellPcmRingProbeExternalProviderClient.open(localIdentity.context);
            if (!open.isSuccess()) {
                lastClassification = open.detail;
                return Operation.failed(lastClassification, diagnosticsPayload());
            }
            try {
                DirectShellPcmRingProbeExternalProviderClient.DescriptorSetResult response;
                if (explicitProof == null) {
                    response = open.client.requestDescriptorSet(
                            secret,
                            requestedGeneration,
                            magic,
                            version,
                            requestedRegionBytes,
                            requestedSlotCount,
                            requestedPayloadCapacityBytes
                    );
                } else {
                    response = open.client.requestDescriptorSetWithProof(
                            requestedGeneration,
                            magic,
                            version,
                            requestedRegionBytes,
                            requestedSlotCount,
                            requestedPayloadCapacityBytes,
                            explicitProof
                    );
                }
                try {
                    if (response.result != expected) {
                        lastClassification = "NEGATIVE_NOT_REJECTED_" + response.result.name();
                        return Operation.failed(lastClassification, response.detail + '\n' + diagnosticsPayload());
                    }
                    lastClassification = successClassification;
                    return Operation.ok(lastClassification, response.detail + '\n' + diagnosticsPayload());
                } finally {
                    response.close();
                }
            } finally {
                open.client.release();
                if (explicitProof != null) Arrays.fill(explicitProof, (byte) 0);
            }
        }

        private void failCapture(String classification) {
            captureRunning.set(false);
            synchronized (this) {
                state = State.FAILED;
                lastClassification = classification;
            }
            log("PCM_RING_CAPTURE_FAILURE classification=" + classification);
        }

        private void stopCaptureWorker() {
            captureRunning.set(false);
            AudioRecord localRecord = audioRecord;
            if (localRecord != null) {
                try {
                    if (localRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        localRecord.stop();
                    }
                } catch (IllegalStateException ignored) {
                    // Release below is deterministic even when stop sees an already failed record.
                }
            }
            Thread thread = captureThread;
            captureThread = null;
            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(STOP_JOIN_TIMEOUT_MILLIS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void releaseAudioRecord() {
            if (audioRecord == null) return;
            try {
                audioRecord.release();
            } catch (RuntimeException ignored) {
                // Debug spike cleanup must not hide the first actual capture boundary.
            }
            audioRecord = null;
        }

        private void closeRemoteResources() {
            closeQuietly(notifyOutput);
            closeQuietly(ackInput);
            closeQuietly(notifyWriteFd);
            closeQuietly(ackReadFd);
            notifyOutput = null;
            ackInput = null;
            notifyWriteFd = null;
            ackReadFd = null;
            if (mappedRing != null) {
                try {
                    SharedMemory.unmap(mappedRing);
                } catch (RuntimeException ignored) {
                    // Debug spike cleanup must not obscure the first boundary.
                }
            }
            mappedRing = null;
            if (sharedMemory != null) {
                try {
                    sharedMemory.close();
                } catch (RuntimeException ignored) {
                    // Debug spike cleanup must not obscure the first boundary.
                }
            }
            sharedMemory = null;
            discardBuffer = null;
            closeProviderOnly();
        }

        private void closeProviderOnly() {
            if (providerClient == null) return;
            String result = providerClient.release();
            providerClient = null;
            log("PCM_RING_PROVIDER_RELEASE result=" + result);
        }

        private int expectedRegionBytes() {
            return SharedPcmAudioRingLayout.INSTANCE.totalBytes(SLOT_COUNT, PAYLOAD_CAPACITY_BYTES);
        }

        private enum State {
            NEW,
            BOUND,
            PREPARED,
            RECORDING,
            FAILED,
            CLOSED,
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

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int currentGid() {
        try {
            return Os.getgid();
        } catch (RuntimeException exception) {
            return -1;
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

    private static void closeQuietly(OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup should not obscure the first failed boundary.
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup should not obscure the first failed boundary.
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Cleanup should not obscure the first failed boundary.
        }
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
