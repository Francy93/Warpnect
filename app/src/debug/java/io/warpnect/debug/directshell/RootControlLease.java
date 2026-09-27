package io.warpnect.debug.directshell;

/** Pure, generation-scoped policy on top of the existing authenticated envelope. */
final class RootControlLease {
    private final long generation;
    private final long expiresAt;
    private final String challenge;
    private final DirectShellProbeProtocol.RequestOrder order = new DirectShellProbeProtocol.RequestOrder();
    private boolean authenticated;
    private boolean closed;

    RootControlLease(long generation, long expiresAt, String challenge) {
        if (generation <= 0 || expiresAt <= 0 || !challenge.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid_root_lease");
        }
        this.generation = generation;
        this.expiresAt = expiresAt;
        this.challenge = challenge;
    }

    synchronized boolean hello(long requestId, String payload, long now) {
        if (closed || authenticated || now >= expiresAt || requestId != 1 ||
                !payload.equals(generation + "|" + challenge)) return false;
        authenticated = order.accept(requestId);
        return authenticated;
    }

    synchronized boolean accept(long requestId, String payload, long now) {
        return authenticated && !closed && now < expiresAt &&
                payload.equals(Long.toString(generation)) && order.accept(requestId);
    }

    synchronized void close() {
        closed = true;
    }

    synchronized boolean acceptInput(long requestId, byte[] payload, long now) {
        if (!authenticated || closed || now >= expiresAt || payload.length < 16 || payload.length > 4096) return false;
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(payload);
        return header.getInt() == 0x574e4953 && header.getInt() == 1 &&
                header.getLong() == generation && order.accept(requestId);
    }

    static boolean inputCommand(DirectShellProbeProtocol.Command command) {
        switch (command) {
            case INPUT_SESSION_CAPABILITIES:
            case INPUT_SESSION_PREPARE:
            case INPUT_SESSION_START:
            case INPUT_SESSION_KEY:
            case INPUT_SESSION_TOUCH:
            case INPUT_SESSION_POINTER:
            case INPUT_SESSION_JOYSTICK:
            case INPUT_SESSION_RESET:
            case INPUT_SESSION_STOP:
            case INPUT_SESSION_SNAPSHOT:
                return true;
            default:
                return false;
        }
    }

    static boolean supported(DirectShellProbeProtocol.Command command) {
        return inputCommand(command) || command == DirectShellProbeProtocol.Command.PING ||
                command == DirectShellProbeProtocol.Command.GET_RUNTIME_INFO ||
                command == DirectShellProbeProtocol.Command.ROOT_CAPABILITIES ||
                command == DirectShellProbeProtocol.Command.SHUTDOWN;
    }
}
