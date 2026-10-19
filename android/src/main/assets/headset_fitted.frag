// SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
precision highp float;
uniform sampler2D frame;
uniform vec2 panelSize;
uniform float fittedStrength;
uniform float flipY;
varying vec2 coord;

void main()
{
    vec2 uv = vec2(coord.x, mix(coord.y, 1.0 - coord.y, flipY));
    if (fittedStrength == 0.0) {
        gl_FragColor = texture2D(frame, uv);
        return;
    }
    vec2 eyeSize = vec2(panelSize.x * 0.5, panelSize.y);
    vec2 pixel = floor(uv * panelSize);
    float eye = step(eyeSize.x, pixel.x);
    pixel.x -= eye * eyeSize.x;
    vec2 centre = floor(eyeSize * 0.5);
    vec2 offset = pixel - centre;
    float radius2 = 4.0 * dot(offset, offset) / dot(eyeSize, eyeSize);
    // Match FFmpeg lenscorrection's bilinear pixel convention, including its half-pixel offset.
    vec2 source = centre + offset * (1.0 + fittedStrength * radius2) + 0.5;
    if (any(lessThan(source, vec2(0.0))) || any(greaterThanEqual(source, eyeSize))) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    source = min(source, eyeSize - 1.0);
    source.x += eye * eyeSize.x;
    gl_FragColor = texture2D(frame, (source + 0.5) / panelSize);
}
