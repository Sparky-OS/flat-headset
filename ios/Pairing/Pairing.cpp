// SPDX-License-Identifier: GPL-3.0-or-later
// KDE Connect wire behavior follows Albert Vaca Cintora and Vineet Garg's Android backend.
#include "Pairing.hpp"
#include <openssl/ssl.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
#include <openssl/sha.h>
#include <sys/socket.h>
#include <poll.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <ctime>
#include <stdexcept>
#include <vector>

namespace flatheadset {
namespace {
constexpr size_t packetLimit = 64 * 1024;
void require(bool good, const char* reason) { if (!good) throw std::runtime_error(reason); }
using Certificate = std::unique_ptr<X509, decltype(&X509_free)>;
using Key = std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)>;
using Bio = std::unique_ptr<BIO, decltype(&BIO_free)>;
Certificate readCertificate(const std::string& pem) {
    Bio bio(BIO_new_mem_buf(pem.data(), static_cast<int>(pem.size())), BIO_free);
    Certificate cert(bio ? PEM_read_bio_X509(bio.get(), nullptr, nullptr, nullptr) : nullptr, X509_free);
    require(bool(cert), "Invalid certificate"); return cert;
}
std::string bioString(BIO* bio) {
    char* bytes = nullptr; const long count = BIO_get_mem_data(bio, &bytes);
    require(count > 0, "Empty identity"); return {bytes, static_cast<size_t>(count)};
}
std::string hex(const unsigned char* bytes, size_t count) {
    constexpr char digits[] = "0123456789abcdef"; std::string value;
    for (size_t i=0; i<count; ++i) { value += digits[bytes[i]>>4]; value += digits[bytes[i]&15]; }
    return value;
}
std::vector<unsigned char> publicKey(X509* cert) {
    auto* key = X509_get_X509_PUBKEY(cert); const int size = i2d_X509_PUBKEY(key, nullptr);
    require(size > 0, "Invalid public key"); std::vector<unsigned char> out(size); auto* p=out.data();
    require(i2d_X509_PUBKEY(key,&p)==size, "Invalid public key"); return out;
}
bool validId(const std::string& id) {
    return id.size()>=8 && id.size()<=64 && std::all_of(id.begin(),id.end(),[](unsigned char c){
        return (c>='a'&&c<='z') || (c>='A'&&c<='Z') || (c>='0'&&c<='9') || c=='_' || c=='-';
    });
}
std::string name(const Json& body) {
    require(body.contains("deviceName") && body["deviceName"].is_string(), "Missing device name");
    auto value=body["deviceName"].get<std::string>();
    require(!value.empty() && value.size()<=128 && std::none_of(value.begin(),value.end(),[](unsigned char c){return c<32 || c==127;}), "Invalid device name");
    return value;
}
}
Identity makeIdentity() {
    unsigned char random[16]; require(RAND_bytes(random,sizeof(random))==1,"Random source failed");
    Identity result; result.id=hex(random,sizeof(random));
    Key key(EVP_RSA_gen(2048),EVP_PKEY_free); require(bool(key),"Identity key generation failed");
    Certificate cert(X509_new(),X509_free); require(bool(cert),"Certificate allocation failed");
    require(X509_set_version(cert.get(),2)==1 && ASN1_INTEGER_set(X509_get_serialNumber(cert.get()),1)==1,"Certificate setup failed");
    require(X509_gmtime_adj(X509_getm_notBefore(cert.get()),-86400) && X509_gmtime_adj(X509_getm_notAfter(cert.get()),315360000),"Certificate dates failed");
    require(X509_set_pubkey(cert.get(),key.get())==1,"Certificate key failed");
    std::unique_ptr<X509_NAME, decltype(&X509_NAME_free)> subject(X509_NAME_new(),X509_NAME_free);
    require(subject && X509_NAME_add_entry_by_txt(subject.get(),"CN",MBSTRING_ASC,reinterpret_cast<const unsigned char*>(result.id.c_str()),-1,-1,0)==1,"Certificate name failed");
    require(X509_set_subject_name(cert.get(),subject.get())==1 && X509_set_issuer_name(cert.get(),subject.get())==1 && X509_sign(cert.get(),key.get(),EVP_sha256())>0,"Certificate signing failed");
    Bio out(BIO_new(BIO_s_mem()),BIO_free); require(bool(out),"Identity allocation failed");
    require(PEM_write_bio_X509(out.get(),cert.get())==1,"Certificate encoding failed"); result.certificate=bioString(out.get());
    require(BIO_reset(out.get())==1 && PEM_write_bio_PrivateKey(out.get(),key.get(),nullptr,nullptr,0,nullptr,nullptr)==1,"Key encoding failed"); result.key=bioString(out.get());
    return result;
}
Json packet(const std::string& type, Json body) {
    const auto now=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    return {{"id",now},{"type",type},{"body",std::move(body)}};
}
Json parsePacket(const std::string& line) {
    require(!line.empty() && line.size()<=packetLimit,"Packet size limit");
    auto value=Json::parse(line,[](int depth,Json::parse_event_t,Json&){require(depth<=16,"Packet depth limit");return true;});
    require(value.is_object() && value.contains("type") && value["type"].is_string() && value.contains("body") && value["body"].is_object(),"Invalid packet");
    require(value["type"].get_ref<const std::string&>().size()<=128,"Packet type limit"); return value;
}
Json identityPacket(const Identity& identity,const std::string& target) {
    Json body={{"deviceId",identity.id},{"deviceName","Flat Headset"},{"deviceType","phone"},{"protocolVersion",8},
               {"incomingCapabilities",Json::array({"kdeconnect.virtualmonitor","kdeconnect.virtualmonitor.request"})},{"outgoingCapabilities",Json::array({"kdeconnect.virtualmonitor","kdeconnect.virtualmonitor.request"})}};
    if (!target.empty()) { body["targetDeviceId"]=target; body["targetProtocolVersion"]=8; }
    return packet("kdeconnect.identity",body);
}
std::string certificatePin(const std::string& pem) {
    auto cert=readCertificate(pem); unsigned char digest[EVP_MAX_MD_SIZE]; unsigned int length=0;
    require(X509_digest(cert.get(),EVP_sha256(),digest,&length)==1 && length==32,"Certificate digest failed"); return hex(digest,length);
}
std::string verificationKey(const std::string& a,const std::string& b,int64_t timestamp) {
    auto ca=readCertificate(a),cb=readCertificate(b); auto pa=publicKey(ca.get()),pb=publicKey(cb.get());
    if(pa<pb) std::swap(pa,pb);
    pa.insert(pa.end(),pb.begin(),pb.end());
    const auto stamp=std::to_string(timestamp); pa.insert(pa.end(),stamp.begin(),stamp.end());
    unsigned char hash[SHA256_DIGEST_LENGTH]; require(SHA256(pa.data(),pa.size(),hash)!=nullptr,"Verification key failed");
    auto result=hex(hash,4); std::transform(result.begin(),result.end(),result.begin(),[](unsigned char c){return c>='a'&&c<='f'?c-'a'+'A':c;}); return result;
}
struct PairingChannel::State {
    int socket; SSL_CTX* ctx=nullptr; SSL* ssl=nullptr;
    explicit State(int fd):socket(fd) {}
    ~State(){ SSL_free(ssl); SSL_CTX_free(ctx); }
};
PairingChannel::PairingChannel(int fd,const Identity& identity):state_(new State(fd)),identity_(identity) {
    require(fd>=0,"Invalid socket");
    timeval timeout{5,0}; require(setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))==0 && setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout))==0,"Socket timeout failed");
