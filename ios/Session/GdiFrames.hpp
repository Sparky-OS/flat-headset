// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "Frames.hpp"
#include <freerdp/gdi/gdi.h>
namespace flatheadset {
inline bool beginGdiPaint(rdpGdi* g) {
    if(!g || !g->primary || !g->primary->hdc || !g->primary->hdc->hwnd || !g->primary->hdc->hwnd->invalid)return false;
    auto* window=g->primary->hdc->hwnd;
    window->invalid->null=TRUE;window->ninvalid=0;return true;
}
inline bool endGdiPaint(rdpGdi* g,Frames& frames) {
    if(!g || !g->primary || !g->primary->hdc || !g->primary->hdc->hwnd || !g->primary->hdc->hwnd->invalid)return false;
    if(g->primary->hdc->hwnd->invalid->null)return true;
    return frames.publish(g->primary_buffer,static_cast<size_t>(g->stride)*g->height,g->stride,g->width,g->height);
}
}
