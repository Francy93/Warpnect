package io.warpnect.video.transport

data class VideoTransportConfig(
    val remoteAddress: String,
    val remotePort: Int,
    val maxWireDatagramSize: Int,
    val retransmissionCacheSlots: Int,
    val localPort: Int = 0,
    val initialVideoSequence: Long = 0,
    val initialControlSequence: Long = 0,
    val initialFrameId: Long = 0,
    val fec: VideoTransportFecConfig = VideoTransportFecConfig.Disabled,
)

data class VideoTransportFecConfig(
    val enabled: Boolean,
    val dataShards: Int,
    val parityShards: Int,
) {
    companion object {
        val Disabled: VideoTransportFecConfig = VideoTransportFecConfig(
            enabled = false,
            dataShards = 0,
            parityShards = 0,
        )
    }
}
