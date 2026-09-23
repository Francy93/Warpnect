package io.warpnect.debug.directshell

import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.input.injection.MAX_TOUCH_POINTERS
import io.warpnect.input.injection.PrivilegedUidKind
import io.warpnect.input.injection.UhidCapability
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Binary payloads for the DEBUG-only DirectShell Input Session bridge.
 *
 * The outer DirectShell protocol supplies HMAC framing and request ordering. This inner codec
 * independently binds every operation and reply to one shell-server generation and rejects
 * malformed, trailing, or oversized command data before it reaches the injector.
 */
internal object DirectShellInputSessionCodec {
    private const val MAGIC = 0x574E4953 // "WNIS"
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 4 * 1024

    fun generationRequest(generationId: Long): ByteArray = encode(generationId) {}

    fun decodeGenerationRequest(payload: ByteArray, expectedGenerationId: Long): DecodeResult<Unit> =
        decode(payload, expectedGenerationId) { Unit }

    fun encodePrepare(generationId: Long, config: InputInjectionConfig): ByteArray = encode(generationId) {
        writeInt(config.targetUid)
        writeInt(config.injectionMode.ordinal)
        writeInt(config.maxTrackedInjectionSlots)
        writeInt(config.maxPressedKeysPerSlot)
        writeBoolean(config.resetAllOnStop)
    }

    fun decodePrepare(payload: ByteArray, expectedGenerationId: Long): DecodeResult<InputInjectionConfig> =
        decode(payload, expectedGenerationId) {
            val targetUid = readInt()
            val mode = InputInjectionMode.entries.getOrNull(readInt())
                ?: throw DecodeException("invalid_mode")
            InputInjectionConfig(
                targetUid = targetUid,
                injectionMode = mode,
                maxTrackedInjectionSlots = readInt(),
                maxPressedKeysPerSlot = readInt(),
                resetAllOnStop = readBoolean(),
            )
        }.validate { config -> config.validate().takeIf { it != InputInjectionError.None }?.let { "invalid_config" } }

    fun encodeKey(generationId: Long, event: AndroidKeyInjectionEvent): ByteArray = encode(generationId) {
        writeLong(event.sourceEventTimeUs)
        writeInt(event.stateSlot)
        writeInt(event.action)
        writeInt(event.keyCode)
        writeInt(event.repeatCount)
        writeInt(event.metaState)
        writeInt(event.scanCode)
        writeInt(event.flags)
        writeInt(event.source)
        writeInt(event.androidDeviceId)
        writeInt(event.displayId)
    }

    fun decodeKey(payload: ByteArray, expectedGenerationId: Long): DecodeResult<AndroidKeyInjectionEvent> =
        decode(payload, expectedGenerationId) {
            AndroidKeyInjectionEvent(
                sourceEventTimeUs = readLong(),
                stateSlot = readInt(),
                action = readInt(),
                keyCode = readInt(),
                repeatCount = readInt(),
                metaState = readInt(),
                scanCode = readInt(),
                flags = readInt(),
                source = readInt(),
                androidDeviceId = readInt(),
                displayId = readInt(),
            )
        }

    fun encodeTouch(generationId: Long, event: AndroidTouchInjectionEvent): ByteArray = encode(generationId) {
        writeLong(event.sourceEventTimeUs)
        writeInt(event.stateSlot)
        writeInt(event.actionMasked)
        writeInt(event.actionIndex)
        writeInt(event.pointers.size)
        event.pointers.forEach { pointer ->
            writeInt(pointer.pointerId)
            writeInt(pointer.toolType)
            writeFloat(pointer.xPx)
            writeFloat(pointer.yPx)
            writeFloat(pointer.pressure)
            writeFloat(pointer.size)
        }
        writeInt(event.metaState)
        writeInt(event.buttonState)
        writeInt(event.source)
        writeInt(event.androidDeviceId)
        writeInt(event.displayId)
    }

    fun decodeTouch(payload: ByteArray, expectedGenerationId: Long): DecodeResult<AndroidTouchInjectionEvent> =
        decode(payload, expectedGenerationId) {
            val sourceEventTimeUs = readLong()
            val stateSlot = readInt()
            val actionMasked = readInt()
            val actionIndex = readInt()
            val pointerCount = readInt()
            if (pointerCount !in 1..MAX_TOUCH_POINTERS) throw DecodeException("invalid_pointer_count")
            val pointers = Array(pointerCount) {
                AndroidTouchPointer(
                    pointerId = readInt(),
                    toolType = readInt(),
                    xPx = readFloat(),
                    yPx = readFloat(),
                    pressure = readFloat(),
                    size = readFloat(),
                )
            }
            AndroidTouchInjectionEvent(
                stateSlot = stateSlot,
                sourceEventTimeUs = sourceEventTimeUs,
                actionMasked = actionMasked,
                actionIndex = actionIndex,
                pointers = pointers,
                metaState = readInt(),
                buttonState = readInt(),
                source = readInt(),
                androidDeviceId = readInt(),
                displayId = readInt(),
            )
        }

