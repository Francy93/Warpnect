package io.warpnect.platform.input.injection

import android.view.InputEvent
import android.view.MotionEvent
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionServiceResult
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal data class PrivilegedInputManagerCapabilities(
    val apiResolved: Boolean = false,
    val asyncInjectionSupported: Boolean = false,
    val waitForResultSupported: Boolean = false,
    val targetUidInjectionSupported: Boolean = false,
    val displayTargetingSupported: Boolean = false,
    val lastError: InputInjectionError = InputInjectionError.InputApiUnavailable,
)

internal interface PrivilegedInputManagerApi {
    fun resolve(): PrivilegedInputManagerCapabilities

    fun inject(
        event: InputEvent,
        displayId: Int,
        mode: InputInjectionMode,
        targetUid: Int,
        actionButton: Int? = null,
    ): InputInjectionServiceResult
}

/**
 * Cached reflection wrapper for the hidden framework injection path. Resolution occurs only in
 * prepare/query-capabilities paths; `inject` only invokes cached methods.
 */
internal class ReflectivePrivilegedInputManagerApi(
    private val reflection: InputManagerReflection = AndroidInputManagerReflection(),
) : PrivilegedInputManagerApi {
    private var resolved: ResolvedInputManagerApi? = null
    private var capabilities = PrivilegedInputManagerCapabilities()
    private var resolutionAttempted = false

    override fun resolve(): PrivilegedInputManagerCapabilities {
        if (resolutionAttempted) {
            return capabilities
        }
        resolutionAttempted = true
        val result = reflection.resolve()
        resolved = result.api
        capabilities = result.capabilities
        return capabilities
    }

    override fun inject(
        event: InputEvent,
        displayId: Int,
        mode: InputInjectionMode,
        targetUid: Int,
        actionButton: Int?,
    ): InputInjectionServiceResult {
        val api = resolved ?: run {
            resolve()
            resolved
        } ?: return InputInjectionServiceResult.InputApiUnavailable
        if (!api.displayTargetingSupported) {
            return InputInjectionServiceResult.DisplayTargetingUnsupported
        }
        if (targetUid >= 0 && !api.targetUidInjectionSupported) {
            return InputInjectionServiceResult.TargetUidUnsupported
        }
        val modeValue = when (mode) {
            InputInjectionMode.AsyncLowLatency -> api.asyncMode
            InputInjectionMode.WaitForResultDiagnostics -> api.waitForResultMode
        } ?: return InputInjectionServiceResult.InputApiUnavailable
        return try {
            api.displayIdSetter.invoke(event, displayId)
            if (actionButton != null && actionButton != 0) {
                val setter = api.motionActionButtonSetter
                    ?: return InputInjectionServiceResult.InputApiUnavailable
                setter.invoke(event as? MotionEvent ?: return InputInjectionServiceResult.InvalidEvent, actionButton)
            }
            val accepted = if (targetUid >= 0) {
                api.injectWithTargetUid?.invoke(api.instance, event, modeValue, targetUid) as? Boolean
            } else {
                api.inject?.invoke(api.instance, event, modeValue) as? Boolean
            }
            if (accepted != true) {
                InputInjectionServiceResult.InjectionRejected
            } else if (mode == InputInjectionMode.AsyncLowLatency) {
                InputInjectionServiceResult.SubmittedAsync
            } else {
                InputInjectionServiceResult.AcceptedWaitForResult
            }
        } catch (throwable: Throwable) {
            mapInvocationFailure(throwable)
        }
    }

    private fun mapInvocationFailure(throwable: Throwable): InputInjectionServiceResult = when (
        unwrapInvocationFailure(throwable)
    ) {
        is SecurityException -> InputInjectionServiceResult.InjectEventsPermissionDenied
        is IllegalArgumentException -> InputInjectionServiceResult.InvalidEvent
        else -> InputInjectionServiceResult.UnknownFailure
    }
}

internal interface InputManagerReflection {
    fun resolve(): InputManagerReflectionResult
}

internal data class InputManagerReflectionResult(
    val api: ResolvedInputManagerApi?,
    val capabilities: PrivilegedInputManagerCapabilities,
)

internal data class ResolvedInputManagerApi(
    val instance: Any,
    val inject: Method,
    val injectWithTargetUid: Method?,
    val displayIdSetter: Method,
    val motionActionButtonSetter: Method?,
    val asyncMode: Int?,
    val waitForResultMode: Int?,
) {
    val targetUidInjectionSupported: Boolean
        get() = injectWithTargetUid != null

    val displayTargetingSupported: Boolean
        get() = true
}

internal class AndroidInputManagerReflection : InputManagerReflection {
    // The UserService resolves this hidden API under Shizuku/Sui identity during cold-path setup.
    @Suppress("BlockedPrivateApi")
    override fun resolve(): InputManagerReflectionResult = try {
        val inputEventClass = Class.forName("android.view.InputEvent")
        val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
        val global = globalClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
            .invoke(null) ?: return unavailable()
        val inject = globalClass.getDeclaredMethod(
            "injectInputEvent",
            inputEventClass,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val injectWithTargetUid = runCatching {
            globalClass.getDeclaredMethod(
                "injectInputEvent",
                inputEventClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
        val displaySetter = inputEventClass.getDeclaredMethod(
            "setDisplayId",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val actionButtonSetter = runCatching {
            MotionEvent::class.java.getDeclaredMethod(
                "setActionButton",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        val asyncMode = staticInt(inputManagerClass, "INJECT_INPUT_EVENT_MODE_ASYNC")
        val waitForResultMode = staticInt(inputManagerClass, "INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT")
        val api = ResolvedInputManagerApi(
            instance = global,
            inject = inject,
            injectWithTargetUid = injectWithTargetUid,
            displayIdSetter = displaySetter,
            motionActionButtonSetter = actionButtonSetter,
            asyncMode = asyncMode,
            waitForResultMode = waitForResultMode,
        )
        InputManagerReflectionResult(
            api = api,
            capabilities = PrivilegedInputManagerCapabilities(
                apiResolved = true,
                asyncInjectionSupported = asyncMode != null,
                waitForResultSupported = waitForResultMode != null,
                targetUidInjectionSupported = api.targetUidInjectionSupported,
                displayTargetingSupported = api.displayTargetingSupported,
                lastError = InputInjectionError.None,
            ),
        )
    } catch (_: Throwable) {
        unavailable()
    }

    private fun staticInt(type: Class<*>, fieldName: String): Int? = runCatching {
        type.getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)
    }.getOrNull()

    private fun unavailable(): InputManagerReflectionResult = InputManagerReflectionResult(
        api = null,
        capabilities = PrivilegedInputManagerCapabilities(),
    )
}

private fun unwrapInvocationFailure(throwable: Throwable): Throwable {
    var current = throwable
    while (current is InvocationTargetException && current.targetException != null) {
        current = current.targetException
    }
    return current
}
