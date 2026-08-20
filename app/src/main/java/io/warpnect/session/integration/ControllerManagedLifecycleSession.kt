package io.warpnect.session.integration

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.lifecycle.DisconnectReason
import io.warpnect.session.lifecycle.SessionLifecycleController
import io.warpnect.session.lifecycle.SessionLifecycleError
import io.warpnect.session.setup.PreparedSessionBootstrap

/**
 * Thin adapter around the RFC-005H controller. The provider is responsible for supplying its
 * existing path monitor, migration adapter and reconnect delegate; this wrapper adds no lifecycle
 * state machine and forwards the pipeline continuity participant supplied by RFC-005I.
 */
fun interface SessionLifecycleControllerProvider {
    fun create(
        bootstrap: PreparedSessionBootstrap,
        pipeline: SessionPipelineRuntime,
        listener: SessionLifecycleRuntimeListener,
    ): SessionLifecycleController
}

class ControllerManagedLifecycleSessionFactory(
    private val provider: SessionLifecycleControllerProvider,
) : SessionLifecycleSessionFactory {
    override fun create(
        bootstrap: PreparedSessionBootstrap,
        pipeline: SessionPipelineRuntime,
        listener: SessionLifecycleRuntimeListener,
    ): SessionLifecycleFactoryResult = try {
        SessionLifecycleFactoryResult(
            SecureSessionIntegrationError.None,
            ControllerManagedLifecycleSession(provider.create(bootstrap, pipeline, listener)),
        )
    } catch (_: RuntimeException) {
        SessionLifecycleFactoryResult(SecureSessionIntegrationError.LifecycleStartFailed)
    }
}

class ControllerManagedLifecycleSession(
    private val controller: SessionLifecycleController,
) : ManagedLifecycleSession {
    override val sessionId: SessionId
        get() = controller.snapshot().sessionId
    override val generation: SessionGeneration
        get() = controller.snapshot().generation
    override val activePathKind: NetworkPathKind?
        get() = controller.snapshot().activePathKind

    override fun start(): SecureSessionIntegrationError = controller.start().toIntegrationError()

    override fun gracefulDisconnect(reason: DisconnectReason) {
        controller.gracefulDisconnect(reason)
    }

    override fun advance() {
        controller.advance()
    }

    override fun acceptFreshGeneration(bootstrap: PreparedSessionBootstrap): SecureSessionIntegrationError =
        controller.onFreshGenerationPrepared(bootstrap).toIntegrationError()

    override fun close() {
        controller.close()
    }
}

private fun SessionLifecycleError.toIntegrationError(): SecureSessionIntegrationError = when (this) {
    SessionLifecycleError.None -> SecureSessionIntegrationError.None
    SessionLifecycleError.Closed -> SecureSessionIntegrationError.Closed
    else -> SecureSessionIntegrationError.LifecycleStartFailed
}
