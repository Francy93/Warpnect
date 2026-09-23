package io.warpnect.debug.directshell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
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

    fun loadOrCreate(): Material = load() ?: create().also(::save)

    fun clear() {
        preferences.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                if (containsAlias(ENCRYPTION_KEY_ALIAS)) deleteEntry(ENCRYPTION_KEY_ALIAS)
            }
        }
    }

    private fun load(): Material? {
        val encryptedPrivateKey = preferences.getString(PREF_PRIVATE_KEY, null) ?: return null
        val encryptedCertificate = preferences.getString(PREF_CERTIFICATE, null) ?: return null
        val privateKeyBytes = decrypt(encryptedPrivateKey)
        val certificateBytes = decrypt(encryptedCertificate)
        return try {
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
            Material(privateKey, certificate)
        } finally {
            privateKeyBytes.fill(0)
            certificateBytes.fill(0)
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
            preferences.edit()
                .putString(PREF_PRIVATE_KEY, encrypt(privateKeyBytes))
                .putString(PREF_CERTIFICATE, encrypt(certificateBytes))
                .apply()
        } finally {
            privateKeyBytes.fill(0)
            certificateBytes.fill(0)
        }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = store.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
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

    private fun encrypt(value: ByteArray): String {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
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
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }.doFinal(ciphertext)
        } finally {
            combined.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private companion object {
        const val PREFERENCES = "directshell_self_adb_probe"
        const val PREF_PRIVATE_KEY = "private_key"
        const val PREF_CERTIFICATE = "certificate"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val ENCRYPTION_KEY_ALIAS = "warpnect.directshell.selfadb.debug.v1"
        const val CIPHER = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val CERTIFICATE_VALIDITY_MILLIS = 365L * 24L * 60L * 60L * 1_000L
    }
}
