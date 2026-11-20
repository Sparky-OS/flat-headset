// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "Viewer.hpp"
#include "../ThirdParty/Cardboard/qrcode/cardboard_v1/cardboard_v1.h"
#include <stdexcept>
namespace flatheadset {
inline std::string profileLink(const std::vector<uint8_t>& bytes) {
    parseViewer(bytes);
    const char* alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out="https://google.com/cardboard/cfg?p=";
    uint32_t acc=0;int bits=0;
    for(uint8_t b:bytes){acc=(acc<<8)|b;bits+=8;while(bits>=6){bits-=6;out+=alphabet[(acc>>bits)&63];}}
    if(bits)out+=alphabet[(acc<<(6-bits))&63];
    return out;
}
inline std::string normalizeProfileLink(std::string url) {
    if(url.size()>24000)throw std::runtime_error("Invalid profile link");
    if(url.rfind("http://",0)==0)url.replace(0,7,"https://");
    if(url.rfind("https://",0)!=0)url="https://"+url;
    if(url=="https://g.co/cardboard")return profileLink(cardboard::qrcode::getCardboardV1DeviceParams());
    if(url.rfind("https://www.google.com/cardboard/cfg?p=",0)==0)url.erase(8,4);
    return profileLink(viewerBytes(url));
}
inline std::string profileHandoff(const std::string& link) {
    const std::string canonical=normalizeProfileLink(link);
    return "flatheadset://viewer?p="+canonical.substr(canonical.find("?p=")+3);
}
inline std::string profileFromHandoff(const std::string& url) {
    const std::string prefix="flatheadset://viewer?p=";
    if(url.rfind(prefix,0)!=0 || url.size()>24000)throw std::runtime_error("Invalid profile handoff");
    return normalizeProfileLink("https://google.com/cardboard/cfg?p="+url.substr(prefix.size()));
}
}
