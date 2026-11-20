// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Session/DecoderChoice.hpp"
#include <freerdp/codec/h264.h>
#include <freerdp/codec/color.h>
#include <atomic>
#include <cassert>
#include <chrono>
#include <fstream>
#include <iostream>
#include <thread>
using namespace flatheadset;
static BOOL permission(void* data,H264_SOFTWARE_REASON reason) {
    auto& choice=*static_cast<DecoderChoice*>(data);
    if(reason==H264_SOFTWARE_QUERY)return choice.state()==DecoderChoice::State::Software;
    assert(reason==H264_HARDWARE_INIT_FAILED || reason==H264_HARDWARE_FORMAT_FAILED);
    return choice.hardwareFailed(reason==H264_HARDWARE_INIT_FAILED?DecoderChoice::Failure::Initialization:DecoderChoice::Failure::Format);
}
static void awaiting(DecoderChoice& choice) {
    const auto until=std::chrono::steady_clock::now()+std::chrono::seconds(3);
    while(choice.state()!=DecoderChoice::State::Waiting && std::chrono::steady_clock::now()<until)std::this_thread::yield();
    assert(choice.state()==DecoderChoice::State::Waiting);
}
static void decode(H264_CONTEXT* decoder,const std::vector<uint8_t>& packet) {
    assert(h264_context_reset(decoder,32,16));
    std::vector<uint8_t> pixels(32*16*4,0);const RECTANGLE_16 rect{0,0,32,16};
    const auto rc=avc420_decompress(decoder,packet.data(),static_cast<UINT32>(packet.size()),pixels.data(),PIXEL_FORMAT_BGRA32,32*4,32,16,&rect,1);
    assert(rc>=0);
    for(unsigned y=0;y<16;++y)for(unsigned x=0;x<32;++x){
        const auto* p=pixels.data()+(y*32+x)*4;
        if(x<16)assert(p[2]>240 && p[0]<10 && p[1]<10);
        else assert(p[0]>240 && p[2]<10 && p[1]<10);
    }
}
int main(int argc,char** argv) {
    assert(argc==2);std::ifstream file(argv[1],std::ios::binary);assert(file);
    const std::vector<uint8_t> packet((std::istreambuf_iterator<char>(file)),{});assert(!packet.empty());
    for(auto reason:{H264_HARDWARE_INIT_FAILED,H264_HARDWARE_FORMAT_FAILED})for(bool accept:{false,true}) {
        auto frames=std::make_shared<Frames>(32,16);DecoderChoice choice(frames);
        std::vector<uint8_t> pixels(32*16*4,100);assert(frames->publish(pixels.data(),pixels.size(),128,32,16));
        assert(!choice.useSoftware());
        auto* decoder=h264_context_new_with_decoder_policy(FALSE,permission,&choice);assert(decoder);
        assert(choice.state()==DecoderChoice::State::Hardware);
        std::atomic<bool> finished=false;bool result=false;
        std::thread worker([&]{result=h264_context_request_software(decoder,reason);finished=true;});
        awaiting(choice);assert(!finished && !frames->take());
        assert(frames->publish(pixels.data(),pixels.size(),128,32,16) && !frames->take());
        if(accept)assert(choice.useSoftware());else choice.cancel();
        worker.join();assert(finished && result==accept);
        assert(!choice.useSoftware());
        if(accept) {
            decode(decoder,packet); // Same context, real FFmpeg H.264 software decoder.
            assert(h264_context_request_software(decoder,reason)); // No repeated prompt.
            auto* nextSurface=h264_context_new_with_decoder_policy(FALSE,permission,&choice);assert(nextSurface);
            decode(nextSurface,packet);h264_context_free(nextSurface);
            assert(frames->publish(pixels.data(),pixels.size(),128,32,16) && frames->take());
        }else {assert(!frames->active());assert(!h264_context_request_software(decoder,reason));}
        choice.cancel();h264_context_free(decoder);
        auto freshFrames=std::make_shared<Frames>(32,16);DecoderChoice fresh(freshFrames);
        assert(fresh.state()==DecoderChoice::State::Hardware && !permission(&fresh,H264_SOFTWARE_QUERY));
    }
    // Stop, backgrounding and loss of the paired control link all cancel the waiter.
    for(int i=0;i<3;++i){auto frames=std::make_shared<Frames>(32,16);DecoderChoice c(frames);bool result=true;
        std::thread waiter([&]{result=c.hardwareFailed(DecoderChoice::Failure::Initialization);});awaiting(c);c.cancel();waiter.join();assert(!result && !c.useSoftware() && !frames->active());}
    auto* noPolicy=h264_context_new(FALSE);assert(noPolicy && !h264_context_request_software(noPolicy,H264_HARDWARE_INIT_FAILED));h264_context_free(noPolicy);
    std::cout<<"PASS: both hardware failure reasons pause; explicit software and Back branches; real software decode preserves both eyes; repeated surfaces retain consent only within session; no implicit switch; cancel wakes waiter; fresh session defaults to hardware\n";
}
