// SPDX-License-Identifier: GPL-3.0-or-later
// Wire fields follow KDE Connect's RemoteDesktopPlugin and VirtualMonitorPlugin.
#include "SessionControl.hpp"
#include <openssl/crypto.h>
#include <openssl/rand.h>
#include <arpa/inet.h>
#include <algorithm>
#include <stdexcept>
namespace flatheadset {
namespace {
constexpr auto monitor="kdeconnect.virtualmonitor";
constexpr auto requestType="kdeconnect.virtualmonitor.request";
bool hexPin(const std::string& s) {
    return s.size()==64 && s.find_first_not_of("0123456789abcdef")==std::string::npos;
}
bool numericHost(std::string s) {
    const auto zone=s.find('%');
    if(zone!=std::string::npos) {
        if(s.substr(zone+1).empty() || s.substr(zone+1).find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.")!=std::string::npos)return false;
        s.resize(zone);
    }
    in6_addr a6{};in_addr a4{};
    return inet_pton(AF_INET6,s.c_str(),&a6)==1 || (zone==std::string::npos && inet_pton(AF_INET,s.c_str(),&a4)==1);
}
std::string nonce() {
    unsigned char b[16];if(RAND_bytes(b,sizeof(b))!=1)throw std::runtime_error("Random source failed");
    std::string s;for(auto c:b){s+="0123456789abcdef"[c>>4];s+="0123456789abcdef"[c&15];}return s;
}
bool boundedText(const Json& b,const char* key,size_t limit) {
    if(!b.contains(key) || !b[key].is_string())return false;
    const auto& s=b[key].get_ref<const std::string&>();
    return !s.empty() && s.size()<=limit && std::none_of(s.begin(),s.end(),[](unsigned char c){return c<32 || c==127;});
}
}
SessionGrant::~SessionGrant(){if(!password_.empty())OPENSSL_cleanse(password_.data(),password_.size());}
SessionControl::SessionControl(std::string id,std::string pin,std::string local,std::string address,PanelSize size)
    :peerId_(std::move(id)),peerPin_(std::move(pin)),localPin_(std::move(local)),address_(std::move(address)) {
    if(peerId_.empty() || !hexPin(peerPin_) || !hexPin(localPin_) || !numericHost(address_) ||
       size.width<320 || size.width>7680 || size.width%2 || size.height<240 || size.height>4320 ||
       size.refreshMilliHz<=0 || size.refreshMilliHz>1000000)throw std::runtime_error("Invalid paired panel");
    panel_={{"version",1},{"identity",localPin_},{"name","Flat Headset"},{"width",size.width},{"height",size.height},
            {"refreshMilliHz",size.refreshMilliHz},{"scale",1.0},{"widthMm",0},{"heightMm",0},{"splitEye",true},{"lensProfile","none"}};
}
SessionControl::~SessionControl(){invalidate();}
void SessionControl::invalidate(){if(live_)live_->store(false);grant_.reset();live_.reset();deadline_=0;}
void SessionControl::authorize(const std::string& pin) {
    if(pin.size()!=peerPin_.size() || CRYPTO_memcmp(pin.data(),peerPin_.data(),pin.size())!=0) {
        revoke();throw std::runtime_error("Peer not paired");
    }
    trusted_=true;status_=SessionStatus::Idle;
}
void SessionControl::revoke(){invalidate();trusted_=false;available_=false;sessionId_.clear();status_=SessionStatus::Untrusted;}
Json SessionControl::command(const char* action) const {
    return packet(requestType,{{"protocolVersion",2},{"action",action},{"sessionId",sessionId_}});
}
Json SessionControl::capabilities() const {
    if(!trusted_)throw std::runtime_error("Peer not paired");
    return packet(monitor,{{"protocolVersion",2},{"action","capabilities"},{"supports_rdp",true},
        {"supports_virt_mon",false},{"supports_remote_desktop",false},{"sessionModes",Json::array()},
        {"resolutions",Json::array({{{"resolution",std::to_string(panel_["width"].get<int>())+"x"+std::to_string(panel_["height"].get<int>())},{"scale",1.0}}})},
        {"rdpFeatures",{{"graphicsPipeline",true},{"h264",true},{"avc444",true},{"progressive",true},{"nla",true},{"tls",true},{"tlsFingerprint",true}}}});
}
Json SessionControl::capabilityRequest() const {
    if(!trusted_)throw std::runtime_error("Peer not paired");
    return packet(requestType,{{"protocolVersion",2},{"action","requestCapabilities"}});
}
Json SessionControl::request(int64_t now) {
    if(!trusted_){status_=SessionStatus::Untrusted;return nullptr;}
    if(!sessionId_.empty())return nullptr;
    if(!available_){status_=SessionStatus::Unavailable;return nullptr;}
    invalidate();sessionId_=nonce();deadline_=now+45;status_=SessionStatus::Waiting;
    auto value=command("request");auto& b=value["body"];
    b["mode"]="virtualMonitor";b["width"]=panel_["width"];b["height"]=panel_["height"];b["scale"]=1.0;b["quality"]=70;b["panel"]=panel_;
    return value;
}
void SessionControl::fail(SessionStatus reason){invalidate();status_=reason;}
void SessionControl::receive(const Json& value,int64_t now) {
    if(!trusted_){fail(SessionStatus::Untrusted);return;}
    try {
        const auto& b=value.at("body");const auto type=value.at("type");
        if(type!=monitor && type!=requestType)return;
        if(b.at("protocolVersion")!=2){fail(SessionStatus::Invalid);return;}
        const auto action=b.at("action");
        if(type==monitor && action=="capabilities") {
            const bool supported=b.value("supports_remote_desktop",false) && b.value("supports_virt_mon",false) && b.contains("nativePanelVersion") && b["nativePanelVersion"]==1;
            available_=supported;
            if(sessionId_.empty())status_=supported?SessionStatus::Idle:SessionStatus::Unavailable;
            else if(!supported)fail(SessionStatus::Unavailable);
            return;
        }
        // Old, unsolicited and cross-session replies can never create or replace credentials.
        if(sessionId_.empty() || b.value("sessionId",std::string())!=sessionId_)return;
        if(expire(now))return;
        if(type==monitor && action=="status") {
            const auto state=b.value("status",std::string());
            if(state=="failed")fail(SessionStatus::Refused);
            else if(state=="stopped")fail(SessionStatus::Stopped);
            else if(!live_ && state=="awaitingPermission")status_=SessionStatus::Permission;
            else if(!live_ && state=="starting")status_=SessionStatus::Starting;
            return;
        }
        if(type!=requestType || action!="connect" || live_ ||
           (status_!=SessionStatus::Waiting && status_!=SessionStatus::Permission && status_!=SessionStatus::Starting))return;
        if(b.value("protocol",std::string())!="rdp" || b.value("security",std::string())!="nla" ||
           b.value("certificateHash",std::string())!="sha256" || b.value("status",std::string())!="ready" ||
           !b.contains("panel") || b["panel"]!=panel_ || !b.contains("port") || !b["port"].is_number_integer() || b["port"]<1 || b["port"]>65535 ||
           !boundedText(b,"username",256) || !boundedText(b,"password",4096) || !boundedText(b,"certificateFingerprint",64) ||
           !hexPin(b["certificateFingerprint"].get<std::string>()) || (b.contains("host") && b["host"]!=address_)) {
            fail(SessionStatus::Invalid);return;
        }
        grant_=std::unique_ptr<SessionGrant>(new SessionGrant);
        grant_->host_=address_;grant_->port_=b["port"];grant_->username_=b["username"];grant_->password_=b["password"];
        grant_->fingerprint_=b["certificateFingerprint"];grant_->width_=panel_["width"];grant_->height_=panel_["height"];
        live_=std::make_shared<std::atomic<bool>>(true);grant_->live_=live_;status_=SessionStatus::Connecting;
        deadline_=now+30;
    }catch(const Json::exception&){fail(SessionStatus::Invalid);}
}
std::unique_ptr<SessionGrant> SessionControl::takeGrant(){return std::move(grant_);}
Json SessionControl::connected() {
    if(!trusted_ || !live_ || !live_->load() || status_!=SessionStatus::Connecting)return nullptr;
    deadline_=0;status_=SessionStatus::Connected;return command("clientConnected");
}
Json SessionControl::stop(SessionStatus reason) {
    Json value=sessionId_.empty()?Json():command("stop");invalidate();sessionId_.clear();status_=reason;return value;
}
bool SessionControl::expire(int64_t now) {
    if(deadline_ && now>=deadline_){fail(SessionStatus::Timeout);return true;}return false;
}
}
