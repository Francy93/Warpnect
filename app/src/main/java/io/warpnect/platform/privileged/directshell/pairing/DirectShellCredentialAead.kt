package io.warpnect.platform.privileged.directshell.pairing

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Worker-only authenticated encryption. Neither API exports a wrapping key or creates one. */
internal interface DirectShellCredentialAead {
    class Sealed(val iv: ByteArray, val ciphertext: ByteArray) {
        override fun toString(): String = "DirectShellCredentialSealed(redacted)"
    }
    fun seal(plaintext: ByteArray, aad: ByteArray): Sealed
    fun open(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray
}

/** Handle must have independently verified AES256 ownership/policy; no getEncoded in crypto operations. */
internal class DirectShellJcaCredentialAead private constructor(
    private val key: SecretKey,
) : DirectShellCredentialAead {
    override fun seal(plaintext: ByteArray, aad: ByteArray): DirectShellCredentialAead.Sealed {
        require(plaintext.size <= MAX_PLAINTEXT && aad.size <= MAX_AAD) { "credential_aead_bounds" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore randomized-encryption keys reject caller-provided encryption IVs.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val parameters = cipher.parameters.getParameterSpec(GCMParameterSpec::class.java)
        val iv = cipher.iv
        require(iv.size == 12 && parameters.tLen == 128) { "credential_aead_parameters" }
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        require(ciphertext.size == plaintext.size + 16) { "credential_aead_output" }
        return DirectShellCredentialAead.Sealed(iv, ciphertext)
    }

    override fun open(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertext.size in 16..MAX_PLAINTEXT + 16 && iv.size == 12 && aad.size <= MAX_AAD) {
            "credential_aead_bounds"
        }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(ciphertext)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_PLAINTEXT = 65_536 - 52 - 12 - 16
        const val MAX_AAD = 52

        /** Compatibility for existing host fixtures; an opaque key without platform proof stays unavailable. */
        fun software(key: SecretKey): DirectShellCredentialAead? {
            if (key.algorithm != "AES") return null
            val encoded = key.encoded ?: return null
            return try {
                if (encoded.size == 32) DirectShellJcaCredentialAead(key) else null
            } finally {
                encoded.fill(0)
            }
        }

        /** Called only after the Android adapter has checked KeyInfo for this exact alias/handle. */
        internal fun verifiedHandle(key: SecretKey): DirectShellCredentialAead = DirectShellJcaCredentialAead(key)
    }
}