#ifdef SO_NOSIGPIPE
    int yes=1; require(setsockopt(fd,SOL_SOCKET,SO_NOSIGPIPE,&yes,sizeof(yes))==0,"Socket signal policy failed");
#endif
    state_->ctx=SSL_CTX_new(TLS_method()); require(state_->ctx,"TLS context failed");
    require(SSL_CTX_set_min_proto_version(state_->ctx,TLS1_2_VERSION)==1 && SSL_CTX_set_max_proto_version(state_->ctx,TLS1_2_VERSION)==1,"TLS version failed");
    auto cert=readCertificate(identity.certificate); Bio keyBio(BIO_new_mem_buf(identity.key.data(),static_cast<int>(identity.key.size())),BIO_free);
    Key key(keyBio?PEM_read_bio_PrivateKey(keyBio.get(),nullptr,nullptr,nullptr):nullptr,EVP_PKEY_free);
    require(key && SSL_CTX_use_certificate(state_->ctx,cert.get())==1 && SSL_CTX_use_PrivateKey(state_->ctx,key.get())==1 && SSL_CTX_check_private_key(state_->ctx)==1,"TLS identity failed");
    // KDE Connect uses self-signed peers. No application packet is allowed until
    // handshake() checks the certificate, identity and any previously saved pin.
    SSL_CTX_set_verify(state_->ctx,SSL_VERIFY_PEER|SSL_VERIFY_FAIL_IF_NO_PEER_CERT,[](int,X509_STORE_CTX*){return 1;});
    state_->ssl=SSL_new(state_->ctx); require(state_->ssl && SSL_set_fd(state_->ssl,fd)==1,"TLS socket failed");
}
PairingChannel::~PairingChannel()=default;
void PairingChannel::handshake(const std::string& expectedId,const std::string& savedPin) {
    require(validId(expectedId),"Invalid peer identity");
    const auto line=identityPacket(identity_,expectedId).dump()+"\n"; size_t offset=0;
    while(offset<line.size()) { auto n=::send(state_->socket,line.data()+offset,line.size()-offset,0);require(n>0,"Identity send failed");offset+=n; }
    // TCP initiator is the TLS server in KDE Connect's LAN protocol.
    require(SSL_accept(state_->ssl)==1,"TLS handshake failed");
    Certificate cert(SSL_get1_peer_certificate(state_->ssl),X509_free); require(bool(cert),"Peer has no certificate");
    auto* key=X509_get0_pubkey(cert.get());
    require(key && EVP_PKEY_get_security_bits(key)>=112 && X509_verify(cert.get(),key)==1,"Invalid peer certificate signature");
    const auto now=std::time(nullptr);
    const int before=ASN1_TIME_cmp_time_t(X509_get0_notBefore(cert.get()),now);
    const int after=ASN1_TIME_cmp_time_t(X509_get0_notAfter(cert.get()),now);
    require((before==-1 || before==0) && (after==0 || after==1),"Peer certificate date failed");
    auto* subject=X509_get_subject_name(cert.get()); const int index=X509_NAME_get_index_by_NID(subject,NID_commonName,-1);
    require(index>=0 && X509_NAME_get_index_by_NID(subject,NID_commonName,index)<0,"Invalid peer certificate name");
    auto* cn=X509_NAME_ENTRY_get_data(X509_NAME_get_entry(subject,index));
    const auto* bytes=ASN1_STRING_get0_data(cn);const int size=ASN1_STRING_length(cn);
    require(size==static_cast<int>(expectedId.size()) && std::equal(expectedId.begin(),expectedId.end(),bytes),"Peer identity does not match certificate");
    Bio pem(BIO_new(BIO_s_mem()),BIO_free);require(pem && PEM_write_bio_X509(pem.get(),cert.get())==1,"Peer certificate encoding failed");peerCertificate_=bioString(pem.get());
    const auto pin=certificatePin(peerCertificate_);
    require(savedPin.empty() || (savedPin.size()==pin.size() && CRYPTO_memcmp(savedPin.data(),pin.data(),pin.size())==0),"Peer certificate changed");
    send(identityPacket(identity_)); Json remote;require(receive(remote,5000),"Secure identity timeout");
    require(remote["type"]=="kdeconnect.identity" && remote["body"].value("deviceId",std::string())==expectedId && remote["body"].contains("protocolVersion") && remote["body"]["protocolVersion"].is_number_integer() && remote["body"]["protocolVersion"]==8,"Secure identity mismatch or protocol downgrade");
    peerName_=name(remote["body"]);
}
void PairingChannel::send(const Json& value) {
    const auto bytes=value.dump()+"\n";require(bytes.size()<=packetLimit,"Packet size limit");size_t offset=0;
    while(offset<bytes.size()) { const int n=SSL_write(state_->ssl,bytes.data()+offset,static_cast<int>(bytes.size()-offset)); require(n>0,"TLS write failed");offset+=n; }
}
bool PairingChannel::receive(Json& value,int timeoutMs) {
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::milliseconds(timeoutMs);
    for(;;) {
        const auto end=pending_.find('\n');
        if(end!=std::string::npos) {value=parsePacket(pending_.substr(0,end));pending_.erase(0,end+1);return true;}
        require(pending_.size()<packetLimit,"Packet size limit");
        const auto left=std::chrono::duration_cast<std::chrono::milliseconds>(deadline-std::chrono::steady_clock::now()).count();
        pollfd fd{state_->socket,POLLIN,0};
        if(!SSL_pending(state_->ssl)) { const int ready=poll(&fd,1,static_cast<int>(std::max<int64_t>(0,left)));require(ready>=0,"Socket poll failed");if(!ready)return false; }
        char buffer[4096];const int n=SSL_read(state_->ssl,buffer,sizeof(buffer));require(n>0,"TLS connection closed");pending_.append(buffer,n);
        require(pending_.size()<=packetLimit,"Packet size limit");
    }
}
void PairingState::offer(int64_t wall,int64_t mono) { require(phase==Phase::Unpaired,"Pair already active");phase=Phase::ConfirmOutgoing;timestamp=wall;deadline_=mono+30; }
Json PairingState::confirm(int64_t mono) {
    require(!expire(mono),"Pairing expired");
    if(phase==Phase::ConfirmOutgoing) { phase=remoteAccepted_?Phase::Paired:Phase::Waiting;return Json(); }
    require(phase==Phase::ConfirmIncoming,"No pairing request");phase=Phase::Paired;return packet("kdeconnect.pair",{{"pair",true}});
}
void PairingState::incoming(const Json& body,int64_t wall,int64_t mono) {
    require(body.contains("pair") && body["pair"].is_boolean(),"Invalid pairing packet");
    if(!body["pair"].get<bool>()) {reset();return;}
    if(expire(mono)) throw std::runtime_error("Pairing expired");
    if(phase==Phase::Waiting || phase==Phase::ConfirmOutgoing) {require(!body.contains("timestamp"),"Conflicting pairing requests");remoteAccepted_=true;if(phase==Phase::Waiting)phase=Phase::Paired;return;}
    require(body.contains("timestamp") && body["timestamp"].is_number_integer(),"Pairing timestamp missing");
    const auto stamp=body["timestamp"].get<int64_t>();require(stamp>=wall-1800 && stamp<=wall+1800,"Pairing clock mismatch");
    require(phase!=Phase::ConfirmIncoming,"Duplicate pairing request");timestamp=stamp;phase=Phase::ConfirmIncoming;deadline_=mono+25;
}
bool PairingState::expire(int64_t mono) {if(phase!=Phase::Paired && phase!=Phase::Unpaired && mono>=deadline_){reset();return true;}return false;}
}
