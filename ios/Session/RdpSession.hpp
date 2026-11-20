// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "SessionControl.hpp"
#include "DecoderChoice.hpp"
#include <atomic>
#include <mutex>
#include <thread>
namespace flatheadset {
class RdpSession {
public:
    enum class State { Idle, Connecting, Connected, CertificateRejected, Refused, Failed, Stopped };
    explicit RdpSession(std::unique_ptr<SessionGrant> grant);
    ~RdpSession();
    RdpSession(const RdpSession&)=delete;
    void start();
    void stop();
    std::shared_ptr<Frames> frames() const {return frames_;}
    std::shared_ptr<DecoderChoice> decoderChoice() const {return decoderChoice_;}
    State state() const {return status_.load();}
    // The FreeRDP callback and offline certificate tests use the same entry point.
    bool verifyPeer(const std::string& pem,const std::string& host,unsigned port,unsigned flags);
private:
    struct Native;
    std::unique_ptr<Native> native_;
    std::unique_ptr<SessionGrant> grant_;
    std::shared_ptr<Frames> frames_;
    std::shared_ptr<DecoderChoice> decoderChoice_;
    std::mutex mutex_;
    std::thread worker_;
    std::atomic<bool> stopped_{false};
    std::atomic<State> status_{State::Idle};
    void run();
};
}
