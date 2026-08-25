@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import io.warpnect.session.ChannelId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryHubTest {
    @Test
    fun descriptorCatalogIsStableAndValid() {
        TelemetryDescriptorCatalog.validate(TelemetryDescriptorCatalog.descriptors)

        assertEquals(143, TelemetryDescriptorCatalog.descriptors.size)
        assertTrue(
            TelemetryDescriptorCatalog.descriptors.all {
                it.id.value in 0x0001..0x00ff ||
                    it.id.value in 0x0100..0x01ff ||
                    it.id.value in 0x0200..0x02ff ||
                    it.id.value in 0x0300..0x03ff ||
                    it.id.value in 0x0400..0x04ff ||
                    it.id.value in 0x0500..0x05ff ||
                    it.id.value in 0x0600..0x06ff ||
                    it.id.value in 0x0700..0x07ff
            },
        )
        assertTrue(TelemetryDescriptorCatalog.descriptors.all { it.canonicalName.startsWith("warpnect.") })
    }

    @Test
    fun mediaAndInputDescriptorsUseFrozenIdsAndHistogramBoundaries() {
        val descriptors = TelemetryDescriptorCatalog.descriptors.associateBy { it.id }
        assertEquals(
            TelemetryMetricKind.HistogramU64,
            descriptors.getValue(TelemetryMetricIds.VideoEncoderAccessUnitSize).kind,
        )
        assertTrue(
            descriptors.getValue(TelemetryMetricIds.VideoEncoderAccessUnitSize)
                .histogramBoundaries.contentEquals(
                    ulongArrayOf(
                        512u, 1_024u, 2_048u, 4_096u, 8_192u, 16_384u,
                        32_768u, 65_536u, 131_072u, 262_144u, 524_288u, 1_048_576u,
                    ),
                ),
        )
        assertTrue(
            descriptors.getValue(TelemetryMetricIds.AudioEncoderFrameSize)
                .histogramBoundaries.contentEquals(
                    ulongArrayOf(32u, 64u, 96u, 128u, 192u, 256u, 384u, 512u, 768u, 1_024u, 1_536u, 2_048u),
                ),
        )
        assertEquals(TelemetryUnit.Samples, descriptors.getValue(TelemetryMetricIds.AudioCaptureSample).unit)
        assertEquals(TelemetryUnit.Events, descriptors.getValue(TelemetryMetricIds.InputCaptureEvent).unit)
    }

    @Test
    fun counterGaugeAndHistogramPreservePrimitiveSemantics() {
        var overflowReports = 0
        val counter = TelemetryCounterU64 { overflowReports += 1 }
        counter.add(4u)
        counter.increment()
        assertEquals(5uL, counter.snapshot())
        counter.add(ULong.MAX_VALUE)
        assertEquals(ULong.MAX_VALUE, counter.snapshot())
        assertEquals(1, overflowReports)

        val gauge = TelemetryGaugeI64()
        assertFalse(gauge.snapshot().valid)
        gauge.set(-1)
        assertEquals(TelemetryGaugeValue(true, -1), gauge.snapshot())
        gauge.clear()
        assertFalse(gauge.snapshot().valid)

        val histogram = TelemetryHistogramU64(ulongArrayOf(10u, 20u))
        listOf(9uL, 10uL, 11uL, 20uL, 21uL).forEach(histogram::record)
        val snapshot = histogram.snapshot()
        assertEquals(5uL, snapshot.count)
        assertEquals(71uL, snapshot.sum)
        assertEquals(9uL, snapshot.min)
        assertEquals(21uL, snapshot.max)
        assertTrue(snapshot.bucketCounts.contentEquals(ulongArrayOf(2u, 2u, 1u)))
    }

    @Test
    fun histogramConcurrentUpdatesRemainBoundedAndCumulative() {
        val histogram = TelemetryHistogramU64(ulongArrayOf(100u))
        val started = CountDownLatch(1)
        val threads = List(4) {
            thread {
                started.await()
                repeat(1_000) { histogram.record(50u) }
            }
        }
        started.countDown()
        threads.forEach(Thread::join)

        val snapshot = histogram.snapshot()
        assertEquals(4_000uL, snapshot.count)
        assertEquals(200_000uL, snapshot.sum)
        assertEquals(snapshot.count, snapshot.bucketCounts.fold(0uL) { total, bucket -> total + bucket })
    }

    @Test
    fun sourceRegistrationScopesAndSnapshotsAreIndependent() {
        val metric = TelemetryMetricDescriptor(
            TelemetryMetricId(0x0100),
            "warpnect.session.test.counter",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            setOf(TelemetryScopeKind.Session, TelemetryScopeKind.Channel),
            "Test-only descriptor used to validate static scope plumbing.",
        )
        val clock = FakeClock(100u)
        val hub = TelemetryHub(clock, TelemetryDescriptorCatalog.descriptors + metric)
        val sessionId = SessionId.requireValid(1u, 2u)
        val first = hub.registerSource(
            TelemetrySourceDefinition(
                TelemetryScope.Session(sessionId, SessionGeneration.requireValid(1u)),
                listOf(metric.id),
            ),
        ).source
        val second = hub.registerSource(
            TelemetrySourceDefinition(
                TelemetryScope.Channel(
                    sessionId,
                    SessionGeneration.requireValid(2u),
                    ChannelId.requireValid(4u),
                    SessionChannelKind.Video,
                    SessionChannelDirection.HostToClient,
                ),
                listOf(metric.id),
            ),
        ).source
        first.counter(metric.id).add(2u)
        second.counter(metric.id).add(3u)

        val one = hub.snapshot()
        clock.value = 200u
        val two = hub.snapshot()
        assertEquals(TelemetrySnapshotStatus.Complete, one.status)
        assertEquals(1uL, one.sequence)
        assertEquals(2uL, two.sequence)
        val counters = one.records.filter { it.metricId == metric.id }
        assertEquals(2, counters.size)
        assertNotEquals(counters[0].sourceId, counters[1].sourceId)
        assertTrue(counters.any { it.scope is TelemetryScope.Session })
        assertTrue(counters.any { it.scope is TelemetryScope.Channel })
        assertEquals(one.records.filter { it.metricId == metric.id }, two.records.filter { it.metricId == metric.id })

        first.close()
        assertEquals(1, hub.snapshot().records.count { it.metricId == metric.id })
        second.close()
        hub.close()
    }

    @Test
    fun registrationCapacityAndProviderFailureStayObservational() {
        val hub = TelemetryHub()
        val registrations = List(MAX_TELEMETRY_SOURCES) {
            hub.registerSource(
                TelemetrySourceDefinition(TelemetryScope.Process, listOf(TelemetryMetricIds.SnapshotCount)),
            )
        }
        assertTrue(registrations.all { it.source.enabled })
        val rejected = hub.registerSource(
            TelemetrySourceDefinition(TelemetryScope.Process, listOf(TelemetryMetricIds.SnapshotCount)),
        )
        assertFalse(rejected.source.enabled)
        assertEquals(TelemetrySnapshotError.SourceCapacityExceeded, rejected.error)

        assertNull(hub.registerProvider(TelemetrySnapshotProvider { error("test provider failure") }))
        val snapshot = hub.snapshot()
        assertEquals(TelemetrySnapshotStatus.Partial, snapshot.status)
        assertTrue(TelemetrySnapshotError.ProviderFailure in snapshot.errors)
        registrations.forEach { it.source.close() }
        hub.close()
    }

    @Test
    fun sourceCloseAndHubCloseAreIdempotent() {
        val hub = TelemetryHub()
        val source = hub.registerSource(
            TelemetrySourceDefinition(TelemetryScope.Process, listOf(TelemetryMetricIds.SnapshotCount)),
        ).source
        source.close()
        source.close()
        assertEquals(0, hub.snapshot().records.count { it.sourceId == source.sourceId })
        hub.close()
        hub.close()
        assertEquals(TelemetrySnapshotStatus.Closed, hub.snapshot().status)
    }

    private class FakeClock(
        var value: ULong,
    ) : TelemetryMonotonicClock {
        override fun nowNs(): ULong = value
    }
}
