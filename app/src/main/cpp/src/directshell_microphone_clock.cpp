#include "microphone_destination_queue.h"
#include "microphone_sdk_write_clock.h"
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstdio>
#include <dlfcn.h>
#include <sched.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {
int64_t nowNs() {
    timespec value{};
    clock_gettime(CLOCK_MONOTONIC, &value);
    return int64_t(value.tv_sec) * 1000000000LL + value.tv_nsec;
}
struct Clock {
    explicit Clock(int64_t generation) : queue(generation), generation(generation) {}
    warpnect::audio::MicrophoneDestinationQueue queue;
    const int64_t generation;
    AAudioStream* stream = nullptr;
    int device = 0;
    std::atomic<int> error{0}, tid{0};
    std::atomic<int64_t> callbacks{0}, frames{0}, last{0}, maxGap{0};
    std::atomic<int64_t> maxRender{0}, maxReturnAge{0}, lateCallbackFrames{0};
    bool consumerActive = false; // Serialized recording-state observer, not the audio callback.
    bool appOwned = false;
    std::atomic<int64_t> sdkStartNs{0};
    std::atomic<int64_t> windowSince{0};
    std::atomic<bool> stopPump{false}, flushPending{false};
    std::atomic<int64_t> zeroWrites{0}, partialWrites{0}, maxWrite{0};
    std::atomic<int64_t> maxCounterProbe{0}, maxActiveLoopGap{0}, acceptedDespiteEstimate{0};
    std::atomic<int64_t> sdkWritten{0}, sdkRead{0};
    std::atomic<int64_t> sdkSampleBegin{0}, sdkSampleEnd{0};
    std::atomic<uint64_t> sdkObservationVersion{0};
    std::atomic<int> sdkObservedState{0};
    std::atomic<int64_t> transitionProbeUntil{0};
    std::atomic<int> sdkXrun{0}, estimatedAhead{0};
    std::atomic<int64_t> estimatedEmptyEpisodes{0};
    uint64_t sdkWriteCalls = 0;
    int sdkLastRequested = 0, sdkLastAccepted = 0;
    bool writingReal = false;
    std::atomic<bool> traceFrozen{false};
    bool sdkTraceFrozen = false; // Sole SDK sampler owns this independent diagnostic window.
    unsigned sdkXrunReports = 0;
    struct Event { int64_t begin, end, frames, before, after, result, sourceFirst, sourceLast, destinationFirst; };
    std::array<Event, 1024> ingressEvents{}, callbackEvents{}, zeroEvents{};
    std::array<Event, 1024> sdkEvents{};
    std::array<Event, 1024> maintenanceEvents{};
    uint64_t ingressCount = 0, callbackCount = 0, zeroCount = 0;
    uint64_t sdkCount = 0;
    uint64_t maintenanceCount = 0;
};
aaudio_data_callback_result_t render(AAudioStream*, void* user, void* output, int32_t frames) {
    auto& clock = *static_cast<Clock*>(user);
    if (clock.error.load(std::memory_order_acquire) != 0) {
        warpnect::audio::MicrophoneDestinationQueue::erase(static_cast<int16_t*>(output), frames);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    const auto now = nowNs();
    const auto retainedBefore = clock.queue.retained();
    const auto previous = clock.last.exchange(now);
    if (previous != 0 && now - previous > clock.maxGap.load()) clock.maxGap.store(now - previous);
    clock.tid.store(gettid());
    clock.queue.render(static_cast<int16_t*>(output), frames, now, nowNs);
    const auto ended = nowNs();
    if (clock.appOwned && clock.windowSince.load() > 0) {
        clock.callbackEvents[clock.callbackCount++ % clock.callbackEvents.size()] = {now, ended, frames, retainedBefore,
                clock.queue.retained(), clock.queue.render_accepted(), clock.queue.render_first(),
                clock.queue.render_last(), clock.frames.load()};
    }
    if (ended - now > clock.maxRender.load()) clock.maxRender.store(ended - now);
    const auto oldest = clock.queue.render_oldest();
    if (oldest != 0) {
        const auto age = ended - oldest;
        if (age > clock.maxReturnAge.load()) clock.maxReturnAge.store(age);
        if (age > warpnect::audio::MicrophoneDestinationQueue::kFreshNs) {
            clock.lateCallbackFrames.fetch_add(clock.queue.render_accepted());
        }
    }
    clock.callbacks.fetch_add(1);
    clock.frames.fetch_add(frames);
    if (clock.error.load(std::memory_order_acquire) != 0) {
        warpnect::audio::MicrophoneDestinationQueue::erase(static_cast<int16_t*>(output), frames);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
void error(AAudioStream*, void* user, aaudio_result_t result) {
    auto* clock = static_cast<Clock*>(user);
    clock->error.store(result, std::memory_order_release);
    clock->queue.window(0);
}
Clock* cast(jlong handle) { return reinterpret_cast<Clock*>(handle); }
int nonblockingWrite(void* user, const int16_t* pcm, int count) {
    auto* clock = static_cast<Clock*>(user);
    if (clock->stopPump.load() || clock->error.load() != 0 ||
            AAudioStream_getDeviceId(clock->stream) != clock->device) return AAUDIO_ERROR_DISCONNECTED;
    const auto begin = nowNs();
    const int written = AAudioStream_write(clock->stream, pcm, count, 0);
    ++clock->sdkWriteCalls;
    clock->sdkLastRequested = count;
    clock->sdkLastAccepted = written;
    const auto elapsed = nowNs() - begin;
    if (elapsed > clock->maxWrite.load()) clock->maxWrite.store(elapsed);
    if (written > 0) clock->sdkWritten.fetch_add(written);
    if (written == 0) {
        clock->zeroWrites.fetch_add(1);
        if (clock->appOwned && clock->writingReal && !clock->traceFrozen.load()) {
            clock->zeroEvents[clock->zeroCount++ % 1024] = {begin, begin + elapsed, count,
                    clock->queue.retained(), clock->queue.retained(), 0,
                    clock->queue.pending_first(), -1, clock->queue.pending_enqueued()};
        }
    }
    else if (written > 0 && written < count) clock->partialWrites.fetch_add(1);
    return written;
}
int primeSilence(Clock* clock) {
    std::array<int16_t, 240> silence{};
    const int capacity = AAudioStream_getBufferSizeInFrames(clock->stream);
    int filled = 0;
    while (filled < capacity) {
        const int written = nonblockingWrite(clock, silence.data(), std::min(240, capacity - filled));
        if (written <= 0) break;
        filled += written;
    }
    return filled;
}
aaudio_result_t awaitState(AAudioStream* stream, aaudio_stream_state_t wanted) {
    const auto deadline = nowNs() + 500000000LL;
    auto state = AAudioStream_getState(stream);
    while (state != wanted) {
        const auto remaining = deadline - nowNs();
        if (remaining <= 0) return AAUDIO_ERROR_TIMEOUT;
        aaudio_stream_state_t next;
        const auto result = AAudioStream_waitForStateChange(stream, state, &next, remaining);
        if (result != AAUDIO_OK) return result;
        state = next;
    }
    return AAUDIO_OK;
}
jlong openClock(jint device, jlong generation, const char* callerPackage) {
    if (device <= 0 || generation <= 0) return 0;
    auto* clock = new Clock(generation);
    clock->appOwned = getuid() >= 10000;
    AAudioStreamBuilder* builder = nullptr;
    auto result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) { delete clock; return 0; }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setDeviceId(builder, device);
    // Public NDK functions added after minSdk26; only this centralized adapter resolves them.
    const auto usage = reinterpret_cast<void (*)(AAudioStreamBuilder*, aaudio_usage_t)>(
            dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage"));
    const auto content = reinterpret_cast<void (*)(AAudioStreamBuilder*, aaudio_content_type_t)>(
            dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setContentType"));
    const auto package = reinterpret_cast<void (*)(AAudioStreamBuilder*, const char*)>(
            dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setPackageName"));
    if (!usage || !content || !package) { AAudioStreamBuilder_delete(builder); delete clock; return 0; }
    usage(builder, AAUDIO_USAGE_MEDIA);
    content(builder, AAUDIO_CONTENT_TYPE_SPEECH);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    if (!clock->appOwned) AAudioStreamBuilder_setFramesPerDataCallback(builder, 240);
    package(builder, callerPackage);
    if (!clock->appOwned) AAudioStreamBuilder_setDataCallback(builder, render, clock);
    AAudioStreamBuilder_setErrorCallback(builder, error, clock);
    result = AAudioStreamBuilder_openStream(builder, &clock->stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) { delete clock; return 0; }
    auto* stream = clock->stream;
    const bool verified = AAudioStream_getDeviceId(stream) == device && AAudioStream_getSampleRate(stream) == 48000 &&
            AAudioStream_getChannelCount(stream) == 1 && AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_I16;
    __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMicPolicy",
            "REMOTE_MIC_NATIVE_OPEN requested=%d;actual=%d;verified=%d;capacity=%d;buffer=%d;burst=%d;performance=%d;uid=%d",
            device, AAudioStream_getDeviceId(stream), verified, AAudioStream_getBufferCapacityInFrames(stream),
            AAudioStream_getBufferSizeInFrames(stream), AAudioStream_getFramesPerBurst(stream),
            AAudioStream_getPerformanceMode(stream), getuid());
    if (!verified) { AAudioStream_close(stream); delete clock; return 0; }
    clock->device = device;
    return reinterpret_cast<jlong>(clock);
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_openNative(
        JNIEnv*, jclass, jint device, jlong generation) {
    if (getuid() != 2000) return 0;
    return openClock(device, generation, "com.android.shell");
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_openNative(
        JNIEnv*, jclass, jint device, jlong generation) {
    if (getuid() < 10000) return 0;
    return openClock(device, generation, "io.warpnect");
}
extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_startNative(JNIEnv*, jclass, jlong handle) {
    return AAudioStream_requestStart(cast(handle)->stream);
}
static jboolean offerClock(JNIEnv* env, jlong handle, jlong generation, jshortArray pcm,
        jlong enqueued, jlong firstFrame) {
    const int frames = env->GetArrayLength(pcm);
    if (frames <= 0 || frames > 960 || cast(handle)->error.load() != 0) return JNI_FALSE;
    int16_t scratch[960]{};
    env->GetShortArrayRegion(pcm, 0, frames, scratch);
    if (env->ExceptionCheck()) return JNI_FALSE;
    auto* clock = cast(handle);
    const auto before = clock->queue.retained();
    const bool accepted = clock->queue.offer(generation, scratch, frames, enqueued, nowNs(), firstFrame);
    if (clock->appOwned && clock->windowSince.load() > 0 && !clock->traceFrozen.load()) {
        clock->ingressEvents[clock->ingressCount++ % clock->ingressEvents.size()] = {enqueued, nowNs(), frames, before,
                clock->queue.retained(), accepted, firstFrame, firstFrame < 0 ? -1 : firstFrame + frames, -1};
        if (clock->queue.capacity_dropped() > 0) clock->traceFrozen.store(true);
    }
    warpnect::audio::MicrophoneDestinationQueue::erase(scratch, frames);
    return accepted;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_offerNative(
        JNIEnv* env, jclass, jlong handle, jlong generation, jshortArray pcm, jlong enqueued) {
    return offerClock(env, handle, generation, pcm, enqueued, -1);
}
extern "C" JNIEXPORT void JNICALL
Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_windowNative(
        JNIEnv*, jclass, jlong handle, jlong since) {
    auto* clock = cast(handle);
    clock->windowSince.store(since);
    clock->transitionProbeUntil.store(since > 0 ? nowNs() + 400000000LL : 0);
    clock->queue.window(since);
    const bool closing = clock->consumerActive && since == 0;
    clock->consumerActive = since > 0;
    if (!closing || clock->error.load() != 0) return;
    if (clock->appOwned) { clock->flushPending.store(true); return; }
    // Preserve the existing CLOSE-only stale-platform-buffer cleanup. Never flush on OPEN.
    const auto begin = nowNs();
    auto result = AAudioStream_requestPause(clock->stream);
    if (result == AAUDIO_OK) result = awaitState(clock->stream, AAUDIO_STREAM_STATE_PAUSED);
    if (result == AAUDIO_OK) result = AAudioStream_requestFlush(clock->stream);
    if (result == AAUDIO_OK) result = awaitState(clock->stream, AAUDIO_STREAM_STATE_FLUSHED);
    if (result == AAUDIO_OK) result = AAudioStream_requestStart(clock->stream);
    if (result != AAUDIO_OK) clock->error.store(result);
    __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMicPolicy",
            "REMOTE_MIC_NATIVE_CLOSE_WINDOW result=%d;elapsed_ns=%lld", result, (long long)(nowNs() - begin));
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_snapshotNative(JNIEnv* env, jclass, jlong handle) {
    auto* clock = cast(handle);
    std::array<int64_t, 9> totals{};
    if (!clock->queue.try_totals(totals)) return nullptr;
    jlong values[35]{};
    std::copy(totals.begin(), totals.end(), values);
    values[9] = clock->queue.retained(); values[10] = clock->queue.high_water();
    values[11] = clock->queue.max_age(); values[12] = clock->queue.active_silence();
    values[13] = clock->queue.idle_silence(); values[14] = clock->maxGap.load();
    values[15] = clock->callbacks.load(); values[16] = clock->frames.load();
    values[17] = clock->appOwned ? clock->sdkXrun.load() : AAudioStream_getXRunCount(clock->stream);
    values[18] = clock->error.load();
    const int tid = clock->tid.load();
    values[19] = tid > 0 ? sched_getscheduler(tid) : -1;
    values[20] = tid > 0 ? getpriority(PRIO_PROCESS, tid) : 0;
    values[21] = clock->appOwned ? clock->sdkWritten.load() : AAudioStream_getFramesWritten(clock->stream);
    values[22] = clock->appOwned ? clock->sdkRead.load() : AAudioStream_getFramesRead(clock->stream);
    values[23] = clock->maxRender.load(); values[24] = clock->maxReturnAge.load();
    values[25] = clock->lateCallbackFrames.load();
    values[26] = clock->queue.post_copy_stale();
    values[27] = clock->zeroWrites.load(); values[28] = clock->partialWrites.load(); values[29] = clock->maxWrite.load();
    values[30] = clock->maxCounterProbe.load(); values[31] = clock->maxActiveLoopGap.load();
    values[32] = clock->acceptedDespiteEstimate.load();
    values[33] = clock->estimatedAhead.load();
    values[34] = clock->estimatedEmptyEpisodes.load();
    auto array = env->NewLongArray(35);
    if (array) env->SetLongArrayRegion(array, 0, 35, values);
    return array;
}
namespace {
jlongArray closeClock(JNIEnv* env, jlong handle) {
    auto* clock = cast(handle);
    clock->queue.window(0);
    AAudioStream_requestStop(clock->stream);
    AAudioStream_close(clock->stream);
    std::vector<jlong> trace;
    if (env != nullptr && clock->appOwned) {
        trace.reserve((std::min<uint64_t>(clock->ingressCount, 1024) +
                std::min<uint64_t>(clock->callbackCount, 1024) + std::min<uint64_t>(clock->zeroCount, 1024) +
                std::min<uint64_t>(clock->sdkCount, 1024) +
                std::min<uint64_t>(clock->maintenanceCount, 1024) + 1) * 10);
        for (int side = 0; side < 5; ++side) {
            const auto& events = side == 0 ? clock->ingressEvents : side == 1 ? clock->callbackEvents :
                    side == 2 ? clock->zeroEvents : side == 3 ? clock->sdkEvents : clock->maintenanceEvents;
            const auto count = side == 0 ? clock->ingressCount : side == 1 ? clock->callbackCount :
                    side == 2 ? clock->zeroCount : side == 3 ? clock->sdkCount : clock->maintenanceCount;
            const auto first = count > events.size() ? count - events.size() : 0;
            for (auto i = first; i < count; ++i) {
                const auto& e = events[i % events.size()];
                trace.insert(trace.end(), {side, e.begin, e.end, e.frames, e.before, e.after, e.result,
                        e.sourceFirst, e.sourceLast, e.destinationFirst});
            }
        }
        // The app lifetime write lease and joined owners make this final ledger stable.
        trace.insert(trace.end(), {5, clock->generation, clock->queue.active_silence(),
                clock->queue.idle_silence(), clock->queue.capacity_dropped(), clock->queue.retained(),
                clock->queue.max_age(), clock->sdkXrun.load(), clock->error.load(), clock->frames.load()});
    }
    clock->queue.clear();
    delete clock;
    if (env == nullptr) return nullptr;
    auto result = env->NewLongArray(static_cast<jsize>(trace.size()));
    if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(trace.size()), trace.data());
    return result;
}
}
extern "C" JNIEXPORT void JNICALL
Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_closeNative(JNIEnv*, jclass, jlong handle) {
    closeClock(nullptr, handle);
}

// Separate entry point and caller identity, shared bounded queue/callback contract.
extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_startNative(JNIEnv* env, jclass cls, jlong handle) {
    auto* clock = cast(handle);
    // Prime only the already allocated SDK buffer, with silence while no consumer exists.
    clock->estimatedAhead.store(primeSilence(clock));
    clock->sdkStartNs.store(nowNs());
    return Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_startNative(env, cls, handle);
}
extern "C" JNIEXPORT void JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_pumpNative(JNIEnv*, jclass, jlong handle) {
    auto* clock = cast(handle);
    std::array<int16_t, 240> silence{};
    const int capacity = AAudioStream_getBufferSizeInFrames(clock->stream);
    const int idleBurst = AAudioStream_getFramesPerBurst(clock->stream);
    warpnect::audio::MicrophoneSdkWriteClock sdkClock(capacity, 48000);
    const auto entry = nowNs();
    auto epoch = clock->sdkStartNs.load();
    if (epoch <= 0 || epoch > entry) { clock->error.store(AAUDIO_ERROR_INVALID_STATE); return; }
    sdkClock.start(clock->estimatedAhead.load(), epoch);
    __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMic",
            "MIC_SDK_PUMP_START epoch_ns=%lld entry_ns=%lld elapsed_ns=%lld primed=%d policy=BOUNDED_MISSING",
            static_cast<long long>(epoch), static_cast<long long>(entry),
            static_cast<long long>(entry - epoch), clock->estimatedAhead.load());
    unsigned correctionReports = 0;
    bool estimatedEmpty = false;
    int64_t previousLoop = nowNs();
    while (!clock->stopPump.load() && clock->error.load() == 0) {
        const auto loop = nowNs();
        if (clock->windowSince.load() > 0 && loop - previousLoop > clock->maxActiveLoopGap.load()) {
            clock->maxActiveLoopGap.store(loop - previousLoop);
        }
        previousLoop = loop;
        if (clock->flushPending.exchange(false)) {
            auto result = AAudioStream_requestPause(clock->stream);
            if (result == AAUDIO_OK) result = awaitState(clock->stream, AAUDIO_STREAM_STATE_PAUSED);
            if (result == AAUDIO_OK) result = AAudioStream_requestFlush(clock->stream);
            if (result == AAUDIO_OK) result = awaitState(clock->stream, AAUDIO_STREAM_STATE_FLUSHED);
            if (result == AAUDIO_OK) {
                const auto primed = primeSilence(clock);
                epoch = nowNs();
                result = AAudioStream_requestStart(clock->stream);
                sdkClock.start(primed, epoch);
            }
            if (result != AAUDIO_OK) clock->error.store(result);
            continue;
        }
        const auto version = clock->sdkObservationVersion.load(std::memory_order_acquire);
        const auto sampled = clock->sdkSampleBegin.load();
        const auto read = clock->sdkRead.load();
        const auto state = clock->sdkObservedState.load();
        const auto now = nowNs();
        const auto nominal = sdkClock.ahead(now);
        if (version > 0 && (version & 1) == 0 && sampled >= epoch && state == AAUDIO_STREAM_STATE_STARTED &&
                version == clock->sdkObservationVersion.load(std::memory_order_acquire)) {
            sdkClock.bound_from_read_position(clock->sdkWritten.load(), read, sampled, now,
                    warpnect::audio::MicrophoneDestinationQueue::kFreshNs);
        }
        const auto queued = sdkClock.ahead(now);
        if (queued != nominal && correctionReports++ < 12) {
            __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMic",
                    "MIC_SDK_CLOCK_BOUND generation=%lld;now_ns=%lld;sample_ns=%lld;read=%lld;written=%lld;"
                    "nominal=%d;bounded=%d;epoch_ns=%lld",
                    static_cast<long long>(clock->generation), static_cast<long long>(now),
                    static_cast<long long>(sampled), static_cast<long long>(read),
                    static_cast<long long>(clock->sdkWritten.load()), nominal, queued, static_cast<long long>(epoch));
        }
        clock->estimatedAhead.store(queued);
        const auto decisionWindow = clock->windowSince.load(std::memory_order_acquire);
        const auto decisionNs = nowNs();
        const bool emptyNow = decisionWindow > 0 && clock->queue.retained() == 0 &&
            sdkClock.maintenance_due(true, 240, idleBurst, decisionNs);
        if (emptyNow && !estimatedEmpty) clock->estimatedEmptyEpisodes.fetch_add(1);
        estimatedEmpty = emptyNow;
#ifndef NDEBUG
        static std::atomic<unsigned> emptyReports{0};
        if (emptyNow && emptyReports.fetch_add(1) < 16) {
            __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMic",
                    "MIC_ACTIVE_EMPTY policy=%s ns=%lld window=%lld read=%lld written=%lld xrun=%d",
                    "BOUNDED_MISSING",
                    static_cast<long long>(decisionNs), static_cast<long long>(decisionWindow),
                    static_cast<long long>(clock->sdkRead.load()), static_cast<long long>(clock->sdkWritten.load()),
                    clock->sdkXrun.load());
        }
#endif
        const bool missingPcmDue = clock->queue.retained() == 0 &&
            sdkClock.active_maintenance_due(decisionWindow > 0, 240, decisionNs);
        if (clock->queue.retained() > 0 || missingPcmDue ||
                sdkClock.idle_maintenance_due(decisionWindow > 0, 240, idleBurst, decisionNs)) {
            const auto begin = nowNs();
            const auto before = clock->queue.retained();
            int written = 0;
            int accepted = 0;
            int64_t first = -1, last = -1, oldest = 0;
            const auto callsBefore = clock->sdkWriteCalls;
            if (before > 0) {
                clock->writingReal = true;
                written = clock->queue.transfer(240, begin, nonblockingWrite, clock, nowNs);
                clock->writingReal = false;
                accepted = clock->queue.render_accepted();
                first = clock->queue.render_first(); last = clock->queue.render_last(); oldest = clock->queue.render_oldest();
            } else {
                // Prefer a producer that arrived after the clock decision over replacement.
                if (missingPcmDue && clock->queue.retained() > 0) continue;
#ifndef NDEBUG
                static std::atomic<unsigned> maintenanceReports{0};
                if (clock->windowSince.load() > 0 && maintenanceReports.fetch_add(1) < 16) {
                    __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMic",
                        "MIC_MAINTENANCE_DEADLINE begin_ns=%lld estimated_ahead=%d sdk_written=%lld "
                        "sdk_read=%lld sample_begin_ns=%lld sample_end_ns=%lld last_positive_ns=%lld",
                        static_cast<long long>(begin), queued,
                        static_cast<long long>(clock->sdkWritten.load()), static_cast<long long>(clock->sdkRead.load()),
                        static_cast<long long>(clock->sdkSampleBegin.load()), static_cast<long long>(clock->sdkSampleEnd.load()),
                        static_cast<long long>(clock->last.load()));
                }
#endif
                written = nonblockingWrite(clock, silence.data(), 240);
                const auto afterWindow = clock->windowSince.load(std::memory_order_acquire);
#ifndef NDEBUG
                static std::atomic<unsigned> selectionReports{0};
                if (written > 0 && afterWindow > 0 && selectionReports.fetch_add(1) < 16) {
                    __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMic",
                        "MIC_SILENCE_SELECTION decision_ns=%lld begin_ns=%lld end_ns=%lld "
                        "selected_window=%lld after_window=%lld frames=%d estimated_ahead=%d",
                        static_cast<long long>(decisionNs), static_cast<long long>(begin),
                        static_cast<long long>(nowNs()), static_cast<long long>(decisionWindow),
                        static_cast<long long>(afterWindow), written, queued);
                }
#endif
                if (written > 0) {
                    clock->queue.silence(written, afterWindow > 0);
                    if (decisionWindow > 0 || afterWindow > 0) {
                        clock->maintenanceEvents[clock->maintenanceCount++ % 1024] = {
                            begin, nowNs(), written, before, clock->queue.retained(),
                            sdkClock.ahead(decisionNs), decisionWindow, afterWindow,
                            clock->sdkWritten.load() - written};
                    }
                }
            }
            const auto ended = nowNs();
            if (written < 0) { clock->error.store(written); continue; }
            if (clock->sdkWriteCalls != callsBefore) {
                sdkClock.written(clock->sdkLastRequested, clock->sdkLastAccepted, ended);
            }
            if (accepted > 0 && capacity - queued < accepted) clock->acceptedDespiteEstimate.fetch_add(accepted);
            if (oldest != 0 && ended - oldest > clock->maxReturnAge.load()) clock->maxReturnAge.store(ended - oldest);
            if (oldest != 0 && ended - oldest > warpnect::audio::MicrophoneDestinationQueue::kFreshNs) {
                clock->lateCallbackFrames.fetch_add(accepted);
                clock->error.store(AAUDIO_ERROR_TIMEOUT);
            }
            clock->tid.store(gettid());
            if (written > 0) {
                if (clock->windowSince.load() > 0 && !clock->traceFrozen.load()) {
                    clock->callbackEvents[clock->callbackCount++ % 1024] = {
                        begin, ended, written, before, clock->queue.retained(), accepted, first, last, clock->frames.load()};
                    if (before == 0 && accepted == 0) clock->traceFrozen.store(true);
                }
                const auto previous = clock->last.exchange(begin);
                if (previous != 0 && begin - previous > clock->maxGap.load()) clock->maxGap.store(begin - previous);
                clock->callbacks.fetch_add(1); clock->frames.fetch_add(written);
            }
            if (written == 0) { timespec pause{0, 500000}; nanosleep(&pause, nullptr); }
        } else {
            timespec pause{0, 500000};
            nanosleep(&pause, nullptr);
        }
    }
}
extern "C" JNIEXPORT void JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_stopPumpNative(JNIEnv*, jclass, jlong handle) {
    cast(handle)->stopPump.store(true);
}
extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_sampleSdkNative(JNIEnv*, jclass, jlong handle) {
    auto* clock = cast(handle);
    const auto begin = nowNs();
    const auto writtenBefore = clock->sdkWritten.load();
    const auto previousXrun = clock->sdkXrun.load();
    clock->sdkObservationVersion.fetch_add(1, std::memory_order_acq_rel);
    clock->sdkRead.store(AAudioStream_getFramesRead(clock->stream));
    const auto xrun = AAudioStream_getXRunCount(clock->stream);
    clock->sdkXrun.store(xrun);
    const auto end = nowNs();
    clock->sdkSampleBegin.store(begin);
    clock->sdkSampleEnd.store(end);
    clock->sdkObservedState.store(AAudioStream_getState(clock->stream));
    clock->sdkObservationVersion.fetch_add(1, std::memory_order_release);
    const auto elapsed = end - begin;
    if (elapsed > clock->maxCounterProbe.load()) clock->maxCounterProbe.store(elapsed);
    // Endpoint progress reconciles the silence deadline, never PCM admission credits.
    // Keep a bounded history through the first xrun.
    const bool transition = begin < clock->transitionProbeUntil.load();
    if (!clock->sdkTraceFrozen) {
        clock->sdkEvents[clock->sdkCount++ % clock->sdkEvents.size()] = {begin, end, clock->sdkRead.load(),
                writtenBefore, clock->sdkWritten.load(), clock->sdkXrun.load(), clock->windowSince.load(),
                clock->queue.retained(), AAudioStream_getState(clock->stream)};
    }
    // A prior transition freeze must not conceal the first later active SDK xrun.
    if (xrun > previousXrun) {
        clock->sdkTraceFrozen = true;
        clock->traceFrozen.store(true);
        if (clock->sdkXrunReports++ < 16) {
            __android_log_print(ANDROID_LOG_INFO, "WarpnectRemoteMic",
                    "MIC_SDK_XRUN_FIRST generation=%lld;begin_ns=%lld;end_ns=%lld;previous=%d;current=%d;"
                    "read=%lld;written_before=%lld;written_after=%lld;window_ns=%lld;retained=%lld",
                    static_cast<long long>(clock->generation), static_cast<long long>(begin),
                    static_cast<long long>(end), previousXrun, xrun,
                    static_cast<long long>(clock->sdkRead.load()), static_cast<long long>(writtenBefore),
                    static_cast<long long>(clock->sdkWritten.load()), static_cast<long long>(clock->windowSince.load()),
                    static_cast<long long>(clock->queue.retained()));
        }
    }
    // Bounded numeric diagnostic: retain the last SDK read quanta through the
    // first steady xrun, not only the initial 400 ms route transition.
    return transition || clock->windowSince.load() > 0 ? 2 : 100;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_offerNative(
        JNIEnv* env, jclass, jlong handle, jlong generation, jshortArray pcm, jlong enqueued, jlong firstFrame) {
    return offerClock(env, handle, generation, pcm, enqueued, firstFrame);
}
extern "C" JNIEXPORT void JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_windowNative(JNIEnv* env, jclass cls, jlong handle, jlong since) {
    Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_windowNative(env, cls, handle, since);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_snapshotNative(JNIEnv* env, jclass cls, jlong handle) {
    return Java_io_warpnect_debug_directshell_DirectShellNativeMicrophoneClock_snapshotNative(env, cls, handle);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_debug_directshell_AppOwnedMicrophoneClock_closeNative(JNIEnv* env, jclass cls, jlong handle) {
    return closeClock(env, handle);
}
