package io.warpnect.debug.directshell

internal enum class PairingSetupPermissionAction { SHOW_NOTIFICATION, REQUEST_PERMISSIONS, DENIED }

internal class PairingSetupPermissionGate(requestIssued: Boolean = false) {
    var requestIssued: Boolean = requestIssued
        private set

    fun action(missing: Boolean, explicitRetry: Boolean = false): PairingSetupPermissionAction = when {
        !missing -> PairingSetupPermissionAction.SHOW_NOTIFICATION
        !requestIssued || explicitRetry -> {
            requestIssued = true
            PairingSetupPermissionAction.REQUEST_PERMISSIONS
        }
        else -> PairingSetupPermissionAction.DENIED
    }
}
