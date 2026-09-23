package io.warpnect.debug.directshell

import android.os.Process
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.platform.input.injection.privileged.PrivilegedInputInjectionContract
import io.warpnect.platform.input.injection.privileged.PrivilegedInputInjectionUserService
import io.warpnect.platform.input.injection.privileged.toInputInjectionCapabilities
import io.warpnect.platform.input.injection.privileged.toInputInjectionSnapshot

/**
 * DirectShell-side adapter that invokes the established privileged injector without Shizuku.
 *
 * The server owns the actual tracker and release behavior. The normal process only serializes
 * already-validated Android-ready event objects across the authenticated local control channel.
 */
internal class DirectShellInputSessionBridge(
    private val generationId: Long,
) {
    private val service = PrivilegedInputInjectionUserService()

    fun dispatch(command: DirectShellProbeProtocol.Command, payload: ByteArray): Reply = when (command) {
        DirectShellProbeProtocol.Command.INPUT_SESSION_CAPABILITIES -> capabilities(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_PREPARE -> prepare(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_START -> start(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_KEY -> key(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_TOUCH -> touch(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_POINTER -> pointer(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_JOYSTICK -> joystick(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_RESET -> reset(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_STOP -> stop(payload)
        DirectShellProbeProtocol.Command.INPUT_SESSION_SNAPSHOT -> snapshot(payload)
        else -> Reply.unsupported(command.name)
    }

    fun shutdown(): Reply {
        val result = runCatching { service.stopInjection(true) }
            .getOrElse { InputInjectionError.RemoteFailure.code }
        return Reply.ok(
            "INPUT_SESSION_SHUTDOWN",
            DirectShellInputSessionCodec.encodeServiceResult(generationId, result),
        )
    }

    private fun capabilities(payload: ByteArray): Reply {
        val request = DirectShellInputSessionCodec.decodeGenerationRequest(payload, generationId)
        if (!request.isSuccess) return rejected(request.error)
        val capabilities = runCatching {
            service.capabilities.toInputInjectionCapabilities().copy(
                backend = InputInjectionBackend.DirectShell,
                privilegedUid = Process.myUid(),
                privilegedUidKind = io.warpnect.input.injection.PrivilegedUidKind.Shell,
            )
        }.getOrElse {
            return Reply.ok(
                "INPUT_SESSION_CAPABILITIES_ERROR",
                DirectShellInputSessionCodec.encodeCapabilities(
                    generationId,
                    io.warpnect.input.injection.InputInjectionCapabilities(
                        lastError = InputInjectionError.RemoteFailure,
                    ),
                ),
            )
        }
        return Reply.ok(
            "INPUT_SESSION_CAPABILITIES",
            DirectShellInputSessionCodec.encodeCapabilities(generationId, capabilities),
        )
    }

    private fun prepare(payload: ByteArray): Reply {
        val request = DirectShellInputSessionCodec.decodePrepare(payload, generationId)
        val config = request.value ?: return rejected(request.error)
        val code = runCatching {
            service.prepare(
                config.targetUid,
                config.injectionMode.toWireMode(),
                config.maxTrackedInjectionSlots,
                config.maxPressedKeysPerSlot,
            )
        }.getOrElse { InputInjectionError.RemoteFailure.code }
        return serviceResult("INPUT_SESSION_PREPARE", code)
    }

    private fun start(payload: ByteArray): Reply {
        if (!generationOnly(payload)) return rejected("generation")
        return serviceResult("INPUT_SESSION_START", call { service.startInjection() })
    }

    private fun key(payload: ByteArray): Reply {
        val event = DirectShellInputSessionCodec.decodeKey(payload, generationId).value
            ?: return rejected("key")
        return serviceResult(
            "INPUT_SESSION_KEY",
            call {
                service.injectKey(
                    event.sourceEventTimeUs,
                    event.stateSlot,
                    event.action,
                    event.keyCode,
                    event.repeatCount,
                    event.metaState,
                    event.scanCode,
                    event.flags,
                    event.source,
                    event.androidDeviceId,
                    event.displayId,
                )
            },
        )
    }

    private fun touch(payload: ByteArray): Reply {
        val event = DirectShellInputSessionCodec.decodeTouch(payload, generationId).value
            ?: return rejected("touch")
        return serviceResult(
            "INPUT_SESSION_TOUCH",
            call {
                val pointers = event.pointers
                service.injectTouch(
                    event.sourceEventTimeUs,
                    event.stateSlot,
                    event.actionMasked,
                    event.actionIndex,
                    pointers.size,
                    IntArray(pointers.size) { pointers[it].pointerId },
                    IntArray(pointers.size) { pointers[it].toolType },
                    FloatArray(pointers.size) { pointers[it].xPx },
                    FloatArray(pointers.size) { pointers[it].yPx },
                    FloatArray(pointers.size) { pointers[it].pressure },
                    FloatArray(pointers.size) { pointers[it].size },
                    event.metaState,
                    event.buttonState,
                    event.source,
                    event.androidDeviceId,
                    event.displayId,
                )
            },
        )
    }

    private fun pointer(payload: ByteArray): Reply {
        val event = DirectShellInputSessionCodec.decodePointer(payload, generationId).value
            ?: return rejected("pointer")
        return serviceResult(
            "INPUT_SESSION_POINTER",
            call {
                service.injectPointer(
                    event.sourceEventTimeUs,
                    event.stateSlot,
                    event.action,
                    event.actionButton,
                    event.xPx,
                    event.yPx,
                    event.relativeXPx,
                    event.relativeYPx,
                    event.horizontalScroll,
                    event.verticalScroll,
                    event.pressure,
                    event.size,
                    event.metaState,
                    event.buttonState,
                    event.source,
                    event.androidDeviceId,
                    event.displayId,
                )
            },
        )
    }

    private fun joystick(payload: ByteArray): Reply {
        val event = DirectShellInputSessionCodec.decodeJoystick(payload, generationId).value
            ?: return rejected("joystick")
        return serviceResult(
            "INPUT_SESSION_JOYSTICK",
            call {
                service.injectJoystick(
                    event.sourceEventTimeUs,
                    event.stateSlot,
                    event.leftX,
                    event.leftY,
                    event.rightX,
                    event.rightY,
                    event.leftTrigger,
                    event.rightTrigger,
                    event.hatX,
                    event.hatY,
                    event.metaState,
                    event.source,
                    event.androidDeviceId,
                    event.displayId,
                )
            },
        )
    }

    private fun reset(payload: ByteArray): Reply {
        val request = DirectShellInputSessionCodec.decodeReset(payload, generationId).value
            ?: return rejected("reset")
        return serviceResult(
            "INPUT_SESSION_RESET",
            call {
                service.resetState(
                    request.scope.toWireScope(),
                    request.stateSlot,
                    request.reason.ordinal + 1,
                )
            },
        )
    }

    private fun stop(payload: ByteArray): Reply {
        val resetAll = DirectShellInputSessionCodec.decodeStop(payload, generationId).value
            ?: return rejected("stop")
        return serviceResult("INPUT_SESSION_STOP", call { service.stopInjection(resetAll) })
    }

    private fun snapshot(payload: ByteArray): Reply {
        if (!generationOnly(payload)) return rejected("generation")
        val snapshot = runCatching {
            service.snapshot.toInputInjectionSnapshot().copy(
                backend = InputInjectionBackend.DirectShell,
                privilegedUid = Process.myUid(),
                privilegedUidKind = io.warpnect.input.injection.PrivilegedUidKind.Shell,
            )
        }.getOrElse {
            return serviceResult("INPUT_SESSION_SNAPSHOT_ERROR", InputInjectionError.RemoteFailure.code)
        }
        return Reply.ok(
            "INPUT_SESSION_SNAPSHOT",
            DirectShellInputSessionCodec.encodeSnapshot(generationId, snapshot),
        )
    }

    private fun generationOnly(payload: ByteArray): Boolean =
        DirectShellInputSessionCodec.decodeGenerationRequest(payload, generationId).isSuccess

    private fun serviceResult(classification: String, code: Int): Reply = Reply.ok(
        classification,
        DirectShellInputSessionCodec.encodeServiceResult(generationId, code),
    )

    private fun call(block: () -> Int): Int = runCatching(block).getOrElse { InputInjectionError.RemoteFailure.code }

    private fun rejected(reason: String?): Reply = Reply.badState(
        "INPUT_SESSION_REJECTED",
        "reason=${reason ?: "invalid"}".encodeToByteArray(),
    )

    private fun io.warpnect.input.injection.InputInjectionMode.toWireMode(): Int = when (this) {
        io.warpnect.input.injection.InputInjectionMode.AsyncLowLatency -> PrivilegedInputInjectionContract.MODE_ASYNC
        io.warpnect.input.injection.InputInjectionMode.WaitForResultDiagnostics ->
            PrivilegedInputInjectionContract.MODE_WAIT_FOR_RESULT
    }

    private fun io.warpnect.input.injection.InputResetScope.toWireScope(): Int = when (this) {
        io.warpnect.input.injection.InputResetScope.ThisSlot -> PrivilegedInputInjectionContract.RESET_THIS_SLOT
        io.warpnect.input.injection.InputResetScope.AllSlots -> PrivilegedInputInjectionContract.RESET_ALL_SLOTS
    }

    internal data class Reply(
        val status: DirectShellProbeProtocol.Status,
        val classification: String,
        val payload: ByteArray,
    ) {
        companion object {
            fun ok(classification: String, payload: ByteArray): Reply =
                Reply(DirectShellProbeProtocol.Status.OK, classification, payload)

            fun badState(classification: String, payload: ByteArray): Reply =
                Reply(DirectShellProbeProtocol.Status.BAD_STATE, classification, payload)

            fun unsupported(command: String): Reply = Reply(
                DirectShellProbeProtocol.Status.UNSUPPORTED,
                "INPUT_SESSION_UNSUPPORTED",
                "command=$command".encodeToByteArray(),
            )
        }
    }
}
