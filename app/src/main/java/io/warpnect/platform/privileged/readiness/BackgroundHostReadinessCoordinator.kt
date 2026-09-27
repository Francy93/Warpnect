package io.warpnect.platform.privileged.readiness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serialized non-Main owner for bounded readiness work. */
internal fun interface HostReadinessDispatcher {
    fun dispatch(action: () -> Unit): Boolean
}

/**
 * Platform adapter owned by the Session composition. Observation is side-effect free; Host
 * preparation is allowed to acquire the existing runtime and freeze the existing RFC-006I plan.
 */
internal interface HostReadinessWork {
    fun observeVolatileRuntime(generation: Long): PrivilegedRuntimeStateSnapshot

    /**
     * Runs on the coordinator's non-Main dispatcher. Implementations may load compatibility
     * evidence, but must not acquire a provider or start a feature runtime here.
     */
    fun observeBackgroundReadiness(generation: Long): BackgroundHostReadinessObservation =
        BackgroundHostReadinessObservation(observeVolatileRuntime(generation))

    fun prepareForHost(generation: Long, runtimeState: PrivilegedRuntimeStateSnapshot): HostReadinessState

    /** Releases only resources acquired by a stale or abandoned Host preparation. */
    fun releasePreparedResources()
}

/**
 * One generation-scoped readiness owner shared by background observation and Enable Host.
 *
 * Startup performs only [HostReadinessWork.observeBackgroundReadiness]. A user Host request
 * upgrades that same flight to the existing acquisition/admission path, so it cannot bootstrap a
 * second DirectShell runtime or start a second staged Shizuku qualification concurrently.
 */
