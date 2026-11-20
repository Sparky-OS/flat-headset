// SPDX-License-Identifier: GPL-3.0-or-later
#include "Frames.hpp"
#include <cstring>
#include <limits>
#include <stdexcept>
namespace flatheadset {
Frames::Frames(uint32_t w,uint32_t h):width_(w),height_(h) {
    if(w<2 || w%2 || h<1 || w>7680 || h>4320)throw std::runtime_error("Invalid native frame extent");
}
bool Frames::publish(const uint8_t* p,size_t size,size_t stride,uint32_t w,uint32_t h) noexcept {
    try {
        const size_t row=static_cast<size_t>(width_)*4;
        if(!p || w!=width_ || h!=height_ || stride<row || stride>std::numeric_limits<size_t>::max()/height_ || size<stride*height_)return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if(!active_)return false;
        if(paused_)return true;
        auto frame=std::make_shared<Frame>();frame->width=w;frame->height=h;frame->sequence=++sequence_;
        frame->pixels.resize(row*height_);
        for(size_t y=0;y<height_;++y)std::memcpy(frame->pixels.data()+y*row,p+y*stride,row);
        // One pending complete frame. Replacing it cannot mutate a frame already in use.
        pending_=std::move(frame);return true;
    }catch(...){return false;}
}
std::shared_ptr<const Frame> Frames::take(){std::lock_guard<std::mutex> lock(mutex_);return std::move(pending_);}
void Frames::pause(){std::lock_guard<std::mutex> lock(mutex_);paused_=true;pending_.reset();}
void Frames::resume(){std::lock_guard<std::mutex> lock(mutex_);if(active_)paused_=false;}
void Frames::close(){std::lock_guard<std::mutex> lock(mutex_);active_=false;pending_.reset();}
bool Frames::active() const {std::lock_guard<std::mutex> lock(mutex_);return active_;}
}
