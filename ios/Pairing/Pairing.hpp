// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include "../ThirdParty/nlohmann/json.hpp"

namespace flatheadset {
using Json = nlohmann::json;
struct Identity { std::string id, certificate, key; };
Identity makeIdentity();
// Read KDE Connect's canonical Security keychain identity, never mint a second one.
// certificateDER is X.509; privateX963 is P-256's 04 || X || Y || d export.
Identity importKdeIdentity(const std::string& certificateDER, const std::string& privateX963);
std::string certificateFromDer(const std::string& der);
std::string kdeAccessGroup(const std::string& receiverDefaultGroup);
class SharedTrust {
public:
    SharedTrust(std::string identityPin, std::string peerPin);
    bool matches(const std::string& identityPin, const std::string& peerPin) const;
private:
    std::string identityPin_, peerPin_;
};
Json packet(const std::string& type, Json body);
Json parsePacket(const std::string& line);
Json identityPacket(const Identity& identity, const std::string& target = {});
std::string certificatePin(const std::string& pem);
std::string verificationKey(const std::string& first, const std::string& second, int64_t timestamp);

// Borrows the connected socket. The caller owns shutdown/close; one worker uses TLS.
class PairingChannel {
public:
    PairingChannel(int socket, const Identity& identity);
    ~PairingChannel();
    PairingChannel(const PairingChannel&) = delete;
    PairingChannel& operator=(const PairingChannel&) = delete;
    void handshake(const std::string& expectedId, const std::string& savedPin);
    void send(const Json& value);
    bool receive(Json& value, int timeoutMs);
    const std::string& certificate() const { return peerCertificate_; }
    const std::string& peerName() const { return peerName_; }
private:
    struct State;
    std::unique_ptr<State> state_;
    Identity identity_;
    std::string peerCertificate_, peerName_, pending_;
};

class PairingState {
public:
    enum class Phase { Unpaired, ConfirmOutgoing, ConfirmIncoming, Waiting, Paired };
    explicit PairingState(bool pinned) : phase(pinned ? Phase::Paired : Phase::Unpaired) {}
    Phase phase;
    int64_t timestamp = 0;
    void offer(int64_t wallSeconds, int64_t monotonicSeconds);
    Json confirm(int64_t monotonicSeconds);
    void incoming(const Json& body, int64_t wallSeconds, int64_t monotonicSeconds);
    bool expire(int64_t monotonicSeconds);
    void reset() { phase = Phase::Unpaired; timestamp = 0; deadline_ = 0; remoteAccepted_ = false; }
private:
    int64_t deadline_ = 0;
    bool remoteAccepted_ = false;
};
}
