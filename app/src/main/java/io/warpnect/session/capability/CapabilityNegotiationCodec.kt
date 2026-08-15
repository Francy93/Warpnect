@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.capability

import java.security.MessageDigest

data class CapabilityNegotiationHeader(
    val messageType: CapabilityNegotiationMessageType,
    val negotiationId: CapabilityNegotiationId,
    val bodyLength: Int,
)

data class CapabilityTlv(val wireType: Int, val value: ByteArray) {
    val isCritical: Boolean get() = wireType and 0x8000 != 0
    val encodedSize: Int get() = TLV_HEADER_BYTES + value.size

    companion object {
        const val TLV_HEADER_BYTES = 4
    }
}

sealed interface CapabilityNegotiationMessage {
    val header: CapabilityNegotiationHeader

    data class ClientOffer(
        override val header: CapabilityNegotiationHeader,
        val capabilities: LocalCapabilitySnapshot,
        val request: CapabilityRequest,
        val unknownOptionalTlvs: List<CapabilityTlv> = emptyList(),
    ) : CapabilityNegotiationMessage

    data class HostSelection(
        override val header: CapabilityNegotiationHeader,
        val clientOfferHash: ByteArray,
        val hostCapabilities: LocalCapabilitySnapshot,
        val profile: NegotiatedCapabilityProfile,
        val unknownOptionalTlvs: List<CapabilityTlv> = emptyList(),
    ) : CapabilityNegotiationMessage

    data class ClientConfirm(
        override val header: CapabilityNegotiationHeader,
        val clientOfferHash: ByteArray,
        val hostSelectionHash: ByteArray,
        val profileHash: ByteArray,
    ) : CapabilityNegotiationMessage

    data class HostComplete(
        override val header: CapabilityNegotiationHeader,
        val clientOfferHash: ByteArray,
        val hostSelectionHash: ByteArray,
        val profileHash: ByteArray,
    ) : CapabilityNegotiationMessage

    data class Reject(
        override val header: CapabilityNegotiationHeader,
        val stage: CapabilityNegotiationRejectStage,
        val reason: CapabilityNegotiationRejectReason,
        val relatedHash: ByteArray,
    ) : CapabilityNegotiationMessage
}

data class DecodedCapabilityNegotiationPacket(
    val message: CapabilityNegotiationMessage,
    val bytes: ByteArray,
) {
    val hash: ByteArray get() = CapabilityNegotiationCodec.hash(bytes)
}

/** Strict canonical WNCP V1 codec. It accepts no unauthenticated transport bytes directly. */
object CapabilityNegotiationCodec {
    private const val HEADER_MAGIC_OFFSET = 0
    private const val HEADER_VERSION_OFFSET = 4
    private const val HEADER_TYPE_OFFSET = 5
    private const val HEADER_FLAGS_OFFSET = 6
    private const val HEADER_ID_OFFSET = 8
    private const val HEADER_BODY_LENGTH_OFFSET = 16
    private const val HEADER_RESERVED_OFFSET = 18
    private const val HASH_BYTES = 32

