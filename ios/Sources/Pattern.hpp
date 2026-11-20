// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

namespace flatheadset {
// Writes full-panel RGBA8, left eye first. Coordinates and circles use pixels.
// Rejects invalid dimensions/stride/capacity without writing any bytes.
bool makePattern(std::uint32_t width, std::uint32_t height,
                 std::uint8_t* rgba, std::size_t stride, std::size_t capacity);
}
