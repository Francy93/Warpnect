package io.warpnect.platform.input.transport

import io.warpnect.NativeBridge
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputModelError
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputScroll
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.WarpnectInputEvent
import io.warpnect.input.transport.InputTransportConfig
import io.warpnect.input.transport.InputTransportController
import io.warpnect.input.transport.InputTransportError
import io.warpnect.input.transport.InputTransportResult
import io.warpnect.input.transport.InputTransportSnapshot
import io.warpnect.input.transport.InputTransportState
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface InputTransportNativeApi {
    fun create(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialInputSequence: Long,
    ): Long

    fun destroy(handle: Long): Int

    fun submitKey(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        usagePage: Int,
        usageId: Int,
        action: Int,
        repeatCount: Int,
        modifierMask: Int,
    ): Int

    fun submitTouchFrame(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        action: Int,
        actionPointerId: Int,
        pointerCount: Int,
        contactScratch: ByteBuffer,
    ): Int

    fun submitPointerAbsolute(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        xNormalized: Int,
        yNormalized: Int,
        buttonMask: Int,
        pointerFlags: Int,
        pressure: Int,
    ): Int

    fun submitPointerRelative(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        deltaXQ1616: Int,
        deltaYQ1616: Int,
        buttonMask: Int,
    ): Int

    fun submitScroll(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        horizontalQ88: Int,
        verticalQ88: Int,
        buttonMask: Int,
    ): Int

    fun submitGamepadState(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        buttonMask: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
        leftTrigger: Int,
        rightTrigger: Int,
    ): Int

    fun submitReset(handle: Long, eventTimeUs: Long, deviceKind: Int, deviceSlot: Int, scope: Int, reason: Int): Int

    fun snapshot(handle: Long): LongArray
}

internal object NativeBridgeInputTransportApi : InputTransportNativeApi {
    override fun create(
        remoteAddress: String,
        remotePort: Int,
        localPort: Int,
        maxWireDatagramSize: Int,
        initialInputSequence: Long,
    ): Long = NativeBridge.inputTransportCreate(
        remoteAddress,
        remotePort,
        localPort,
        maxWireDatagramSize,
        initialInputSequence,
    )

    override fun destroy(handle: Long): Int = NativeBridge.inputTransportDestroy(handle)

    override fun submitKey(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        usagePage: Int,
        usageId: Int,
        action: Int,
        repeatCount: Int,
        modifierMask: Int,
    ): Int = NativeBridge.inputTransportSubmitKey(
        handle,
        eventTimeUs,
        deviceSlot,
        usagePage,
        usageId,
        action,
        repeatCount,
        modifierMask,
    )

    override fun submitTouchFrame(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        action: Int,
        actionPointerId: Int,
        pointerCount: Int,
        contactScratch: ByteBuffer,
    ): Int = NativeBridge.inputTransportSubmitTouchFrame(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        action,
        actionPointerId,
        pointerCount,
        contactScratch,
    )

    override fun submitPointerAbsolute(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        xNormalized: Int,
        yNormalized: Int,
        buttonMask: Int,
        pointerFlags: Int,
        pressure: Int,
    ): Int = NativeBridge.inputTransportSubmitPointerAbsolute(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        xNormalized,
        yNormalized,
        buttonMask,
        pointerFlags,
        pressure,
    )

    override fun submitPointerRelative(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        deltaXQ1616: Int,
        deltaYQ1616: Int,
        buttonMask: Int,
    ): Int = NativeBridge.inputTransportSubmitPointerRelative(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        deltaXQ1616,
        deltaYQ1616,
        buttonMask,
    )

    override fun submitScroll(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        horizontalQ88: Int,
        verticalQ88: Int,
        buttonMask: Int,
    ): Int = NativeBridge.inputTransportSubmitScroll(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        horizontalQ88,
        verticalQ88,
        buttonMask,
    )

    override fun submitGamepadState(
        handle: Long,
        eventTimeUs: Long,
        deviceSlot: Int,
        buttonMask: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
        leftTrigger: Int,
        rightTrigger: Int,
    ): Int = NativeBridge.inputTransportSubmitGamepadState(
        handle,
        eventTimeUs,
        deviceSlot,
        buttonMask,
        leftX,
        leftY,
        rightX,
        rightY,
        leftTrigger,
        rightTrigger,
    )

