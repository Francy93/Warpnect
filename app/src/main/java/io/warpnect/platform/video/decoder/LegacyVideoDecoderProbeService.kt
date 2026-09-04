package io.warpnect.platform.video.decoder

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Process
import android.util.Log

/** Private disposable process for cold legacy Client decoder qualification only. */
class LegacyVideoDecoderProbeService : Service() {
    private val binder = object : ILegacyVideoDecoderProbeService.Stub() {
        override fun probe(codecName: String, qualificationAlgorithmVersion: Int): Int {
            if (qualificationAlgorithmVersion != LegacyDecoderQualificationProfile.ALGORITHM_VERSION) {
                return LegacyDecoderProbeResult.FixtureUnavailable.code
            }
            val execution = runLegacyDecoderQualification(applicationContext, codecName)
            logExecution(execution)
            return execution.result.code
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun logExecution(execution: LegacyDecoderProbeExecution) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val metrics = execution.metrics
        val summary = buildString {
            append("event=decoder_qualification_probe_execution pid=")
            append(Process.myPid())
            append(" uid=")
            append(Process.myUid())
            append(" result=")
            append(execution.result.name)
            metrics?.let {
                append(" inputs=")
                append(it.inputs)
                append(" outputs=")
                append(it.outputs)
                append(" presentations=")
                append(it.presentations)
                append(" max_input_wait_ms=")
                append(it.maxInputWaitMs)
                append(" max_output_gap_ms=")
                append(it.maxOutputGapMs)
                append(" max_surface_gap_ms=")
                append(it.maxPresentationGapMs)
                append(" eos=")
                append(it.endOfStream)
                append(" elapsed_ms=")
                append(it.elapsedMs)
            }
        }
        Log.d(TAG, summary)
    }

    private companion object {
        const val TAG = "WarpnectDecoderProbe"
    }
}
