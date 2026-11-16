#ifndef WARPNECT_AUDIO_WIRE_TIMING_DEBUG_H_
#define WARPNECT_AUDIO_WIRE_TIMING_DEBUG_H_

#if defined(__ANDROID__) && !defined(NDEBUG)
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <android/log.h>
#include <linux/errqueue.h>
#include <linux/net_tstamp.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>
#include "audio_wire_timing_samples.h"

namespace warpnect::scl {

class AudioWireTimingDebug final {
  public:
    static std::unique_ptr<AudioWireTimingDebug> create(int fd, bool sender) noexcept {
        char property[PROP_VALUE_MAX]{};
        if (__system_property_get("debug.warpnect.audio_wire_timing", property) <= 0 ||
            std::strcmp(property, "1") != 0) return {};
        auto probe = std::unique_ptr<AudioWireTimingDebug>(new (std::nothrow) AudioWireTimingDebug(fd, sender));
        if (!probe || !probe->storage_) return {};
        return probe;
    }

    ~AudioWireTimingDebug() noexcept { finish(); }
    static std::uint64_t now(clockid_t clock) noexcept {
        timespec value{};
        if (clock_gettime(clock, &value) != 0) return 0;
        return static_cast<std::uint64_t>(value.tv_sec) * 1000000000ULL + value.tv_nsec;
    }
    void send(std::span<const std::byte> wire, std::uint32_t sequence,
              std::uint64_t begin, std::uint64_t end, std::uint64_t real, bool success) noexcept {
        if (finished_) return;
        if (!success) samples_.invalidate_tx_mapping();
        if (within_budget(end)) {
            samples_.append({.fingerprint = AudioWireTimingSamples::fingerprint(wire),
                .begin_ns = begin, .end_ns = end, .realtime_ns = real,
                .sequence = sequence, .bytes = static_cast<std::uint32_t>(wire.size()),
                .authenticated = true});
        }
        drain();
    }
    void receive(std::span<const std::byte> wire, std::uint64_t mono,
                 std::uint64_t software, std::uint64_t age) noexcept {
        current_recorded_ = false;
        if (finished_ || !within_budget(mono)) return;
        current_recorded_ = samples_.append({.fingerprint = AudioWireTimingSamples::fingerprint(wire),
            .begin_ns = mono, .end_ns = mono, .realtime_ns = age,
            .software_ns = software, .bytes = static_cast<std::uint32_t>(wire.size())});
    }
    void authenticate(std::uint32_t sequence) noexcept {
        if (current_recorded_) samples_.authenticate_last(sequence);
        current_recorded_ = false;
    }
    void finish() noexcept {
        if (finished_) return;
        if (sender_) { drain(); disable(); }
        finished_ = true;
        char path[192]{};
        std::snprintf(path, sizeof(path), "/data/data/io.warpnect/cache/audio-wire-%s-%d-%d-%llu.csv",
            sender_ ? "tx" : "rx", getpid(), fd_, static_cast<unsigned long long>(start_ns_));
        FILE* file = std::fopen(path, "w");
        if (file) {
            std::fprintf(file, "META,sender=%d,option=%d,option_errno=%d,count=%zu,overflow=%llu,unmatched=%llu,mapping=%d,other_errors=%u,receive_errors=%u\n",
                sender_, option_result_, option_errno_, samples_.samples().size(),
                static_cast<unsigned long long>(samples_.overflow()), static_cast<unsigned long long>(samples_.unmatched()),
                samples_.mapping_valid(), other_errors_, receive_errors_);
            std::fprintf(file, "id,sequence,fingerprint,begin_mono,end_mono,send_realtime_or_rx_age,tx_sched_or_rx_software,tx_software,observed_mono,bytes,authenticated\n");
            std::uint32_t id = 0;
            for (const auto& s : samples_.samples()) {
                std::fprintf(file, "%u,%u,%016llx,%llu,%llu,%llu,%llu,%llu,%llu,%u,%d\n", id++, s.sequence,
                    static_cast<unsigned long long>(s.fingerprint), static_cast<unsigned long long>(s.begin_ns),
                    static_cast<unsigned long long>(s.end_ns), static_cast<unsigned long long>(s.realtime_ns),
                    static_cast<unsigned long long>(s.software_ns), static_cast<unsigned long long>(s.tx_software_ns),
                    static_cast<unsigned long long>(s.observed_ns), s.bytes, s.authenticated);
            }
            std::fclose(file);
        }
        __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
            "AUDIO_WIRE_TIMING_FINISH sender=%d count=%zu file=%s saved=%d", sender_, samples_.samples().size(), path, file != nullptr);
    }

