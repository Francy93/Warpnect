package io.warpnect.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    @Test
    fun identitiesAreOpaqueTypedAndRejectReservedValues() {
        val device = device(1uL)
        val sameDevice = device(1uL)
        val session = session(1uL)

        assertEquals(device, sameDevice)
        assertNotEquals(device, session)
        assertEquals(
            "DeviceId(00000000000000000000000000000001)",
            device.toString(),
        )
        assertEquals(
            "SessionId(00000000000000000000000000000001)",
            session.toString(),
        )
        assertNull(DeviceId.fromParts(0uL, 0uL))
        assertNull(SessionId.fromParts(0uL, 0uL))
        assertNull(SessionGeneration.from(0u))
        assertNull(ChannelId.from(0u))
        assertNull(PathId.from(0u))
        assertNull(ParticipantIndex.from(-1))
        assertNull(ParticipantIndex.from(SessionBounds.HARD_MAX_CONCURRENT_CLIENTS))
    }

    @Test
    fun behaviorPolicySupportsAllBoundedPhaseFivePreferences() {
        PathPreferencePolicy.entries.forEach { pathPreference ->
            SecondaryPathPolicy.entries.forEach { secondaryPath ->
                MicrophoneRoutingPolicy.entries.forEach { microphoneRouting ->
                    val policy = SessionBehaviorPolicy(
                        maxConcurrentClients = 2,
                        microphoneRoutingPolicy = microphoneRouting,
                        pathPreferencePolicy = pathPreference,
                        secondaryPathPolicy = secondaryPath,
                    )
                    assertEquals(SessionError.None, policy.validate())
                }
            }
        }
        assertEquals(
            SessionError.InvalidPolicy,
            SessionBehaviorPolicy(maxConcurrentClients = 0).validate(),
        )
        assertEquals(
            SessionError.InvalidPolicy,
            SessionBehaviorPolicy(
                maxConcurrentClients = SessionBounds.HARD_MAX_CONCURRENT_CLIENTS + 1,
            ).validate(),
        )
        assertEquals(
            SessionError.InvalidManagerConfiguration,
            SessionManagerConfig(
                localDeviceId = device(10uL),
                maxSessions = SessionBounds.HARD_MAX_SESSIONS + 1,
            ).validate(),
        )
    }

    @Test
    fun defaultHostPolicyKeepsTheFirstClientSessionAndRejectsTheSecond() {
        val manager = hostManager()
        val first = manager.createSession(hostRequest(1uL, device(101uL)))
        val second = manager.createSession(hostRequest(2uL, device(102uL)))

        assertTrue(first.isSuccess)
        assertEquals(SessionError.SessionCapacityExceeded, second.error)
        assertEquals(1, manager.snapshot().registeredSessionCount)
        assertEquals(first.session?.sessionId, manager.session(session(1uL))?.sessionId)
    }

    @Test
    fun authenticatedAdmissionsAreBoundedAndReleasedWithoutCreatingSessions() {
        val manager = hostManager()
        val first = manager.reserveAuthenticatedAdmission(
            session(90uL),
            device(190uL),
            SessionGeneration.Initial,
            30_000L,
        )
        val second = manager.reserveAuthenticatedAdmission(
            session(91uL),
            device(191uL),
            SessionGeneration.Initial,
            30_000L,
        )

        assertTrue(first.isSuccess)
        assertEquals(SessionError.SessionCapacityExceeded, second.error)
        assertEquals(0, manager.snapshot().registeredSessionCount)
        assertEquals(1, manager.snapshot().authenticatedReservationCount)
        assertEquals(SessionError.None, manager.releaseAuthenticatedAdmission(session(90uL)))
        assertEquals(0, manager.snapshot().authenticatedReservationCount)
    }

    @Test
    fun lifecycleRecoveryRetainsExactlyOneHostCapacitySlotAcrossGenerationClaim() {
        val manager = hostManager()
        val sessionId = session(93uL)
        val peer = device(193uL)
        assertTrue(manager.reserveAuthenticatedAdmission(sessionId, peer, SessionGeneration.Initial, 30_000L).isSuccess)
        assertTrue(
            manager.promoteAuthenticatedAdmissionToLifecycle(sessionId, peer, SessionGeneration.Initial).isSuccess,
        )
        assertEquals(1, manager.snapshot().lifecycleAdmissionCount)
        assertEquals(0, manager.snapshot().authenticatedReservationCount)
        assertTrue(manager.beginLifecycleRecovery(sessionId, peer, SessionGeneration.Initial, 30_000L).isSuccess)
        assertEquals(1, manager.snapshot().recoveryLeaseCount)
        assertEquals(1, manager.snapshot().lifecycleAdmissionCount + manager.snapshot().recoveryLeaseCount)

        val next = SessionGeneration.requireValid(2u)
        assertTrue(manager.hasPendingRecoveryAdmission(sessionId, next))
        assertFalse(manager.claimRecoveryAdmission(sessionId, peer, SessionGeneration.Initial, 30_000L).isSuccess)
        assertTrue(manager.claimRecoveryAdmission(sessionId, peer, next, 30_000L).isSuccess)
        assertEquals(1, manager.snapshot().authenticatedReservationCount)
        assertEquals(0, manager.snapshot().recoveryLeaseCount)
        assertFalse(
            manager.reserveAuthenticatedAdmission(
                session(94uL),
                device(194uL),
                SessionGeneration.Initial,
                30_000L,
            ).isSuccess,
        )
    }

    @Test
    fun expiredRecoveryLeaseReleasesItsSingleHostCapacitySlotExactlyOnce() {
        val clock = ManualClock(1_000L)
        val manager = hostManager(clock = clock)
        val sessionId = session(95uL)
        val peer = device(195uL)
        assertTrue(manager.reserveAuthenticatedAdmission(sessionId, peer, SessionGeneration.Initial, 100L).isSuccess)
        assertTrue(
            manager.promoteAuthenticatedAdmissionToLifecycle(sessionId, peer, SessionGeneration.Initial).isSuccess,
        )
        assertTrue(manager.beginLifecycleRecovery(sessionId, peer, SessionGeneration.Initial, 100L).isSuccess)
        assertEquals(1, manager.snapshot().recoveryLeaseCount)

        clock.nowUs = 1_100L
        assertEquals(0, manager.snapshot().recoveryLeaseCount)
        assertEquals(SessionError.RecoveryLeaseNotFound, manager.releaseRecoveryAdmission(sessionId))
        assertTrue(
            manager.reserveAuthenticatedAdmission(
                session(96uL),
                device(196uL),
                SessionGeneration.Initial,
                100L,
            ).isSuccess,
        )
    }

    @Test
    fun recoveryClaimRequiresExpectedPeerExactlyNextGenerationAndHasOneWinner() {
        val manager = hostManager()
        val sessionId = session(97uL)
        val expectedPeer = device(197uL)
        assertTrue(
            manager.reserveAuthenticatedAdmission(
                sessionId,
                expectedPeer,
                SessionGeneration.Initial,
                30_000L,
            ).isSuccess,
        )
        assertTrue(
            manager.promoteAuthenticatedAdmissionToLifecycle(
                sessionId,
                expectedPeer,
                SessionGeneration.Initial,
            ).isSuccess,
        )
        assertTrue(
            manager.beginLifecycleRecovery(sessionId, expectedPeer, SessionGeneration.Initial, 30_000L).isSuccess,
        )
        val next = SessionGeneration.requireValid(2u)

        assertFalse(manager.claimRecoveryAdmission(sessionId, device(198uL), next, 30_000L).isSuccess)
        assertFalse(
            manager.claimRecoveryAdmission(sessionId, expectedPeer, SessionGeneration.Initial, 30_000L).isSuccess,
        )
        assertFalse(
            manager.claimRecoveryAdmission(
                sessionId,
                expectedPeer,
                SessionGeneration.requireValid(3u),
                30_000L,
            ).isSuccess,
        )
        assertTrue(manager.claimRecoveryAdmission(sessionId, expectedPeer, next, 30_000L).isSuccess)
        assertFalse(manager.claimRecoveryAdmission(sessionId, expectedPeer, next, 30_000L).isSuccess)
        assertEquals(1, manager.snapshot().authenticatedReservationCount)
    }

    @Test
    fun multiClientAndSamePeerPoliciesAreExplicitAndBounded() {
        val singlePeerManager = hostManager(
            policy = SessionBehaviorPolicy(maxConcurrentClients = 2),
        )
        assertTrue(singlePeerManager.createSession(hostRequest(1uL, device(101uL))).isSuccess)
        assertEquals(
            SessionError.DuplicatePeerSessionNotAllowed,
            singlePeerManager.createSession(hostRequest(2uL, device(101uL))).error,
        )

        val multiplePeerManager = hostManager(
            policy = SessionBehaviorPolicy(
                maxConcurrentClients = 2,
                duplicatePeerSessionPolicy = DuplicatePeerSessionPolicy.MultipleSessionsPerPeer,
            ),
        )
        assertTrue(multiplePeerManager.createSession(hostRequest(3uL, device(101uL))).isSuccess)
        assertTrue(multiplePeerManager.createSession(hostRequest(4uL, device(101uL))).isSuccess)
        assertEquals(2, multiplePeerManager.sessionsForPeer(device(101uL)).size)
        assertEquals(2, multiplePeerManager.snapshot().hostSessions)
        assertEquals(0, multiplePeerManager.session(session(3uL))?.participantIndex?.value)
        assertEquals(1, multiplePeerManager.session(session(4uL))?.participantIndex?.value)
    }

    @Test
    fun channelsHaveExplicitDirectionsAndAllowMultipleMicrophoneSources() {
        val manager = hostManager()
        val created = manager.createSession(hostRequest(1uL, device(101uL)))
        val sessionId = requireNotNull(created.session).sessionId
        val channels = listOf(
            SessionChannel(channel(1u), SessionChannelKind.Control),
            SessionChannel(channel(2u), SessionChannelKind.Video),
            SessionChannel(channel(3u), SessionChannelKind.SystemAudio),
            SessionChannel(channel(4u), SessionChannelKind.MicrophoneAudio),
            SessionChannel(channel(5u), SessionChannelKind.Input),
            SessionChannel(channel(6u), SessionChannelKind.Telemetry),
            SessionChannel(channel(7u), SessionChannelKind.MicrophoneAudio),
        )

        channels.forEach { channel ->
            assertTrue(manager.addChannel(sessionId, channel).isSuccess)
        }

        val snapshot = requireNotNull(manager.session(sessionId))
        assertEquals(7, snapshot.channels.size)
        assertEquals(
            SessionChannelDirection.HostToClient,
            snapshot.channels.single { it.kind == SessionChannelKind.Video }.direction,
        )
        assertEquals(
            2,
            snapshot.channels.count {
                it.kind == SessionChannelKind.MicrophoneAudio &&
                    it.direction == SessionChannelDirection.ClientToHost
            },
        )
        assertEquals(
            SessionChannelDirection.Bidirectional,
            snapshot.channels.single { it.kind == SessionChannelKind.Control }.direction,
        )
    }

    @Test
    fun peripheralNamespacesAreScopedToSessionsAndSupportMultipleInstances() {
        val manager = hostManager(policy = SessionBehaviorPolicy(maxConcurrentClients = 2))
        val first = requireNotNull(manager.createSession(hostRequest(1uL, device(101uL))).session)
        val second = requireNotNull(manager.createSession(hostRequest(2uL, device(102uL))).session)
        val firstGamepad = peripheral(first.sessionId, PeripheralKind.Gamepad, 0)
        val secondGamepad = peripheral(second.sessionId, PeripheralKind.Gamepad, 0)

        assertNotEquals(firstGamepad, secondGamepad)
        assertTrue(manager.addPeripheral(first.sessionId, SessionPeripheral(firstGamepad)).isSuccess)
        assertTrue(manager.addPeripheral(second.sessionId, SessionPeripheral(secondGamepad)).isSuccess)
        assertTrue(
            manager.addPeripheral(
                first.sessionId,
                SessionPeripheral(peripheral(first.sessionId, PeripheralKind.Gamepad, 1)),
            ).isSuccess,
        )
        assertTrue(
            manager.addPeripheral(
                first.sessionId,
                SessionPeripheral(peripheral(first.sessionId, PeripheralKind.Microphone, 0)),
            ).isSuccess,
        )
        assertTrue(
            manager.addPeripheral(
                first.sessionId,
                SessionPeripheral(peripheral(first.sessionId, PeripheralKind.Microphone, 1)),
            ).isSuccess,
        )

        assertEquals(4, requireNotNull(manager.session(first.sessionId)).logicalPeripherals.size)
        assertEquals(1, requireNotNull(manager.session(second.sessionId)).logicalPeripherals.size)
    }

    @Test
    fun presencePoliciesKeepPhysicalAndTargetExposureStateSeparate() {
        val policy = SessionBehaviorPolicy(
            microphoneRoutingPolicy = MicrophoneRoutingPolicy.MixToSingleHostStream,
            peripheralPresencePolicies = PeripheralPresencePolicies(
                gamepad = PeripheralPresencePolicy.StableSessionPresence,
            ),
        )
        val manager = hostManager(policy = policy)
        val snapshot = requireNotNull(manager.createSession(hostRequest(1uL, device(101uL))).session)
        val gamepad = peripheral(snapshot.sessionId, PeripheralKind.Gamepad, 0)
        val microphone = peripheral(snapshot.sessionId, PeripheralKind.Microphone, 0)

        assertTrue(
            manager.addPeripheral(
                snapshot.sessionId,
                SessionPeripheral(
                    gamepad,
                    SourcePeripheralPresence.Present,
                    TargetPeripheralExposureState.Active,
                ),
            ).isSuccess,
        )
        assertTrue(
            manager.updatePeripheralPresence(
                snapshot.sessionId,
                gamepad,
                SourcePeripheralPresence.Absent,
                TargetPeripheralExposureState.RetainedInactive,
            ).isSuccess,
        )
        assertTrue(
            manager.addPeripheral(
                snapshot.sessionId,
                SessionPeripheral(
                    microphone,
                    SourcePeripheralPresence.Present,
                    TargetPeripheralExposureState.Active,
                ),
            ).isSuccess,
        )
        assertTrue(
            manager.updatePeripheralPresence(
                snapshot.sessionId,
                microphone,
                SourcePeripheralPresence.Present,
                TargetPeripheralExposureState.NotExposed,
            ).isSuccess,
        )
        assertTrue(
            manager.updatePeripheralPresence(
                snapshot.sessionId,
                microphone,
                SourcePeripheralPresence.Absent,
                TargetPeripheralExposureState.NotExposed,
            ).isSuccess,
        )
        assertEquals(
            SessionError.InvalidPeripheralPresence,
            manager.updatePeripheralPresence(
                snapshot.sessionId,
                microphone,
                SourcePeripheralPresence.Absent,
                TargetPeripheralExposureState.Active,
            ).error,
        )

        val updated = requireNotNull(manager.session(snapshot.sessionId))
        val retained = updated.logicalPeripherals.single { it.id == gamepad }
        assertEquals(SourcePeripheralPresence.Absent, retained.sourcePresence)
        assertEquals(TargetPeripheralExposureState.RetainedInactive, retained.targetExposure)
        assertEquals(MicrophoneRoutingPolicy.MixToSingleHostStream, updated.policy.microphoneRoutingPolicy)
    }

    @Test
    fun directAndLanPathsMigrateWithoutChangingSessionOrPeripheralIdentity() {
        val manager = hostManager()
        val created = requireNotNull(manager.createSession(hostRequest(1uL, device(101uL))).session)
        val gamepad = peripheral(created.sessionId, PeripheralKind.Gamepad, 0)
        assertTrue(manager.addPeripheral(created.sessionId, SessionPeripheral(gamepad)).isSuccess)
        assertTrue(
            manager.addPath(
                created.sessionId,
                NetworkPath(path(1u), NetworkPathKind.Direct, NetworkPathState.Active),
            ).isSuccess,
        )
        assertTrue(
            manager.addPath(
                created.sessionId,
                NetworkPath(path(2u), NetworkPathKind.Lan, NetworkPathState.Standby),
            ).isSuccess,
        )
        assertEquals(
            SessionError.MultipleActivePaths,
            manager.addPath(
                created.sessionId,
                NetworkPath(path(3u), NetworkPathKind.Lan, NetworkPathState.Active),
            ).error,
        )
        assertEquals(
            SessionError.DuplicatePathId,
            manager.addPath(
                created.sessionId,
                NetworkPath(path(2u), NetworkPathKind.Lan, NetworkPathState.Standby),
            ).error,
        )

        assertTrue(manager.updatePathState(created.sessionId, path(1u), NetworkPathState.Failed).isSuccess)
        assertTrue(manager.updatePathState(created.sessionId, path(2u), NetworkPathState.Active).isSuccess)

        val migrated = requireNotNull(manager.session(created.sessionId))
        assertEquals(created.sessionId, migrated.sessionId)
        assertEquals(created.remoteDeviceId, migrated.remoteDeviceId)
        assertEquals(path(2u), migrated.activePathId)
        assertEquals(gamepad, migrated.logicalPeripherals.single().id)
    }

    @Test
    fun managerEnforcesTopologyBoundsAndPreservesPriorSnapshots() {
        val manager = hostManager(
            maxChannels = 1,
            maxPaths = 1,
            maxPeripherals = 1,
        )
        val created = requireNotNull(manager.createSession(hostRequest(1uL, device(101uL))).session)
        val sessionId = created.sessionId
        assertTrue(manager.addChannel(sessionId, SessionChannel(channel(1u), SessionChannelKind.Input)).isSuccess)
        val before = requireNotNull(manager.session(sessionId))
        assertEquals(
            SessionError.ChannelCapacityExceeded,
            manager.addChannel(sessionId, SessionChannel(channel(2u), SessionChannelKind.Video)).error,
        )
        assertTrue(
            manager.addPath(sessionId, NetworkPath(path(1u), NetworkPathKind.Direct)).isSuccess,
        )
        assertEquals(
            SessionError.PathCapacityExceeded,
            manager.addPath(sessionId, NetworkPath(path(2u), NetworkPathKind.Lan)).error,
        )
        assertTrue(
            manager.addPeripheral(
                sessionId,
                SessionPeripheral(peripheral(sessionId, PeripheralKind.Gamepad, 0)),
            ).isSuccess,
        )
        assertEquals(
            SessionError.PeripheralCapacityExceeded,
            manager.addPeripheral(
                sessionId,
                SessionPeripheral(peripheral(sessionId, PeripheralKind.Gamepad, 1)),
            ).error,
        )
        assertEquals(1, before.channels.size)
        assertEquals(0, before.paths.size)
        assertEquals(0, before.logicalPeripherals.size)
    }

    @Test
    fun lifecycleLookupPolicyReplacementAndCloseAreDeterministic() {
        val manager = hostManager()
        val created = requireNotNull(manager.createSession(hostRequest(1uL, device(101uL))).session)
        assertEquals(
            SessionError.InvalidStateTransition,
            manager.transitionState(created.sessionId, SessionState.Ready).error,
        )
        assertTrue(manager.transitionState(created.sessionId, SessionState.Establishing).isSuccess)
        assertTrue(manager.transitionState(created.sessionId, SessionState.Ready).isSuccess)
        assertTrue(manager.transitionState(created.sessionId, SessionState.Running).isSuccess)
        assertTrue(
            manager.replacePolicy(
                SessionBehaviorPolicy(
                    maxConcurrentClients = 1,
                    pathPreferencePolicy = PathPreferencePolicy.PreferLan,
                    secondaryPathPolicy = SecondaryPathPolicy.Disabled,
                ),
            ).isSuccess,
        )
        assertEquals(PathPreferencePolicy.PreferLan, manager.snapshot().policy.pathPreferencePolicy)
        assertEquals(
            PathPreferencePolicy.PreferDirectThenLan,
            manager.session(created.sessionId)?.policy?.pathPreferencePolicy,
        )
        assertTrue(manager.removeSession(created.sessionId).isSuccess)
        assertNull(manager.session(created.sessionId))

        manager.close()
        manager.close()
        assertTrue(manager.snapshot().closed)
        assertFalse(manager.createSession(hostRequest(2uL, device(102uL))).isSuccess)
        assertEquals(
            SessionError.Closed,
            manager.createSession(hostRequest(3uL, device(103uL))).error,
        )
    }

    @Test
    fun invalidTopologyIsRejectedWithoutReplacingExistingSession() {
        val manager = hostManager()
        val created = requireNotNull(manager.createSession(hostRequest(1uL, device(101uL))).session)
        assertEquals(
            SessionError.DuplicateSessionId,
            manager.createSession(hostRequest(1uL, device(102uL))).error,
        )
        assertEquals(
            SessionError.InvalidRoleCombination,
            manager.createSession(
                SessionCreateRequest(
                    sessionId = session(2uL),
                    remotePeer = PeerReference(device(102uL)),
                    localRole = SessionRole.Host,
                    remoteRole = SessionRole.Host,
                ),
            ).error,
        )
        assertTrue(
            manager.addChannel(
                created.sessionId,
                SessionChannel(channel(1u), SessionChannelKind.Input),
            ).isSuccess,
        )
        assertEquals(
            SessionError.DuplicateChannelId,
            manager.addChannel(
                created.sessionId,
                SessionChannel(channel(1u), SessionChannelKind.Input),
            ).error,
        )
        assertEquals(created.sessionId, requireNotNull(manager.session(created.sessionId)).sessionId)
    }

    private fun hostManager(
        policy: SessionBehaviorPolicy = SessionBehaviorPolicy(),
        maxChannels: Int = SessionBounds.DEFAULT_MAX_CHANNELS_PER_SESSION,
        maxPaths: Int = SessionBounds.DEFAULT_MAX_PATHS_PER_SESSION,
        maxPeripherals: Int = SessionBounds.DEFAULT_MAX_PERIPHERALS_PER_SESSION,
        clock: SessionMonotonicClock = TestClock(),
    ): SessionManager = SessionManager(
        config = SessionManagerConfig(
            localDeviceId = device(10uL),
            initialPolicy = policy,
            maxChannelsPerSession = maxChannels,
            maxPathsPerSession = maxPaths,
            maxPeripheralsPerSession = maxPeripherals,
        ),
        clock = clock,
    )

    private fun hostRequest(sessionValue: ULong, peer: DeviceId): SessionCreateRequest = SessionCreateRequest(
        sessionId = session(sessionValue),
        remotePeer = PeerReference(peer),
        localRole = SessionRole.Host,
        remoteRole = SessionRole.Client,
    )

    private fun device(value: ULong): DeviceId = DeviceId.requireValid(0uL, value)

    private fun session(value: ULong): SessionId = SessionId.requireValid(0uL, value)

    private fun channel(value: UInt): ChannelId = ChannelId.requireValid(value)

    private fun path(value: UInt): PathId = PathId.requireValid(value)

    private fun peripheral(sessionId: SessionId, kind: PeripheralKind, slot: Int): LogicalPeripheralId =
        LogicalPeripheralId.requireValid(sessionId, kind, slot)

    private class TestClock : SessionMonotonicClock {
        private var nowUs = 1_000L

        override fun nowUs(): Long = nowUs++
    }

    private class ManualClock(var nowUs: Long) : SessionMonotonicClock {
        override fun nowUs(): Long = nowUs
    }
}
