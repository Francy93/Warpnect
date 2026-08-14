package io.warpnect.session.identity

import io.warpnect.session.DeviceId
import java.security.SecureRandom

/**
 * Immutable byte value with value equality. It is used for public protocol values so ByteArray
 * reference equality cannot accidentally become an identity or trust decision.
 */
class ImmutableBytes private constructor(
    private val bytes: ByteArray,
) {
    val size: Int
        get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    internal fun wipe() {
        bytes.fill(0)
    }

    override fun equals(other: Any?): Boolean = other is ImmutableBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ImmutableBytes(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): ImmutableBytes = ImmutableBytes(bytes.copyOf())
    }
}

enum class IdentityKeyAlgorithm(
    val wireId: Int,
) {
    EcdsaP256Sha256(1),
    ;

    companion object {
        fun fromWireId(value: Int): IdentityKeyAlgorithm? = entries.firstOrNull { it.wireId == value }
    }
}

enum class IdentityKeySecurityLevel {
    Software,
    TrustedEnvironment,
    StrongBox,
    Unknown,
}

class IdentityPublicKey private constructor(
    private val encoded: ImmutableBytes,
) {
    val size: Int
        get() = encoded.size

    fun encodedSpki(): ByteArray = encoded.toByteArray()

    override fun equals(other: Any?): Boolean = other is IdentityPublicKey && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "IdentityPublicKey(size=$size)"

    companion object {
        const val MAX_ENCODED_BYTES: Int = 256

        fun fromSpki(encoded: ByteArray): IdentityPublicKey? =
            encoded.takeIf { it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES }?.let {
                IdentityPublicKey(ImmutableBytes.copyOf(it))
            }

        fun requireSpki(encoded: ByteArray): IdentityPublicKey = requireNotNull(fromSpki(encoded)) {
            "Identity public key must be non-empty and at most $MAX_ENCODED_BYTES bytes"
        }
    }
}

class IdentityFingerprint private constructor(
    private val digest: ImmutableBytes,
) {
    fun sha256(): ByteArray = digest.toByteArray()

    override fun equals(other: Any?): Boolean = other is IdentityFingerprint && digest == other.digest

    override fun hashCode(): Int = digest.hashCode()

    override fun toString(): String = sha256().joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val SHA256_BYTES: Int = 32

        fun fromSha256(digest: ByteArray): IdentityFingerprint? =
            digest.takeIf { it.size == SHA256_BYTES }?.let { IdentityFingerprint(ImmutableBytes.copyOf(it)) }

        fun requireSha256(digest: ByteArray): IdentityFingerprint = requireNotNull(fromSha256(digest)) {
            "Identity fingerprint must be exactly $SHA256_BYTES bytes"
        }
    }
}

data class LocalDeviceIdentity(
    val deviceId: DeviceId,
    val keyAlias: String,
    val algorithm: IdentityKeyAlgorithm,
    val publicKey: IdentityPublicKey,
    val fingerprint: IdentityFingerprint,
    val securityLevel: IdentityKeySecurityLevel,
)

enum class LocalIdentityConsistency {
    Ready,
    LocalIdentityInconsistent,
    StorageFailure,
    KeyStoreFailure,
}

sealed interface LocalDeviceIdentityResult {
    data class Ready(
        val identity: LocalDeviceIdentity,
    ) : LocalDeviceIdentityResult

    data class Inconsistent(
        val deviceIdPresent: Boolean,
        val keyPresent: Boolean,
    ) : LocalDeviceIdentityResult

    data class Failed(
        val consistency: LocalIdentityConsistency,
        val detail: String,
    ) : LocalDeviceIdentityResult
}

sealed interface DeviceIdStorageReadResult {
    data object Missing : DeviceIdStorageReadResult

    data class Value(
        val deviceId: DeviceId,
    ) : DeviceIdStorageReadResult

    data class Failed(
        val detail: String,
    ) : DeviceIdStorageReadResult
}

interface LocalDeviceIdStorage {
    fun read(): DeviceIdStorageReadResult

    fun write(deviceId: DeviceId): Boolean

    fun clear(): Boolean
}

sealed interface IdentityKeyResult<out T> {
    data class Value<T>(
        val value: T,
    ) : IdentityKeyResult<T>

    data class Failed(
        val detail: String,
    ) : IdentityKeyResult<Nothing>
}

interface DeviceIdentityKeyProvider {
    fun contains(alias: String): IdentityKeyResult<Boolean>

    fun generate(alias: String): IdentityKeyResult<IdentityPublicKey>

    fun loadPublicKey(alias: String): IdentityKeyResult<IdentityPublicKey>

    fun sign(alias: String, data: ByteArray): IdentityKeyResult<ImmutableBytes>

    fun securityLevel(alias: String): IdentityKeyResult<IdentityKeySecurityLevel>

    fun delete(alias: String): IdentityKeyResult<Unit>
}

fun interface DeviceIdGenerator {
    fun next(): DeviceId
}

/** Cryptographically strong generator for the non-zero opaque local DeviceId. */
class SecureRandomDeviceIdGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : DeviceIdGenerator {
    override fun next(): DeviceId {
        while (true) {
            val bytes = ByteArray(16).also(secureRandom::nextBytes)
            var high = 0uL
            var low = 0uL
            repeat(8) { index -> high = (high shl 8) or (bytes[index].toInt() and 0xff).toULong() }
            repeat(8) { index -> low = (low shl 8) or (bytes[index + 8].toInt() and 0xff).toULong() }
            DeviceId.fromParts(high, low)?.let { return it }
        }
    }
}

interface LocalDeviceIdentitySigner {
    val identity: LocalDeviceIdentity

    fun sign(data: ByteArray): IdentityKeyResult<ImmutableBytes>
}

