@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.platform.diagnostics

import android.util.Log
import io.warpnect.diagnostics.DiagnosticEventDescriptor
import io.warpnect.diagnostics.DiagnosticEventRecord
import io.warpnect.diagnostics.DiagnosticLogSink
import io.warpnect.diagnostics.DiagnosticPayloadFieldKey
import io.warpnect.diagnostics.DiagnosticReason
import io.warpnect.diagnostics.DiagnosticSeverity

/**
 * Optional developer sink. The primary diagnostic record remains the bounded structured history;
 * this adapter deliberately omits scope identities and all arbitrary platform/error strings.
 */
class AndroidLogcatDiagnosticSink(
    private val tag: String = DEFAULT_TAG,
    private val minimumSeverity: DiagnosticSeverity = DiagnosticSeverity.Warning,
) : DiagnosticLogSink {
    init {
        require(tag.isNotBlank() && tag.length <= MAX_TAG_LENGTH)
    }

    override fun emit(descriptor: DiagnosticEventDescriptor, event: DiagnosticEventRecord) {
        if (event.severity.bridgeId < minimumSeverity.bridgeId) return
        val payload = descriptor.payload.mapIndexedNotNull { index, field ->
            val value = event.payload.getOrNull(index) ?: return@mapIndexedNotNull null
            when (field.key) {
                DiagnosticPayloadFieldKey.Reason -> {
                    val reason = DiagnosticReason.entries.firstOrNull { it.code == value }
                        ?: return@mapIndexedNotNull null
                    "reason=${reason.name}"
                }
                DiagnosticPayloadFieldKey.RawCode -> "raw_code=${value.toLong()}"
                else -> null
            }
        }
        val message = buildString {
            append("event=")
            append(descriptor.canonicalName)
            append(" severity=")
            append(event.severity.name)
            payload.forEach { field ->
                append(' ')
                append(field)
            }
        }
        Log.println(event.severity.logcatPriority(), tag, message)
    }

    private companion object {
        const val DEFAULT_TAG = "WarpnectDiag"
        const val MAX_TAG_LENGTH = 23
    }
}

private fun DiagnosticSeverity.logcatPriority(): Int = when (this) {
    DiagnosticSeverity.Debug -> Log.DEBUG
    DiagnosticSeverity.Info -> Log.INFO
    DiagnosticSeverity.Warning -> Log.WARN
    DiagnosticSeverity.Error,
    DiagnosticSeverity.Critical,
    -> Log.ERROR
}
