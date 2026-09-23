package io.warpnect.platform.privileged

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds provider-owned UserService startup when no ServiceConnection callback is delivered. */
internal suspend fun <T> awaitPrivilegedUserServiceStart(
    timeoutMillis: Long,
    bind: (CancellableContinuation<T?>) -> Unit,
): T? = withTimeoutOrNull(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        bind(continuation)
    }
}
