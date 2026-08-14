package io.warpnect.session.trust

import io.warpnect.session.DeviceId
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import io.warpnect.session.pairing.JcaSoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedPeerStoreTest {
    private val crypto = JcaPairingCryptoProvider()

    @Test
    fun storesOneExactBindingAndRejectsIdentityChanges() {
        val store = store()
        val first = record(1u)
        assertTrue(store.bind(first).isSuccess)
        assertEquals(TrustStoreError.AlreadyTrusted, store.bind(first).error)

        val changedKey = record(1u)
        assertEquals(TrustStoreError.PeerIdentityKeyChanged, store.bind(changedKey).error)

        val sameKeyDifferentDevice = first.copy(peerDeviceId = device(2u))
        assertEquals(TrustStoreError.IdentityBindingConflict, store.bind(sameKeyDifferentDevice).error)
        assertEquals(1, store.count())
    }

    @Test
    fun capacityIsBoundedAndForgetIsAtomicReplacement() {
        val persistence = InMemoryTrustedPeerStorePersistence()
        val store = TrustedPeerStore(persistence, crypto::sha256, maxTrustedPeers = 1)
        val first = record(1u)
        assertTrue(store.bind(first).isSuccess)
        assertEquals(TrustStoreError.TrustStoreCapacityExceeded, store.bind(record(2u)).error)
        assertTrue(store.forgetTrustedPeer(first.peerDeviceId).isSuccess)
        assertNull(store.findTrustedPeer(first.peerDeviceId))
        assertTrue(store.bind(record(2u)).isSuccess)
    }

    @Test
    fun corruptPublicKeyFingerprintFailsClosedAtLoad() {
        val record = record(1u)
        val corrupt = record.copy(
            identityFingerprint = io.warpnect.session.identity.IdentityFingerprint.requireSha256(ByteArray(32) { 7 }),
        )
        val store = TrustedPeerStore(InMemoryTrustedPeerStorePersistence(listOf(corrupt)), crypto::sha256)
        assertEquals(
            TrustStoreError.TrustStoreCorrupt,
            store.validateBinding(record.peerDeviceId, record.identityPublicKey),
        )
        assertTrue(store.clear())
        assertEquals(TrustStoreError.None, store.validateBinding(record.peerDeviceId, record.identityPublicKey))
    }

    private fun store(): TrustedPeerStore = TrustedPeerStore(InMemoryTrustedPeerStorePersistence(), crypto::sha256)

    private fun record(value: ULong): TrustedPeerRecord {
        val signer = JcaSoftwareIdentitySigner.generate(device(value), crypto)
        return TrustedPeerRecord(
            peerDeviceId = signer.identity.deviceId,
            identityKeyAlgorithm = signer.identity.algorithm,
            identityPublicKey = signer.identity.publicKey,
            identityFingerprint = signer.identity.fingerprint,
            pairedAtWallClockMs = 10L,
            lastVerifiedAtWallClockMs = 10L,
            remoteAliasAtPairing = "Untrusted presentation alias",
        )
    }

    private fun device(value: ULong): DeviceId = DeviceId.requireValid(0u, value)
}
