/* SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
 * SPDX-License-Identifier: Apache-2.0 */
#include <freerdp/dvc.h>
#include <freerdp/client/channels.h>
#include <winpr/stream.h>
#include <winpr/synch.h>
#include <stdatomic.h>
#include <stdbool.h>
#include "android_jni_callback.h"

typedef struct { GENERIC_DYNVC_PLUGIN base; rdpContext* context; } PosePlugin;
typedef struct PoseCallback {
    GENERIC_CHANNEL_CALLBACK base;
    struct PoseCallback* next;
    UINT64 cookie;
    atomic_bool failed;
} PoseCallback;
static INIT_ONCE once = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION lock;
static PoseCallback* live;
static UINT64 next_cookie = 1;
static BOOL CALLBACK init_lock(PINIT_ONCE a, PVOID b, PVOID* c) {
    (void)a; (void)b; (void)c; return InitializeCriticalSectionAndSpinCount(&lock, 0);
}
static int pose_java(PoseCallback* callback, int event, const BYTE* bytes, size_t length) {
    PosePlugin* plugin = (PosePlugin*)callback->base.plugin;
    JNIEnv* env = NULL;
    const jboolean attached = jni_attach_thread(&env);
    if (!env) return 0;
    jbyteArray data = (*env)->NewByteArray(env, (jsize)length);
    if (!data) { if (attached) jni_detach_thread(); return 0; }
    if (length) (*env)->SetByteArrayRegion(env, data, 0, (jsize)length, (const jbyte*)bytes);
    int result = freerdp_callback_int_result("OnStereoPosePacket", "(JJI[B)I",
        (jlong)plugin->context->instance, (jlong)callback->cookie, (jint)event, data);
    (*env)->DeleteLocalRef(env, data);
    if (attached) jni_detach_thread();
    return result;
}
static UINT pose_open(IWTSVirtualChannelCallback* iface) {
    PoseCallback* c = (PoseCallback*)iface;
    if (!InitOnceExecuteOnce(&once, init_lock, NULL, NULL)) return CHANNEL_RC_NO_MEMORY;
    EnterCriticalSection(&lock);
    c->cookie = next_cookie++;
    c->next = live; live = c;
    LeaveCriticalSection(&lock);
    // No local consent means no HELLO, no sensor registration and no sample.
    atomic_store(&c->failed, pose_java(c, 0, NULL, 0) != 1);
    return CHANNEL_RC_OK;
}
static UINT pose_received(IWTSVirtualChannelCallback* iface, wStream* stream) {
    PoseCallback* c = (PoseCallback*)iface;
    if (atomic_load(&c->failed)) return CHANNEL_RC_OK;
    const size_t size = Stream_GetRemainingLength(stream);
    if (size > 4096 || pose_java(c, 1, Stream_Pointer(stream), size) != 1) {
        atomic_store(&c->failed, true);
        // Closing this optional DVC does not abort the RDP display session.
        return c->base.channel->Close(c->base.channel);
    }
    return CHANNEL_RC_OK;
}
static UINT pose_closed(IWTSVirtualChannelCallback* iface) {
    PoseCallback* c = (PoseCallback*)iface;
    if (!InitOnceExecuteOnce(&once, init_lock, NULL, NULL)) return CHANNEL_RC_OK;
    EnterCriticalSection(&lock);
    PoseCallback** entry = &live;
    while (*entry && *entry != c) entry = &(*entry)->next;
    if (*entry) *entry = c->next;
    LeaveCriticalSection(&lock);
    pose_java(c, 2, NULL, 0);
    free(c);
    return CHANNEL_RC_OK;
}
static UINT pose_initialize(GENERIC_DYNVC_PLUGIN* base, rdpContext* context, rdpSettings* settings) {
    (void)settings; ((PosePlugin*)base)->context = context; return CHANNEL_RC_OK;
}
UINT stereopose_entry(IDRDYNVC_ENTRY_POINTS* ep) {
    static const IWTSVirtualChannelCallback callbacks = {
        .OnDataReceived = pose_received, .OnOpen = pose_open, .OnClose = pose_closed
    };
    return freerdp_generic_DVCPluginEntry(ep, "com.freerdp.channels.stereopose", "Sparky.StereoDroid.Pose.1",
        sizeof(PosePlugin), sizeof(PoseCallback), &callbacks, pose_initialize, NULL);
}
JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_services_LibFreeRDP_sendStereoPose(
    JNIEnv* env, jclass cls, jlong instance, jlong cookie, jbyteArray packet) {
    (void)cls;
    if (!packet || !cookie || !InitOnceExecuteOnce(&once, init_lock, NULL, NULL)) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, packet);
    if (size < 32 || size > 4096) return JNI_FALSE;
    BYTE bytes[4096];
    (*env)->GetByteArrayRegion(env, packet, 0, size, (jbyte*)bytes);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    UINT status = ERROR_INVALID_HANDLE;
    EnterCriticalSection(&lock);
    for (PoseCallback* c = live; c; c = c->next) {
        PosePlugin* plugin = (PosePlugin*)c->base.plugin;
        if ((jlong)plugin->context->instance == instance && c->cookie == (UINT64)cookie && !atomic_load(&c->failed)) {
            status = c->base.channel->Write(c->base.channel, (UINT32)size, bytes, NULL);
            break;
        }
    }
    LeaveCriticalSection(&lock);
    return status == CHANNEL_RC_OK ? JNI_TRUE : JNI_FALSE;
}
