// SPDX-License-Identifier: GPL-3.0-or-later
#include "Pattern.hpp"
#include "Launch.hpp"
#include <cassert>
#include <cstdint>
#include <iostream>
#include <limits>
#include <vector>
#include <utility>

int main() {
    assert(flatheadset::isCatchLaunch("flatheadset://catch"));
    for(const auto* bad:{"flatheadset://catch?password=secret","flatheadset://catch?host=192.0.2.1","flatheadset://catch#pin","flatheadset://other","https://catch","flatheadset://catch/"})assert(!flatheadset::isCatchLaunch(bad));
    // Native panel plus unrelated aspect ratios; row guards expose overruns.
    for (const auto& dimensions : {std::pair{1920u, 1080u}, {2208u, 1242u}, {100u, 70u}, {2u, 1u}}) {
        const auto [width, height] = dimensions;
        const size_t stride = width * 4 + 16;
        std::vector<uint8_t> image(stride * height + 32, 0xA5);
        assert(flatheadset::makePattern(width, height, image.data(), stride, stride * height));
        for (unsigned y = 0; y < height; ++y) {
            for (unsigned x = 0; x < width; ++x) {
                assert(image[y * stride + x * 4 + 3] == 255);
                if (x == 0 || x == width / 2 - 1 || x == width / 2 || x == width - 1 || y == 0 || y == height - 1) {
                    assert(image[y * stride + x * 4] == 255); // All four eye edges survive.
                }
            }
            for (size_t p = width * 4; p < stride; ++p) { assert(image[y * stride + p] == 0xA5); }
        }
        for (size_t p = stride * height; p < image.size(); ++p) { assert(image[p] == 0xA5); }
        if (width == 1920) {
            auto channel = [&](unsigned x, unsigned y, unsigned c) { return image[y * stride + x * 4 + c]; };
            assert(channel(401, 401, 2) > channel(401, 401, 0));
            assert(channel(1361, 401, 0) > channel(1361, 401, 2));
            // The circle has equal pixel radius horizontally and vertically, not normalized-eye aspect.
            assert(channel(768, 541, 0) == 255);
            assert(channel(479, 828, 0) == 255);
            assert(channel(1728, 541, 0) == 255);
        }
    }
    std::vector<uint8_t> guard(64, 0xA5);
    assert(!flatheadset::makePattern(3, 1, guard.data(), 12, guard.size()));
    assert(!flatheadset::makePattern(2, 0, guard.data(), 8, guard.size()));
    assert(!flatheadset::makePattern(2, 1, nullptr, 8, guard.size()));
    assert(!flatheadset::makePattern(2, 2, guard.data(), 7, guard.size()));
    assert(!flatheadset::makePattern(2, 2, guard.data(), 8, 15));
    assert(!flatheadset::makePattern(2, 2, guard.data(), std::numeric_limits<size_t>::max(), guard.size()));
    for (auto byte : guard) { assert(byte == 0xA5); }
    std::cout << "PASS: eye identity, complete edges, pixel geometry, row guards and rejected input\n";
}
