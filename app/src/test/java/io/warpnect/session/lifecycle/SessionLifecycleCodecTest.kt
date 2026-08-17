package io.warpnect.session.lifecycle

import io.warpnect.session.ChannelId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.SessionGeneration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleCodecTest {
    @Test
    fun allV1MessagesRoundTripWithExactHeaderAndBoundedBodies() {
        messages().forEach { message ->
            val encoded = requireNotNull(SessionLifecycleCodec.encode(message))
            assertTrue(encoded.size <= SessionLifecycleProtocol.MAX_PAYLOAD_BYTES)
            assertArrayEquals(
                byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte()),
                encoded.copyOfRange(0, 4),
            )
            assertEquals(1, encoded[4].toInt() and 0xff)
            assertEquals(0, encoded[6].toInt() and 0xff)
            assertEquals(0, encoded[7].toInt() and 0xff)
            assertEquals(encoded.size - SessionLifecycleProtocol.HEADER_BYTES, readU16(encoded, 16))
            assertEquals(0, readU16(encoded, 18))
            val decoded = requireNotNull(SessionLifecycleCodec.decode(encoded))
            assertEquals(message.header.messageType, decoded.message.header.messageType)
            assertArrayEquals(encoded, decoded.bytes)
        }
    }

    @Test
    fun heartbeatAndDisconnectBodiesUseFrozenLengths() {
        val heartbeat = requireNotNull(SessionLifecycleCodec.encode(messages().first()))
        assertEquals(SessionLifecycleProtocol.HEADER_BYTES + 16, heartbeat.size)
        val disconnect =
            requireNotNull(
                SessionLifecycleCodec.encode(messages().first { it is SessionLifecycleMessage.DisconnectNotice }),
            )
        assertEquals(SessionLifecycleProtocol.HEADER_BYTES + 12, disconnect.size)
    }

    @Test
    fun migrationPlanHashBindsCanonicalHeadersAndPorts() {
        val prepare = messages().filterIsInstance<SessionLifecycleMessage.PathMigrationPrepare>().single()
        val ready = messages().filterIsInstance<SessionLifecycleMessage.PathMigrationReady>().single()
        val baseline = requireNotNull(SessionLifecycleCodec.migrationPlanHash(prepare, ready))
        val changed = ready.copy(
            entries = listOf(PathMigrationEntry(channel(1u), 4_445), PathMigrationEntry(channel(2u), 4_446)),
        )
        assertNotEquals(
            baseline.toList(),
            requireNotNull(SessionLifecycleCodec.migrationPlanHash(prepare, changed)).toList(),
        )
        val changedHeader = prepare.copy(header = header(SessionLifecycleMessageType.PathMigrationPrepare, 99u))
        assertNotEquals(
            baseline.toList(),
            requireNotNull(SessionLifecycleCodec.migrationPlanHash(changedHeader, ready)).toList(),
        )
    }

    @Test
    fun malformedHeaderAndMigrationEntriesAreRejected() {
        val encoded = requireNotNull(SessionLifecycleCodec.encode(messages().first()))
        val badMagic = encoded.copyOf().also { it[0] = 0 }
        val badFlags = encoded.copyOf().also { it[6] = 1 }
        val badLength = encoded.copyOf().also { it[17] = (it[17] + 1).toByte() }
        val zeroId = encoded.copyOf().also { index -> (8 until 16).forEach { index[it] = 0 } }
        assertNull(SessionLifecycleCodec.decode(badMagic))
        assertNull(SessionLifecycleCodec.decode(badFlags))
        assertNull(SessionLifecycleCodec.decode(badLength))
        assertNull(SessionLifecycleCodec.decode(zeroId))

        val duplicate = SessionLifecycleMessage.PathMigrationPrepare(
            header(SessionLifecycleMessageType.PathMigrationPrepare, 70u),
            migration(7u),
            path(2u),
            hash(),
            listOf(PathMigrationEntry(channel(1u), 4_444), PathMigrationEntry(channel(1u), 4_445)),
        )
        assertNull(SessionLifecycleCodec.encode(duplicate))
    }

    private fun messages(): List<SessionLifecycleMessage> {
        val challenge = ByteArray(16) { it.toByte() }
        val entries = listOf(PathMigrationEntry(channel(1u), 4_444), PathMigrationEntry(channel(2u), 4_445))
        return listOf(
            SessionLifecycleMessage.Heartbeat(
                header(SessionLifecycleMessageType.Heartbeat, 1u),
                heartbeat(1u),
                path(1u),
            ),
            SessionLifecycleMessage.HeartbeatAck(
                header(SessionLifecycleMessageType.HeartbeatAck, 2u),
                heartbeat(1u),
                path(1u),
            ),
            SessionLifecycleMessage.PathChallenge(
                header(SessionLifecycleMessageType.PathChallenge, 3u),
                migration(1u),
                path(2u),
                NetworkPathKind.Lan,
                challenge,
            ),
            SessionLifecycleMessage.PathResponse(
                header(SessionLifecycleMessageType.PathResponse, 4u),
                migration(1u),
                path(2u),
                NetworkPathKind.Lan,
                challenge,
            ),
            SessionLifecycleMessage.PathMigrationPrepare(
                header(SessionLifecycleMessageType.PathMigrationPrepare, 5u),
                migration(1u),
                path(2u),
                hash(),
                entries,
            ),
            SessionLifecycleMessage.PathMigrationReady(
                header(SessionLifecycleMessageType.PathMigrationReady, 6u),
                migration(1u),
                path(2u),
                hash(),
                entries,
            ),
            SessionLifecycleMessage.PathMigrationCommit(
                header(SessionLifecycleMessageType.PathMigrationCommit, 7u),
                migration(1u),
                path(2u),
                hash(),
            ),
            SessionLifecycleMessage.PathMigrationAck(
                header(SessionLifecycleMessageType.PathMigrationAck, 8u),
                migration(1u),
                path(2u),
                hash(),
            ),
            SessionLifecycleMessage.DisconnectNotice(
                header(SessionLifecycleMessageType.DisconnectNotice, 9u),
                DisconnectReason.UserRequested,
                SessionGeneration.Initial,
                path(1u),
            ),
            SessionLifecycleMessage.DisconnectAck(
                header(SessionLifecycleMessageType.DisconnectAck, 10u),
                DisconnectReason.UserRequested,
                SessionGeneration.Initial,
                path(1u),
            ),
        )
    }

    private fun header(type: SessionLifecycleMessageType, id: ULong): SessionLifecycleHeader =
        SessionLifecycleHeader(type, LifecycleMessageId.requireValid(id), 0)
    private fun heartbeat(value: ULong): HeartbeatId = HeartbeatId.requireValid(value)
    private fun migration(value: ULong): PathMigrationId = PathMigrationId.requireValid(value)
    private fun path(value: UInt): PathId = PathId.requireValid(value)
    private fun channel(value: UInt): ChannelId = ChannelId.requireValid(value)
    private fun hash(): ByteArray = ByteArray(32) { (it + 1).toByte() }
    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
