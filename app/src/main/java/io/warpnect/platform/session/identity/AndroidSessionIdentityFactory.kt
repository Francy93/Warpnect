package io.warpnect.platform.session.identity

import android.content.Context
import io.warpnect.platform.session.trust.AndroidTrustedPeerStorePersistence
import io.warpnect.session.identity.LocalDeviceIdentityRepository
import io.warpnect.session.identity.SecureRandomDeviceIdGenerator
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import io.warpnect.session.trust.TrustedPeerStore

/** Cold-path Android composition for RFC-005C local identity and public trusted-peer persistence. */
object AndroidSessionIdentityFactory {
    fun createLocalDeviceIdentityRepository(context: Context): LocalDeviceIdentityRepository {
        val crypto = JcaPairingCryptoProvider()
        return LocalDeviceIdentityRepository(
            deviceIdStorage = AndroidLocalDeviceIdStorage(context),
            keyProvider = AndroidDeviceIdentityKeyProvider(),
            deviceIdGenerator = SecureRandomDeviceIdGenerator(),
            fingerprintCalculator = crypto::sha256,
        )
    }

    fun createTrustedPeerStore(context: Context): TrustedPeerStore {
        val crypto = JcaPairingCryptoProvider()
        return TrustedPeerStore(AndroidTrustedPeerStorePersistence(context), crypto::sha256)
    }
}
