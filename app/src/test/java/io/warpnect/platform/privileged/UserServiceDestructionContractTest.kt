package io.warpnect.platform.privileged

import io.warpnect.platform.audio.capture.privileged.IPrivilegedAudioCaptureService
import io.warpnect.platform.capture.privileged.IPrivilegedCaptureService
import io.warpnect.platform.input.injection.privileged.IPrivilegedInputInjectionService
import org.junit.Assert.assertEquals
import org.junit.Test

class UserServiceDestructionContractTest {
    @Test
    fun userServicesExposeShizukuReservedDestructionTransaction() {
        assertTransactions(
            IPrivilegedCaptureService.Stub::class.java,
            "queryCapabilities" to 1,
            "startCapture" to 2,
            "updateCapture" to 3,
            "stopCapture" to 4,
            "getState" to 5,
        )
        assertTransactions(
            IPrivilegedAudioCaptureService.Stub::class.java,
            "querySystemAudioCapabilities" to 1,
            "prepareSystemAudioCapture" to 2,
            "startSystemAudioCapture" to 3,
            "stopSystemAudioCapture" to 4,
            "getSystemAudioState" to 5,
        )
        assertTransactions(
            IPrivilegedInputInjectionService.Stub::class.java,
            "getServiceVersion" to 1,
            "getCapabilities" to 2,
            "prepare" to 3,
            "startInjection" to 4,
            "stopInjection" to 5,
            "injectKey" to 6,
            "injectTouch" to 7,
            "injectPointer" to 8,
            "injectJoystick" to 9,
            "resetState" to 10,
            "getSnapshot" to 11,
        )
    }

    private fun assertTransactions(stubClass: Class<*>, vararg expected: Pair<String, Int>) {
        expected.forEach { (method, transaction) ->
            assertTransaction(stubClass, method, transaction)
        }
        assertTransaction(stubClass, "destroy", SHIZUKU_DESTROY_TRANSACTION)
    }

    private fun assertTransaction(stubClass: Class<*>, method: String, expected: Int) {
        val field = stubClass.getDeclaredField("TRANSACTION_$method")
        field.isAccessible = true
        assertEquals(expected, field.getInt(null))
    }

    private companion object {
        const val SHIZUKU_DESTROY_TRANSACTION = 16_777_115
    }
}
