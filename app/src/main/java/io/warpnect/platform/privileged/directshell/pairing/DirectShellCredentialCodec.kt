package io.warpnect.platform.privileged.directshell.pairing

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.SecretKey

/** Existing AES-256 key only; no creation, repair, DEBUG import or Android ownership claim. */
internal fun interface DirectShellPairingWrappingKey {
    fun existing(): SecretKey?

    fun existingAead(): DirectShellCredentialAead? = existing()?.let(DirectShellJcaCredentialAead::software)
}

internal class DirectShellCredentialIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    override fun toString(): String = "DirectShellCredentialIdentity(redacted)"
}

/** Supplied by a future authenticated protocol adapter, NOT by discovery or this storage engine. */
internal class DirectShellAuthenticatedPairingEvidence(
    internal val ticket: DirectShellPairingTicket,
    localCertificateDigest: ByteArray,
    authenticatedPeerDigest: ByteArray,
) {
    private val localDigest = localCertificateDigest.copyOf()
    private val peerDigest = authenticatedPeerDigest.copyOf()

    internal fun matches(certificate: X509Certificate): Boolean = localDigest.size == 32 && peerDigest.size == 32 &&
        MessageDigest.isEqual(localDigest, DirectShellCredentialCodec.digest(certificate.encoded))

    internal fun peerDigestCopy(): ByteArray = peerDigest.copyOf()

    override fun toString(): String = "DirectShellAuthenticatedPairingEvidence(redacted)"
}

/** Blocking worker-only codec. Reuses reviewed P2 RSA consistency/AES-GCM algorithms, not its schema. */
internal object DirectShellCredentialCodec {
    const val MAX_RECORD = 65_536
    private const val HEADER = 52
    private const val MAGIC = 0x57504144
    private const val VERSION = 2

    enum class Kind { RECOVERY, AUTHENTICATED }

    class Record(
        val kind: Kind,
        val binding: ByteArray,
        val identity: DirectShellCredentialIdentity,
        val peerDigest: ByteArray?,
    ) {
        override fun toString(): String = "DirectShellCredentialRecord(redacted)"
    }

    fun binding(ticket: DirectShellPairingTicket): ByteArray {
        require(ticket.ownerEpoch.length in 1..64 && ticket.nonce.length in 1..64) { "credential_binding_invalid" }
        val owner = ticket.ownerEpoch.toByteArray(Charsets.UTF_8)
        val nonce = ticket.nonce.toByteArray(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate(8 + owner.size + nonce.size + 24)
            .putInt(owner.size).put(owner).putInt(nonce.size).put(nonce)
            .putLong(ticket.originReadinessGeneration).putLong(ticket.networkEpoch)
            .putLong(ticket.credentialRevision).array()
        return digest(bytes)
    }

    fun encode(record: Record, key: SecretKey): ByteArray =
        encode(record, requireNotNull(DirectShellJcaCredentialAead.software(key)) { "credential_key_unavailable" })

    fun encode(record: Record, aead: DirectShellCredentialAead): ByteArray {
        require(record.binding.size == 32 && valid(record.identity)) { "credential_material_invalid" }
        require(
            (record.kind == Kind.RECOVERY && record.peerDigest == null) ||
                (record.kind == Kind.AUTHENTICATED && record.peerDigest?.size == 32),
        ) { "credential_evidence_invalid" }
        val privateBytes = record.identity.privateKey.encoded
        val certificateBytes = record.identity.certificate.encoded
        var plaintext: ByteArray? = null
        try {
            require(privateBytes.size in 1..8192 && certificateBytes.size in 1..16384) { "credential_material_size" }
            val size = 8 + privateBytes.size + certificateBytes.size + (record.peerDigest?.size ?: 0)
            plaintext = ByteBuffer.allocate(size)
                .putInt(privateBytes.size).put(privateBytes).putInt(certificateBytes.size).put(certificateBytes)
                .apply { record.peerDigest?.let(::put) }.array()
            val header = ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(VERSION)
                .putInt(record.kind.ordinal).putLong(if (record.kind == Kind.RECOVERY) 0 else 1)
                .put(record.binding).array()
            val sealed = aead.seal(plaintext, header)
            require(sealed.iv.size == 12 && sealed.ciphertext.size == plaintext.size + 16) { "credential_aead_output" }
            return (header + sealed.iv + sealed.ciphertext).also {
                require(it.size <= MAX_RECORD) { "credential_record_size" }
            }
        } finally {
            privateBytes.fill(0)
            certificateBytes.fill(0)
            plaintext?.fill(0)
        }
    }

    fun decode(bytes: ByteArray, key: SecretKey): Record =
        decode(bytes, requireNotNull(DirectShellJcaCredentialAead.software(key)) { "credential_key_unavailable" })

    fun decode(bytes: ByteArray, aead: DirectShellCredentialAead): Record {
        require(bytes.size in HEADER + 12 + 16..MAX_RECORD) { "credential_record_size" }
        val header = ByteBuffer.wrap(bytes, 0, HEADER)
        require(header.int == MAGIC && header.int == VERSION) { "credential_record_version" }
        val kind = Kind.entries.getOrNull(header.int) ?: error("credential_record_kind")
        require(header.long == if (kind == Kind.RECOVERY) 0L else 1L) { "credential_record_revision" }
        val binding = ByteArray(32).also(header::get)
        val plaintext = aead.open(
            bytes.copyOfRange(HEADER + 12, bytes.size),
            bytes.copyOfRange(HEADER, HEADER + 12),
            bytes.copyOfRange(0, HEADER),
        )
        try {
            require(plaintext.size <= DirectShellJcaCredentialAead.MAX_PLAINTEXT) { "credential_plaintext_size" }
            val input = DataInputStream(ByteArrayInputStream(plaintext))
            val privateBytes = field(input, 8192)
            var certificateBytes: ByteArray? = null
            try {
                certificateBytes = field(input, 16384)
                val peer = if (kind == Kind.AUTHENTICATED) ByteArray(32).also(input::readFully) else null
                require(input.available() == 0) { "credential_record_trailing" }
                val identity = DirectShellCredentialIdentity(
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate,
                )
                require(valid(identity)) { "credential_record_identity" }
                return Record(kind, binding, identity, peer)
            } finally {
                privateBytes.fill(0)
                certificateBytes?.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun valid(identity: DirectShellCredentialIdentity): Boolean {
        val privateKey = identity.privateKey as? RSAPrivateKey ?: return false
        val publicKey = identity.certificate.publicKey as? RSAPublicKey ?: return false
        if (privateKey.modulus.bitLength() != 2048 || privateKey.modulus != publicKey.modulus) return false
        identity.certificate.verify(publicKey)
        val marker = byteArrayOf(87, 65, 68, 66, 2)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(marker)
        }.sign()
        return Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update(marker)
        }.verify(signature)
    }

    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun field(input: DataInputStream, bound: Int): ByteArray {
        val count = input.readInt()
        require(count in 1..bound && count <= input.available()) { "credential_field_size" }
        return ByteArray(count).also(input::readFully)
    }
}
