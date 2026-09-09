package io.warpnect.platform.audio.capture

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T> awaitPrivilegedUserServiceStart(
    timeoutMillis: Long,
    bind: (CancellableContinuation<T?>) -> Unit,
): T? = withTimeoutOrNull(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        bind(continuation)
    }
}
