package io.warpnect.platform.video.encoder

import io.warpnect.video.encoder.VideoEncoderRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CbrCapabilityFallbackTest {
    private val key = ExactVideoEncoderCapabilityKey.from(
        codecName = "vendor.avc.encoder",
        request = VideoEncoderRequest(
            width = 1280,
            height = 720,
            frameRate = 60,
            bitrateBps = 8_000_000,
            iFrameIntervalSeconds = 1f,
        ),
    )

    @Test
    fun metadataSupportDoesNotInvokeActiveProbe() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = true,
            allOtherRequirementsSupported = true,
            key = key,
        )

        assertTrue(decision.supported)
        assertEquals(CbrCapabilityDecisionSource.Metadata, decision.source)
        assertEquals(0, probe.calls)
    }

    @Test
    fun metadataFalseNegativeUsesSuccessfulExactProbe() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = false,
            allOtherRequirementsSupported = true,
            key = key,
        )

        assertTrue(decision.supported)
        assertEquals(CbrCapabilityDecisionSource.ActiveProbe, decision.source)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.Supported, decision.probeResult)
        assertEquals(1, probe.calls)
    }

    @Test
    fun failedExactProbeRetainsStrictCbrUnavailability() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = false,
            allOtherRequirementsSupported = true,
            key = key,
        )

        assertFalse(decision.supported)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed, decision.probeResult)
        assertEquals(1, probe.calls)
    }

    @Test
    fun otherCapabilityFailureDoesNotInstantiateCodec() {
        val probe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = CbrCapabilityFallback(probe).resolve(
            metadataSupported = false,
            allOtherRequirementsSupported = false,
            key = key,
        )

        assertFalse(decision.supported)
        assertEquals(CbrCapabilityDecisionSource.NotEligible, decision.source)
        assertEquals(0, probe.calls)
    }

    @Test
    fun exactProbeResultIsCachedForTheCurrentProcessLifetime() {
        val rawProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)
        val probe = CachedExactVideoEncoderCapabilityProbe(rawProbe)
        val fallback = CbrCapabilityFallback(probe)

        val first = fallback.resolve(false, true, key)
        val second = fallback.resolve(false, true, key)

        assertTrue(first.supported)
        assertTrue(second.supported)
        assertEquals(CbrCapabilityDecisionSource.ActiveProbe, first.source)
        assertEquals(CbrCapabilityDecisionSource.CurrentProcessProbeCache, second.source)
        assertEquals(1, rawProbe.calls)
    }

    @Test
    fun failedExactProbeIsAlsoCached() {
        val rawProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed)
        val probe = CachedExactVideoEncoderCapabilityProbe(rawProbe)
        val fallback = CbrCapabilityFallback(probe)

        val first = fallback.resolve(false, true, key)
        val second = fallback.resolve(false, true, key)

        assertFalse(first.supported)
        assertFalse(second.supported)
        assertEquals(CbrCapabilityDecisionSource.CurrentProcessProbeCache, second.source)
        assertEquals(1, rawProbe.calls)
    }

    @Test
    fun probeProcessDeathIsCachedAndQuarantinesFurtherColdProbes() {
        val rawProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied)
        val probe = CachedExactVideoEncoderCapabilityProbe(rawProbe)
        val fallback = CbrCapabilityFallback(probe)
        val otherKey = key.copy(codecName = "other.vendor.avc.encoder")

        val first = fallback.resolve(false, true, key)
        val same = fallback.resolve(false, true, key)
        val other = fallback.resolve(false, true, otherKey)

        assertFalse(first.supported)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied, first.probeResult)
        assertEquals(CbrCapabilityDecisionSource.CurrentProcessProbeCache, same.source)
        assertEquals(CbrCapabilityDecisionSource.CurrentProcessQuarantine, other.source)
        assertEquals(ExactVideoEncoderCapabilityProbeResult.ProbeProcessDied, other.probeResult)
        assertEquals(1, rawProbe.calls)
    }

    @Test
    fun supportedExactResultIsReusedByANewProcessCacheWithoutAProbe() {
        val store = RecordingStore()
        val initialProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)
        val initial = CachedExactVideoEncoderCapabilityProbe(initialProbe, store)

        val first = initial.probe(key)

        val restartedProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.ConfigureFailed)
        val restarted = CachedExactVideoEncoderCapabilityProbe(restartedProbe, store)
        val second = restarted.probe(key)

        assertEquals(CbrCapabilityDecisionSource.ActiveProbe, first.source)
        assertTrue(first.supported)
        assertEquals(CbrCapabilityDecisionSource.PersistentProbeCache, second.source)
        assertTrue(second.supported)
        assertEquals(1, initialProbe.calls)
        assertEquals(0, restartedProbe.calls)
    }

    @Test
    fun exactKeyChangeDoesNotReusePersistedEvidence() {
        val store = RecordingStore()
        CachedExactVideoEncoderCapabilityProbe(
            RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported),
            store,
        ).probe(key)
        val changed = key.copy(
            qualificationAlgorithmVersion = key.qualificationAlgorithmVersion + 1,
        )
        val restartedProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)

        val decision = CachedExactVideoEncoderCapabilityProbe(restartedProbe, store).probe(changed)

        assertNotEquals(key.storageKey, changed.storageKey)
        assertEquals(CbrCapabilityDecisionSource.ActiveProbe, decision.source)
        assertEquals(1, restartedProbe.calls)
    }

    @Test
    fun everyAuthoritativeEncoderCompatibilityInputChangesTheStorageKey() {
        val changedKeys = listOf(
            key.copy(probeWorkloadVersion = "other-workload"),
            key.copy(targetProfileVersion = "other-profile"),
            key.copy(codecName = "other.codec"),
            key.copy(mimeType = "video/other"),
            key.copy(width = key.width + 2),
            key.copy(height = key.height + 2),
            key.copy(frameRate = key.frameRate - 1),
            key.copy(bitrateBps = key.bitrateBps - 1),
            key.copy(bitrateMode = "Other"),
            key.copy(iFrameIntervalBits = key.iFrameIntervalBits + 1),
            key.copy(buildFingerprint = "other-fingerprint"),
            key.copy(mediaRuntimeCompatibilityVersion = "other-media-runtime"),
        )

        changedKeys.forEach { changed -> assertNotEquals(key.storageKey, changed.storageKey) }
    }

    @Test
    fun transientProbeFailureIsNotPersistedAcrossProcessRestart() {
        val store = RecordingStore()
        val initialProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.ProbeTimedOut)
        val initial = CachedExactVideoEncoderCapabilityProbe(initialProbe, store)

        initial.probe(key)

        val restartedProbe = RecordingProbe(ExactVideoEncoderCapabilityProbeResult.Supported)
        val restarted = CachedExactVideoEncoderCapabilityProbe(restartedProbe, store)
        val decision = restarted.probe(key)

        assertEquals(CbrCapabilityDecisionSource.ActiveProbe, decision.source)
        assertTrue(decision.supported)
        assertEquals(1, restartedProbe.calls)
    }

    @Test
    fun malformedPersistedResultCodeIsACacheMiss() {
        assertNull(ExactVideoEncoderCapabilityProbeResult.fromPersistedCode(Int.MAX_VALUE))
    }

    @Test
    fun concurrentSameKeyRequestsStartOneActiveProbe() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = BlockingProbe(started, release)
        val cached = CachedExactVideoEncoderCapabilityProbe(delegate)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<CbrCapabilityDecision> { cached.probe(key) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val second = executor.submit<CbrCapabilityDecision> { cached.probe(key) }
            release.countDown()

            val decisions = listOf(first.get(1, TimeUnit.SECONDS), second.get(1, TimeUnit.SECONDS))
            assertEquals(1, decisions.count { it.source == CbrCapabilityDecisionSource.ActiveProbe })
            assertEquals(1, decisions.count { it.source == CbrCapabilityDecisionSource.CurrentProcessProbeCache })
            assertEquals(1, delegate.calls)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private class RecordingProbe(
        private val result: ExactVideoEncoderCapabilityProbeResult,
    ) : ExactVideoEncoderCapabilityProbe {
        var calls = 0

        override fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision {
            calls += 1
            return CbrCapabilityDecision(
                supported = result == ExactVideoEncoderCapabilityProbeResult.Supported,
                source = CbrCapabilityDecisionSource.ActiveProbe,
                probeResult = result,
            )
        }
    }

    private class RecordingStore : ExactVideoEncoderQualificationStore {
        private val values = mutableMapOf<ExactVideoEncoderCapabilityKey, ExactVideoEncoderCapabilityProbeResult>()

        override fun read(key: ExactVideoEncoderCapabilityKey): ExactVideoEncoderCapabilityProbeResult? = values[key]

        override fun write(key: ExactVideoEncoderCapabilityKey, result: ExactVideoEncoderCapabilityProbeResult) {
            values[key] = result
        }
    }

    private class BlockingProbe(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : ExactVideoEncoderCapabilityProbe {
        var calls = 0

        override fun probe(key: ExactVideoEncoderCapabilityKey): CbrCapabilityDecision {
            calls += 1
            started.countDown()
            check(release.await(1, TimeUnit.SECONDS))
            return CbrCapabilityDecision(
                supported = true,
                source = CbrCapabilityDecisionSource.ActiveProbe,
                probeResult = ExactVideoEncoderCapabilityProbeResult.Supported,
            )
        }
    }
}
