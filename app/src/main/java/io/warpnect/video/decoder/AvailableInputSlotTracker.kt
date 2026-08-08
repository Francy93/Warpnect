package io.warpnect.video.decoder

internal class AvailableInputSlotTracker {
    private val indices = mutableListOf<Int>()

    fun retain(index: Int): Boolean {
        if (index in indices) {
            return false
        }
        indices += index
        return true
    }

    fun drain(): List<Int> {
        if (indices.isEmpty()) {
            return emptyList()
        }
        val drained = indices.toList()
        indices.clear()
        return drained
    }

    fun clear() {
        indices.clear()
    }

    fun contains(index: Int): Boolean = index in indices

    val size: Int
        get() = indices.size
}
