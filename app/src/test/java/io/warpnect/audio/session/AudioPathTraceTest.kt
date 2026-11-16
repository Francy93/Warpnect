package io.warpnect.audio.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPathTraceTest {
    @Test fun absentObserverKeepsExecutionSynchronousAndMonitorOwned() {
        val monitor = Any()
        val caller = Thread.currentThread()
        val result = tracedAudioMonitor(null, AudioPathStage.SessionFrame, monitor) {
            assertTrue(Thread.holdsLock(monitor))
            assertSame(caller, Thread.currentThread())
            42
        }
        assertEquals(42, result)
    }

    @Test fun nestedAndExceptionalScopesRemainBalanced() {
        val events = mutableListOf<String>()
        val trace = object : AudioPathTrace {
            override fun waiting(stage: AudioPathStage) {
                events += "wait:$stage"
            }
            override fun acquired(stage: AudioPathStage) {
                events += "held:$stage"
            }
            override fun finished() {
                events += "end"
            }
        }
        val monitor = Any()
        val failure = IllegalStateException("controlled")
        try {
            tracedAudioMonitor(trace, AudioPathStage.SessionFrame, monitor) {
                tracedAudioMonitor(trace, AudioPathStage.DecoderFrame, Any()) { throw failure }
            }
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals(
            listOf("wait:SessionFrame", "held:SessionFrame", "wait:DecoderFrame", "held:DecoderFrame", "end", "end"),
            events,
        )
        assertTrue(!Thread.holdsLock(monitor))
    }
}