    fun encodePointer(generationId: Long, event: AndroidPointerInjectionEvent): ByteArray = encode(generationId) {
        writeLong(event.sourceEventTimeUs)
        writeInt(event.stateSlot)
        writeInt(event.action)
        writeInt(event.actionButton)
        writeFloat(event.xPx)
        writeFloat(event.yPx)
        writeFloat(event.relativeXPx)
        writeFloat(event.relativeYPx)
        writeFloat(event.horizontalScroll)
        writeFloat(event.verticalScroll)
        writeFloat(event.pressure)
        writeFloat(event.size)
        writeInt(event.metaState)
        writeInt(event.buttonState)
        writeInt(event.source)
        writeInt(event.androidDeviceId)
        writeInt(event.displayId)
    }

    fun decodePointer(payload: ByteArray, expectedGenerationId: Long): DecodeResult<AndroidPointerInjectionEvent> =
        decode(payload, expectedGenerationId) {
            AndroidPointerInjectionEvent(
                sourceEventTimeUs = readLong(),
                stateSlot = readInt(),
                action = readInt(),
                actionButton = readInt(),
                xPx = readFloat(),
                yPx = readFloat(),
                relativeXPx = readFloat(),
                relativeYPx = readFloat(),
                horizontalScroll = readFloat(),
                verticalScroll = readFloat(),
                pressure = readFloat(),
                size = readFloat(),
                metaState = readInt(),
                buttonState = readInt(),
                source = readInt(),
                androidDeviceId = readInt(),
                displayId = readInt(),
            )
        }

    fun encodeJoystick(generationId: Long, event: AndroidJoystickInjectionEvent): ByteArray = encode(generationId) {
        writeLong(event.sourceEventTimeUs)
        writeInt(event.stateSlot)
        writeFloat(event.leftX)
        writeFloat(event.leftY)
        writeFloat(event.rightX)
        writeFloat(event.rightY)
        writeFloat(event.leftTrigger)
        writeFloat(event.rightTrigger)
        writeFloat(event.hatX)
        writeFloat(event.hatY)
        writeInt(event.metaState)
        writeInt(event.source)
        writeInt(event.androidDeviceId)
        writeInt(event.displayId)
    }

    fun decodeJoystick(payload: ByteArray, expectedGenerationId: Long): DecodeResult<AndroidJoystickInjectionEvent> =
        decode(payload, expectedGenerationId) {
            AndroidJoystickInjectionEvent(
                sourceEventTimeUs = readLong(),
                stateSlot = readInt(),
                leftX = readFloat(),
                leftY = readFloat(),
                rightX = readFloat(),
                rightY = readFloat(),
                leftTrigger = readFloat(),
                rightTrigger = readFloat(),
                hatX = readFloat(),
                hatY = readFloat(),
                metaState = readInt(),
                source = readInt(),
                androidDeviceId = readInt(),
                displayId = readInt(),
            )
        }

    fun encodeReset(generationId: Long, scope: InputResetScope, stateSlot: Int, reason: InputResetReason): ByteArray =
        encode(generationId) {
            writeInt(scope.ordinal)
            writeInt(stateSlot)
            writeInt(reason.ordinal)
        }

    fun decodeReset(payload: ByteArray, expectedGenerationId: Long): DecodeResult<ResetRequest> =
        decode(payload, expectedGenerationId) {
            val scope = InputResetScope.entries.getOrNull(readInt()) ?: throw DecodeException("invalid_scope")
            val stateSlot = readInt()
            val reason = InputResetReason.entries.getOrNull(readInt()) ?: throw DecodeException("invalid_reason")
            ResetRequest(scope, stateSlot, reason)
        }

    fun encodeStop(generationId: Long, resetAll: Boolean): ByteArray = encode(generationId) {
        writeBoolean(resetAll)
    }

    fun decodeStop(payload: ByteArray, expectedGenerationId: Long): DecodeResult<Boolean> =
        decode(payload, expectedGenerationId) { readBoolean() }

    fun encodeServiceResult(generationId: Long, resultCode: Int): ByteArray = encode(generationId) {
        writeInt(resultCode)
    }

    fun decodeServiceResult(payload: ByteArray, expectedGenerationId: Long): DecodeResult<Int> =
        decode(payload, expectedGenerationId) { readInt() }

