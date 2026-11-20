// SPDX-License-Identifier: GPL-3.0-or-later
// Same pixel mapping in the portable proof and Metal; Android headset_fitted.frag.
#pragma once
#ifdef __METAL_VERSION__
#define FH_FLOOR floor
#else
#include <cmath>
#define FH_FLOOR std::floor
#endif
struct FHSample { float x,y; unsigned eye; bool visible; };
inline FHSample fittedSample(float x,float y,float width,float height) {
    const float ew=width*0.5f;
    const unsigned eye=x>=ew?1u:0u;
    const float px=FH_FLOOR(x)-eye*ew,py=FH_FLOOR(y);
    const float cx=FH_FLOOR(ew*0.5f),cy=FH_FLOOR(height*0.5f);
    const float dx=px-cx,dy=py-cy;
    const float radius2=4.0f*(dx*dx+dy*dy)/(ew*ew+height*height);
    const float scale=1.0f+0.13f*radius2; // Existing shinecon-fit-d, not a QR coefficient.
    float sx=cx+dx*scale+0.5f,sy=cy+dy*scale+0.5f;
    const bool visible=sx>=0 && sy>=0 && sx<ew && sy<height;
    if(sx>ew-1)sx=ew-1;
    if(sy>height-1)sy=height-1;
    return {sx+eye*ew,sy,eye,visible};
}
#undef FH_FLOOR
