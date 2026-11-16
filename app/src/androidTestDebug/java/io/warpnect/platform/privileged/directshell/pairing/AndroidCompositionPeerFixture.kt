package io.warpnect.platform.privileged.directshell.pairing

import io.github.muntashirakon.adb.DebugSyntheticPairingPeerAuth
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Vector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateEntry
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

/** Controlled adbd-wire peer, not an Android daemon. Requires and checks the real client certificate/key. */
internal class AndroidCompositionPeerFixture(
    val mode: Mode = Mode.SUCCESS,
    private val serverIdentity: DirectShellCredentialIdentity,
    private val expectedClientIdentity: DirectShellCredentialIdentity,
) : AutoCloseable {
    enum class Mode {
        SUCCESS,
        TLS_STALL,
        PAKE_STALL,
        TRICKLE,
        WRONG_CODE,
        BAD_VERSION,
        BAD_TYPE,
        BAD_SIZE,
        BAD_TAG,
        BAD_GUID_TYPE,
        EMPTY_GUID,
        UNTERMINATED_GUID,
        LONG_GUID,
        EOF,
        HOLD_PEER_REPLY,
    }

    private val listener = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val endpoint = InetSocketAddress(listener.inetAddress, listener.localPort)
    val boundary = CountDownLatch(1)
    val peerInfo = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val clientCertificate = AtomicReference<ByteArray>()
    private val socket = AtomicReference<Socket>()
    private val failure = AtomicReference<Exception>()
    private val worker = Thread({
        try {
            listener.accept().use {
                socket.set(it)
                it.soTimeout = 5_000
                serve(it)
            }
        } catch (problem: Exception) {
            if (!listener.isClosed && problem !is IOException) failure.set(problem)
        }
    }, "test-owned-pairing-peer").also { it.start() }

    private fun serve(socket: Socket) {
        if (mode == Mode.TLS_STALL) {
            val input = socket.getInputStream()
            check(input.read() >= 0)
            boundary.countDown()
            while (input.read() >= 0) Unit
            return
        }
        val crypto = BcTlsCrypto(SecureRandom())
        val exported = AtomicReference<ByteArray>()
        val tls = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
        tls.accept(object : DefaultTlsServer(crypto) {
            override fun getProtocolVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
            override fun getCertificateRequest() = CertificateRequest(
                byteArrayOf(),
                Vector(listOf(SignatureAndHashAlgorithm.rsa_pss_rsae_sha256)),
                null,
                null,
            )
            override fun notifyClientCertificate(certificate: Certificate) {
                check(!certificate.isEmpty)
                val encoded = certificate.getCertificateAt(0).encoded
                check(encoded.contentEquals(expectedClientIdentity.certificate.encoded))
                clientCertificate.set(encoded)
            }
            override fun getCredentials(): TlsCredentials {
                val identity = serverIdentity
                return BcDefaultTlsCredentialedSigner(
                    TlsCryptoParameters(context),
                    crypto,
                    PrivateKeyFactory.createKey(identity.privateKey.encoded),
                    Certificate(
                        byteArrayOf(),
                        arrayOf(CertificateEntry(crypto.createCertificate(identity.certificate.encoded), null)),
                    ),
                    SignatureAndHashAlgorithm.rsa_pss_rsae_sha256,
                )
            }
            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                exported.set(context.exportKeyingMaterial("adb-label\u0000", null, 64))
            }
        })
        val input = DataInputStream(tls.inputStream)
        val output = DataOutputStream(tls.outputStream)
        val alice = read(input, 0)
        boundary.countDown()
        if (mode == Mode.PAKE_STALL || mode == Mode.TRICKLE || mode == Mode.EOF) {
            if (mode == Mode.EOF) return
            if (mode == Mode.TRICKLE) {
                output.write(1)
                output.flush()
                peerInfo.countDown()
            }
            while (input.read() >= 0) Unit
            return
        }
        val code = (if (mode == Mode.WRONG_CODE) "271828" else CODE).toCharArray()
        val bob = DebugSyntheticPairingPeerAuth(code, exported.get())
        code.fill('\u0000')
        exported.get().fill(0)
        bob.use {
            check(it.initialize(alice))
            when (mode) {
                Mode.BAD_VERSION -> output.write(byteArrayOf(2, 0, 0, 0, 0, 1, 0))
                Mode.BAD_TYPE -> output.write(byteArrayOf(1, 2, 0, 0, 0, 1, 0))
                Mode.BAD_SIZE -> output.write(ByteBuffer.allocate(6).put(1).put(0).putInt(Int.MAX_VALUE).array())
                else -> write(output, 0, it.message())
            }
            output.flush()
            if (mode in setOf(Mode.BAD_VERSION, Mode.BAD_TYPE, Mode.BAD_SIZE)) return
            val client = it.decrypt(read(input, 1))
            if (mode == Mode.WRONG_CODE) {
                check(client == null)
                return
            }
            check(client != null && client.size == 8192 && client[0] == 0.toByte())
            val end = (1 until client.size).first { offset -> client[offset] == 0.toByte() }
            val keyText = String(client, 1, end - 1, Charsets.US_ASCII).split(' ')
            check(keyText.size == 2 && keyText[1] == "Warpnect")
            val encoded = Base64.getDecoder().decode(keyText[0])
            check(encoded.size == 524)
            val key = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            check(key.int == 64)
            key.int // n0inv
            val modulus = ByteArray(256).also { bytes -> key.get(bytes) }
            key.position(key.position() + 256) // Montgomery RR
            val publicKey = expectedClientIdentity.certificate.publicKey as RSAPublicKey
            check(BigInteger(1, modulus.reversedArray()) == publicKey.modulus)
            check(key.int.toLong() and 0xffffffffL == publicKey.publicExponent.toLong())
            client.fill(0)
            peerInfo.countDown()
            if (mode == Mode.HOLD_PEER_REPLY) check(proceed.await(3, TimeUnit.SECONDS))
            val info = ByteArray(8192)
            info[0] = if (mode == Mode.BAD_GUID_TYPE) 0 else 1
            when (mode) {
                Mode.EMPTY_GUID -> Unit
                Mode.UNTERMINATED_GUID -> info.fill(65, 1)
                Mode.LONG_GUID -> ByteArray(500) { 65 }.copyInto(info, 1)
                else -> "fixture opaque guid".toByteArray().copyInto(info, 1)
            }
            val encrypted = requireNotNull(it.encrypt(info))
            if (mode == Mode.BAD_TAG) encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            write(output, 1, encrypted)
            info.fill(0)
            while (input.read() >= 0) Unit
        }
    }

    fun await(latch: CountDownLatch = boundary) = check(latch.await(3, TimeUnit.SECONDS)) { "owned_peer_boundary" }

    override fun close() {
        proceed.countDown()
        listener.close()
        socket.get()?.close()
        worker.join(3_000)
        check(!worker.isAlive) { "owned_peer_worker_pending" }
        check(failure.get() == null) { "owned_peer_fixture_failed" }
    }

    companion object {
        const val CODE = "314159"
        private fun read(input: DataInputStream, type: Int): ByteArray {
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == type)
            val size = input.readInt()
            check(size in 1..16384)
            return ByteArray(size).also { input.readFully(it) }
        }
        private fun write(output: DataOutputStream, type: Int, bytes: ByteArray) {
            output.write(ByteBuffer.allocate(6).put(1).put(type.toByte()).putInt(bytes.size).array())
            output.write(bytes)
            output.flush()
        }
    }
}
