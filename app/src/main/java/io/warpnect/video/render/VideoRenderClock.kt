package io.warpnect.video.render

fun interface VideoRenderClock {
    fun nowNs(): Long
}
object SystemVideoRenderClock : VideoRenderClock {
    override fun nowNs(): Long = System.nanoTime()
}
