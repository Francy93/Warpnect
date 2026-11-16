package io.warpnect.debug.directshell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * DEBUG-only persistence for the ADB host key used by a user-approved Wireless Debugging pairing.
 *
 * The ADB key itself must remain software-exportable because the pairing client presents it through
 * a TLS 1.3 stack. Its serialized form is encrypted at rest by an AndroidKeyStore AES-GCM key.
 */
internal class DirectShellSelfAdbKeyStore(context: Context) {
    data class Material(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun loadOrCreate(): Material {
        val records = preferences.all
        if (!records.hasStoredIdentity()) return create().also(::save)
        return try {
            load(records)
        } catch (_: Exception) {
            throw unreadableIdentity()
        }
    }

    /**
     * The ADB Wi-Fi GUID is mDNS service metadata, not a shell credential. It is nevertheless
     * kept alongside the paired key under the same app-private encryption key so a later bootstrap
     * resolves only the service the user explicitly paired with.
     */
    fun trustedConnectionServiceName(): String = readTrustedConnectionServiceName(preferences.all, ::decrypt)

    fun rememberTrustedConnectionServiceName(serviceName: String) {
        val normalized = DirectShellSelfAdbEndpointDiscovery.requireTrustedServiceName(serviceName)
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        try {
            check(
                preferences.edit()
                    .putString(PREF_TRUSTED_CONNECTION_SERVICE_NAME, encrypt(bytes, encryptionKey(createIfMissing = false)))
                    .commit(),
            ) { "trusted_adb_service_name_persist_failed" }
        } finally {
            bytes.fill(0)
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                if (containsAlias(ENCRYPTION_KEY_ALIAS)) deleteEntry(ENCRYPTION_KEY_ALIAS)
            }
        }
    }

    private fun load(records: Map<String, *>): Material {
        val encryptedPrivateKey = checkNotNull(records[PREF_PRIVATE_KEY] as? String) { "stored_adb_identity_incomplete" }
        val encryptedCertificate = checkNotNull(records[PREF_CERTIFICATE] as? String) { "stored_adb_identity_incomplete" }
        val privateKeyBytes = decrypt(encryptedPrivateKey)
        return try {
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            val certificateBytes = decrypt(encryptedCertificate)
            try {
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
                certificate.checkValidity()
                certificate.verify(certificate.publicKey)
                Material(privateKey, certificate)
            } finally {
                certificateBytes.fill(0)
            }
        } finally {
            privateKeyBytes.fill(0)
        }
    }

    private fun create(): Material {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2_048, random)
        }.generateKeyPair()
        val now = Date()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                X500Name("CN=Warpnect DirectShell Debug"),
                BigInteger(160, random),
                now,
                Date(now.time + CERTIFICATE_VALIDITY_MILLIS),
                X500Name("CN=Warpnect DirectShell Debug"),
                keyPair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
        )
        certificate.checkValidity(now)
        certificate.verify(keyPair.public)
        return Material(keyPair.private, certificate)
    }

    private fun save(material: Material) {
        val privateKeyBytes = material.privateKey.encoded
            ?: throw IllegalStateException("software_adb_private_key_not_exportable")
        val certificateBytes = material.certificate.encoded
        try {
            val key = encryptionKey(createIfMissing = true)
            preferences.edit()
                .putString(PREF_PRIVATE_KEY, encrypt(privateKeyBytes, key))
                .putString(PREF_CERTIFICATE, encrypt(certificateBytes, key))
                .apply()
        } finally {
            privateKeyBytes.fill(0)
            certificateBytes.fill(0)
        }
    }

    private fun encryptionKey(createIfMissing: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = store.getKey(ENCRYPTION_KEY_ALIAS, null)
        if (existing != null) return checkNotNull(existing as? SecretKey) { "stored_adb_identity_wrapping_key_invalid" }
        check(createIfMissing) { "stored_adb_identity_wrapping_key_missing" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ENCRYPTION_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(value: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(value)
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val combined = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("stored_adb_key_is_not_base64")
        }
        require(combined.size > GCM_IV_BYTES) { "stored_adb_key_is_truncated" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        return try {
            Cipher.getInstance(CIPHER).apply {
                init(Cipher.DECRYPT_MODE, encryptionKey(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, iv))
            }.doFinal(ciphertext)
        } finally {
            combined.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    companion object {
        internal fun readTrustedConnectionServiceName(
            records: Map<String, *>,
            decrypt: (String) -> ByteArray,
        ): String {
            if (!records.hasStoredIdentity()) {
                throw DirectShellSelfAdbEndpointDiscovery.DiscoveryException(
                    DirectShellSelfAdbEndpointDiscovery.Failure.CONNECTION_SERVICE_PAIRING_REQUIRED,
                )
            }
            val encrypted = records[PREF_TRUSTED_CONNECTION_SERVICE_NAME] as? String
            val privateKey = records[PREF_PRIVATE_KEY] as? String
            val certificate = records[PREF_CERTIFICATE] as? String
            if (encrypted.isNullOrBlank() || privateKey.isNullOrBlank() || certificate.isNullOrBlank()) {
                throw unreadableIdentity()
            }
            val bytes = try {
                decrypt(encrypted)
            } catch (_: Exception) {
                throw unreadableIdentity()
            }
            return try {
                DirectShellSelfAdbEndpointDiscovery.requireTrustedServiceName(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (_: CharacterCodingException) {
                throw unreadableIdentity()
            } catch (_: IllegalArgumentException) {
                throw unreadableIdentity()
            } finally {
                bytes.fill(0)
            }
        }

        private fun Map<String, *>.hasStoredIdentity(): Boolean =
            containsKey(PREF_PRIVATE_KEY) || containsKey(PREF_CERTIFICATE) || containsKey(PREF_TRUSTED_CONNECTION_SERVICE_NAME)

        private fun unreadableIdentity() = DirectShellSelfAdbEndpointDiscovery.DiscoveryException(
            DirectShellSelfAdbEndpointDiscovery.Failure.CONNECTION_SERVICE_IDENTITY_REQUIRED,
        )

        private const val PREFERENCES = "directshell_self_adb_probe"
        private const val PREF_PRIVATE_KEY = "private_key"
        private const val PREF_CERTIFICATE = "certificate"
        private const val PREF_TRUSTED_CONNECTION_SERVICE_NAME = "trusted_connection_service_name"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "warpnect.directshell.selfadb.debug.v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        const val CERTIFICATE_VALIDITY_MILLIS = 365L * 24L * 60L * 60L * 1_000L
    }
}
