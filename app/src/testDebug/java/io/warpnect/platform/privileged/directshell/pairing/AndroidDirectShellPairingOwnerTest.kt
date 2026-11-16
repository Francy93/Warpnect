package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellSyntheticPairingComposition.Stage
import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidDirectShellPairingOwnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun privateSetupRotationReattachesAndDoesNotRestoreConsentAfterProcessDeath() {
        val access = DirectShellPrivateSetupSession.NotificationAccess(30, true, true, false, true)
        val clock = Clock()
        lateinit var oldAction: DirectShellPrivateSetupSession.NotificationAction
        passiveOwner(clock, Queue()).use { old ->
            DirectShellPrivateSetupSession(old).use { setup ->
                val first = setup.attach()
                assertEquals(DirectShellPairingPhase.IDLE, old.state.value.phase)
                oldAction = requireNotNull(setup.begin(first, 1, access))
                setup.detach(first)
                val rotated = setup.attach()
                setup.cancel(first)
                setup.resume(rotated)
                assertNull(setup.begin(rotated, 1, access))
                assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, old.state.value.phase)
            }
        }
        passiveOwner(clock, Queue()).use { recreated ->
            DirectShellPrivateSetupSession(recreated).use { setup ->
                setup.attach()
                val input = code()
                assertFalse(setup.receive(oldAction, access, input))
                assertTrue(input.all { it == '\u0000' })
                assertEquals(DirectShellPairingPhase.IDLE, recreated.state.value.phase)
            }
        }
    }

    @Test fun privateSetupDenialCancellationAndExpiryRetireTheNotificationAction() {
        val allowed = DirectShellPrivateSetupSession.NotificationAccess(33, true, true, true, true)
        val denied = allowed.copy(postNotificationsGranted = false)
        val clock = Clock()
        passiveOwner(clock, Queue()).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val activity = setup.attach()
                assertNull(setup.begin(activity, 1, denied))
                assertNull(setup.begin(activity, 1, allowed.copy(channelEnabled = false)))
                assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
                val first = requireNotNull(setup.begin(activity, 1, allowed))
                val input = code()
                assertFalse(setup.receive(first, denied, input))
                assertTrue(input.all { it == '\u0000' })
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
                val second = requireNotNull(setup.begin(activity, 1, allowed))
                assertFalse(setup.receive(first, allowed, code()))
                clock.now = DirectShellPairingCoordinator.CONSENT_MS
                assertFalse(setup.receive(second, allowed, code()))
                assertEquals(DirectShellPairingPhase.EXPIRED, owner.state.value.phase)
                assertTrue(owner.state.value.drained)
            }
        }
    }

    @Test fun lockingDeniesAndPermanentlyRetiresTheExactPendingAction() {
        val unlocked = DirectShellPrivateSetupSession.NotificationAccess(31, true, true, false, true)
        val locked = unlocked.copy(deviceUnlocked = false)
        passiveOwner(Clock(), Queue()).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val screen = setup.attach()
                assertNull(setup.begin(screen, 1, locked))
                val issued = checkNotNull(setup.begin(screen, 1, unlocked))
                val input = code()
                assertFalse(setup.receive(issued, locked, input))
                assertTrue(input.all { it == '\u0000' })
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
                assertFalse(setup.receive(issued, unlocked, code()))
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
            }
        }
    }

    @Test fun foregroundAlternativeDoesNotArmFromResumeOrReviveAfterPauseOrRotation() {
        val denied = DirectShellPrivateSetupSession.NotificationAccess(33, true, true, false, true)
        passiveOwner(Clock(), Queue()).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val first = setup.attach()
                assertNull(setup.beginForeground(first, 1, unlocked = true))
                setup.resume(first)
                assertNull(setup.begin(first, 1, denied))
                assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
                val entry = checkNotNull(setup.beginForeground(first, 1, unlocked = true))
                setup.pause(first)
                setup.resume(first)
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
                val second = setup.attach()
                setup.resume(second)
                val newEntry = checkNotNull(setup.beginForeground(second, 1, unlocked = true))
                val stale = code()
                setup.pause(first)
                assertFalse(setup.receiveForeground(first, entry, unlocked = true, stale))
                assertTrue(stale.all { it == '\u0000' })
                assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
                val locked = code()
                assertFalse(setup.receiveForeground(second, newEntry, unlocked = false, locked))
                assertTrue(locked.all { it == '\u0000' })
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
            }
        }
    }

    @Test fun foregroundAlternativeUsesTheSameTicketAndRejectsLateSubmissionAfterExpiry() {
        val clock = Clock()
        val started = AtomicInteger()
        passiveOwner(clock, Queue(), checkpoint = {
            started.incrementAndGet()
            error("unexpected_attempt")
        }).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val screen = setup.attach()
                setup.resume(screen)
                val entry = checkNotNull(setup.beginForeground(screen, 1, unlocked = true))
                assertEquals(1L, owner.state.value.originReadinessGeneration)
                clock.now = DirectShellPairingCoordinator.CONSENT_MS
                val expired = code()
                assertFalse(setup.receiveForeground(screen, entry, unlocked = true, expired))
                assertTrue(expired.all { it == '\u0000' })
                assertEquals(DirectShellPairingPhase.EXPIRED, owner.state.value.phase)
                assertEquals(0, started.get())
            }
        }
    }

    @Test fun foregroundSubmissionDispatchesOnceAndPauseDoesNotPretendToDrainRunningWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        passiveOwner(Clock(), Queue(), checkpoint = { stage ->
            if (stage == Stage.BEFORE_RECOVERY) {
                entered.countDown()
                await(release)
                error("stop_fixture_before_storage")
            }
        }).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val screen = setup.attach()
                setup.resume(screen)
                val entry = checkNotNull(setup.beginForeground(screen, 1, unlocked = true))
                try {
                    val input = code()
                    val duplicate = code()
                    assertTrue(setup.receiveForeground(screen, entry, unlocked = true, input))
                    await(entered)
                    assertFalse(setup.receiveForeground(screen, entry, unlocked = true, duplicate))
                    assertTrue((input + duplicate).all { it == '\u0000' })
                    setup.pause(screen)
                    assertEquals(DirectShellPairingPhase.DISCOVERING, owner.state.value.phase)
                    assertFalse(owner.state.value.drained)
                    setup.cancel(screen)
                } finally {
                    release.countDown()
                }
                awaitDrain(owner)
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
            }
        }
    }

    @Test fun changedReadinessRejectsAndRetiresOnlyTheExactReplyWithoutStartingWork() {
        val access = DirectShellPrivateSetupSession.NotificationAccess(31, true, true, false, true)
        var generation = 1L
        passiveOwner(Clock(), Queue(), readinessGeneration = { generation }).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val screen = setup.attach()
                setup.resume(screen)
                val old = checkNotNull(setup.begin(screen, generation, access))
                assertNull(setup.beginForeground(screen, generation, unlocked = true))
                generation++
                val obsolete = code()
                assertFalse(setup.receive(old, access, obsolete))
                assertTrue(obsolete.all { it == '\u0000' })
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
                val next = checkNotNull(setup.begin(screen, generation, access))
                assertFalse(setup.receive(old, access, code()))
                assertEquals(2L, owner.state.value.originReadinessGeneration)
                assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
                setup.dismiss(next)
            }
        }
    }

    @Test fun privateSetupDeliversOnceAcrossRotationToTheExistingAttempt() {
        val access = DirectShellPrivateSetupSession.NotificationAccess(31, true, true, false, true)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        passiveOwner(Clock(), Queue(), checkpoint = { stage ->
            if (stage == Stage.BEFORE_RECOVERY) {
                entered.countDown()
                await(proceed)
                error("stop_fixture_before_storage")
            }
        }).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val previous = setup.attach()
                val action = requireNotNull(setup.begin(previous, 1, access))
                val current = setup.attach()
                try {
                    val input = code()
                    val duplicate = code()
                    assertTrue(setup.receive(action, access, input))
                    await(entered)
                    assertFalse(setup.receive(action, access, duplicate))
                    assertTrue((input + duplicate).all { it == '\u0000' })
                    setup.cancel(previous)
                    assertEquals(DirectShellPairingPhase.DISCOVERING, owner.state.value.phase)
                    setup.cancel(current)
                } finally {
                    proceed.countDown()
                }
                awaitDrain(owner)
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
            }
        }
    }

    @Test fun dismissalWhileDetachedCancelsOnlyItsOwnPendingAction() {
        val access = DirectShellPrivateSetupSession.NotificationAccess(31, true, true, false, true)
        passiveOwner(Clock(), Queue()).use { owner ->
            DirectShellPrivateSetupSession(owner).use { setup ->
                val previous = setup.attach()
                val first = requireNotNull(setup.begin(previous, 1, access))
                setup.detach(previous)
                setup.dismiss(first)
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
                assertTrue(owner.state.value.drained)
                val current = setup.attach()
                val second = requireNotNull(setup.begin(current, 1, access))
                setup.dismiss(first)
                assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
                val stale = code()
                assertFalse(setup.receive(first, access, stale))
                assertTrue(stale.all { it == '\u0000' })
                setup.dismiss(second)
                assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
                assertTrue(owner.state.value.drained)
            }
        }
    }

    @Test fun concurrentCodeCarrierDeliveryUsesSameP1AttemptAndWipesBothInputs() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        passiveOwner(Clock(), Queue(), checkpoint = { stage ->
            if (stage == Stage.BEFORE_RECOVERY) {
                entered.countDown()
                await(release)
                error("stop_fixture_before_storage")
            }
        }).use { owner ->
            val ticket = requireNotNull(owner.request(1))
            val carrier = owner.bindCodeDelivery(ticket)
            val first = code()
            val second = code()
            val gate = CountDownLatch(1)
            val tasks = listOf(first, second).map { input ->
                FutureTask {
                    await(gate)
                    carrier.deliver(input)
                }
            }
            val threads = tasks.map { task -> Thread(task).also { it.start() } }
            try {
                gate.countDown()
                assertEquals(listOf(false, true), tasks.map { it.get(5, TimeUnit.SECONDS) }.sorted())
                await(entered)
                assertTrue((first + second).all { it == '\u0000' })
                assertNull(owner.request(1))
                owner.cancel(ticket)
            } finally {
                release.countDown()
                threads.forEach {
                    it.join(5_000)
                    assertFalse(it.isAlive)
                }
            }
            awaitDrain(owner)
        }
    }

    @Test fun invalidatedAndOldCodeCarriersCannotSubmitToNewTicket() {
        passiveOwner(Clock(), Queue()).use { owner ->
            val old = requireNotNull(owner.request(1))
            val invalidated = owner.bindCodeDelivery(old)
            invalidated.invalidate()
            val first = code()
            assertFalse(invalidated.deliver(first))
            assertTrue(first.all { it == '\u0000' })
            val late = owner.bindCodeDelivery(old)
            owner.cancel(old)
            val current = requireNotNull(owner.request(1))
            val second = code()
            assertFalse(late.deliver(second))
            assertTrue(second.all { it == '\u0000' })
            assertNotEquals(old.nonce, current.nonce)
            assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
        }
    }

    @Test fun staleReadinessGenerationCannotUsePrivateCodeCarrier() {
        var generation = 1L
        passiveOwner(Clock(), Queue(), readinessGeneration = { generation }).use { owner ->
            val ticket = requireNotNull(owner.request(generation))
            val carrier = owner.bindCodeDelivery(ticket)
            generation++
            val input = code()
            assertFalse(carrier.deliver(input))
            assertTrue(input.all { it == '\u0000' })
            assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
        }
    }

    @Test fun resumeExpiresConsentBeforeDelayedTimerAndDoesNotRestoreIt() {
        val clock = Clock()
        val queue = Queue()
        passiveOwner(clock, queue).use { owner ->
            val first = requireNotNull(owner.request(1))
            val delayed = queue.only()
            clock.now = 120_000
            owner.onResume()
            assertEquals(DirectShellPairingPhase.EXPIRED, owner.state.value.phase)
            assertTrue(owner.state.value.drained) // No operation was dispatched.
            assertFalse(owner.submitCode(first, code()))
            val second = requireNotNull(owner.request(1))
            delayed.run()
            assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
            assertFalse(owner.submitCode(first, code()))
            assertEquals(DirectShellPairingStop.STALE_OR_FINISHED, owner.cancel(first))
            assertNotEquals(first.nonce, second.nonce)
        }
    }

    @Test fun delayedDeliveryUsesElapsedTimeAndCannotRepeatAnAction() {
        val clock = Clock()
        val queue = Queue()
        passiveOwner(clock, queue).use { owner ->
            val ticket = requireNotNull(owner.request(1))
            val delayed = queue.only()
            clock.now = 10_000
            delayed.run()
            assertEquals(110_000L, queue.delay(delayed))
            clock.now = 160_000
            delayed.run()
            assertEquals(DirectShellPairingReason.CONSENT_EXPIRED, owner.state.value.reason)
            assertFalse(owner.submitCode(ticket, code()))
            delayed.run()
            assertEquals(0, queue.size())
        }
    }

    @Test fun disposalAndProcessRecreationRequireNewConsent() {
        val clock = Clock()
        val queue = Queue()
        val old = passiveOwner(clock, queue)
        val previous = requireNotNull(old.request(1))
        val delayed = queue.only()
        old.close()
        old.close()
        assertTrue(old.awaitObserverTermination(0))
        assertEquals(DirectShellPairingPhase.CLOSED, old.state.value.phase)
        assertNull(old.request(1))
        assertEquals(0, queue.size())
        passiveOwner(clock, Queue()).use { new ->
            new.onResume()
            assertEquals(DirectShellPairingPhase.IDLE, new.state.value.phase)
            val input = code()
            assertFalse(new.submitCode(previous, input))
            assertTrue(input.all { it == '\u0000' })
            val current = requireNotNull(new.request(1))
            delayed.run()
            assertNotEquals(previous.ownerEpoch, current.ownerEpoch)
            assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, new.state.value.phase)
        }
    }

    @Test fun changedNetworkEpochInvalidatesConsentWithoutDiscoveryAndLateTimerIsInert() {
        val clock = Clock()
        val queue = Queue()
        val environment = AtomicReference(environment())
        passiveOwner(clock, queue, environment = environment::get).use { owner ->
            val ticket = requireNotNull(owner.request(1))
            val delayed = queue.only()
            environment.set(environment.get().copy(networkEpoch = 2))
            owner.environmentChanged()
            assertEquals(DirectShellPairingReason.NETWORK_CHANGED, owner.state.value.reason)
            assertTrue(owner.state.value.drained)
            assertFalse(owner.submitCode(ticket, code()))
            val current = requireNotNull(owner.request(1))
            assertEquals(2L, current.networkEpoch)
            delayed.run()
            assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
        }
    }

    @Test fun concurrentDuplicateStopsDoNotDisplaceTheOperationOrProveDrain() {
        val clock = Clock()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = CountDownLatch(1)
        passiveOwner(clock, Queue()) { stage ->
            if (stage == Stage.BEFORE_RECOVERY) {
                entered.countDown()
                await(release)
            }
        }.use { owner ->
            try {
                val ticket = requireNotNull(owner.request(1))
                assertTrue(owner.submitCode(ticket, code()))
                await(entered)
                val workers = List(2) {
                    FutureTask {
                        await(gate)
                        owner.cancel(ticket)
                    }
                }
                val threads = workers.map { task -> Thread(task).also { it.start() } }
                gate.countDown()
                assertEquals(
                    setOf(
                        DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START,
                        DirectShellPairingStop.STALE_OR_FINISHED,
                    ),
                    workers.map { it.get(5, TimeUnit.SECONDS) }.toSet(),
                )
                threads.forEach {
                    it.join(5_000)
                    assertFalse(it.isAlive)
                }
                assertFalse(owner.state.value.drained)
                assertNull(owner.request(1))
                release.countDown()
                awaitDrain(owner)
                val next = requireNotNull(owner.request(1))
                assertNotEquals(ticket.nonce, next.nonce)
                assertEquals(DirectShellPairingStop.STALE_OR_FINISHED, owner.cancel(ticket))
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun resumeStopsExpiredTransport() = exerciseStalledTransport(dispose = false)

    @Test fun disposalDuringStalledTransportClosesAdmission() = exerciseStalledTransport(dispose = true)

    @Test fun networkLossStopsOwnedTransport() = exerciseStalledTransport(dispose = false, networkLost = true)

    @Test fun overdueCleanupWhileWorkerIsHeldFencesAdmissionUntilRealLateDrain() {
        val clock = Clock()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = Queue()
        passiveOwner(clock, queue) { stage ->
            if (stage == Stage.BEFORE_RECOVERY) {
                entered.countDown()
                await(release)
            }
        }.use { owner ->
            try {
                val ticket = requireNotNull(owner.request(1))
                assertTrue(owner.submitCode(ticket, code()))
                await(entered)
                clock.now = 60_000
                owner.onResume()
                assertEquals(DirectShellPairingReason.OPERATION_EXPIRED, owner.state.value.reason)
                await(clock.observation)
                val delayedCleanup = queue.only()
                clock.now += DirectShellPairingCoordinator.CLEANUP_MS
                owner.onResume()
                assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, owner.state.value.phase)
                assertFalse(owner.state.value.drained)
                assertNull(owner.request(1))
                release.countDown()
                awaitDrain(owner)
                assertEquals(DirectShellPairingPhase.EXPIRED, owner.state.value.phase)
                val next = requireNotNull(owner.request(1))
                delayedCleanup.run()
                assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
                assertNotEquals(ticket.nonce, next.nonce)
            } finally {
                release.countDown()
            }
        }
    }

    private fun exerciseStalledTransport(dispose: Boolean, networkLost: Boolean = false) {
        val clock = Clock()
        val queue = Queue()
        val base = temporary.newFolder().toPath()
        val calls = AtomicInteger()
        var socket: Socket? = null
        val identity = StorageFixture.identity
        val environment = AtomicReference(environment())
        OwnedPairingPeerFixture(OwnedPairingPeerFixture.Mode.TLS_STALL, identity).use { peer ->
            AndroidDirectShellPairingOwner(
                { pairingRequiredUiState(1) },
                environment::get,
                clock,
                queue,
            ) { c, scheduler ->
                DirectShellSyntheticPairingComposition(
                    c,
                    scheduler,
                    { DirectShellCredentialStorage(base, { StorageFixture.key }, ModeledDirectorySync()) },
                    { DirectShellSyntheticPairingComposition.IdentityBorrow(identity) { true } },
                    { peer.endpoint },
                    {
                        Socket().also {
                            socket = it
                            calls.incrementAndGet()
                        }
                    },
                )
            }.use { owner ->
                val ticket = requireNotNull(owner.request(1))
                assertTrue(owner.submitCode(ticket, code()))
                peer.await()
                assertFalse(owner.state.value.drained)
                if (dispose) {
                    owner.close()
                    owner.close()
                } else if (networkLost) {
                    environment.set(environment.get().copy(networkEpoch = 2))
                    owner.environmentChanged()
                    assertEquals(DirectShellPairingReason.NETWORK_CHANGED, owner.state.value.reason)
                } else {
                    clock.now = 60_000
                    owner.onResume()
                    owner.onResume()
                }
                awaitDrain(owner)
                assertTrue(requireNotNull(socket).isClosed)
                assertEquals(1, calls.get())
                assertEquals(DirectShellPairingExternalEffect.POSSIBLE, owner.state.value.externalEffect)
                assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, owner.state.value.publication)
                val namespace = base.resolve(DirectShellCredentialStorage.DIRECTORY)
                assertFalse(Files.exists(namespace.resolve(DirectShellCredentialStorage.RECORD)))
                assertNull(owner.request(1)) // Recovery/effect remains, even after physical drain.
                owner.close()
                assertTrue(owner.awaitObserverTermination())
                assertEquals(0, queue.size())
            }
        }
    }

    private fun passiveOwner(
        clock: Clock,
        queue: Queue,
        environment: () -> DirectShellPairingEnvironment = ::environment,
        readinessGeneration: () -> Long = { 1L },
        checkpoint: (Stage) -> Unit = {},
    ) = AndroidDirectShellPairingOwner(
        { pairingRequiredUiState(readinessGeneration()) },
        environment,
        clock,
        queue,
    ) { c, scheduler ->
        DirectShellSyntheticPairingComposition(
            c,
            scheduler,
            { throw AssertionError("unexpected storage") },
            { throw AssertionError("unexpected identity") },
            { throw AssertionError("unexpected endpoint") },
            checkpoint = checkpoint,
        )
    }

    private class Clock : DirectShellPairingClock {
        @Volatile var now = 0L
        val observation = CountDownLatch(1)
        override fun elapsedRealtimeMs(): Long {
            val value = now
            if (Thread.currentThread().name == "pairing-owner-drain") observation.countDown()
            return value
        }
    }

    private class Queue : DirectShellPairingTimerQueue {
        private val entries = mutableMapOf<Runnable, Long>()

        @Synchronized override fun post(task: Runnable, delayMs: Long) {
            entries[task] = delayMs
        }

        @Synchronized override fun remove(task: Runnable) {
            entries.remove(task)
        }

        @Synchronized fun only(): Runnable = entries.keys.single()

        @Synchronized fun size() = entries.size

        @Synchronized fun delay(task: Runnable) = entries.getValue(task)
    }

    private companion object {
        fun environment() = DirectShellPairingEnvironment(true, true, 1, 0)
        fun code() = "314159".toCharArray()
        fun await(latch: CountDownLatch) = check(latch.await(5, TimeUnit.SECONDS))
        fun awaitDrain(owner: AndroidDirectShellPairingOwner) {
            runBlocking { withTimeout(5_000) { owner.state.first { it.drained } } }
        }
    }
}