    override fun submitReset(
        handle: Long,
        eventTimeUs: Long,
        deviceKind: Int,
        deviceSlot: Int,
        scope: Int,
        reason: Int,
    ): Int = NativeBridge.inputTransportSubmitReset(
        handle,
        eventTimeUs,
        deviceKind,
        deviceSlot,
        scope,
        reason,
    )

    override fun snapshot(handle: Long): LongArray = NativeBridge.inputTransportSnapshot(handle)
}

class NativeSclInputTransportController private constructor(
    private val nativeApi: InputTransportNativeApi,
) : InputTransportController {
    constructor() : this(NativeBridgeInputTransportApi)

    companion object {
        internal fun forTesting(nativeApi: InputTransportNativeApi): NativeSclInputTransportController =
            NativeSclInputTransportController(nativeApi)
    }

    private var nativeHandle: Long = 0L
    private var config: InputTransportConfig? = null
    private var touchScratch: InputTouchScratch? = null
    private var localSnapshot = InputTransportSnapshot()

    override fun prepare(config: InputTransportConfig): InputTransportResult {
        if (localSnapshot.state == InputTransportState.Closed) {
            return result(InputTransportError.Closed)
        }
        if (nativeHandle != 0L) {
            return result(InputTransportError.InvalidConfiguration)
        }
        val validation = config.validate()
        if (validation != InputTransportError.None) {
            localSnapshot = localSnapshot.copy(state = InputTransportState.Error, lastError = validation)
            return InputTransportResult(validation, localSnapshot)
        }

        localSnapshot = localSnapshot.copy(
            state = InputTransportState.Preparing,
            remoteAddress = config.remoteAddress,
            remotePort = config.remotePort,
            localPort = config.localPort,
            lastError = InputTransportError.None,
        )
        val handle = nativeApi.create(
            config.remoteAddress,
            config.remotePort,
            config.localPort,
            config.maxWireDatagramSize,
            config.initialInputSequence,
        )
        if (handle == 0L) {
            localSnapshot = localSnapshot.copy(
                state = InputTransportState.Error,
                lastError = InputTransportError.InvalidEndpoint,
            )
            return InputTransportResult(InputTransportError.InvalidEndpoint, localSnapshot)
        }
        nativeHandle = handle
        this.config = config
        touchScratch = touchScratch ?: InputTouchScratch()
        localSnapshot = nativeSnapshot(InputTransportState.Prepared)
        return InputTransportResult(localSnapshot.lastError, localSnapshot)
    }

    override fun start(): InputTransportResult = when (localSnapshot.state) {
        InputTransportState.Closed -> result(InputTransportError.Closed)
        InputTransportState.Running -> result(InputTransportError.AlreadyRunning)
        InputTransportState.Prepared -> {
            localSnapshot = nativeSnapshot(InputTransportState.Running)
            InputTransportResult(InputTransportError.None, localSnapshot)
        }
        else -> result(InputTransportError.NotPrepared)
    }

    override fun submit(eventTimeUs: Long, event: WarpnectInputEvent): InputTransportResult {
        if (localSnapshot.state == InputTransportState.Closed) return result(InputTransportError.Closed)
        if (localSnapshot.state != InputTransportState.Running || nativeHandle == 0L) {
            return result(InputTransportError.NotRunning)
        }
        if (eventTimeUs < 0L || event.validationError() != InputModelError.None) {
            return result(InputTransportError.InvalidInputEvent)
        }

        val error = InputTransportError.fromNativeCode(submitNative(eventTimeUs, event))
        localSnapshot = nativeSnapshot(
            if (error == InputTransportError.InvalidHandle || error == InputTransportError.Closed) {
                InputTransportState.Error
            } else {
                InputTransportState.Running
            },
        ).copy(lastError = error)
        return InputTransportResult(error, localSnapshot)
    }

    override fun stop(): InputTransportResult {
        if (localSnapshot.state == InputTransportState.Closed) return result(InputTransportError.Closed)
        val handle = nativeHandle
        if (handle == 0L) {
            localSnapshot = localSnapshot.copy(state = InputTransportState.Stopped)
            return InputTransportResult(InputTransportError.None, localSnapshot)
        }
        localSnapshot = nativeSnapshot(InputTransportState.Stopping)
        nativeHandle = 0L
        val error = InputTransportError.fromNativeCode(nativeApi.destroy(handle))
        localSnapshot = localSnapshot.copy(
            state = if (error == InputTransportError.None) InputTransportState.Stopped else InputTransportState.Error,
            lastError = error,
        )
        config = null
        return InputTransportResult(error, localSnapshot)
    }

    override fun snapshot(): InputTransportSnapshot {
        if (nativeHandle != 0L) {
            localSnapshot = nativeSnapshot(localSnapshot.state)
        }
        return localSnapshot
    }

    override fun close() {
        if (localSnapshot.state != InputTransportState.Closed) {
            stop()
            localSnapshot = localSnapshot.copy(state = InputTransportState.Closed)
        }
    }

    private fun submitNative(eventTimeUs: Long, event: WarpnectInputEvent): Int = when (event) {
        is InputKeyEvent -> nativeApi.submitKey(
            nativeHandle,
            eventTimeUs,
            event.deviceSlot,
            event.usagePage,
            event.usageId,
            event.action.ordinal,
            event.repeatCount,
            event.modifierMask,
        )
        is InputTouchFrame -> {
            val scratch = touchScratch ?: return InputTransportError.InvalidHandle.ordinal
            scratch.write(event)
            nativeApi.submitTouchFrame(
                nativeHandle,
                eventTimeUs,
                event.deviceKind.ordinal,
                event.deviceSlot,
                event.action.ordinal,
                event.actionPointerId,
                event.contacts.size,
                scratch.buffer,
            )
        }
        is InputPointerAbsolute -> nativeApi.submitPointerAbsolute(
            nativeHandle,
            eventTimeUs,
            event.deviceKind.ordinal,
            event.deviceSlot,
            event.xNormalized,
            event.yNormalized,
            event.buttonMask,
            event.pointerFlags,
            event.pressure,
        )
        is InputPointerRelative -> nativeApi.submitPointerRelative(
            nativeHandle,
            eventTimeUs,
            event.deviceKind.ordinal,
            event.deviceSlot,
            event.deltaXQ16_16,
            event.deltaYQ16_16,
            event.buttonMask,
        )
        is InputScroll -> nativeApi.submitScroll(
            nativeHandle,
            eventTimeUs,
            event.deviceKind.ordinal,
            event.deviceSlot,
            event.horizontalQ8_8,
            event.verticalQ8_8,
            event.buttonMask,
        )
        is InputGamepadState -> nativeApi.submitGamepadState(
            nativeHandle,
            eventTimeUs,
            event.deviceSlot,
            event.buttonMask,
            event.leftX,
            event.leftY,
            event.rightX,
            event.rightY,
            event.leftTrigger,
            event.rightTrigger,
        )
        is InputResetState -> nativeApi.submitReset(
            nativeHandle,
            eventTimeUs,
            event.deviceKind.ordinal,
            event.deviceSlot,
            event.scope.ordinal,
            event.reason.ordinal,
        )
    }

    private fun nativeSnapshot(state: InputTransportState): InputTransportSnapshot =
        InputTransportSnapshot.fromNative(nativeApi.snapshot(nativeHandle), state, config)

    private fun result(error: InputTransportError): InputTransportResult =
        InputTransportResult(error, localSnapshot.copy(lastError = error))
}

internal class InputTouchScratch {
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder())

    fun write(frame: InputTouchFrame) {
        buffer.clear()
        for (contact in frame.contacts) {
            buffer.putInt(contact.pointerId)
            buffer.putInt(contact.toolType.ordinal)
            buffer.putInt(contact.pointerFlags)
            buffer.putInt(contact.xNormalized)
            buffer.putInt(contact.yNormalized)
            buffer.putInt(contact.pressure)
            buffer.putInt(contact.size)
        }
        buffer.clear()
    }

    private companion object {
        const val FIELDS_PER_CONTACT = 7
        const val BYTES_PER_FIELD = Int.SIZE_BYTES
        const val BYTES = 32 * FIELDS_PER_CONTACT * BYTES_PER_FIELD
    }
}

private fun WarpnectInputEvent.validationError(): InputModelError = when (this) {
    is InputKeyEvent -> validate()
    is InputTouchFrame -> validate()
    is InputPointerAbsolute -> validate()
    is InputPointerRelative -> validate()
    is InputScroll -> validate()
    is InputGamepadState -> validate()
    is InputResetState -> validate()
}
