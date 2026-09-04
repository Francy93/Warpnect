package io.warpnect.video.decoder

/**
 * Local-only decoder qualification state. It is deliberately not serialized into WNCP: peers
 * receive only the existing video-available capability derived from the final result.
 */
enum class VideoDecoderQualification {
    FrameworkHardware,
    ExplicitSoftwareRejected,
    LegacySoftwareFamilyRejected,
    LegacyCandidate,
    ActivePass,
    ActiveFail,
    ActiveInconclusive,
    CachedPass,
    CachedFail,
    CachedInconclusive,
    NotApplicable,
}

/** Conservative recognition for legacy Android releases without framework classification APIs. */
object LegacyVideoDecoderSoftwareClassifier {
    fun isKnownSoftwareFamily(codecName: String, canonicalName: String? = null): Boolean =
        sequenceOf(codecName, canonicalName)
            .filterNotNull()
            .map(String::lowercase)
            .any { name ->
                name.startsWith("omx.google.") ||
                    name.startsWith("omx.ffmpeg.") ||
                    name.startsWith("c2.android.") ||
                    name.startsWith("c2.google.") ||
                    (name.startsWith("omx.sec.") && name.contains(".sw."))
            }
}
