package io.warpnect.debug.directshell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeFactory
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeRegistry

/**
 * DEBUG-only process initializer for normal Host acquisition.
 *
 * It registers a lazy factory only. It never connects to local ADB, launches app_process, or
 * starts a privileged server until the user requests Host mode through the normal UI.
 */
internal class DirectShellRuntimeAutoRegistrationProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val applicationContext = context?.applicationContext
        if (applicationContext == null) {
            Log.e(TAG, "DIRECT_SHELL_RUNTIME_AUTO_REGISTRATION_FAILED missing_context")
            return false
        }
        DirectRootLabController.clearAbandonedFiles(applicationContext)
        val registered = PrivilegedRuntimeRegistry.registerAutomaticDirectShell(
            PrivilegedRuntimeFactory { DirectShellRuntime(applicationContext) },
        )
        Log.i(
            TAG,
            "DIRECT_SHELL_RUNTIME_AUTO_REGISTRATION registered=$registered " +
                "launch_on_host_enable_only=true",
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0

    private companion object {
        const val TAG = "WarpnectDirectShellRuntime"
    }
}
