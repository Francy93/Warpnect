package io.warpnect.debug.directshell

import io.warpnect.debug.directshell.DirectShellRoleCommands.Input
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.platform.input.injection.RootInputRequestBounds

/** Unwired admission after authenticated framing, before any privileged adapter is called. */
internal class DirectShellInputRoleSession(
    private val owner: Owner,
    private val generation: Long,
    private val targetUid: Int,
) {
    class Owner
    class Call internal constructor(val command: Input, private val validated: ByteArray) {
        fun copyPayload(): ByteArray = validated.copyOf()
    }
    enum class Phase { IDLE, PREPARED, STARTED, DRAIN_PENDING, CLOSED }

    init {
        require(generation > 0 && targetUid >= 10_000)
    }

    private var pending: Call? = null
    private var phase = Phase.IDLE

    @Synchronized
    fun begin(caller: Owner, wire: Int, payload: ByteArray): Call? {
        if (caller !== owner || pending != null || payload.size !in 16..4096) return null
        val snapshot = payload.copyOf()
        val command = DirectShellRoleCommands.inputEnvelope(wire, snapshot, generation) ?: return null
        if (!permits(command) || !valid(command, snapshot)) return null
        return Call(command, snapshot).also { pending = it }
    }

    /** A lost or uncertain result is terminal for the role; never retry the operation. */
    @Synchronized
    fun complete(caller: Owner, call: Call, succeeded: Boolean): Boolean {
        if (caller !== owner || pending !== call) return false
        pending = null
        if (phase != Phase.DRAIN_PENDING) {
            phase = if (!succeeded) {
                Phase.DRAIN_PENDING
            } else {
                when (call.command) {
                    Input.PREPARE -> Phase.PREPARED
                    Input.START -> Phase.STARTED
                    Input.STOP -> Phase.DRAIN_PENDING
                    else -> phase
                }
            }
        }
        return true
    }

    @Synchronized
    fun cancel(caller: Owner): Boolean {
        if (caller !== owner || phase == Phase.CLOSED || phase == Phase.DRAIN_PENDING) return false
        phase = Phase.DRAIN_PENDING
        return true
    }

    @Synchronized
    fun observeDrain(caller: Owner, observedGeneration: Long, workerExited: Boolean, inputReleased: Boolean): Boolean {
        if (caller !== owner || observedGeneration != generation || phase != Phase.DRAIN_PENDING ||
            !workerExited || !inputReleased
        ) {
            return false
        }
        // A real worker exit may resolve an in-flight request whose acknowledgement was lost.
        pending = null
        phase = Phase.CLOSED
        return true
    }

    @Synchronized
    fun phase() = phase

    private fun permits(command: Input) = when (phase) {
        Phase.IDLE -> command == Input.CAPABILITIES || command == Input.PREPARE || command == Input.SNAPSHOT
        Phase.PREPARED -> command == Input.START || command == Input.STOP || command == Input.SNAPSHOT
        Phase.STARTED -> command != Input.PREPARE && command != Input.START
        Phase.DRAIN_PENDING, Phase.CLOSED -> false
    }

    private fun valid(command: Input, bytes: ByteArray): Boolean {
        val codec = DirectShellInputSessionCodec
        return when (command) {
            Input.PREPARE -> codec.decodePrepare(bytes, generation).value?.let {
                it.targetUid == targetUid && it.injectionMode == InputInjectionMode.AsyncLowLatency
            } == true
            Input.KEY -> codec.decodeKey(bytes, generation).value?.let(RootInputRequestBounds::valid) == true
            Input.TOUCH -> codec.decodeTouch(bytes, generation).value?.let(RootInputRequestBounds::valid) == true
            Input.POINTER -> codec.decodePointer(bytes, generation).value?.let(RootInputRequestBounds::valid) == true
            Input.JOYSTICK -> codec.decodeJoystick(bytes, generation).value?.let(RootInputRequestBounds::valid) == true
            Input.RESET -> codec.decodeReset(bytes, generation).isSuccess
            Input.STOP -> bytes.contentEquals(codec.encodeStop(generation, resetAll = true))
            else -> codec.decodeGenerationRequest(bytes, generation).isSuccess
        }
    }
}
