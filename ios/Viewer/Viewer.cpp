// SPDX-License-Identifier: GPL-3.0-or-later
#include "Viewer.hpp"
#include "../ThirdParty/Cardboard/lens_distortion.h"
#include "../ThirdParty/Cardboard/qrcode/cardboard_v1/cardboard_v1.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>

namespace flatheadset {
namespace {
[[noreturn]] void invalid(){throw std::runtime_error("Invalid viewer profile");}
struct Reader {
    const std::vector<uint8_t>& data; size_t pos=0;
    uint64_t varint(){uint64_t n=0;for(int i=0;i<10;++i){if(pos==data.size())invalid();auto b=data[pos++];if(i==9 && b>1)invalid();n|=uint64_t(b&127)<<(7*i);if(!(b&128))return n;}invalid();}
    size_t length(){auto n=varint();if(n>data.size()-pos)invalid();return static_cast<size_t>(n);}
    float number(){if(data.size()-pos<4)invalid();uint32_t n=0;for(int i=0;i<4;++i)n|=uint32_t(data[pos++])<<(8*i);float f;std::memcpy(&f,&n,4);if(!std::isfinite(f))invalid();return f;}
};
}
float phoneDpi(const std::string& model){
    static const std::pair<const char*,float> values[]={
#include "PhoneDpi.inc"
    };
    for(const auto& row:values)if(model==row.first)return row.second;
    return 0;
}
const char* Viewer::profileId() const {
    switch(mode){case LensMode::None:return "none";case LensMode::ShineconFitD:return "shinecon-fit-d";case LensMode::Cardboard:return "cardboard-default";}
    invalid();
}
cardboard::DeviceParams parseViewer(const std::vector<uint8_t>& bytes){
    if(bytes.empty() || bytes.size()>16384)invalid();
    cardboard::DeviceParams p;Reader r{bytes};unsigned seen=0;
    while(r.pos<bytes.size()){
        auto tag=r.varint();auto field=tag>>3,wire=tag&7;if(!field || field>0x1fffffff)invalid();
        if(field==1 || field==2){if(wire!=2)invalid();auto n=r.length();if(n>256)invalid();auto s=std::string(reinterpret_cast<const char*>(bytes.data()+r.pos),n);r.pos+=n;if(field==1)p.vendor=s;else p.model=s;}
        else if(field==3 || field==4 || field==6){if(wire!=5)invalid();float f=r.number();if(f<=0 || f>1)invalid();if(field==3){p.distance=f;seen|=1;}else if(field==4){p.separation=f;seen|=2;}else {p.tray=f;seen|=4;}}
        else if(field==5 || field==7){auto& values=field==5?p.fov:p.coefficients;
            if(wire==5)values.push_back(r.number());
            else if(wire==2){auto n=r.length();if(n%4 || n>64)invalid();auto end=r.pos+n;while(r.pos<end)values.push_back(r.number());}
            else invalid();
            if(values.size()>16)invalid();
        }else if(field==11){if(wire!=0)invalid();auto n=r.varint();if(n>2)invalid();p.alignment=static_cast<int>(n);}
        else {switch(wire){case 0:r.varint();break;case 1:if(bytes.size()-r.pos<8)invalid();r.pos+=8;break;case 2:{auto n=r.length();r.pos+=n;break;}case 5:if(bytes.size()-r.pos<4)invalid();r.pos+=4;break;default:invalid();}}
    }
    if(seen!=7 || p.fov.size()!=4)invalid();
    for(float angle:p.fov)if(angle<=0 || angle>=90)invalid();
    for(float k:p.coefficients)if(std::fabs(k)>1000000)invalid();
    return p;
}
std::vector<uint8_t> viewerBytes(const std::string& url){
    if(url.empty())return cardboard::qrcode::getCardboardV1DeviceParams();
    const std::string https="https://google.com/cardboard/cfg?p=",http="http://google.com/cardboard/cfg?p=";
    size_t start=url.rfind(https,0)==0?https.size():url.rfind(http,0)==0?http.size():0;
    if(!start || url.size()>24000)invalid();
    std::vector<uint8_t> out;uint32_t acc=0;int bits=0;bool padded=false;size_t pads=0,count=0;
    for(size_t i=start;i<url.size();++i){char c=url[i];if(c=='='){padded=true;++pads;continue;}if(padded)invalid();
        int v=c>='A'&&c<='Z'?c-'A':c>='a'&&c<='z'?c-'a'+26:c>='0'&&c<='9'?c-'0'+52:c=='-'?62:c=='_'?63:-1;
        if(v<0)invalid();
        ++count;acc=(acc<<6)|static_cast<unsigned>(v);bits+=6;if(bits>=8){bits-=8;out.push_back(static_cast<uint8_t>(acc>>bits));}
    }
    if(count%4==1 || pads>2 || (pads && (count+pads)%4) || (bits && (acc&((1u<<bits)-1))))invalid();
    parseViewer(out);return out;
}
std::shared_ptr<const Viewer> makeViewer(LensMode mode,const std::string& qr,int width,int height,float dpi){
    if(width<2 || width%2 || height<1 || width>16384 || height>16384)invalid();
    if(mode!=LensMode::None && mode!=LensMode::ShineconFitD && mode!=LensMode::Cardboard)invalid();
    auto result=std::make_shared<Viewer>();result->mode=mode;result->width=width;result->height=height;
    if(mode!=LensMode::Cardboard)return result;
    if(!std::isfinite(dpi) || dpi<=0)throw std::runtime_error("Unknown phone screen dimensions");
    const auto p=parseViewer(viewerBytes(qr));
    const float w=width/dpi*0.0254f,h=height/dpi*0.0254f;
    const float y=p.alignment==1?h/2:p.alignment==0?p.tray-0.003f:h-p.tray-0.003f;
    if(p.separation>=w || y<=0 || y>=h)invalid();
    cardboard::LensDistortion lens(p,w,h);
    for(int eye=0;eye<2;++eye){auto mesh=lens.GetDistortionMesh(static_cast<CardboardEye>(eye));auto& dst=result->eyes[eye];
        for(int i=0;i<mesh.n_vertices;++i){
            LensVertex v{mesh.vertices[2*i],mesh.vertices[2*i+1],mesh.uvs[2*i],1-mesh.uvs[2*i+1]};
            if(!std::isfinite(v.x) || !std::isfinite(v.y) || std::fabs(v.x)>100 || std::fabs(v.y)>100)invalid();
            dst.vertices.push_back(v);
        }
        for(int i=0;i<mesh.n_indices;++i){if(mesh.indices[i]<0 || mesh.indices[i]>=mesh.n_vertices)invalid();dst.indices.push_back(static_cast<uint32_t>(mesh.indices[i]));}
    }
    return result;
}
}