/**
 * Owns the durable DeviceId plus Android-KeyStore-backed identity-key relationship. A partially
 * lost identity is deliberately reported instead of silently creating a key or DeviceId that
 * would invalidate existing peer trust bindings.
 */
class LocalDeviceIdentityRepository(
    private val deviceIdStorage: LocalDeviceIdStorage,
    private val keyProvider: DeviceIdentityKeyProvider,
    private val deviceIdGenerator: DeviceIdGenerator,
    private val fingerprintCalculator: (ByteArray) -> ByteArray,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    @Volatile
    private var cached: LocalDeviceIdentity? = null

    fun loadOrCreate(): LocalDeviceIdentityResult = synchronized(this) {
        cached?.let { return@synchronized LocalDeviceIdentityResult.Ready(it) }

        val stored = deviceIdStorage.read()
        if (stored is DeviceIdStorageReadResult.Failed) {
            return@synchronized LocalDeviceIdentityResult.Failed(
                LocalIdentityConsistency.StorageFailure,
                stored.detail,
            )
        }
        val keyPresent = when (val result = keyProvider.contains(keyAlias)) {
            is IdentityKeyResult.Value -> result.value
            is IdentityKeyResult.Failed -> {
                return@synchronized LocalDeviceIdentityResult.Failed(
                    LocalIdentityConsistency.KeyStoreFailure,
                    result.detail,
                )
            }
        }
        val deviceId = (stored as? DeviceIdStorageReadResult.Value)?.deviceId
        if (deviceId == null && !keyPresent) return@synchronized createFreshIdentity()
        if (deviceId == null || !keyPresent) {
            return@synchronized LocalDeviceIdentityResult.Inconsistent(
                deviceIdPresent = deviceId != null,
                keyPresent = keyPresent,
            )
        }
        loadExistingIdentity(deviceId)
    }

    fun signer(): LocalDeviceIdentitySigner? = synchronized(this) {
        val identity = cached
            ?: (loadOrCreate() as? LocalDeviceIdentityResult.Ready)?.identity
            ?: return@synchronized null
        object : LocalDeviceIdentitySigner {
            override val identity: LocalDeviceIdentity = identity

            override fun sign(data: ByteArray): IdentityKeyResult<ImmutableBytes> =
                keyProvider.sign(identity.keyAlias, data)
        }
    }

    /** Explicitly destructive reset; callers must clear their trusted-peer store in the callback. */
    fun resetLocalIdentityAndTrust(clearTrustedPeers: () -> Boolean): LocalDeviceIdentityResult = synchronized(this) {
        if (!clearTrustedPeers()) {
            return@synchronized LocalDeviceIdentityResult.Failed(
                LocalIdentityConsistency.StorageFailure,
                "Unable to clear trusted peers before identity reset",
            )
        }
        when (val deletion = keyProvider.delete(keyAlias)) {
            is IdentityKeyResult.Failed -> {
                return@synchronized LocalDeviceIdentityResult.Failed(
                    LocalIdentityConsistency.KeyStoreFailure,
                    deletion.detail,
                )
            }
            is IdentityKeyResult.Value -> Unit
        }
        if (!deviceIdStorage.clear()) {
            return@synchronized LocalDeviceIdentityResult.Failed(
                LocalIdentityConsistency.StorageFailure,
                "Unable to clear the local DeviceId during identity reset",
            )
        }
        cached = null
        loadOrCreate()
    }

    private fun createFreshIdentity(): LocalDeviceIdentityResult {
        val generatedKey = when (val result = keyProvider.generate(keyAlias)) {
            is IdentityKeyResult.Value -> result.value
            is IdentityKeyResult.Failed -> {
                return LocalDeviceIdentityResult.Failed(LocalIdentityConsistency.KeyStoreFailure, result.detail)
            }
        }
        val deviceId = deviceIdGenerator.next()
        if (!deviceIdStorage.write(deviceId)) {
            keyProvider.delete(keyAlias)
            return LocalDeviceIdentityResult.Failed(
                LocalIdentityConsistency.StorageFailure,
                "Unable to persist the generated local DeviceId",
            )
        }
        return loadIdentity(deviceId, generatedKey)
    }

    private fun loadExistingIdentity(deviceId: DeviceId): LocalDeviceIdentityResult =
        when (val key = keyProvider.loadPublicKey(keyAlias)) {
            is IdentityKeyResult.Value -> loadIdentity(deviceId, key.value)
            is IdentityKeyResult.Failed -> LocalDeviceIdentityResult.Failed(
                LocalIdentityConsistency.KeyStoreFailure,
                key.detail,
            )
        }

    private fun loadIdentity(deviceId: DeviceId, publicKey: IdentityPublicKey): LocalDeviceIdentityResult {
        val securityLevel = when (val level = keyProvider.securityLevel(keyAlias)) {
            is IdentityKeyResult.Value -> level.value
            is IdentityKeyResult.Failed -> IdentityKeySecurityLevel.Unknown
        }
        val fingerprint = IdentityFingerprint.fromSha256(fingerprintCalculator(publicKey.encodedSpki()))
            ?: return LocalDeviceIdentityResult.Failed(
                LocalIdentityConsistency.KeyStoreFailure,
                "Identity fingerprint provider did not return SHA-256 output",
            )
        val identity = LocalDeviceIdentity(
            deviceId = deviceId,
            keyAlias = keyAlias,
            algorithm = IdentityKeyAlgorithm.EcdsaP256Sha256,
            publicKey = publicKey,
            fingerprint = fingerprint,
            securityLevel = securityLevel,
        )
        cached = identity
        return LocalDeviceIdentityResult.Ready(identity)
    }

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "warpnect.device.identity.v1"
    }
}
