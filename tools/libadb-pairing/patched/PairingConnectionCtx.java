// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Objects;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.CertificateEntry;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

// https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_connection/pairing_connection.cpp
// Also based on Shizuku's implementation
@RequiresApi(Build.VERSION_CODES.GINGERBREAD)
public final class PairingConnectionCtx implements Closeable {
    public static final String TAG = PairingConnectionCtx.class.getSimpleName();

    public static final String EXPORTED_KEY_LABEL = "adb-label\u0000";
    public static final int EXPORT_KEY_SIZE = 64;

    private enum State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    enum Role {
        Client,
        Server,
    }

    private final String mHost;
    private final int mPort;
    private final byte[] mPswd;
    private final PeerInfo mPeerInfo;
    private final KeyPair mKeyPair;
    private final Role mRole = Role.Client;

    private DataInputStream mInputStream;
    private DataOutputStream mOutputStream;
    private PairingAuthCtx mPairingAuthCtx;
    private State mState = State.Ready;
    // Warpnect opt-in path only: the worker borrows a connected socket, never owns cancellation.
    private Socket mBorrowedSocket;
    private InetSocketAddress mEndpoint;
    private Runnable mCheckpoint;
    private TlsClientProtocol mTlsProtocol;
    private AdbTlsClient mTlsClient;
    private AuthenticatedPeerInfo mAuthenticatedPeerInfo;

    public PairingConnectionCtx(@NonNull String host, int port, @NonNull byte[] pswd, @NonNull KeyPair keyPair,
                                @NonNull String deviceName)
            throws NoSuchAlgorithmException, KeyManagementException, InvalidKeyException {
        this.mHost = Objects.requireNonNull(host);
        this.mPort = port;
        this.mPswd = Objects.requireNonNull(pswd);
        this.mPeerInfo = new PeerInfo(PeerInfo.ADB_RSA_PUB_KEY, AndroidPubkey.encodeWithName((RSAPublicKey)
                keyPair.getPublicKey(), Objects.requireNonNull(deviceName)));
        this.mKeyPair = keyPair;
    }

    public PairingConnectionCtx(@NonNull String host, int port, @NonNull byte[] pswd, @NonNull PrivateKey privateKey,
                                @NonNull Certificate certificate, @NonNull String deviceName)
            throws NoSuchAlgorithmException, KeyManagementException, InvalidKeyException {
        this(host, port, pswd, new KeyPair(Objects.requireNonNull(privateKey), Objects.requireNonNull(certificate)),
                deviceName);
    }

    public void start() throws IOException {
        if (mState != State.Ready) {
            throw new IOException("Connection is not ready yet.");
        }

        mState = State.ExchangingMsgs;

        // Start worker
        setupTlsConnection();

        for (; ; ) {
            switch (mState) {
                case ExchangingMsgs:
                    if (!doExchangeMsgs()) {
                        notifyResult();
                        throw new IOException("Exchanging message wasn't successful.");
                    }
                    mState = State.ExchangingPeerInfo;
                    break;
                case ExchangingPeerInfo:
                    if (!doExchangePeerInfo()) {
                        notifyResult();
                        throw new IOException("Could not exchange peer info.");
                    }
                    notifyResult();
                    return;
                case Ready:
                case Stopped:
                    throw new IOException("Connection closed with errors.");
            }
        }
    }

    /** Additive DEBUG extension. Same caller/worker; no connect, retry, timeout or socket replacement. */
    public AuthenticatedPeerInfo startConnected(Socket socket, InetSocketAddress endpoint, Runnable checkpoint)
            throws IOException {
        if (mState != State.Ready || mBorrowedSocket != null || socket == null || endpoint == null
                || endpoint.isUnresolved() || !socket.isConnected() || socket.isClosed()
                || socket.isInputShutdown() || socket.isOutputShutdown()
                || !endpoint.equals(socket.getRemoteSocketAddress()) || checkpoint == null) {
            throw new IOException("pairing_connected_socket_invalid");
        }
        mBorrowedSocket = socket;
        mEndpoint = endpoint;
        mCheckpoint = checkpoint;
        try {
            checkpoint();
            start();
            if (mAuthenticatedPeerInfo == null) throw new IOException("pairing_peer_unconfirmed");
            return mAuthenticatedPeerInfo;
        } catch (Exception ignored) {
            // No raw library/peer exception can cross the opt-in boundary.
            throw new IOException("pairing_protocol_failed");
        }
    }

