package io.warpnect

import android.app.Application
import io.warpnect.platform.session.integration.AndroidSecureSessionComposition

/** Application-scope owner for the normal RFC-005I composition; Activity recreation never recreates it. */
class WarpnectApplication : Application() {
    private val compositionDelegate = lazy {
        AndroidSecureSessionComposition.create(this)
    }

    val secureSessionComposition: AndroidSecureSessionComposition?
        get() = compositionDelegate.value

    override fun onTerminate() {
        // Android production does not promise process-death session resume; this is test-process cleanup.
        if (compositionDelegate.isInitialized()) compositionDelegate.value?.close()
        super.onTerminate()
    }
}
