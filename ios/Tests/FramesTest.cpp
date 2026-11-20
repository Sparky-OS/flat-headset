// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Session/Frames.hpp"
#include <algorithm>
#include <atomic>
#include <cassert>
#include <iostream>
#include <limits>
#include <thread>
using namespace flatheadset;
int main() {
    Frames frames(8,4);std::vector<uint8_t> pixels(40*4,0xff);
    for(size_t y=0;y<4;++y)for(size_t x=0;x<8;++x) {
        auto* p=pixels.data()+y*40+x*4;p[0]=x<4?10:200;p[1]=static_cast<uint8_t>(y);p[2]=static_cast<uint8_t>(x);p[3]=255;
    }
    assert(!frames.publish(nullptr,pixels.size(),40,8,4));
    assert(!frames.publish(pixels.data(),pixels.size()-1,40,8,4));
    assert(!frames.publish(pixels.data(),pixels.size(),31,8,4));
    assert(!frames.publish(pixels.data(),pixels.size(),40,4,8));
    assert(!frames.publish(pixels.data(),pixels.size(),std::numeric_limits<size_t>::max(),8,4));
    assert(frames.publish(pixels.data(),pixels.size(),40,8,4));auto first=frames.take();assert(first && first->pixels.size()==128);
    for(size_t y=0;y<4;++y)for(size_t x=0;x<8;++x) {
        const auto* p=first->pixels.data()+y*32+x*4;assert(p[0]==(x<4?10:200) && p[1]==y && p[2]==x && p[3]==255);
    }
    std::fill(pixels.begin(),pixels.end(),3);assert(first->pixels[0]==10 && !frames.take());
    for(int i=0;i<50;++i){std::fill(pixels.begin(),pixels.end(),static_cast<uint8_t>(i));assert(frames.publish(pixels.data(),pixels.size(),40,8,4));}
    auto latest=frames.take();assert(latest->sequence==51 && latest->pixels[0]==49 && !frames.take());assert(first->pixels[0]==10);
    std::atomic<bool> done=false;
    std::thread producer([&]{for(int i=0;i<1000;++i){std::fill(pixels.begin(),pixels.end(),static_cast<uint8_t>(i));assert(frames.publish(pixels.data(),pixels.size(),40,8,4));}done=true;});
    uint64_t sequence=51;
    while(!done){if(auto frame=frames.take()){assert(frame->sequence>sequence);sequence=frame->sequence;assert(std::all_of(frame->pixels.begin(),frame->pixels.end(),[&](uint8_t p){return p==frame->pixels[0];}));}}
    producer.join();frames.close();assert(!frames.active() && !frames.take());assert(!frames.publish(pixels.data(),pixels.size(),40,8,4));assert(first->pixels[0]==10);
    std::cout<<"PASS: complete BGRA eye geometry, padded rows, ownership, latest-only delivery, concurrent coherent frames, revocation and malformed dimensions/extent\n";
}
