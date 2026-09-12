package io.warpnect.debug.directshell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileReader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEBUG-only, app_process-compatible privileged-server bootstrap probe.
 *
 * <p>The loopback TCP listener is intentionally limited to authenticated control messages. It
 * is not a media transport or an FD-passing design; it exists because the initial cross-UID
 * Android Unix-domain-socket experiment was denied by SELinux on the hardware target.</p>
 */
public final class WarpnectPrivilegedServer {
    private static final String TAG = "WarpnectDirectShell";
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 20_000L;
    private static final long MIN_IDLE_TIMEOUT_MILLIS = 3_000L;
    private static final long MAX_IDLE_TIMEOUT_MILLIS = 60_000L;
    private static final int CONTROL_ACCEPT_TIMEOUT_MILLIS = 250;

    private WarpnectPrivilegedServer() {}

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
        private volatile FileDescriptor listenerDescriptor;
        private Thread idleWatchdog;

        ProbeServer(ServerArguments arguments) {
            this.arguments = arguments;
        }

        void run() throws IOException {
            RuntimeInfo runtimeInfo = RuntimeInfo.collect(arguments.declaredPackage);
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
                    "START result=READY timestamp_ms=" + System.currentTimeMillis() +
                            " pid=" + runtimeInfo.values.get("pid") +
                            " uid=" + runtimeInfo.values.get("uid") +
                            " uid_kind=" + runtimeInfo.values.get("uid_kind") +
                            " api=" + runtimeInfo.values.get("api") +
                            " endpoint=" + endpoint() +
                            " transport=LOOPBACK_TCP" +
                            " context_strategy=" + runtimeInfo.values.get("context_strategy") +
                            " class_loader=" + runtimeInfo.values.get("class_loader")
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
                            log(
                                    "CONTROL_ACCEPT_FAILURE type=" +
                                            exception.getClass().getSimpleName()
                            );
                        }
                    }
                }
            } finally {
                releaseEndpoint();
                joinWatchdog();
            }
        }

        private void handleControl(DirectShellProbeIpv4Socket client) {
            DirectShellProbeProtocol.RequestOrder order = new DirectShellProbeProtocol.RequestOrder();
            try (
                    DirectShellProbeIpv4Socket ignored = client
            ) {
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
                        DirectShellProbeProtocol.writeResponse(
                                output,
                                request.requestId,
                                DirectShellProbeProtocol.Status.OUT_OF_ORDER,
                                bytes("request_id_not_strictly_increasing"),
                                arguments.secret
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
                    log("CLIENT_DISCONNECTED result=IO_FAILURE type=" + exception.getClass().getSimpleName());
                }
            }
        }

        private boolean dispatch(DirectShellProbeProtocol.Request request, DataOutputStream output)
                throws IOException {
            switch (request.command) {
                case PING:
                    DirectShellProbeProtocol.writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.OK,
                            bytes("PONG"),
                            arguments.secret
                    );
                    log("REQUEST id=" + request.requestId + " command=PING result=OK");
                    return false;
                case GET_RUNTIME_INFO:
                    DirectShellProbeProtocol.writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.OK,
                            RuntimeInfo.collect(arguments.declaredPackage).toPayload(),
                            arguments.secret
                    );
                    log("REQUEST id=" + request.requestId + " command=GET_RUNTIME_INFO result=OK");
                    return false;
                case SHUTDOWN:
                    DirectShellProbeProtocol.writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.OK,
                            bytes("SHUTDOWN_ACCEPTED"),
                            arguments.secret
                    );
                    log("REQUEST id=" + request.requestId + " command=SHUTDOWN result=OK");
                    return true;
                default:
                    DirectShellProbeProtocol.writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.UNSUPPORTED,
                            bytes("unsupported_command"),
                            arguments.secret
                    );
                    log("REQUEST id=" + request.requestId + " command=UNKNOWN result=UNSUPPORTED");
                    return false;
            }
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
                            long idleMillis = SystemClock.elapsedRealtime() -
                                    lastAuthenticatedActivityMillis.get();
                            if (idleMillis >= arguments.idleTimeoutMillis) {
                                requestShutdown("idle_timeout");
                                return;
                            }
                        }
                    },
                    "WarpnectDirectShellIdleWatchdog"
            );
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
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private String endpoint() {
            return "127.0.0.1:" + arguments.port;
        }

    }

    private static final class ServerArguments {
        final int port;
        final byte[] secret;
        final String declaredPackage;
        final long idleTimeoutMillis;

        private ServerArguments(int port, byte[] secret, String declaredPackage, long idleTimeoutMillis) {
            this.port = port;
            this.secret = secret;
            this.declaredPackage = declaredPackage;
            this.idleTimeoutMillis = idleTimeoutMillis;
        }

        static ServerArguments parse(String[] args) {
            int port = -1;
            String encodedSecret = null;
            String declaredPackage = "io.warpnect";
            long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--port".equals(argument)) {
                    port = Integer.parseInt(requireValue(args, ++index, argument));
                } else if ("--secret-base64".equals(argument)) {
                    encodedSecret = requireValue(args, ++index, argument);
                } else if ("--declared-package".equals(argument)) {
                    declaredPackage = requireValue(args, ++index, argument);
                } else if ("--idle-timeout-ms".equals(argument)) {
                    idleTimeoutMillis = Long.parseLong(requireValue(args, ++index, argument));
                } else {
                    throw new IllegalArgumentException("unknown_argument");
                }
            }
            if (port < 1024 || port > 65535) {
                throw new IllegalArgumentException("invalid_port");
            }
            if (declaredPackage == null || !declaredPackage.matches("[A-Za-z][A-Za-z0-9_.]{2,127}")) {
                throw new IllegalArgumentException("invalid_declared_package");
            }
            if (idleTimeoutMillis < MIN_IDLE_TIMEOUT_MILLIS || idleTimeoutMillis > MAX_IDLE_TIMEOUT_MILLIS) {
                throw new IllegalArgumentException("invalid_idle_timeout");
            }
            try {
                byte[] secret = Base64.getUrlDecoder().decode(encodedSecret == null ? "" : encodedSecret);
                return new ServerArguments(
                        port,
                        DirectShellProbeProtocol.requireSecret(secret),
                        declaredPackage,
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

    private static final class RuntimeInfo {
        final Map<String, String> values;

        private RuntimeInfo(Map<String, String> values) {
            this.values = values;
        }

        static RuntimeInfo collect(String declaredPackage) {
            Map<String, String> values = new LinkedHashMap<>();
            int uid = Process.myUid();
            values.put("timestamp_ms", String.valueOf(System.currentTimeMillis()));
            values.put("pid", String.valueOf(Process.myPid()));
            values.put("uid", String.valueOf(uid));
            values.put("gid", currentGid());
            values.put("uid_kind", uidKind(uid));
            values.put("api", String.valueOf(Build.VERSION.SDK_INT));
            values.put("process_name", processName());
            values.put("declared_package", declaredPackage);
            values.put("selinux_context", readSelinuxContext());
            values.put("java_class_path", truncate(System.getProperty("java.class.path", "")));
            ClassLoader classLoader = WarpnectPrivilegedServer.class.getClassLoader();
            values.put("class_loader", classLoader == null ? "bootstrap" : classLoader.getClass().getName());
            values.put("class_loader_parent", classLoader == null || classLoader.getParent() == null
                    ? "none"
                    : classLoader.getParent().getClass().getName());
            collectContext(values, declaredPackage);
            return new RuntimeInfo(values);
        }

        byte[] toPayload() {
            StringBuilder output = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                output.append(entry.getKey()).append('=').append(safe(entry.getValue())).append('\n');
            }
            return bytes(output.toString());
        }

        private static void collectContext(Map<String, String> values, String declaredPackage) {
            values.put("activity_thread_system_main", "NOT_INVOKED");
            Context context = null;
            String strategy = "UNAVAILABLE";
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
                Object application = currentApplication.invoke(null);
                if (application instanceof Context) {
                    context = (Context) application;
                    strategy = "CURRENT_APPLICATION";
                }
            } catch (Throwable throwable) {
                values.put("current_application_probe", throwable.getClass().getSimpleName());
            }
            if (context == null) {
                try {
                    Class<?> activityThread = Class.forName("android.app.ActivityThread");
                    Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
                    Object thread = currentActivityThread.invoke(null);
                    if (thread != null) {
                        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
                        Object systemContext = getSystemContext.invoke(thread);
                        if (systemContext instanceof Context) {
                            context = (Context) systemContext;
                            strategy = "CURRENT_ACTIVITY_THREAD_SYSTEM_CONTEXT";
                        }
                    }
                } catch (Throwable throwable) {
                    values.put("system_context_probe", throwable.getClass().getSimpleName());
                }
            }
            values.put("context_strategy", strategy);
            if (context == null) {
                values.put("package_name", "UNAVAILABLE");
                values.put("op_package_name", "UNAVAILABLE");
                values.put("attribution", "UNAVAILABLE");
                values.put("package_manager", "UNAVAILABLE");
                values.put("package_context", "UNAVAILABLE");
                return;
            }
            values.put("package_name", safe(context.getPackageName()));
            values.put("op_package_name", opPackageName(context));
            values.put("package_manager", context.getPackageManager() == null ? "UNAVAILABLE" : "AVAILABLE");
            collectAttribution(values, context);
            try {
                Context packageContext = context.createPackageContext(declaredPackage, Context.CONTEXT_IGNORE_SECURITY);
                ApplicationInfo applicationInfo = packageContext.getApplicationInfo();
                values.put("package_context", "AVAILABLE");
                values.put("package_context_name", safe(packageContext.getPackageName()));
                values.put("package_context_op_package", opPackageName(packageContext));
                values.put("package_source_dir", truncate(applicationInfo.sourceDir));
            } catch (Throwable throwable) {
                values.put("package_context", throwable.getClass().getSimpleName());
            }
        }

        private static void collectAttribution(Map<String, String> values, Context context) {
            if (Build.VERSION.SDK_INT < 31) {
                values.put("attribution", "API_LT_31");
                return;
            }
            try {
                Method method = Context.class.getMethod("getAttributionSource");
                Object attribution = method.invoke(context);
                values.put("attribution", attribution == null ? "NULL" : safe(attribution.toString()));
            } catch (Throwable throwable) {
                values.put("attribution", throwable.getClass().getSimpleName());
            }
        }

        private static String opPackageName(Context context) {
            if (Build.VERSION.SDK_INT < 29) return "API_LT_29";
            return safe(context.getOpPackageName());
        }

        private static String currentGid() {
            try {
                return String.valueOf(Os.getgid());
            } catch (Throwable throwable) {
                return "UNAVAILABLE_" + throwable.getClass().getSimpleName();
            }
        }

        private static String processName() {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    Class<?> application = Class.forName("android.app.Application");
                    Method getProcessName = application.getMethod("getProcessName");
                    Object result = getProcessName.invoke(null);
                    if (result instanceof String && !((String) result).isEmpty()) return safe((String) result);
                } catch (Throwable ignored) {
                    // /proc remains a deterministic fallback for a bare app_process runtime.
                }
            }
            return readProcCmdline();
        }

        private static String readProcCmdline() {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
                String value = reader.readLine();
                return value == null || value.isEmpty() ? "UNAVAILABLE" : safe(value.replace('\u0000', ' '));
            } catch (IOException exception) {
                return "UNAVAILABLE_" + exception.getClass().getSimpleName();
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
    }

    private static String uidKind(int uid) {
        if (uid == Process.SHELL_UID) return "SHELL";
        if (uid == Process.ROOT_UID) return "ROOT";
        return "OTHER";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        if (value == null) return "none";
        return truncate(value.replace('\n', '_').replace('\r', '_').replace('\u0000', '_'));
    }

    private static String truncate(String value) {
        if (value == null) return "none";
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    private static void log(String message) {
        Log.i(TAG, message);
        // app_process stdout is intentionally mirrored into the bounded hardware-run artifact.
        System.out.println(TAG + " " + message);
        System.out.flush();
    }
}
