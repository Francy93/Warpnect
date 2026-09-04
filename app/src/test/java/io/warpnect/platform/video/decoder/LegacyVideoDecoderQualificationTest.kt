package io.warpnect.platform.video.decoder

import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderQualification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LegacyVideoDecoderQualificationTest {
    private val config = VideoDecoderConfig(
        width = 1280,
        height = 720,
        expectedFrameRate = 60,
        configGeneration = 1,
        codecSpecificData = listOf(byteArrayOf(1)),
    )

    @Test
    fun passIsCachedWithoutSecondProbe() {
        val caller = RecordingCaller(LegacyDecoderProbeResult.Pass)
        val qualifier = CachedLegacyVideoDecoderQualifier(caller, MemoryStore(), ::key)

        assertEquals(LegacyDecoderQualificationOutcome.Pass, qualifier.qualify(config, "legacy-avc").outcome)
        val cached = qualifier.qualify(config, "legacy-avc")

        assertEquals(LegacyDecoderQualificationOutcome.Pass, cached.outcome)
        assertEquals(LegacyDecoderQualificationSource.PersistentCache, cached.source)
        assertEquals(1, caller.calls)
    }

    @Test
    fun processDeathIsQuarantinedWithoutSecondProbe() {
        val caller = RecordingCaller(LegacyDecoderProbeResult.ProbeProcessDied)
        val qualifier = CachedLegacyVideoDecoderQualifier(caller, MemoryStore(), ::key)

        assertEquals(LegacyDecoderQualificationOutcome.Inconclusive, qualifier.qualify(config, "legacy-avc").outcome)
        val quarantined = qualifier.qualify(config, "legacy-avc")

        assertEquals(LegacyDecoderQualificationSource.CurrentProcessQuarantine, quarantined.source)
        assertEquals(1, caller.calls)
    }

    @Test
    fun persistentExactKeyResultSurvivesAQualifierRecreation() {
        val store = MemoryStore()
        val initialCaller = RecordingCaller(LegacyDecoderProbeResult.InsufficientPerformance)
        CachedLegacyVideoDecoderQualifier(initialCaller, store, ::key).qualify(config, "legacy-avc")
        val secondCaller = RecordingCaller(LegacyDecoderProbeResult.Pass)

        val result = CachedLegacyVideoDecoderQualifier(secondCaller, store, ::key).qualify(config, "legacy-avc")

        assertEquals(LegacyDecoderQualificationOutcome.Fail, result.outcome)
        assertEquals(LegacyDecoderQualificationSource.PersistentCache, result.source)
        assertEquals(0, secondCaller.calls)
    }

    @Test
    fun failedQualificationIsPersistedWithoutASecondProbe() {
        val caller = RecordingCaller(LegacyDecoderProbeResult.ConfigureFailure)
        val qualifier = CachedLegacyVideoDecoderQualifier(caller, MemoryStore(), ::key)

        assertEquals(LegacyDecoderQualificationOutcome.Fail, qualifier.qualify(config, "legacy-avc").outcome)
        val cached = qualifier.qualify(config, "legacy-avc")

        assertEquals(LegacyDecoderQualificationOutcome.Fail, cached.outcome)
        assertEquals(LegacyDecoderQualificationSource.PersistentCache, cached.source)
        assertEquals(1, caller.calls)
    }

    @Test
    fun timeoutIsPersistedAndQuarantinedWithoutASecondProbe() {
        val caller = RecordingCaller(LegacyDecoderProbeResult.ProbeTimedOut)
        val qualifier = CachedLegacyVideoDecoderQualifier(caller, MemoryStore(), ::key)

        assertEquals(LegacyDecoderQualificationOutcome.Inconclusive, qualifier.qualify(config, "legacy-avc").outcome)
        val quarantined = qualifier.qualify(config, "legacy-avc")

        assertEquals(LegacyDecoderQualificationSource.CurrentProcessQuarantine, quarantined.source)
        assertEquals(1, caller.calls)
    }

    @Test
    fun serviceUnavailableIsPersistedAsInconclusive() {
        val caller = RecordingCaller(LegacyDecoderProbeResult.ProbeServiceUnavailable)
        val qualifier = CachedLegacyVideoDecoderQualifier(caller, MemoryStore(), ::key)

        val decision = qualifier.qualify(config, "legacy-avc")

        assertEquals(LegacyDecoderQualificationOutcome.Inconclusive, decision.outcome)
        assertEquals(VideoDecoderQualification.ActiveInconclusive, decision.qualificationState())
    }

    @Test
    fun cacheKeyChangesForEveryQualificationIdentityDimension() {
        val base = key("legacy-avc")
        val variants = listOf(
            base.copy(algorithmVersion = base.algorithmVersion + 1),
            base.copy(fixtureId = "next-fixture"),
            base.copy(fixtureSha256 = "different"),
            base.copy(videoProfileVersion = "next-profile"),
            base.copy(codecName = "other-codec"),
            base.copy(buildFingerprint = "other-build"),
            base.copy(mediaRuntimeVersion = "next-runtime"),
        )

        variants.forEach { variant -> assertNotEquals(base.storageKey, variant.storageKey) }
    }

    private fun key(codecName: String) = LegacyDecoderQualificationKey(
        algorithmVersion = 1,
        fixtureId = "fixture",
        fixtureSha256 = "hash",
        videoProfileVersion = "profile",
        codecName = codecName,
        buildFingerprint = "build",
        mediaRuntimeVersion = "runtime",
    )

    private class RecordingCaller(
        private val result: LegacyDecoderProbeResult,
    ) : LegacyDecoderProbeServiceCaller {
        var calls = 0

        override fun probe(codecName: String, algorithmVersion: Int): LegacyDecoderProbeResult {
            calls += 1
            return result
        }
    }

    private class MemoryStore : LegacyDecoderQualificationStore {
        private val values = mutableMapOf<LegacyDecoderQualificationKey, LegacyDecoderProbeResult>()

        override fun read(key: LegacyDecoderQualificationKey): LegacyDecoderProbeResult? = values[key]

        override fun write(key: LegacyDecoderQualificationKey, result: LegacyDecoderProbeResult) {
            values[key] = result
        }
    }
}
