package io.warpnect.debug.directshell

/** Bounded payload parser for the DEBUG-only DirectShell Input probe. */
internal object DirectShellInputProbeRequest {
    const val MAX_SEQUENCE_COUNT = 100
    private const val MAX_SCREEN_COORDINATE = 16_384

    data class TouchGesture(
        val count: Int,
        val targetX: Int? = null,
        val targetY: Int? = null,
    )

    fun parseCount(payload: ByteArray): Int? {
        val value = payload.toString(Charsets.UTF_8)
        if (!value.matches(Regex("count=[0-9]{1,3}"))) return null
        val count = value.substringAfter('=').toIntOrNull() ?: return null
        return count.takeIf { it in 1..MAX_SEQUENCE_COUNT }
    }

    fun parseTouchGesture(payload: ByteArray): TouchGesture? {
        val value = payload.toString(Charsets.UTF_8)
        parseCount(payload)?.let { return TouchGesture(count = it) }
        val match = TOUCH_GESTURE_PATTERN.matchEntire(value) ?: return null
        val count = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_SEQUENCE_COUNT } ?: return null
        val targetX = match.groupValues[2].toIntOrNull()?.takeIf { it in 0..MAX_SCREEN_COORDINATE } ?: return null
        val targetY = match.groupValues[3].toIntOrNull()?.takeIf { it in 0..MAX_SCREEN_COORDINATE } ?: return null
        return TouchGesture(count = count, targetX = targetX, targetY = targetY)
    }

    fun touchGesturePayload(count: Int, targetX: Int, targetY: Int): String {
        require(count in 1..MAX_SEQUENCE_COUNT) { "invalid_touch_count" }
        require(targetX in 0..MAX_SCREEN_COORDINATE) { "invalid_touch_x" }
        require(targetY in 0..MAX_SCREEN_COORDINATE) { "invalid_touch_y" }
        return "count=$count;x=$targetX;y=$targetY"
    }

    private val TOUCH_GESTURE_PATTERN = Regex("count=([0-9]{1,3});x=([0-9]{1,5});y=([0-9]{1,5})")
}
