package io.warpnect.platform.privileged.directshell.pairing

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

internal enum class DirectShellKeyAvailability { NOT_CHECKED, AVAILABLE, MISSING, POLICY_REJECTED, UNKNOWN }
internal enum class DirectShellKeyBacking { UNKNOWN, SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX }

internal data class DirectShellKeyEvidence(
    val availability: DirectShellKeyAvailability = DirectShellKeyAvailability.NOT_CHECKED,
    val backing: DirectShellKeyBacking = DirectShellKeyBacking.UNKNOWN,
)

/** Private adapter evidence, never a key export, enrollment grant or hardware qualification. */
internal class DirectShellAndroidKeyMaterial(
    val key: SecretKey,
    val alias: String,
    val bits: Int,
    val purposes: Int,
    val modes: Set<String>,
    val paddings: Set<String>,
    val generated: Boolean,
    val authenticationRequired: Boolean,
    val backing: DirectShellKeyBacking,
) {
    override fun toString(): String = "DirectShellAndroidKeyMaterial(redacted)"
}

internal fun interface DirectShellAndroidKeyReader {
    fun read(alias: String): DirectShellAndroidKeyMaterial?
}

/** Read-only, inert until explicitly called on the operation worker. There is no provisioning/delete API. */
internal class AndroidDirectShellCredentialKeyStore(
    private val alias: String = PRODUCTION_ALIAS,
    private val reader: DirectShellAndroidKeyReader = PlatformKeyReader(),
) : DirectShellPairingWrappingKey {
    private val mutableEvidence = AtomicReference(DirectShellKeyEvidence())
    val evidence: DirectShellKeyEvidence get() = mutableEvidence.get()

    init {
        require(
            alias == PRODUCTION_ALIAS || alias.matches(Regex(TEST_ALIAS_PATTERN)),
        ) { "credential_key_owner_invalid" }
    }

    override fun existing(): SecretKey? = verified()?.key

    override fun existingAead(): DirectShellCredentialAead? =
        verified()?.let { DirectShellJcaCredentialAead.verifiedHandle(it.key) }

    private fun verified(): DirectShellAndroidKeyMaterial? = try {
        val material = reader.read(alias)
        if (material == null) {
            mutableEvidence.set(DirectShellKeyEvidence(DirectShellKeyAvailability.MISSING))
            null
        } else if (material.alias != alias || material.key.algorithm != "AES" || material.bits != 256 ||
            material.purposes != (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) ||
            material.modes != setOf(KeyProperties.BLOCK_MODE_GCM) ||
            material.paddings != setOf(KeyProperties.ENCRYPTION_PADDING_NONE) ||
            !material.generated || material.authenticationRequired
        ) {
            mutableEvidence.set(DirectShellKeyEvidence(DirectShellKeyAvailability.POLICY_REJECTED))
            null
        } else {
            mutableEvidence.set(DirectShellKeyEvidence(DirectShellKeyAvailability.AVAILABLE, material.backing))
            material
        }
    } catch (_: Exception) {
        mutableEvidence.set(DirectShellKeyEvidence(DirectShellKeyAvailability.UNKNOWN))
        null
    }

    companion object {
        const val PRODUCTION_ALIAS = "warpnect.directshell.pairing.production.aes256.v1"
        private const val TEST_ALIAS_PATTERN =
            "warpnect\\.test\\.directshell\\.pairing\\.aes256\\.v1\\." + "[a-zA-Z0-9-]{1,64}"
    }

    private class PlatformKeyReader : DirectShellAndroidKeyReader {
        override fun read(alias: String): DirectShellAndroidKeyMaterial? {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(alias)) return null
            val key = store.getKey(alias, null) as? SecretKey ?: error("credential_key_type")
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val backing = if (Build.VERSION.SDK_INT >= 31) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> DirectShellKeyBacking.SOFTWARE
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> DirectShellKeyBacking.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> DirectShellKeyBacking.STRONGBOX
                    else -> DirectShellKeyBacking.UNKNOWN
                }
            } else {
                // Do not infer hardware backing on old API levels or use a deprecated fallback.
                DirectShellKeyBacking.UNKNOWN
            }
            return DirectShellAndroidKeyMaterial(
                key, info.keystoreAlias, info.keySize, info.purposes,
                info.blockModes.toSet(), info.encryptionPaddings.toSet(),
                info.origin == KeyProperties.ORIGIN_GENERATED, info.isUserAuthenticationRequired, backing,
            )
        }
    }
}
