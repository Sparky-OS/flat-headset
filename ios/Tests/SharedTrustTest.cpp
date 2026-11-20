// SPDX-License-Identifier: GPL-3.0-or-later
#include "../Pairing/Pairing.hpp"
#include <openssl/pem.h>
#include <openssl/core_names.h>
#include <cassert>
#include <csignal>
#include <iostream>
using namespace flatheadset;
template<class F> void expectRefusal(F f){bool rejected=false;try{f();}catch(...){rejected=true;}assert(rejected);}
void exchange(const Identity&,const Identity&,const std::string&,const std::string&,int64_t,bool);
int main(){
    std::signal(SIGPIPE,SIG_IGN);
    EVP_PKEY* key=EVP_EC_gen("prime256v1");assert(key);
    X509* cert=X509_new();assert(cert);
    assert(X509_set_version(cert,2)==1 && ASN1_INTEGER_set(X509_get_serialNumber(cert),1)==1);
    assert(X509_gmtime_adj(X509_getm_notBefore(cert),-60) && X509_gmtime_adj(X509_getm_notAfter(cert),3600));
    assert(X509_set_pubkey(cert,key)==1);
    auto* name=X509_NAME_new();assert(name);const std::string id="kde-connect-test-identity";
    assert(X509_NAME_add_entry_by_txt(name,"CN",MBSTRING_ASC,reinterpret_cast<const unsigned char*>(id.data()),id.size(),-1,0)==1);
    assert(X509_set_subject_name(cert,name)==1 && X509_set_issuer_name(cert,name)==1 && X509_sign(cert,key,EVP_sha512())>0);X509_NAME_free(name);
    auto encode=[&]{const auto size=i2d_X509(cert,nullptr);assert(size>0);std::string der(size,'\0');auto* p=reinterpret_cast<unsigned char*>(der.data());assert(i2d_X509(cert,&p)==size);return der;};
    auto der=encode();std::string raw(97,'\0');size_t written=0;BIGNUM* scalar=nullptr;
    assert(EVP_PKEY_get_octet_string_param(key,OSSL_PKEY_PARAM_PUB_KEY,reinterpret_cast<unsigned char*>(raw.data()),65,&written)==1 && written==65);
    assert(EVP_PKEY_get_bn_param(key,OSSL_PKEY_PARAM_PRIV_KEY,&scalar)==1);
    assert(BN_bn2binpad(scalar,reinterpret_cast<unsigned char*>(raw.data()+65),32)==32);BN_clear_free(scalar);
    const auto imported=importKdeIdentity(der,raw);assert(imported.id==id && certificateFromDer(der)==imported.certificate);
    const auto remote=makeIdentity();
    exchange(imported,remote,remote.id,certificatePin(remote.certificate),8,true);
    exchange(imported,remote,remote.id,certificatePin(imported.certificate),8,false);
    BIO* b=BIO_new_mem_buf(imported.key.data(),imported.key.size());auto* importedKey=PEM_read_bio_PrivateKey(b,nullptr,nullptr,nullptr);BIO_free(b);
    assert(importedKey && EVP_PKEY_eq(key,importedKey)==1);EVP_PKEY_free(importedKey);
    expectRefusal([&]{importKdeIdentity(der,raw.substr(1));});auto wrong=raw;wrong[96]^=1;expectRefusal([&]{importKdeIdentity(der,wrong);});
    expectRefusal([&]{importKdeIdentity(der+"x",raw);});expectRefusal([&]{importKdeIdentity("bad",raw);});
    assert(X509_gmtime_adj(X509_getm_notAfter(cert),-1) && X509_sign(cert,key,EVP_sha512())>0);
    expectRefusal([&]{importKdeIdentity(encode(),raw);});
    const auto host=certificatePin(imported.certificate);const auto peer=certificatePin(makeIdentity().certificate);
    SharedTrust trust(host,peer);assert(trust.matches(host,peer));assert(!trust.matches(host,""));assert(!trust.matches("",peer));
    assert(!trust.matches(peer,peer));assert(!trust.matches(host,host));
    expectRefusal([&]{SharedTrust missing(host,"");});expectRefusal([&]{SharedTrust bad("",peer);});expectRefusal([&]{SharedTrust bad(host,std::string(64,'z'));});
    assert(kdeAccessGroup("TEAM.XTL-TEAM.org.sparkyos.flatheadset")=="TEAM.XTL-TEAM.org.sparkyos.kdeconnect");
    assert(kdeAccessGroup("PREFIX.org.sparkyos.flatheadset")=="PREFIX.org.sparkyos.kdeconnect");
    expectRefusal([]{kdeAccessGroup("org.sparkyos.flatheadset");});expectRefusal([]{kdeAccessGroup("TEAM.other");});
    EVP_PKEY_free(key);X509_free(cert);
    std::cout<<"PASS: canonical P-256 identity import and mutual TLS; bad key/DER/dates refused; same trust accepted; absent/revoked/replaced peer or identity refused; signing group derivation\n";
}
