package io.warpnect.video.session

/**
 * A StreamConfig generation identifies one immutable decoder configuration on the wire.
 * Resends of that generation are recovery data, not a request to recreate the decoder.
 */
internal class VideoReceiverStreamConfigAdmission {
    private var admittedGeneration: Long? = null

    fun admits(configGeneration: Long): Boolean {
        if (admittedGeneration == configGeneration) {
            return false
        }
        admittedGeneration = configGeneration
        return true
    }
}
