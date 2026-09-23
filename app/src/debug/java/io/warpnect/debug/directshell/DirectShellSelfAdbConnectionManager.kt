package io.warpnect.debug.directshell

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
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

    private companion object {
        const val DEVICE_NAME = "Warpnect DirectShell Debug"
        const val CONNECTION_TIMEOUT_MILLIS = 12_000L
    }
}