    fun encodeCapabilities(generationId: Long, value: InputInjectionCapabilities): ByteArray = encode(generationId) {
        writeBoolean(value.serviceAvailable)
        writeInt(value.privilegedUid ?: NO_UID)
        writeInt(value.privilegedUidKind.ordinal)
        writeBoolean(value.inputManagerApiResolved)
        writeBoolean(value.asyncInjectionSupported)
        writeBoolean(value.waitForResultSupported)
        writeBoolean(value.targetUidInjectionSupported)
        writeBoolean(value.displayTargetingSupported)
        writeBoolean(value.keyInjectionSupported)
        writeBoolean(value.touchInjectionSupported)
        writeBoolean(value.pointerInjectionSupported)
        writeBoolean(value.joystickInjectionSupported)
        writeInt(value.uhidCapability.ordinal)
        writeBoolean(value.uhidErrno != null)
        value.uhidErrno?.let(::writeInt)
        writeInt(value.maxPointers)
        writeInt(value.maxTrackedStateSlots)
        writeInt(value.lastError.code)
    }

    fun decodeCapabilities(payload: ByteArray, expectedGenerationId: Long): DecodeResult<InputInjectionCapabilities> =
        decode(payload, expectedGenerationId) {
            val serviceAvailable = readBoolean()
            val uid = readInt().takeUnless { it == NO_UID }
            val uidKind = enumAt<PrivilegedUidKind>(readInt(), "invalid_uid_kind")
            val apiResolved = readBoolean()
            val async = readBoolean()
            val wait = readBoolean()
            val targetUid = readBoolean()
            val display = readBoolean()
            val key = readBoolean()
            val touch = readBoolean()
            val pointer = readBoolean()
            val joystick = readBoolean()
            val uhid = enumAt<UhidCapability>(readInt(), "invalid_uhid")
            val hasErrno = readBoolean()
            val errno = if (hasErrno) readInt() else null
            val maxPointers = readInt()
            val maxSlots = readInt()
            val lastError = InputInjectionError.fromCode(readInt())
            InputInjectionCapabilities(
                serviceAvailable = serviceAvailable,
                backend = if (serviceAvailable) InputInjectionBackend.DirectShell else InputInjectionBackend.None,
                privilegedUid = uid,
                privilegedUidKind = uidKind,
                inputManagerApiResolved = apiResolved,
                asyncInjectionSupported = async,
                waitForResultSupported = wait,
                targetUidInjectionSupported = targetUid,
                displayTargetingSupported = display,
                keyInjectionSupported = key,
                touchInjectionSupported = touch,
                pointerInjectionSupported = pointer,
                joystickInjectionSupported = joystick,
                uhidCapability = uhid,
                uhidErrno = errno,
                maxPointers = maxPointers,
                maxTrackedStateSlots = maxSlots,
                lastError = lastError,
            )
        }

    fun encodeSnapshot(generationId: Long, value: InputInjectionSnapshot): ByteArray = encode(generationId) {
        writeInt(value.state.ordinal)
        writeInt(value.privilegedUid ?: NO_UID)
        writeInt(value.privilegedUidKind.ordinal)
        writeInt(value.injectionMode.ordinal)
        writeInt(value.targetUid)
        writeBoolean(value.apiResolved)
        writeBoolean(value.targetUidSupported)
        writeBoolean(value.displayTargetingSupported)
        SNAPSHOT_COUNTERS.forEach { writeLong(it(value)) }
        writeInt(value.trackedStateSlots)
        writeInt(value.activePressedKeys)
        writeInt(value.activeTouchStreams)
        writeInt(value.activePointerButtonStates)
        writeInt(value.activeJoystickStates)
        writeOptionalLong(value.lastSourceEventTimeUs)
        writeOptionalLong(value.lastLocalInjectionTimeMs)
        writeBoolean(value.stateMayRemainInjected)
        writeInt(value.lastError.code)
    }

