package io.warpnect.platform.privileged.directshell.pairing

import io.github.muntashirakon.adb.PairingAuthFixture
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateEntry
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

/** Synthetic TLS1.3 peer/framing, real pinned-library PAKE computation. This is NOT adbd. */
internal class PairingTransportFixture(private val scenario: Scenario) : AutoCloseable {
    enum class Scenario {
        TLS_STALL,
        PAKE_STALL,
        TRICKLE,
        SUCCESS,
        WRONG_CODE,
        WRONG_TYPE,
        BAD_VERSION,
        BAD_SIZE,
        EARLY_CLOSE,
        BAD_TAG,
        EMPTY_GUID,
        UNTERMINATED_GUID,
    }
    val boundary = CountDownLatch(1)
    val partial = CountDownLatch(1)
    val continuePartial = CountDownLatch(1)
    private val listener = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val endpoint = InetSocketAddress(listener.inetAddress, listener.localPort)
    private val accepted = AtomicReference<Socket>()
    private val failure = AtomicReference<Exception>()
    private val worker = Thread({
        try {
            listener.accept().use { socket ->
                accepted.set(socket)
                socket.soTimeout = 5_000
                if (scenario == Scenario.TLS_STALL) {
                    val input = socket.getInputStream()
                    check(input.read() >= 0)
                    boundary.countDown()
                    while (input.read() >= 0) Unit
                } else {
                    serve(socket)
                }
            }
        } catch (problem: Exception) {
            // Assertions report only the closed category; exception details never enter receipts/logs.
            if (!listener.isClosed && scenario != Scenario.EARLY_CLOSE) failure.set(problem)
        }
    }, "test-pairing-peer").also { it.start() }

    private fun serve(socket: Socket) {
        val crypto = BcTlsCrypto(SecureRandom())
        val exporter = AtomicReference<ByteArray>()
        val tls = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
        tls.accept(object : DefaultTlsServer(crypto) {
            override fun getProtocolVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
            override fun getCredentials(): TlsCredentials {
                val identity = StorageFixture.identity
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
                exporter.set(context.exportKeyingMaterial("adb-label\u0000", null, 64))
            }
        })
        val input = DataInputStream(tls.inputStream)
        val output = DataOutputStream(tls.outputStream)
        val message = readPacket(input, 0)
        if (scenario == Scenario.PAKE_STALL || scenario == Scenario.TRICKLE || scenario == Scenario.EARLY_CLOSE) {
            boundary.countDown()
            if (scenario == Scenario.EARLY_CLOSE) return
            if (scenario == Scenario.TRICKLE) {
                output.write(1)
                output.flush()
                partial.countDown()
                check(continuePartial.await(3, TimeUnit.SECONDS))
            }
            try {
                while (input.read() >= 0) Unit
            } catch (_: IOException) { /* Peer closed the operation-owned transport. */ }
            exporter.get().fill(0)
            return
        }
        val code = (if (scenario == Scenario.WRONG_CODE) "271828" else CODE).toCharArray()
        val bob = PairingAuthFixture(false, code, exporter.get())
        code.fill('\u0000')
        exporter.get().fill(0)
        bob.use {
            check(it.initialize(message))
            when (scenario) {
                Scenario.BAD_VERSION -> output.write(byteArrayOf(2, 0, 0, 0, 0, 1, 0))
                Scenario.BAD_SIZE -> output.write(ByteBuffer.allocate(6).put(1).put(0).putInt(Int.MAX_VALUE).array())
                else -> writePacket(output, 0, it.message())
            }
            output.flush()
            boundary.countDown()
            if (scenario == Scenario.BAD_VERSION || scenario == Scenario.BAD_SIZE) {
                try {
                    while (input.read() >= 0) Unit
                } catch (_: IOException) { }
                return
            }
            val client = it.decrypt(readPacket(input, 1))
            if (scenario == Scenario.WRONG_CODE) {
                check(client == null)
                return
            }
            check(client != null && client.size == 8192 && client[0] == 0.toByte())
            client.fill(0)
            val info = ByteArray(8192)
            info[0] = if (scenario == Scenario.WRONG_TYPE) 0 else 1
            if (scenario == Scenario.UNTERMINATED_GUID) {
                info.fill('a'.code.toByte(), 1)
            } else if (scenario != Scenario.EMPTY_GUID) "fixture-guid".toByteArray().copyInto(info, 1)
            val ciphertext = requireNotNull(it.encrypt(info))
            if (scenario == Scenario.BAD_TAG) {
                ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            }
            writePacket(output, 1, ciphertext)
            info.fill(0)
            try {
                while (input.read() >= 0) Unit
            } catch (_: IOException) { }
        }
    }

