package io.warpnect.debug.directshell

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** One app-process owner, backed by an app-private no-backup record before any protocol work. */
internal class DirectShellPairingAuthorization(
    private val store: Store,
    private val bootCount: () -> Int,
    private val elapsedMs: () -> Long,
) {
    data class Grant(val token: String, val boot: Int, val issuedAtMs: Long)
    data class Record(val grant: Grant, val consumed: Boolean)

    interface Store {
        fun read(): Record?
        fun write(record: Record)
    }

    class Lease

    private var active: Lease? = null

    @Synchronized
    fun present(grant: Grant): Boolean {
        if (!isLive(grant) || active != null) return false
        val previous = store.read()
        if (previous?.grant == grant) return !previous.consumed
        if (previous != null &&
            (previous.grant.token == grant.token ||
                (previous.grant.boot == grant.boot && previous.grant.issuedAtMs >= grant.issuedAtMs))
        ) return false
        store.write(Record(grant, consumed = false))
        return true
    }

    @Synchronized
    fun claim(token: String): Lease? {
        if (active != null) return null
        val pending = store.read() ?: return null
        if (pending.consumed || pending.grant.token != token || !isLive(pending.grant)) return null
        store.write(pending.copy(consumed = true))
        return Lease().also { active = it }
    }

    @Synchronized
    fun dismiss(grant: Grant) {
        val pending = store.read() ?: return
        if (pending.grant == grant && !pending.consumed) store.write(pending.copy(consumed = true))
    }

    @Synchronized
    fun finish(lease: Lease): Boolean {
        if (active !== lease) return false
        active = null
        return true
    }

    private fun isLive(grant: Grant): Boolean {
        val now = elapsedMs()
        return grant.token.matches(TOKEN) && grant.boot >= 0 && grant.boot == bootCount() &&
            grant.issuedAtMs >= 0 && grant.issuedAtMs <= now && now - grant.issuedAtMs < LIFETIME_MS
    }

    companion object {
        internal const val LIFETIME_MS = 300_000L
        private val TOKEN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private var instance: DirectShellPairingAuthorization? = null

        @Synchronized
        fun forApp(context: Context): DirectShellPairingAuthorization {
            val app = context.applicationContext
            return instance ?: DirectShellPairingAuthorization(
                AndroidStore(app),
                { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) },
                SystemClock::elapsedRealtime,
            ).also { instance = it }
        }
    }

    private class AndroidStore(context: Context) : Store {
        private val file = AtomicFile(File(context.noBackupFilesDir, "directshell-notification-authorization.v1"))

        override fun read(): Record? = try {
            DataInputStream(file.openRead()).use { input ->
                val grant = Grant(input.readUTF(), input.readInt(), input.readLong())
                val consumed = input.readBoolean()
                if (input.read() != -1) throw IOException("pairing_authorization_invalid")
                Record(grant, consumed)
            }
        } catch (_: FileNotFoundException) {
            if (file.baseFile.exists()) throw IOException("pairing_authorization_unreadable")
            null
        }

        override fun write(record: Record) {
            val output = file.startWrite()
            try {
                val data = DataOutputStream(output)
                data.writeUTF(record.grant.token)
                data.writeInt(record.grant.boot)
                data.writeLong(record.grant.issuedAtMs)
                data.writeBoolean(record.consumed)
                data.flush()
                output.fd.sync()
            } catch (failure: IOException) {
                file.failWrite(output)
                throw failure
            }
            file.finishWrite(output)
            if (read() != record) throw IOException("pairing_authorization_write_unconfirmed")
        }
    }
}