    private void checkpoint() {
        if (mCheckpoint != null) mCheckpoint.run();
    }

    /** Opaque protocol evidence, not an Android trust/storage/Host grant. Arrays are defensive copies. */
    public static final class AuthenticatedPeerInfo {
        private final byte[] guid;
        private final byte[] remoteCertificate;
        private final byte[] localCertificate;
        private final InetSocketAddress endpoint;

        private AuthenticatedPeerInfo(byte[] guid, byte[] remote, byte[] local, InetSocketAddress endpoint) {
            this.guid = guid.clone();
            this.remoteCertificate = remote.clone();
            this.localCertificate = local.clone();
            this.endpoint = endpoint;
        }
        public byte[] getDeviceGuid() { return guid.clone(); }
        public byte[] getRemoteCertificateSha256() { return remoteCertificate.clone(); }
        public byte[] getLocalCertificateSha256() { return localCertificate.clone(); }
        public InetSocketAddress getEndpoint() { return endpoint; }
        @Override public String toString() { return "AuthenticatedPeerInfo(redacted, protocol-only)"; }
    }

    private void notifyResult() {
        mState = State.Stopped;
    }

    private void setupTlsConnection() throws IOException {
        if (mRole == Role.Server) {
            // The server role is unused; only the low-level client is implemented (see AdbTlsClient).
            throw new IOException("Server role is not supported.");
        }
        checkpoint();
        Socket socket = mBorrowedSocket == null ? new Socket(mHost, mPort) : mBorrowedSocket;
        socket.setTcpNoDelay(true);

        // BouncyCastle's JSSE (SSLSocket) clears the TLS 1.3 exporter secret before application code can run, so we
        // drive the handshake with the low-level TLS API instead. This lets AdbTlsClient export the keying material
        // from within notifyHandshakeComplete(), while the exporter secret is still alive. Any server certificate is
        // accepted; the connection is authenticated via SPAKE2 over that exported keying material.
        // Use the lightweight (bc) TLS crypto backend so no JCA provider is required.
        BcTlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
        TlsClientProtocol protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
        AdbTlsClient client = new AdbTlsClient(crypto, mKeyPair);
        mTlsProtocol = protocol;
        mTlsClient = client;
        checkpoint();
        protocol.connect(client);
        checkpoint();
        Log.d(TAG, "Handshake succeeded.");

        mInputStream = new DataInputStream(protocol.getInputStream());
        mOutputStream = new DataOutputStream(protocol.getOutputStream());

        // To ensure the connection is not stolen while we do the PAKE, append the exported key material from the
        // tls connection to the password.
        byte[] keyMaterial = client.getKeyingMaterial();
        if (keyMaterial == null) {
            throw new IOException("Failed to export TLS keying material.");
        }
        byte[] passwordBytes = new byte[mPswd.length + keyMaterial.length];
        System.arraycopy(mPswd, 0, passwordBytes, 0, mPswd.length);
        System.arraycopy(keyMaterial, 0, passwordBytes, mPswd.length, keyMaterial.length);

        PairingAuthCtx pairingAuthCtx;
        try {
            pairingAuthCtx = PairingAuthCtx.createAlice(passwordBytes);
        } finally {
            if (mBorrowedSocket != null) {
                Arrays.fill(passwordBytes, (byte) 0);
                Arrays.fill(keyMaterial, (byte) 0);
            }
        }
        if (pairingAuthCtx == null) {
            throw new IOException("Unable to create PairingAuthCtx.");
        }
        this.mPairingAuthCtx = pairingAuthCtx;
    }

