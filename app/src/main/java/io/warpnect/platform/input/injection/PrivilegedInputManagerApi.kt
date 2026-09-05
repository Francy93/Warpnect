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
    private var diagnostics = InputManagerResolutionDiagnostics()
    private var resolutionAttempted = false

    override fun resolve(): PrivilegedInputManagerCapabilities {
        if (resolutionAttempted) {
            return capabilities
        }
        resolutionAttempted = true
        val result = reflection.resolve()
        resolved = result.api
        capabilities = result.capabilities
        diagnostics = result.diagnostics
        return capabilities
    }

    internal fun resolutionDiagnostics(): InputManagerResolutionDiagnostics {
        resolve()
        return diagnostics
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
        targetUidUnsupportedResult(targetUid, api.targetUidInjectionSupported)?.let { return it }
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

internal fun targetUidUnsupportedResult(
    targetUid: Int,
    targetUidInjectionSupported: Boolean,
): InputInjectionServiceResult? = InputInjectionServiceResult.TargetUidUnsupported.takeIf {
    targetUid >= 0 && !targetUidInjectionSupported
}

internal interface InputManagerReflection {
    fun resolve(): InputManagerReflectionResult
}

internal data class InputManagerReflectionResult(
    val api: ResolvedInputManagerApi?,
    val capabilities: PrivilegedInputManagerCapabilities,
    val diagnostics: InputManagerResolutionDiagnostics = InputManagerResolutionDiagnostics(),
)

internal enum class PrivilegedInputManagerBackend {
    ModernInputManagerGlobal,
    LegacyInputManager,
}

internal enum class InputManagerResolutionFailure {
    NotAttempted,
    None,
    ClassUnavailable,
    MethodUnavailable,
    InstanceUnavailable,
    RequiredCapabilityUnavailable,
    AccessDenied,
    InvocationFailed,
    Unknown,
}

internal data class InputManagerResolutionDiagnostics(
    val selectedBackend: PrivilegedInputManagerBackend? = null,
    val modernFailure: InputManagerResolutionFailure = InputManagerResolutionFailure.NotAttempted,
    val legacyFailure: InputManagerResolutionFailure = InputManagerResolutionFailure.NotAttempted,
)

internal data class InputManagerBackendCandidate(
    val backend: PrivilegedInputManagerBackend,
    val api: ResolvedInputManagerApi?,
    val capabilities: PrivilegedInputManagerCapabilities,
    val failure: InputManagerResolutionFailure,
)

/** Selects one fully-qualified framework path during UserService cold-path setup. */
internal class InputManagerBackendSelector {
    fun resolve(
        modern: InputManagerBackendCandidate,
        legacy: () -> InputManagerBackendCandidate,
    ): InputManagerReflectionResult {
        if (modern.api != null && modern.capabilities.apiResolved) {
            return selected(
                candidate = modern,
                modernFailure = InputManagerResolutionFailure.None,
                legacyFailure = InputManagerResolutionFailure.NotAttempted,
            )
        }

        val legacyCandidate = legacy()
        if (legacyCandidate.api != null && legacyCandidate.capabilities.apiResolved) {
            return selected(
                candidate = legacyCandidate,
                modernFailure = modern.failure,
                legacyFailure = InputManagerResolutionFailure.None,
            )
        }

        return InputManagerReflectionResult(
            api = null,
            capabilities = PrivilegedInputManagerCapabilities(),
            diagnostics = InputManagerResolutionDiagnostics(
                modernFailure = modern.failure,
                legacyFailure = legacyCandidate.failure,
            ),
        )
    }

    private fun selected(
        candidate: InputManagerBackendCandidate,
        modernFailure: InputManagerResolutionFailure,
        legacyFailure: InputManagerResolutionFailure,
    ): InputManagerReflectionResult = InputManagerReflectionResult(
        api = candidate.api,
        capabilities = candidate.capabilities,
        diagnostics = InputManagerResolutionDiagnostics(
            selectedBackend = candidate.backend,
            modernFailure = modernFailure,
            legacyFailure = legacyFailure,
        ),
    )
}

internal data class ResolvedInputManagerApi(
    val backend: PrivilegedInputManagerBackend,
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

@Suppress("BlockedPrivateApi", "DiscouragedPrivateApi")
internal class AndroidInputManagerReflection : InputManagerReflection {
    private val selector = InputManagerBackendSelector()

    // The UserService resolves this hidden API under Shizuku/Sui identity during cold-path setup.
    override fun resolve(): InputManagerReflectionResult = selector.resolve(
        modern = resolveCandidate(
            backend = PrivilegedInputManagerBackend.ModernInputManagerGlobal,
            providerClassName = "android.hardware.input.InputManagerGlobal",
        ),
        legacy = {
            resolveCandidate(
                backend = PrivilegedInputManagerBackend.LegacyInputManager,
                providerClassName = "android.hardware.input.InputManager",
            )
        },
    )

    private fun resolveCandidate(
        backend: PrivilegedInputManagerBackend,
        providerClassName: String,
    ): InputManagerBackendCandidate {
        return try {
            val inputEventClass = Class.forName("android.view.InputEvent")
            val providerClass = Class.forName(providerClassName)
            val instance = providerClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
                .invoke(null) ?: return unavailable(backend, InputManagerResolutionFailure.InstanceUnavailable)
            val inject = providerClass.getDeclaredMethod(
                "injectInputEvent",
                inputEventClass,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val injectWithTargetUid = runCatching {
                providerClass.getDeclaredMethod(
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
            if (asyncMode == null || waitForResultMode == null || actionButtonSetter == null) {
                return unavailable(backend, InputManagerResolutionFailure.RequiredCapabilityUnavailable)
            }
            val api = ResolvedInputManagerApi(
                backend = backend,
                instance = instance,
                inject = inject,
                injectWithTargetUid = injectWithTargetUid,
                displayIdSetter = displaySetter,
                motionActionButtonSetter = actionButtonSetter,
                asyncMode = asyncMode,
                waitForResultMode = waitForResultMode,
            )
            InputManagerBackendCandidate(
                backend = backend,
                api = api,
                capabilities = PrivilegedInputManagerCapabilities(
                    apiResolved = true,
                    asyncInjectionSupported = true,
                    waitForResultSupported = true,
                    targetUidInjectionSupported = api.targetUidInjectionSupported,
                    displayTargetingSupported = api.displayTargetingSupported,
                    lastError = InputInjectionError.None,
                ),
                failure = InputManagerResolutionFailure.None,
            )
        } catch (throwable: Throwable) {
            unavailable(backend, resolutionFailure(throwable))
        }
    }

    private fun staticInt(type: Class<*>, fieldName: String): Int? = runCatching {
        type.getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)
    }.getOrNull()

    private fun unavailable(
        backend: PrivilegedInputManagerBackend,
        failure: InputManagerResolutionFailure,
    ): InputManagerBackendCandidate = InputManagerBackendCandidate(
        backend = backend,
        api = null,
        capabilities = PrivilegedInputManagerCapabilities(),
        failure = failure,
    )

    private fun resolutionFailure(throwable: Throwable): InputManagerResolutionFailure = when (
        unwrapInvocationFailure(throwable)
    ) {
        is ClassNotFoundException -> InputManagerResolutionFailure.ClassUnavailable
        is NoSuchMethodException -> InputManagerResolutionFailure.MethodUnavailable
        is IllegalAccessException,
        is SecurityException,
        -> InputManagerResolutionFailure.AccessDenied
        is InvocationTargetException -> InputManagerResolutionFailure.InvocationFailed
        else -> InputManagerResolutionFailure.Unknown
    }
}

private fun unwrapInvocationFailure(throwable: Throwable): Throwable {
    var current = throwable
    while (current is InvocationTargetException && current.targetException != null) {
        current = current.targetException
    }
    return current
}
