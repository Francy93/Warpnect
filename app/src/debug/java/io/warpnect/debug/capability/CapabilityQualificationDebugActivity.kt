package io.warpnect.debug.capability

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.warpnect.platform.video.decoder.AndroidVideoDecoderDiscovery
import io.warpnect.platform.video.decoder.SharedPreferencesLegacyDecoderQualificationStore
import io.warpnect.video.decoder.VideoDecoderConfig

/** Debug-only, exact-key cache control for a reproducible legacy decoder qualification miss. */
class CapabilityQualificationDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread(::clearActiveLegacyDecoderQualification, "WarpnectCapabilityDebug").start()
    }

    private fun clearActiveLegacyDecoderQualification() {
        val config = VideoDecoderConfig(
            width = 1280,
            height = 720,
            expectedFrameRate = 60,
            configGeneration = 1,
            codecSpecificData = listOf(byteArrayOf(1)),
        )
        val key = AndroidVideoDecoderDiscovery()
            .legacyQualificationKeyForDebug(config)
        val store = SharedPreferencesLegacyDecoderQualificationStore(applicationContext)
        val removed = key?.let(store::removeForDebug) ?: false
        Log.i(
            TAG,
            "CAPABILITY_DEBUG_CACHE_INVALIDATED cache=legacy_decoder_exact_key " +
                "fixture=rfc002i-avc-720p60-full-v2 result=$removed",
        )
        finish()
    }

    private companion object {
        const val TAG = "WarpnectCapabilityDebug"
    }
}
