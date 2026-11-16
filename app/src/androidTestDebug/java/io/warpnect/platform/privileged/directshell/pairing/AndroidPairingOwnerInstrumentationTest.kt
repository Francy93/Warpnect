package io.warpnect.platform.privileged.directshell.pairing

import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 30, maxSdkVersion = 30)
class AndroidPairingOwnerInstrumentationTest {
    @Test fun explicitAndroidOwnerUsesPlatformClockAndDrainsSyntheticPairing() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires the isolated physical gate package", target.packageName == "io.warpnect.af30gate")
        AndroidPairingStorageFixture().use { fixture ->
            val identity = AndroidPairingStorageFixture.identity
            val remote = AndroidPairingStorageFixture.newIdentity()
            fixture.provision()
            AndroidCompositionPeerFixture(serverIdentity = remote, expectedClientIdentity = identity).use { peer ->
                val owner = AndroidDirectShellPairingOwner.forSyntheticFixture(
                    ::readiness,
                    { DirectShellPairingEnvironment(true, true, 1, 0) },
                    { fixture.adapter().storage },
                    { DirectShellSyntheticPairingComposition.IdentityBorrow(identity) { true } },
                    { peer.endpoint },
                )
                owner.use {
                    owner.onResume()
                    assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
                    val ticket = requireNotNull(owner.request(1))
                    assertTrue(owner.submitCode(ticket, AndroidCompositionPeerFixture.CODE.toCharArray()))
                    val completed = runBlocking { withTimeout(15_000) { owner.state.first { it.drained } } }
                    assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, completed.phase)
                    assertEquals(DirectShellPairingPublication.PUBLISHED, completed.publication)
                    assertEquals(DirectShellPairingDurability.SYNC_CONFIRMED, completed.durability)
                    assertEquals(DirectShellPairingExternalEffect.POSSIBLE, completed.externalEffect)
                    assertTrue(Files.exists(fixture.record))
                    assertNull(owner.request(1))
                }
                assertTrue(owner.awaitObserverTermination())
                fixture.emit("android_owner=platform_clock_and_handler operation_d=verified observer_terminated=true")
            }
        }
    }

    private fun readiness(): HostReadinessUiState {
        val reason = PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED
        val runtime = PrivilegedRuntimeStateSnapshot(
            1,
            1,
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            ),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_SHELL,
                PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
                reason,
            ),
            PrivilegedRuntimeAvailability(PrivilegedRuntimeKind.SHIZUKU, PrivilegedRuntimeAvailabilityState.UNKNOWN),
        )
        return HostReadinessUiState(
            HostReadinessState.AuthorizationRequired(1, runtime, reason),
            hostActionVisible = true,
        )
    }
}