    private void writeHeader(@NonNull PairingPacketHeader header, @NonNull byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(PairingPacketHeader.PAIRING_PACKET_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        header.writeTo(buffer);

        checkpoint();
        mOutputStream.write(buffer.array());
        checkpoint();
        mOutputStream.write(payload);
        checkpoint();
    }

    @Nullable
    private PairingPacketHeader readHeader() throws IOException {
        byte[] bytes = new byte[PairingPacketHeader.PAIRING_PACKET_HEADER_SIZE];
        checkpoint();
        mInputStream.readFully(bytes);
        checkpoint();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return PairingPacketHeader.readFrom(buffer);
    }

    @NonNull
    private PairingPacketHeader createHeader(byte type, int payloadSize) {
        return new PairingPacketHeader(PairingPacketHeader.CURRENT_KEY_HEADER_VERSION, type, payloadSize);
    }

    private boolean checkHeaderType(byte expected, byte actual) {
        if (expected != actual) {
            Log.e(TAG, "Unexpected header type (expected=" + expected + " actual=" + actual + ")");
            return false;
        }
        return true;
    }

    private boolean doExchangeMsgs() throws IOException {
        byte[] msg = mPairingAuthCtx.getMsg();

        PairingPacketHeader ourHeader = createHeader(PairingPacketHeader.SPAKE2_MSG, msg.length);
        // Write our SPAKE2 msg
        writeHeader(ourHeader, msg);

        // Read the peer's SPAKE2 msg header
        PairingPacketHeader theirHeader = readHeader();
        if (theirHeader == null || !checkHeaderType(PairingPacketHeader.SPAKE2_MSG, theirHeader.type)) return false;

        // Read the SPAKE2 msg payload and initialize the cipher for encrypting the PeerInfo and certificate.
        byte[] theirMsg = new byte[theirHeader.payloadSize];
        checkpoint();
        mInputStream.readFully(theirMsg);
        checkpoint();

        try {
            return mPairingAuthCtx.initCipher(theirMsg);
        } catch (Exception e) {
            Log.e(TAG, "Unable to initialize pairing cipher");
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException().initCause(e);
        }
    }

    private boolean doExchangePeerInfo() throws IOException {
        // Encrypt PeerInfo
        ByteBuffer buffer = ByteBuffer.allocate(PeerInfo.MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN);
        mPeerInfo.writeTo(buffer);
        byte[] outBuffer = mPairingAuthCtx.encrypt(buffer.array());
        if (outBuffer == null) {
            Log.e(TAG, "Failed to encrypt peer info");
            return false;
        }

        // Write out the packet header
        PairingPacketHeader ourHeader = createHeader(PairingPacketHeader.PEER_INFO, outBuffer.length);
        // Write out the encrypted payload
        writeHeader(ourHeader, outBuffer);

        // Read in the peer's packet header
        PairingPacketHeader theirHeader = readHeader();
        if (theirHeader == null || !checkHeaderType(PairingPacketHeader.PEER_INFO, theirHeader.type)) return false;

        // Read in the encrypted peer certificate
        byte[] theirMsg = new byte[theirHeader.payloadSize];
        checkpoint();
        mInputStream.readFully(theirMsg);
        checkpoint();

        // Try to decrypt the certificate
        byte[] decryptedMsg = mPairingAuthCtx.decrypt(theirMsg);
        if (decryptedMsg == null) {
            Log.e(TAG, "Unsupported payload while decrypting peer info.");
            return false;
        }

        // The decrypted message should contain the PeerInfo.
        if (decryptedMsg.length != PeerInfo.MAX_PEER_INFO_SIZE) {
            Log.e(TAG, "Got size=" + decryptedMsg.length + " PeerInfo.size=" + PeerInfo.MAX_PEER_INFO_SIZE);
            return false;
        }

        PeerInfo theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decryptedMsg));
        if (mBorrowedSocket != null) {
            try {
                // AOSP PeerInfo.data is an opaque NUL-terminated GUID, not a fixed UUID/255-char schema.
                int end = 0;
                while (end < theirPeerInfo.data.length && theirPeerInfo.data[end] != 0) end++;
                if (theirPeerInfo.type != 1 || end == 0 || end == theirPeerInfo.data.length
                        || mTlsClient.remoteCertificate == null) throw new IOException("pairing_peer_invalid");
                mAuthenticatedPeerInfo = new AuthenticatedPeerInfo(Arrays.copyOf(theirPeerInfo.data, end),
                        mTlsClient.remoteCertificate, MessageDigest.getInstance("SHA-256")
                        .digest(mKeyPair.getCertificate().getEncoded()), mEndpoint);
            } catch (java.security.GeneralSecurityException ignored) {
                throw new IOException("pairing_identity_invalid");
            } finally {
                Arrays.fill(theirPeerInfo.data, (byte) 0);
                Arrays.fill(decryptedMsg, (byte) 0);
            }
        }
        // Raw peer-info logging is removed in both paths; legacy wire/API behavior is unchanged.
        return true;
    }

    @Override
    public void close() {
        if (mBorrowedSocket != null) {
            releaseConnected();
            return;
        }
        Arrays.fill(mPswd, (byte) 0);
        try {
            if (mInputStream != null) mInputStream.close();
        } catch (IOException ignore) {
        }
        try {
            if (mOutputStream != null) mOutputStream.close();
        } catch (IOException ignore) {
        }
        if (mState != State.Ready && mPairingAuthCtx != null) {
            mPairingAuthCtx.destroy();
        }
    }

    /** Called only by the protocol worker, after the transport owner closes its raw socket. */
    public boolean releaseConnected() {
        Arrays.fill(mPswd, (byte) 0);
        if (mPairingAuthCtx != null) mPairingAuthCtx.destroy();
        if (mTlsProtocol != null) {
            try { mTlsProtocol.close(); } catch (IOException ignored) { }
            if (!mTlsProtocol.isClosed()) return false;
        }
        mInputStream = null;
        mOutputStream = null;
        mCheckpoint = null;
        return mPairingAuthCtx == null || mPairingAuthCtx.isDestroyed();
    }

    // A minimal TLS 1.3 client for ADB pairing: it presents the local key pair's self-signed certificate, accepts
    // any server certificate, and exports the keying material from notifyHandshakeComplete() (the only point at
    // which BouncyCastle keeps the TLS 1.3 exporter secret alive).
    private static class AdbTlsClient extends DefaultTlsClient {
        private final KeyPair mKeyPair;
        private byte[] mKeyingMaterial;
        private byte[] remoteCertificate;

        AdbTlsClient(@NonNull BcTlsCrypto crypto, @NonNull KeyPair keyPair) {
            super(crypto);
            this.mKeyPair = keyPair;
        }

        @Nullable
        byte[] getKeyingMaterial() {
            return mKeyingMaterial;
        }

        @Override
        public ProtocolVersion[] getProtocolVersions() {
            // ADB pairing requires TLS 1.3 (the exporter master secret is only defined there).
            return ProtocolVersion.TLSv13.only();
        }

        @Override
        public TlsAuthentication getAuthentication() {
            return new TlsAuthentication() {
                @Override
                public void notifyServerCertificate(TlsServerCertificate serverCertificate) throws IOException {
                    // Accept any certificate; the connection is authenticated out-of-band via SPAKE2.
                    try {
                        remoteCertificate = MessageDigest.getInstance("SHA-256").digest(
                                serverCertificate.getCertificate().getCertificateAt(0).getEncoded());
                    } catch (java.security.GeneralSecurityException ignored) {
                        throw new IOException("pairing_certificate_invalid");
                    }
                }

                @Override
                public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) throws IOException {
                    BcTlsCrypto crypto = (BcTlsCrypto) getCrypto();
                    TlsCertificate tlsCertificate;
                    try {
                        tlsCertificate = crypto.createCertificate(mKeyPair.getCertificate().getEncoded());
                    } catch (CertificateEncodingException e) {
                        throw new IOException(e);
                    }
                    // Echo back the server's certificate_request_context, as required for the TLS 1.3 Certificate message.
                    org.bouncycastle.tls.Certificate certificate = new org.bouncycastle.tls.Certificate(
                            certificateRequest.getCertificateRequestContext(),
                            new CertificateEntry[]{new CertificateEntry(tlsCertificate, null)});
                    // Convert the JCA private key to a lightweight (bc) key parameter for the bc signer.
                    AsymmetricKeyParameter privateKey = PrivateKeyFactory.createKey(
                            mKeyPair.getPrivateKey().getEncoded());
                    return new BcDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto,
                            privateKey, certificate, SignatureAndHashAlgorithm.rsa_pss_rsae_sha256);
                }
            };
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            mKeyingMaterial = context.exportKeyingMaterial(EXPORTED_KEY_LABEL, null, EXPORT_KEY_SIZE);
        }
    }

    private static class PeerInfo {
        public static final int MAX_PEER_INFO_SIZE = 1 << 13;

        public static final byte ADB_RSA_PUB_KEY = 0;
        public static final byte ADB_DEVICE_GUID = 0;

        @NonNull
        public static PeerInfo readFrom(@NonNull ByteBuffer buffer) {
            byte type = buffer.get();
            byte[] data = new byte[MAX_PEER_INFO_SIZE - 1];
            buffer.get(data);
            return new PeerInfo(type, data);
        }

        private final byte type;
        private final byte[] data = new byte[MAX_PEER_INFO_SIZE - 1];

        public PeerInfo(byte type, byte[] data) {
            this.type = type;
            System.arraycopy(data, 0, this.data, 0, Math.min(data.length, MAX_PEER_INFO_SIZE - 1));
        }

        public void writeTo(@NonNull ByteBuffer buffer) {
            buffer.put(type).put(data);
        }

        @NonNull
        @Override
        public String toString() {
            return "PeerInfo{" +
                    "type=" + type +
                    ", data=" + Arrays.toString(data) +
                    '}';
        }
    }

    private static class PairingPacketHeader {
        public static final byte CURRENT_KEY_HEADER_VERSION = 1;
        public static final byte MIN_SUPPORTED_KEY_HEADER_VERSION = 1;
        public static final byte MAX_SUPPORTED_KEY_HEADER_VERSION = 1;

        public static final int MAX_PAYLOAD_SIZE = 2 * PeerInfo.MAX_PEER_INFO_SIZE;
        public static final byte PAIRING_PACKET_HEADER_SIZE = 6;

        public static final byte SPAKE2_MSG = 0;
        public static final byte PEER_INFO = 1;

        @Nullable
        public static PairingPacketHeader readFrom(@NonNull ByteBuffer buffer) {
            byte version = buffer.get();
            byte type = buffer.get();
            int payload = buffer.getInt();
            if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=" + CURRENT_KEY_HEADER_VERSION
                        + " them=" + version + ")");
                return null;
            }
            if (type != SPAKE2_MSG && type != PEER_INFO) {
                Log.e(TAG, "Unknown PairingPacket type " + type);
                return null;
            }
            if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) {
                Log.e(TAG, "Header payload not within a safe payload size (size=" + payload + ")");
                return null;
            }
            return new PairingPacketHeader(version, type, payload);
        }

        private final byte version;
        private final byte type;
        private final int payloadSize;

        public PairingPacketHeader(byte version, byte type, int payloadSize) {
            this.version = version;
            this.type = type;
            this.payloadSize = payloadSize;
        }

        public void writeTo(@NonNull ByteBuffer buffer) {
            buffer.put(version).put(type).putInt(payloadSize);
        }

        @NonNull
        @Override
        public String toString() {
            return "PairingPacketHeader{" +
                    "version=" + version +
                    ", type=" + type +
                    ", payloadSize=" + payloadSize +
                    '}';
        }
    }
}
