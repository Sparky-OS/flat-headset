// SPDX-License-Identifier: GPL-3.0-or-later
#include "Pattern.hpp"
#include <algorithm>
#include <limits>

namespace flatheadset {
bool makePattern(std::uint32_t width, std::uint32_t height,
                 std::uint8_t* rgba, std::size_t stride, std::size_t capacity)
{
    if (!rgba || width < 2 || (width & 1) || !height ||
        std::numeric_limits<std::size_t>::max() / width < 4 ||
        stride < static_cast<std::size_t>(width) * 4 ||
        height > std::numeric_limits<std::size_t>::max() / stride ||
        capacity < stride * height) {
        return false;
    }
    const auto eyeWidth = width / 2;
    const double radius = std::min(eyeWidth, height) * 0.30;
    const auto cell = std::max(1u, std::min(eyeWidth, height) / 16);
    const auto glyphScale = std::max(1u, std::min(eyeWidth, height) / 40);
    // Five-by-seven L and R; generated marks remain language-independent eye IDs.
    const std::uint8_t letters[2][7] = {
        {16, 16, 16, 16, 16, 16, 31},
        {30, 17, 17, 30, 20, 18, 17}
    };
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const unsigned eye = x / eyeWidth;
            const auto localX = x % eyeWidth;
            const double dx = localX + 0.5 - eyeWidth * 0.5;
            const double dy = y + 0.5 - height * 0.5;
            const double d2 = dx * dx + dy * dy;
            const bool circle = d2 >= radius * radius && d2 < (radius + 2) * (radius + 2);
            const bool edge = localX == 0 || localX + 1 == eyeWidth || y == 0 || y + 1 == height;
            const bool grid = localX % cell == 0 || y % cell == 0;
            const auto gx = localX / glyphScale;
            const auto gy = y / glyphScale;
            const bool letter = gx >= 2 && gx < 7 && gy >= 2 && gy < 9 &&
                (letters[eye][gy - 2] & (1u << (6 - gx)));
            auto* pixel = rgba + static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 4;
            pixel[0] = static_cast<std::uint8_t>(eye ? 90 : 10);
            pixel[1] = static_cast<std::uint8_t>(eye ? 35 : 65);
            pixel[2] = static_cast<std::uint8_t>(eye ? 10 : 90);
            if (grid) { pixel[0] += 25; pixel[1] += 25; pixel[2] += 25; }
            if (circle || edge || letter) { pixel[0] = pixel[1] = pixel[2] = 255; }
            pixel[3] = 255;
        }
    }
    return true;
}
}
