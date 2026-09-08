package io.warpnect.platform.audio.capture.privileged

import android.os.Binder

internal interface PrivilegedBinderIdentity {
    fun clearCallingIdentity(): Long

    fun restoreCallingIdentity(token: Long)
}

internal object AndroidPrivilegedBinderIdentity : PrivilegedBinderIdentity {
    override fun clearCallingIdentity(): Long = Binder.clearCallingIdentity()

    override fun restoreCallingIdentity(token: Long) {
        Binder.restoreCallingIdentity(token)
    }
}

internal inline fun <T> withUserServiceIdentity(identity: PrivilegedBinderIdentity, operation: () -> T): T {
    val token = identity.clearCallingIdentity()
    return try {
        operation()
    } finally {
        identity.restoreCallingIdentity(token)
    }
}
