package io.warpnect.session.lifecycle

import io.warpnect.session.ChannelId
import io.warpnect.session.DeviceId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.NetworkPathState
import io.warpnect.session.PathId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.control.SessionControlUnprotectResult
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionContextResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.security.SessionProtectionSnapshot
import io.warpnect.session.setup.ChannelDescriptor
import io.warpnect.session.setup.ChannelEndpointLease
import io.warpnect.session.setup.PathSocketBinding
import io.warpnect.session.setup.PreparedChannel
import io.warpnect.session.setup.PreparedChannelProtection
import io.warpnect.session.setup.PreparedChannelTransport
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionPathPlan
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleControllerTest {
    @Test
    fun hardDirectLossCompletesSameGenerationMigrationWithoutRecreatingSecurityState() {
        val clock = TestClock(100)
        val clientControl = TestControl()
        val hostControl = TestControl()
        val clientFixture = fixture(SessionRole.Client, clientControl, contextBase = 10)
        val hostFixture = fixture(SessionRole.Host, hostControl, contextBase = 20)
        val clientAdapter = TestMigrationAdapter(clientFixture.channelIds)
        val hostAdapter = TestMigrationAdapter(hostFixture.channelIds)
        val clientEvents = TestContinuityParticipant()
        val hostEvents = TestContinuityParticipant()
        lateinit var client: SessionLifecycleController
        lateinit var host: SessionLifecycleController

        clientAdapter.deliver = { bytes ->
            host.receiveCandidate(clientLanEndpoint, bytes, clock.nowMs * 1_000L)
        }
        hostAdapter.deliver = { bytes ->
            client.receiveCandidate(hostLanEndpoint, bytes, clock.nowMs * 1_000L)
        }
        clientControl.peer = hostControl
        hostControl.peer = clientControl
        host = controller(
            hostFixture,
            hostAdapter,
            clock,
            continuity = listOf(hostEvents),
            capacityOwner = TestCapacityOwner(),
        )
        client = controller(clientFixture, clientAdapter, clock, listOf(clientEvents))

        assertEquals(SessionLifecycleError.None, host.start())
        assertEquals(SessionLifecycleError.None, client.start())
        val clientRuntime = clientFixture.runtime
        val originalProfile = clientFixture.bootstrap.profile
        val originalHash = clientFixture.bootstrap.preparedConfigurationHash.copyOf()
        val originalChannelIds = clientFixture.channelIds.toSet()
        val originalContexts = clientFixture.channels.map { it.protection.contextIds }

        client.onPlatformPathLoss(directPath, hard = true)

        assertEquals(SessionLifecycleState.Active, client.snapshot().state)
        assertEquals(lanPath, client.snapshot().activePathId)
        assertEquals(SessionGeneration.Initial, client.snapshot().generation)
        assertSame(originalProfile, clientFixture.bootstrap.profile)
        assertArrayEquals(originalHash, clientFixture.bootstrap.preparedConfigurationHash)
        assertEquals(originalChannelIds, clientFixture.bootstrap.channels.map { it.descriptor.channelId }.toSet())
        assertEquals(originalContexts, clientFixture.channels.map { it.protection.contextIds })
        assertSame(clientRuntime, clientFixture.bootstrap.protectionRuntime)
        assertFalse(clientRuntime.closed)
        assertEquals(0, clientRuntime.createdChannelContexts)
        assertEquals(1, clientAdapter.commitCount)
        assertEquals(1, hostAdapter.commitCount)
        assertEquals(
            listOf(
                SessionLifecycleMessageType.PathChallenge,
                SessionLifecycleMessageType.PathMigrationPrepare,
                SessionLifecycleMessageType.PathMigrationCommit,
            ),
            clientAdapter.candidateTypes,
        )
        assertEquals(
            listOf(SessionLifecycleMessageType.PathResponse, SessionLifecycleMessageType.PathMigrationReady),
            hostAdapter.candidateTypes,
        )
        assertTrue(hostControl.activeTypes.contains(SessionLifecycleMessageType.PathMigrationAck))
        assertEquals(listOf("migration-start", "migration-commit"), clientEvents.events)
        assertEquals(listOf("migration-start", "migration-commit"), hostEvents.events)
        assertEquals(0, clientAdapter.ordinaryMediaDatagrams)
        assertEquals(0, hostAdapter.ordinaryMediaDatagrams)
    }

    @Test
    fun oneSessionMigrationAndCloseLeaveAnIndependentSessionUntouched() {
        val clock = TestClock(100)
        val aClientControl = TestControl()
        val aHostControl = TestControl()
        val aClientFixture = fixture(SessionRole.Client, aClientControl, contextBase = 80)
        val aHostFixture = fixture(SessionRole.Host, aHostControl, contextBase = 90)
        val aClientAdapter = TestMigrationAdapter(aClientFixture.channelIds)
        val aHostAdapter = TestMigrationAdapter(aHostFixture.channelIds)
        lateinit var aClient: SessionLifecycleController
        lateinit var aHost: SessionLifecycleController
        aClientAdapter.deliver = { bytes ->
            aHost.receiveCandidate(clientLanEndpoint, bytes, clock.nowMs * 1_000L)
        }
        aHostAdapter.deliver = { bytes ->
            aClient.receiveCandidate(hostLanEndpoint, bytes, clock.nowMs * 1_000L)
        }
        aClientControl.peer = aHostControl
        aHostControl.peer = aClientControl
        aHost = controller(aHostFixture, aHostAdapter, clock, capacityOwner = TestCapacityOwner())
        aClient = controller(aClientFixture, aClientAdapter, clock)

        val bControl = TestControl()
        val bEvents = TestContinuityParticipant()
        val bFixture = fixture(
            SessionRole.Client,
            bControl,
            contextBase = 100,
            session = SessionId.requireValid(0uL, 200uL),
        )
        val b = controller(bFixture, TestMigrationAdapter(bFixture.channelIds), clock, listOf(bEvents))

        assertEquals(SessionLifecycleError.None, aHost.start())
        assertEquals(SessionLifecycleError.None, aClient.start())
        assertEquals(SessionLifecycleError.None, b.start())
        val bContexts = bFixture.channels.map { it.protection.contextIds }

        aClient.onPlatformPathLoss(directPath, hard = true)
        assertEquals(SessionLifecycleError.None, aClient.gracefulDisconnect(DisconnectReason.UserRequested))

        assertEquals(SessionLifecycleState.Closed, aClient.snapshot().state)
        assertEquals(SessionLifecycleState.Active, b.snapshot().state)
        assertEquals(directPath, b.snapshot().activePathId)
        assertEquals(SessionGeneration.Initial, b.snapshot().generation)
        assertFalse(bFixture.runtime.closed)
        assertEquals(bContexts, bFixture.channels.map { it.protection.contextIds })
        assertEquals(emptyList<String>(), bEvents.events)
        assertEquals(emptyList<LifecycleInputSafetyResetReason>(), bEvents.inputSafetyResets)
    }

    @Test
    fun unarmedResponderCandidateAcceptsOnlyAuthenticatedPathChallenge() {
        val clock = TestClock(100)
        val control = TestControl()
        val fixture = fixture(SessionRole.Host, control, contextBase = 30)
        val adapter = TestMigrationAdapter(fixture.channelIds)
        val controller = controller(fixture, adapter, clock, capacityOwner = TestCapacityOwner())
        assertEquals(SessionLifecycleError.None, controller.start())

        val heartbeat = requireNotNull(
            SessionLifecycleCodec.encode(
                SessionLifecycleMessage.Heartbeat(
                    header(SessionLifecycleMessageType.Heartbeat, 1u),
                    HeartbeatId.requireValid(1u),
                    directPath,
                ),
            ),
        )
        controller.receiveCandidate(clientLanEndpoint, heartbeat, clock.nowMs * 1_000L)
        assertEquals(0, adapter.armCount)
        assertEquals(SessionLifecycleState.Active, controller.snapshot().state)

        control.candidateAuthenticationSucceeds = false
        val challenge = requireNotNull(
            SessionLifecycleCodec.encode(
                SessionLifecycleMessage.PathChallenge(
                    header(SessionLifecycleMessageType.PathChallenge, 2u),
                    PathMigrationId.requireValid(2u),
                    lanPath,
                    NetworkPathKind.Lan,
                    ByteArray(SessionLifecycleProtocol.CHALLENGE_BYTES) { 7 },
                ),
            ),
        )
        controller.receiveCandidate(clientLanEndpoint, challenge, clock.nowMs * 1_000L)
        assertEquals(0, adapter.armCount)
        assertEquals(0, control.activeRecords.size)
    }

    @Test
    fun disconnectSemanticRetryUsesFreshSecureRecordAndNeverStartsRecovery() {
        val clock = TestClock(100)
        val control = TestControl()
        val reconnect = TestReconnectOrchestrator()
        val continuity = TestContinuityParticipant()
        val fixture = fixture(SessionRole.Client, control, contextBase = 40, standby = false)
        val controller =
            controller(fixture, TestMigrationAdapter(fixture.channelIds), clock, listOf(continuity), reconnect)
        assertEquals(SessionLifecycleError.None, controller.start())

        assertEquals(SessionLifecycleError.None, controller.gracefulDisconnect(DisconnectReason.UserRequested))
        clock.nowMs += 125
        controller.advance()

        val notices = control.activeRecords.filter { it.type == SessionLifecycleMessageType.DisconnectNotice }
        assertEquals(2, notices.size)
        assertArrayEquals(notices[0].payload, notices[1].payload)
        assertTrue(notices[1].sclSequence > notices[0].sclSequence)
        assertTrue(notices[1].securityPacketNumber > notices[0].securityPacketNumber)
        clock.nowMs += 125
        controller.advance()
        assertEquals(SessionLifecycleState.Closed, controller.snapshot().state)
        assertEquals(0, reconnect.calls)
        assertEquals(listOf(LifecycleInputSafetyResetReason.SessionClosing), continuity.inputSafetyResets)
    }

    @Test
    fun allPathLossRequestsFreshGenerationChainAndResetsInputSafetyOnce() {
        val clock = TestClock(100)
        val control = TestControl()
        val continuity = TestContinuityParticipant()
        val reconnect = TestReconnectOrchestrator { generation ->
            fixture(
                SessionRole.Client,
                TestControl(),
                contextBase = 70,
                standby = false,
                generation = generation,
            ).bootstrap
        }
        val old = fixture(SessionRole.Client, control, contextBase = 50, standby = false)
        val controller = controller(old, TestMigrationAdapter(old.channelIds), clock, listOf(continuity), reconnect)
        assertEquals(SessionLifecycleError.None, controller.start())

        controller.onPlatformPathLoss(directPath, hard = true)

        assertEquals(1, reconnect.calls)
        assertEquals(sessionId, reconnect.record?.sessionId)
        assertEquals(SessionGeneration.Initial, reconnect.record?.currentGeneration)
        assertEquals(SessionGeneration.requireValid(2u), reconnect.nextGeneration)
        assertEquals(hostDevice, reconnect.record?.expectedPeerDeviceId)
        assertEquals(listOf("005D", "005E", "005F", "005G"), reconnect.steps)
        assertTrue(old.runtime.closed)
        val fresh = requireNotNull(reconnect.fresh)
        assertEquals(sessionId, fresh.sessionId)
        assertEquals(SessionGeneration.requireValid(2u), fresh.generation)
        assertNotSame(old.runtime, fresh.protectionRuntime)
        assertNotSame(old.runtime.sessionControlContext, fresh.protectionRuntime.sessionControlContext)
        assertEquals(
            listOf(LifecycleInputSafetyResetReason.PathUnavailable),
            continuity.inputSafetyResets,
        )

        assertEquals(SessionLifecycleError.None, controller.onFreshGenerationPrepared(fresh))
        assertEquals(listOf("suspended", "reconnected"), continuity.events)
        controller.onPlatformPathLoss(directPath, hard = true)
        controller.advance()
        assertEquals(1, reconnect.calls)
    }

    @Test
    fun remoteDisconnectAcknowledgesClosesAndDoesNotRecover() {
        val clock = TestClock(100)
        val control = TestControl()
        val continuity = TestContinuityParticipant()
        val reconnect = TestReconnectOrchestrator()
        val capacity = TestCapacityOwner()
        val fixture = fixture(SessionRole.Host, control, contextBase = 60)
        val controller =
            controller(
                fixture,
                TestMigrationAdapter(fixture.channelIds),
                clock,
                listOf(continuity),
                reconnect,
                capacity,
            )
        assertEquals(SessionLifecycleError.None, controller.start())

        val notice = requireNotNull(
            SessionLifecycleCodec.encode(
                SessionLifecycleMessage.DisconnectNotice(
                    header(SessionLifecycleMessageType.DisconnectNotice, 4u),
                    DisconnectReason.HostClosing,
                    SessionGeneration.Initial,
                    directPath,
                ),
            ),
        )
        control.deliverInbound(notice)
        control.deliverInbound(notice)

        assertEquals(SessionLifecycleState.Closed, controller.snapshot().state)
        assertEquals(1, control.activeTypes.count { it == SessionLifecycleMessageType.DisconnectAck })
        assertEquals(0, reconnect.calls)
        assertEquals(1, capacity.closeCount)
        assertEquals(listOf("closing"), continuity.events)
        assertEquals(listOf(LifecycleInputSafetyResetReason.SessionClosing), continuity.inputSafetyResets)
    }

    private fun controller(
        fixture: Fixture,
        adapter: TestMigrationAdapter,
        clock: TestClock,
        continuity: List<TestContinuityParticipant> = emptyList(),
        reconnect: TestReconnectOrchestrator? = null,
        capacityOwner: TestCapacityOwner? = null,
    ): SessionLifecycleController = SessionLifecycleController(
        bootstrap = fixture.bootstrap,
        pathProvider = FixturePathProvider(fixture),
        migrationAdapter = adapter,
        recoveryDelegate = reconnect,
        continuityParticipants = continuity,
        clock = clock,
        capacityOwner = capacityOwner,
    )

    private fun fixture(
        role: SessionRole,
        control: TestControl,
        contextBase: Long,
        standby: Boolean = true,
        generation: SessionGeneration = SessionGeneration.Initial,
        session: SessionId = sessionId,
    ): Fixture {
        val local = if (role == SessionRole.Client) clientDevice else hostDevice
        val remote = if (role == SessionRole.Client) hostDevice else clientDevice
        val localAddress = if (role == SessionRole.Client) "10.0.0.2" else "10.0.0.1"
        val remoteAddress = if (role == SessionRole.Client) "10.0.0.1" else "10.0.0.2"
        val localLanAddress = if (role == SessionRole.Client) "192.168.1.2" else "192.168.1.1"
        val remoteLanAddress = if (role == SessionRole.Client) "192.168.1.1" else "192.168.1.2"
        val descriptors = listOf(
            ChannelDescriptor(
                channel(
                    1u,
                ),
                SessionChannelKind.Video, SessionChannelDirection.HostToClient, 0, directPath, 41_000, 42_000, 1_200, 0,
            ),
            ChannelDescriptor(
                channel(
                    2u,
                ),
                SessionChannelKind.Input, SessionChannelDirection.ClientToHost, 0, directPath, 41_001, 42_001, 1_200, 0,
            ),
        )
        val channels = descriptors.mapIndexed { index, descriptor ->
            PreparedChannel(
                descriptor,
                TestLease(
                    PathSocketBinding(directPath, NetworkPathKind.Direct, localAddress),
                    if (role == SessionRole.Client) descriptor.clientLocalPort else descriptor.hostLocalPort,
                    descriptor.kind,
                ),
                remoteAddress,
                emptyList(),
                TestChannelProtection(
                    descriptor.channelId,
                    ProtectionContextIds(contextBase + index, contextBase + index + 100),
                ),
                TestPreparedTransport(),
            )
        }
        val runtime = TestRuntime(session, ProtectionContextIds(contextBase, contextBase + 1))
        val active =
            SessionPathPlan(directPath, NetworkPathKind.Direct, NetworkPathState.Active, localAddress, remoteAddress)
        val standbyPath = standby.then {
            SessionPathPlan(lanPath, NetworkPathKind.Lan, NetworkPathState.Standby, localLanAddress, remoteLanAddress)
        }
        val bootstrap = PreparedSessionBootstrap(
            session,
            generation,
            local,
            remote,
            role,
            if (role == SessionRole.Client) SessionRole.Host else SessionRole.Client,
            profile(),
            ByteArray(32) { 3 },
            active,
            standbyPath,
            channels,
            control,
            runtime,
            null,
            0,
            30_000,
            preparedConfigurationHash = ByteArray(32) { 9 },
        )
        return Fixture(
            bootstrap,
            runtime,
            channels,
            descriptors.map { it.channelId },
            standbyPath?.let {
                LifecyclePathBinding(
                    it,
                    if (role == SessionRole.Client) hostLanEndpoint else clientLanEndpoint,
                )
            },
        )
    }

    private class FixturePathProvider(private val fixture: Fixture) : SessionLifecyclePathProvider {
        override fun bindingFor(pathId: PathId): LifecyclePathBinding? =
            fixture.lanBinding?.takeIf { it.plan.pathId == pathId }
    }

    private data class Fixture(
        val bootstrap: PreparedSessionBootstrap,
        val runtime: TestRuntime,
        val channels: List<PreparedChannel>,
        val channelIds: List<ChannelId>,
        val lanBinding: LifecyclePathBinding?,
    )

    private class TestControl : SecureSessionControlTransport {
        override val maxPayloadBytes: Int = SessionLifecycleProtocol.MAX_PAYLOAD_BYTES
        var peer: TestControl? = null
        var candidateAuthenticationSucceeds = true
        private var listener: ((ByteArray) -> Unit)? = null
        private var sclSequence = 0L
        private var securityPacketNumber = 0L
        val activeRecords = mutableListOf<SecureRecord>()
        val activeTypes: List<SessionLifecycleMessageType>
            get() = activeRecords.mapNotNull { it.type }

        override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) {
            this.listener = listener
        }

        override fun send(payload: ByteArray): SecureSessionControlSendResult {
            val copy = payload.copyOf()
            activeRecords += SecureRecord(++sclSequence, ++securityPacketNumber, copy, typeOf(copy))
            peer?.listener?.invoke(copy)
            return SecureSessionControlSendResult(SessionProtectionError.None, copy)
        }

        override fun protectCandidate(payload: ByteArray): SecureSessionControlSendResult =
            SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())

        override fun unprotectCandidate(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ): SessionControlUnprotectResult = if (candidateAuthenticationSucceeds) {
            SessionControlUnprotectResult(SessionProtectionError.None, protectedDatagram.copyOf())
        } else {
            SessionControlUnprotectResult(SessionProtectionError.AuthFailure)
        }

        override fun rebindRemoteEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError =
            SessionProtectionError.None

        fun deliverInbound(payload: ByteArray) {
            listener?.invoke(payload.copyOf())
        }

        override fun close() = Unit
    }

    private data class SecureRecord(
        val sclSequence: Long,
        val securityPacketNumber: Long,
        val payload: ByteArray,
        val type: SessionLifecycleMessageType?,
    )

    private class TestMigrationAdapter(private val channelIds: List<ChannelId>) : SessionLifecycleMigrationAdapter {
        var deliver: ((ByteArray) -> Unit)? = null
        var armCount = 0
        var commitCount = 0
        var ordinaryMediaDatagrams = 0
        val candidateTypes = mutableListOf<SessionLifecycleMessageType>()

        override fun armCandidateWindow(
            binding: LifecyclePathBinding,
            migrationId: PathMigrationId,
            timeoutMs: Long,
        ): Boolean {
            armCount += 1
            return binding.isValid() && timeoutMs > 0
        }

        override fun disarmCandidateWindow(migrationId: PathMigrationId) = Unit

        override fun sendCandidate(binding: LifecyclePathBinding, protectedDatagram: ByteArray): Boolean {
            val decoded = SessionLifecycleCodec.decode(protectedDatagram) ?: return false
            candidateTypes += decoded.message.header.messageType
            deliver?.invoke(protectedDatagram.copyOf())
            return true
        }

        override fun prepareChannels(
            binding: LifecyclePathBinding,
            channels: List<SessionChannelKind>,
        ): ChannelMigrationPreparation? = TestPreparation(channelIds)

        override fun commit(
            binding: LifecyclePathBinding,
            preparation: ChannelMigrationPreparation,
            remoteEntries: List<PathMigrationEntry>,
        ): SessionLifecycleError {
            if (remoteEntries.map { it.channelId }.toSet() != channelIds.toSet()) {
                return SessionLifecycleError.PathMigrationConflict
            }
            commitCount += 1
            return SessionLifecycleError.None
        }
    }

    private class TestPreparation(ids: List<ChannelId>) : ChannelMigrationPreparation {
        override val entries: List<PathMigrationEntry> = ids.mapIndexed { index, id ->
            PathMigrationEntry(
                id,
                45_000 + index,
            )
        }
        override fun close() = Unit
    }

    private class TestRuntime(
        override val sessionId: SessionId,
        override val sessionControlContext: ProtectionContextIds,
    ) : SessionProtectionRuntime {
        override val maxInnerSclDatagramSize: Int = 1_156
        var createdChannelContexts = 0
        var closed = false

        override fun createChannelContext(channelId: ChannelId): SessionProtectionContextResult {
            createdChannelContexts += 1
            return SessionProtectionContextResult(SessionProtectionError.None, ProtectionContextIds(500, 600))
        }

        override fun destroyChannelContext(channelId: ChannelId): SessionProtectionError = SessionProtectionError.None

        override fun protectSessionControl(
            sequenceNumber: Long,
            timestampUs: Long,
            payload: ByteArray,
        ): SecureSessionControlSendResult = SecureSessionControlSendResult(
            SessionProtectionError.None,
            payload.copyOf(),
        )

        override fun unprotectSessionControl(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ): SessionControlUnprotectResult = SessionControlUnprotectResult(
            SessionProtectionError.None,
            protectedDatagram.copyOf(),
        )

        override fun snapshot(): SessionProtectionSnapshot = SessionProtectionSnapshot(
            activeContexts = 1,
            protectedPackets = 0,
            decryptedPackets = 0,
            replayDrops = 0,
            tooOldDrops = 0,
            unknownContextDrops = 0,
            endpointFilterDrops = 0,
            authFailures = 0,
            keyUpdatesSent = 0,
            keyUpdatesAccepted = 0,
            currentSendEpoch = 0,
            currentReceiveEpoch = 0,
            lastError = SessionProtectionError.None,
        )

        override fun close() {
            closed = true
        }
    }

    private class TestChannelProtection(
        override val channelId: ChannelId,
        override val contextIds: ProtectionContextIds,
    ) : PreparedChannelProtection {
        override fun close() = Unit
    }

    private class TestLease(
        override val binding: PathSocketBinding,
        override val localPort: Int,
        override val channelKind: SessionChannelKind,
    ) : ChannelEndpointLease {
        override fun close() = Unit
    }

    private class TestPreparedTransport : PreparedChannelTransport {
        override val protectedRequired: Boolean = true
        override val started: Boolean = false
        override fun close() = Unit
    }

    private class TestCapacityOwner : SessionLifecycleCapacityOwner {
        var promoteCount = 0
        var recoveryCount = 0
        var closeCount = 0

        override fun promote(sessionId: SessionId, peerDeviceId: DeviceId, generation: SessionGeneration): Boolean {
            promoteCount += 1
            return true
        }

        override fun beginRecovery(
            sessionId: SessionId,
            peerDeviceId: DeviceId,
            generation: SessionGeneration,
            recoveryWindowMs: Long,
        ): Boolean {
            recoveryCount += 1
            return true
        }

        override fun handoffToFreshGeneration() = Unit

        override fun close() {
            closeCount += 1
        }
    }

    private class TestContinuityParticipant : SessionContinuityParticipant {
        val events = mutableListOf<String>()
        val inputSafetyResets = mutableListOf<LifecycleInputSafetyResetReason>()
        override fun onPathMigrationStarting() {
            events += "migration-start"
        }

        override fun onPathMigrationCommitted() {
            events += "migration-commit"
        }

        override fun onSessionSuspended() {
            events += "suspended"
        }

        override fun onSessionReconnected() {
            events += "reconnected"
        }

        override fun onSessionClosing() {
            events += "closing"
        }

        override fun onInputSafetyReset(reason: LifecycleInputSafetyResetReason) {
            inputSafetyResets += reason
        }
    }

    private class TestReconnectOrchestrator(
        private val freshFactory: ((SessionGeneration) -> PreparedSessionBootstrap)? = null,
    ) : SessionLifecycleReconnectDelegate {
        var calls = 0
        var record: RecoverableSessionRecord? = null
        var nextGeneration: SessionGeneration? = null
        val steps = mutableListOf<String>()
        var fresh: PreparedSessionBootstrap? = null

        override fun onReconnectRequired(record: RecoverableSessionRecord, nextGeneration: SessionGeneration) {
            calls += 1
            this.record = record
            this.nextGeneration = nextGeneration
            steps += "005D"
            steps += "005E"
            steps += "005F"
            steps += "005G"
            fresh = freshFactory?.invoke(nextGeneration)
        }
    }

    private class TestClock(var nowMs: Long) : SessionLifecycleMonotonicClock {
        override fun nowMs(): Long = nowMs
    }

    private companion object {
        val sessionId: SessionId = SessionId.requireValid(0uL, 100uL)
        val clientDevice: DeviceId = DeviceId.requireValid(0uL, 101uL)
        val hostDevice: DeviceId = DeviceId.requireValid(0uL, 102uL)
        val directPath: PathId = PathId.requireValid(1u)
        val lanPath: PathId = PathId.requireValid(2u)
        val clientLanEndpoint: HandshakeTransportEndpoint = endpoint("192.168.1.2", 45_000)
        val hostLanEndpoint: HandshakeTransportEndpoint = endpoint("192.168.1.1", 45_000)

        fun channel(value: UInt): ChannelId = ChannelId.requireValid(value)

        fun header(type: SessionLifecycleMessageType, id: ULong): SessionLifecycleHeader =
            SessionLifecycleHeader(type, LifecycleMessageId.requireValid(id), 0)

        fun typeOf(payload: ByteArray): SessionLifecycleMessageType? =
            SessionLifecycleCodec.decode(payload)?.message?.header?.messageType

        fun endpoint(address: String, port: Int): HandshakeTransportEndpoint = requireNotNull(
            HandshakeTransportEndpoint.from(InetAddress.getByName(address).address, port),
        )

        fun Boolean.then(value: () -> SessionPathPlan): SessionPathPlan? = if (this) value() else null

        fun profile() = NegotiatedCapabilityProfile(
            selectedChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
            eligiblePathKinds = CapabilityBits.PATH_DIRECT or CapabilityBits.PATH_LAN,
            secureDatagramBytes = 1_200,
            maxSessionChannels = 32,
            recoveryFlags = 0,
            videoCodec = NegotiatedCapabilityProfile.VIDEO_CODEC_AVC,
            videoFlags = CapabilityBits.VIDEO_LOW_LATENCY_DECODE,
            videoPayloadVersion = 1,
            videoMaxWidth = 1_920,
            videoMaxHeight = 1_080,
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
            inputKinds = CapabilityBits.INPUT_KEYBOARD,
            inputFeatureFlags = CapabilityBits.INPUT_STATE_CONVERGENCE,
            stablePresenceKinds = 0,
        )
    }
}
