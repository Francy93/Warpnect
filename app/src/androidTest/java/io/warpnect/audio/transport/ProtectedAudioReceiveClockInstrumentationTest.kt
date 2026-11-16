package io.warpnect.audio.transport

import android.os.SystemClock
import android.util.Log
import io.warpnect.NativeBridge
import io.warpnect.audio.capture.AudioCaptureSource
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedAudioReceiveClockInstrumentationTest {
    @Test
    fun authenticatedAudioAdvancesLivenessInAndroidElapsedRealtimeDomain() {
        val senderEndpoint = NativeBridge.preparedUdpEndpointCreate("127.0.0.1")
        val receiverEndpoint = NativeBridge.preparedUdpEndpointCreate("127.0.0.1")
        assertEquals(0L, senderEndpoint[1])
        assertEquals(0L, receiverEndpoint[1])
        val senderPort = senderEndpoint[2].toInt()
        val receiverPort = receiverEndpoint[2].toInt()
        val client = protection(1, receiverPort)
        val host = protection(2, senderPort)
        var sender = 0L
        var receiver = 0L
        try {
            assertEquals(0L, NativeBridge.sessionProtectionCreateContext(client, 1, CHANNEL, LOOPBACK, receiverPort)[0])
            assertEquals(0L, NativeBridge.sessionProtectionCreateContext(host, 1, CHANNEL, LOOPBACK, senderPort)[0])
            receiver = NativeBridge.audioReceiverCreate(
                "127.0.0.1", receiverPort, "127.0.0.1", senderPort, true,
                1200, 4096, 4, 4, 20_000L, AudioCaptureSource.MicrophoneAudio.ordinal,
                host, CHANNEL, receiverEndpoint[0],
            )
            sender = NativeBridge.audioTransportCreate(
                "127.0.0.1", receiverPort, senderPort, 1200, 0L,
                AudioCaptureSource.MicrophoneAudio.ordinal, client, CHANNEL, senderEndpoint[0],
            )
            assertNotEquals(0L, receiver)
            assertNotEquals(0L, sender)

            val controlTimeUs = SystemClock.elapsedRealtimeNanos() / 1000L
            val control = NativeBridge.sessionProtectionProtectSessionControl(
                client,
                0L,
                controlTimeUs,
                byteArrayOf(1),
            )
            assertNotNull(control)
            val protectedControl = requireNotNull(control)
            assertEquals(0, ByteBuffer.wrap(protectedControl).int)
            val decodedControl = requireNotNull(
                NativeBridge.sessionProtectionUnprotectSessionControl(
                    host,
                    LOOPBACK,
                    senderPort,
                    protectedControl.copyOfRange(4, protectedControl.size),
                    controlTimeUs,
                ),
            )
            assertEquals(0, ByteBuffer.wrap(decodedControl).int)
            val afterControl = NativeBridge.sessionProtectionLastAuthenticatedReceiveUs(host)
            assertEquals(controlTimeUs, afterControl)
            val replay = requireNotNull(
                NativeBridge.sessionProtectionUnprotectSessionControl(
                    host,
                    LOOPBACK,
                    senderPort,
                    protectedControl.copyOfRange(4, protectedControl.size),
                    controlTimeUs + 1_000_000L,
                ),
            )
            assertEquals(12, ByteBuffer.wrap(replay).int)
            assertEquals(
                "Replay must not refresh liveness",
                afterControl,
                NativeBridge.sessionProtectionLastAuthenticatedReceiveUs(host),
            )
            val decryptedBefore = NativeBridge.sessionProtectionSnapshot(host)[2]
            SystemClock.sleep(10L)
            val beforeAudioUs = SystemClock.elapsedRealtimeNanos() / 1000L
            assertEquals(0, NativeBridge.audioTransportSubmitConfig(sender, 48_000, 1, 5000, 120))
            val event = NativeBridge.audioReceiverPump(receiver, 100_000L)
            val afterAudioUs = SystemClock.elapsedRealtimeNanos() / 1000L
            assertEquals(0L, event[1])
            assertEquals(decryptedBefore + 1L, NativeBridge.sessionProtectionSnapshot(host)[2])
            val lastReceiveUs = NativeBridge.sessionProtectionLastAuthenticatedReceiveUs(host)
            Log.i(
                "WarpnectClockProbe",
                "control_us=$afterControl before_audio_boot_us=$beforeAudioUs " +
                    "after_audio_boot_us=$afterAudioUs last_receive_us=$lastReceiveUs " +
                    "local_mono_us=${System.nanoTime() / 1000L}",
            )
            assertTrue("Media authentication must advance control-plane liveness", lastReceiveUs > afterControl)
            assertTrue("Media receive must use BOOTTIME, not MONOTONIC", lastReceiveUs in beforeAudioUs..afterAudioUs)
        } finally {
            if (sender != 0L) NativeBridge.audioTransportDestroy(sender)
            if (receiver != 0L) NativeBridge.audioReceiverDestroy(receiver)
            NativeBridge.preparedUdpEndpointDestroy(senderEndpoint[0])
            NativeBridge.preparedUdpEndpointDestroy(receiverEndpoint[0])
            NativeBridge.sessionProtectionDestroy(client)
            NativeBridge.sessionProtectionDestroy(host)
        }
    }

    private fun protection(role: Int, remotePort: Int): Long {
        val result = NativeBridge.sessionProtectionCreate(
            ByteArray(32) { (it + 1).toByte() }, ByteArray(16) { 1 }, 1,
            ByteArray(32) { 2 }, role, 1200, 4096, 16, 1L shl 20,
            2_000_000L, 100_000L, LOOPBACK, remotePort,
        )
        assertEquals(0L, result[1])
        assertNotEquals(0L, result[0])
        return result[0]
    }

    companion object {
        private const val CHANNEL = 5L
        private val LOOPBACK = byteArrayOf(127, 0, 0, 1)
    }
}
