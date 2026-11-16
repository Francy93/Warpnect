package io.github.muntashirakon.adb;

import java.util.Arrays;

/** Test-only package access to the pinned library's actual SPAKE2/AEAD; no crypto reimplementation. */
public final class PairingAuthFixture implements AutoCloseable {
    private final PairingAuthCtx context;

    public PairingAuthFixture(boolean alice, char[] code, byte[] exporter) {
        byte[] password = new byte[code.length + exporter.length];
        for (int i = 0; i < code.length; i++) password[i] = (byte) code[i];
        System.arraycopy(exporter, 0, password, code.length, exporter.length);
        try {
            context = alice ? PairingAuthCtx.createAlice(password) : PairingAuthCtx.createBob(password);
            if (context == null) throw new IllegalStateException("fixture_pake_unavailable");
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    public byte[] message() { return context.getMsg().clone(); }
    public boolean initialize(byte[] message) { return context.initCipher(message); }
    public byte[] encrypt(byte[] plaintext) { return context.encrypt(plaintext); }
    public byte[] decrypt(byte[] ciphertext) { return context.decrypt(ciphertext); }
    @Override public void close() { context.destroy(); }
    @Override public String toString() { return "PairingAuthFixture(redacted)"; }
}
