package io.warpnect.platform.privileged.directshell.pairing

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.PairingConnectionCtx
import io.warpnect.platform.privileged.directshell.pairing.DirectShellPairingTransport as Transport
import io.warpnect.platform.privileged.directshell.pairing.OwnedPairingPeerFixture as Peer
import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellOwnedPairingProtocolTest {
    private val ticket = DirectShellPairingTicket("owned-owner", "owned-nonce", 1, 1, 0)
    private val time = Time()
    private val operations = mutableListOf<Transport>()
    private val peers = mutableListOf<Peer>()
    private val unblock = mutableListOf<() -> Unit>()

    @After fun cleanup() {
        unblock.forEach { it() }
        operations.forEach { it.requestStop(ticket) }
        operations.forEach { assertTrue("actual transport drain", it.awaitDrain().drained) }
        peers.forEach { it.close() }
    }

    private fun peer(mode: Peer.Mode = Peer.Mode.SUCCESS) = Peer(mode).also { peers += it }
    private fun protocol(endpoint: InetSocketAddress, bind: Boolean = true) =
        DirectShellOwnedPairingProtocol(endpoint) {
            DirectShellOwnedPairingProtocol.Identity(
                StorageFixture.identity.privateKey,
                StorageFixture.identity.certificate,
            )
        }.also { if (bind) check(it.bindWork(ticket, work())) }
    private fun work(owner: DirectShellPairingTicket = ticket, deadline: Long = 61_000) =
        DirectShellPairingWorkContext(owner, deadline, time, time)
    private fun operation(
        endpoint: InetSocketAddress,
        protocol: Transport.Protocol = protocol(endpoint),
        work: DirectShellPairingWorkContext = work(),
        sockets: () -> Socket = { Socket() },
    ) = Transport(
        ticket,
        endpoint,
        work.deadlineElapsedRealtimeMs,
        work.clock,
        work.scheduler,
        { true },
        protocol,
        sockets,
    )
        .also { operations += it }
    private fun start(operation: Transport): Boolean =
        DirectShellPairingCode.take(Peer.CODE.toCharArray())!!.use { operation.start(it) }

    @Test fun a01PassiveFactoryRequiresOwnerContextWithoutIdentityOrNetwork() {
        val endpoint = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1)
        val calls = AtomicInteger()
        val protocol =
            DirectShellOwnedPairingProtocol(endpoint) {
                calls.incrementAndGet()
                error("unexpected_identity")
            }
        assertNull(DirectShellOwnedPairingProtocol.transport(ticket, null, endpoint, { true }, protocol))
        assertNull(
            DirectShellOwnedPairingProtocol.transport(
                ticket,
                work(
                    DirectShellPairingTicket("o", "n", 2, 1, 0),
                ),
                endpoint,
                {
                    true
                },
                protocol,
            ),
        )
        val idle = DirectShellOwnedPairingProtocol.transport(ticket, work(), endpoint, { true }, protocol)!!
        operations += idle
        assertEquals(Transport.Outcome.IDLE, idle.observe().outcome)
        assertEquals(0, calls.get())
        assertEquals(0, time.tasks.size)
    }

    @Test fun a02a03a09ActualPatchedContextUsesOneSocketClientCertificateAndRealRsaPeerInfo() {
        val peer = peer()
        val protocol = protocol(peer.endpoint)
        val sockets = mutableListOf<Socket>()
        val operation = operation(peer.endpoint, protocol, sockets = { Socket().also { sockets += it } })
        assertTrue(start(operation))
        val receipt = operation.awaitDrain()
        assertEquals(Transport.Outcome.PEER_INFO_VERIFIED, receipt.outcome)
        assertTrue(receipt.drained)
        assertEquals(1, sockets.size)
        assertTrue(sockets.single().isClosed)
        val evidence = protocol.verifiedPeer()!!
        assertEquals(peer.endpoint, evidence.endpoint)
        assertTrue(evidence.deviceGuid.contentEquals("fixture opaque guid".toByteArray()))
        val digest = MessageDigest.getInstance("SHA-256").digest(StorageFixture.identity.certificate.encoded)
        assertTrue(evidence.localCertificateSha256.contentEquals(digest))
        assertTrue(evidence.remoteCertificateSha256.contentEquals(digest))
        assertNotNull(peer.clientCertificate.get())
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, receipt.externalEffect)
        assertFalse(evidence.toString().contains("guid"))
        val guid = evidence.deviceGuid
        guid.fill(0)
        assertFalse(evidence.deviceGuid.contentEquals(guid))
    }

    @Test fun a04ActualContextTlsHandshakeIsInterruptedAndDrains() = cancelAt(Peer.Mode.TLS_STALL)

    @Test fun a05ActualContextPakeReceiveIsInterruptedAndDrains() = cancelAt(Peer.Mode.PAKE_STALL)

    @Test fun sameOwnedTransportInterruptsNativeWriteUnderRealTlsApplicationBackpressure() {
        val peer = peer(Peer.Mode.TLS_APPLICATION_BACKPRESSURE)
        val thread = AtomicReference<Thread>()
        val written = AtomicInteger()
        val connected = CountDownLatch(1)
        val socket = Socket().apply { sendBufferSize = 4_096 }
        val engine = object : Transport.Protocol {
            override fun exchange(socket: Socket, code: CharArray, checkActive: () -> Unit): Transport.Completion {
                thread.set(Thread.currentThread())
                val tls = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
                tls.connect(object : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {
                    override fun getProtocolVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
                    override fun getAuthentication() = object : TlsAuthentication {
                        override fun notifyServerCertificate(certificate: TlsServerCertificate) {
                            check(
                                StorageFixture.identity.certificate.encoded.contentEquals(
                                    certificate.certificate.getCertificateAt(0).encoded,
                                ),
                            )
                        }
                        override fun getClientCredentials(request: org.bouncycastle.tls.CertificateRequest) = null
                    }
                })
                connected.countDown()
                val chunk = ByteArray(4096)
                repeat(8_192) {
                    checkActive()
                    tls.outputStream.write(chunk)
                    written.addAndGet(chunk.size)
                }
                error("tls_test_backpressure_not_reached")
            }

            // This fixture borrows only the owned socket; it grants no pairing completion or peer evidence.
            override fun release() = socket.isClosed
        }
        val operation = operation(peer.endpoint, engine, sockets = { socket })
        assertTrue(start(operation))
        peer.await()
        assertTrue(connected.await(3, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        fun inNativeTlsWrite(): Boolean {
            val stack = thread.get().stackTrace
            val tlsRecord = stack.any {
                it.className.startsWith("org.bouncycastle.tls.") && it.methodName == "writeRecord"
            }
            val kernelWrite = stack.any {
                it.className.startsWith("sun.nio.ch.") && it.methodName.contains("write", ignoreCase = true)
            }
            return tlsRecord && kernelWrite
        }
        var blocked = false
        while (System.nanoTime() < deadline && !blocked) {
            val before = written.get()
            val inWrite = inNativeTlsWrite()
            Thread.sleep(80)
            blocked = before > 0 && written.get() == before && inWrite && inNativeTlsWrite()
        }
        assertTrue("live socket write beneath a TLS record, with no write progress", blocked)
        assertFalse(operation.awaitDrain(0).drained)
        assertTrue(operation.requestStop(ticket))
        val result = operation.awaitDrain()
        assertEquals(Transport.Outcome.CANCELLED, result.outcome)
        assertEquals(Transport.Completion.INCOMPLETE, result.completion)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, result.externalEffect)
        assertTrue(result.drained)
        assertTrue(socket.isClosed)
        assertEquals(1L, peer.proceed.count)
    }
    private fun cancelAt(mode: Peer.Mode) {
        val peer = peer(mode)
        val protocol = protocol(peer.endpoint)
        val operation = operation(peer.endpoint, protocol)
        start(operation)
        peer.await()
        operation.requestStop(ticket)
        val result = operation.awaitDrain()
        assertEquals(Transport.Outcome.CANCELLED, result.outcome)
        assertTrue(result.drained)
        assertNull(protocol.verifiedPeer())
    }

    @Test fun a07WrongCodeFailsWithoutAuthenticatedEvidence() = rejects(Peer.Mode.WRONG_CODE)

    @Test fun a08WrongVersion() = rejects(Peer.Mode.BAD_VERSION)

    @Test fun a08WrongType() = rejects(Peer.Mode.BAD_TYPE)

    @Test fun a08WrongLength() = rejects(Peer.Mode.BAD_SIZE)

    @Test fun a08WrongTag() = rejects(Peer.Mode.BAD_TAG)

    @Test fun a08WrongGuidType() = rejects(Peer.Mode.BAD_GUID_TYPE)

    @Test fun a08EmptyGuid() = rejects(Peer.Mode.EMPTY_GUID)

    @Test fun a08UnterminatedGuid() = rejects(Peer.Mode.UNTERMINATED_GUID)

    @Test fun a08UnexpectedEof() = rejects(Peer.Mode.EOF)
    private fun rejects(mode: Peer.Mode) {
        val peer = peer(mode)
        val protocol = protocol(peer.endpoint)
        val operation = operation(peer.endpoint, protocol)
        start(operation)
        val result = operation.awaitDrain()
        assertEquals(Transport.Outcome.FAILED, result.outcome)
        assertTrue(result.drained)
        assertNull(protocol.verifiedPeer())
        assertFalse(result.toString().contains(Peer.CODE))
    }

    @Test fun a08GuidIsNotRestrictedToAnInventedUuidOr255ByteSchema() {
        val peer = peer(Peer.Mode.LONG_GUID)
        val protocol = protocol(peer.endpoint)
        val operation = operation(peer.endpoint, protocol)
        start(operation)
        assertEquals(Transport.Outcome.PEER_INFO_VERIFIED, operation.awaitDrain().outcome)
        assertEquals(500, protocol.verifiedPeer()!!.deviceGuid.size)
    }

    @Test fun a10StopCanWinLifecycleWithoutErasingVerifiedProtocolKnowledge() = finishAfterProof(false)

    @Test fun a12LateVerifiedResultCannotTurnExpiredLifecycleIntoSuccess() = finishAfterProof(true)

    private fun finishAfterProof(expired: Boolean) {
        val peer = peer()
        val protocol = protocol(peer.endpoint)
        val verified = CountDownLatch(1)
        val release = CountDownLatch(1)
        unblock += { release.countDown() }
        val barrier = object : Transport.Protocol {
            override fun exchange(socket: Socket, code: CharArray, checkActive: () -> Unit): Transport.Completion {
                val result = protocol.exchange(socket, code, checkActive)
                verified.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return result
            }
            override fun release() = protocol.release()
        }
        val operation = operation(peer.endpoint, barrier)
        start(operation)
        assertTrue(verified.await(3, TimeUnit.SECONDS))
        if (expired) time.advance(61_000) else operation.requestStop(ticket)
        assertFalse(operation.awaitDrain(0).drained)
        release.countDown()
        val result = operation.awaitDrain()
        assertEquals(if (expired) Transport.Outcome.EXPIRED else Transport.Outcome.CANCELLED, result.outcome)
        assertEquals(Transport.Completion.PEER_INFO_VERIFIED, result.completion)
        assertNotNull(protocol.verifiedPeer())
    }

    @Test fun a11PartiallyConsumedContextDoesNotResetCutoff() {
        val peer = peer(Peer.Mode.PAKE_STALL)
        val work = work()
        time.now = 60_000
        val protocol = protocol(peer.endpoint, bind = false)
        val operation = DirectShellOwnedPairingProtocol.transport(ticket, work, peer.endpoint, { true }, protocol)!!
        operations += operation
        start(operation)
        peer.await()
        assertTrue(time.tasks.any { it.first == 61_000L })
        time.advance(61_000)
        assertEquals(Transport.Outcome.EXPIRED, operation.awaitDrain().outcome)
    }

    @Test fun a12DeadlineStopsActualTls() = expire(Peer.Mode.TLS_STALL)

    @Test fun a12DeadlineStopsActualPake() = expire(Peer.Mode.PAKE_STALL)

    @Test fun a12PartialResponseCannotRenewTotalDeadline() = expire(Peer.Mode.TRICKLE)
    private fun expire(mode: Peer.Mode) {
        val peer = peer(mode)
        val operation = operation(peer.endpoint)
        start(operation)
        peer.await(if (mode == Peer.Mode.TRICKLE) peer.peerInfo else peer.boundary)
        time.advance(61_000)
        val result = operation.awaitDrain()
        assertEquals(Transport.Outcome.EXPIRED, result.outcome)
        assertTrue(result.drained)
    }

    @Test fun a12AlreadyExpiredWorkAndPreStartStopCannotCreateSocket() {
        val endpoint = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1)
        val calls = AtomicInteger()
        val expired =
            operation(endpoint, work = work(deadline = 1_000), sockets = {
                calls.incrementAndGet()
                Socket()
            })
        assertFalse(start(expired))
        val stopped = operation(endpoint, sockets = {
            calls.incrementAndGet()
            Socket()
        })
        stopped.requestStop(ticket)
        assertFalse(start(stopped))
        assertEquals(0, calls.get())
    }

    @Test fun a13RepeatedStopAndOldTicketCannotRebindProtocol() {
        val peer = peer(Peer.Mode.PAKE_STALL)
        val operation = operation(peer.endpoint)
        start(operation)
        peer.await()
        assertFalse(operation.requestStop(DirectShellPairingTicket("owner", "nonce", 2, 1, 0)))
        repeat(5) { operation.requestStop(ticket) }
        val result = operation.awaitDrain()
        assertSame(ticket, result.ticket)
        assertEquals(Transport.Outcome.CANCELLED, result.outcome)
        assertTrue(result.drained)
    }

    @Test fun a14CleanupObservationIsNotWorkerTermination() {
        val peer = peer()
        val protocol = protocol(peer.endpoint)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        unblock += { release.countDown() }
        val barrier = object : Transport.Protocol {
            override fun exchange(socket: Socket, code: CharArray, checkActive: () -> Unit) =
                protocol.exchange(socket, code, checkActive)
            override fun release(): Boolean {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return protocol.release()
            }
        }
        val operation = operation(peer.endpoint, barrier)
        start(operation)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertFalse(operation.awaitDrain(0).drained)
        assertEquals(Transport.Outcome.DRAIN_PENDING, operation.observe().outcome)
        release.countDown()
        assertTrue(operation.awaitDrain().drained)
    }

    @Test fun a15CancelAfterClientPeerInfoCannotClaimNoRemoteEffect() {
        val peer = peer(Peer.Mode.HOLD_PEER_REPLY)
        val operation = operation(peer.endpoint)
        start(operation)
        peer.await(peer.peerInfo)
        operation.requestStop(ticket)
        val result = operation.awaitDrain()
        assertEquals(Transport.Outcome.CANCELLED, result.outcome)
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, result.externalEffect)
    }

    @Test fun a17ReleasedCodeFailsBeforeAnySocketOrIdentity() {
        val endpoint = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1)
        val operation = operation(endpoint, sockets = { error("unexpected_socket") })
        val code = DirectShellPairingCode.take(Peer.CODE.toCharArray())!!
        code.close()
        assertFalse(operation.start(code))
        assertEquals(Transport.Outcome.FAILED, operation.observe().outcome)
    }

    @Test fun a14ActualP1AwareDispatchRetainsFenceUntilActualTransportDrain() {
        val peer = peer(Peer.Mode.PAKE_STALL)
        var transport: Transport? = null
        var callback: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        var observedWork: DirectShellPairingWorkContext? = null
        val owner = DirectShellPairingCoordinator(
            { pairingRequiredUiState() },
            { DirectShellPairingEnvironment(true, true, 1, 0) },
            time,
            time,
            { actualTicket, observer ->
                object : DeadlineAwarePairingOperation {
                    override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) {
                        observedWork = work
                        transport = DirectShellOwnedPairingProtocol.transport(
                            actualTicket, work, peer.endpoint,
                            { observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED) },
                            protocol(peer.endpoint, bind = false),
                        )!!.also { check(it.start(code)) }
                    }
                    override fun cancel() {
                        transport?.requestStop(actualTicket)
                    }
                    override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                        callback = drained
                    }
                }
            },
            { _, _, _ -> error("unexpected_store") },
            "test-owner",
            { "test-nonce" },
        )
        val actualTicket = owner.request(1)!!
        try {
            assertTrue(owner.submitCode(actualTicket, Peer.CODE.toCharArray()))
            peer.await()
            assertSame(actualTicket, observedWork!!.ticket)
            assertEquals(61_000, observedWork!!.deadlineElapsedRealtimeMs)
            owner.cancel(actualTicket)
            time.advance(4_000)
            assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, owner.state.value.phase)
            assertNull(owner.request(1))
            val result = transport!!.awaitDrain()
            assertTrue(result.drained)
            callback!!(
                DirectShellPairingDrainReceipt(
                    result.externalEffect,
                    DirectShellPairingRecovery.RETAINED_UNCONFIRMED,
                    transportDrained = result.drained,
                ),
            )
            assertNull(owner.request(1))
        } finally {
            owner.close()
            transport?.requestStop(actualTicket)
            assertTrue(transport!!.awaitDrain().drained)
        }
    }

    @Test fun a16PriorOwnerContextCannotDispatchANewTicket() {
        val peer = peer()
        val newer = DirectShellPairingTicket("new-owner", "new-nonce", 2, 1, 0)
        assertNull(
            DirectShellOwnedPairingProtocol.transport(newer, work(), peer.endpoint, { true }, protocol(peer.endpoint)),
        )
        val protocol = protocol(peer.endpoint)
        val operation = operation(peer.endpoint, protocol)
        start(operation)
        assertTrue(operation.awaitDrain().drained)
        assertSame(ticket, operation.observe().ticket)
        assertNotNull(protocol.verifiedPeer())
    }

    @Test fun a20ConnectedEntryRejectsUnconnectedOrWrongEndpointWithoutIo() {
        Socket().use { socket ->
            val identity = StorageFixture.identity
            val context =
                PairingConnectionCtx(
                    "127.0.0.1",
                    1,
                    byteArrayOf(1),
                    identity.privateKey,
                    identity.certificate,
                    "fixture",
                )
            assertThrows(java.io.IOException::class.java) {
                context.startConnected(
                    socket,
                    InetSocketAddress(socket.inetAddress ?: InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1),
                ) {
                    error("checkpoint_not_expected")
                }
            }
            context.close()
        }
    }

    @Test fun a01UnboundProtocolFailsBeforeLoadingIdentityOrDoingIo() {
        val endpoint = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1)
        val protocol = DirectShellOwnedPairingProtocol(endpoint) { error("unexpected_identity") }
        Socket().use { socket ->
            assertThrows(IllegalStateException::class.java) {
                protocol.exchange(socket, Peer.CODE.toCharArray()) {}
            }
        }
        assertTrue(protocol.bindWork(ticket, work()))
        assertFalse(protocol.bindWork(ticket, work()))
        assertTrue(protocol.release())
    }

    @Test fun a18LegacyManagerAndNonPairingPublicApisRemainAvailable() {
        assertNotNull(PairingConnectionCtx::class.java.getMethod("start"))
        assertNotNull(PairingConnectionCtx::class.java.getMethod("close"))
        assertNotNull(
            AbsAdbConnectionManager::class.java.getMethod(
                "pair",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
            ),
        )
        assertNotNull(
            AbsAdbConnectionManager::class.java.getMethod("connect", String::class.java, Int::class.javaPrimitiveType),
        )
        assertNotNull(AbsAdbConnectionManager::class.java.getMethod("openStream", String::class.java))
    }

    private class Time : DirectShellPairingClock, DirectShellPairingScheduler {
        @Volatile var now = 1_000L
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun elapsedRealtimeMs() = now
        override fun at(deadlineMs: Long, action: () -> Unit): DirectShellPairingTimer {
            val task = deadlineMs to action
            synchronized(tasks) { tasks += task }
            return DirectShellPairingTimer { synchronized(tasks) { tasks.remove(task) } }
        }
        fun advance(value: Long) {
            now = value
            synchronized(tasks) { tasks.filter { it.first <= now } }.forEach { it.second() }
        }
    }
}