    fun encode(message: CapabilityNegotiationMessage): ByteArray? {
        return try {
            val body = encodeBody(message) ?: return null
            if (body.size > CapabilityNegotiationProtocol.MAX_PAYLOAD_BYTES - CapabilityNegotiationProtocol.HEADER_BYTES) return null
            val header = message.header.copy(bodyLength = body.size)
            if (!header.isValidFor(message)) return null
            ByteArray(CapabilityNegotiationProtocol.HEADER_BYTES + body.size).also { bytes ->
                CapabilityNegotiationProtocol.MAGIC.copyInto(bytes, HEADER_MAGIC_OFFSET)
                bytes[HEADER_VERSION_OFFSET] = CapabilityNegotiationProtocol.VERSION.toByte()
                bytes[HEADER_TYPE_OFFSET] = header.messageType.wireId.toByte()
                writeU16(bytes, HEADER_FLAGS_OFFSET, 0)
                writeU64(bytes, HEADER_ID_OFFSET, header.negotiationId.value)
                writeU16(bytes, HEADER_BODY_LENGTH_OFFSET, body.size)
                writeU16(bytes, HEADER_RESERVED_OFFSET, 0)
                body.copyInto(bytes, CapabilityNegotiationProtocol.HEADER_BYTES)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decode(bytes: ByteArray): DecodedCapabilityNegotiationPacket? {
        if (bytes.size !in CapabilityNegotiationProtocol.HEADER_BYTES..CapabilityNegotiationProtocol.MAX_PAYLOAD_BYTES) return null
        if (!bytes.copyOfRange(
                HEADER_MAGIC_OFFSET,
                HEADER_MAGIC_OFFSET + 4,
            ).contentEquals(CapabilityNegotiationProtocol.MAGIC)
        ) {
            return null
        }
        if (unsigned(bytes[HEADER_VERSION_OFFSET]) != CapabilityNegotiationProtocol.VERSION) return null
        val messageType = CapabilityNegotiationMessageType.fromWireId(unsigned(bytes[HEADER_TYPE_OFFSET])) ?: return null
        if (readU16(bytes, HEADER_FLAGS_OFFSET) != 0 || readU16(bytes, HEADER_RESERVED_OFFSET) != 0) return null
        val negotiationId = CapabilityNegotiationId.from(readU64(bytes, HEADER_ID_OFFSET)) ?: return null
        val bodyLength = readU16(bytes, HEADER_BODY_LENGTH_OFFSET)
        if (bodyLength != bytes.size - CapabilityNegotiationProtocol.HEADER_BYTES) return null
        val header = CapabilityNegotiationHeader(messageType, negotiationId, bodyLength)
        val body = bytes.copyOfRange(CapabilityNegotiationProtocol.HEADER_BYTES, bytes.size)
        val message = when (messageType) {
            CapabilityNegotiationMessageType.ClientOffer -> decodeClientOffer(header, body)
            CapabilityNegotiationMessageType.HostSelection -> decodeHostSelection(header, body)
            CapabilityNegotiationMessageType.ClientConfirm -> decodeHashTriple(header, body) { offer, selection, profile ->
                CapabilityNegotiationMessage.ClientConfirm(header, offer, selection, profile)
            }
            CapabilityNegotiationMessageType.HostComplete -> decodeHashTriple(header, body) { offer, selection, profile ->
                CapabilityNegotiationMessage.HostComplete(header, offer, selection, profile)
            }
            CapabilityNegotiationMessageType.NegotiationReject -> decodeReject(header, body)
        } ?: return null
        return DecodedCapabilityNegotiationPacket(message, bytes.copyOf())
    }

    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun profileHash(profile: NegotiatedCapabilityProfile): ByteArray? {
        val value = encodeProfile(profile) ?: return null
        return hash(encodeTlv(CapabilityTlv(CapabilityTlvType.NegotiatedCapabilityProfile.wireType, value)))
    }

    fun encodeProfileTlv(profile: NegotiatedCapabilityProfile): ByteArray? = encodeProfile(
        profile,
    )?.let { encodeTlv(CapabilityTlv(CapabilityTlvType.NegotiatedCapabilityProfile.wireType, it)) }

    private fun encodeBody(message: CapabilityNegotiationMessage): ByteArray? {
        return when (message) {
            is CapabilityNegotiationMessage.ClientOffer -> {
                if (!message.capabilities.isValid() || !message.request.isValid()) {
                    null
                } else {
                    encodeTlvs(
                        listOf(
                            capabilityTlv(CapabilityTlvType.CoreTransportCapabilities, encodeCore(message.capabilities.core)),
                            capabilityTlv(CapabilityTlvType.PathCapabilities, encodePaths(message.capabilities.paths)),
                            capabilityTlv(CapabilityTlvType.VideoCapabilities, encodeVideo(message.capabilities.video)),
                            capabilityTlv(CapabilityTlvType.AudioCapabilities, encodeAudio(message.capabilities.audio)),
                            capabilityTlv(CapabilityTlvType.InputCapabilities, encodeInput(message.capabilities.input)),
                            capabilityTlv(CapabilityTlvType.BehaviorCapabilities, encodeBehavior(message.capabilities.behavior)),
                            capabilityTlv(CapabilityTlvType.CapabilityRequest, encodeRequest(message.request)),
                        ) + message.unknownOptionalTlvs,
                    )
                }
            }
            is CapabilityNegotiationMessage.HostSelection -> {
                if (!hashValid(message.clientOfferHash) || !message.hostCapabilities.isValid() || !message.profile.isValid()) {
                    null
                } else {
                    val tlvs = encodeTlvs(
                        listOf(
                            capabilityTlv(CapabilityTlvType.CoreTransportCapabilities, encodeCore(message.hostCapabilities.core)),
                            capabilityTlv(CapabilityTlvType.PathCapabilities, encodePaths(message.hostCapabilities.paths)),
                            capabilityTlv(CapabilityTlvType.VideoCapabilities, encodeVideo(message.hostCapabilities.video)),
                            capabilityTlv(CapabilityTlvType.AudioCapabilities, encodeAudio(message.hostCapabilities.audio)),
                            capabilityTlv(CapabilityTlvType.InputCapabilities, encodeInput(message.hostCapabilities.input)),
                            capabilityTlv(CapabilityTlvType.BehaviorCapabilities, encodeBehavior(message.hostCapabilities.behavior)),
                            capabilityTlv(CapabilityTlvType.NegotiatedCapabilityProfile, encodeProfile(message.profile)),
                        ) + message.unknownOptionalTlvs,
                    )
                    tlvs?.let { message.clientOfferHash + it }
                }
            }
            is CapabilityNegotiationMessage.ClientConfirm -> encodeHashTriple(
                message.clientOfferHash,
                message.hostSelectionHash,
                message.profileHash,
            )
            is CapabilityNegotiationMessage.HostComplete -> encodeHashTriple(
                message.clientOfferHash,
                message.hostSelectionHash,
                message.profileHash,
            )
            is CapabilityNegotiationMessage.Reject -> {
                if (!hashValid(message.relatedHash)) {
                    null
                } else {
                    ByteArray(CapabilityNegotiationProtocol.REJECT_BYTES).also { body ->
                        body[0] = message.stage.wireId.toByte()
                        body[1] = message.reason.wireId.toByte()
                        message.relatedHash.copyInto(body, 4)
                    }
                }
            }
        }
    }

    private fun decodeClientOffer(
        header: CapabilityNegotiationHeader,
        body: ByteArray,
    ): CapabilityNegotiationMessage.ClientOffer? {
        val parsed = decodeTlvs(body) ?: return null
        val sections = decodeCapabilitySections(parsed.known) ?: return null
        if (sections.profile != null) return null
        val request = decodeRequest(sections.request ?: return null) ?: return null
        val snapshot = LocalCapabilitySnapshot(
            capturedAtMonotonicNs = 0L,
            role = io.warpnect.session.SessionRole.Client,
            core = decodeCore(sections.core ?: return null) ?: return null,
            paths = decodePaths(sections.paths ?: return null) ?: return null,
            video = decodeVideo(sections.video ?: return null) ?: return null,
            audio = decodeAudio(sections.audio ?: return null) ?: return null,
            input = decodeInput(sections.input ?: return null) ?: return null,
            behavior = decodeBehavior(sections.behavior ?: return null) ?: return null,
        )
        if (!snapshot.isValid() || !request.isValid()) return null
        return CapabilityNegotiationMessage.ClientOffer(header, snapshot, request, parsed.unknownOptional)
    }

    private fun decodeHostSelection(
        header: CapabilityNegotiationHeader,
        body: ByteArray,
    ): CapabilityNegotiationMessage.HostSelection? {
        if (body.size < HASH_BYTES) return null
        val clientOfferHash = body.copyOfRange(0, HASH_BYTES)
        val parsed = decodeTlvs(body.copyOfRange(HASH_BYTES, body.size)) ?: return null
        val sections = decodeCapabilitySections(parsed.known) ?: return null
        if (sections.request != null) return null
        val snapshot = LocalCapabilitySnapshot(
            capturedAtMonotonicNs = 0L,
            role = io.warpnect.session.SessionRole.Host,
            core = decodeCore(sections.core ?: return null) ?: return null,
            paths = decodePaths(sections.paths ?: return null) ?: return null,
            video = decodeVideo(sections.video ?: return null) ?: return null,
            audio = decodeAudio(sections.audio ?: return null) ?: return null,
            input = decodeInput(sections.input ?: return null) ?: return null,
            behavior = decodeBehavior(sections.behavior ?: return null) ?: return null,
        )
        val profile = decodeProfile(sections.profile ?: return null) ?: return null
        if (!hashValid(clientOfferHash) || !snapshot.isValid() || !profile.isValid()) return null
        return CapabilityNegotiationMessage.HostSelection(
            header,
            clientOfferHash,
            snapshot,
            profile,
            parsed.unknownOptional,
        )
    }

    private fun decodeHashTriple(
        header: CapabilityNegotiationHeader,
        body: ByteArray,
        factory: (ByteArray, ByteArray, ByteArray) -> CapabilityNegotiationMessage,
    ): CapabilityNegotiationMessage? {
        if (body.size != CapabilityNegotiationProtocol.CLIENT_CONFIRM_BYTES) return null
        val hashes = listOf(
            body.copyOfRange(0, HASH_BYTES),
            body.copyOfRange(HASH_BYTES, HASH_BYTES * 2),
            body.copyOfRange(HASH_BYTES * 2, HASH_BYTES * 3),
        )
        return if (hashes.all(::hashValid)) factory(hashes[0], hashes[1], hashes[2]) else null
    }

    private fun decodeReject(
        header: CapabilityNegotiationHeader,
        body: ByteArray,
    ): CapabilityNegotiationMessage.Reject? {
        if (body.size != CapabilityNegotiationProtocol.REJECT_BYTES || body[2] != 0.toByte() || body[3] != 0.toByte()) return null
        val stage = CapabilityNegotiationRejectStage.fromWireId(unsigned(body[0])) ?: return null
        val reason = CapabilityNegotiationRejectReason.fromWireId(unsigned(body[1])) ?: return null
        val relatedHash = body.copyOfRange(4, body.size)
        if (!hashValid(relatedHash)) return null
        return CapabilityNegotiationMessage.Reject(header, stage, reason, relatedHash)
    }

    private fun decodeTlvs(body: ByteArray): ParsedTlvs? {
        var offset = 0
        var previous = -1
        val known = ArrayList<CapabilityTlv>()
        val unknownOptional = ArrayList<CapabilityTlv>()
        while (offset < body.size) {
            if (body.size - offset < CapabilityTlv.TLV_HEADER_BYTES || known.size + unknownOptional.size >= CapabilityNegotiationProtocol.MAX_TLVS) return null
            val type = readU16(body, offset)
            val length = readU16(body, offset + 2)
            offset += CapabilityTlv.TLV_HEADER_BYTES
            if (type <= previous || length > CapabilityNegotiationProtocol.MAX_TLV_VALUE_BYTES || length > body.size - offset) return null
            val tlv = CapabilityTlv(type, body.copyOfRange(offset, offset + length))
            offset += length
            previous = type
            if (CapabilityTlvType.fromWireType(type) != null) {
                known += tlv
            } else if (tlv.isCritical) {
                return null
            } else {
                unknownOptional += tlv
            }
        }
        return if (offset == body.size) ParsedTlvs(known, unknownOptional) else null
    }

    private fun decodeCapabilitySections(known: List<CapabilityTlv>): CapabilitySections? {
        val sections = CapabilitySections()
        known.forEach { tlv ->
            when (CapabilityTlvType.fromWireType(tlv.wireType) ?: return null) {
                CapabilityTlvType.CoreTransportCapabilities -> if (sections.core == null) sections.core = tlv.value else return null
                CapabilityTlvType.PathCapabilities -> if (sections.paths == null) sections.paths = tlv.value else return null
                CapabilityTlvType.VideoCapabilities -> if (sections.video == null) sections.video = tlv.value else return null
                CapabilityTlvType.AudioCapabilities -> if (sections.audio == null) sections.audio = tlv.value else return null
                CapabilityTlvType.InputCapabilities -> if (sections.input == null) sections.input = tlv.value else return null
                CapabilityTlvType.BehaviorCapabilities -> if (sections.behavior == null) sections.behavior = tlv.value else return null
                CapabilityTlvType.CapabilityRequest -> if (sections.request == null) sections.request = tlv.value else return null
                CapabilityTlvType.NegotiatedCapabilityProfile -> if (sections.profile == null) sections.profile = tlv.value else return null
            }
        }
        return sections
    }

    private fun encodeTlvs(tlvs: List<CapabilityTlv>): ByteArray? {
        if (tlvs.size > CapabilityNegotiationProtocol.MAX_TLVS || tlvs.any {
                it.value.size > CapabilityNegotiationProtocol.MAX_TLV_VALUE_BYTES
            }
        ) {
            return null
        }
        val sorted = tlvs.sortedBy { it.wireType }
        if (sorted.zipWithNext().any { (left, right) -> left.wireType == right.wireType } || sorted.any {
                it.wireType !in 0..0xffff
            }
        ) {
            return null
        }
        return sorted.flatMap { encodeTlv(it).asIterable() }.toByteArray()
    }

    private fun encodeTlv(tlv: CapabilityTlv): ByteArray {
        require(tlv.wireType in 0..0xffff && tlv.value.size <= CapabilityNegotiationProtocol.MAX_TLV_VALUE_BYTES)
        return ByteArray(CapabilityTlv.TLV_HEADER_BYTES + tlv.value.size).also { bytes ->
            writeU16(bytes, 0, tlv.wireType)
            writeU16(bytes, 2, tlv.value.size)
            tlv.value.copyInto(bytes, CapabilityTlv.TLV_HEADER_BYTES)
        }
    }

    private fun capabilityTlv(type: CapabilityTlvType, value: ByteArray?): CapabilityTlv =
        CapabilityTlv(type.wireType, requireNotNull(value))

    private fun encodeCore(value: CoreTransportCapabilities): ByteArray? = value.takeIf { it.isValid() }?.let {
        ByteArray(16).also { bytes ->
            writeU16(bytes, 0, it.sclProtocolVersion)
            writeU16(bytes, 2, it.sessionPacketProtectionVersion)
            writeU16(bytes, 4, it.maxSecureDatagramBytes)
            writeU16(bytes, 6, it.maxSessionChannels)
            writeU32(bytes, 8, it.recoveryFlags.toUInt())
            writeU32(bytes, 12, it.sessionFeatureFlags.toUInt())
        }
    }

    private fun decodeCore(bytes: ByteArray): CoreTransportCapabilities? = bytes.takeIf { it.size == 16 }?.let {
        CoreTransportCapabilities(
            readU16(it, 0),
            readU16(it, 2),
            readU16(it, 4),
            readU16(it, 6),
            readU32(it, 8).toInt(),
            readU32(it, 12).toInt(),
        )
            .takeIf(CoreTransportCapabilities::isValid)
    }

    private fun encodePaths(value: PathCapabilities): ByteArray? = value.takeIf { it.isValid() }?.let {
        byteArrayOf(
            it.implementedPathKinds.toByte(),
            it.availablePathKinds.toByte(),
            it.maxPaths.toByte(),
            it.pathFlags.toByte(),
            0,
            0,
            0,
            0,
        )
    }

    private fun decodePaths(bytes: ByteArray): PathCapabilities? = bytes.takeIf {
        it.size == 8 && it.copyOfRange(4, 8).all { byte -> byte == 0.toByte() }
    }?.let {
        PathCapabilities(
            unsigned(it[0]),
            unsigned(it[1]),
            unsigned(it[2]),
            unsigned(it[3]),
        ).takeIf(PathCapabilities::isValid)
    }

    private fun encodeVideo(value: VideoCapabilities): ByteArray? = value.takeIf { it.isValid() }?.let {
        ByteArray(20).also { bytes ->
            writeU16(bytes, 0, it.videoPayloadVersion)
            writeU16(bytes, 2, it.codecMask)
            writeU32(bytes, 4, it.videoFlags.toUInt())
            writeU16(bytes, 8, it.maxWidth)
            writeU16(bytes, 10, it.maxHeight)
            writeU16(bytes, 12, it.maxFps)
            writeU16(bytes, 14, 0)
            writeU32(bytes, 16, it.maxBitrateBps.toUInt())
        }
    }

    private fun decodeVideo(bytes: ByteArray): VideoCapabilities? = bytes.takeIf {
        it.size == 20 && readU16(it, 14) == 0
    }?.let {
        VideoCapabilities(
            readU16(it, 0),
            readU16(it, 2),
            readU32(it, 4).toInt(),
            readU16(it, 8),
            readU16(it, 10),
            readU16(it, 12),
            readU32(it, 16).toLong(),
        )
            .takeIf(VideoCapabilities::isValid)
    }

    private fun encodeAudio(value: AudioCapabilities): ByteArray? = value.takeIf { it.isValid() }?.let {
        ByteArray(16).also { bytes ->
            writeU16(bytes, 0, it.audioPayloadVersion)
            writeU16(bytes, 2, it.codecMask)
            writeU32(bytes, 4, it.audioFlags.toUInt())
            bytes[8] = it.frameDurationMask.toByte()
            bytes[9] = it.maxSendChannels.toByte()
            bytes[10] = it.maxReceiveChannels.toByte()
            bytes[11] = 0
            writeU32(bytes, 12, it.sampleRateMask.toUInt())
        }
    }

    private fun decodeAudio(bytes: ByteArray): AudioCapabilities? = bytes.takeIf {
        it.size == 16 && it[11] == 0.toByte()
    }?.let {
        AudioCapabilities(
            readU16(it, 0),
            readU16(it, 2),
            readU32(it, 4).toInt(),
            unsigned(it[8]),
            unsigned(it[9]),
            unsigned(it[10]),
            readU32(it, 12).toInt(),
        )
            .takeIf(AudioCapabilities::isValid)
    }

    private fun encodeInput(value: InputCapabilities): ByteArray? = value.takeIf { it.isValid() }?.let {
        ByteArray(16).also { bytes ->
            writeU16(bytes, 0, it.inputPayloadVersion)
            writeU16(bytes, 2, it.captureKinds)
            writeU16(bytes, 4, it.injectionKinds)
            bytes[6] = it.maxTouchPointers.toByte()
            bytes[7] = it.maxDeviceSlots.toByte()
            writeU32(bytes, 8, it.inputFlags.toUInt())
            writeU32(bytes, 12, 0u)
        }
    }

    private fun decodeInput(bytes: ByteArray): InputCapabilities? = bytes.takeIf {
        it.size == 16 && readU32(it, 12) == 0u
    }?.let {
        InputCapabilities(
            readU16(it, 0),
            readU16(it, 2),
            readU16(it, 4),
            unsigned(it[6]),
            unsigned(it[7]),
            readU32(it, 8).toInt(),
        )
            .takeIf(InputCapabilities::isValid)
    }

    private fun encodeBehavior(value: BehaviorCapabilities): ByteArray? = value.takeIf { it.isValid() }?.let {
        ByteArray(8).also { bytes ->
            bytes[0] = it.microphoneRoutingMask.toByte()
            bytes[1] = 0
            writeU16(bytes, 2, it.mirrorPresenceKinds)
            writeU16(bytes, 4, it.stablePresenceKinds)
            writeU16(bytes, 6, 0)
        }
    }

    private fun decodeBehavior(bytes: ByteArray): BehaviorCapabilities? = bytes.takeIf {
        it.size == 8 && it[1] == 0.toByte() && readU16(it, 6) == 0
    }?.let {
        BehaviorCapabilities(unsigned(it[0]), readU16(it, 2), readU16(it, 4)).takeIf(BehaviorCapabilities::isValid)
    }

    private fun encodeRequest(value: CapabilityRequest): ByteArray? = value.takeIf { it.isValid() }?.let {
        ByteArray(24).also { bytes ->
            writeU16(bytes, 0, it.requiredChannels)
            writeU16(bytes, 2, it.preferredChannels)
            writeU16(bytes, 4, it.disabledChannels)
            writeU16(bytes, 6, it.requiredInputKinds)
            writeU16(bytes, 8, it.preferredInputKinds)
            bytes[10] = it.microphonePolicyPrimary.wireId.toByte()
            bytes[11] = it.microphonePolicyFallback.wireId.toByte()
            writeU16(bytes, 12, it.stablePresenceRequiredKinds)
            writeU16(bytes, 14, it.stablePresencePreferredKinds)
            bytes[16] = it.videoLowLatencyRequirement.wireId.toByte()
            bytes[17] = it.distinctGamepadIdentityRequirement.wireId.toByte()
            writeU16(bytes, 18, 0)
            writeU32(bytes, 20, it.requiredRecoveryFlags.toUInt())
        }
    }

    private fun decodeRequest(bytes: ByteArray): CapabilityRequest? = bytes.takeIf {
        it.size == 24 && readU16(it, 18) == 0
    }?.let {
        CapabilityRequest(
            readU16(it, 0), readU16(it, 2), readU16(it, 4), readU16(it, 6), readU16(it, 8),
            MicrophoneRoutingSelection.fromWireId(unsigned(it[10])) ?: return null,
            MicrophoneRoutingSelection.fromWireId(unsigned(it[11])) ?: return null,
            readU16(it, 12), readU16(it, 14),
            FeatureRequirement.fromWireId(unsigned(it[16])) ?: return null,
            FeatureRequirement.fromWireId(unsigned(it[17])) ?: return null,
            readU32(it, 20).toInt(),
        ).takeIf(CapabilityRequest::isValid)
    }

    private fun encodeProfile(value: NegotiatedCapabilityProfile): ByteArray? = value.takeIf { it.isValid() }?.let {
        ByteArray(52).also { bytes ->
            writeU16(bytes, 0, it.selectedChannels)
            bytes[2] = it.eligiblePathKinds.toByte()
            bytes[3] = 0
            writeU16(bytes, 4, it.secureDatagramBytes)
            writeU16(bytes, 6, it.maxSessionChannels)
            writeU32(bytes, 8, it.recoveryFlags.toUInt())
            bytes[12] = it.videoCodec.toByte()
            bytes[13] = it.videoFlags.toByte()
            writeU16(bytes, 14, it.videoPayloadVersion)
            writeU16(bytes, 16, it.videoMaxWidth)
            writeU16(bytes, 18, it.videoMaxHeight)
            writeU16(bytes, 20, it.videoMaxFps)
            writeU16(bytes, 22, 0)
            writeU32(bytes, 24, it.videoMaxBitrateBps.toUInt())
            bytes[28] = it.audioCodec.toByte()
            bytes[29] = it.audioFrameDurationMask.toByte()
            writeU16(bytes, 30, it.audioPayloadVersion)
            writeU32(bytes, 32, it.audioSampleRateMask.toUInt())
            bytes[36] = it.systemAudioMaxChannels.toByte()
            bytes[37] = it.microphoneMaxChannels.toByte()
            bytes[38] = it.microphoneRoutingPolicy.wireId.toByte()
            bytes[39] = 0
            writeU16(bytes, 40, it.inputPayloadVersion)
            writeU16(bytes, 42, it.inputKinds)
            writeU32(bytes, 44, it.inputFeatureFlags.toUInt())
            writeU16(bytes, 48, it.stablePresenceKinds)
            writeU16(bytes, 50, 0)
        }
    }

    private fun decodeProfile(bytes: ByteArray): NegotiatedCapabilityProfile? = bytes.takeIf {
        it.size == 52 && it[3] == 0.toByte() && readU16(it, 22) == 0 && it[39] == 0.toByte() && readU16(it, 50) == 0
    }?.let {
        NegotiatedCapabilityProfile(
            readU16(it, 0), unsigned(it[2]), readU16(it, 4), readU16(it, 6), readU32(it, 8).toInt(),
            unsigned(
                it[12],
            ),
            unsigned(
                it[13],
            ),
            readU16(it, 14), readU16(it, 16), readU16(it, 18), readU16(it, 20), readU32(it, 24).toLong(),
            unsigned(
                it[28],
            ),
            unsigned(it[29]), readU16(it, 30), readU32(it, 32).toInt(), unsigned(it[36]), unsigned(it[37]),
            MicrophoneRoutingSelection.fromWireId(unsigned(it[38])) ?: return null,
            readU16(it, 40), readU16(it, 42), readU32(it, 44).toInt(), readU16(it, 48),
        ).takeIf(NegotiatedCapabilityProfile::isValid)
    }

    private fun encodeHashTriple(first: ByteArray, second: ByteArray, third: ByteArray): ByteArray? =
        if (!hashValid(first) || !hashValid(second) || !hashValid(third)) {
            null
        } else {
            first + second + third
        }

    private fun hashValid(bytes: ByteArray): Boolean = bytes.size == HASH_BYTES

    private fun CapabilityNegotiationHeader.isValidFor(message: CapabilityNegotiationMessage): Boolean =
        negotiationId.isValid && bodyLength >= 0 && messageType == when (message) {
            is CapabilityNegotiationMessage.ClientOffer -> CapabilityNegotiationMessageType.ClientOffer
            is CapabilityNegotiationMessage.HostSelection -> CapabilityNegotiationMessageType.HostSelection
            is CapabilityNegotiationMessage.ClientConfirm -> CapabilityNegotiationMessageType.ClientConfirm
            is CapabilityNegotiationMessage.HostComplete -> CapabilityNegotiationMessageType.HostComplete
            is CapabilityNegotiationMessage.Reject -> CapabilityNegotiationMessageType.NegotiationReject
        }

    private class CapabilitySections {
        var core: ByteArray? = null
        var paths: ByteArray? = null
        var video: ByteArray? = null
        var audio: ByteArray? = null
        var input: ByteArray? = null
        var behavior: ByteArray? = null
        var request: ByteArray? = null
        var profile: ByteArray? = null
    }

    private data class ParsedTlvs(val known: List<CapabilityTlv>, val unknownOptional: List<CapabilityTlv>)

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xff

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])

    private fun readU32(bytes: ByteArray, offset: Int): UInt =
        (unsigned(bytes[offset]).toUInt() shl 24) or (unsigned(bytes[offset + 1]).toUInt() shl 16) or
            (unsigned(bytes[offset + 2]).toUInt() shl 8) or unsigned(bytes[offset + 3]).toUInt()

    private fun readU64(bytes: ByteArray, offset: Int): ULong =
        (0 until 8).fold(0uL) { result, index -> (result shl 8) or unsigned(bytes[offset + index]).toULong() }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xffff)
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeU64(bytes: ByteArray, offset: Int, value: ULong) {
        repeat(8) { index -> bytes[offset + index] = (value shr (56 - index * 8)).toByte() }
    }
}
