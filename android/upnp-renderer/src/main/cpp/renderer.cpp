// SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
#include <jni.h>
#include <upnp.h>
#include <UpnpActionRequest.h>
#include <UpnpSubscriptionRequest.h>
#include <cstdlib>
#include <mutex>
#include <string>

namespace {
JavaVM *vm;
jobject receiver;
jmethodID actionMethod;
jmethodID snapshotMethod;
UpnpDevice_Handle device = -1;
std::mutex lifecycle;

class Environment {
public:
    JNIEnv *env = nullptr;
    bool attached = false;
    Environment() {
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
    }
    ~Environment() { if (attached) vm->DetachCurrentThread(); }
};

std::string string(JNIEnv *env, jstring value) {
    if (!value) return {};
    const auto chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

int callback(Upnp_EventType type, void *event, void *) {
    Environment thread;
    auto env = thread.env;
    if (!env || !receiver) return 0;
    env->PushLocalFrame(16);
    if (type == UPNP_CONTROL_ACTION_REQUEST) {
        auto request = static_cast<UpnpActionRequest *>(event);
        auto document = ixmlPrintDocument(UpnpActionRequest_get_ActionRequest(request));
        auto result = static_cast<jstring>(env->CallObjectMethod(receiver, actionMethod,
            env->NewStringUTF(UpnpActionRequest_get_ServiceID_cstr(request)),
            env->NewStringUTF(UpnpActionRequest_get_ActionName_cstr(request)),
            env->NewStringUTF(document ? document : "")));
        ixmlFreeDOMString(document);
        std::string response;
        if (env->ExceptionCheck()) env->ExceptionClear();
        else response = string(env, result);
        if (!response.empty() && response.front() != '!') {
            auto xml = ixmlParseBuffer(response.c_str());
            if (xml) {
                UpnpActionRequest_set_ActionResult(request, xml);
                UpnpActionRequest_set_ErrCode(request, 0);
            } else UpnpActionRequest_set_ErrCode(request, 501);
        } else {
            int error = response.empty() ? 501 : std::atoi(response.c_str() + 1);
            UpnpActionRequest_set_ErrCode(request, error >= 400 && error <= 899 ? error : 501);
            UpnpActionRequest_strcpy_ErrStr(request, "Action failed");
        }
    } else if (type == UPNP_EVENT_SUBSCRIPTION_REQUEST) {
        auto request = static_cast<const UpnpSubscriptionRequest *>(event);
        auto result = static_cast<jstring>(env->CallObjectMethod(receiver, snapshotMethod,
            env->NewStringUTF(UpnpSubscriptionRequest_get_ServiceId_cstr(request))));
        std::string value;
        if (env->ExceptionCheck()) env->ExceptionClear();
        else value = string(env, result);
        auto properties = value.empty() ? nullptr : ixmlParseBuffer(value.c_str());
        if (properties) {
            UpnpAcceptSubscriptionExt(device, UpnpSubscriptionRequest_get_UDN_cstr(request),
                UpnpSubscriptionRequest_get_ServiceId_cstr(request), properties,
                UpnpSubscriptionRequest_get_SID_cstr(request));
            ixmlDocument_free(properties);
        } else {
            UpnpAcceptSubscription(device, UpnpSubscriptionRequest_get_UDN_cstr(request),
                UpnpSubscriptionRequest_get_ServiceId_cstr(request), nullptr, nullptr, 0,
                UpnpSubscriptionRequest_get_SID_cstr(request));
        }
    }
    env->PopLocalFrame(nullptr);
    return 0;
}

void fail(JNIEnv *env, int error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), ("UPnP initialization failed: " + std::to_string(error)).c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_kde_kdeconnect_headset_upnp_NativeRenderer_start(JNIEnv *env, jobject self, jstring iface, jstring root, jstring description) {
    std::lock_guard lock(lifecycle);
    if (receiver) { fail(env, UPNP_E_INIT); return nullptr; }
    env->GetJavaVM(&vm);
    auto interfaceName = string(env, iface);
    int error = UpnpInit2(interfaceName.empty() ? nullptr : interfaceName.c_str(), 0);
    if (error != UPNP_E_SUCCESS) { fail(env, error); return nullptr; }
    UpnpSetMaxContentLength(65536);
    error = UpnpSetWebServerRootDir(string(env, root).c_str());
    if (error != UPNP_E_SUCCESS) { UpnpFinish(); fail(env, error); return nullptr; }
    receiver = env->NewGlobalRef(self);
    auto clazz = env->GetObjectClass(self);
    actionMethod = env->GetMethodID(clazz, "onAction", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    snapshotMethod = env->GetMethodID(clazz, "snapshot", "(Ljava/lang/String;)Ljava/lang/String;");
    const auto xml = string(env, description);
    error = UpnpRegisterRootDevice2(UPNPREG_BUF_DESC, xml.c_str(), xml.size(), 1, callback, nullptr, &device);
    if (error == UPNP_E_SUCCESS) error = UpnpSendAdvertisement(device, 1800);
    if (error != UPNP_E_SUCCESS) {
        UpnpFinish();
        env->DeleteGlobalRef(receiver);
        receiver = nullptr;
        device = -1;
        fail(env, error);
        return nullptr;
    }
    const auto address = std::string("http://") + UpnpGetServerIpAddress() + ":" + std::to_string(UpnpGetServerPort());
    return env->NewStringUTF(address.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_kde_kdeconnect_headset_upnp_NativeRenderer_close(JNIEnv *env, jobject self) {
    std::lock_guard lock(lifecycle);
    if (!receiver || !env->IsSameObject(receiver, self)) return;
    UpnpUnRegisterRootDevice(device);
    UpnpFinish();
    env->DeleteGlobalRef(receiver);
    receiver = nullptr;
    device = -1;
}

extern "C" JNIEXPORT void JNICALL
Java_org_kde_kdeconnect_headset_upnp_NativeRenderer_notifyChange(JNIEnv *env, jobject self, jstring udn, jstring service, jstring change) {
    std::lock_guard lock(lifecycle);
    if (!receiver || !env->IsSameObject(receiver, self)) return;
    const auto value = string(env, change);
    const char *names[] = {"LastChange"};
    const char *values[] = {value.c_str()};
    UpnpNotify(device, string(env, udn).c_str(), string(env, service).c_str(), names, values, 1);
}

extern "C" JNIEXPORT void JNICALL
Java_org_kde_kdeconnect_headset_upnp_NativeRenderer_notifyProperties(JNIEnv *env, jobject self, jstring udn, jstring service, jstring propertiesXml) {
    std::lock_guard lock(lifecycle);
    if (!receiver || !env->IsSameObject(receiver, self)) return;
    auto properties = ixmlParseBuffer(string(env, propertiesXml).c_str());
    if (!properties) return;
    UpnpNotifyExt(device, string(env, udn).c_str(), string(env, service).c_str(), properties);
    ixmlDocument_free(properties);
}