  private:
    static constexpr std::size_t capacity = 32768;
    AudioWireTimingDebug(int fd, bool sender) noexcept
        : fd_(fd), sender_(sender), start_ns_(now(CLOCK_MONOTONIC)),
          storage_(new (std::nothrow) AudioWireTimingSample[capacity]),
          samples_(storage_ ? std::span<AudioWireTimingSample>(storage_.get(), capacity) : std::span<AudioWireTimingSample>{}) {
        if (!storage_) return;
        if (sender_) {
            const int flags = SOF_TIMESTAMPING_TX_SCHED | SOF_TIMESTAMPING_TX_SOFTWARE |
                SOF_TIMESTAMPING_SOFTWARE | SOF_TIMESTAMPING_OPT_ID | SOF_TIMESTAMPING_OPT_TSONLY;
            option_result_ = setsockopt(fd_, SOL_SOCKET, SO_TIMESTAMPING, &flags, sizeof(flags));
            option_errno_ = option_result_ == 0 ? 0 : errno;
            if (option_result_ != 0) samples_.invalidate_tx_mapping();
            enabled_ = option_result_ == 0;
        }
        __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
            "AUDIO_WIRE_TIMING_START sender=%d fd=%d tid=%d mono=%llu real=%llu capacity=%zu option=%d errno=%d",
            sender_, fd_, gettid(), static_cast<unsigned long long>(start_ns_),
            static_cast<unsigned long long>(now(CLOCK_REALTIME)), capacity, option_result_, option_errno_);
    }
    bool within_budget(std::uint64_t now_ns) noexcept {
        if (now_ns >= start_ns_ && now_ns - start_ns_ < 120000000000ULL) return true;
        if (sender_) disable();
        return false;
    }
    void disable() noexcept {
        if (!enabled_) return;
        const int flags = 0;
        setsockopt(fd_, SOL_SOCKET, SO_TIMESTAMPING, &flags, sizeof(flags));
        enabled_ = false;
    }
    void drain() noexcept {
        if (option_result_ != 0) return;
        // TSONLY: fixed-size ancillary data, no media bytes/logging and no blocking observer.
        // IP_RECVERR is not enabled: the probe does not subscribe to ICMP errors.
        for (int i = 0; i < 8; ++i) {
            alignas(cmsghdr) std::array<std::byte, 512> control{};
            msghdr message{};
            message.msg_control = control.data(); message.msg_controllen = control.size();
            if (recvmsg(fd_, &message, MSG_ERRQUEUE | MSG_DONTWAIT) < 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK) ++receive_errors_;
                break;
            }
            timespec stamp{};
            sock_extended_err error{};
            bool found = false;
            if ((message.msg_flags & MSG_CTRUNC) != 0) { ++other_errors_; continue; }
            for (auto* c = CMSG_FIRSTHDR(&message); c; c = CMSG_NXTHDR(&message, c)) {
                if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_TIMESTAMPING &&
                    c->cmsg_len >= CMSG_LEN(sizeof(timespec) * 3)) std::memcpy(&stamp, CMSG_DATA(c), sizeof(stamp));
                if ((c->cmsg_level == SOL_IP || c->cmsg_level == SOL_IPV6) &&
                    (c->cmsg_type == IP_RECVERR || c->cmsg_type == IPV6_RECVERR) &&
                    c->cmsg_len >= CMSG_LEN(sizeof(error))) {
                    std::memcpy(&error, CMSG_DATA(c), sizeof(error)); found = true;
                }
            }
            if (!found || error.ee_origin != SO_EE_ORIGIN_TIMESTAMPING || stamp.tv_sec <= 0 ||
                stamp.tv_nsec < 0 || stamp.tv_nsec >= 1000000000) { ++other_errors_; continue; }
            samples_.attach_tx(error.ee_data, error.ee_info,
                static_cast<std::uint64_t>(stamp.tv_sec) * 1000000000ULL + stamp.tv_nsec, now(CLOCK_MONOTONIC));
        }
    }
    int fd_;
    bool sender_;
    std::uint64_t start_ns_;
    std::unique_ptr<AudioWireTimingSample[]> storage_;
    AudioWireTimingSamples samples_;
    int option_result_ = 0, option_errno_ = 0;
    std::uint32_t other_errors_ = 0, receive_errors_ = 0;
    bool enabled_ = false, finished_ = false, current_recorded_ = false;
};

} // namespace warpnect::scl
#endif
#endif