internal class BackgroundHostReadinessCoordinator(
    private val dispatcher: HostReadinessDispatcher,
    private val work: HostReadinessWork,
    private val onStateChanged: (HostReadinessState) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val _state = MutableStateFlow<HostReadinessState>(HostReadinessState.NotStarted)
    val state: StateFlow<HostReadinessState> = _state.asStateFlow()
    private val _uiState = MutableStateFlow(HostReadinessUiState())
    val uiState: StateFlow<HostReadinessUiState> = _uiState.asStateFlow()

    private var nextGeneration = 0L
    private var inFlight: Flight? = null
    private var latestRuntimeState: PrivilegedRuntimeStateSnapshot? = null
    private var closed = false
    private var completionDispatched = false

    /** Begins background observation without waiting for, or blocking, the UI owner. */
    fun startBackgroundReadiness(): Boolean {
        val generation = synchronized(lock) {
            if (
                closed ||
                inFlight != null ||
                (_state.value !is HostReadinessState.NotStarted && _state.value !is HostReadinessState.Stale)
            ) {
                return@synchronized null
            }
            newFlightLocked(hostRequested = false)
        } ?: return false
        return dispatchObservation(generation)
    }

    /**
     * Requests Host preparation. Repeated callers join the active generation and only one
     * completion callback may start Host discovery.
     */
    fun requestHostPreparation(onPrepared: (PreparedPrivilegedCapabilityPlan) -> Unit): Boolean {
        val action = synchronized(lock) {
            if (closed) return@synchronized DispatchAction.None
            val active = inFlight
            if (active != null) {
                active.hostRequested = true
                if (active.onPrepared == null) active.onPrepared = onPrepared
                publishUiLocked(hostActionVisible = true)
                return@synchronized DispatchAction.None
            }
            if (_state.value is HostReadinessState.Ready && completionDispatched) {
                publishUiLocked(hostActionVisible = true)
                return@synchronized DispatchAction.None
            }
            val generation = newFlightLocked(hostRequested = true, onPrepared = onPrepared)
            DispatchAction.Observe(generation)
        }
        return when (action) {
            is DispatchAction.Observe -> dispatchObservation(action.generation)
            is DispatchAction.Prepare -> error("host_readiness_prepare_cannot_be_dispatched_before_observation")
            DispatchAction.None -> true
        }
    }

    /** Marks current evidence stale. An obsolete completion is released rather than published. */
    fun invalidate() {
        val shouldRelease = synchronized(lock) {
            if (closed) return@synchronized false
            nextGeneration += 1L
            val wasFrozen = (_state.value as? HostReadinessState.Ready)
                ?.prepared
                ?.commitState == HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION
            inFlight = null
            completionDispatched = false
            val snapshot = latestRuntimeState
            if (snapshot != null) {
                publishLocked(
                    HostReadinessState.Stale(
                        generation = nextGeneration,
                        runtimeState = snapshot.copy(generation = nextGeneration),
                        reason = PrivilegedRuntimeFailureReason.UNKNOWN,
                    ),
                    hostActionVisible = false,
                )
            } else {
                publishLocked(HostReadinessState.NotStarted, hostActionVisible = false)
            }
            wasFrozen
        }
        if (shouldRelease) work.releasePreparedResources()
    }

    /** Dismisses only presentation; it never erases the underlying structured result. */
    fun dismissHostActionState() = synchronized(lock) {
        if (!closed) publishUiLocked(hostActionVisible = false)
    }

    /** Releases an acquired plan after Host admission itself could not start discovery. */
    fun abandonPreparedHost() = invalidate()

    /** A volatile setting change invalidates advisory work, never the active Host contract. */
    fun refreshBackgroundReadiness(): Boolean {
        val generation = synchronized(lock) {
            if (closed || inFlight?.hostRequested == true ||
                (_state.value as? HostReadinessState.Ready)?.prepared?.commitState ==
                HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION
            ) {
                return@synchronized null
            }
            newFlightLocked(hostRequested = false)
        } ?: return false
        return dispatchObservation(generation)
    }

    override fun close() {
        val shouldRelease = synchronized(lock) {
            if (closed) return@synchronized false
            closed = true
            nextGeneration += 1L
            val release = (_state.value as? HostReadinessState.Ready)
                ?.prepared
                ?.commitState == HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION
            inFlight = null
            completionDispatched = false
            publishLocked(HostReadinessState.NotStarted, hostActionVisible = false)
            release
        }
        if (shouldRelease) work.releasePreparedResources()
    }

    private fun dispatchObservation(generation: Long): Boolean {
        if (dispatcher.dispatch { observe(generation) }) return true
        publishDispatchFailure(generation)
        return false
    }

    private fun observe(generation: Long) {
        if (!isCurrentFlight(generation)) return
        val observation = runCatching { work.observeBackgroundReadiness(generation) }.getOrElse {
            completeObservationFailure(generation)
            return
        }
        val runtimeState = observation.runtimeState
        require(runtimeState.generation == generation) { "runtime_readiness_observation_generation_mismatch" }

        val next = synchronized(lock) {
            val flight = inFlight
            if (closed || flight?.generation != generation) return@synchronized DispatchAction.None
            latestRuntimeState = runtimeState
            val observedState = backgroundStateFor(
                runtimeState = runtimeState,
                preparedPlan = observation.preparedPlan,
                evidenceSource = observation.evidenceSource,
            )
            if (flight.hostRequested &&
                (observedState is HostReadinessState.Stale || observedState is HostReadinessState.Ready)
            ) {
                flight.stage = FlightStage.PREPARING_HOST
                publishLocked(
                    HostReadinessState.Checking(generation, HostReadinessPhase.PREPARING_HOST),
                    hostActionVisible = true,
                )
                DispatchAction.Prepare(generation, runtimeState)
            } else {
                inFlight = null
                publishLocked(observedState, hostActionVisible = flight.hostRequested)
                DispatchAction.None
            }
        }
        if (next is DispatchAction.Prepare) prepare(next.generation, next.runtimeState)
    }

    private fun prepare(generation: Long, runtimeState: PrivilegedRuntimeStateSnapshot) {
        if (!isCurrentFlight(generation)) return
        val result = runCatching {
            work.prepareForHost(generation, runtimeState).also { prepared ->
                require(
                    prepared !is HostReadinessState.Ready ||
                        prepared.prepared.commitState == HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION,
                ) { "host_readiness_advisory_cannot_admit_host" }
            }
        }.getOrElse {
            HostReadinessState.Failed(
                generation = generation,
                runtimeState = runtimeState,
                reason = PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED,
            )
        }
        require(result.generationOrNull == generation) { "host_readiness_preparation_generation_mismatch" }

        var releaseStaleResult = false
        var callback: ((PreparedPrivilegedCapabilityPlan) -> Unit)? = null
        var prepared: PreparedPrivilegedCapabilityPlan? = null
        synchronized(lock) {
            val flight = inFlight
            if (closed || flight?.generation != generation) {
                releaseStaleResult = (result as? HostReadinessState.Ready)
                    ?.prepared
                    ?.commitState == HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION
                return@synchronized
            }
            inFlight = null
            publishLocked(result, hostActionVisible = true)
            if (result is HostReadinessState.Ready && !completionDispatched) {
                completionDispatched = true
                callback = flight.onPrepared
                prepared = result.prepared
            }
        }
        if (releaseStaleResult) {
            work.releasePreparedResources()
        } else if (callback != null && prepared != null) {
            callback?.invoke(prepared!!)
        }
    }

    private fun completeObservationFailure(generation: Long) = synchronized(lock) {
        val flight = inFlight
        if (closed || flight?.generation != generation) return@synchronized
        inFlight = null
        publishLocked(
            HostReadinessState.Failed(
                generation = generation,
                runtimeState = latestRuntimeState,
                reason = PrivilegedRuntimeFailureReason.UNKNOWN,
            ),
            hostActionVisible = flight.hostRequested,
        )
    }

    private fun publishDispatchFailure(generation: Long) = synchronized(lock) {
        val flight = inFlight
        if (closed || flight?.generation != generation) return@synchronized
        inFlight = null
        publishLocked(
            HostReadinessState.Failed(
                generation = generation,
                runtimeState = latestRuntimeState,
                reason = PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED,
            ),
            hostActionVisible = flight.hostRequested,
        )
    }

    private fun newFlightLocked(
        hostRequested: Boolean,
        onPrepared: ((PreparedPrivilegedCapabilityPlan) -> Unit)? = null,
    ): Long {
        val generation = ++nextGeneration
        inFlight = Flight(generation, hostRequested, onPrepared)
        completionDispatched = false
        publishLocked(
            HostReadinessState.Checking(generation, HostReadinessPhase.CHECKING_PRIVILEGED_RUNTIME),
            hostActionVisible = hostRequested,
        )
        return generation
    }

    private fun backgroundStateFor(
        runtimeState: PrivilegedRuntimeStateSnapshot,
        preparedPlan: PreparedPrivilegedCapabilityPlan?,
        evidenceSource: HostReadinessEvidenceSource,
    ): HostReadinessState {
        if (runtimeState.directRoot.rootFoundation?.ready == true) {
            return preparedPlan?.let(HostReadinessState::Ready)
                ?: HostReadinessState.Stale(runtimeState.generation, runtimeState, evidenceSource = evidenceSource)
        }
        if (runtimeState.directRoot.rootFoundation?.provenance != null &&
            runtimeState.directRoot.state == PrivilegedRuntimeAvailabilityState.TEMPORARILY_UNAVAILABLE
        ) {
            return HostReadinessState.Stale(runtimeState.generation, runtimeState, evidenceSource = evidenceSource)
        }
        val directShell = runtimeState.directShell
        return when (directShell.state) {
            PrivilegedRuntimeAvailabilityState.DISABLED_RECOVERABLE -> HostReadinessState.UserActionRequired(
                runtimeState.generation,
                runtimeState,
                directShell.reason ?: PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED,
            )

            PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED -> HostReadinessState.AuthorizationRequired(
                runtimeState.generation,
                runtimeState,
                directShell.reason ?: PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED,
            )

            PrivilegedRuntimeAvailabilityState.UNSUPPORTED,
            PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            -> HostReadinessState.Unsupported(
                runtimeState.generation,
                runtimeState,
                directShell.reason ?: PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
            )

            PrivilegedRuntimeAvailabilityState.ENABLED_BUT_UNUSABLE -> HostReadinessState.Failed(
                runtimeState.generation,
                runtimeState,
                directShell.reason ?: PrivilegedRuntimeFailureReason.PROVIDER_BOOTSTRAP_FAILED,
            )

            PrivilegedRuntimeAvailabilityState.TEMPORARILY_UNAVAILABLE,
            PrivilegedRuntimeAvailabilityState.RECOVERY_IN_PROGRESS,
            PrivilegedRuntimeAvailabilityState.RECOVERY_FAILED,
            -> HostReadinessState.Stale(
                generation = runtimeState.generation,
                runtimeState = runtimeState,
                evidenceSource = evidenceSource,
            )

            else -> preparedPlan?.let(HostReadinessState::Ready) ?: HostReadinessState.Stale(
                generation = runtimeState.generation,
                runtimeState = runtimeState,
                evidenceSource = evidenceSource,
            )
        }
    }

    private fun publishLocked(state: HostReadinessState, hostActionVisible: Boolean) {
        _state.value = state
        _uiState.value = HostReadinessUiState(state, hostActionVisible)
        runCatching { onStateChanged(state) }
    }

    private fun publishUiLocked(hostActionVisible: Boolean) {
        _uiState.value = HostReadinessUiState(_state.value, hostActionVisible)
    }

    private fun isCurrentFlight(generation: Long): Boolean = synchronized(lock) {
        !closed && inFlight?.generation == generation
    }

    private data class Flight(
        val generation: Long,
        var hostRequested: Boolean,
        var onPrepared: ((PreparedPrivilegedCapabilityPlan) -> Unit)?,
        var stage: FlightStage = FlightStage.OBSERVING,
    )

    private enum class FlightStage {
        OBSERVING,
        PREPARING_HOST,
    }

    private sealed interface DispatchAction {
        data object None : DispatchAction
        data class Observe(val generation: Long) : DispatchAction
        data class Prepare(
            val generation: Long,
            val runtimeState: PrivilegedRuntimeStateSnapshot,
        ) : DispatchAction
    }
}
