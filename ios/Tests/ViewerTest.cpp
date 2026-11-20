// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Viewer/Viewer.hpp"
#include "../Viewer/Sampling.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <stdexcept>
using namespace flatheadset;
template<class F> void rejects(F f){bool failed=false;try{f();}catch(const std::exception&){failed=true;}assert(failed);}
int main(){
    assert(phoneDpi("iPhone10,2")==401 && phoneDpi("iPhone10,5")==401 && phoneDpi("unknown")==0);
    const auto bytes=viewerBytes("");const auto p=parseViewer(bytes);
    assert(p.vendor=="Google, Inc." && p.model=="Cardboard v1" && p.fov.size()==4 && p.coefficients.size()==2);
    const std::string qr="https://google.com/cardboard/cfg?p=CgZHb29nbGUSEkNhcmRib2FyZCBJL08gMjAxNR0rGBU9JQHegj0qEAAASEIAAEhCAABIQgAASEJYADUpXA89OggeZnc-Ej6aPlAAYAM";
    assert(parseViewer(viewerBytes(qr)).model=="Cardboard I/O 2015");
    for(const auto& url:{std::string(),qr})for(const auto& dimensions:{std::pair{1920,1080},std::pair{2340,1080},std::pair{1280,720}}){
        const auto v=makeViewer(LensMode::Cardboard,url,dimensions.first,dimensions.second,401);
        assert(std::string(v->profileId())=="cardboard-default");
        for(const auto& eye:v->eyes){assert(eye.vertices.size()==1600 && eye.indices.size()==3158);for(const auto& vertex:eye.vertices)assert(std::isfinite(vertex.x)&&std::isfinite(vertex.y)&&vertex.u>=0&&vertex.u<=1&&vertex.v>=0&&vertex.v<=1);}
        assert(v->eyes[0].vertices[0].x<v->eyes[1].vertices[0].x);
    }
    rejects([&]{makeViewer(LensMode::Cardboard,"",1920,1080,0);});
    rejects([&]{makeViewer(static_cast<LensMode>(3),"",1920,1080,401);});
    rejects([&]{makeViewer(LensMode::None,"",1919,1080,401);});
    for(auto mode:{LensMode::None,LensMode::ShineconFitD}){auto v=makeViewer(mode,"",1920,1080,0);assert(v->eyes[0].vertices.empty());}
    assert(std::string(makeViewer(LensMode::ShineconFitD,"",1920,1080,401)->profileId())=="shinecon-fit-d");
    for(const auto& bad:{"https://example.org/cardboard/cfg?p=AAAA","https://google.com/cardboard/cfg?p=A","https://google.com/cardboard/cfg?p=AA=A","https://google.com/cardboard/cfg?p=AB","https://google.com/cardboard/cfg?p=!!!"})rejects([&]{viewerBytes(bad);});
    for(size_t i=0;i<bytes.size();++i){auto truncated=bytes;truncated.resize(i);try{auto parsed=parseViewer(truncated);assert(parsed.fov.size()==4 && parsed.distance>0);}catch(const std::exception&){} }
    rejects([&]{parseViewer(std::vector<uint8_t>(16385,0));});
    rejects([&]{parseViewer({0x1d,0,0,0x80,0x7f});}); // Infinity.
    rejects([&]{parseViewer({0x2a,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff});});
    auto bad=bytes;bad.insert(bad.end(),{0x58,3});rejects([&]{parseViewer(bad);});
    auto unknown=bytes;unknown.insert(unknown.end(),{0x85,0x01,0xff,0xff,0xff,0xff});assert(parseViewer(unknown).model==p.model);
    size_t black=0;
    for(unsigned y=0;y<1080;++y)for(unsigned x=0;x<1920;++x){auto s=fittedSample(x+0.5f,y+0.5f,1920,1080);assert(s.eye==x/960);if(!s.visible){++black;continue;}assert(s.x>=s.eye*960 && s.x<=(s.eye+1)*960-1 && s.y>=0 && s.y<=1079);}
    assert(black>0);
    auto centre=fittedSample(480.5f,540.5f,1920,1080);assert(centre.x==480.5f && centre.y==540.5f);
    std::cout<<"PASS: actual V1 and I/O 2015 profiles, mesh geometry, native extents, unknown DPI refusal, malformed inputs, per-eye fitted bounds\n";
}
