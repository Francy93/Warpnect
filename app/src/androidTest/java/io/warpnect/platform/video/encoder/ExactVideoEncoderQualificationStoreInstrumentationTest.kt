package io.warpnect.platform.video.encoder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.video.encoder.VideoEncoderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExactVideoEncoderQualificationStoreInstrumentationTest {
    @Test
    fun supportedExactRecordSurvivesAStoreRecreationAndMalformedRecordIsAMiss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = ExactVideoEncoderCapabilityKey.from(
            codecName = "instrumentation.exact.encoder",
            request = VideoEncoderRequest(
                width = 1280,
                height = 720,
                frameRate = 60,
                bitrateBps = 8_000_000,
                iFrameIntervalSeconds = 1f,
            ),
        )
        val store = SharedPreferencesExactVideoEncoderQualificationStore(context)
        store.removeForDebug(key)
        try {
            store.write(key, ExactVideoEncoderCapabilityProbeResult.Supported)
            assertEquals(
                ExactVideoEncoderCapabilityProbeResult.Supported,
                SharedPreferencesExactVideoEncoderQualificationStore(context).read(key),
            )

            context.getSharedPreferences("exact_video_encoder_qualification", 0)
                .edit()
                .putInt(key.storageKey, Int.MAX_VALUE)
                .commit()
            assertNull(SharedPreferencesExactVideoEncoderQualificationStore(context).read(key))
        } finally {
            store.removeForDebug(key)
        }
    }
}
