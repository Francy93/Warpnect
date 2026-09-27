package io.warpnect.platform.privileged.runtime

import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.readiness.PersistentCapabilityEvidenceRecord
import io.warpnect.platform.session.capability.AndroidCapabilityProbeSnapshot

/** Provider-specific resource acquisition, not a bootstrap or shell-command interface. */
internal interface DirectRootHostResources : AutoCloseable {
    val generation: Long
    val snapshot: AndroidCapabilityProbeSnapshot
    val matrix: PrivilegedCapabilityMatrix
}

internal fun interface DirectRootHostResourceFactory {
    fun acquire(runtime: DirectRootRuntime, evidence: List<PersistentCapabilityEvidenceRecord>): DirectRootHostResources
}

/** A registered authenticated authority can be retained by the normal Host admission owner. */
internal object DirectRootHostOwnership {
    private val lock = Any()
    private var runtime: DirectRootRuntime? = null
    private var factory: DirectRootHostResourceFactory? = null
    private var resources: DirectRootHostResources? = null
    private var releaseAuthority: (() -> Unit)? = null

    fun register(owner: DirectRootRuntime, acquire: DirectRootHostResourceFactory, release: () -> Unit) =
        synchronized(lock) {
            check(runtime == null || runtime === owner) { "root_host_owner_conflict" }
            runtime = owner
            factory = acquire
            releaseAuthority = release
        }

    fun passiveSnapshot(): PrivilegedRuntimeSnapshot? = synchronized(lock) {
        runtime?.snapshot?.takeIf { it.ready }?.asHostSnapshot()
    }

    fun revalidate(generation: Long): PrivilegedRuntimeSnapshot? {
        val owner = synchronized(lock) { runtime } ?: return null
        if (owner.snapshot.generation != generation || !owner.checkLiveness()) return null
        return owner.snapshot.takeIf { it.ready && it.generation == generation }?.asHostSnapshot()
    }

    fun acquire(generation: Long, evidence: List<PersistentCapabilityEvidenceRecord>): DirectRootHostResources {
        val (owner, create) = synchronized(lock) {
            check(resources == null) { "root_host_resources_already_owned" }
            requireNotNull(runtime) to requireNotNull(factory)
        }
        check(revalidate(generation)?.ready == true) { "root_host_late_revalidation_failed" }
        val acquired = create.acquire(owner, evidence)
        try {
            check(acquired.generation == generation && revalidate(generation)?.ready == true) {
                "root_host_acquisition_generation_lost"
            }
            synchronized(lock) {
                check(runtime === owner && resources == null) { "root_host_owner_changed" }
                resources = acquired
            }
            return acquired
        } catch (error: Exception) {
            acquired.close()
            throw error
        }
    }

    fun release() {
        val (owned, release) = synchronized(lock) {
            val value = resources to releaseAuthority
            resources = null
            value
        }
        try {
            owned?.close()
        } finally {
            release?.invoke()
        }
    }

    fun clear(owner: DirectRootRuntime?) {
        val owned = synchronized(lock) {
            if (runtime !== owner) return
            runtime = null
            factory = null
            releaseAuthority = null
            resources.also { resources = null }
        }
        owned?.close()
    }

    private fun DirectRootSnapshot.asHostSnapshot() = PrivilegedRuntimeSnapshot(
        kind = PrivilegedRuntimeKind.DIRECT_ROOT,
        state = PrivilegedRuntimeState.READY,
        phase = PrivilegedRuntimePhase.RUNTIME_READY,
        generation = generation,
        identity = PrivilegedRuntimeIdentity(identity?.uid, identity?.gid, identity?.selinuxContext),
        detail = "authenticated_root provenance=$provenance",
    )
}
