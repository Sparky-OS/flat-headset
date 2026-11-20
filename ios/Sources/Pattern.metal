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
