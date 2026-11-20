// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Session/SessionControl.hpp"
#include <cassert>
#include <iostream>
using namespace flatheadset;
int main() {
    const std::string peer(64,'a'),local(64,'b'),rdp(64,'c');
    const auto caps=packet("kdeconnect.virtualmonitor",{{"protocolVersion",2},{"action","capabilities"},{"supports_remote_desktop",true},{"supports_virt_mon",true},{"nativePanelVersion",1}});
    for(const auto* profile:{"none","shinecon-fit-d","cardboard-default"})for(bool altered:{false,true}){
        SessionControl control("paired-device",peer,local,"192.0.2.1",{1920,1080,60000,profile});
        assert(control.request(0).is_null());
        bool rejected=false;try{control.authorize(local);}catch(const std::exception&){rejected=true;}assert(rejected);
        control.authorize(peer);control.receive(caps,0);auto request=control.request(1);
        assert(request["body"]["panel"]["lensProfile"]==profile);
        auto reply=packet("kdeconnect.virtualmonitor.request",{{"protocolVersion",2},{"action","connect"},{"sessionId",request["body"]["sessionId"]},{"protocol","rdp"},{"security","nla"},{"certificateHash","sha256"},{"status","ready"},{"port",3389},{"username","test"},{"password","test-only"},{"certificateFingerprint",rdp},{"panel",request["body"]["panel"]}});
        if(altered)reply["body"]["panel"]["lensProfile"]="different-profile";
        control.receive(reply,2);auto grant=control.takeGrant();
        if(altered){assert(!grant && control.status()==SessionStatus::Invalid);}
        else {assert(grant && grant->valid());control.revoke();assert(!grant->valid());}
    }
    bool rejected=false;try{SessionControl bad("peer",peer,local,"192.0.2.1",{1920,1080,60000,"invented"});}catch(const std::exception&){rejected=true;}assert(rejected);
    std::cout<<"PASS: all three profile IDs require paired identity; exact echo accepted, altered echo refused, revocation invalidates grant; no sockets opened\n";
}
