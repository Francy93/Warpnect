package io.warpnect.debug.input

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.platform.input.injection.ReflectivePrivilegedInputManagerApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Debug-only Shizuku UserService. It reports reflection stages individually and injects only
 * F1, touch, pointer-hover, and joystick-motion events into the foreground
 * [InputApiForensicsActivity] owned by this application.
 */
class InputApiForensicsUserService : IInputApiForensicsService.Stub() {
    private val api = InputApiForensics()
    private val productionResolver = ReflectivePrivilegedInputManagerApi()

    override fun inspect(): Bundle = api.inspect().apply {
        val production = productionResolver.resolve()
        val diagnostics = productionResolver.resolutionDiagnostics()
        putBoolean("production_resolver_api_resolved", production.apiResolved)
        putString("production_resolver_last_error", production.lastError.name)
        putString("production_resolver_backend", diagnostics.selectedBackend?.name ?: "NONE")
        putString("production_resolver_modern_failure", diagnostics.modernFailure.name)
        putString("production_resolver_legacy_failure", diagnostics.legacyFailure.name)
        putBoolean("production_resolver_async", production.asyncInjectionSupported)
        putBoolean("production_resolver_display_targeting", production.displayTargetingSupported)
        putInt(KEY_PID, Process.myPid())
        putInt(KEY_UID, Process.myUid())
        putString(KEY_SERVICE_CLASS_LOADER, classLoaderName(javaClass.classLoader))
        putString(KEY_INPUT_MANAGER_CLASS_LOADER, frameworkClassLoader("android.hardware.input.InputManager"))
        putString(
            KEY_INPUT_MANAGER_GLOBAL_CLASS_LOADER,
            frameworkClassLoader("android.hardware.input.InputManagerGlobal"),
        )
    }

    override fun injectTestKey(displayId: Int): Bundle = api.injectTestKey(displayId)

    override fun injectTestTouch(displayId: Int, xPx: Float, yPx: Float): Bundle =
        api.injectTestTouch(displayId, xPx, yPx)

    override fun injectTestPointer(displayId: Int, xPx: Float, yPx: Float): Bundle =
        api.injectTestPointer(displayId, xPx, yPx)

    override fun injectTestJoystick(displayId: Int): Bundle = api.injectTestJoystick(displayId)

    @Suppress("unused")
    fun destroy() = Unit

    private companion object {
        const val KEY_PID = "process_pid"
        const val KEY_UID = "process_uid"
        const val KEY_SERVICE_CLASS_LOADER = "service_class_loader"
        const val KEY_INPUT_MANAGER_CLASS_LOADER = "input_manager_class_loader"
        const val KEY_INPUT_MANAGER_GLOBAL_CLASS_LOADER = "input_manager_global_class_loader"
    }
}

private fun frameworkClassLoader(className: String): String = runCatching {
    classLoaderName(Class.forName(className).classLoader)
}.getOrElse { "CLASS_UNAVAILABLE" }

private fun classLoaderName(classLoader: ClassLoader?): String = classLoader?.javaClass?.name ?: "BOOT_CLASS_LOADER"

private class InputApiForensics {
    private val candidates by lazy { listOf(resolve("InputManagerGlobal"), resolve("InputManager")) }

    fun inspect(): Bundle = Bundle().apply {
        candidates.forEach { candidate ->
            putString("${candidate.name}.status", candidate.status)
            putBoolean("${candidate.name}.two_arg_inject", candidate.inject != null)
            putBoolean("${candidate.name}.three_arg_inject", candidate.targetUidInject != null)
            putBoolean("${candidate.name}.async_mode", candidate.asyncMode != null)
            putBoolean("${candidate.name}.wait_for_result_mode", candidate.waitForResultMode != null)
            putBoolean("${candidate.name}.display_targeting", candidate.displayIdSetter != null)
            putBoolean("${candidate.name}.motion_action_button", candidate.motionActionButtonSetter != null)
        }
        putString("selected_path", selected()?.name ?: "NONE")
    }

    fun injectTestKey(displayId: Int): Bundle {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_F1,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return inject("key", displayId, down, up)
    }

