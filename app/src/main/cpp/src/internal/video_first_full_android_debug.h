#pragma once

#if defined(__ANDROID__) && !defined(NDEBUG)
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <sys/system_properties.h>
#include <unistd.h>

#include "video_first_full_diagnostic.h"

namespace warpnect::scl::diagnostics {

inline bool video_first_full_enabled() noexcept {
    char value[PROP_VALUE_MAX]{};
    return __system_property_get("debug.warpnect.video_first_full", value) > 0 &&
           std::strcmp(value, "1") == 0;
}

// Only called by nativeVideoReceiverDestroy after close, detach, worker/codec
// quiescence and release of the existing handle mutex. No destructor export.
inline void export_video_first_full(const VideoFirstFullDiagnostic& recorder) noexcept {
    if (!recorder.frozen())
        return;
    char path[192]{};
    std::snprintf(path, sizeof(path),
                  "/data/data/io.warpnect/cache/video-first-full-%d-%llu-%u.txt", getpid(),
                  static_cast<unsigned long long>(recorder.header().session_tag),
                  recorder.header().session_generation);
    auto* file = std::fopen(path, "wx"); // Never overwrite an earlier evidence file.
    bool saved = false;
    if (file != nullptr) {
        const bool written = recorder.write(file);
        saved = std::fclose(file) == 0 && written;
    }
    __android_log_print(ANDROID_LOG_INFO, "WarpnectVideoDiagnostics",
                        "VIDEO_FIRST_FULL_FINISH file=%s saved=%d", path, saved);
}

} // namespace warpnect::scl::diagnostics
#endif
