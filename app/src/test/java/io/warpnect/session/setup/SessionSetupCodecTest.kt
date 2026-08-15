@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.setup

import io.warpnect.session.ChannelId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSetupCodecTest {
    @Test
    fun clientRequestUsesFrozenWnsnHeaderAndRoundTripsOptionalPreferences() {
        val request = SessionSetupMessage.ClientSetupRequest(
            header(SessionSetupMessageType.ClientSetupRequest),
            hash(),
            profile().selectedChannels,
            preferences(),
        )
        assertTrue(request.preferences.video!!.isValid())
        assertTrue(request.preferences.input!!.isValid())
        val bytes = requireNotNull(SessionSetupCodec.encode(request))
        assertEquals("WNSN", bytes.copyOfRange(0, 4).decodeToString())
        assertEquals(1, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt())
        val decoded = requireNotNull(SessionSetupCodec.decode(bytes)).message as SessionSetupMessage.ClientSetupRequest
        assertEquals(request.selectedChannels, decoded.selectedChannels)
        assertEquals(request.preferences, decoded.preferences)
        assertArrayEquals(bytes, requireNotNull(SessionSetupCodec.encode(decoded)))
    }

    @Test
    fun proposalUsesChannelDescriptorAndCanonicalConfigurationOrdering() {
        val descriptors = descriptors()
        val proposal = SessionSetupMessage.HostConfigurationProposal(
            header(SessionSetupMessageType.HostConfigurationProposal),
            hash(1),
            hash(2),
            1,
            NetworkPathKind.Lan,
            null,
            PathId.requireValid(1u),
            null,
            descriptors,
            listOf(
                SetupConfiguration.Input(descriptors[1].channelId, preferences().input!!),
                SetupConfiguration.Video(descriptors[0].channelId, preferences().video!!.modes.single()),
                SetupConfiguration.Recovery(
                    descriptors[0].channelId,
                    RecoveryConfiguration(
                        descriptors[0].channelId,
                        CapabilityBits.RECOVERY_NACK,
                        retransmissionCacheSlots = 64,
                    ),
                ),
            ),
        )
        val bytes = requireNotNull(SessionSetupCodec.encode(proposal))
        assertTrue(bytes.size <= SessionSetupProtocol.MAX_PAYLOAD_BYTES)
        val decoded = requireNotNull(
            SessionSetupCodec.decode(bytes),
        ).message as SessionSetupMessage.HostConfigurationProposal
        assertEquals(listOf(1, 6, 4), decoded.configurations.map { typeOf(it) })
        assertArrayEquals(bytes, requireNotNull(SessionSetupCodec.encode(decoded)))
    }

    @Test
    fun hashTripleRejectAndMalformedHeaderHaveExactSizesAndFailClosed() {
        val accept = SessionSetupMessage.ClientConfigurationAccept(
            header(SessionSetupMessageType.ClientConfigurationAccept),
            hash(1),
            hash(2),
            hash(3),
        )
        val reject = SessionSetupMessage.Reject(
            header(SessionSetupMessageType.SetupReject),
            SetupRejectStage.Proposal,
            SetupRejectReason.Incompatible,
            hash(),
        )
        assertEquals(20 + 96, requireNotNull(SessionSetupCodec.encode(accept)).size)
        assertEquals(20 + 36, requireNotNull(SessionSetupCodec.encode(reject)).size)
        val valid = requireNotNull(SessionSetupCodec.encode(accept))
        assertNull(SessionSetupCodec.decode(valid.copyOf().also { it[6] = 1 }))
        assertNull(SessionSetupCodec.decode(valid.copyOf().also { it[18] = 1 }))
        assertNull(SessionSetupCodec.decode(valid + byteArrayOf(0)))
    }

    @Test
    fun endpointOfferRejectsMissingDuplicateAndControlEndpoints() {
        val valid = listOf(
            ChannelEndpointOffer(SessionChannelKind.Video, 0, 41000),
            ChannelEndpointOffer(SessionChannelKind.Input, 0, 41001),
        )
        val offer = SessionSetupMessage.ClientEndpointOffer(
            header(SessionSetupMessageType.ClientEndpointOffer),
            hash(),
            NetworkPathKind.Lan,
            valid,
        )
        assertTrue(offer.endpoints.all(ChannelEndpointOffer::isValid))
        assertTrue(SessionSetupCodec.encode(offer) != null)
        assertNull(SessionSetupCodec.encode(offer.copy(endpoints = listOf(valid[0], valid[0]))))
        assertNull(
            SessionSetupCodec.encode(
                offer.copy(endpoints = listOf(ChannelEndpointOffer(SessionChannelKind.Control, 0, 41000))),
            ),
        )
    }

    @Test
    fun goldenVectorsFreezeCanonicalCoreMessagesAndHashes() {
        val endpointOffer = SessionSetupMessage.ClientEndpointOffer(
            header(SessionSetupMessageType.ClientEndpointOffer),
            hash(),
            NetworkPathKind.Lan,
            listOf(
                ChannelEndpointOffer(SessionChannelKind.Video, 0, 41000),
                ChannelEndpointOffer(SessionChannelKind.Input, 0, 41001),
            ),
        )
        val proposal = SessionSetupMessage.HostConfigurationProposal(
            header(SessionSetupMessageType.HostConfigurationProposal),
            hash(1),
            SessionSetupCodec.hash(requireNotNull(SessionSetupCodec.encode(endpointOffer))),
            1,
            NetworkPathKind.Lan,
            null,
            PathId.requireValid(1u),
            null,
            descriptors(),
            listOf(
                SetupConfiguration.Video(descriptors()[0].channelId, preferences().video!!.modes.single()),
                SetupConfiguration.Input(descriptors()[1].channelId, preferences().input!!),
            ),
        )
        val encodedProposal = requireNotNull(SessionSetupCodec.encode(proposal))
        val proposalHash = SessionSetupCodec.hash(encodedProposal)
        val accept = SessionSetupMessage.ClientConfigurationAccept(
            header(SessionSetupMessageType.ClientConfigurationAccept),
            hash(1),
            proposal.clientEndpointOfferHash,
            proposalHash,
        )
        val commit = SessionSetupMessage.HostCommit(
            header(SessionSetupMessageType.HostCommit),
            hash(1),
            proposal.clientEndpointOfferHash,
            proposalHash,
        )
        val reject = SessionSetupMessage.Reject(
            header(SessionSetupMessageType.SetupReject),
            SetupRejectStage.Proposal,
            SetupRejectReason.Incompatible,
            proposalHash,
        )
        val actual = listOf(
            SessionSetupMessage.ClientSetupRequest(
                header(SessionSetupMessageType.ClientSetupRequest),
                hash(),
                profile().selectedChannels,
                preferences(),
            ),
            endpointOffer,
            proposal,
            accept,
            commit,
            reject,
        ).joinToString("\n") { message -> requireNotNull(SessionSetupCodec.encode(message)).toHex() }

        val expected = """
            574e534e010100000000000000000007004d0000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f00090102000001010100050002d0003c007a120000000010000001000500000000000502030000000000400020
            574e534e010600000000000000000007002c0000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f020200000100a0280400a029
            574e534e01070000000000000000000700a800000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20eb6df0b28b477742cc9a8edd835b61d85553e1c79aaba4c38556b7245bb5d5d200010200000000010000000002020000000000010101000000000001a410a02804b00001000000020402000000000001a411a02904b0000000010012000000010110050002d0003c007a120000000004001600000002000500000000000502030000000000400020
            574e534e010800000000000000000007006000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20eb6df0b28b477742cc9a8edd835b61d85553e1c79aaba4c38556b7245bb5d5d21fc500f4694afc02575fd7d4b8ac12cb164af12b2bca10952ee0328a9532b59c
            574e534e010a00000000000000000007006000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20eb6df0b28b477742cc9a8edd835b61d85553e1c79aaba4c38556b7245bb5d5d21fc500f4694afc02575fd7d4b8ac12cb164af12b2bca10952ee0328a9532b59c
            574e534e010b0000000000000000000700240000040100001fc500f4694afc02575fd7d4b8ac12cb164af12b2bca10952ee0328a9532b59c
        """.trimIndent()
        assertEquals(expected, actual)
    }

    private fun header(type: SessionSetupMessageType) = SessionSetupHeader(type, SessionSetupId.requireValid(7u), 0)

    private fun hash(seed: Int = 0): ByteArray = ByteArray(32) { (it + seed).toByte() }

    private fun profile() = NegotiatedCapabilityProfile(
        selectedChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
        eligiblePathKinds = CapabilityBits.PATH_LAN,
        secureDatagramBytes = 1200,
        maxSessionChannels = 32,
        recoveryFlags = CapabilityBits.RECOVERY_NACK,
        videoCodec = NegotiatedCapabilityProfile.VIDEO_CODEC_AVC,
        videoFlags = CapabilityBits.VIDEO_LOW_LATENCY_DECODE or CapabilityBits.VIDEO_DYNAMIC_BITRATE,
        videoPayloadVersion = 1,
        videoMaxWidth = 1920,
        videoMaxHeight = 1080,
        videoMaxFps = 60,
        videoMaxBitrateBps = 20_000_000,
        audioCodec = NegotiatedCapabilityProfile.AUDIO_CODEC_NONE,
        audioFrameDurationMask = 0,
        audioPayloadVersion = 0,
        audioSampleRateMask = 0,
        systemAudioMaxChannels = 0,
        microphoneMaxChannels = 0,
        microphoneRoutingPolicy = MicrophoneRoutingSelection.NotApplicable,
        inputPayloadVersion = 1,
        inputKinds = CapabilityBits.INPUT_KEYBOARD or CapabilityBits.INPUT_MOUSE,
        inputFeatureFlags = CapabilityBits.INPUT_RELATIVE_POINTER or CapabilityBits.INPUT_STATE_CONVERGENCE,
        stablePresenceKinds = 0,
    )

    private fun preferences() = SessionSetupPreferences(
        video = VideoStreamPreference(
            VideoPreferencePolicy.Exact,
            listOf(VideoStreamMode(1280, 720, 60, 8_000_000, CapabilityBits.VIDEO_LOW_LATENCY_DECODE)),
        ),
        input = InputStreamConfiguration(
            CapabilityBits.INPUT_KEYBOARD or CapabilityBits.INPUT_MOUSE,
            0,
            CapabilityBits.INPUT_RELATIVE_POINTER or CapabilityBits.INPUT_STATE_CONVERGENCE,
        ),
    )

    private fun descriptors() = listOf(
        ChannelDescriptor(
            ChannelId.requireValid(1u), SessionChannelKind.Video, SessionChannelDirection.HostToClient, 0,
            PathId.requireValid(1u), 42000, 41000, 1200, CapabilityBits.RECOVERY_NACK,
        ),
        ChannelDescriptor(
            ChannelId.requireValid(2u), SessionChannelKind.Input, SessionChannelDirection.ClientToHost, 0,
            PathId.requireValid(1u), 42001, 41001, 1200, 0,
        ),
    )

    private fun typeOf(value: SetupConfiguration): Int = when (value) {
        is SetupConfiguration.Video -> 1
        is SetupConfiguration.SystemAudio -> 2
        is SetupConfiguration.MicrophoneAudio -> 3
        is SetupConfiguration.Input -> 4
        is SetupConfiguration.Telemetry -> 5
        is SetupConfiguration.Recovery -> 6
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
