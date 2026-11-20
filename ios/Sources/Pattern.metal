// SPDX-License-Identifier: GPL-3.0-or-later
#include <metal_stdlib>
using namespace metal;

struct PanelVertex {
    float4 position [[position]];
};

vertex PanelVertex fullPanel(uint id [[vertex_id]]) {
    const float2 points[3] = {float2(-1, -1), float2(3, -1), float2(-1, 3)};
    return {float4(points[id], 0, 1)};
}

fragment float4 patternPixel(PanelVertex input [[stage_in]],
                            texture2d<float, access::read> image [[texture(0)]]) {
    // Fragment position is in drawable pixels. The texture has the identical extent.
    return image.read(uint2(input.position.xy));
}

fragment float4 desktopPixel(PanelVertex input [[stage_in]],
                            texture2d<float, access::read> image [[texture(0)]]) {
    // GDI BGRA texture; the complete received pair keeps its original pixel positions.
    return float4(image.read(uint2(input.position.xy)).rgb, 1.0);
}

#include "../Viewer/Sampling.h"

// Interpolate only within the selected eye's own texels.
float4 eyeBilinear(texture2d<float, access::read> image, float2 source, uint eye) {
    const uint ew=image.get_width()/2, h=image.get_height();
    source=clamp(source,float2(eye*ew,0),float2((eye+1)*ew-1,h-1));
    const uint2 a=uint2(floor(source));
    const uint2 b=min(a+1,uint2((eye+1)*ew-1,h-1));
    const float2 f=source-float2(a);
    return float4(mix(mix(image.read(a).rgb,image.read(uint2(b.x,a.y)).rgb,f.x),
                      mix(image.read(uint2(a.x,b.y)).rgb,image.read(b).rgb,f.x),f.y),1);
}
fragment float4 fittedPixel(PanelVertex input [[stage_in]],
                            texture2d<float, access::read> image [[texture(0)]]) {
    const auto p=fittedSample(input.position.x,input.position.y,image.get_width(),image.get_height());
    if(!p.visible)return float4(0,0,0,1);
    return eyeBilinear(image,float2(p.x,p.y),p.eye);
}
struct LensVertex { float x,y,u,v; };
struct LensOutput { float4 position [[position]]; float2 uv; };
vertex LensOutput lensVertex(uint id [[vertex_id]], const device LensVertex* vertices [[buffer(0)]]) {
    const LensVertex v=vertices[id];
    return {float4(v.x,v.y,0,1),float2(v.u,v.v)};
}
fragment float4 lensPixel(LensOutput input [[stage_in]],
                         texture2d<float, access::read> image [[texture(0)]],
                         constant uint& eye [[buffer(0)]]) {
    const float2 size=float2(image.get_width()/2,image.get_height());
    const float2 source=input.uv*size-0.5f+float2(eye*size.x,0);
    return eyeBilinear(image,source,eye);
}
