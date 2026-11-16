package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellPairingTransport as Transport
import io.warpnect.platform.privileged.directshell.pairing.PairingTransportFixture as Peer
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellPairingTransportTest {
    private val ticket = DirectShellPairingTicket("owner-canary", "nonce-canary", 1, 1, 0)
    private val time = ManualTime()
    private val operations = mutableListOf<Transport>()
    private val peers = mutableListOf<Peer>()
    private val after = mutableListOf<() -> Unit>()
    private val unknownAllowed = mutableSetOf<Transport>()

    @After fun cleanup() {
        after.forEach { it() }
        operations.forEach { it.requestStop(ticket) }
        operations.forEach {
            val receipt = it.awaitDrain()
            assertTrue("actual IO worker termination required", receipt.ioWorkerTerminated)
            assertTrue("actual close worker termination required", receipt.closeWorkerTerminated)
            assertTrue(receipt.codeReleased)
            if (it !in unknownAllowed) assertTrue("physical drain required", receipt.drained)
        }
        peers.forEach { it.close() }
    }

    private fun peer(scenario: Peer.Scenario) = Peer(scenario).also { peers += it }
    private fun operation(
        endpoint: InetSocketAddress = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1),
        protocol: Transport.Protocol = SimpleProtocol(),
        recovery: () -> Boolean = { true },
        sockets: () -> Socket = { Socket() },
        connector: (Socket, InetSocketAddress, Int) -> Unit = { s, a, t -> s.connect(a, t) },
        owner: DirectShellPairingTicket = ticket,
    ) = Transport(owner, endpoint, 61_000, time, time, recovery, protocol, sockets, connector).also { operations += it }

    private fun start(op: Transport): Boolean {
        val input = Peer.CODE.toCharArray()
        val code = requireNotNull(DirectShellPairingCode.take(input))
        assertTrue(input.all { it == '\u0000' })
        return code.use { op.start(it) }
    }

    @Test fun t01ConstructionInspectionAndIdleP1HaveNoNetwork() {
        val creates = AtomicInteger()
        val op = operation(sockets = {
            creates.incrementAndGet()
            Socket()
        })
        repeat(3) {
            assertEquals(Transport.Outcome.IDLE, op.observe().outcome)
            assertTrue(op.observe().drained)
        }
        assertEquals(0, creates.get())
        assertEquals(0, time.tasks.size)
        val owner = owner { _, _ -> error("passive_backend_called") }
        assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
        owner.close()
    }

    @Test fun t02CancelBeforeStartCreatesNoSocketOrWorker() {
        val creates = AtomicInteger()
        val op = operation(sockets = {
            creates.incrementAndGet()
            Socket()
        })
        assertTrue(op.requestStop(ticket))
        assertFalse(start(op))
        assertEquals(0, creates.get())
        assertEquals(Transport.Outcome.CANCELLED, op.observe().outcome)
        assertEquals(DirectShellPairingExternalEffect.NONE_POSSIBLE, op.observe().externalEffect)
        assertTrue(op.observe().drained)
    }

    @Test fun t03ActualRefusedLoopbackConnectFailsOnceAndDrains() {
        val port = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        val creates = AtomicInteger()
        val op = operation(
            InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port),
            sockets = {
                creates.incrementAndGet()
                Socket()
            },
        )
        assertTrue(start(op))
        val r = op.awaitDrain()
        assertTrue(r.drained)
        assertEquals(Transport.Outcome.CONNECT_FAILED, r.outcome)
        assertEquals(DirectShellPairingExternalEffect.NONE_POSSIBLE, r.externalEffect)
        assertEquals(1, creates.get())
    }

    @Test fun t02AlreadyReleasedCodeFailsClosedWithoutStartingWork() {
        val created = AtomicInteger()
        val op = operation(sockets = {
            created.incrementAndGet()
            Socket()
        })
        val code = requireNotNull(DirectShellPairingCode.take(Peer.CODE.toCharArray()))
        code.close()
        assertFalse(op.start(code))
        assertEquals(0, created.get())
        assertEquals(Transport.Outcome.FAILED, op.observe().outcome)
        assertTrue(op.observe().drained)
    }

    @Test fun t04ControlledConnectAdmissionIsClosedNotClaimedAsRealBlackhole() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val op = operation(
            sockets = {
                object : Socket() {
                    override fun close() {
                        super.close()
                        closed.countDown()
                    }
                }
            },
            connector = { _, _, timeout ->
                assertEquals(60_000, timeout)
                entered.countDown()
                check(closed.await(3, TimeUnit.SECONDS))
                throw IOException("synthetic_connect_stop")
            },
        )
        assertTrue(start(op))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        op.requestStop(ticket)
        val r = op.awaitDrain()
        assertTrue(r.drained)
        assertEquals(Transport.Outcome.CANCELLED, r.outcome)
        assertEquals(DirectShellPairingExternalEffect.NONE_POSSIBLE, r.externalEffect)
    }

    @Test fun t05ActualStalledTlsHandshakeClosesAndJoinsBothWorkers() {
        val server = peer(Peer.Scenario.TLS_STALL)
        val op = operation(server.endpoint, Peer.Client())
        assertTrue(start(op))
        server.awaitBoundary()
        assertFalse(op.observe().ioWorkerTerminated)
        op.requestStop(ticket)
        val r = op.awaitDrain()
        assertTrue(r.drained)
        assertEquals(Transport.Outcome.CANCELLED, r.outcome)
        assertEquals(Transport.SocketClosure.CLOSED, r.socket)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, r.externalEffect)
    }

    @Test fun t06RealTlsAndPinnedPakeMessageReachAStalledRead() {
        val server = peer(Peer.Scenario.PAKE_STALL)
        val op = operation(server.endpoint, Peer.Client())
        assertTrue(start(op))
        server.awaitBoundary()
        op.requestStop(ticket)
        assertTrue(op.awaitDrain().drained)
        assertEquals(Transport.Completion.INCOMPLETE, op.observe().completion)
    }

    @Test fun t07ActualSocketWriteBackpressureIsInterruptedByOwnedClose() {
        val listener = ServerSocket().apply {
            receiveBufferSize = 1024
            bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 1)
        }
        val accepted = AtomicReference<Socket>()
        val released = CountDownLatch(1)
        val peer = Thread {
            listener.accept().use {
                accepted.set(it)
                check(released.await(5, TimeUnit.SECONDS))
            }
        }.also { it.start() }
        after += {
            released.countDown()
            accepted.get()?.close()
            listener.close()
            peer.join(3_000)
            assertFalse(peer.isAlive)
        }
        val writing = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val payload = ByteArray(8 * 1024 * 1024)
        val op = operation(
            InetSocketAddress(listener.inetAddress, listener.localPort),
            SimpleProtocol { socket, _, checkActive ->
                checkActive()
                writing.set(Thread.currentThread())
                entered.countDown()
                socket.getOutputStream().write(payload)
                error("fixture_write_did_not_block")
            },
            sockets = { Socket().apply { sendBufferSize = 1024 } },
        )
        assertTrue(start(op))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var blocked = false
        while (!blocked && System.nanoTime() < limit) {
            blocked = writing.get().stackTrace.any {
                (it.className.contains("SocketDispatcher") && it.methodName.startsWith("write")) ||
                    (it.className == "sun.nio.ch.Net" && it.methodName == "poll")
            }
            Thread.yield()
        }
        assertTrue("positive native write/poll stack required, not a sleep", blocked)
        assertFalse(op.observe().ioWorkerTerminated)
        op.requestStop(ticket)
        assertTrue(op.awaitDrain().drained)
        assertEquals(Transport.Outcome.CANCELLED, op.observe().outcome)
    }

    @Test fun t08PartialTlsResponseCannotResetTheAbsoluteDeadline() {
        val server = peer(Peer.Scenario.TRICKLE)
        val op = operation(server.endpoint, Peer.Client())
        assertTrue(start(op))
        server.awaitBoundary()
        assertTrue(server.partial.await(3, TimeUnit.SECONDS))
        time.advance(61_000)
        assertTrue(op.awaitDrain().drained)
        assertEquals(Transport.Outcome.EXPIRED, op.observe().outcome)
        server.continuePartial.countDown()
    }

    @Test fun t09CancelBeforeReturnPreservesVerifiedSyntheticProtocolKnowledge() {
        val returned = CountDownLatch(1)
        val allow = CountDownLatch(1)
        after += { allow.countDown() }
        val server = peer(Peer.Scenario.SUCCESS)
        val op =
            operation(
                server.endpoint,
                Peer.Client {
                    returned.countDown()
                    check(allow.await(3, TimeUnit.SECONDS))
                },
            )
        assertTrue(start(op))
        assertTrue(returned.await(3, TimeUnit.SECONDS))
        op.requestStop(ticket)
        allow.countDown()
        val r = op.awaitDrain()
        assertTrue(r.drained)
        assertEquals(Transport.Outcome.CANCELLED, r.outcome)
        assertEquals(Transport.Completion.FIXTURE_VERIFIED, r.completion)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, r.externalEffect)
    }

    @Test fun t10RepeatedStopHasOnePhysicalCloseAndOneWorker() {
        val server = peer(Peer.Scenario.TLS_STALL)
        val transitions = AtomicInteger()
        val ownerCalls = AtomicInteger()
        val observations = java.util.Collections.synchronizedList(mutableListOf<String>())
        val op = operation(server.endpoint, Peer.Client(), sockets = {
            object : Socket() {
                @Synchronized override fun close() {
                    check(observations.size < 8)
                    val ownedCall = Thread.currentThread().stackTrace.any {
                        it.className == Transport::class.java.name && it.methodName == "closeSocket"
                    }
                    observations += "closed_before=$isClosed owner_call=$ownedCall"
                    if (ownedCall) ownerCalls.incrementAndGet()
                    val wasClosed = isClosed
                    super.close()
                    if (!wasClosed && isClosed) transitions.incrementAndGet()
                }
            }
        })
        assertTrue(start(op))
        server.awaitBoundary()
        repeat(10) { assertTrue(op.requestStop(ticket)) }
        assertFalse(start(op))
        assertTrue(op.awaitDrain().drained)
        println("TRANSPORT_CLOSE_OBSERVER $observations")
        assertEquals(1, ownerCalls.get())
        assertEquals(1, transitions.get())
    }

    @Test fun t02CancellationWhileFactoryIsPendingClosesItsLateSocketWithoutConnect() {
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(1)
        val connects = AtomicInteger()
        after += { allow.countDown() }
        val op = operation(
            sockets = {
                entered.countDown()
                check(allow.await(3, TimeUnit.SECONDS))
                Socket()
            },
            connector = { _, _, _ -> connects.incrementAndGet() },
        )
        assertTrue(start(op))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        op.requestStop(ticket)
        assertFalse(op.awaitDrain(0).drained)
        assertFalse(start(op))
        allow.countDown()
        assertTrue(op.awaitDrain().drained)
        assertEquals(0, connects.get())
        assertEquals(Transport.Outcome.CANCELLED, op.observe().outcome)
    }

    @Test fun t12CloseInProgressCannotBeAcknowledgedAsPhysicalDrain() {
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(1)
        after += { allow.countDown() }
        val op = operation(sockets = {
            object : Socket() {
                override fun close() {
                    entered.countDown()
                    check(allow.await(3, TimeUnit.SECONDS))
                    super.close()
                }
            }
        }, connector = { _, _, _ -> })
        assertTrue(start(op))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        op.requestStop(ticket)
        val pending = op.awaitDrain(0)
        assertFalse(pending.drained)
        assertEquals(Transport.Outcome.DRAIN_PENDING, pending.outcome)
        assertFalse(start(op))
        allow.countDown()
        assertTrue(op.awaitDrain().drained)
    }

    @Test fun t12UnconfirmedProtocolReleaseKeepsTheOwnershipFence() {
        val op = operation(
            protocol = object : SimpleProtocol() {
                override fun release() = false
            },
            connector = { _, _, _ -> },
        )
        unknownAllowed += op
        assertTrue(start(op))
        val receipt = op.awaitDrain()
        assertTrue(receipt.ioWorkerTerminated && receipt.closeWorkerTerminated && receipt.codeReleased)
        assertFalse(receipt.protocolReleased)
        assertFalse(receipt.drained)
        assertEquals(Transport.Outcome.CLEANUP_UNCONFIRMED, receipt.outcome)
        assertFalse(start(op))
    }

    @Test fun t18WorkerOwnedCodeArrayIsActuallyWipedAtDrain() {
        val borrowed = AtomicReference<CharArray>()
        val op = operation(
            protocol = SimpleProtocol { _, code, _ ->
                borrowed.set(code)
                Transport.Completion.REJECTED
            },
            connector = { _, _, _ -> },
        )
        assertTrue(start(op))
        assertTrue(op.awaitDrain().drained)
        assertTrue(borrowed.get().all { it == '\u0000' })
    }

    @Test fun t16AlreadyExpiredWorkCannotDispatchAWorkerOrSocket() {
        val creates = AtomicInteger()
        val op = operation(sockets = {
            creates.incrementAndGet()
            Socket()
        })
        time.advance(61_000)
        assertFalse(start(op))
        assertEquals(0, creates.get())
        assertTrue(op.observe().drained)
        assertEquals(Transport.Outcome.EXPIRED, op.observe().outcome)
    }

    @Test fun t11StopAfterCompletionCannotRewriteSettledOutcome() {
        val server = peer(Peer.Scenario.SUCCESS)
        val op = operation(server.endpoint, Peer.Client())
        assertTrue(start(op))
        val before = op.awaitDrain()
        assertTrue(before.drained)
        assertEquals(Transport.Outcome.FIXTURE_VERIFIED, before.outcome)
        op.requestStop(ticket)
        time.advance(70_000)
        assertEquals(before, op.observe())
    }

    @Test fun t12ProtocolCleanupBarrierIsNotWorkerTermination() {
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(1)
        after += { allow.countDown() }
        val server = peer(Peer.Scenario.SUCCESS)
        val delegate = Peer.Client()
        val op = operation(
            server.endpoint,
            object : Transport.Protocol by delegate {
                override fun release(): Boolean {
                    entered.countDown()
                    check(allow.await(3, TimeUnit.SECONDS))
                    return delegate.release()
                }
            },
        )
        assertTrue(start(op))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        op.requestStop(ticket)
        assertFalse(op.awaitDrain(0).drained)
        assertFalse(op.observe().ioWorkerTerminated)
        assertEquals(Transport.Outcome.DRAIN_PENDING, op.observe().outcome)
        assertFalse(start(op))
        allow.countDown()
        assertTrue(op.awaitDrain().drained)
    }

    @Test fun t12CloseErrorRetainsUnknownEvenWhenPublicSocketFlagIsClosed() {
        val server = peer(Peer.Scenario.SUCCESS)
        val op = operation(server.endpoint, Peer.Client(), sockets = {
            object : Socket() {
                override fun close() {
                    super.close()
                    throw IOException("secret_exception_canary_314159")
                }
            }
        })
        unknownAllowed += op
        assertTrue(start(op))
        val r = op.awaitDrain()
        assertFalse(r.drained)
        assertTrue(r.ioWorkerTerminated && r.closeWorkerTerminated)
        assertEquals(Transport.SocketClosure.UNKNOWN, r.socket)
        assertEquals(Transport.Outcome.CLEANUP_UNCONFIRMED, r.outcome)
        assertFalse(r.toString().contains("secret_exception"))
    }

    @Test fun t13ForeignTicketCannotStopOrRebindAnOwnedTransport() {
        val server = peer(Peer.Scenario.PAKE_STALL)
        val op = operation(server.endpoint, Peer.Client())
        assertTrue(start(op))
        server.awaitBoundary()
        val other = DirectShellPairingTicket("owner-canary", "new-nonce", 2, 1, 0)
        assertFalse(op.requestStop(other))
        assertSame(ticket, op.observe().ticket)
        assertEquals(Transport.Outcome.RUNNING, op.observe().outcome)
        op.requestStop(ticket)
        assertTrue(op.awaitDrain().drained)
        assertSame(ticket, op.observe().ticket)
    }

    @Test fun t14RecoveryGateIsBeforeAnyTlsOrPakeBytes() {
        val listener = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val bytes = AtomicInteger(-2)
        val peer = Thread { listener.accept().use { bytes.set(it.getInputStream().read()) } }.also { it.start() }
        after += {
            listener.close()
            peer.join(3_000)
            assertFalse(peer.isAlive)
        }
        val calls = AtomicInteger()
        val op = operation(InetSocketAddress(listener.inetAddress, listener.localPort), recovery = {
            calls.incrementAndGet()
            false // Explicitly simulated missing durable preparation, not Android storage evidence.
        })
        assertTrue(start(op))
        val r = op.awaitDrain()
        peer.join(3_000)
        assertTrue(r.drained)
        assertEquals(1, calls.get())
        assertEquals(-1, bytes.get())
        assertEquals(Transport.Outcome.RECOVERY_UNCONFIRMED, r.outcome)
        assertEquals(DirectShellPairingExternalEffect.NONE_POSSIBLE, r.externalEffect)
    }

    @Test fun t15WrongCodeFailsClosedWithNoAuthenticatedGrant() = rejects(Peer.Scenario.WRONG_CODE)

    @Test fun t15WrongPeerTypeFailsClosed() = rejects(Peer.Scenario.WRONG_TYPE)

    @Test fun t15MalformedVersionFailsBeforePayloadAllocation() = rejects(Peer.Scenario.BAD_VERSION)

    @Test fun t15OversizedFrameFailsBeforePayloadAllocation() = rejects(Peer.Scenario.BAD_SIZE)

    @Test fun t15UnexpectedCloseIsNotAuthentication() = rejects(Peer.Scenario.EARLY_CLOSE)

    @Test fun t15TamperedAuthenticationTagIsRejected() = rejects(Peer.Scenario.BAD_TAG)

    @Test fun t15EmptyGuidIsRejected() = rejects(Peer.Scenario.EMPTY_GUID)

    @Test fun t15UnterminatedGuidIsRejected() = rejects(Peer.Scenario.UNTERMINATED_GUID)

    private fun rejects(scenario: Peer.Scenario) {
        val server = peer(scenario)
        val op = operation(server.endpoint, Peer.Client())
        assertTrue(start(op))
        val r = op.awaitDrain()
        assertTrue(r.drained)
        assertEquals(Transport.Outcome.FAILED, r.outcome)
        assertFalse(r.completion == Transport.Completion.FIXTURE_VERIFIED)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, r.externalEffect)
    }

    @Test fun t16ClockAdvanceBetweenConnectAndProtocolCannotExtendWork() {
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(1)
        after += { allow.countDown() }
        val op = operation(connector = { _, _, _ ->
            time.now.set(60_999)
            entered.countDown()
            check(allow.await(3, TimeUnit.SECONDS))
        })
        assertTrue(start(op))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(61_000L), time.tasks.map { it.at })
        time.advance(61_000)
        allow.countDown()
        assertEquals(Transport.Outcome.EXPIRED, op.awaitDrain().outcome)
        assertEquals(Transport.Completion.NOT_ENTERED, op.observe().completion)
    }

    @Test fun t17ActualP1CleanupDeadlineCannotBypassUndrainedTransport() {
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        after += { release.countDown() }
        lateinit var op: Transport
        var drain: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        val owner = owner { origin, observer ->
            op = operation(
                owner = origin,
                protocol = object : SimpleProtocol() {
                    override fun release(): Boolean {
                        blocked.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        return true
                    }
                },
                connector = { _, _, _ -> },
            )
            object : DirectShellPairingOperation {
                override fun start(code: DirectShellPairingCode) {
                    assertTrue(observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
                    assertTrue(op.start(code))
                }
                override fun cancel() {
                    op.requestStop(origin)
                }
                override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                    drain = drained
                }
            }
        }
        val origin = requireNotNull(owner.request(1))
        assertTrue(owner.submitCode(origin, Peer.CODE.toCharArray()))
        assertTrue(blocked.await(3, TimeUnit.SECONDS))
        owner.cancel(origin)
        time.advance(4_000)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, owner.state.value.phase)
        assertNull(owner.request(1))
        assertFalse(op.observe().drained)
        release.countDown()
        assertTrue(op.awaitDrain().drained)
        drain!!(
            DirectShellPairingDrainReceipt(
                op.observe().externalEffect,
                DirectShellPairingRecovery.RETAINED_UNCONFIRMED,
                transportDrained = op.observe().drained,
            ),
        )
        assertNull(owner.request(1)) // Retained recovery still fences replacement after actual D.
        owner.close()
    }

    @Test fun t18ReceiptsAndThrownDetailsExposeNoCodeKeyPayloadOrNonce() {
        val op =
            operation(
                protocol = SimpleProtocol { _, _, _ -> error("payload_private_key_314159") },
                connector = { _, _, _ -> },
            )
        assertTrue(start(op))
        val r = op.awaitDrain()
        assertTrue(r.drained)
        val rendered = r.toString()
        for (canary in listOf(Peer.CODE, "owner-canary", "nonce-canary", "payload_private_key", "java.lang")) {
            assertFalse(rendered.contains(canary))
        }
        assertFalse(r.externalEffect == DirectShellPairingExternalEffect.AUTHENTICATED)
    }

    @Test fun endpointsMustBePreResolvedExactLoopbackWithoutDns() {
        for (address in listOf(
            InetSocketAddress.createUnresolved("localhost", 1234),
            InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 1234),
            InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)), 1234),
        )) {
            var rejected = false
            try {
                operation(address)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        }
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        assertTrue(operation(InetSocketAddress(ipv6, 1234)).observe().drained)
    }

    private fun owner(create: (DirectShellPairingTicket, DirectShellPairingObserver) -> DirectShellPairingOperation) =
        DirectShellPairingCoordinator(
            { readiness() },
            { DirectShellPairingEnvironment(true, true, 1, 0) },
            time,
            time,
            DirectShellPairingBackend { origin, observer -> create(origin, observer) },
            DirectShellPairingCommitter { _, _, _ -> error("transport_cannot_commit") },
            "fixture-owner",
            { "fixture-nonce" },
        )

    private fun readiness(): HostReadinessUiState {
        val runtime = PrivilegedRuntimeStateSnapshot(
            1,
            1,
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            ),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_SHELL,
                PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
                PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED,
            ),
            PrivilegedRuntimeAvailability(PrivilegedRuntimeKind.SHIZUKU, PrivilegedRuntimeAvailabilityState.UNKNOWN),
        )
        return HostReadinessUiState(
            HostReadinessState.AuthorizationRequired(1, runtime, PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED),
            hostActionVisible = true,
        )
    }

    private open class SimpleProtocol(
        private val action: (
            Socket,
            CharArray,
            () -> Unit,
        ) -> Transport.Completion = { _, _, _ -> Transport.Completion.REJECTED },
    ) : Transport.Protocol {
        override fun exchange(socket: Socket, code: CharArray, checkActive: () -> Unit) =
            action(socket, code, checkActive)
        override fun release() = true
    }

    private class ManualTime : DirectShellPairingClock, DirectShellPairingScheduler {
        val now = AtomicLong(1_000)
        val tasks = mutableListOf<Task>()
        override fun elapsedRealtimeMs() = now.get()
        override fun at(deadlineMs: Long, action: () -> Unit): DirectShellPairingTimer = synchronized(tasks) {
            Task(deadlineMs, action).also { tasks += it }
        }
        fun advance(value: Long) {
            now.set(value)
            synchronized(tasks) { tasks.filter { !it.cancelled && it.at <= value }.toList() }.forEach {
                it.cancelled = true
                it.action()
            }
        }
        class Task(val at: Long, val action: () -> Unit) : DirectShellPairingTimer {
            @Volatile var cancelled = false
            override fun cancel() {
                cancelled = true
            }
        }
    }
}
