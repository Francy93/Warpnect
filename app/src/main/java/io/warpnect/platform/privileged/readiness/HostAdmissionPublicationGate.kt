package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.DirectRootSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot

/** Freeze authorizes publication; only a successful normal Host start actually owns it. */
internal class HostAdmissionPublicationGate {
    private var published: PreparedPrivilegedCapabilityPlan? = null

    fun publish(prepared: PreparedPrivilegedCapabilityPlan, startHost: () -> Boolean): Boolean {
        check(prepared.commitState == HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION) {
            "advisory_plan_cannot_publish_host"
        }
        if (published != null || !startHost()) return false
        published = prepared
        return true
    }

    fun rootOwnershipLost(runtime: DirectRootSnapshot): Boolean {
        val current = published ?: return false
        if (PrivilegedProvider.DIRECT_ROOT !in current.plan.participatingProviders) return false
        val expected = current.runtimeState.directRoot.rootFoundation
        return !runtime.ready || runtime.generation != expected?.generation ||
            runtime.provenance != expected?.provenance
    }

    fun directShellOwnershipLost(runtime: PrivilegedRuntimeSnapshot): Boolean {
        val current = published ?: return false
        if (PrivilegedProvider.DIRECT_SHELL !in current.plan.participatingProviders) return false
        return runtime.kind != PrivilegedRuntimeKind.DIRECT_SHELL || !runtime.ready ||
            runtime.generation != current.runtimeSnapshot.generation
    }

    fun clear() {
        published = null
    }
}
