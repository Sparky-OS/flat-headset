// SPDX-License-Identifier: GPL-3.0-or-later
#include "RdpSession.hpp"
#include <freerdp/client.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/freerdp.h>
#include <freerdp/error.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/graphics.h>
#include <winpr/synch.h>
#include <openssl/crypto.h>
#include <stdexcept>
namespace flatheadset {
struct RdpSession::Native {
    struct Context {rdpClientContext client;RdpSession* owner;};
    rdpContext* context=nullptr;
    static RdpSession* owner(freerdp* f){return reinterpret_cast<Context*>(f->context)->owner;}
    static int certificate(freerdp* f,const BYTE* pem,size_t length,const char* host,UINT16 port,DWORD flags) {
        if(!pem || !host || length>65536)return 0;
        return owner(f)->verifyPeer(std::string(reinterpret_cast<const char*>(pem),length),host,port,flags)?2:0;
    }
    static BOOL redirect(freerdp*){return FALSE;}
    static SSIZE_T retry(freerdp*,const char*,size_t,void*){return -1;}
    static BOOL authenticate(freerdp*,char**,char**,char**,rdp_auth_reason){return FALSE;}
    static BOOL preConnect(freerdp* f) {
        auto* o=owner(f);if(o->stopped_ || !o->grant_->valid())return FALSE;
        if(PubSub_SubscribeChannelConnected(f->context->pubSub,freerdp_client_OnChannelConnectedEventHandler)<0 ||
           PubSub_SubscribeChannelDisconnected(f->context->pubSub,freerdp_client_OnChannelDisconnectedEventHandler)<0)return FALSE;
        return freerdp_client_load_addins(f->context->channels,f->context->settings);
    }
    static BOOL resize(rdpContext* c) {
        auto* o=reinterpret_cast<Context*>(c)->owner;
        // Native-panel v1 is an exact full-panel pair, never a resized eye.
        return freerdp_settings_get_uint32(c->settings,FreeRDP_DesktopWidth)==static_cast<UINT32>(o->grant_->width_) &&
               freerdp_settings_get_uint32(c->settings,FreeRDP_DesktopHeight)==static_cast<UINT32>(o->grant_->height_);
    }
    static BOOL postConnect(freerdp* f) {
        auto* o=owner(f);if(o->stopped_ || !o->grant_->valid() || !resize(f->context))return FALSE;
        if(!gdi_init(f,PIXEL_FORMAT_BGRA32))return FALSE;
        f->context->update->DesktopResize=resize;
        return TRUE;
    }
    static void postDisconnect(freerdp* f) {
        PubSub_UnsubscribeChannelConnected(f->context->pubSub,freerdp_client_OnChannelConnectedEventHandler);
        PubSub_UnsubscribeChannelDisconnected(f->context->pubSub,freerdp_client_OnChannelDisconnectedEventHandler);
        gdi_free(f);
    }
    ~Native(){if(context)freerdp_client_context_free(context);}
};
RdpSession::RdpSession(std::unique_ptr<SessionGrant> grant):native_(new Native),grant_(std::move(grant)) {
    if(!grant_ || !grant_->valid())throw std::runtime_error("No paired session grant");
    RDP_CLIENT_ENTRY_POINTS entry{};entry.Size=sizeof(entry);entry.Version=RDP_CLIENT_INTERFACE_VERSION;
    entry.ContextSize=sizeof(Native::Context);
    native_->context=freerdp_client_context_new(&entry);
    if(!native_->context)throw std::runtime_error("RDP context failed");
    reinterpret_cast<Native::Context*>(native_->context)->owner=this;
    auto* f=native_->context->instance;auto* s=native_->context->settings;
    f->PreConnect=Native::preConnect;f->PostConnect=Native::postConnect;f->PostDisconnect=Native::postDisconnect;
    f->RetryDialog=Native::retry;f->Redirect=Native::redirect;f->VerifyX509Certificate=Native::certificate;f->AuthenticateEx=Native::authenticate;
    auto setBool=[&](FreeRDP_Settings_Keys_Bool key,BOOL v){if(!freerdp_settings_set_bool(s,key,v))throw std::runtime_error("RDP policy failed");};
    auto setNumber=[&](FreeRDP_Settings_Keys_UInt32 key,UINT32 v){if(!freerdp_settings_set_uint32(s,key,v))throw std::runtime_error("RDP policy failed");};
    auto setString=[&](FreeRDP_Settings_Keys_String key,const std::string& v){if(!freerdp_settings_set_string(s,key,v.c_str()))throw std::runtime_error("RDP policy failed");};
    setString(FreeRDP_ServerHostname,grant_->host_);setNumber(FreeRDP_ServerPort,grant_->port_);
    setString(FreeRDP_Username,grant_->username_);setString(FreeRDP_Password,grant_->password_);
    setNumber(FreeRDP_DesktopWidth,grant_->width_);setNumber(FreeRDP_DesktopHeight,grant_->height_);setNumber(FreeRDP_ColorDepth,32);
    setNumber(FreeRDP_TcpConnectTimeout,10000);setNumber(FreeRDP_AuthenticationLevel,2);
    for(auto key:{FreeRDP_ExternalCertificateManagement,FreeRDP_NlaSecurity,FreeRDP_SupportGraphicsPipeline,
                 FreeRDP_GfxH264,FreeRDP_GfxAVC444,FreeRDP_SoftwareGdi})setBool(key,TRUE);
    for(auto key:{FreeRDP_IgnoreCertificate,FreeRDP_AutoAcceptCertificate,FreeRDP_AutoReconnectionEnabled,
                 FreeRDP_TlsSecurity,FreeRDP_RdpSecurity,FreeRDP_ExtSecurity,FreeRDP_GatewayEnabled,
                 FreeRDP_NetworkAutoDetect,FreeRDP_SupportHeartbeatPdu,FreeRDP_SupportMultitransport,
                 FreeRDP_DeviceRedirection,FreeRDP_AudioPlayback,FreeRDP_AudioCapture,FreeRDP_RedirectClipboard,
                 FreeRDP_RedirectDrives,FreeRDP_RedirectPrinters,FreeRDP_RedirectSmartCards,FreeRDP_RemoteApplicationMode})setBool(key,FALSE);
}
RdpSession::~RdpSession(){stop();if(worker_.joinable())worker_.join();}
void RdpSession::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if(worker_.joinable())throw std::runtime_error("RDP session already started");
    if(stopped_ || !grant_->valid()){status_=State::Stopped;return;}
    status_=State::Connecting;worker_=std::thread([this]{run();});
}
void RdpSession::stop() {
    stopped_=true;
    std::lock_guard<std::mutex> lock(mutex_);
    if(native_->context && !freerdp_abort_connect_context(native_->context))status_=State::Failed;
}
bool RdpSession::verifyPeer(const std::string& pem,const std::string& host,unsigned port,unsigned flags) {
    bool valid=false;
    try {
        const auto pin=certificatePin(pem);
        valid=!stopped_ && grant_->valid() && !(flags&(VERIFY_CERT_FLAG_GATEWAY|VERIFY_CERT_FLAG_REDIRECT)) &&
            host==grant_->host_ && port==static_cast<unsigned>(grant_->port_) && pin.size()==grant_->fingerprint_.size() &&
            CRYPTO_memcmp(pin.data(),grant_->fingerprint_.data(),pin.size())==0;
    }catch(const std::exception&){}
    if(!valid)status_=State::CertificateRejected;
    return valid;
}
void RdpSession::run() {
    auto* c=native_->context;auto* f=c->instance;
    if(!stopped_ && grant_->valid() && freerdp_connect(f)) {
        status_=State::Connected;
        while(!stopped_ && grant_->valid() && !freerdp_shall_disconnect_context(c)) {
            HANDLE handles[64];const DWORD count=freerdp_get_event_handles(c,handles,64);
            if(!count || WaitForMultipleObjects(count,handles,FALSE,200)==WAIT_FAILED || !freerdp_check_event_handles(c))break;
        }
    }
    const auto error=freerdp_get_last_error(c);
    const bool disconnected=freerdp_disconnect(f);
    if(stopped_ || !grant_->valid())status_=State::Stopped;
    else if(status_!=State::CertificateRejected) {
        if(error==FREERDP_ERROR_AUTHENTICATION_FAILED)status_=State::Refused;
        else if(!disconnected || error!=FREERDP_ERROR_SUCCESS || status_!=State::Connected)status_=State::Failed;
        else status_=State::Stopped;
    }
    if(!grant_->password_.empty())OPENSSL_cleanse(grant_->password_.data(),grant_->password_.size());
}
}
