package io.warpnect.session.integration

import io.warpnect.session.SessionChannelKind
import io.warpnect.session.lifecycle.LifecycleInputSafetyResetReason
import io.warpnect.session.lifecycle.SessionContinuityParticipant
import io.warpnect.session.setup.PreparedSessionBootstrap

/**
 * Owns the selected local runtime components for one RFC-005G prepared generation. It does not
 * own a media queue, a socket, or RFC-005E key material: those stay in the prepared bootstrap.
 */
class SessionPipelineRuntime(
    private val bootstrap: PreparedSessionBootstrap,
    components: List<SessionPipelineComponent>,
) : SessionContinuityParticipant, AutoCloseable {
    private val lock = Any()
    private val components = components.toList()
    private val selectedChannels = bootstrap.channels.map { it.descriptor.kind }.toSet()
    private val started = linkedSetOf<SessionPipelineComponent>()
    private var state = SessionPipelineState.Stopped
    private var lastFailedComponent: String? = null
    private var lastError = SecureSessionIntegrationError.None
    private var inputSafetyReset = false

    init {
        require(bootstrap.channels.map { it.descriptor.kind }.toSet().size == bootstrap.channels.size) {
            "RFC-005G V1 permits one prepared channel per kind"
        }
    }

    fun start(): SecureSessionIntegrationError = synchronized(lock) {
        if (state == SessionPipelineState.Closed) return@synchronized remember(SecureSessionIntegrationError.Closed)
        if (state == SessionPipelineState.Running) return@synchronized SecureSessionIntegrationError.None
        if (state == SessionPipelineState.Starting || state == SessionPipelineState.Stopping) {
            return@synchronized remember(SecureSessionIntegrationError.Busy)
        }
        if (!matchesCommittedChannelPlan()) {
            return@synchronized remember(SecureSessionIntegrationError.PipelinePlanInvalid)
        }
        state = SessionPipelineState.Starting
        lastError = SecureSessionIntegrationError.None
        lastFailedComponent = null
        orderedComponents().forEach { component ->
            val result = component.start()
            if (!result.isSuccess) {
                lastFailedComponent = component.name
                rollbackStarted()
                state = SessionPipelineState.Failed
                return@synchronized remember(result.error)
            }
            started += component
        }
        state = SessionPipelineState.Running
        SecureSessionIntegrationError.None
    }

    fun stop(): SecureSessionIntegrationError = synchronized(lock) {
        if (state == SessionPipelineState.Closed) return@synchronized SecureSessionIntegrationError.Closed
        stopStartedComponents()
        state = SessionPipelineState.Stopped
        lastError = SecureSessionIntegrationError.None
        SecureSessionIntegrationError.None
    }

    fun snapshot(): SessionPipelineSnapshot = synchronized(lock) {
        SessionPipelineSnapshot(
            state = state,
            selectedChannels = selectedChannels.toSet(),
            startedComponents = components.map { component ->
                SessionPipelineComponentSnapshot(
                    component.name,
                    component.phase,
                    component.channelKinds.toSet(),
                    component in started,
                )
            },
            lastFailedComponent = lastFailedComponent,
            lastError = lastError,
        )
    }

    override fun onPathMigrationCommitted() = synchronized(lock) {
        if (state == SessionPipelineState.Running) {
            components.forEach(SessionPipelineComponent::onPathMigrationCommitted)
        }
    }

    override fun onPathMigrationStarting() = synchronized(lock) {
        if (state == SessionPipelineState.Running) {
            components.forEach(SessionPipelineComponent::onPathMigrationStarting)
        }
    }

    override fun onSessionSuspended() = synchronized(lock) {
        if (state == SessionPipelineState.Closed) return@synchronized
        // Generation N must not continue creating packets while RFC-005H has no usable path.
        started.filter { it.phase == SessionPipelineStartPhase.PhysicalSource }
            .asReversed()
            .forEach {
                it.stop()
                started.remove(it)
            }
        components.forEach(SessionPipelineComponent::onSessionSuspended)
    }

    override fun onSessionClosing() = synchronized(lock) {
        if (state != SessionPipelineState.Closed) {
            started.filter { it.phase == SessionPipelineStartPhase.PhysicalSource }
                .asReversed()
                .forEach {
                    it.stop()
                    started.remove(it)
                }
        }
    }

    override fun onSessionReconnected() = synchronized(lock) {
        if (state != SessionPipelineState.Closed) {
            components.forEach(SessionPipelineComponent::onSessionReconnected)
        }
    }

    override fun onInputSafetyReset(reason: LifecycleInputSafetyResetReason) = synchronized(lock) {
        if (inputSafetyReset || state == SessionPipelineState.Closed) return@synchronized
        inputSafetyReset = true
        components.filter { SessionChannelKind.Input in it.channelKinds }
            .forEach(SessionPipelineComponent::onInputSafetyReset)
    }

    override fun close() {
        synchronized(lock) {
            if (state == SessionPipelineState.Closed) return
            stopStartedComponents()
            components.asReversed().forEach(SessionPipelineComponent::close)
            state = SessionPipelineState.Closed
        }
    }

    private fun matchesCommittedChannelPlan(): Boolean {
        val covered = components.flatMap(SessionPipelineComponent::channelKinds).toSet()
        return covered == selectedChannels && components.all { component ->
            component.channelKinds.isNotEmpty() && component.channelKinds.all { it in selectedChannels }
        }
    }

    private fun orderedComponents(): List<SessionPipelineComponent> =
        components.sortedBy(SessionPipelineComponent::phase)

    private fun rollbackStarted() {
        stopStartedComponents()
    }

    private fun stopStartedComponents() {
        if (started.isEmpty()) return
        state = SessionPipelineState.Stopping
        started.toList().asReversed().forEach { component ->
            component.stop()
            started.remove(component)
        }
    }

    private fun remember(error: SecureSessionIntegrationError): SecureSessionIntegrationError {
        lastError = error
        return error
    }
}