    fun injectTestTouch(displayId: Int, xPx: Float, yPx: Float): Bundle {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            xPx,
            yPx,
            1f,
            1f,
            0,
            1f,
            1f,
            0,
            0,
        )
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            xPx,
            yPx,
            1f,
            1f,
            0,
            1f,
            1f,
            0,
            0,
        )
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return try {
            inject("touch", displayId, down, up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun injectTestPointer(displayId: Int, xPx: Float, yPx: Float): Bundle {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_HOVER_MOVE,
            xPx,
            yPx,
            0f,
            0f,
            0,
            1f,
            1f,
            0,
            0,
        ).apply { source = InputDevice.SOURCE_MOUSE }
        return try {
            inject("pointer", displayId, event, null)
        } finally {
            event.recycle()
        }
    }

    fun injectTestJoystick(displayId: Int): Bundle {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_MOVE,
            0f,
            0f,
            0f,
            0f,
            0,
            1f,
            1f,
            0,
            0,
        ).apply { source = InputDevice.SOURCE_JOYSTICK }
        return try {
            inject("joystick", displayId, event, null)
        } finally {
            event.recycle()
        }
    }

    private fun inject(kind: String, displayId: Int, down: InputEvent, up: InputEvent?): Bundle {
        val candidate = selected() ?: return result(kind, "NO_SUPPORTED_PATH")
        return try {
            candidate.displayIdSetter!!.invoke(down, displayId)
            up?.let { candidate.displayIdSetter.invoke(it, displayId) }
            val downAccepted = candidate.inject!!.invoke(
                candidate.instance,
                down,
                candidate.waitForResultMode,
            ) as? Boolean
            val upAccepted = up?.let {
                candidate.inject.invoke(
                    candidate.instance,
                    it,
                    candidate.waitForResultMode,
                ) as? Boolean
            }
            result(kind, "INVOKED", candidate).apply {
                putBoolean("down_accepted", downAccepted == true)
                putBoolean("up_accepted", up == null || upAccepted == true)
            }
        } catch (throwable: Throwable) {
            result(kind, "INVOKE_${stage(throwable)}", candidate)
        }
    }

    private fun result(kind: String, status: String, candidate: Candidate? = null): Bundle = Bundle().apply {
        putString("kind", kind)
        putString("status", status)
        putString("path", candidate?.name ?: "NONE")
        putInt("process_pid", Process.myPid())
        putInt("process_uid", Process.myUid())
    }

    private fun selected(): Candidate? = candidates.firstOrNull { it.usable }

    @Suppress("BlockedPrivateApi")
    private fun resolve(name: String): Candidate {
        val className = "android.hardware.input.$name"
        return try {
            val type = Class.forName(className)
            val instance = type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                ?: return Candidate(name, "GET_INSTANCE_NULL")
            val inputEvent = Class.forName("android.view.InputEvent")
            val inject = type.getDeclaredMethod(
                "injectInputEvent",
                inputEvent,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val targetUidInject = runCatching {
                type.getDeclaredMethod(
                    "injectInputEvent",
                    inputEvent,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
            }.getOrNull()
            val displayIdSetter = inputEvent.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val manager = Class.forName("android.hardware.input.InputManager")
            val actionButtonSetter = runCatching {
                MotionEvent::class.java.getDeclaredMethod(
                    "setActionButton",
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
            }.getOrNull()
            val async = staticInt(manager, "INJECT_INPUT_EVENT_MODE_ASYNC")
            val waitForResult = staticInt(manager, "INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT")
            Candidate(
                name = name,
                status = if (async != null && waitForResult != null) "RESOLVED" else "MODE_UNAVAILABLE",
                instance = instance,
                inject = inject,
                targetUidInject = targetUidInject,
                displayIdSetter = displayIdSetter,
                motionActionButtonSetter = actionButtonSetter,
                asyncMode = async,
                waitForResultMode = waitForResult,
            )
        } catch (throwable: Throwable) {
            Candidate(name, stage(throwable))
        }
    }

    private fun staticInt(type: Class<*>, field: String): Int? = runCatching {
        type.getDeclaredField(field).apply { isAccessible = true }.getInt(null)
    }.getOrNull()
}

private data class Candidate(
    val name: String,
    val status: String,
    val instance: Any? = null,
    val inject: Method? = null,
    val targetUidInject: Method? = null,
    val displayIdSetter: Method? = null,
    val motionActionButtonSetter: Method? = null,
    val asyncMode: Int? = null,
    val waitForResultMode: Int? = null,
) {
    val usable: Boolean
        get() =
            status == "RESOLVED" &&
                instance != null &&
                inject != null &&
                displayIdSetter != null &&
                waitForResultMode != null
}

private fun stage(throwable: Throwable): String {
    var cause = throwable
    while (cause is InvocationTargetException && cause.targetException != null) {
        cause = cause.targetException
    }
    return when (cause) {
        is ClassNotFoundException -> "CLASS_UNAVAILABLE"
        is NoSuchMethodException -> "METHOD_UNAVAILABLE"
        is IllegalAccessException,
        is SecurityException,
        -> "ACCESS_DENIED"
        is IllegalArgumentException -> "ARGUMENT_REJECTED"
        else -> "${cause::class.java.simpleName.uppercase()}"
    }
}
