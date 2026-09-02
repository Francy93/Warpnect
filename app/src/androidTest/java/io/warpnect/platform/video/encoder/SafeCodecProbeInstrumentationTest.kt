package io.warpnect.platform.video.encoder

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.video.encoder.VideoEncoderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hardware evidence for app-UID probe isolation and its process-local cache. */
@RunWith(AndroidJUnit4::class)
class SafeCodecProbeInstrumentationTest {
    @Test
    fun strictCbrQualificationIsBoundedAndCached() {
        val decisions = mutableListOf<CbrCapabilityDecision>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val discovery = AndroidVideoEncoderDiscovery(
            CbrCapabilityFallback(
                CachedExactVideoEncoderCapabilityProbe(
                    ServiceBackedExactVideoEncoderCapabilityProbe(
                        AndroidCodecProbeServiceCaller(context),
                    ),
                ),
            ),
            debugObserver = object : VideoEncoderCbrCapabilityDebugObserver {
                override fun onDecision(decision: CbrCapabilityDecision) {
                    decisions += decision
                }
            },
        )
        val request = VideoEncoderRequest(
            width = 1280,
            height = 720,
            frameRate = 60,
            bitrateBps = 8_000_000,
            iFrameIntervalSeconds = 1f,
        )

        val first = discovery.query(request)
        val firstProbeDecisions = decisions.filter { it.source == CbrCapabilityDecisionSource.ActiveProbe }
        assertFalse("strict CBR eligibility bypassed the safe probe", firstProbeDecisions.isEmpty())
        decisions.clear()

        val second = discovery.query(request)
        assertEquals(first.error, second.error)
        assertTrue(
            "second exact query should use the process-local probe result",
            decisions.any { it.source == CbrCapabilityDecisionSource.ActiveProbeCache },
        )
        assertFalse(
            "second exact query must not launch another codec process",
            decisions.any { it.source == CbrCapabilityDecisionSource.ActiveProbe },
        )

        val firstResult = firstProbeDecisions.first().probeResult
        Log.i(
            TAG,
            "device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
                "firstResult=$firstResult videoAvailable=${first.isSupported} error=${first.error} " +
                "selectedCodec=${first.selectedCodec?.codecName} candidates=${first.candidates.map { it.codecName }}",
        )
    }

    private companion object {
        const val TAG = "WarpnectCodecProbe"
    }
}
