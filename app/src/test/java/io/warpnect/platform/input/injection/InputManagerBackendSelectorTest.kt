package io.warpnect.platform.input.injection

import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionServiceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputManagerBackendSelectorTest {
    @Test
    fun modernFullyQualifiedPathIsPreferredWithoutLegacyLookup() {
        var legacyLookups = 0

        val result = InputManagerBackendSelector().resolve(
            modern = candidate(PrivilegedInputManagerBackend.ModernInputManagerGlobal),
            legacy = {
                legacyLookups += 1
                candidate(PrivilegedInputManagerBackend.LegacyInputManager)
            },
        )

        assertEquals(0, legacyLookups)
        assertEquals(PrivilegedInputManagerBackend.ModernInputManagerGlobal, result.api?.backend)
        assertEquals(PrivilegedInputManagerBackend.ModernInputManagerGlobal, result.diagnostics.selectedBackend)
        assertEquals(InputManagerResolutionFailure.None, result.diagnostics.modernFailure)
        assertEquals(InputManagerResolutionFailure.NotAttempted, result.diagnostics.legacyFailure)
    }

    @Test
    fun partialModernPathFallsBackToFullyQualifiedLegacyPath() {
        val result = InputManagerBackendSelector().resolve(
            modern = unavailable(
                PrivilegedInputManagerBackend.ModernInputManagerGlobal,
                InputManagerResolutionFailure.MethodUnavailable,
            ),
            legacy = { candidate(PrivilegedInputManagerBackend.LegacyInputManager) },
        )

        assertEquals(PrivilegedInputManagerBackend.LegacyInputManager, result.api?.backend)
        assertEquals(PrivilegedInputManagerBackend.LegacyInputManager, result.diagnostics.selectedBackend)
        assertEquals(InputManagerResolutionFailure.MethodUnavailable, result.diagnostics.modernFailure)
        assertEquals(InputManagerResolutionFailure.None, result.diagnostics.legacyFailure)
    }

    @Test
    fun unavailablePathsPreserveBothLocalFailureStages() {
        val result = InputManagerBackendSelector().resolve(
            modern = unavailable(
                PrivilegedInputManagerBackend.ModernInputManagerGlobal,
                InputManagerResolutionFailure.ClassUnavailable,
            ),
            legacy = {
                unavailable(
                    PrivilegedInputManagerBackend.LegacyInputManager,
                    InputManagerResolutionFailure.RequiredCapabilityUnavailable,
                )
            },
        )

        assertNull(result.api)
        assertFalse(result.capabilities.apiResolved)
        assertEquals(InputManagerResolutionFailure.ClassUnavailable, result.diagnostics.modernFailure)
        assertEquals(
            InputManagerResolutionFailure.RequiredCapabilityUnavailable,
            result.diagnostics.legacyFailure,
        )
    }

    @Test
    fun legacyCandidatePreservesUntargetedOnlyCapability() {
        val result = InputManagerBackendSelector().resolve(
            modern = unavailable(
                PrivilegedInputManagerBackend.ModernInputManagerGlobal,
                InputManagerResolutionFailure.ClassUnavailable,
            ),
            legacy = {
                candidate(
                    backend = PrivilegedInputManagerBackend.LegacyInputManager,
                    targetUidInjectionSupported = false,
                )
            },
        )

        assertTrue(result.capabilities.apiResolved)
        assertFalse(result.capabilities.targetUidInjectionSupported)
    }

    @Test
    fun legacyBackendRejectsAnExplicitTargetUidWithoutDroppingIt() {
        assertEquals(
            InputInjectionServiceResult.TargetUidUnsupported,
            targetUidUnsupportedResult(targetUid = 42, targetUidInjectionSupported = false),
        )
        assertNull(targetUidUnsupportedResult(targetUid = -1, targetUidInjectionSupported = false))
    }

    @Test
    fun reflectiveApiCachesTheSelectedBackendForItsLifetime() {
        var calls = 0
        val selected = candidate(PrivilegedInputManagerBackend.LegacyInputManager)
        val reflection = object : InputManagerReflection {
            override fun resolve(): InputManagerReflectionResult {
                calls += 1
                return InputManagerReflectionResult(
                    api = selected.api,
                    capabilities = selected.capabilities,
                    diagnostics = InputManagerResolutionDiagnostics(
                        selectedBackend = PrivilegedInputManagerBackend.LegacyInputManager,
                        modernFailure = InputManagerResolutionFailure.ClassUnavailable,
                        legacyFailure = InputManagerResolutionFailure.None,
                    ),
                )
            }
        }
        val api = ReflectivePrivilegedInputManagerApi(reflection)

        assertTrue(api.resolve().apiResolved)
        assertTrue(api.resolve().apiResolved)
        assertEquals(1, calls)
        assertEquals(PrivilegedInputManagerBackend.LegacyInputManager, api.resolutionDiagnostics().selectedBackend)
        assertEquals(1, calls)
    }

    private fun candidate(
        backend: PrivilegedInputManagerBackend,
        targetUidInjectionSupported: Boolean = true,
    ): InputManagerBackendCandidate {
        val bridge = FakeInputManager()
        val api = ResolvedInputManagerApi(
            backend = backend,
            instance = bridge,
            inject = FakeInputManager::class.java.getDeclaredMethod(
                "inject",
                Any::class.java,
                Int::class.javaPrimitiveType,
            ),
            injectWithTargetUid = if (targetUidInjectionSupported) {
                FakeInputManager::class.java.getDeclaredMethod(
                    "injectWithTargetUid",
                    Any::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            } else {
                null
            },
            displayIdSetter = FakeEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType),
            motionActionButtonSetter = FakeEvent::class.java.getDeclaredMethod(
                "setActionButton",
                Int::class.javaPrimitiveType,
            ),
            asyncMode = 0,
            waitForResultMode = 1,
        )
        return InputManagerBackendCandidate(
            backend = backend,
            api = api,
            capabilities = PrivilegedInputManagerCapabilities(
                apiResolved = true,
                asyncInjectionSupported = true,
                waitForResultSupported = true,
                targetUidInjectionSupported = targetUidInjectionSupported,
                displayTargetingSupported = true,
                lastError = InputInjectionError.None,
            ),
            failure = InputManagerResolutionFailure.None,
        )
    }

    private fun unavailable(
        backend: PrivilegedInputManagerBackend,
        failure: InputManagerResolutionFailure,
    ): InputManagerBackendCandidate = InputManagerBackendCandidate(
        backend = backend,
        api = null,
        capabilities = PrivilegedInputManagerCapabilities(),
        failure = failure,
    )

    private class FakeInputManager {
        @Suppress("unused")
        fun inject(@Suppress("UNUSED_PARAMETER") event: Any, @Suppress("UNUSED_PARAMETER") mode: Int): Boolean = true

        @Suppress("unused")
        fun injectWithTargetUid(
            @Suppress("UNUSED_PARAMETER") event: Any,
            @Suppress("UNUSED_PARAMETER") mode: Int,
            @Suppress("UNUSED_PARAMETER") targetUid: Int,
        ): Boolean = true
    }

    private class FakeEvent {
        @Suppress("unused")
        fun setDisplayId(displayId: Int) = Unit

        @Suppress("unused")
        fun setActionButton(actionButton: Int) = Unit
    }
}