    fun awaitBoundary() = check(boundary.await(3, TimeUnit.SECONDS)) { "fixture_boundary_not_observed" }

    override fun close() {
        continuePartial.countDown()
        listener.close()
        accepted.get()?.close()
        worker.join(3_000)
        check(!worker.isAlive) { "fixture_peer_undrained" }
        check(failure.get() == null) { "fixture_peer_failed" }
    }

    internal class Client(private val beforeReturn: () -> Unit = {}) : DirectShellPairingTransport.Protocol {
        private var auth: PairingAuthFixture? = null
        override fun exchange(
            socket: Socket,
            code: CharArray,
            checkActive: () -> Unit,
        ): DirectShellPairingTransport.Completion {
            val crypto = BcTlsCrypto(SecureRandom())
            val exporter = AtomicReference<ByteArray>()
            val tls = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
            checkActive()
            tls.connect(object : DefaultTlsClient(crypto) {
                override fun getProtocolVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
                override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
                    override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) = Unit
                    override fun getClientCredentials(
                        request: org.bouncycastle.tls.CertificateRequest,
                    ): TlsCredentials? = null
                }
                override fun notifyHandshakeComplete() {
                    super.notifyHandshakeComplete()
                    exporter.set(context.exportKeyingMaterial("adb-label\u0000", null, 64))
                }
            })
            val alice = PairingAuthFixture(true, code, exporter.get()).also { auth = it }
            exporter.get().fill(0)
            val output = DataOutputStream(tls.outputStream)
            val input = DataInputStream(tls.inputStream)
            checkActive()
            writePacket(output, 0, alice.message())
            val peer = readPacket(input, 0)
            check(alice.initialize(peer)) { "fixture_authentication_rejected" }
            checkActive()
            val info = ByteArray(8192) // Synthetic client peer-info, not a production RSA credential.
            try {
                writePacket(output, 1, requireNotNull(alice.encrypt(info)))
            } finally {
                info.fill(0)
            }
            val authenticated = alice.decrypt(readPacket(input, 1)) ?: error("fixture_authentication_rejected")
            try {
                check(authenticated.size == 8192 && authenticated[0] == 1.toByte()) { "fixture_peer_type_rejected" }
                val end = (1 until authenticated.size).firstOrNull { authenticated[it] == 0.toByte() } ?: 0
                check(end in 2..256) { "fixture_peer_guid_rejected" }
                val guid = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(authenticated, 1, end - 1))
                check(guid.isNotEmpty() && guid.all { !it.isWhitespace() && !it.isISOControl() })
                beforeReturn()
                return DirectShellPairingTransport.Completion.FIXTURE_VERIFIED
            } finally {
                authenticated.fill(0)
            }
        }

        override fun release(): Boolean {
            auth?.close()
            auth = null
            return true
        }
    }

    companion object {
        const val CODE = "314159"
        private fun readPacket(input: DataInputStream, expected: Int): ByteArray {
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == expected) { "fixture_frame_rejected" }
            val size = input.readInt()
            check(size in 1..16384) { "fixture_frame_bound" }
            return ByteArray(size).also { input.readFully(it) }
        }
        private fun writePacket(output: DataOutputStream, type: Int, payload: ByteArray) {
            check(payload.size in 1..16384)
            output.write(ByteBuffer.allocate(6).put(1).put(type.toByte()).putInt(payload.size).array())
            output.write(payload)
            output.flush()
        }
    }
}
