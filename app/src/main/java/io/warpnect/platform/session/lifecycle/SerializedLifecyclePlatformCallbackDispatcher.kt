package io.warpnect.platform.session.lifecycle

import io.warpnect.session.PathId
import io.warpnect.session.integration.SessionControlDispatcher

/** Keeps framework callback threads out of synchronous Session lifecycle work. */
internal class SerializedLifecyclePlatformCallbackDispatcher(
    private val controlDispatcher: SessionControlDispatcher,
    private val handlePathLoss: (PathId, Boolean) -> Unit,
    private val handlePathAvailable: (PathId) -> Unit,
) {
    fun pathLoss(pathId: PathId, hardLoss: Boolean) {
        controlDispatcher.dispatch { handlePathLoss(pathId, hardLoss) }
    }

    fun pathAvailable(pathId: PathId) {
        controlDispatcher.dispatch { handlePathAvailable(pathId) }
    }
}
