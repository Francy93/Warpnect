package io.github.muntashirakon.adb;

import java.util.Arrays;
import androidx.annotation.RestrictTo;

/** DEBUG synthetic peer bridge, colocated with the target library for package access.
 * No socket, credential store, enrollment authority or normal application registration.
 */
@RestrictTo(RestrictTo.Scope.TESTS)
public final class DebugSyntheticPairingPeerAuth implements AutoCloseable {
    private final PairingAuthCtx context;
    private boolean closed;

    public DebugSyntheticPairingPeerAuth(char[] code, byte[] exporter) {
        if (code.length != 6 || exporter.length != 64) {
            throw new IllegalArgumentException("fixture_pake_input_invalid");
        }
        byte[] password = new byte[code.length + exporter.length];
        for (int i = 0; i < code.length; i++) password[i] = (byte) code[i];
        System.arraycopy(exporter, 0, password, code.length, exporter.length);
        try {
            context = PairingAuthCtx.createBob(password);
            if (context == null) throw new IllegalStateException("fixture_pake_unavailable");
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    public byte[] message() { requireOpen(); return context.getMsg().clone(); }
    public boolean initialize(byte[] message) { requireOpen(); return context.initCipher(message); }
    public byte[] encrypt(byte[] plaintext) { requireOpen(); return context.encrypt(plaintext); }
    public byte[] decrypt(byte[] ciphertext) { requireOpen(); return context.decrypt(ciphertext); }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("fixture_pake_closed");
    }
    @Override public void close() {
        if (!closed) { closed = true; context.destroy(); }
    }
    @Override public String toString() { return "DebugSyntheticPairingPeerAuth(redacted)"; }
}
