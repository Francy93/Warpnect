package io.warpnect.session.trust

import io.warpnect.session.DeviceId
import io.warpnect.session.identity.IdentityFingerprint
import io.warpnect.session.identity.IdentityKeyAlgorithm
import io.warpnect.session.identity.IdentityPublicKey

const val TRUSTED_PEER_STORE_SCHEMA_VERSION: Int = 1
const val DEFAULT_MAX_TRUSTED_PEERS: Int = 128

data class TrustedPeerRecord(
    val peerDeviceId: DeviceId,
    val identityKeyAlgorithm: IdentityKeyAlgorithm,
    val identityPublicKey: IdentityPublicKey,
    val identityFingerprint: IdentityFingerprint,
    val pairedAtWallClockMs: Long,
    val lastVerifiedAtWallClockMs: Long,
    val remoteAliasAtPairing: String? = null,
) {
    init {
        require(remoteAliasAtPairing == null || remoteAliasAtPairing.length <= MAX_STORED_ALIAS_LENGTH) {
            "Trusted-peer alias exceeds $MAX_STORED_ALIAS_LENGTH characters"
        }
    }

    companion object {
        const val MAX_STORED_ALIAS_LENGTH: Int = 64
    }
}

enum class TrustStoreError {
    None,
    AlreadyTrusted,
    PeerIdentityKeyChanged,
    IdentityBindingConflict,
    TrustStoreCapacityExceeded,
    TrustStoreCorrupt,
    PersistenceFailure,
    InvalidPeerRecord,
}

data class TrustedPeerStoreResult(
    val error: TrustStoreError,
    val record: TrustedPeerRecord? = null,
) {
    val isSuccess: Boolean
        get() = error == TrustStoreError.None
}

sealed interface TrustedPeerStoreLoadResult {
    data object Empty : TrustedPeerStoreLoadResult

    data class Records(
        val records: List<TrustedPeerRecord>,
    ) : TrustedPeerStoreLoadResult

    data class Corrupt(
        val detail: String,
    ) : TrustedPeerStoreLoadResult

    data class Failed(
        val detail: String,
    ) : TrustedPeerStoreLoadResult
}

/** Persistence implementations must atomically replace the complete bounded record set. */
interface TrustedPeerStorePersistence {
    fun load(): TrustedPeerStoreLoadResult

    fun replace(records: List<TrustedPeerRecord>): Boolean
}

/** Small in-memory persistence used by pure JVM protocol tests. */
class InMemoryTrustedPeerStorePersistence(
    initialRecords: List<TrustedPeerRecord> = emptyList(),
) : TrustedPeerStorePersistence {
    private var stored = initialRecords.toList()

    override fun load(): TrustedPeerStoreLoadResult =
        if (stored.isEmpty()) TrustedPeerStoreLoadResult.Empty else TrustedPeerStoreLoadResult.Records(stored.toList())

    override fun replace(records: List<TrustedPeerRecord>): Boolean {
        stored = records.toList()
        return true
    }
}

/**
 * Bounded persistent binding of a peer DeviceId to one exact long-term identity public key.
 * It intentionally stores no private key and no symmetric pairing secret.
 */
