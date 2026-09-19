package io.warpnect.debug.directshell

/** Bounded payload parser for the DEBUG-only DirectShell Input probe. */
internal object DirectShellInputProbeRequest {
    const val MAX_SEQUENCE_COUNT = 100

    fun parseCount(payload: ByteArray): Int? {
        val value = payload.toString(Charsets.UTF_8)
        if (!value.matches(Regex("count=[0-9]{1,3}"))) return null
        val count = value.substringAfter('=').toIntOrNull() ?: return null
        return count.takeIf { it in 1..MAX_SEQUENCE_COUNT }
    }
}
