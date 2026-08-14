package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import io.warpnect.session.identity.IdentityFingerprint
import io.warpnect.session.identity.IdentityKeyAlgorithm
import io.warpnect.session.identity.IdentityKeyResult
import io.warpnect.session.identity.IdentityKeySecurityLevel
import io.warpnect.session.identity.IdentityPublicKey
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.identity.LocalDeviceIdentity
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface PairingCryptoResult<out T> {
    data class Value<T>(
        val value: T,
    ) : PairingCryptoResult<T>

    data class Failed(
        val detail: String,
    ) : PairingCryptoResult<Nothing>
}

interface PairingEphemeralKeyPair : AutoCloseable {
    val publicKey: EphemeralPublicKey

    fun deriveSharedSecret(peerPublicKey: EphemeralPublicKey): PairingCryptoResult<ImmutableBytes>

    override fun close()
}

interface PairingCryptoProvider {
    fun randomBytes(size: Int): ByteArray

    fun sha256(input: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray

    fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray

    fun hkdfExpand(pseudorandomKey: ByteArray, info: ByteArray, outputLength: Int): ByteArray

    fun generateEphemeralKeyPair(): PairingCryptoResult<PairingEphemeralKeyPair>

    fun isValidP256PublicKey(encodedSpki: ByteArray): Boolean

    fun verifyIdentitySignature(publicKey: IdentityPublicKey, data: ByteArray, signature: PairingSignature): Boolean

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean
}

/**
 * JCA-backed P-256 pairing provider. It delegates all elliptic-curve, signature, digest, HMAC,
 * and key-agreement operations to platform cryptographic primitives.
 */
class JcaPairingCryptoProvider(
    private val secureRandom: SecureRandom = SecureRandom(),
) : PairingCryptoProvider {
    override fun randomBytes(size: Int): ByteArray {
        require(size >= 0)
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    override fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    override fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(input)
    }

    override fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(PairingBootstrapProtocol.HASH_BYTES) else salt
        return hmacSha256(effectiveSalt, inputKeyMaterial)
    }

    override fun hkdfExpand(pseudorandomKey: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        require(outputLength in 0..(255 * PairingBootstrapProtocol.HASH_BYTES)) {
            "HKDF output length exceeds RFC 5869 bounds"
        }
        val output = ByteArray(outputLength)
        var previous = ByteArray(0)
        var copied = 0
        var counter = 1
        while (copied < outputLength) {
            val input = ByteArray(previous.size + info.size + 1)
            previous.copyInto(input)
            info.copyInto(input, previous.size)
            input[input.lastIndex] = counter.toByte()
            previous = hmacSha256(pseudorandomKey, input)
            val copyLength = minOf(previous.size, outputLength - copied)
            previous.copyInto(output, copied, 0, copyLength)
            copied += copyLength
            counter += 1
        }
        previous.fill(0)
        return output
    }

    override fun generateEphemeralKeyPair(): PairingCryptoResult<PairingEphemeralKeyPair> = try {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(P256_CURVE_NAME), secureRandom)
        val keyPair = generator.generateKeyPair()
        val publicKey = EphemeralPublicKey.fromSpki(keyPair.public.encoded)
            ?: return PairingCryptoResult.Failed("Generated P-256 public key exceeded protocol bounds")
        PairingCryptoResult.Value(JcaPairingEphemeralKeyPair(keyPair, publicKey))
    } catch (exception: Exception) {
        PairingCryptoResult.Failed(exception.message ?: "Unable to generate P-256 ephemeral key")
    }

    override fun isValidP256PublicKey(encodedSpki: ByteArray): Boolean = parseP256PublicKey(encodedSpki) != null

    override fun verifyIdentitySignature(
        publicKey: IdentityPublicKey,
        data: ByteArray,
        signature: PairingSignature,
    ): Boolean = try {
        val parsed = parseP256PublicKey(publicKey.encodedSpki()) ?: return false
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(parsed)
            update(data)
            verify(signature.der())
        }
    } catch (_: Exception) {
        false
    }

    override fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)

    private class JcaPairingEphemeralKeyPair(
        private var keyPair: KeyPair?,
        override val publicKey: EphemeralPublicKey,
    ) : PairingEphemeralKeyPair {
        override fun deriveSharedSecret(peerPublicKey: EphemeralPublicKey): PairingCryptoResult<ImmutableBytes> = try {
            val current = keyPair ?: return PairingCryptoResult.Failed("Ephemeral key has already been destroyed")
            val peer = parseP256PublicKey(peerPublicKey.encodedSpki())
                ?: return PairingCryptoResult.Failed("Peer ephemeral key is not P-256")
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(current.private)
            agreement.doPhase(peer, true)
            PairingCryptoResult.Value(ImmutableBytes.copyOf(agreement.generateSecret()))
        } catch (exception: Exception) {
            PairingCryptoResult.Failed(exception.message ?: "ECDH agreement failed")
        }

        override fun close() {
            keyPair = null
        }
    }

    companion object {
        const val P256_CURVE_NAME: String = "secp256r1"

        fun parseP256PublicKey(encodedSpki: ByteArray): PublicKey? {
            return try {
                if (encodedSpki.isEmpty() || encodedSpki.size > PairingBootstrapProtocol.MAX_PUBLIC_KEY_BYTES) {
                    return null
                }
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedSpki)) as? ECPublicKey
                    ?: return null
                if (key.algorithm != "EC" || !key.params.isP256()) return null
                key
            } catch (_: Exception) {
                null
            }
        }

        private fun ECParameterSpec.isP256(): Boolean {
            val expected = p256Parameters()
            val field = curve.field as? ECFieldFp ?: return false
            val expectedField = expected.curve.field as? ECFieldFp ?: return false
            return field.p == expectedField.p &&
                curve.a == expected.curve.a &&
                curve.b == expected.curve.b &&
                generator.samePoint(expected.generator) &&
                order == expected.order &&
                cofactor == expected.cofactor
        }

        private fun ECPoint.samePoint(other: ECPoint): Boolean = affineX == other.affineX && affineY == other.affineY

        private fun p256Parameters(): ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(P256_CURVE_NAME))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }
}

/** Software-only identity signer for deterministic JVM protocol tests. */
class JcaSoftwareIdentitySigner private constructor(
    override val identity: LocalDeviceIdentity,
    private val keyPair: KeyPair,
) : LocalDeviceIdentitySigner {
    override fun sign(data: ByteArray): IdentityKeyResult<ImmutableBytes> = try {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data)
            sign()
        }
        IdentityKeyResult.Value(ImmutableBytes.copyOf(signature))
    } catch (exception: Exception) {
        IdentityKeyResult.Failed(exception.message ?: "Unable to sign pairing data")
    }

    companion object {
        fun generate(
            deviceId: DeviceId,
            crypto: PairingCryptoProvider = JcaPairingCryptoProvider(),
        ): JcaSoftwareIdentitySigner {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(JcaPairingCryptoProvider.P256_CURVE_NAME))
            val keyPair = generator.generateKeyPair()
            val publicKey = IdentityPublicKey.requireSpki(keyPair.public.encoded)
            val fingerprint = IdentityFingerprint.requireSha256(crypto.sha256(publicKey.encodedSpki()))
            return JcaSoftwareIdentitySigner(
                LocalDeviceIdentity(
                    deviceId = deviceId,
                    keyAlias = "test.software.identity",
                    algorithm = IdentityKeyAlgorithm.EcdsaP256Sha256,
                    publicKey = publicKey,
                    fingerprint = fingerprint,
                    securityLevel = IdentityKeySecurityLevel.Software,
                ),
                keyPair,
            )
        }
    }
}
