// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Session/GdiFrames.hpp"
#include <freerdp/client.h>
#include <freerdp/gdi/region.h>
#include <cassert>
#include <iostream>
using namespace flatheadset;
int main() {
    RDP_CLIENT_ENTRY_POINTS entry{};entry.Size=sizeof(entry);entry.Version=RDP_CLIENT_INTERFACE_VERSION;
    entry.ContextSize=sizeof(rdpClientContext);
    auto* context=freerdp_client_context_new(&entry);assert(context);
    assert(freerdp_settings_set_uint32(context->settings,FreeRDP_DesktopWidth,8));
    assert(freerdp_settings_set_uint32(context->settings,FreeRDP_DesktopHeight,4));
    assert(gdi_init(context->instance,PIXEL_FORMAT_BGRA32));
    auto* g=context->gdi;Frames frames(8,4);
    assert(!beginGdiPaint(nullptr) && !endGdiPaint(nullptr,frames));
    assert(beginGdiPaint(g) && endGdiPaint(g,frames) && !frames.take());
    for(unsigned i=0;i<40;++i) {
        assert(beginGdiPaint(g));assert(g->primary->hdc->hwnd->ninvalid==0);
        for(unsigned y=0;y<4;++y)for(unsigned x=0;x<8;++x) {
            auto* pixel=g->primary_buffer+y*g->stride+x*4;
            pixel[0]=x<4?10:200;pixel[1]=y;pixel[2]=i;pixel[3]=255;
        }
        assert(gdi_InvalidateRegion(g->primary->hdc,0,0,8,4));assert(endGdiPaint(g,frames));
        auto frame=frames.take();assert(frame && frame->sequence==i+1);
        for(unsigned y=0;y<4;++y)for(unsigned x=0;x<8;++x) {
            const auto* pixel=frame->pixels.data()+(y*8+x)*4;
            assert(pixel[0]==(x<4?10:200) && pixel[1]==y && pixel[2]==i && pixel[3]==255);
        }
    }
    Frames wrong(4,8);assert(!endGdiPaint(g,wrong));
    frames.close();assert(!endGdiPaint(g,frames) && !frames.take());
    gdi_free(context->instance);freerdp_client_context_free(context);
    std::cout<<"PASS: real FreeRDP GDI BGRA surface through production paint handoff, forty eye pairs, dirty-region reset, empty paint, wrong extent and closed receiver; no connection\n";
}
