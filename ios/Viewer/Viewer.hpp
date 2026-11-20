// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "ProfileParams.hpp"
#include <cstdint>
#include <memory>
namespace flatheadset {
enum class LensMode { None=0, ShineconFitD=1, Cardboard=2 };
struct LensVertex { float x,y,u,v; };
struct EyeMesh { std::vector<LensVertex> vertices; std::vector<uint32_t> indices; };
struct Viewer {
    LensMode mode;
    int width, height;
    std::array<EyeMesh,2> eyes;
    const char* profileId() const;
};
float phoneDpi(const std::string& model);
cardboard::DeviceParams parseViewer(const std::vector<uint8_t>& bytes);
std::vector<uint8_t> viewerBytes(const std::string& fullQrUrl);
std::shared_ptr<const Viewer> makeViewer(LensMode mode,const std::string& fullQrUrl,
                                       int width,int height,float dpi);
}
