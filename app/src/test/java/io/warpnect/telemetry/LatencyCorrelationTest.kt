@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import io.warpnect.session.ChannelId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyCorrelationTest {
    @Test
    fun clockDomainsRejectIncompatibleSubtraction() {
        val monotonic = ClockDomainTimestampNs(ClockDomainId.AndroidMonotonic, MonotonicTimestampNs(2_000L))
        val earlier = ClockDomainTimestampNs(ClockDomainId.AndroidMonotonic, MonotonicTimestampNs(1_000L))
        val bootTime = ClockDomainTimestampNs(ClockDomainId.AndroidBootTime, MonotonicTimestampNs(1_000L))

        assertEquals(1_000L, monotonic.elapsedSince(earlier))
        assertNull(monotonic.elapsedSince(bootTime))
    }

    @Test
    fun correlationTableIsBoundedAndExpiresOpportunistically() {
        val table = LatencyCorrelationTable(capacity = 1, maxProbe = 1, expiryNs = 10L)
        assertEquals(LatencyCorrelationOutcome.Started, table.start(1L, 1L))
        assertEquals(LatencyCorrelationOutcome.CapacityRejected, table.start(2L, 2L))
        assertEquals(LatencyCorrelationOutcome.Started, table.start(2L, 12L))
        assertEquals(1, table.drainExpiredCount())
        assertEquals(LatencyCorrelationOutcome.Unmatched, table.markSecond(1L, 13L))

        val zeroTimestamp = LatencyCorrelationTable(capacity = 1, maxProbe = 1, expiryNs = 10L)
        assertEquals(LatencyCorrelationOutcome.Started, zeroTimestamp.start(0L, 0L))
        assertEquals(LatencyCorrelationOutcome.Matched, zeroTimestamp.markSecond(0L, 1L))
    }

    @Test
    fun videoCorrelatesByPresentationTimestampAndUsesProvidedRenderTime() {
        val hub = TelemetryHub()
        val telemetry = VideoDecoderTelemetry.register(hub, videoScope())

        telemetry.decoderInput(100L, 1_000_000L)
        telemetry.decoderInput(200L, 2_000_000L)
        telemetry.decoderOutput(200L, 4_000_000L)
        telemetry.decoderOutput(100L, 5_000_000L)
        telemetry.surfaceReleased(200L, 4_500_000L)
        telemetry.surfaceReleased(100L, 5_500_000L)
        telemetry.frameRendered(200L, 6_000_000L)
        telemetry.frameRendered(100L, 7_000_000L)

        val snapshot = hub.snapshot()
        assertHistogram(snapshot, TelemetryMetricIds.VideoDecoderInputToOutput, 2u, 6_000u)
        assertHistogram(snapshot, TelemetryMetricIds.VideoDecoderOutputToRelease, 2u, 1_000u)
        assertHistogram(snapshot, TelemetryMetricIds.VideoReleaseToRender, 2u, 3_000u)
        assertCounter(snapshot, TelemetryMetricIds.LatencyTraceCompleted, 2u)
        assertFalse(snapshot.records.any { it.metricId == TelemetryMetricIds.VideoSourceToRender })
        telemetry.close()
    }

    @Test
    fun audioAndInputUseDeterministicBoundedSampling() {
        val hub = TelemetryHub()
        val audio = AudioReceiverTelemetry.register(hub, audioScope())
        val input = InputSenderTelemetry.register(hub, inputScope())

        audio.recordDecoderInputToOutput(8L, 10_000L, 13_000L)
        audio.recordDecoderInputToOutput(9L, 10_000L, 14_000L)
        input.recordCaptureToSender(4L, 14L)
        input.recordCaptureToSender(5L, 16L)

        val snapshot = hub.snapshot()
        assertHistogram(snapshot, TelemetryMetricIds.AudioDecoderInputToOutput, 1u, 3u)
        assertHistogram(snapshot, TelemetryMetricIds.InputCaptureToSender, 1u, 10u)
        audio.close()
        input.close()
    }

    @Test
    fun sameGenerationSourceContinuesAndFreshGenerationCreatesANewSource() {
        val hub = TelemetryHub()
        val generationOne = VideoDecoderTelemetry.register(hub, videoScope())
        generationOne.decoderInput(100L, 1_000L)
        generationOne.decoderOutput(100L, 2_000L)
        val beforeRebind = hub.snapshot()
        val firstSource = beforeRebind.records.single {
            it.metricId == TelemetryMetricIds.VideoDecoderInputToOutput
        }.sourceId

        // RFC-005H rebind keeps the live decoder telemetry instance; no trace or source reset occurs.
        generationOne.decoderInput(200L, 3_000L)
        generationOne.decoderOutput(200L, 5_000L)
        val afterRebind = hub.snapshot()
        val continued = afterRebind.records.single {
            it.metricId == TelemetryMetricIds.VideoDecoderInputToOutput
        }
        assertEquals(firstSource, continued.sourceId)
        assertEquals(2uL, (continued.value as TelemetryMetricValue.Histogram).count)

        generationOne.close()
        val generationTwo = VideoDecoderTelemetry.register(
            hub,
            channelScope(
                SessionChannelKind.Video,
                SessionChannelDirection.HostToClient,
                SessionGeneration.requireValid(2u),
            ),
        )
        generationTwo.decoderInput(300L, 8_000L)
        generationTwo.decoderOutput(300L, 9_000L)
        val fresh = hub.snapshot().records.single {
            it.metricId == TelemetryMetricIds.VideoDecoderInputToOutput
        }
        assertFalse(fresh.sourceId == firstSource)
        assertEquals(1uL, (fresh.value as TelemetryMetricValue.Histogram).count)
        assertEquals(SessionGeneration.requireValid(2u), (fresh.scope as TelemetryScope.Channel).generation)
        generationTwo.close()
    }

    private fun assertCounter(snapshot: TelemetrySnapshot, id: TelemetryMetricId, expected: ULong) {
        val value = snapshot.records.single { it.metricId == id }.value as TelemetryMetricValue.Counter
        assertEquals(expected, value.value)
    }

    private fun assertHistogram(snapshot: TelemetrySnapshot, id: TelemetryMetricId, count: ULong, sum: ULong) {
        val value = snapshot.records.single { it.metricId == id }.value as TelemetryMetricValue.Histogram
        assertEquals(count, value.count)
        assertEquals(sum, value.sum)
        assertTrue(value.min != null && value.max != null)
    }

    private fun videoScope() = channelScope(SessionChannelKind.Video, SessionChannelDirection.HostToClient)

    private fun audioScope() = channelScope(SessionChannelKind.SystemAudio, SessionChannelDirection.HostToClient)

    private fun inputScope() = channelScope(SessionChannelKind.Input, SessionChannelDirection.ClientToHost)

    private fun channelScope(
        kind: SessionChannelKind,
        direction: SessionChannelDirection,
        generation: SessionGeneration = SessionGeneration.requireValid(1u),
    ): TelemetryScope.Channel {
        return TelemetryScope.Channel(
            sessionId = SessionId.requireValid(1u, 2u),
            generation = generation,
            channelId = ChannelId.requireValid(kind.ordinal.toUInt() + 1u),
            channelKind = kind,
            direction = direction,
        )
    }
}
