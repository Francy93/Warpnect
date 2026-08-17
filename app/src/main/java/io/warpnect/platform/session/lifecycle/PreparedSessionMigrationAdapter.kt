package io.warpnect.platform.session.lifecycle

import io.warpnect.session.SessionRole
import io.warpnect.session.lifecycle.ChannelMigrationPreparation
import io.warpnect.session.lifecycle.LifecyclePathBinding
import io.warpnect.session.lifecycle.PathMigrationEntry
import io.warpnect.session.lifecycle.PathMigrationId
import io.warpnect.session.lifecycle.SessionLifecycleError
import io.warpnect.session.lifecycle.SessionLifecycleMigrationAdapter
import io.warpnect.session.setup.ChannelDescriptor
import io.warpnect.session.setup.ChannelEndpointAllocator
import io.warpnect.session.setup.ChannelEndpointLease
import io.warpnect.session.setup.ChannelTransportPreparationRequest
import io.warpnect.session.setup.ChannelTransportPreparer
import io.warpnect.session.setup.PathSocketBinding
import io.warpnect.session.setup.PreparedChannel
import io.warpnect.session.setup.PreparedChannelTransport
import io.warpnect.session.setup.PreparedSessionBootstrap
import java.net.InetAddress

/** Platform route adapter for the bounded RFC-005H migration transaction. */
interface LifecycleCandidateDatagramIo {
    fun arm(binding: LifecyclePathBinding, migrationId: PathMigrationId, timeoutMs: Long): Boolean
    fun disarm(migrationId: PathMigrationId)
    fun send(binding: LifecyclePathBinding, protectedDatagram: ByteArray): Boolean
}

/**
 * Replaces path-bound endpoint leases while retaining the existing RFC-005E runtime and channel
 * contexts. Because RFC-005G transports are stopped, a replacement stopped transport can be
 * built before commit without starting capture, receive loops, codecs or audio.
 */
