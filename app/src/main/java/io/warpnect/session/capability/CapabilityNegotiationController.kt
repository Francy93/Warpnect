@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.capability

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.handshake.AuthenticatedSessionAdmissionReservation
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.SessionProtectionRuntime
import java.util.Collections

fun interface LocalCapabilityCollector {
    /** Must only inspect existing capability APIs; it must not start media/input/path resources. */
    fun collect(role: SessionRole): LocalCapabilitySnapshot
}

fun interface CapabilityNegotiationMonotonicClock {
    fun nowMs(): Long
}

data class SecureSessionCapabilityBootstrap(
    val sessionId: SessionId,
    val generation: SessionGeneration,
    val localDeviceId: DeviceId,
    val remoteDeviceId: DeviceId,
    val localRole: SessionRole,
    val remoteRole: SessionRole,
    val endpoint: HandshakeTransportEndpoint,
    val protection: SessionProtectionRuntime,
    val admissionReservation: AuthenticatedSessionAdmissionReservation? = null,
)

data class NegotiatedSessionBootstrap(
    val sessionId: SessionId,
    val generation: SessionGeneration,
    val localDeviceId: DeviceId,
    val remoteDeviceId: DeviceId,
    val localRole: SessionRole,
    val remoteRole: SessionRole,
    val profile: NegotiatedCapabilityProfile,
    val profileHash: ByteArray,
    val protection: SessionProtectionRuntime,
    val secureSessionControl: SecureSessionControlTransport,
    val admissionReservation: AuthenticatedSessionAdmissionReservation? = null,
)

data class CapabilityNegotiationConfig(
    val maxActiveNegotiations: Int = CapabilityNegotiationProtocol.DEFAULT_MAX_ACTIVE_NEGOTIATIONS,
    val timeoutMs: Long = CapabilityNegotiationProtocol.DEFAULT_TIMEOUT_MS,
    val completionCacheRetentionMs: Long = CapabilityNegotiationProtocol.COMPLETION_CACHE_RETENTION_MS,
) {
    fun isValid(): Boolean = maxActiveNegotiations in 1..CapabilityNegotiationProtocol.HARD_MAX_ACTIVE_NEGOTIATIONS &&
        timeoutMs > 0L && completionCacheRetentionMs > 0L
}

enum class CapabilityNegotiationState {
    Idle,
    ClientOfferSent,
    HostSelectionSent,
    ClientConfirmSent,
    Completed,
    Rejected,
    TimedOut,
    Failed,
    Closed,
}

data class CapabilityNegotiationSnapshot(
    val closed: Boolean,
    val activeNegotiations: Int,
    val completedNegotiations: Long,
    val retries: Long,
    val timeouts: Long,
    val malformedMessages: Long,
    val unknownCriticalTlvRejects: Long,
    val semanticConflicts: Long,
    val requiredFeatureRejects: Long,
    val hostPolicyRejects: Long,
    val invalidSelections: Long,
    val secureControlFailures: Long,
    val completionCacheSize: Int,
    val lastNegotiationId: CapabilityNegotiationId?,
    val lastPeerDeviceId: DeviceId?,
    val lastProfileHash: ByteArray?,
    val lastDurationMs: Long?,
    val lastError: CapabilityNegotiationError,
)

/**
 * Serialized bounded WNCP state machine. Its transport has already authenticated/decrypted the
 * payload; raw UDP and plaintext WNCP are deliberately outside this API.
 */