class TrustedPeerStore(
    private val persistence: TrustedPeerStorePersistence,
    private val fingerprintCalculator: (ByteArray) -> ByteArray,
    private val maxTrustedPeers: Int = DEFAULT_MAX_TRUSTED_PEERS,
) {
    private val lock = Any()
    private val recordsByDeviceId = LinkedHashMap<DeviceId, TrustedPeerRecord>()
    private var initializationError: TrustStoreError = TrustStoreError.None

    init {
        require(maxTrustedPeers in 1..DEFAULT_MAX_TRUSTED_PEERS) {
            "Trusted peer capacity must be within 1..$DEFAULT_MAX_TRUSTED_PEERS"
        }
        when (val loaded = persistence.load()) {
            TrustedPeerStoreLoadResult.Empty -> Unit
            is TrustedPeerStoreLoadResult.Records -> {
                if (loaded.records.size > maxTrustedPeers || !validateAll(loaded.records)) {
                    initializationError = TrustStoreError.TrustStoreCorrupt
                } else {
                    loaded.records.forEach { record -> recordsByDeviceId[record.peerDeviceId] = record }
                }
            }
            is TrustedPeerStoreLoadResult.Corrupt -> initializationError = TrustStoreError.TrustStoreCorrupt
            is TrustedPeerStoreLoadResult.Failed -> initializationError = TrustStoreError.PersistenceFailure
        }
    }

    fun bind(record: TrustedPeerRecord): TrustedPeerStoreResult = synchronized(lock) {
        initializationError.takeUnless { it == TrustStoreError.None }?.let {
            return@synchronized TrustedPeerStoreResult(
                it,
            )
        }
        if (!isValid(record)) return@synchronized TrustedPeerStoreResult(TrustStoreError.InvalidPeerRecord)

        val existingForDevice = recordsByDeviceId[record.peerDeviceId]
        if (existingForDevice != null) {
            return@synchronized when {
                existingForDevice.identityPublicKey == record.identityPublicKey ->
                    TrustedPeerStoreResult(TrustStoreError.AlreadyTrusted, existingForDevice)
                else -> TrustedPeerStoreResult(TrustStoreError.PeerIdentityKeyChanged, existingForDevice)
            }
        }
        val existingForKey = recordsByDeviceId.values.firstOrNull {
            it.identityFingerprint == record.identityFingerprint
        }
        if (existingForKey != null) {
            return@synchronized TrustedPeerStoreResult(TrustStoreError.IdentityBindingConflict, existingForKey)
        }
        if (recordsByDeviceId.size >= maxTrustedPeers) {
            return@synchronized TrustedPeerStoreResult(TrustStoreError.TrustStoreCapacityExceeded)
        }

        val replacement = recordsByDeviceId.values + record
        if (!persistence.replace(replacement)) {
            return@synchronized TrustedPeerStoreResult(TrustStoreError.PersistenceFailure)
        }
        recordsByDeviceId[record.peerDeviceId] = record
        TrustedPeerStoreResult(TrustStoreError.None, record)
    }

    fun validateBinding(peerDeviceId: DeviceId, identityPublicKey: IdentityPublicKey): TrustStoreError =
        synchronized(lock) {
            initializationError.takeUnless { it == TrustStoreError.None }?.let { return@synchronized it }
            val existingForDevice = recordsByDeviceId[peerDeviceId]
            if (existingForDevice != null) {
                return@synchronized if (existingForDevice.identityPublicKey == identityPublicKey) {
                    TrustStoreError.AlreadyTrusted
                } else {
                    TrustStoreError.PeerIdentityKeyChanged
                }
            }
            val fingerprint = fingerprint(identityPublicKey) ?: return@synchronized TrustStoreError.InvalidPeerRecord
            if (recordsByDeviceId.values.any { it.identityFingerprint == fingerprint }) {
                TrustStoreError.IdentityBindingConflict
            } else {
                TrustStoreError.None
            }
        }

    fun findTrustedPeer(deviceId: DeviceId): TrustedPeerRecord? = synchronized(lock) {
        recordsByDeviceId[deviceId]
    }

    fun findByFingerprint(fingerprint: IdentityFingerprint): TrustedPeerRecord? = synchronized(lock) {
        recordsByDeviceId.values.firstOrNull { it.identityFingerprint == fingerprint }
    }

    fun listTrustedPeers(): List<TrustedPeerRecord> = synchronized(lock) {
        recordsByDeviceId.values.toList()
    }

    fun forgetTrustedPeer(deviceId: DeviceId): TrustedPeerStoreResult = synchronized(lock) {
        initializationError.takeUnless { it == TrustStoreError.None }?.let {
            return@synchronized TrustedPeerStoreResult(
                it,
            )
        }
        if (!recordsByDeviceId.containsKey(deviceId)) return@synchronized TrustedPeerStoreResult(TrustStoreError.None)
        val replacement = recordsByDeviceId.values.filterNot { it.peerDeviceId == deviceId }
        if (!persistence.replace(
                replacement,
            )
        ) {
            return@synchronized TrustedPeerStoreResult(TrustStoreError.PersistenceFailure)
        }
        recordsByDeviceId.remove(deviceId)
        TrustedPeerStoreResult(TrustStoreError.None)
    }

    fun clear(): Boolean = synchronized(lock) {
        if (!persistence.replace(emptyList())) return@synchronized false
        recordsByDeviceId.clear()
        initializationError = TrustStoreError.None
        true
    }

    fun count(): Int = synchronized(lock) { recordsByDeviceId.size }

    private fun validateAll(records: List<TrustedPeerRecord>): Boolean {
        val deviceIds = HashSet<DeviceId>()
        val fingerprints = HashSet<IdentityFingerprint>()
        return records.all { record ->
            deviceIds.add(record.peerDeviceId) && fingerprints.add(record.identityFingerprint) && isValid(record)
        }
    }

    private fun isValid(record: TrustedPeerRecord): Boolean =
        record.identityKeyAlgorithm == IdentityKeyAlgorithm.EcdsaP256Sha256 &&
            fingerprint(record.identityPublicKey) == record.identityFingerprint &&
            record.pairedAtWallClockMs >= 0L &&
            record.lastVerifiedAtWallClockMs >= record.pairedAtWallClockMs &&
            (
                record.remoteAliasAtPairing == null ||
                    record.remoteAliasAtPairing.length <= TrustedPeerRecord.MAX_STORED_ALIAS_LENGTH
                )

    private fun fingerprint(key: IdentityPublicKey): IdentityFingerprint? =
        IdentityFingerprint.fromSha256(fingerprintCalculator(key.encodedSpki()))
}
