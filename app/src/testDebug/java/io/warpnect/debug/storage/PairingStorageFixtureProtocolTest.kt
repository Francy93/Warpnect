package io.warpnect.debug.storage

import io.warpnect.platform.privileged.directshell.pairing.AndroidDirectShellCredentialKeyStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStorageFixtureProtocolTest {
    @Test fun canonicalUuidOnly() {
        assertTrue(PairingStorageFixtureProtocol.validToken("00000000-0000-0000-0000-000000000001"))
        assertFalse(PairingStorageFixtureProtocol.validToken(null))
        assertFalse(PairingStorageFixtureProtocol.validToken("../directshell-pairing-v1"))
        assertFalse(PairingStorageFixtureProtocol.validToken("00000000-0-0-0-1"))
        assertFalse(PairingStorageFixtureProtocol.validToken("00000000-0000-0000-0000-00000000000G"))
    }

    @Test fun finiteProtocolOnly() {
        assertFalse(PairingStorageFixtureProtocol.allowed(0))
        (1..9).forEach { assertTrue(PairingStorageFixtureProtocol.allowed(it)) }
        assertFalse(PairingStorageFixtureProtocol.allowed(10))
        assertFalse(PairingStorageFixtureProtocol.allowed(Int.MAX_VALUE))
    }

    @Test fun processSuffixUsesAndroidIdentifierCharacters() {
        val parts = PairingStorageFixtureProtocol.PROCESS.split(':')
        assertTrue(parts.size == 2 && parts[0] == "io.warpnect")
        assertTrue(parts[1].matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")))
        assertFalse(parts[1].contains('-'))
    }

    @Test fun syntheticAliasUsesExistingKeyOwnershipPolicyWithoutReadingOrProvisioning() {
        var reads = 0
        val alias = PairingStorageFixtureProtocol.ALIAS + "00000000-0000-0000-0000-000000000001"
        assertFalse(alias == AndroidDirectShellCredentialKeyStore.PRODUCTION_ALIAS)
        AndroidDirectShellCredentialKeyStore(alias) {
            reads++
            null
        }
        assertTrue(reads == 0)
    }
}
