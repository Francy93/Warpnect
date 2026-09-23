package io.warpnect.platform.session.lifecycle

import io.warpnect.session.PathId
import io.warpnect.session.integration.SessionControlDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializedLifecyclePlatformCallbackDispatcherTest {
    @Test
    fun queuesPlatformFactsWithoutRunningLifecycleWorkOnCallbackThread() {
        val dispatcher = QueuedDispatcher()
        val observed = mutableListOf<String>()
        val callbacks = SerializedLifecyclePlatformCallbackDispatcher(
            controlDispatcher = dispatcher,
            handlePathLoss = { pathId, hard -> observed += "loss:${pathId.value}:$hard" },
            handlePathAvailable = { pathId -> observed += "available:${pathId.value}" },
        )

        callbacks.pathLoss(PathId.requireValid(1u), hardLoss = true)
        callbacks.pathAvailable(PathId.requireValid(1u))

        assertEquals(emptyList<String>(), observed)
        dispatcher.runAll()
        assertEquals(listOf("loss:1:true", "available:1"), observed)
    }

    @Test
    fun dropsPlatformFactWhenSessionControlOwnerHasStopped() {
        val observed = mutableListOf<String>()
        val callbacks = SerializedLifecyclePlatformCallbackDispatcher(
            controlDispatcher = SessionControlDispatcher { false },
            handlePathLoss = { _, _ -> observed += "loss" },
            handlePathAvailable = { observed += "available" },
        )

        callbacks.pathLoss(PathId.requireValid(1u), hardLoss = true)
        callbacks.pathAvailable(PathId.requireValid(1u))

        assertEquals(emptyList<String>(), observed)
    }

    private class QueuedDispatcher : SessionControlDispatcher {
        private val pending = ArrayDeque<() -> Unit>()

        override fun dispatch(action: () -> Unit): Boolean {
            pending += action
            return true
        }

        fun runAll() {
            while (pending.isNotEmpty()) {
                pending.removeFirst().invoke()
            }
        }
    }
}
