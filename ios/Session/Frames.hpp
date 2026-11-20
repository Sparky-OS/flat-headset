// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
namespace flatheadset {
struct Frame {
    uint32_t width, height;
    uint64_t sequence;
    std::vector<uint8_t> pixels; // tightly packed BGRA, complete native panel
};
class Frames {
public:
    Frames(uint32_t width,uint32_t height);
    bool publish(const uint8_t* pixels,size_t size,size_t stride,uint32_t width,uint32_t height) noexcept;
    std::shared_ptr<const Frame> take();
    void pause();
    void resume();
    void close();
    bool active() const;
private:
    const uint32_t width_,height_;
    mutable std::mutex mutex_;
    bool active_=true, paused_=false;
    uint64_t sequence_=0;
    std::shared_ptr<const Frame> pending_;
};
}
