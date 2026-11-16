package io.warpnect.platform.audio

import android.os.Trace
import io.warpnect.audio.session.AudioPathStage
import io.warpnect.audio.session.AudioPathTrace

/** Selected only for an already-attached SystemAudio flight recorder in DEBUG. */
internal object AndroidAudioPathTrace : AudioPathTrace {
    override fun waiting(stage: AudioPathStage) = Trace.beginSection(stage.waitName)

    override fun acquired(stage: AudioPathStage) {
        Trace.endSection()
        Trace.beginSection(stage.heldName)
    }

    override fun finished() = Trace.endSection()
}
