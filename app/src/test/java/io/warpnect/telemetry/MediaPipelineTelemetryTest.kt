@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import io.warpnect.session.ChannelId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipelineTelemetryTest {
    @Test
    fun videoEncoderRecordsAccessUnitsWithoutARegistryLookup() {
        val hub = TelemetryHub()
        val telemetry = VideoEncoderTelemetry.register(hub, channelScope(SessionChannelKind.Video))

        telemetry.accessUnits.increment()
        telemetry.bytes.add(1_024u)
        telemetry.keyframes.increment()
        telemetry.accessUnitSize.record(1_024u)

        val snapshot = hub.snapshot()
        assertCounter(snapshot, TelemetryMetricIds.VideoEncoderAccessUnitOutput, 1u)
        assertCounter(snapshot, TelemetryMetricIds.VideoEncoderByteOutput, 1_024u)
        assertCounter(snapshot, TelemetryMetricIds.VideoEncoderKeyframeOutput, 1u)
        val histogram = snapshot.records.single { it.metricId == TelemetryMetricIds.VideoEncoderAccessUnitSize }
        assertEquals(1uL, (histogram.value as TelemetryMetricValue.Histogram).count)
        telemetry.close()
        assertFalse(
            hub.snapshot().records.any {
                it.sourceId == telemetrySourceId(snapshot, TelemetryMetricIds.VideoEncoderAccessUnitOutput)
            },
        )
    }

    @Test
    fun roleSpecificAudioAndInputSourcesStayBoundedAndContentFree() {
        val hub = TelemetryHub()
        val audio = AudioSenderTelemetry.register(hub, channelScope(SessionChannelKind.SystemAudio))
        val input = InputReceiverTelemetry.register(hub, channelScope(SessionChannelKind.Input))
        audio.capturedSamples.add(240u)
        audio.encodedFrames.increment()
        input.acceptedEvents.increment()
        input.semanticDuplicates.increment()
        input.injectionAttempted.increment()

        val snapshot = hub.snapshot()
        assertCounter(snapshot, TelemetryMetricIds.AudioCaptureSample, 240u)
        assertCounter(snapshot, TelemetryMetricIds.AudioEncoderFrameOutput, 1u)
        assertCounter(snapshot, TelemetryMetricIds.InputReceiverEventAccepted, 1u)
        assertCounter(snapshot, TelemetryMetricIds.InputReceiverSemanticDuplicate, 1u)
        assertCounter(snapshot, TelemetryMetricIds.InputInjectionEventAttempted, 1u)
        assertTrue(snapshot.records.none { it.metricId.value !in 1..0x05ff })
        audio.close()
        input.close()
    }

    private fun assertCounter(snapshot: TelemetrySnapshot, id: TelemetryMetricId, value: ULong) {
        val record = snapshot.records.single { it.metricId == id }
        assertEquals(value, (record.value as TelemetryMetricValue.Counter).value)
    }

    private fun telemetrySourceId(snapshot: TelemetrySnapshot, id: TelemetryMetricId): TelemetrySourceId =
        snapshot.records.single { it.metricId == id }.sourceId

    private fun channelScope(kind: SessionChannelKind): TelemetryScope.Channel = TelemetryScope.Channel(
        sessionId = SessionId.requireValid(1u, 2u),
        generation = SessionGeneration.requireValid(1u),
        channelId = ChannelId.requireValid(kind.ordinal.toUInt() + 1u),
        channelKind = kind,
        direction = when (kind) {
            SessionChannelKind.Input -> SessionChannelDirection.ClientToHost
            else -> SessionChannelDirection.HostToClient
        },
    )
}
