@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.setup

import io.warpnect.session.ChannelId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import java.security.MessageDigest

/** Strict canonical WNSN V1 codec. It only accepts bytes after RFC-005E SessionControl unprotection. */
object SessionSetupCodec {
    private const val HEADER_TYPE_OFFSET = 5
    private const val HEADER_FLAGS_OFFSET = 6
    private const val HEADER_ID_OFFSET = 8
    private const val HEADER_BODY_LENGTH_OFFSET = 16
    private const val HEADER_RESERVED_OFFSET = 18
    private const val CONFIG_TLV_HEADER_BYTES = 4

    fun encode(message: SessionSetupMessage): ByteArray? {
        return try {
            val body = encodeBody(message) ?: return null
            if (body.size > SessionSetupProtocol.MAX_PAYLOAD_BYTES - SessionSetupProtocol.HEADER_BYTES) return null
            val header = message.header.copy(bodyLength = body.size)
            if (!header.isValidFor(message)) return null
            ByteWriter(SessionSetupProtocol.HEADER_BYTES + body.size).apply {
                writeBytes(SessionSetupProtocol.MAGIC)
                writeU8(SessionSetupProtocol.VERSION)
                writeU8(header.messageType.wireId)
                writeU16(0)
                writeU64(header.setupId.value)
                writeU16(body.size)
                writeU16(0)
                writeBytes(body)
            }.toByteArray()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decode(bytes: ByteArray): DecodedSessionSetupPacket? {
        return try {
            if (bytes.size !in SessionSetupProtocol.HEADER_BYTES..SessionSetupProtocol.MAX_PAYLOAD_BYTES) return null
            val reader = ByteReader(bytes)
            if (!reader.readBytes(4).contentEquals(SessionSetupProtocol.MAGIC) || reader.readU8() != SessionSetupProtocol.VERSION) return null
            val type = SessionSetupMessageType.fromWireId(reader.readU8()) ?: return null
            if (reader.readU16() != 0) return null
            val setupId = SessionSetupId.from(reader.readU64()) ?: return null
            val bodyLength = reader.readU16()
            if (reader.readU16() != 0 || bodyLength != bytes.size - SessionSetupProtocol.HEADER_BYTES) return null
            val header = SessionSetupHeader(type, setupId, bodyLength)
            val body = reader.readBytes(bodyLength)
            if (!reader.exhausted) return null
            val message = when (type) {
                SessionSetupMessageType.ClientSetupRequest -> decodeRequest(header, body)
                SessionSetupMessageType.HostPathDirective -> decodeDirective(header, body)
                SessionSetupMessageType.DirectPathProbe -> decodeProbe(header, body, ack = false)
                SessionSetupMessageType.DirectPathAck -> decodeProbe(header, body, ack = true)
                SessionSetupMessageType.PathFailure -> decodePathFailure(header, body)
                SessionSetupMessageType.ClientEndpointOffer -> decodeEndpointOffer(header, body)
                SessionSetupMessageType.HostConfigurationProposal -> decodeProposal(header, body)
                SessionSetupMessageType.ClientConfigurationAccept -> decodeHashTriple(header, body) { p, e, h ->
                    SessionSetupMessage.ClientConfigurationAccept(header, p, e, h)
                }
                SessionSetupMessageType.ClientConfigurationDecline -> decodeDecline(header, body)
                SessionSetupMessageType.HostCommit -> decodeHashTriple(header, body) { p, e, h ->
                    SessionSetupMessage.HostCommit(header, p, e, h)
                }
                SessionSetupMessageType.SetupReject -> decodeReject(header, body)
            } ?: return null
            DecodedSessionSetupPacket(message, bytes.copyOf())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun encodeBody(message: SessionSetupMessage): ByteArray? = when (message) {
        is SessionSetupMessage.ClientSetupRequest -> {
            if (!hashValid(message.profileHash) || message.selectedChannels !in 0..0xffff || !message.preferences.isStructurallyValid()) {
                null
            } else {
                ByteWriter().apply {
                    writeBytes(message.profileHash)
                    writeU16(message.selectedChannels)
                    writeBytes(encodePreferences(message.preferences) ?: return null)
                }.toByteArray()
            }
        }
        is SessionSetupMessage.HostPathDirective -> {
            if (!hashValid(message.profileHash) || !message.isValidDirective()) {
                null
            } else {
                ByteWriter(52).apply {
                    writeBytes(message.profileHash)
                    writeU8(message.directive.wireId)
                    writeU8(message.activeCandidate.toWirePathKind())
                    writeU8(message.standbyCandidate?.toWirePathKind() ?: 0)
                    writeU8(0)
                    writeU64(message.pathAttemptId?.value ?: 0uL)
                    writeU16(message.hostDirectProbePort)
                    writeU16(message.directTimeoutMs)
                    writeU16(0)
                }.toByteArray()
            }
        }
        is SessionSetupMessage.DirectPathProbe -> encodeProbe(message.profileHash, message.pathAttemptId)
        is SessionSetupMessage.DirectPathAck -> encodeProbe(message.profileHash, message.pathAttemptId)
        is SessionSetupMessage.PathFailure -> {
            if (!hashValid(message.profileHash) || !message.pathAttemptId.isValid) {
                null
            } else {
                ByteWriter(44).apply {
                    writeBytes(message.profileHash)
                    writeU64(message.pathAttemptId.value)
                    writeU8(message.reason.wireId)
                    writeBytes(ByteArray(3))
                }.toByteArray()
            }
        }
        is SessionSetupMessage.ClientEndpointOffer -> {
            if (!hashValid(message.profileHash) || !message.endpoints.areValidEndpointSet()) {
                null
            } else {
                ByteWriter().apply {
                    writeBytes(message.profileHash)
                    writeU8(message.activePathKind.toWirePathKind())
                    writeU8(message.endpoints.size)
                    writeU16(0)
                    message.endpoints.sortedBy { it.kind.toWireChannelKind() }.forEach {
                        writeU8(it.kind.toWireChannelKind())
                        writeU8(it.instanceIndex)
                        writeU16(it.localPort)
                    }
                }.toByteArray()
            }
        }
        is SessionSetupMessage.HostConfigurationProposal -> encodeProposal(message)
        is SessionSetupMessage.ClientConfigurationAccept -> encodeHashTriple(
            message.profileHash,
            message.clientEndpointOfferHash,
            message.proposalHash,
        )
        is SessionSetupMessage.ClientConfigurationDecline -> {
            if (!hashValid(message.proposalHash) || message.proposalGeneration !in 1..SessionSetupProtocol.MAX_PROPOSAL_GENERATION) {
                null
            } else {
                ByteWriter(40).apply {
                    writeBytes(message.proposalHash)
                    writeU16(message.proposalGeneration)
                    writeU16(message.reason.ordinal)
                    writeU32(0u)
                }.toByteArray()
            }
        }
        is SessionSetupMessage.HostCommit -> encodeHashTriple(
            message.profileHash,
            message.clientEndpointOfferHash,
            message.proposalHash,
        )
        is SessionSetupMessage.Reject -> {
            if (!hashValid(message.relatedHash)) {
                null
            } else {
                ByteWriter(36).apply {
                    writeU8(message.stage.wireId)
                    writeU8(message.reason.wireId)
                    writeU16(0)
                    writeBytes(message.relatedHash)
                }.toByteArray()
            }
        }
    }

    private fun decodeRequest(header: SessionSetupHeader, body: ByteArray): SessionSetupMessage.ClientSetupRequest? {
        val reader = ByteReader(body)
        val hash = reader.readBytes(SessionSetupProtocol.HASH_BYTES)
        val channels = reader.readU16()
        val preferences = decodePreferences(reader) ?: return null
        return if (reader.exhausted && hashValid(hash) && preferences.isStructurallyValid()) {
            SessionSetupMessage.ClientSetupRequest(header, hash, channels, preferences)
        } else {
            null
        }
    }

    private fun decodeDirective(header: SessionSetupHeader, body: ByteArray): SessionSetupMessage.HostPathDirective? {
        if (body.size != 50) return null
        val reader = ByteReader(body)
        val hash = reader.readBytes(SessionSetupProtocol.HASH_BYTES)
        val directive = PathDirective.fromWireId(reader.readU8()) ?: return null
        val active = fromWirePathKind(reader.readU8()) ?: return null
        val standby = fromWirePathKindOrNull(reader.readU8())
        if (reader.readU8() != 0) return null
        val attempt = reader.readU64().takeIf {
            it != 0uL
        }?.let(PathAttemptId::from) ?: if (directive == PathDirective.UseLan) {
            null
        } else {
            return null
        }
        val port = reader.readU16()
        val timeout = reader.readU16()
        if (reader.readU16() != 0 || !reader.exhausted) return null
        return SessionSetupMessage.HostPathDirective(header, hash, directive, active, standby, attempt, port, timeout)
            .takeIf { hashValid(hash) && it.isValidDirective() }
    }

    private fun decodeProbe(header: SessionSetupHeader, body: ByteArray, ack: Boolean): SessionSetupMessage? {
        if (body.size != 40) return null
        val reader = ByteReader(body)
        val hash = reader.readBytes(SessionSetupProtocol.HASH_BYTES)
        val attempt = PathAttemptId.from(reader.readU64()) ?: return null
        if (!hashValid(hash) || !reader.exhausted) return null
        return if (ack) {
            SessionSetupMessage.DirectPathAck(header, hash, attempt)
        } else {
            SessionSetupMessage.DirectPathProbe(header, hash, attempt)
        }
    }

    private fun decodePathFailure(header: SessionSetupHeader, body: ByteArray): SessionSetupMessage.PathFailure? {
        if (body.size != 44) return null
        val reader = ByteReader(body)
        val hash = reader.readBytes(SessionSetupProtocol.HASH_BYTES)
        val attempt = PathAttemptId.from(reader.readU64()) ?: return null
        val reason = PathFailureReason.fromWireId(reader.readU8()) ?: return null
        if (!reader.readBytes(3).all { it == 0.toByte() } || !reader.exhausted || !hashValid(hash)) return null
        return SessionSetupMessage.PathFailure(header, hash, attempt, reason)
    }

    private fun decodeEndpointOffer(
        header: SessionSetupHeader,
        body: ByteArray,
    ): SessionSetupMessage.ClientEndpointOffer? {
        if (body.size < 36) return null
        val reader = ByteReader(body)
        val hash = reader.readBytes(SessionSetupProtocol.HASH_BYTES)
        val pathKind = fromWirePathKind(reader.readU8()) ?: return null
        val count = reader.readU8()
        if (reader.readU16() != 0 || count !in 1..SessionSetupProtocol.MAX_CHANNELS || reader.remaining != count * 4) return null
        val endpoints = List(count) {
            ChannelEndpointOffer(
                kind = fromWireChannelKind(reader.readU8()) ?: return null,
                instanceIndex = reader.readU8(),
                localPort = reader.readU16(),
            )
        }
        return if (reader.exhausted && hashValid(hash) && endpoints.areValidEndpointSet()) {
            SessionSetupMessage.ClientEndpointOffer(header, hash, pathKind, endpoints)
        } else {
            null
        }
    }

    private fun encodeProposal(message: SessionSetupMessage.HostConfigurationProposal): ByteArray? {
        if (!hashValid(message.profileHash) || !hashValid(message.clientEndpointOfferHash) ||
            message.proposalGeneration !in 1..SessionSetupProtocol.MAX_PROPOSAL_GENERATION ||
            message.descriptors.size !in 1..SessionSetupProtocol.MAX_CHANNELS ||
            message.configurations.size !in 1..SessionSetupProtocol.MAX_CONFIGURATION_TLVS ||
            !message.descriptors.areValidDescriptors() || !message.configurations.areValidConfigurations()
        ) {
            return null
        }
        val tlvs = message.configurations.sortedWith(configOrder).map { encodeConfigTlv(it) ?: return null }
        if (tlvs.zipWithNext().any { (left, right) ->
                !isStrictlyBefore(
                    left.orderKey(),
                    right.orderKey(),
                )
            }
        ) {
            return null
        }
        return ByteWriter().apply {
            writeBytes(message.profileHash)
            writeBytes(message.clientEndpointOfferHash)
            writeU16(message.proposalGeneration)
            writeU8(message.activePathKind.toWirePathKind())
            writeU8(message.standbyPathKind?.toWirePathKind() ?: 0)
            writeU32(message.activePathId.value)
            writeU32(message.standbyPathId?.value ?: 0u)
            writeU8(message.descriptors.size)
            writeU8(tlvs.size)
            writeU16(0)
            message.descriptors.sortedBy { it.kind.toWireChannelKind() }.forEach { descriptor ->
                writeDescriptor(
                    descriptor,
                )
            }
            tlvs.forEach { tlv ->
                writeU16(tlv.type)
                writeU16(tlv.value.size)
                writeBytes(tlv.value)
            }
        }.toByteArray().takeIf { it.size <= SessionSetupProtocol.MAX_PAYLOAD_BYTES - SessionSetupProtocol.HEADER_BYTES }
    }

    private fun decodeProposal(
        header: SessionSetupHeader,
        body: ByteArray,
    ): SessionSetupMessage.HostConfigurationProposal? {
        if (body.size < 80) return null
        val reader = ByteReader(body)
        val profileHash = reader.readBytes(32)
        val offerHash = reader.readBytes(32)
        val generation = reader.readU16()
        val activeKind = fromWirePathKind(reader.readU8()) ?: return null
        val standbyKind = fromWirePathKindOrNull(reader.readU8())
        val activePathId = PathId.from(reader.readU32()) ?: return null
        val standbyPathId = reader.readU32().takeIf {
            it != 0u
        }?.let(PathId::from) ?: if (standbyKind == null) null else return null
        val channelCount = reader.readU8()
        val tlvCount = reader.readU8()
        if (reader.readU16() != 0 || generation !in 1..SessionSetupProtocol.MAX_PROPOSAL_GENERATION ||
            channelCount !in 1..SessionSetupProtocol.MAX_CHANNELS || tlvCount !in 1..SessionSetupProtocol.MAX_CONFIGURATION_TLVS
        ) {
            return null
        }
        val descriptors = List(channelCount) { readDescriptor(reader) ?: return null }
        val configurations = ArrayList<SetupConfiguration>(tlvCount)
        var previousKey: Pair<UInt, Int>? = null
        repeat(tlvCount) {
            val type = reader.readU16()
            val length = reader.readU16()
            if (length > reader.remaining) return null
            val configuration = decodeConfigTlv(type, reader.readBytes(length)) ?: return null
            val key = configuration.channelId.value to type
            if (previousKey != null && !isStrictlyBefore(previousKey!!, key)) return null
            configurations += configuration
            previousKey = key
        }
        if (!reader.exhausted || !hashValid(profileHash) || !hashValid(offerHash) || !descriptors.areValidDescriptors() ||
            !configurations.areValidConfigurations()
        ) {
            return null
        }
        return SessionSetupMessage.HostConfigurationProposal(
            header, profileHash, offerHash, generation, activeKind, standbyKind, activePathId, standbyPathId,
            descriptors, configurations,
        )
    }

    private fun decodeHashTriple(
        header: SessionSetupHeader,
        body: ByteArray,
        factory: (ByteArray, ByteArray, ByteArray) -> SessionSetupMessage,
    ): SessionSetupMessage? {
        if (body.size != 96) return null
        val first = body.copyOfRange(0, 32)
        val second = body.copyOfRange(32, 64)
        val third = body.copyOfRange(64, 96)
        return if (hashValid(first) && hashValid(second) && hashValid(third)) factory(first, second, third) else null
    }

    private fun decodeDecline(
        header: SessionSetupHeader,
        body: ByteArray,
    ): SessionSetupMessage.ClientConfigurationDecline? {
        if (body.size != 40) return null
        val reader = ByteReader(body)
        val hash = reader.readBytes(32)
        val generation = reader.readU16()
        val error = SessionSetupError.entries.getOrNull(reader.readU16()) ?: return null
        if (reader.readU32() != 0u || !reader.exhausted || !hashValid(hash) || generation !in 1..SessionSetupProtocol.MAX_PROPOSAL_GENERATION) return null
        return SessionSetupMessage.ClientConfigurationDecline(header, hash, generation, error)
    }

    private fun decodeReject(header: SessionSetupHeader, body: ByteArray): SessionSetupMessage.Reject? {
        if (body.size != 36) return null
        val reader = ByteReader(body)
        val stage = SetupRejectStage.fromWireId(reader.readU8()) ?: return null
        val reason = SetupRejectReason.fromWireId(reader.readU8()) ?: return null
        if (reader.readU16() != 0) return null
        val hash = reader.readBytes(32)
        return if (reader.exhausted && hashValid(hash)) {
            SessionSetupMessage.Reject(
                header,
                stage,
                reason,
                hash,
            )
        } else {
            null
        }
    }

    private fun encodeProbe(hash: ByteArray, attempt: PathAttemptId): ByteArray? =
        if (!hashValid(hash) || !attempt.isValid) {
            null
        } else {
            ByteWriter(40).apply {
                writeBytes(hash)
                writeU64(attempt.value)
            }.toByteArray()
        }

    private fun encodeHashTriple(first: ByteArray, second: ByteArray, third: ByteArray): ByteArray? =
        if (!hashValid(first) || !hashValid(second) || !hashValid(third)) null else first + second + third

    private fun encodePreferences(value: SessionSetupPreferences): ByteArray? {
        if (!value.isStructurallyValid()) return null
        return ByteWriter().apply {
            writeU8(value.pathPreference.ordinal + 1)
            writeU8(value.secondaryPathPolicy.ordinal + 1)
            writeU16(0)
            writeVideoPreference(value.video)
            writeAudioPreference(value.systemAudio)
            writeAudioPreference(value.microphoneAudio)
            writeInputPreference(value.input)
        }.toByteArray()
    }

    private fun ByteWriter.writeVideoPreference(value: VideoStreamPreference?) {
        writeU8(if (value == null) 0 else 1)
        if (value == null) {
            writeBytes(ByteArray(3))
            return
        }
        writeU8(value.policy.wireId)
        writeU8(value.modes.size)
        writeU8(0)
        value.modes.forEach { mode ->
            writeU16(mode.width)
            writeU16(mode.height)
            writeU16(mode.fps)
            writeU32(mode.bitrateBps.toUInt())
            writeU32(mode.flags.toUInt())
        }
    }

    private fun ByteWriter.writeAudioPreference(value: AudioStreamPreference?) {
        writeU8(value?.modes?.size ?: 0)
        value?.modes?.forEach { mode ->
            writeU32(mode.sampleRateHz.toUInt())
            writeU16(mode.frameDurationUs)
            writeU8(mode.channelCount)
            writeU8(0)
            writeU32(mode.bitrateBps.toUInt())
        }
    }

    private fun ByteWriter.writeInputPreference(value: InputStreamConfiguration?) {
        writeU8(if (value == null) 0 else 1)
        if (value == null) return
        writeU16(value.inputKinds)
        writeU16(value.stablePresenceKinds)
        writeU32(value.featureFlags.toUInt())
        writeU8(value.criticalCopies)
        writeU8(value.resetCopies)
        writeU32(value.networkReorderWaitUs.toUInt())
        writeU16(value.transportDuplicateWindow)
        writeU16(value.semanticIdentityCache)
    }

    private fun decodePreferences(reader: ByteReader): SessionSetupPreferences? {
        val path = PathPreferencePolicy.entries.getOrNull(reader.readU8() - 1) ?: return null
        val standby = SecondaryPathPolicy.entries.getOrNull(reader.readU8() - 1) ?: return null
        if (reader.readU16() != 0) return null
        val video = reader.readVideoPreference()
        val systemAudio = reader.readAudioPreference()
        val microphoneAudio = reader.readAudioPreference()
        val input = reader.readInputPreference()
        return SessionSetupPreferences(path, standby, video, systemAudio, microphoneAudio, input)
    }

    private fun ByteReader.readVideoPreference(): VideoStreamPreference? {
        return when (readU8()) {
            0 -> if (readBytes(3).all { it == 0.toByte() }) null else throw IllegalArgumentException()
            1 -> {
                val policy = VideoPreferencePolicy.fromWireId(readU8()) ?: throw IllegalArgumentException()
                val count = readU8()
                if (readU8() != 0 || count !in 1..4) throw IllegalArgumentException()
                val modes = List(count) {
                    VideoStreamMode(readU16(), readU16(), readU16(), readU32().toLong(), readU32().toInt())
                }
                VideoStreamPreference(policy, modes).takeIf(VideoStreamPreference::isValid)
                    ?: throw IllegalArgumentException()
            }
            else -> throw IllegalArgumentException()
        }
    }

    private fun ByteReader.readAudioPreference(): AudioStreamPreference? {
        val count = readU8()
        if (count == 0) return null
        if (count !in 1..4) throw IllegalArgumentException()
        val modes = List(count) {
            val sampleRate = readU32().toInt()
            val duration = readU16()
            val channels = readU8()
            if (readU8() != 0) throw IllegalArgumentException()
            AudioStreamMode(sampleRate, duration, channels, readU32().toLong())
        }
        return AudioStreamPreference(modes).takeIf(AudioStreamPreference::isValid) ?: throw IllegalArgumentException()
    }

    private fun ByteReader.readInputPreference(): InputStreamConfiguration? = when (readU8()) {
        0 -> null
        1 -> InputStreamConfiguration(
            inputKinds = readU16(),
            stablePresenceKinds = readU16(),
            featureFlags = readU32().toInt(),
            criticalCopies = readU8(),
            resetCopies = readU8(),
            networkReorderWaitUs = readU32().toLong(),
            transportDuplicateWindow = readU16(),
            semanticIdentityCache = readU16(),
        ).takeIf(InputStreamConfiguration::isValid) ?: throw IllegalArgumentException()
        else -> throw IllegalArgumentException()
    }

    private fun ByteWriter.writeDescriptor(value: ChannelDescriptor) {
        writeU32(value.channelId.value)
        writeU8(value.kind.toWireChannelKind())
        writeU8(value.direction.toWireDirection())
        writeU8(value.instanceIndex)
        writeU8(0)
        writeU32(value.pathId.value)
        writeU16(value.hostLocalPort)
        writeU16(value.clientLocalPort)
        writeU16(value.maxSecureDatagramBytes)
        writeU16(value.recoveryFlags)
    }

    private fun readDescriptor(reader: ByteReader): ChannelDescriptor? {
        val id = ChannelId.from(reader.readU32()) ?: return null
        val kind = fromWireChannelKind(reader.readU8()) ?: return null
        val direction = fromWireDirection(reader.readU8()) ?: return null
        val instance = reader.readU8()
        if (reader.readU8() != 0) return null
        val path = PathId.from(reader.readU32()) ?: return null
        return ChannelDescriptor(id, kind, direction, instance, path, reader.readU16(), reader.readU16(), reader.readU16(), reader.readU16())
            .takeIf(ChannelDescriptor::isValid)
    }

    private data class EncodedConfig(val type: Int, val value: ByteArray) {
        fun orderKey(): Pair<UInt, Int> = ByteReader(value).readU32() to type
    }

    private fun isStrictlyBefore(left: Pair<UInt, Int>, right: Pair<UInt, Int>): Boolean =
        left.first < right.first || left.first == right.first && left.second < right.second

    private fun encodeConfigTlv(configuration: SetupConfiguration): EncodedConfig? = when (configuration) {
        is SetupConfiguration.Video -> configuration.mode.takeIf(VideoStreamMode::isValid)?.let { mode ->
            EncodedConfig(
                1,
                ByteWriter(18).apply {
                    writeU32(configuration.channelId.value)
                    writeU8(1)
                    writeU8(mode.flags)
                    writeU16(mode.width)
                    writeU16(mode.height)
                    writeU16(mode.fps)
                    writeU32(mode.bitrateBps.toUInt())
                    writeU16(0)
                }.toByteArray(),
            )
        }
        is SetupConfiguration.SystemAudio -> encodeAudioConfig(2, configuration.channelId, configuration.mode)
        is SetupConfiguration.MicrophoneAudio -> encodeAudioConfig(3, configuration.channelId, configuration.mode)
        is SetupConfiguration.Input -> configuration.config.takeIf(InputStreamConfiguration::isValid)?.let { config ->
            EncodedConfig(
                4,
                ByteWriter(22).apply {
                    writeU32(configuration.channelId.value)
                    writeU16(config.inputKinds)
                    writeU16(config.stablePresenceKinds)
                    writeU32(config.featureFlags.toUInt())
                    writeU8(config.criticalCopies)
                    writeU8(config.resetCopies)
                    writeU32(config.networkReorderWaitUs.toUInt())
                    writeU16(config.transportDuplicateWindow)
                    writeU16(config.semanticIdentityCache)
                }.toByteArray(),
            )
        }
        is SetupConfiguration.Telemetry -> EncodedConfig(
            5,
            ByteWriter(8).apply {
                writeU32(configuration.channelId.value)
                writeU32(configuration.featureFlags.toUInt())
            }.toByteArray(),
        )
        is SetupConfiguration.Recovery -> configuration.config.takeIf(RecoveryConfiguration::isValid)?.let { config ->
            EncodedConfig(
                6,
                ByteWriter(12).apply {
                    writeU32(configuration.channelId.value)
                    writeU16(config.recoveryFlags)
                    writeU8(config.fecDataShards)
                    writeU8(config.fecParityShards)
                    writeU16(config.retransmissionCacheSlots)
                    writeU16(0)
                }.toByteArray(),
            )
        }
    }

    private fun encodeAudioConfig(type: Int, channelId: ChannelId, mode: AudioStreamMode): EncodedConfig? =
        mode.takeIf(AudioStreamMode::isValid)?.let {
            EncodedConfig(
                type,
                ByteWriter(18).apply {
                    writeU32(channelId.value)
                    writeU8(1)
                    writeU8(0)
                    writeU32(it.sampleRateHz.toUInt())
                    writeU16(it.frameDurationUs)
                    writeU8(it.channelCount)
                    writeU8(0)
                    writeU32(it.bitrateBps.toUInt())
                }.toByteArray(),
            )
        }

    private fun decodeConfigTlv(type: Int, value: ByteArray): SetupConfiguration? {
        val reader = ByteReader(value)
        return when (type) {
            1 -> {
                val id = ChannelId.from(reader.readU32()) ?: return null
                if (reader.readU8() != 1) return null
                val flags = reader.readU8()
                val mode =
                    VideoStreamMode(
                        reader.readU16(),
                        reader.readU16(),
                        reader.readU16(),
                        reader.readU32().toLong(),
                        flags,
                    )
                if (reader.readU16() != 0 || !reader.exhausted || !mode.isValid()) {
                    null
                } else {
                    SetupConfiguration.Video(
                        id,
                        mode,
                    )
                }
            }
            2,
            3,
            -> {
                val id = ChannelId.from(reader.readU32()) ?: return null
                if (reader.readU8() != 1 || reader.readU8() != 0) return null
                val mode = AudioStreamMode(
                    reader.readU32().toInt(),
                    reader.readU16(),
                    reader.readU8(),
                    run {
                        if (reader.readU8() != 0) return null
                        reader.readU32().toLong()
                    },
                )
                if (!reader.exhausted || !mode.isValid()) {
                    null
                } else if (type == 2) {
                    SetupConfiguration.SystemAudio(id, mode)
                } else {
                    SetupConfiguration.MicrophoneAudio(id, mode)
                }
            }
            4 -> {
                val id = ChannelId.from(reader.readU32()) ?: return null
                val config = InputStreamConfiguration(
                    reader.readU16(),
                    reader.readU16(),
                    reader.readU32().toInt(),
                    reader.readU8(),
                    reader.readU8(),
                    reader.readU32().toLong(),
                    reader.readU16(),
                    reader.readU16(),
                )
                if (!reader.exhausted || !config.isValid()) null else SetupConfiguration.Input(id, config)
            }
            5 -> {
                val id = ChannelId.from(reader.readU32()) ?: return null
                val flags = reader.readU32().toInt()
                if (!reader.exhausted) null else SetupConfiguration.Telemetry(id, flags)
            }
            6 -> {
                val id = ChannelId.from(reader.readU32()) ?: return null
                val recovery =
                    RecoveryConfiguration(id, reader.readU16(), reader.readU8(), reader.readU8(), reader.readU16())
                if (reader.readU16() != 0 || !reader.exhausted || !recovery.isValid()) {
                    null
                } else {
                    SetupConfiguration.Recovery(
                        id,
                        recovery,
                    )
                }
            }
            else -> null
        }
    }

    private val configOrder = compareBy<SetupConfiguration>({ it.channelId.value.toLong() }, { it.configWireType() })

    private fun SessionSetupHeader.isValidFor(message: SessionSetupMessage): Boolean =
        setupId.isValid && bodyLength >= 0 &&
            messageType == when (message) {
                is SessionSetupMessage.ClientSetupRequest -> SessionSetupMessageType.ClientSetupRequest
                is SessionSetupMessage.HostPathDirective -> SessionSetupMessageType.HostPathDirective
                is SessionSetupMessage.DirectPathProbe -> SessionSetupMessageType.DirectPathProbe
                is SessionSetupMessage.DirectPathAck -> SessionSetupMessageType.DirectPathAck
                is SessionSetupMessage.PathFailure -> SessionSetupMessageType.PathFailure
                is SessionSetupMessage.ClientEndpointOffer -> SessionSetupMessageType.ClientEndpointOffer
                is SessionSetupMessage.HostConfigurationProposal -> SessionSetupMessageType.HostConfigurationProposal
                is SessionSetupMessage.ClientConfigurationAccept -> SessionSetupMessageType.ClientConfigurationAccept
                is SessionSetupMessage.ClientConfigurationDecline -> SessionSetupMessageType.ClientConfigurationDecline
                is SessionSetupMessage.HostCommit -> SessionSetupMessageType.HostCommit
                is SessionSetupMessage.Reject -> SessionSetupMessageType.SetupReject
            }

    private fun SessionSetupPreferences.isStructurallyValid(): Boolean = (video?.isValid() ?: true) &&
        (systemAudio?.isValid() ?: true) && (microphoneAudio?.isValid() ?: true) && (input?.isValid() ?: true)

    private fun SessionSetupMessage.HostPathDirective.isValidDirective(): Boolean = when (directive) {
        PathDirective.UseLan ->
            activeCandidate == NetworkPathKind.Lan && pathAttemptId == null && hostDirectProbePort == 0 &&
                directTimeoutMs == 0
        PathDirective.AttemptDirect ->
            activeCandidate == NetworkPathKind.Direct && pathAttemptId?.isValid == true &&
                hostDirectProbePort in 1..0xffff && directTimeoutMs in 1..0xffff
    }

    private fun List<ChannelEndpointOffer>.areValidEndpointSet(): Boolean =
        size in 1..SessionSetupProtocol.MAX_CHANNELS &&
            all(ChannelEndpointOffer::isValid) && map(ChannelEndpointOffer::kind).distinct().size == size

    private fun List<ChannelDescriptor>.areValidDescriptors(): Boolean = size in 1..SessionSetupProtocol.MAX_CHANNELS &&
        all(ChannelDescriptor::isValid) && map(ChannelDescriptor::channelId).distinct().size == size &&
        map(ChannelDescriptor::kind).distinct().size == size

    private fun List<SetupConfiguration>.areValidConfigurations(): Boolean =
        size in 1..SessionSetupProtocol.MAX_CONFIGURATION_TLVS &&
            map { it.channelId to it.configWireType() }.distinct().size == size && all { encodeConfigTlv(it) != null }

    private fun SetupConfiguration.configWireType(): Int = when (this) {
        is SetupConfiguration.Video -> 1
        is SetupConfiguration.SystemAudio -> 2
        is SetupConfiguration.MicrophoneAudio -> 3
        is SetupConfiguration.Input -> 4
        is SetupConfiguration.Telemetry -> 5
        is SetupConfiguration.Recovery -> 6
    }

    private fun NetworkPathKind.toWirePathKind(): Int = when (this) {
        NetworkPathKind.Direct -> 1
        NetworkPathKind.Lan -> 2
    }

    private fun fromWirePathKind(value: Int): NetworkPathKind? = when (value) {
        1 -> NetworkPathKind.Direct
        2 -> NetworkPathKind.Lan
        else -> null
    }

    private fun fromWirePathKindOrNull(value: Int): NetworkPathKind? = if (value == 0) {
        null
    } else {
        fromWirePathKind(value)
    }

    private fun SessionChannelKind.toWireChannelKind(): Int = when (this) {
        SessionChannelKind.Video -> 1
        SessionChannelKind.SystemAudio -> 2
        SessionChannelKind.MicrophoneAudio -> 3
        SessionChannelKind.Input -> 4
        SessionChannelKind.Telemetry -> 5
        SessionChannelKind.Control -> 0
    }

    private fun fromWireChannelKind(value: Int): SessionChannelKind? = when (value) {
        1 -> SessionChannelKind.Video
        2 -> SessionChannelKind.SystemAudio
        3 -> SessionChannelKind.MicrophoneAudio
        4 -> SessionChannelKind.Input
        5 -> SessionChannelKind.Telemetry
        else -> null
    }

    private fun SessionChannelDirection.toWireDirection(): Int = when (this) {
        SessionChannelDirection.HostToClient -> 1
        SessionChannelDirection.ClientToHost -> 2
        SessionChannelDirection.Bidirectional -> 3
    }

    private fun fromWireDirection(value: Int): SessionChannelDirection? = when (value) {
        1 -> SessionChannelDirection.HostToClient
        2 -> SessionChannelDirection.ClientToHost
        3 -> SessionChannelDirection.Bidirectional
        else -> null
    }

    private fun hashValid(value: ByteArray): Boolean = value.size == SessionSetupProtocol.HASH_BYTES

    private class ByteWriter(initialCapacity: Int = 64) {
        private val bytes = ArrayList<Byte>(initialCapacity)

        fun writeU8(value: Int) {
            require(value in 0..0xff)
            bytes += value.toByte()
        }

        fun writeU16(value: Int) {
            require(value in 0..0xffff)
            writeU8(value ushr 8)
            writeU8(value and 0xff)
        }

        fun writeU32(value: UInt) {
            repeat(4) { index -> writeU8((value shr (24 - index * 8)).toInt() and 0xff) }
        }

        fun writeU64(value: ULong) {
            repeat(8) { index -> writeU8((value shr (56 - index * 8)).toInt() and 0xff) }
        }

        fun writeBytes(value: ByteArray) {
            value.forEach(bytes::add)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private class ByteReader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset
        val exhausted: Boolean get() = remaining == 0

        fun readU8(): Int = requireRemaining(1).let { bytes[offset++].toInt() and 0xff }
        fun readU16(): Int = (readU8() shl 8) or readU8()
        fun readU32(): UInt = (readU8().toUInt() shl 24) or (readU8().toUInt() shl 16) or
            (readU8().toUInt() shl 8) or readU8().toUInt()
        fun readU64(): ULong = (0 until 8).fold(0uL) { result, _ -> (result shl 8) or readU8().toULong() }

        fun readBytes(size: Int): ByteArray {
            require(size >= 0)
            requireRemaining(size)
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        private fun requireRemaining(size: Int) {
            require(remaining >= size) { "Truncated WNSN" }
        }
    }
}

private fun SessionChannelKind.defaultDirection(): SessionChannelDirection = when (this) {
    SessionChannelKind.Video,
    SessionChannelKind.SystemAudio,
    -> SessionChannelDirection.HostToClient
    SessionChannelKind.MicrophoneAudio,
    SessionChannelKind.Input,
    -> SessionChannelDirection.ClientToHost
    SessionChannelKind.Telemetry,
    SessionChannelKind.Control,
    -> SessionChannelDirection.Bidirectional
}
