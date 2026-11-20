// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Session/RdpSession.hpp"
#include <freerdp/freerdp.h>
#include <cassert>
#include <iostream>
using namespace flatheadset;
template<class F> void rejects(F f){bool failed=false;try{f();}catch(const std::exception&){failed=true;}assert(failed);}
int main() {
    const auto peer=makeIdentity(),local=makeIdentity(),server=makeIdentity(),other=makeIdentity();
    const auto peerPin=certificatePin(peer.certificate),localPin=certificatePin(local.certificate),rdpPin=certificatePin(server.certificate);
    auto caps=packet("kdeconnect.virtualmonitor",{{"protocolVersion",2},{"action","capabilities"},{"supports_remote_desktop",true},{"supports_virt_mon",true},{"nativePanelVersion",1}});
    auto response=[&](const Json& request){return packet("kdeconnect.virtualmonitor.request",{{"protocolVersion",2},{"action","connect"},{"sessionId",request["body"]["sessionId"]},{"protocol","rdp"},{"security","nla"},{"certificateHash","sha256"},{"status","ready"},{"port",3389},{"username","test"},{"password","test-only"},{"certificateFingerprint",rdpPin},{"panel",request["body"]["panel"]}});};
    auto controller=[&]{return std::make_unique<SessionControl>(peer.id,peerPin,localPin,"192.0.2.1",PanelSize{1920,1080,60000});};
    auto begin=[&](SessionControl& c){c.authorize(peerPin);c.receive(caps,0);auto q=c.request(1);assert(!q.is_null());return q;};
    {
        auto c=controller();assert(c->request(0).is_null());rejects([&]{c->authorize(localPin);});rejects([&]{c->capabilities();});
        c->receive(caps,0);assert(!c->takeGrant());
        c->authorize(peerPin);assert(c->request(0).is_null());assert(c->status()==SessionStatus::Unavailable);
        rejects([&]{SessionControl bad(peer.id,peerPin,localPin,"untrusted.example",{1920,1080,60000});});
        rejects([&]{RdpSession bad(nullptr);});
    }
    for(const auto* field:{"protocol","security","certificateHash","certificateFingerprint","username","password","panel","port","status"}) {
        auto c=controller();auto r=response(begin(*c));r["body"].erase(field);c->receive(r,2);assert(!c->takeGrant());assert(c->status()==SessionStatus::Invalid);
    }
    for(const auto& change:Json::array({{{"host","198.51.100.2"}},{{"security","tls"}},{{"port",65536}},{{"port",-1}},{{"port",3389.5}},{{"certificateFingerprint",std::string(64,'X')}},{{"protocolVersion",4294967298ULL}},{{"password",std::string("x\0y",3)}}})) {
        auto c=controller();auto r=response(begin(*c));for(auto i=change.begin();i!=change.end();++i)r["body"][i.key()]=i.value();
        c->receive(r,2);assert(!c->takeGrant());assert(c->status()==SessionStatus::Invalid);
    }
    {
        auto c=controller();auto r=response(begin(*c));r["body"]["panel"]["identity"]=peerPin;c->receive(r,2);assert(!c->takeGrant());
        auto broken=caps;broken["body"]["nativePanelVersion"]=4294967297ULL;c->receive(broken,3);assert(c->status()==SessionStatus::Unavailable);
    }
    {
        auto c=controller();auto q=begin(*c);auto r=response(q);auto stale=r;stale["body"]["sessionId"]="old";c->receive(stale,2);assert(!c->takeGrant());
        c->receive(r,2);auto grant=c->takeGrant();assert(grant && grant->valid());assert(!c->takeGrant());
        c->receive(r,3);assert(!c->takeGrant());assert(!c->connected().is_null());assert(c->connected().is_null());
        auto stop=c->stop();assert(stop["body"]["sessionId"]==q["body"]["sessionId"] && !grant->valid());
        auto next=c->request(4);assert(next["body"]["sessionId"]!=q["body"]["sessionId"]);c->receive(r,5);assert(!c->takeGrant());
        c->receive(response(next),5);grant=c->takeGrant();assert(grant->valid());c->revoke();assert(!grant->valid());c->receive(response(next),6);assert(!c->takeGrant());
    }
    {
        auto c=controller();auto q=begin(*c);c->receive(packet("kdeconnect.virtualmonitor",{{"protocolVersion",2},{"action","status"},{"sessionId",q["body"]["sessionId"]},{"status","awaitingPermission"}}),10);
        assert(c->status()==SessionStatus::Permission);assert(c->expire(46));c->receive(response(q),47);assert(!c->takeGrant());
        c->stop();q=c->request(50);c->receive(response(q),51);auto grant=c->takeGrant();assert(grant->valid());assert(c->expire(81));assert(!grant->valid());
        c->stop();q=c->request(90);c->receive(packet("kdeconnect.virtualmonitor",{{"protocolVersion",2},{"action","status"},{"sessionId",q["body"]["sessionId"]},{"status","failed"}}),91);assert(c->status()==SessionStatus::Refused && !c->takeGrant());
    }
    {
        auto c=controller();auto q=begin(*c);c->receive(response(q),2);auto grant=c->takeGrant();
        c->waitingForDecoderChoice(31);assert(!c->expire(40) && grant->valid());
        c->waitingForDecoderChoice(50);assert(!c->expire(70));assert(c->expire(80) && !grant->valid());
    }
    for(int i=0;i<12;++i) {
        auto c=controller();auto q=begin(*c);c->receive(response(q),2);RdpSession session(c->takeGrant());
        assert(session.verifyPeer(server.certificate,"192.0.2.1",3389,0));
        assert(!session.verifyPeer(other.certificate,"192.0.2.1",3389,0));
        assert(!session.verifyPeer(server.certificate,"198.51.100.2",3389,0));
        assert(!session.verifyPeer(server.certificate,"192.0.2.1",3390,0));
        assert(!session.verifyPeer(server.certificate,"192.0.2.1",3389,VERIFY_CERT_FLAG_REDIRECT));
        assert(!session.verifyPeer(server.certificate,"192.0.2.1",3389,VERIFY_CERT_FLAG_GATEWAY));
        assert(!session.verifyPeer("not a certificate","192.0.2.1",3389,0));
        c->revoke();assert(!session.verifyPeer(server.certificate,"192.0.2.1",3389,0));
        auto frames=session.frames();assert(frames && frames->active());
        session.stop();assert(!frames->active() && !frames->take());session.start();assert(session.state()==RdpSession::State::Stopped);
    }
    std::cout<<"PASS: paired grants, bounded panel/session replies, refusal/timeout/replay/revocation, exact RDP pins, twelve native context lifecycles; no network connection attempted\n";
}
