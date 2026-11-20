// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "Frames.hpp"
#include <condition_variable>
namespace flatheadset {
// One instance per paired RDP session. Never persisted in the pairing store.
class DecoderChoice {
public:
    enum class State { Hardware, Waiting, Software, Closed };
    enum class Failure { Initialization, Format };
    explicit DecoderChoice(std::shared_ptr<Frames> frames):frames_(std::move(frames)){}
    State state() const {std::lock_guard<std::mutex> lock(mutex_);return state_;}
    bool hardwareFailed(Failure) {
        std::unique_lock<std::mutex> lock(mutex_);
        if(state_==State::Hardware){frames_->pause();state_=State::Waiting;}
        changed_.wait(lock,[&]{return state_!=State::Waiting;});
        return state_==State::Software;
    }
    bool useSoftware() {
        std::lock_guard<std::mutex> lock(mutex_);
        if(state_!=State::Waiting)return false;
        state_=State::Software;frames_->resume();changed_.notify_all();return true;
    }
    void cancel() {
        std::lock_guard<std::mutex> lock(mutex_);
        state_=State::Closed;frames_->close();changed_.notify_all();
    }
private:
    std::shared_ptr<Frames> frames_;
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    State state_=State::Hardware;
};
}
