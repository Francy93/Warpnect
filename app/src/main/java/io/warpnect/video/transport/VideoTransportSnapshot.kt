package io.warpnect.video.transport

data class VideoTransportSnapshot(
    val state: VideoTransportState = VideoTransportState.Stopped,
    val currentConfigGeneration: Long = 0,
    val nextFrameId: Long = 0,
    val nextVideoSequence: Long = 0,
    val nextControlSequence: Long = 0,
    val configsSubmitted: Long = 0,
    val accessUnitsSubmitted: Long = 0,
    val keyframesSubmitted: Long = 0,
    val accessUnitsFailed: Long = 0,
    val videoDatagramsGenerated: Long = 0,
    val videoDatagramsSent: Long = 0,
    val videoBytesSent: Long = 0,
    val fecParityPackets: Long = 0,
    val retransmissions: Long = 0,
    val resyncRequestsReceived: Long = 0,
    val resyncRequestsSuppressed: Long = 0,
    val resyncRequestsWithoutConfig: Long = 0,
    val streamConfigResends: Long = 0,
    val keyFrameRequestsReceived: Long = 0,
    val lastResyncReason: VideoResyncReason = VideoResyncReason.Unknown,
    val clockSyncRequestsReceived: Long = 0,
    val clockSyncResponsesSent: Long = 0,
    val lastPresentationTimeUs: Long = 0,
    val lastError: VideoTransportError = VideoTransportError.None,
) {
    companion object {
        private const val NATIVE_SNAPSHOT_VALUES = 25

        fun fromNative(values: LongArray): VideoTransportSnapshot {
            if (values.size < NATIVE_SNAPSHOT_VALUES) {
                return VideoTransportSnapshot(
                    state = VideoTransportState.Error,
                    lastError = VideoTransportError.InvalidHandle,
                )
            }
            val error = VideoTransportError.fromNativeCode(values[14].toInt())
            val opened = values[15] != 0L
            val closed = values[16] != 0L
            val state = when {
                closed -> VideoTransportState.Closed
                error != VideoTransportError.None -> VideoTransportState.Error
                opened -> VideoTransportState.Ready
                else -> VideoTransportState.Stopped
            }
            return VideoTransportSnapshot(
                state = state,
                currentConfigGeneration = values[0],
                nextFrameId = values[1],
                nextVideoSequence = values[2],
                nextControlSequence = values[3],
                configsSubmitted = values[4],
                accessUnitsSubmitted = values[5],
                keyframesSubmitted = values[6],
                accessUnitsFailed = values[7],
                videoDatagramsGenerated = values[8],
                videoDatagramsSent = values[9],
                videoBytesSent = values[10],
                fecParityPackets = values[11],
                retransmissions = values[12],
                resyncRequestsReceived = values[17],
                resyncRequestsSuppressed = values[18],
                resyncRequestsWithoutConfig = values[19],
                streamConfigResends = values[20],
                keyFrameRequestsReceived = values[21],
                lastResyncReason = VideoResyncReason.fromNativeCode(values[22].toInt()),
                clockSyncRequestsReceived = values[23],
                clockSyncResponsesSent = values[24],
                lastPresentationTimeUs = values[13],
                lastError = error,
            )
        }
    }
}
