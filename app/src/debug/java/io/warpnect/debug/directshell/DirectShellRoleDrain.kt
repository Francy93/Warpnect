package io.warpnect.debug.directshell

import io.warpnect.debug.directshell.DirectShellRoleTransfer.Owner
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Resource
import io.warpnect.debug.directshell.DirectShellRoleTransfer.Resources
import java.util.concurrent.atomic.AtomicBoolean

/** Local, unwired release adapter. A close returning is not its own release proof. */
internal class DirectShellRoleDrain private constructor(
    private val transfer: DirectShellRoleTransfer,
    private val owner: Owner,
    private val resources: Map<Resource, Handle>,
) {
    class Handle(val resource: Resource, val close: () -> Unit, val isReleased: () -> Boolean)
    private val closeFinished = AtomicBoolean()

    /** Run on the role owner worker, never the Binder/UI monitor. No retries, even after a throw. */
    fun closeOwned(): Boolean {
        if (!transfer.beginRelease(owner)) return false
        var acknowledged = true
        for (handle in resources.values) {
            try {
                handle.close()
            } catch (_: Exception) {
                acknowledged = false
            }
        }
        closeFinished.set(true)
        return acknowledged
    }

    /** An acknowledgement, missing handle, stale caller or surviving worker cannot close the ledger. */
    fun observe(generation: Long, workerExited: Boolean): Boolean {
        if (!workerExited || !closeFinished.get() || !transfer.isReleasePending(owner, generation)) return false
        val closed = resources.filterValues {
            try {
                it.isReleased()
            } catch (_: Exception) {
                false
            }
        }.keys
        return transfer.observeRelease(owner, generation, closed, workerExited)
    }

    companion object {
        fun pcm(
            transfer: DirectShellRoleTransfer,
            owner: Owner,
            ledger: Resources.Pcm,
            backing: Handle,
            notifyWrite: Handle,
            ackRead: Handle,
        ): DirectShellRoleDrain? {
            if (!transfer.owns(owner, ledger) || backing.resource !== ledger.backing ||
                notifyWrite.resource !== ledger.notifyWrite || ackRead.resource !== ledger.ackRead
            ) {
                return null
            }
            val exact = listOf(backing, notifyWrite, ackRead).associateBy { it.resource }
            return DirectShellRoleDrain(transfer, owner, exact)
        }

        fun video(
            transfer: DirectShellRoleTransfer,
            owner: Owner,
            ledger: Resources.Video,
            surface: Handle,
        ): DirectShellRoleDrain? {
            if (!transfer.owns(owner, ledger) || surface.resource !== ledger.surface) return null
            return DirectShellRoleDrain(transfer, owner, mapOf(surface.resource to surface))
        }
    }
}
