package io.warpnect.platform.input.mapping

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import io.warpnect.input.model.InputDeviceKind
import java.util.concurrent.atomic.AtomicReferenceArray

enum class AndroidTargetDeviceResolutionPolicy {
    SyntheticDefault,
    PreferSourceCompatible,
    RequireSourceCompatible,
}

data class AndroidTargetDeviceResolution(
    val deviceId: Int? = null,
    val compatible: Boolean = false,
)

interface TargetInputDeviceResolver : AutoCloseable {
    fun resolve(
        deviceKind: InputDeviceKind,
        policy: AndroidTargetDeviceResolutionPolicy,
    ): AndroidTargetDeviceResolution

    fun invalidate()
}

/**
 * Resolves a target-local Android device identity without putting that identity on the wire.
 * Compatible-device scans occur only after cache invalidation, never for each mapped event.
 */
class AndroidTargetInputDeviceResolver(
    context: Context,
) : TargetInputDeviceResolver, InputManager.InputDeviceListener {
    private val inputManager = context.applicationContext.getSystemService(InputManager::class.java)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val cachedResolutions = AtomicReferenceArray<AndroidTargetDeviceResolution?>(
        InputDeviceKind.entries.size * AndroidTargetDeviceResolutionPolicy.entries.size,
    )

    @Volatile
    private var closed = false

    init {
        inputManager?.registerInputDeviceListener(this, callbackHandler)
    }

    override fun resolve(
        deviceKind: InputDeviceKind,
        policy: AndroidTargetDeviceResolutionPolicy,
    ): AndroidTargetDeviceResolution {
        if (closed || deviceKind == InputDeviceKind.Unknown) return AndroidTargetDeviceResolution()
        if (policy == AndroidTargetDeviceResolutionPolicy.SyntheticDefault) {
            return AndroidTargetDeviceResolution(deviceId = 0, compatible = false)
        }
        val cacheIndex = cacheIndex(deviceKind, policy)
        cachedResolutions.get(cacheIndex)?.let { return it }
        val resolved = inputManager?.inputDeviceIds
            ?.sorted()
            ?.firstOrNull { id -> inputManager.getInputDevice(id).supportsKind(deviceKind) }
        if (resolved != null) {
            return AndroidTargetDeviceResolution(resolved, compatible = true).also {
                cachedResolutions.set(cacheIndex, it)
            }
        }
        val fallback = if (policy == AndroidTargetDeviceResolutionPolicy.PreferSourceCompatible) {
            AndroidTargetDeviceResolution(deviceId = 0, compatible = false)
        } else {
            AndroidTargetDeviceResolution()
        }
        cachedResolutions.set(cacheIndex, fallback)
        return fallback
    }

    override fun invalidate() {
        for (index in 0 until cachedResolutions.length()) {
            cachedResolutions.set(index, null)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = invalidate()

    override fun onInputDeviceRemoved(deviceId: Int) = invalidate()

    override fun onInputDeviceChanged(deviceId: Int) = invalidate()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { inputManager?.unregisterInputDeviceListener(this) }
        invalidate()
    }

    private fun InputDevice?.supportsKind(kind: InputDeviceKind): Boolean = when (kind) {
        InputDeviceKind.Keyboard -> this?.supportsSource(InputDevice.SOURCE_KEYBOARD) == true
        InputDeviceKind.Touchscreen -> this?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true
        InputDeviceKind.Mouse -> this?.supportsSource(InputDevice.SOURCE_MOUSE) == true
        InputDeviceKind.Gamepad -> this?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true ||
            this?.supportsSource(InputDevice.SOURCE_JOYSTICK) == true
        InputDeviceKind.Stylus -> this?.supportsSource(InputDevice.SOURCE_STYLUS) == true
        InputDeviceKind.Touchpad -> this?.supportsSource(InputDevice.SOURCE_TOUCHPAD) == true
        InputDeviceKind.Unknown -> false
    }

    private fun cacheIndex(kind: InputDeviceKind, policy: AndroidTargetDeviceResolutionPolicy): Int =
        kind.ordinal * AndroidTargetDeviceResolutionPolicy.entries.size + policy.ordinal
}
