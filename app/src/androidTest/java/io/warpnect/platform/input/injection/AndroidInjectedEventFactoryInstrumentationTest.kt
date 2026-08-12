package io.warpnect.platform.input.injection

import android.view.InputEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionServiceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidInjectedEventFactoryInstrumentationTest {
    @Test
    fun keyFactoryUsesExplicitDisplayAndAsyncSubmissionContract() {
        val api = RecordingApi()
        val factory = AndroidInjectedEventFactory(api)
        val result = factory.submitKey(
            AndroidKeyInjectionEvent(
                stateSlot = 0,
                sourceEventTimeUs = 10,
                action = AndroidInjectionConstants.KEY_ACTION_DOWN,
                keyCode = 29,
                source = AndroidInjectionConstants.SOURCE_KEYBOARD,
                displayId = 4,
            ),
            eventTimeMs = 50,
            downTimeMs = 50,
            mode = InputInjectionMode.AsyncLowLatency,
            targetUid = -1,
        )

        assertEquals(InputInjectionServiceResult.SubmittedAsync, result)
        assertEquals(4, api.displayId)
        assertEquals(InputInjectionMode.AsyncLowLatency, api.mode)
        assertTrue(api.received)
    }

    private class RecordingApi : PrivilegedInputManagerApi {
        var displayId = -1
        var mode: InputInjectionMode? = null
        var received = false

        override fun resolve(): PrivilegedInputManagerCapabilities = PrivilegedInputManagerCapabilities(
            apiResolved = true,
            asyncInjectionSupported = true,
            displayTargetingSupported = true,
        )

        override fun inject(
            event: InputEvent,
            displayId: Int,
            mode: InputInjectionMode,
            targetUid: Int,
            actionButton: Int?,
        ): InputInjectionServiceResult {
            this.displayId = displayId
            this.mode = mode
            received = true
            return InputInjectionServiceResult.SubmittedAsync
        }
    }
}
