// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "../Pairing/Pairing.hpp"
#include <atomic>
#include <memory>
namespace flatheadset {
struct PanelSize { int width, height, refreshMilliHz; };
enum class SessionStatus { Untrusted, Unavailable, Idle, Waiting, Permission, Starting,
    Connecting, Connected, Refused, Invalid, Certificate, Failed, Timeout, Stopped };
class RdpSession;
class SessionControl;
// Only a matching reply on the paired control connection can create a grant.
// Revocation invalidates every copy of its lease, including a running RDP client.
class SessionGrant {
    friend class SessionControl;
    friend class RdpSession;
    SessionGrant() = default;
    std::string host_, username_, password_, fingerprint_;
    int port_=0, width_=0, height_=0;
    std::shared_ptr<std::atomic<bool>> live_;
public:
    ~SessionGrant();
    SessionGrant(const SessionGrant&)=delete;
    bool valid() const { return live_ && live_->load(); }
};
class SessionControl {
public:
    // Values come from the verified KDE Connect connection, never a received host field.
    SessionControl(std::string peerId,std::string peerPin,std::string localPin,
                   std::string connectedAddress,PanelSize panel);
    ~SessionControl();
    void authorize(const std::string& storedPin);
    void revoke();
    Json capabilities() const;
    Json capabilityRequest() const;
    Json request(int64_t now);
    void receive(const Json& value,int64_t now);
    std::unique_ptr<SessionGrant> takeGrant();
    Json connected();
    Json stop(SessionStatus reason=SessionStatus::Stopped);
    bool expire(int64_t now);
    SessionStatus status() const { return status_; }
    const Json& panel() const { return panel_; }
private:
    bool trusted_=false, available_=false;
    std::string peerId_,peerPin_,localPin_,address_,sessionId_;
    Json panel_;
    SessionStatus status_=SessionStatus::Untrusted;
    int64_t deadline_=0;
    std::unique_ptr<SessionGrant> grant_;
    std::shared_ptr<std::atomic<bool>> live_;
    void invalidate();
    void fail(SessionStatus reason);
    Json command(const char* action) const;
};
}
