package io.warpnect.debug.directshell

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.io.IOException
import java.net.Socket
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/** DEBUG-only same-device Wireless Debugging client with a persisted, per-install ADB host key. */
internal class DirectShellSelfAdbConnectionManager(
    keyStore: DirectShellSelfAdbKeyStore,
) : AbsAdbConnectionManager() {
    private val material = keyStore.loadOrCreate()

    init {
        setHostAddress(DirectShellSelfBootstrapContract.LOOPBACK_HOST)
        setApi(Build.VERSION.SDK_INT)
        setTimeout(CONNECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = material.privateKey

    override fun getCertificate(): Certificate = material.certificate

    override fun getDeviceName(): String = DEVICE_NAME

    fun pairAndReadDeviceGuid(endpoint: DirectShellSelfAdbEndpointDiscovery.Endpoint, code: String): String {
        val address = DirectShellSelfAdbEndpointDiscovery.requireCurrentLocalSocketAddress(endpoint)
        val password = code.encodeToByteArray()
        try {
            Socket().use { socket ->
                socket.connect(address, PAIRING_IO_TIMEOUT_MS)
                socket.soTimeout = PAIRING_IO_TIMEOUT_MS
                PairingConnectionCtx(
                    endpoint.host, endpoint.port, password, material.privateKey, material.certificate, getDeviceName(),
                )
                    .use { pairing ->
                        val peer = pairing.startConnected(socket, address) {
                            check(!Thread.currentThread().isInterrupted) { "pairing_interrupted" }
                        }
                        val local = MessageDigest.getInstance("SHA-256").digest(material.certificate.encoded)
                        check(peer.endpoint == address && MessageDigest.isEqual(local, peer.localCertificateSha256)) {
                            "pairing_peer_binding_invalid"
                        }
                        return decodeDeviceGuid(peer.deviceGuid)
                    }
            }
        } finally {
            password.fill(0)
        }
    }

    private companion object {
        const val DEVICE_NAME = "Warpnect DirectShell Debug"
        const val CONNECTION_TIMEOUT_MILLIS = 12_000L
        const val PAIRING_IO_TIMEOUT_MS = 8_000
    }
}

internal fun decodeDeviceGuid(bytes: ByteArray): String = try {
    DirectShellSelfAdbEndpointDiscovery.requireTrustedServiceName(bytes.decodeToString(throwOnInvalidSequence = true))
} catch (_: CharacterCodingException) {
    throw IOException("pairing_device_guid_invalid")
} catch (_: IllegalArgumentException) {
    throw IOException("pairing_device_guid_invalid")
} finally {
    bytes.fill(0)
}