    fun decodeSnapshot(payload: ByteArray, expectedGenerationId: Long): DecodeResult<InputInjectionSnapshot> =
        decode(payload, expectedGenerationId) {
            val state = enumAt<InputInjectionState>(readInt(), "invalid_state")
            val uid = readInt().takeUnless { it == NO_UID }
            val uidKind = enumAt<PrivilegedUidKind>(readInt(), "invalid_uid_kind")
            val mode = enumAt<InputInjectionMode>(readInt(), "invalid_mode")
            val targetUid = readInt()
            val apiResolved = readBoolean()
            val targetUidSupported = readBoolean()
            val displayTargetingSupported = readBoolean()
            val counters = LongArray(SNAPSHOT_COUNTERS.size) { readLong() }
            InputInjectionSnapshot(
                state = state,
                backend = InputInjectionBackend.DirectShell,
                privilegedUid = uid,
                privilegedUidKind = uidKind,
                injectionMode = mode,
                targetUid = targetUid,
                apiResolved = apiResolved,
                targetUidSupported = targetUidSupported,
                displayTargetingSupported = displayTargetingSupported,
                keyAttempts = counters[0],
                keySubmitted = counters[1],
                keyFailures = counters[2],
                touchAttempts = counters[3],
                touchSubmitted = counters[4],
                touchFailures = counters[5],
                pointerAttempts = counters[6],
                pointerSubmitted = counters[7],
                pointerFailures = counters[8],
                joystickAttempts = counters[9],
                joystickSubmitted = counters[10],
                joystickFailures = counters[11],
                resetRequests = counters[12],
                resetComplete = counters[13],
                resetPartial = counters[14],
                syntheticKeyUps = counters[15],
                syntheticTouchCancels = counters[16],
                syntheticPointerReleases = counters[17],
                syntheticJoystickNeutralEvents = counters[18],
                orphanKeyUps = counters[19],
                invalidTouchSequences = counters[20],
                trackedStateSlots = readInt(),
                activePressedKeys = readInt(),
                activeTouchStreams = readInt(),
                activePointerButtonStates = readInt(),
                activeJoystickStates = readInt(),
                lastSourceEventTimeUs = readOptionalLong(),
                lastLocalInjectionTimeMs = readOptionalLong(),
                permissionFailures = counters[21],
                serviceFailures = counters[22],
                stateMayRemainInjected = readBoolean(),
                lastError = InputInjectionError.fromCode(readInt()),
            )
        }

    data class ResetRequest(
        val scope: InputResetScope,
        val stateSlot: Int,
        val reason: InputResetReason,
    )

    data class DecodeResult<T>(
        val value: T? = null,
        val error: String? = null,
    ) {
        val isSuccess: Boolean
            get() = error == null

        fun validate(validator: (T) -> String?): DecodeResult<T> = when {
            error != null || value == null -> this
            else -> validator(value)?.let { DecodeResult(error = it) } ?: this
        }
    }

    private fun encode(generationId: Long, block: DataOutputStream.() -> Unit): ByteArray {
        require(generationId > 0L) { "generation_must_be_positive" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(generationId)
            output.block()
        }
        return bytes.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "payload_too_large" }
        }
    }

    private fun <T> decode(
        payload: ByteArray,
        expectedGenerationId: Long,
        block: DataInputStream.() -> T,
    ): DecodeResult<T> {
        if (expectedGenerationId <= 0L || payload.size > MAX_PAYLOAD_BYTES) {
            return DecodeResult(error = "payload_bounds")
        }
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) return DecodeResult(error = "magic")
                if (input.readInt() != VERSION) return DecodeResult(error = "version")
                if (input.readLong() != expectedGenerationId) return DecodeResult(error = "generation")
                val value = input.block()
                if (input.available() != 0) return DecodeResult(error = "trailing")
                DecodeResult(value = value)
            }
        } catch (_: EOFException) {
            DecodeResult(error = "truncated")
        } catch (_: DecodeException) {
            DecodeResult(error = "invalid")
        } catch (_: IllegalArgumentException) {
            DecodeResult(error = "invalid")
        }
    }

    private inline fun <reified T : Enum<T>> DataInputStream.enumAt(value: Int, error: String): T {
        val values = enumValues<T>()
        return values.getOrNull(value) ?: throw DecodeException(error)
    }

    private fun DataOutputStream.writeOptionalLong(value: Long?) {
        writeBoolean(value != null)
        value?.let(::writeLong)
    }

    private fun DataInputStream.readOptionalLong(): Long? = if (readBoolean()) readLong() else null

    private class DecodeException(message: String) : IllegalArgumentException(message)

    private const val NO_UID = Int.MIN_VALUE

    private val SNAPSHOT_COUNTERS: List<(InputInjectionSnapshot) -> Long> = listOf(
        { it.keyAttempts },
        { it.keySubmitted },
        { it.keyFailures },
        { it.touchAttempts },
        { it.touchSubmitted },
        { it.touchFailures },
        { it.pointerAttempts },
        { it.pointerSubmitted },
        { it.pointerFailures },
        { it.joystickAttempts },
        { it.joystickSubmitted },
        { it.joystickFailures },
        { it.resetRequests },
        { it.resetComplete },
        { it.resetPartial },
        { it.syntheticKeyUps },
        { it.syntheticTouchCancels },
        { it.syntheticPointerReleases },
        { it.syntheticJoystickNeutralEvents },
        { it.orphanKeyUps },
        { it.invalidTouchSequences },
        { it.permissionFailures },
        { it.serviceFailures },
    )
}