class CapabilityNegotiationController(
    private val collector: LocalCapabilityCollector,
    private val clock: CapabilityNegotiationMonotonicClock,
    private val idGenerator: CapabilityNegotiationIdGenerator = SecureCapabilityNegotiationIdGenerator,
    private val config: CapabilityNegotiationConfig = CapabilityNegotiationConfig(),
    private val onCompleted: (NegotiatedSessionBootstrap) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val bindings = LinkedHashMap<SessionId, Binding>()
    private val active = LinkedHashMap<NegotiationKey, ManagedNegotiation>()
    private val completed = LinkedHashMap<NegotiationKey, CompletedNegotiation>()
    private val counters = Counters()
    private var closed = false

    fun registerHost(
        bootstrap: SecureSessionCapabilityBootstrap,
        transport: SecureSessionControlTransport,
        policy: HostCapabilityPolicy,
    ): CapabilityNegotiationError = synchronized(lock) {
        if (closed) return@synchronized record(CapabilityNegotiationError.Closed)
        if (bootstrap.localRole != SessionRole.Host || bootstrap.remoteRole != SessionRole.Client || !policy.isValid()) {
            return@synchronized record(CapabilityNegotiationError.RoleMismatch)
        }
        if (bindings.containsKey(bootstrap.sessionId)) return@synchronized record(CapabilityNegotiationError.Busy)
        bindings[bootstrap.sessionId] = Binding(bootstrap, transport, policy)
        transport.setPayloadListener { bytes -> receive(bootstrap.sessionId, bytes) }
        CapabilityNegotiationError.None
    }

    fun beginClient(
        bootstrap: SecureSessionCapabilityBootstrap,
        transport: SecureSessionControlTransport,
        request: CapabilityRequest,
    ): CapabilityNegotiationError = synchronized(lock) {
        if (closed) return@synchronized record(CapabilityNegotiationError.Closed)
        if (bootstrap.localRole != SessionRole.Client || bootstrap.remoteRole != SessionRole.Host || !request.isValid()) {
            return@synchronized record(CapabilityNegotiationError.RoleMismatch)
        }
        if (active.size >= config.maxActiveNegotiations || bindings.containsKey(bootstrap.sessionId)) {
            return@synchronized record(CapabilityNegotiationError.Busy)
        }
        val snapshot = freeze(collector.collect(SessionRole.Client))
        if (snapshot.role != SessionRole.Client || !snapshot.isValid()) {
            return@synchronized record(
                CapabilityNegotiationError.MalformedCapabilities,
            )
        }
        val negotiationId = idGenerator.next()
        val header = CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientOffer, negotiationId, 0)
        val offer = CapabilityNegotiationMessage.ClientOffer(header, snapshot, request)
        val offerBytes = CapabilityNegotiationCodec.encode(offer)
            ?: return@synchronized record(CapabilityNegotiationError.CapabilityPayloadTooLarge)
        if (offerBytes.size > transport.maxPayloadBytes) {
            return@synchronized record(
                CapabilityNegotiationError.CapabilityPayloadTooLarge,
            )
        }
        val now = now()
        val managed = ManagedNegotiation(
            key = NegotiationKey(bootstrap.sessionId, negotiationId),
            binding = Binding(bootstrap, transport, null),
            state = CapabilityNegotiationState.ClientOfferSent,
            startedAtMs = now,
            clientSnapshot = snapshot,
            request = request,
            clientOfferBytes = offerBytes,
            clientOfferHash = CapabilityNegotiationCodec.hash(offerBytes),
            outboundBytes = offerBytes,
            nextRetryAtMs = now + CapabilityNegotiationProtocol.RETRY_DELAYS_MS.first(),
        )
        bindings[bootstrap.sessionId] = managed.binding
        active[managed.key] = managed
        transport.setPayloadListener { bytes -> receive(bootstrap.sessionId, bytes) }
        if (!send(
                managed,
                offerBytes,
                retry = false,
            )
        ) {
            CapabilityNegotiationError.SecureControlFailure
        } else {
            CapabilityNegotiationError.None
        }
    }

    fun receive(sessionId: SessionId, wncpPayload: ByteArray) = synchronized(lock) {
        if (closed) return@synchronized
        expireCompletedLocked()
        val packet = CapabilityNegotiationCodec.decode(wncpPayload)
        if (packet == null) {
            counters.malformedMessages += 1
            record(CapabilityNegotiationError.MalformedCapabilities)
            return@synchronized
        }
        val binding = bindings[sessionId] ?: return@synchronized
        val key = NegotiationKey(sessionId, packet.message.header.negotiationId)
        counters.lastNegotiationId = packet.message.header.negotiationId
        when (val message = packet.message) {
            is CapabilityNegotiationMessage.ClientOffer -> handleClientOffer(key, binding, packet, message)
            is CapabilityNegotiationMessage.HostSelection -> handleHostSelection(key, binding, packet, message)
            is CapabilityNegotiationMessage.ClientConfirm -> handleClientConfirm(key, binding, packet, message)
            is CapabilityNegotiationMessage.HostComplete -> handleHostComplete(key, binding, packet, message)
            is CapabilityNegotiationMessage.Reject -> fail(
                active[key],
                CapabilityNegotiationError.SelectionInvalid,
                releaseReservation = true,
            )
        }
    }

    fun advance() = synchronized(lock) {
        if (closed) return@synchronized
        expireCompletedLocked()
        val now = now()
        active.values.toList().forEach { managed ->
            if (now - managed.startedAtMs >= config.timeoutMs) {
                counters.timeouts += 1
                fail(managed, CapabilityNegotiationError.Timeout, releaseReservation = true)
            } else if (now >= managed.nextRetryAtMs) {
                if (managed.retryIndex >= CapabilityNegotiationProtocol.RETRY_DELAYS_MS.size) {
                    counters.timeouts += 1
                    fail(managed, CapabilityNegotiationError.Timeout, releaseReservation = true)
                } else {
                    counters.retries += 1
                    send(managed, managed.outboundBytes, retry = true)
                }
            }
        }
    }

    fun snapshot(): CapabilityNegotiationSnapshot = synchronized(lock) {
        expireCompletedLocked()
        CapabilityNegotiationSnapshot(
            closed, active.size, counters.completed, counters.retries, counters.timeouts, counters.malformedMessages,
            counters.unknownCriticalTlvRejects, counters.semanticConflicts, counters.requiredFeatureRejects,
            counters.hostPolicyRejects, counters.invalidSelections, counters.secureControlFailures, completed.size,
            counters.lastNegotiationId, counters.lastPeerDeviceId, counters.lastProfileHash?.copyOf(), counters.lastDurationMs,
            counters.lastError,
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        active.values.toList().forEach { fail(it, CapabilityNegotiationError.Closed, releaseReservation = true) }
        bindings.values.forEach { it.transport.setPayloadListener(null) }
        bindings.clear()
        active.clear()
        completed.clear()
        closed = true
    }

    private fun handleClientOffer(
        key: NegotiationKey,
        binding: Binding,
        packet: DecodedCapabilityNegotiationPacket,
        offer: CapabilityNegotiationMessage.ClientOffer,
    ) {
        if (binding.bootstrap.localRole != SessionRole.Host || binding.policy == null) {
            fail(active[key], CapabilityNegotiationError.RoleMismatch, releaseReservation = true)
            return
        }
        val existing = active[key]
        if (existing != null) {
            if (!existing.clientOfferHash.contentEquals(packet.hash)) {
                counters.semanticConflicts += 1
                fail(existing, CapabilityNegotiationError.NegotiationConflict, releaseReservation = true)
            } else if (existing.state == CapabilityNegotiationState.HostSelectionSent) {
                send(existing, existing.outboundBytes, retry = false)
            }
            return
        }
        if (active.size >= config.maxActiveNegotiations) {
            sendReject(
                binding,
                offer.header.negotiationId,
                CapabilityNegotiationRejectStage.Offer,
                CapabilityNegotiationRejectReason.Busy,
                packet.hash,
            )
            record(CapabilityNegotiationError.Busy)
            return
        }
        val host = freeze(collector.collect(SessionRole.Host))
        val selection = CapabilityNegotiator.negotiate(offer.capabilities, offer.request, host, binding.policy)
        if (!selection.isSuccess) {
            when (selection.error) {
                CapabilityNegotiationError.RequiredChannelUnavailable,
                CapabilityNegotiationError.RequiredInputKindUnavailable,
                CapabilityNegotiationError.RequiredBehaviorUnavailable,
                CapabilityNegotiationError.RequiredRecoveryFeatureUnavailable,
                -> counters.requiredFeatureRejects += 1
                CapabilityNegotiationError.HostPolicyConflict -> counters.hostPolicyRejects += 1
                else -> Unit
            }
            sendReject(
                binding,
                offer.header.negotiationId,
                CapabilityNegotiationRejectStage.Offer,
                CapabilityNegotiationRejectReason.Incompatible,
                packet.hash,
            )
            binding.bootstrap.admissionReservation?.close()
            removeBindingIfUnusedLocked(key.sessionId)
            record(selection.error)
            return
        }
        val profile = requireNotNull(selection.profile)
        val header =
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.HostSelection, offer.header.negotiationId, 0)
        val response = CapabilityNegotiationMessage.HostSelection(header, packet.hash, host, profile)
        val responseBytes = CapabilityNegotiationCodec.encode(response)
            ?: run {
                binding.bootstrap.admissionReservation?.close()
                record(CapabilityNegotiationError.CapabilityPayloadTooLarge)
                return
            }
        val profileHash = CapabilityNegotiationCodec.profileHash(profile)
            ?: run {
                binding.bootstrap.admissionReservation?.close()
                record(CapabilityNegotiationError.SelectionInvalid)
                return
            }
        val now = now()
        val managed = ManagedNegotiation(
            key = key,
            binding = binding,
            state = CapabilityNegotiationState.HostSelectionSent,
            startedAtMs = now,
            clientSnapshot = offer.capabilities,
            request = offer.request,
            clientOfferBytes = packet.bytes,
            clientOfferHash = packet.hash,
            hostSnapshot = host,
            hostSelectionBytes = responseBytes,
            hostSelectionHash = CapabilityNegotiationCodec.hash(responseBytes),
            profile = profile,
            profileHash = profileHash,
            outboundBytes = responseBytes,
            nextRetryAtMs = now + CapabilityNegotiationProtocol.RETRY_DELAYS_MS.first(),
        )
        active[key] = managed
        send(managed, responseBytes, retry = false)
    }

    private fun handleHostSelection(
        key: NegotiationKey,
        binding: Binding,
        packet: DecodedCapabilityNegotiationPacket,
        selection: CapabilityNegotiationMessage.HostSelection,
    ) {
        val managed = active[key] ?: return
        if (binding.bootstrap.localRole != SessionRole.Client || managed.state !in setOf(
                CapabilityNegotiationState.ClientOfferSent,
                CapabilityNegotiationState.ClientConfirmSent,
            )
        ) {
            return
        }
        if (managed.hostSelectionHash != null) {
            if (!managed.hostSelectionHash.contentEquals(packet.hash)) {
                counters.semanticConflicts += 1
                fail(managed, CapabilityNegotiationError.NegotiationConflict, releaseReservation = true)
            } else {
                managed.clientConfirmBytes?.let { confirmBytes -> send(managed, confirmBytes, retry = false) }
            }
            return
        }
        if (!selection.clientOfferHash.contentEquals(managed.clientOfferHash)) {
            counters.invalidSelections += 1
            fail(managed, CapabilityNegotiationError.SelectionInvalid, releaseReservation = true)
            return
        }
        val validation = CapabilityNegotiator.validateClientSelection(
            managed.clientSnapshot,
            managed.request,
            selection.hostCapabilities,
            selection.profile,
        )
        if (validation != CapabilityNegotiationError.None) {
            counters.invalidSelections += 1
            sendReject(
                binding,
                key.negotiationId,
                CapabilityNegotiationRejectStage.Selection,
                CapabilityNegotiationRejectReason.Incompatible,
                packet.hash,
            )
            fail(managed, validation, releaseReservation = true)
            return
        }
        val profileHash = CapabilityNegotiationCodec.profileHash(selection.profile)
            ?: run {
                fail(managed, CapabilityNegotiationError.SelectionInvalid, releaseReservation = true)
                return
            }
        val confirm = CapabilityNegotiationMessage.ClientConfirm(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.ClientConfirm, key.negotiationId, 0),
            managed.clientOfferHash,
            packet.hash,
            profileHash,
        )
        val confirmBytes = CapabilityNegotiationCodec.encode(confirm)
            ?: run {
                fail(managed, CapabilityNegotiationError.CapabilityPayloadTooLarge, releaseReservation = true)
                return
            }
        managed.hostSnapshot = selection.hostCapabilities
        managed.hostSelectionBytes = packet.bytes
        managed.hostSelectionHash = packet.hash
        managed.profile = selection.profile
        managed.profileHash = profileHash
        managed.clientConfirmBytes = confirmBytes
        managed.outboundBytes = confirmBytes
        managed.state = CapabilityNegotiationState.ClientConfirmSent
        managed.retryIndex = 0
        managed.nextRetryAtMs = now() + CapabilityNegotiationProtocol.RETRY_DELAYS_MS.first()
        send(managed, confirmBytes, retry = false)
    }

    private fun handleClientConfirm(
        key: NegotiationKey,
        binding: Binding,
        packet: DecodedCapabilityNegotiationPacket,
        confirm: CapabilityNegotiationMessage.ClientConfirm,
    ) {
        val cached = completed[key]
        if (cached != null) {
            if (cached.matches(confirm)) {
                cached.transport.send(cached.hostCompleteBytes)
            } else {
                counters.semanticConflicts += 1
                record(CapabilityNegotiationError.NegotiationConflict)
            }
            return
        }
        val managed = active[key] ?: return
        if (binding.bootstrap.localRole != SessionRole.Host || managed.state != CapabilityNegotiationState.HostSelectionSent ||
            !confirm.clientOfferHash.contentEquals(managed.clientOfferHash) ||
            !confirm.hostSelectionHash.contentEquals(managed.hostSelectionHash) ||
            !confirm.profileHash.contentEquals(managed.profileHash)
        ) {
            counters.invalidSelections += 1
            fail(managed, CapabilityNegotiationError.SelectionInvalid, releaseReservation = true)
            return
        }
        if (binding.bootstrap.admissionReservation?.renew(CapabilityNegotiationProtocol.POST_NEGOTIATION_RESERVATION_MS) == false) {
            fail(managed, CapabilityNegotiationError.AdmissionExpired, releaseReservation = true)
            return
        }
        val complete = CapabilityNegotiationMessage.HostComplete(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.HostComplete, key.negotiationId, 0),
            managed.clientOfferHash,
            requireNotNull(managed.hostSelectionHash),
            requireNotNull(managed.profileHash),
        )
        val completeBytes = CapabilityNegotiationCodec.encode(complete)
            ?: run {
                fail(managed, CapabilityNegotiationError.CapabilityPayloadTooLarge, releaseReservation = true)
                return
            }
        if (!send(managed, completeBytes, retry = false)) return
        active.remove(key)
        trimCompletionCacheLocked()
        completed[key] = CompletedNegotiation(
            binding.transport,
            managed.clientOfferHash.copyOf(),
            requireNotNull(managed.hostSelectionHash).copyOf(),
            requireNotNull(managed.profileHash).copyOf(),
            completeBytes,
            now() + config.completionCacheRetentionMs,
        )
        finish(managed)
    }

    private fun handleHostComplete(
        key: NegotiationKey,
        binding: Binding,
        packet: DecodedCapabilityNegotiationPacket,
        complete: CapabilityNegotiationMessage.HostComplete,
    ) {
        val managed = active[key] ?: return
        if (binding.bootstrap.localRole != SessionRole.Client || managed.state != CapabilityNegotiationState.ClientConfirmSent ||
            !complete.clientOfferHash.contentEquals(managed.clientOfferHash) ||
            !complete.hostSelectionHash.contentEquals(managed.hostSelectionHash) ||
            !complete.profileHash.contentEquals(managed.profileHash)
        ) {
            fail(managed, CapabilityNegotiationError.SelectionInvalid, releaseReservation = true)
            return
        }
        active.remove(key)
        finish(managed)
    }

    private fun send(managed: ManagedNegotiation, bytes: ByteArray, retry: Boolean): Boolean {
        if (bytes.size > managed.binding.transport.maxPayloadBytes) {
            fail(managed, CapabilityNegotiationError.CapabilityPayloadTooLarge, releaseReservation = true)
            return false
        }
        val result = managed.binding.transport.send(bytes)
        if (!result.isSuccess) {
            counters.secureControlFailures += 1
            fail(managed, CapabilityNegotiationError.SecureControlFailure, releaseReservation = true)
            return false
        }
        managed.outboundBytes = bytes
        if (retry) managed.retryIndex += 1
        val delayIndex = managed.retryIndex.coerceAtMost(CapabilityNegotiationProtocol.RETRY_DELAYS_MS.lastIndex)
        managed.nextRetryAtMs = now() + CapabilityNegotiationProtocol.RETRY_DELAYS_MS[delayIndex]
        return true
    }

    private fun sendReject(
        binding: Binding,
        negotiationId: CapabilityNegotiationId,
        stage: CapabilityNegotiationRejectStage,
        reason: CapabilityNegotiationRejectReason,
        relatedHash: ByteArray,
    ) {
        val reject = CapabilityNegotiationMessage.Reject(
            CapabilityNegotiationHeader(CapabilityNegotiationMessageType.NegotiationReject, negotiationId, 0),
            stage,
            reason,
            relatedHash,
        )
        CapabilityNegotiationCodec.encode(reject)?.let(binding.transport::send)
    }

    private fun finish(managed: ManagedNegotiation) {
        val profile = requireNotNull(managed.profile)
        val profileHash = requireNotNull(managed.profileHash)
        counters.completed += 1
        counters.lastPeerDeviceId = managed.binding.bootstrap.remoteDeviceId
        counters.lastProfileHash = profileHash.copyOf()
        counters.lastDurationMs = (now() - managed.startedAtMs).coerceAtLeast(0L)
        record(CapabilityNegotiationError.None)
        onCompleted(
            NegotiatedSessionBootstrap(
                managed.binding.bootstrap.sessionId,
                managed.binding.bootstrap.generation,
                managed.binding.bootstrap.localDeviceId,
                managed.binding.bootstrap.remoteDeviceId,
                managed.binding.bootstrap.localRole,
                managed.binding.bootstrap.remoteRole,
                profile,
                profileHash.copyOf(),
                managed.binding.bootstrap.protection,
                managed.binding.transport,
                managed.binding.bootstrap.admissionReservation,
            ),
        )
    }

    private fun fail(managed: ManagedNegotiation?, error: CapabilityNegotiationError, releaseReservation: Boolean) {
        if (managed == null) {
            record(error)
            return
        }
        active.remove(managed.key)
        if (releaseReservation) managed.binding.bootstrap.admissionReservation?.close()
        removeBindingIfUnusedLocked(managed.key.sessionId)
        managed.state = when (error) {
            CapabilityNegotiationError.Timeout -> CapabilityNegotiationState.TimedOut
            CapabilityNegotiationError.Closed -> CapabilityNegotiationState.Closed
            else -> CapabilityNegotiationState.Failed
        }
        record(error)
    }

    private fun expireCompletedLocked() {
        val now = now()
        completed.entries.removeIf { it.value.expiresAtMs <= now }
    }

    private fun trimCompletionCacheLocked() {
        while (completed.size >= CapabilityNegotiationProtocol.COMPLETION_CACHE_CAPACITY) {
            completed.entries.iterator().next().also { completed.remove(it.key) }
        }
    }

    private fun removeBindingIfUnusedLocked(sessionId: SessionId) {
        if (active.keys.none { it.sessionId == sessionId } && completed.keys.none { it.sessionId == sessionId }) {
            bindings.remove(sessionId)?.transport?.setPayloadListener(null)
        }
    }

    private fun record(error: CapabilityNegotiationError): CapabilityNegotiationError {
        counters.lastError = error
        return error
    }

    private fun now(): Long = clock.nowMs().coerceAtLeast(0L)

    private fun freeze(snapshot: LocalCapabilitySnapshot): LocalCapabilitySnapshot =
        snapshot.copy(localAvailability = Collections.unmodifiableMap(LinkedHashMap(snapshot.localAvailability)))

    private data class Binding(
        val bootstrap: SecureSessionCapabilityBootstrap,
        val transport: SecureSessionControlTransport,
        val policy: HostCapabilityPolicy?,
    )

    private data class NegotiationKey(val sessionId: SessionId, val negotiationId: CapabilityNegotiationId)

    private class ManagedNegotiation(
        val key: NegotiationKey,
        val binding: Binding,
        var state: CapabilityNegotiationState,
        val startedAtMs: Long,
        val clientSnapshot: LocalCapabilitySnapshot,
        val request: CapabilityRequest,
        var clientOfferBytes: ByteArray,
        val clientOfferHash: ByteArray,
        var hostSnapshot: LocalCapabilitySnapshot? = null,
        var hostSelectionBytes: ByteArray? = null,
        var hostSelectionHash: ByteArray? = null,
        var profile: NegotiatedCapabilityProfile? = null,
        var profileHash: ByteArray? = null,
        var clientConfirmBytes: ByteArray? = null,
        var outboundBytes: ByteArray,
        var nextRetryAtMs: Long,
        var retryIndex: Int = 0,
    )

    private data class CompletedNegotiation(
        val transport: SecureSessionControlTransport,
        val clientOfferHash: ByteArray,
        val hostSelectionHash: ByteArray,
        val profileHash: ByteArray,
        val hostCompleteBytes: ByteArray,
        val expiresAtMs: Long,
    ) {
        fun matches(confirm: CapabilityNegotiationMessage.ClientConfirm): Boolean =
            confirm.clientOfferHash.contentEquals(clientOfferHash) &&
                confirm.hostSelectionHash.contentEquals(hostSelectionHash) &&
                confirm.profileHash.contentEquals(profileHash)
    }

    private data class Counters(
        var completed: Long = 0,
        var retries: Long = 0,
        var timeouts: Long = 0,
        var malformedMessages: Long = 0,
        var unknownCriticalTlvRejects: Long = 0,
        var semanticConflicts: Long = 0,
        var requiredFeatureRejects: Long = 0,
        var hostPolicyRejects: Long = 0,
        var invalidSelections: Long = 0,
        var secureControlFailures: Long = 0,
        var lastNegotiationId: CapabilityNegotiationId? = null,
        var lastPeerDeviceId: DeviceId? = null,
        var lastProfileHash: ByteArray? = null,
        var lastDurationMs: Long? = null,
        var lastError: CapabilityNegotiationError = CapabilityNegotiationError.None,
    )
}
