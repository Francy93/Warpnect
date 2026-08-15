@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.capability

import io.warpnect.session.SessionRole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityNegotiationCodecTest {
    @Test
    fun clientOfferUsesExactWncpHeaderAndRoundTripsCanonicalTlvs() {
        val message = CapabilityNegotiationMessage.ClientOffer(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientOffer, id(), 0),
            snapshot(SessionRole.Client),
            request(),
        )
        val bytes = requireNotNull(CapabilityNegotiationCodec.encode(message))
        assertEquals('W'.code.toByte(), bytes[0])
        assertEquals('N'.code.toByte(), bytes[1])
        assertEquals('C'.code.toByte(), bytes[2])
        assertEquals('P'.code.toByte(), bytes[3])
        assertEquals(1, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt())
        val decoded = requireNotNull(
            CapabilityNegotiationCodec.decode(bytes),
        ).message as CapabilityNegotiationMessage.ClientOffer
        assertEquals(id(), decoded.header.negotiationId)
        assertEquals(SessionRole.Client, decoded.capabilities.role)
        assertEquals(request(), decoded.request)
        assertArrayEquals(bytes, requireNotNull(CapabilityNegotiationCodec.encode(decoded)))
    }

    @Test
    fun optionalUnknownTlvIsPreservedForHashButCriticalUnknownIsRejected() {
        val optional = CapabilityTlv(0x0010, byteArrayOf(8, 9))
        val offer = CapabilityNegotiationMessage.ClientOffer(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientOffer, id(), 0),
            snapshot(SessionRole.Client),
            request(),
            listOf(optional),
        )
        val optionalBytes = requireNotNull(CapabilityNegotiationCodec.encode(offer))
        val decoded = requireNotNull(
            CapabilityNegotiationCodec.decode(optionalBytes),
        ).message as CapabilityNegotiationMessage.ClientOffer
        assertEquals(optional.wireType, decoded.unknownOptionalTlvs.single().wireType)
        assertArrayEquals(optional.value, decoded.unknownOptionalTlvs.single().value)
        assertArrayEquals(optionalBytes, requireNotNull(CapabilityNegotiationCodec.encode(decoded)))

        val criticalBytes = requireNotNull(
            CapabilityNegotiationCodec.encode(
                offer.copy(unknownOptionalTlvs = listOf(CapabilityTlv(0x8010, byteArrayOf(1)))),
            ),
        )
        assertNull(CapabilityNegotiationCodec.decode(criticalBytes))
    }

    @Test
    fun clientOfferRejectsProfileTlvAndHeaderMutations() {
        val profile =
            requireNotNull(
                CapabilityNegotiator.negotiate(
                    snapshot(SessionRole.Client),
                    request(),
                    snapshot(SessionRole.Host),
                    HostCapabilityPolicy(),
                ).profile,
            )
        val offer = CapabilityNegotiationMessage.ClientOffer(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientOffer, id(), 0),
            snapshot(SessionRole.Client),
            request(),
            listOf(CapabilityTlv(CapabilityTlvType.NegotiatedCapabilityProfile.wireType, profileValue(profile))),
        )
        assertNull(CapabilityNegotiationCodec.decode(requireNotNull(CapabilityNegotiationCodec.encode(offer))))

        val valid = requireNotNull(CapabilityNegotiationCodec.encode(offer.copy(unknownOptionalTlvs = emptyList())))
        assertNull(CapabilityNegotiationCodec.decode(valid.copyOf().also { it[6] = 1 }))
        assertNull(CapabilityNegotiationCodec.decode(valid.copyOf().also { it[18] = 1 }))
        assertNull(CapabilityNegotiationCodec.decode(valid.copyOf().also { it[17] = 0 }))
    }

    @Test
    fun profileAndHashTriplesHaveFrozenLengths() {
        val profile =
            requireNotNull(
                CapabilityNegotiator.negotiate(
                    snapshot(SessionRole.Client),
                    request(),
                    snapshot(SessionRole.Host),
                    HostCapabilityPolicy(),
                ).profile,
            )
        assertEquals(56, requireNotNull(CapabilityNegotiationCodec.encodeProfileTlv(profile)).size)
        val hash = CapabilityNegotiationCodec.hash(byteArrayOf(7))
        val confirm = CapabilityNegotiationMessage.ClientConfirm(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientConfirm, id(), 0),
            hash,
            hash,
            hash,
        )
        val complete = CapabilityNegotiationMessage.HostComplete(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.HostComplete, id(), 0),
            hash,
            hash,
            hash,
        )
        val reject = CapabilityNegotiationMessage.Reject(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.NegotiationReject, id(), 0),
            CapabilityNegotiationRejectStage.Selection,
            CapabilityNegotiationRejectReason.Incompatible,
            hash,
        )
        assertEquals(20 + 96, requireNotNull(CapabilityNegotiationCodec.encode(confirm)).size)
        assertEquals(20 + 96, requireNotNull(CapabilityNegotiationCodec.encode(complete)).size)
        assertEquals(20 + 36, requireNotNull(CapabilityNegotiationCodec.encode(reject)).size)
    }

    @Test
    fun hashesBindNegotiationIdAndCanonicalBody() {
        val first = requireNotNull(
            CapabilityNegotiationCodec.encode(
                CapabilityNegotiationMessage.ClientOffer(
                    CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientOffer, id(1u), 0),
                    snapshot(SessionRole.Client),
                    request(),
                ),
            ),
        )
        val second = requireNotNull(
            CapabilityNegotiationCodec.encode(
                CapabilityNegotiationMessage.ClientOffer(
                    CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientOffer, id(2u), 0),
                    snapshot(SessionRole.Client),
                    request(),
                ),
            ),
        )
        assertTrue(!CapabilityNegotiationCodec.hash(first).contentEquals(CapabilityNegotiationCodec.hash(second)))
    }

    private fun profileValue(profile: NegotiatedCapabilityProfile): ByteArray =
        requireNotNull(CapabilityNegotiationCodec.encodeProfileTlv(profile)).copyOfRange(4, 56)

    companion object Fixtures {
        fun id(value: ULong = 0x0102_0304_0506_0708u): CapabilityNegotiationId =
            CapabilityNegotiationId.requireValid(value)

        fun request(
            required: Int = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
            preferred: Int =
                CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO or
                    CapabilityBits.CHANNEL_TELEMETRY,
            disabled: Int = 0,
            microphonePrimary: MicrophoneRoutingSelection = MicrophoneRoutingSelection.SeparatePerPeer,
            microphoneFallback: MicrophoneRoutingSelection = MicrophoneRoutingSelection.NotApplicable,
            requiredInput: Int = CapabilityBits.INPUT_KEYBOARD,
            preferredInput: Int = CapabilityBits.INPUT_MOUSE,
            stableRequired: Int = 0,
            stablePreferred: Int = 0,
            lowLatency: FeatureRequirement = FeatureRequirement.Preferred,
            distinctGamepad: FeatureRequirement = FeatureRequirement.Disabled,
        ): CapabilityRequest = CapabilityRequest(
            required, preferred, disabled, requiredInput, preferredInput, microphonePrimary, microphoneFallback,
            stableRequired, stablePreferred, lowLatency, distinctGamepad,
            CapabilityBits.RECOVERY_NACK,
        )

        fun snapshot(
            role: SessionRole,
            videoHardware: Boolean = true,
            microphonePolicies: Int = CapabilityBits.MICROPHONE_SEPARATE_PER_PEER,
            hostInputFlags: Int = CapabilityBits.INPUT_PRIVILEGED_INJECTION or CapabilityBits.INPUT_STATE_CONVERGENCE,
            stableKinds: Int = 0,
        ): LocalCapabilitySnapshot {
            val videoFlags = when (role) {
                SessionRole.Client ->
                    CapabilityBits.VIDEO_HARDWARE_DECODE or CapabilityBits.VIDEO_SURFACE_OUTPUT_DECODE or
                        CapabilityBits.VIDEO_LOW_LATENCY_DECODE or CapabilityBits.VIDEO_RESYNC
                SessionRole.Host ->
                    CapabilityBits.VIDEO_HARDWARE_ENCODE or CapabilityBits.VIDEO_SURFACE_INPUT_ENCODE or
                        CapabilityBits.VIDEO_DYNAMIC_BITRATE or CapabilityBits.VIDEO_KEYFRAME_REQUEST or
                        CapabilityBits.VIDEO_RESYNC
            }.takeIf { videoHardware } ?: 0
            return LocalCapabilitySnapshot(
                capturedAtMonotonicNs = 1,
                role = role,
                core = CoreTransportCapabilities(
                    1,
                    1,
                    1_200,
                    32,
                    CapabilityBits.RECOVERY_NACK or CapabilityBits.RECOVERY_FEC or
                        CapabilityBits.RECOVERY_CLOCK_SYNC or CapabilityBits.RECOVERY_VIDEO_RESYNC,
                    CapabilityBits.FEATURE_PROTECTED_SESSION_CONTROL or CapabilityBits.FEATURE_MULTI_CLIENT_HOST or
                        CapabilityBits.FEATURE_INPUT_STATE_CONVERGENCE,
                ),
                paths = PathCapabilities(CapabilityBits.PATH_LAN, CapabilityBits.PATH_LAN, 1, 0),
                video = VideoCapabilities(
                    1,
                    if (videoHardware) CapabilityBits.VIDEO_AVC else 0,
                    videoFlags,
                    1_920,
                    1_080,
                    60,
                    20_000_000,
                ),
                audio = AudioCapabilities(
                    1,
                    CapabilityBits.AUDIO_OPUS,
                    CapabilityBits.AUDIO_OPUS_ENCODE or CapabilityBits.AUDIO_OPUS_DECODE or
                        CapabilityBits.AUDIO_SYSTEM_CAPTURE or CapabilityBits.AUDIO_MICROPHONE_CAPTURE or
                        CapabilityBits.AUDIO_LOW_LATENCY_PLAYBACK or CapabilityBits.AUDIO_PLC_DECODE,
                    CapabilityBits.AUDIO_FRAME_5_MS or CapabilityBits.AUDIO_FRAME_10_MS,
                    2,
                    2,
                    CapabilityBits.AUDIO_SAMPLE_RATE_48_KHZ,
                ),
                input = InputCapabilities(
                    1,
                    if (role == SessionRole.Client) CapabilityBits.INPUT_KIND_MASK else 0,
                    if (role == SessionRole.Host) CapabilityBits.INPUT_KIND_MASK else 0,
                    32,
                    32,
                    if (role == SessionRole.Host) hostInputFlags else CapabilityBits.INPUT_STATE_CONVERGENCE,
                ),
                behavior = BehaviorCapabilities(
                    microphonePolicies,
                    CapabilityBits.INPUT_KIND_MASK and stableKinds.inv(),
                    stableKinds,
                ),
            )
        }
    }
}
