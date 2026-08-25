@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics

import io.warpnect.session.PathId
import io.warpnect.session.SessionGeneration
import io.warpnect.telemetry.TelemetryScope

/** Pre-bound writers for cold RFC-005H semantic transitions. */
class SessionLifecycleDiagnosticEvents private constructor(
    private val hub: DiagnosticEventHub,
    private val scope: TelemetryScope.Session,
) {
    private val session = hub.writer(scope)

    fun stateChanged(from: DiagnosticSessionState, to: DiagnosticSessionState) =
        session.emit(DiagnosticEventIds.SessionStateChanged, from.code, to.code)

    fun running() = session.emit(DiagnosticEventIds.SessionRunning)

    fun startFailed(reason: DiagnosticReason, rawCode: Int = 0) =
        session.emit(DiagnosticEventIds.SessionStartFailed, reason.code, rawCode.toLong().toULong())

    fun suspended(reason: DiagnosticReason) = session.emit(DiagnosticEventIds.SessionSuspended, reason.code)

    fun migrationStarted(from: PathId?, target: PathId?) =
        session.emit(DiagnosticEventIds.MigrationStarted, from.code(), target.code())

    fun migrationSucceeded(from: PathId?, target: PathId?) =
        session.emit(DiagnosticEventIds.MigrationSucceeded, from.code(), target.code())

    fun migrationFailed(from: PathId?, target: PathId?, reason: DiagnosticReason) =
        session.emit(DiagnosticEventIds.MigrationFailed, from.code(), target.code(), reason.code)

    fun reconnectStarted(newGeneration: SessionGeneration) = session.emit(
        DiagnosticEventIds.ReconnectStarted,
        scope.generation.value.toULong(),
        newGeneration.value.toULong(),
    )

    fun reconnectAttemptFailed(attempt: Int, reason: DiagnosticReason) =
        session.emit(DiagnosticEventIds.ReconnectAttemptFailed, attempt.coerceAtLeast(0).toULong(), reason.code)

    fun reconnectSucceeded(newGeneration: SessionGeneration) =
        hub.writer(TelemetryScope.Session(scope.sessionId, newGeneration)).emit(
            DiagnosticEventIds.ReconnectSucceeded,
            scope.generation.value.toULong(),
            newGeneration.value.toULong(),
        )

    fun reconnectExpired() = session.emit(DiagnosticEventIds.ReconnectExpired)

    fun reconnectCancelled(reason: DiagnosticReason) = session.emit(DiagnosticEventIds.ReconnectCancelled, reason.code)

    fun disconnectLocal(reason: DiagnosticReason) = session.emit(DiagnosticEventIds.DisconnectLocal, reason.code)

    fun disconnectRemote(reason: DiagnosticReason) = session.emit(DiagnosticEventIds.DisconnectRemote, reason.code)

    fun platformPathLosing(path: PathId, pathScope: TelemetryScope.Path) =
        hub.writer(pathScope).emit(DiagnosticEventIds.PathPlatformLosing, path.value.toULong())

    fun platformPathLost(path: PathId, pathScope: TelemetryScope.Path) =
        hub.writer(pathScope).emit(DiagnosticEventIds.PathPlatformLost, path.value.toULong())

    fun pathValidationFailed(path: PathId?, pathScope: TelemetryScope.Path?, reason: DiagnosticReason) {
        if (pathScope != null && path != null) {
            hub.writer(pathScope).emit(DiagnosticEventIds.PathValidationFailed, path.value.toULong(), reason.code)
        }
    }

    fun inputSafetyReset(reason: DiagnosticReason) = session.emit(DiagnosticEventIds.InputSafetyReset, reason.code)

    companion object {
        fun register(hub: DiagnosticEventHub, scope: TelemetryScope.Session): SessionLifecycleDiagnosticEvents =
            SessionLifecycleDiagnosticEvents(hub, scope)
    }
}

private fun PathId?.code(): ULong = this?.value?.toULong() ?: 0u