class PreparedSessionMigrationAdapter(
    private val bootstrap: PreparedSessionBootstrap,
    private val endpointAllocator: ChannelEndpointAllocator,
    private val transportPreparer: ChannelTransportPreparer,
    private val candidateIo: LifecycleCandidateDatagramIo,
) : SessionLifecycleMigrationAdapter {
    override fun armCandidateWindow(
        binding: LifecyclePathBinding,
        migrationId: PathMigrationId,
        timeoutMs: Long,
    ): Boolean = candidateIo.arm(binding, migrationId, timeoutMs)

    override fun disarmCandidateWindow(migrationId: PathMigrationId) = candidateIo.disarm(migrationId)

    override fun sendCandidate(binding: LifecyclePathBinding, protectedDatagram: ByteArray): Boolean =
        candidateIo.send(binding, protectedDatagram)

    override fun prepareChannels(
        binding: LifecyclePathBinding,
        channels: List<io.warpnect.session.SessionChannelKind>,
    ): ChannelMigrationPreparation? {
        if (channels != bootstrap.channels.map { it.descriptor.kind } || !binding.isValid()) return null
        val leases = ArrayList<ChannelEndpointLease>(channels.size)
        channels.forEach { kind ->
            val allocated = endpointAllocator.allocate(
                PathSocketBinding(binding.plan.pathId, binding.plan.kind, binding.plan.localAddress),
                kind,
            )
            val lease = allocated.lease
            if (!allocated.isSuccess || lease == null) {
                leases.asReversed().forEach(ChannelEndpointLease::close)
                return null
            }
            leases += lease
        }
        return PreparedMigration(bootstrap.channels, leases, binding)
    }

    override fun commit(
        binding: LifecyclePathBinding,
        preparation: ChannelMigrationPreparation,
        remoteEntries: List<PathMigrationEntry>,
    ): SessionLifecycleError {
        val prepared = preparation as? PreparedMigration ?: return SessionLifecycleError.TransportRebindFailed
        if (prepared.binding != binding || remoteEntries.map {
                it.channelId
            }.toSet() != bootstrap.channels.map { it.descriptor.channelId }.toSet()
        ) {
            return SessionLifecycleError.PathMigrationConflict
        }
        val remoteByChannel = remoteEntries.associateBy { it.channelId }
        val replacements = ArrayList<TransportReplacement>(bootstrap.channels.size)
        bootstrap.channels.forEachIndexed { index, channel ->
            val lease = prepared.leases[index]
            val remotePort = requireNotNull(remoteByChannel[channel.descriptor.channelId]).localPort
            val descriptor = channel.descriptor.forMigration(bootstrap.localRole, lease.localPort, remotePort)
                ?: return closeReplacements(replacements, SessionLifecycleError.PathMigrationConflict)
            val created = transportPreparer.prepare(
                ChannelTransportPreparationRequest(
                    bootstrap.localRole,
                    descriptor,
                    lease,
                    binding.plan.remoteAddress,
                    channel.configuration,
                    bootstrap.protectionRuntime,
                    channel.protection,
                ),
            )
            val transport = created.transport
            if (!created.isSuccess || transport == null || transport.started || !transport.protectedRequired) {
                return closeReplacements(
                    replacements,
                    SessionLifecycleError.TransportRebindFailed,
                )
            }
            replacements += TransportReplacement(
                channel,
                descriptor,
                lease,
                binding.plan.remoteAddress,
                transport,
            )
        }

        val rebound = replacements.map { replacement ->
            val newEndpoint = endpointFor(
                replacement.remoteAddress,
                replacement.descriptor.remotePortFor(bootstrap.localRole),
            )
                ?: return closeReplacements(replacements, SessionLifecycleError.TransportRebindFailed)
            val oldEndpoint = endpointFor(
                replacement.channel.remoteAddress,
                replacement.channel.descriptor.remotePortFor(bootstrap.localRole),
            ) ?: return closeReplacements(
                replacements,
                SessionLifecycleError.TransportRebindFailed,
            )
            EndpointRebind(replacement.channel, oldEndpoint, newEndpoint)
        }
        val reboundChannels = ArrayList<EndpointRebind>(rebound.size)
        rebound.forEach { replacement ->
            if (
                bootstrap.protectionRuntime.rebindChannelEndpoint(
                    replacement.channel.descriptor.channelId,
                    replacement.newEndpoint,
                ) !=
                io.warpnect.session.security.SessionProtectionError.None
            ) {
                reboundChannels.asReversed().forEach { prior ->
                    bootstrap.protectionRuntime.rebindChannelEndpoint(
                        prior.channel.descriptor.channelId,
                        prior.oldEndpoint,
                    )
                }
                return closeReplacements(
                    replacements,
                    SessionLifecycleError.TransportRebindFailed,
                )
            }
            reboundChannels += replacement
        }
        if (
            bootstrap.secureSessionControl.rebindRemoteEndpoint(binding.remoteControlEndpoint) !=
            io.warpnect.session.security.SessionProtectionError.None
        ) {
            reboundChannels.asReversed().forEach { prior ->
                bootstrap.protectionRuntime.rebindChannelEndpoint(prior.channel.descriptor.channelId, prior.oldEndpoint)
            }
            return SessionLifecycleError.TransportRebindFailed
        }
        replacements.forEach { replacement ->
            replacement.channel.replaceEndpoint(
                replacement.descriptor,
                replacement.lease,
                replacement.remoteAddress,
                replacement.transport,
            )
        }
        prepared.transferred = true
        return SessionLifecycleError.None
    }

    private fun closeReplacements(
        replacements: List<TransportReplacement>,
        error: SessionLifecycleError,
    ): SessionLifecycleError {
        replacements.asReversed().forEach { replacement ->
            replacement.transport.close()
        }
        return error
    }

    private fun endpointFor(address: String, port: Int): io.warpnect.session.handshake.HandshakeTransportEndpoint? =
        try {
            io.warpnect.session.handshake.HandshakeTransportEndpoint.from(InetAddress.getByName(address).address, port)
        } catch (_: Exception) {
            null
        }

    private data class TransportReplacement(
        val channel: PreparedChannel,
        val descriptor: ChannelDescriptor,
        val lease: ChannelEndpointLease,
        val remoteAddress: String,
        val transport: PreparedChannelTransport,
    )

    private data class EndpointRebind(
        val channel: PreparedChannel,
        val oldEndpoint: io.warpnect.session.handshake.HandshakeTransportEndpoint,
        val newEndpoint: io.warpnect.session.handshake.HandshakeTransportEndpoint,
    )

    private class PreparedMigration(
        private val channels: List<PreparedChannel>,
        val leases: List<ChannelEndpointLease>,
        val binding: LifecyclePathBinding,
    ) : ChannelMigrationPreparation {
        var transferred = false
        override val entries: List<PathMigrationEntry> = channels.indices.map { index ->
            PathMigrationEntry(channels[index].descriptor.channelId, leases[index].localPort)
        }

        override fun close() {
            if (!transferred) leases.asReversed().forEach(ChannelEndpointLease::close)
        }
    }
}

private fun ChannelDescriptor.forMigration(role: SessionRole, localPort: Int, remotePort: Int): ChannelDescriptor? {
    if (localPort !in 1..0xffff || remotePort !in 1..0xffff) return null
    return if (role == SessionRole.Host) {
        copy(hostLocalPort = localPort, clientLocalPort = remotePort)
    } else {
        copy(hostLocalPort = remotePort, clientLocalPort = localPort)
    }
}

private fun ChannelDescriptor.remotePortFor(role: SessionRole): Int =
    if (role == SessionRole.Host) clientLocalPort else hostLocalPort
