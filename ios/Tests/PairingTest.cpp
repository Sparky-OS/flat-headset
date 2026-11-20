// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Pairing/Pairing.hpp"
#include <openssl/ssl.h>
#include <openssl/pem.h>
#include <openssl/provider.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cassert>
#include <csignal>
#include <exception>
#include <iostream>
#include <thread>
using namespace flatheadset;

template<class F> void rejects(F f) { bool failed=false;try {f();}catch(const std::exception&){failed=true;}assert(failed); }

// KDE Connect's TCP listener is the TLS client. AF_UNIX socketpairs exercise the
// complete handshake without opening a listening socket or contacting a device.
void exchange(const Identity& local,const Identity& remote,const std::string& expected,
              const std::string& pin,int64_t version,bool success) {
    int sockets[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,sockets)==0);
    std::exception_ptr peerError;
    std::thread peer([&]{
        SSL_CTX* ctx=nullptr;SSL* tls=nullptr;X509* cert=nullptr;EVP_PKEY* key=nullptr;
        try {
            timeval timeout{5,0};assert(setsockopt(sockets[1],SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))==0);
            std::string line;char c;while(read(sockets[1],&c,1)==1 && c!='\n')line+=c;
            auto identity=parsePacket(line);assert(identity["body"]["targetDeviceId"]==expected);
            ctx=SSL_CTX_new(TLS_method());assert(ctx);
            assert(SSL_CTX_set_min_proto_version(ctx,TLS1_2_VERSION)==1);
            assert(SSL_CTX_set_max_proto_version(ctx,TLS1_2_VERSION)==1);
            BIO* b=BIO_new_mem_buf(remote.certificate.data(),static_cast<int>(remote.certificate.size()));
            cert=PEM_read_bio_X509(b,nullptr,nullptr,nullptr);BIO_free(b);assert(cert);
            b=BIO_new_mem_buf(remote.key.data(),static_cast<int>(remote.key.size()));
            key=PEM_read_bio_PrivateKey(b,nullptr,nullptr,nullptr);BIO_free(b);assert(key);
            assert(SSL_CTX_use_certificate(ctx,cert)==1 && SSL_CTX_use_PrivateKey(ctx,key)==1);
            SSL_CTX_set_verify(ctx,SSL_VERIFY_PEER,[](int,X509_STORE_CTX*){return 1;});
            tls=SSL_new(ctx);assert(tls && SSL_set_fd(tls,sockets[1])==1 && SSL_connect(tls)==1);
            auto reply=identityPacket(remote);reply["body"]["protocolVersion"]=version;
            auto bytes=reply.dump()+"\n";SSL_write(tls,bytes.data(),static_cast<int>(bytes.size()));
            if(success) {
                line.clear();while(SSL_read(tls,&c,1)==1 && c!='\n')line+=c;
                assert(parsePacket(line)["body"]["deviceId"]==local.id);
                bytes=packet("kdeconnect.pair",{{"pair",true}}).dump()+"\n";
                assert(SSL_write(tls,bytes.data(),static_cast<int>(bytes.size()))==static_cast<int>(bytes.size()));
                line.clear();while(SSL_read(tls,&c,1)==1 && c!='\n')line+=c;
                assert(parsePacket(line)["body"]["pair"]==false);
            }
        }catch(...){peerError=std::current_exception();}
        SSL_free(tls);SSL_CTX_free(ctx);EVP_PKEY_free(key);X509_free(cert);close(sockets[1]);
    });
    bool passed=false;
    try {
        PairingChannel channel(sockets[0],local);channel.handshake(expected,pin);
        assert(channel.peerName()=="Flat Headset");assert(channel.certificate()==remote.certificate);
        Json reply;assert(channel.receive(reply,2000) && reply["body"]["pair"]==true);
        channel.send(packet("kdeconnect.pair",{{"pair",false}}));passed=true;
    }catch(const std::exception&){}
    shutdown(sockets[0],SHUT_RDWR);close(sockets[0]);peer.join();
    if(peerError)std::rethrow_exception(peerError);
    assert(passed==success);
}
int main() {
    std::signal(SIGPIPE,SIG_IGN);
    using Phase=PairingState::Phase;
    PairingState state(false);
    state.offer(1000,10);state.incoming({{"pair",true}},1001,11);
    assert(state.phase==Phase::ConfirmOutgoing); // Remote acceptance alone never trusts.
    assert(state.confirm(12).is_null() && state.phase==Phase::Paired);
    state.incoming({{"pair",false}},1002,13);assert(state.phase==Phase::Unpaired);
    state.offer(1000,10);state.confirm(11);assert(state.phase==Phase::Waiting);
    state.incoming({{"pair",true}},1001,12);assert(state.phase==Phase::Paired);
    state.reset();state.incoming({{"pair",true},{"timestamp",1000}},1000,10);
    assert(state.phase==Phase::ConfirmIncoming);assert(state.confirm(11)["body"]["pair"]==true);
    state.reset();state.offer(1000,10);assert(!state.expire(39));assert(state.expire(40));
    assert(state.phase==Phase::Unpaired);rejects([&]{state.confirm(41);});
    rejects([&]{state.incoming({{"pair",true},{"timestamp",0}},2000,10);});
    rejects([&]{state.incoming({{"pair","true"}},1000,10);});
    rejects([&]{state.incoming({{"pair",true}},1000,10);});
    state.incoming({{"pair",true},{"timestamp",1000}},1000,10);assert(state.expire(35));
    state.offer(1000,10);rejects([&]{state.incoming({{"pair",true},{"timestamp",1000}},1000,11);});
    rejects([]{parsePacket(std::string(65537,' '));});
    rejects([]{parsePacket("{\"type\":0,\"body\":{}}");});
    rejects([]{parsePacket("{\"type\":\"x\",\"body\":[]}");});
    rejects([]{parsePacket(std::string(20,'[')+"0"+std::string(20,']'));});
    assert(parsePacket(packet("kdeconnect.pair",{{"pair",false}}).dump())["body"]["pair"]==false);
    const auto local=makeIdentity(),remote=makeIdentity();
    assert(local.id!=remote.id && certificatePin(local.certificate).size()==64);
    const auto code=verificationKey(local.certificate,remote.certificate,1000);
    assert(code.size()==8 && code==verificationKey(remote.certificate,local.certificate,1000));
    assert(code!=verificationKey(local.certificate,remote.certificate,1001));
    exchange(local,remote,remote.id,"",8,true);
    exchange(local,remote,remote.id,certificatePin(remote.certificate),8,true);
    exchange(local,remote,remote.id,certificatePin(local.certificate),8,false);
    exchange(local,remote,local.id,"",8,false);
    exchange(local,remote,remote.id,"",7,false);
    exchange(local,remote,remote.id,"",4294967304LL,false);
    assert(!OSSL_PROVIDER_available(nullptr,"legacy"));
    std::cout<<"PASS: mutual TLS, reconnect pin, changed pin/identity/downgrade refusal, local confirmation, deadlines, packet bounds; no legacy provider\n";
}
