package io.warpnect.platform.privileged.directshell.pairing

import android.content.Context
import android.os.Process
import java.nio.file.Files
import java.nio.file.LinkOption

/** An explicitly resolved location is not a supported/qualified provider or enrollment grant. */
internal class AndroidDirectShellCredentialStorageAdapter private constructor(
    val storage: DirectShellCredentialStorage,
    val filesystem: AndroidDirectShellCredentialFileSystem,
    val keys: AndroidDirectShellCredentialKeyStore,
) {
    enum class Location { RESOLVED_NOT_QUALIFIED, UNAVAILABLE }
    class Opened(val location: Location, val adapter: AndroidDirectShellCredentialStorageAdapter? = null) {
        override fun toString(): String = "DirectShellAndroidStorageLocation($location)"
    }

    companion object {
        /** Worker-only explicit factory. No key lookup/creation, namespace creation or passive composition hook. */
        fun forApplication(
            context: Context,
            keys: AndroidDirectShellCredentialKeyStore = AndroidDirectShellCredentialKeyStore(),
        ): Opened {
            return try {
                val app = context.applicationContext
                if (app.applicationInfo.uid != Process.myUid()) return Opened(Location.UNAVAILABLE)
                val raw = app.noBackupFilesDir.toPath()
                if (!Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) return Opened(Location.UNAVAILABLE)
                val base = raw.toRealPath()
                val data = app.dataDir.toPath().toRealPath()
                if (base.parent != data) return Opened(Location.UNAVAILABLE)
                val fs = AndroidDirectShellCredentialFileSystem(base)
                fs.canonicalBase(base)
                Opened(
                    Location.RESOLVED_NOT_QUALIFIED,
                    AndroidDirectShellCredentialStorageAdapter(DirectShellCredentialStorage(base, keys, fs), fs, keys),
                )
            } catch (_: Exception) {
                Opened(Location.UNAVAILABLE)
            }
        }
    }
}
