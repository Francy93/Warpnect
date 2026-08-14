package io.warpnect.session

object SessionBounds {
    const val DEFAULT_MAX_CONCURRENT_CLIENTS = 1
    const val HARD_MAX_CONCURRENT_CLIENTS = 8
    const val DEFAULT_MAX_SESSIONS = 8
    const val HARD_MAX_SESSIONS = 8
    const val DEFAULT_MAX_PATHS_PER_SESSION = 4
    const val HARD_MAX_PATHS_PER_SESSION = 4
    const val DEFAULT_MAX_CHANNELS_PER_SESSION = 32
    const val HARD_MAX_CHANNELS_PER_SESSION = 32
    const val DEFAULT_MAX_PERIPHERALS_PER_SESSION = 64
    const val HARD_MAX_PERIPHERALS_PER_SESSION = 64
    const val MAX_LOGICAL_DEVICE_SLOT = 65_534
}

enum class DuplicatePeerSessionPolicy {
    SingleSessionPerPeer,
    MultipleSessionsPerPeer,
}

enum class MicrophoneRoutingPolicy {
    SeparatePerPeer,
    MixToSingleHostStream,
}

enum class PeripheralPresencePolicy {
    MirrorPhysicalPresence,
    StableSessionPresence,
}

enum class PathPreferencePolicy {
    PreferDirectThenLan,
    PreferLan,
    DirectOnly,
    LanOnly,
}

enum class SecondaryPathPolicy {
    Disabled,
    KeepValidatedStandby,
}

/**
 * Immutable per-kind peripheral-presence preferences. These are intent only; they do not create a
 * persistent Android device, a mixer, or another runtime backend.
 */
data class PeripheralPresencePolicies(
    val keyboard: PeripheralPresencePolicy = PeripheralPresencePolicy.MirrorPhysicalPresence,
    val mouse: PeripheralPresencePolicy = PeripheralPresencePolicy.MirrorPhysicalPresence,
    val touchscreen: PeripheralPresencePolicy = PeripheralPresencePolicy.MirrorPhysicalPresence,
    val gamepad: PeripheralPresencePolicy = PeripheralPresencePolicy.MirrorPhysicalPresence,
    val stylus: PeripheralPresencePolicy = PeripheralPresencePolicy.MirrorPhysicalPresence,
    val touchpad: PeripheralPresencePolicy = PeripheralPresencePolicy.MirrorPhysicalPresence,
    val microphone: PeripheralPresencePolicy = PeripheralPresencePolicy.MirrorPhysicalPresence,
) {
    fun forKind(kind: PeripheralKind): PeripheralPresencePolicy = when (kind) {
        PeripheralKind.Keyboard -> keyboard
        PeripheralKind.Mouse -> mouse
        PeripheralKind.Touchscreen -> touchscreen
        PeripheralKind.Gamepad -> gamepad
        PeripheralKind.Stylus -> stylus
        PeripheralKind.Touchpad -> touchpad
        PeripheralKind.Microphone -> microphone
    }
}

/**
 * Resolved local behavior preferences. The manager applies its own concurrency and duplicate-peer
 * limits when registering sessions; a session retains the immutable effective policy it received.
 */
data class SessionBehaviorPolicy(
    val maxConcurrentClients: Int = SessionBounds.DEFAULT_MAX_CONCURRENT_CLIENTS,
    val duplicatePeerSessionPolicy: DuplicatePeerSessionPolicy =
        DuplicatePeerSessionPolicy.SingleSessionPerPeer,
    val microphoneRoutingPolicy: MicrophoneRoutingPolicy = MicrophoneRoutingPolicy.SeparatePerPeer,
    val peripheralPresencePolicies: PeripheralPresencePolicies = PeripheralPresencePolicies(),
    val pathPreferencePolicy: PathPreferencePolicy = PathPreferencePolicy.PreferDirectThenLan,
    val secondaryPathPolicy: SecondaryPathPolicy = SecondaryPathPolicy.KeepValidatedStandby,
) {
    fun validate(): SessionError = if (maxConcurrentClients in 1..SessionBounds.HARD_MAX_CONCURRENT_CLIENTS) {
        SessionError.None
    } else {
        SessionError.InvalidPolicy
    }
}

/**
 * Bounded manager configuration. The maximum collection sizes are implementation safety limits,
 * while [SessionBehaviorPolicy.maxConcurrentClients] is the product-facing Host default.
 */
data class SessionManagerConfig(
    val localDeviceId: DeviceId,
    val initialPolicy: SessionBehaviorPolicy = SessionBehaviorPolicy(),
    val maxSessions: Int = SessionBounds.DEFAULT_MAX_SESSIONS,
    val maxPathsPerSession: Int = SessionBounds.DEFAULT_MAX_PATHS_PER_SESSION,
    val maxChannelsPerSession: Int = SessionBounds.DEFAULT_MAX_CHANNELS_PER_SESSION,
    val maxPeripheralsPerSession: Int = SessionBounds.DEFAULT_MAX_PERIPHERALS_PER_SESSION,
) {
    fun validate(): SessionError {
        if (initialPolicy.validate() != SessionError.None) return SessionError.InvalidPolicy
        if (maxSessions !in 1..SessionBounds.HARD_MAX_SESSIONS ||
            maxPathsPerSession !in 1..SessionBounds.HARD_MAX_PATHS_PER_SESSION ||
            maxChannelsPerSession !in 1..SessionBounds.HARD_MAX_CHANNELS_PER_SESSION ||
            maxPeripheralsPerSession !in 1..SessionBounds.HARD_MAX_PERIPHERALS_PER_SESSION
        ) {
            return SessionError.InvalidManagerConfiguration
        }
        return if (initialPolicy.maxConcurrentClients <= maxSessions) {
            SessionError.None
        } else {
            SessionError.InvalidPolicy
        }
    }
}
