package io.warpnect.platform.privileged.directshell.pairing

import io.github.muntashirakon.adb.DebugSyntheticPairingPeerAuth
import io.github.muntashirakon.adb.PairingAuthFixture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DebugSyntheticPairingPeerAuthTest {
    @Test fun realPinnedAliceAndDebugBobExchangeAuthenticatedBytes() {
        val exporter = ByteArray(64) { it.toByte() }
        val code = "314159".toCharArray()
        PairingAuthFixture(true, code, exporter).use { alice ->
            DebugSyntheticPairingPeerAuth(code, exporter).use { bob ->
                assertTrue(bob.initialize(alice.message()))
                assertTrue(alice.initialize(bob.message()))
                val plain = byteArrayOf(4, 2, 0, 1)
                assertArrayEquals(plain, bob.decrypt(alice.encrypt(plain)))
                assertArrayEquals(plain, alice.decrypt(bob.encrypt(plain)))
            }
        }
        exporter.fill(0)
        code.fill('\u0000')
    }

    @Test fun wrongCodeCannotAuthenticateFixturePayload() {
        val exporter = ByteArray(64)
        PairingAuthFixture(true, "314159".toCharArray(), exporter).use { alice ->
            DebugSyntheticPairingPeerAuth("271828".toCharArray(), exporter).use { bob ->
                assertTrue(bob.initialize(alice.message()))
                assertTrue(alice.initialize(bob.message()))
                assertNull(bob.decrypt(alice.encrypt(byteArrayOf(1, 2, 3))))
            }
        }
    }

    @Test fun invalidInputFailsClosedWithBoundedSafeMessage() {
        try {
            DebugSyntheticPairingPeerAuth("314159".toCharArray(), ByteArray(63))
            fail("expected_fixture_input_rejection")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message == "fixture_pake_input_invalid")
            assertFalse(failure.toString().contains("314159"))
        }
    }

    @Test fun closeIsIdempotentAndReleasedContextIsUnusable() {
        val bob = DebugSyntheticPairingPeerAuth("314159".toCharArray(), ByteArray(64))
        bob.close()
        bob.close()
        try {
            bob.message()
            fail("expected_fixture_release_rejection")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message == "fixture_pake_closed")
            assertFalse(bob.toString().contains("314159"))
        }
    }
}
