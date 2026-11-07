// SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
// SPDX-License-Identifier: Apache-2.0
// Isolated protocol fixture using the FreeRDP contributors' server APIs.
#include <freerdp/peer.h>
#include <freerdp/freerdp.h>
#include <freerdp/crypto/certificate.h>
#include <freerdp/crypto/privatekey.h>
#include <freerdp/listener.h>
#include <freerdp/settings.h>
#include <freerdp/channels/drdynvc.h>
#include <freerdp/channels/wtsvc.h>
#include <freerdp/server/rdpecam.h>
#include <freerdp/server/rdpecam-enumerator.h>
#include <winpr/synch.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>

struct CameraProbe {
    std::atomic<unsigned> frames{0};
    std::atomic<bool> failed{false};
    std::set<unsigned long long> hashes;
    CameraDeviceServerContext* device = nullptr;
    CamDevEnumServerContext* enumerator = nullptr;
    CAM_MEDIA_TYPE_DESCRIPTION format{};
    unsigned stage = 0;
};

struct PeerContext {
    rdpContext context;
    HANDLE manager;
    CameraProbe* probe;
};

static CameraProbe probe;
static const char* certificate;
static const char* privateKey;

static UINT cameraError(CameraDeviceServerContext*, const CAM_ERROR_RESPONSE* error)
{
    std::printf("CAMERA_ERROR code=%u\n", unsigned(error->ErrorCode));
    probe.failed = true;
    return CHANNEL_RC_OK;
}

static UINT sampleError(CameraDeviceServerContext*, const CAM_SAMPLE_ERROR_RESPONSE* error)
{
    std::printf("SAMPLE_ERROR code=%u\n", unsigned(error->ErrorCode));
    probe.failed = true;
    return CHANNEL_RC_OK;
}

static UINT sample(CameraDeviceServerContext* camera, const CAM_SAMPLE_RESPONSE* frame)
{
    const size_t expected = size_t(probe.format.Width) * probe.format.Height * 3 / 2;
    if (frame->StreamIndex != 0 || frame->SampleSize != expected || !frame->Sample) {
        std::printf("INVALID_SAMPLE bytes=%zu expected=%zu\n", frame->SampleSize, expected);
        probe.failed = true;
        return CHANNEL_RC_OK;
    }
    unsigned long long hash = 14695981039346656037ULL;
    for (size_t i = 0; i < frame->SampleSize; ++i) {
        hash ^= frame->Sample[i];
        hash *= 1099511628211ULL;
    }
    probe.hashes.insert(hash);
    const unsigned count = ++probe.frames;
    std::printf("SAMPLE index=%u bytes=%zu unique=%zu\n", count, frame->SampleSize, probe.hashes.size());
    if (count < 12) {
        CAM_SAMPLE_REQUEST request{};
        request.StreamIndex = 0;
        return camera->SampleRequest(camera, &request);
    }
    return CHANNEL_RC_OK;
}

static UINT success(CameraDeviceServerContext* camera, const CAM_SUCCESS_RESPONSE*)
{
    if (probe.stage == 1) {
        probe.stage = 2;
        CAM_STREAM_LIST_REQUEST request{};
        return camera->StreamListRequest(camera, &request);
    }
    if (probe.stage == 4) {
        probe.stage = 5;
        CAM_SAMPLE_REQUEST request{};
        request.StreamIndex = 0;
        return camera->SampleRequest(camera, &request);
    }
    return CHANNEL_RC_OK;
}

static UINT formats(CameraDeviceServerContext* camera, const CAM_MEDIA_TYPE_LIST_RESPONSE* reply)
{
    for (size_t i = 0; i < reply->N_Descriptions; ++i) {
        const auto& format = reply->MediaTypeDescriptions[i];
        if (format.Format != CAM_MEDIA_FORMAT_NV12 || format.Width != 640 || format.Height != 480
            || !format.FrameRateDenominator || !format.FrameRateNumerator) {
            continue;
        }
        probe.format = format;
        std::printf("SELECT_RAW_NV12 width=%u height=%u rate=%u/%u\n", format.Width, format.Height,
                    format.FrameRateNumerator, format.FrameRateDenominator);
        CAM_START_STREAMS_REQUEST request{};
        request.N_Infos = 1;
        request.StartStreamsInfo[0].StreamIndex = 0;
        request.StartStreamsInfo[0].MediaTypeDescription = format;
        probe.stage = 4;
        return camera->StartStreamsRequest(camera, &request);
    }
    std::puts("NO_REQUESTED_RAW_NV12_MODE");
    probe.failed = true;
    return CHANNEL_RC_OK;
}

static UINT streams(CameraDeviceServerContext* camera, const CAM_STREAM_LIST_RESPONSE* reply)
{
    if (!reply->N_Descriptions) {
        probe.failed = true;
        return CHANNEL_RC_OK;
    }
    CAM_MEDIA_TYPE_LIST_REQUEST request{};
    request.StreamIndex = 0;
    probe.stage = 3;
    return camera->MediaTypeListRequest(camera, &request);
}

static BOOL cameraReady(CameraDeviceServerContext* camera, UINT32)
{
    CAM_ACTIVATE_DEVICE_REQUEST request{};
    probe.stage = 1;
    return camera->ActivateDeviceRequest(camera, &request) == CHANNEL_RC_OK;
}

static UINT added(CamDevEnumServerContext* enumerator, const CAM_DEVICE_ADDED_NOTIFICATION* device)
{
    if (probe.device) {
        return CHANNEL_RC_OK;
    }
    if (!device->VirtualChannelName || std::strlen(device->VirtualChannelName) > 256
        || std::strstr(device->VirtualChannelName, "android-camera-") != device->VirtualChannelName) {
        probe.failed = true;
        return CHANNEL_RC_BAD_CHANNEL;
    }
    std::puts("CAMERA_ADDED");
    auto* camera = camera_device_server_context_new(enumerator->vcm);
    if (!camera) {
        probe.failed = true;
        return CHANNEL_RC_NO_MEMORY;
    }
    probe.device = camera;
    camera->virtualChannelName = strdup(device->VirtualChannelName);
    camera->protocolVersion = 2;
    camera->ChannelIdAssigned = cameraReady;
    camera->SuccessResponse = success;
    camera->ErrorResponse = cameraError;
    camera->StreamListResponse = streams;
    camera->MediaTypeListResponse = formats;
    camera->SampleResponse = sample;
    camera->SampleErrorResponse = sampleError;
    if (camera->Initialize(camera, FALSE) != CHANNEL_RC_OK || camera->Open(camera) != CHANNEL_RC_OK) {
        probe.failed = true;
    }
    return CHANNEL_RC_OK;
}

static UINT version(CamDevEnumServerContext* enumerator, const CAM_SELECT_VERSION_REQUEST* request)
{
    std::printf("CAMERA_VERSION client=%u\n", unsigned(request->Header.Version));
    CAM_SELECT_VERSION_RESPONSE response{};
    response.Header.Version = request->Header.Version > 2 ? 2 : request->Header.Version;
    return enumerator->SelectVersionResponse(enumerator, &response);
}

static BOOL contextNew(freerdp_peer* peer, rdpContext*)
{
    auto* context = reinterpret_cast<PeerContext*>(peer->context);
    context->manager = WTSOpenServerA(reinterpret_cast<LPSTR>(peer->context));
    context->probe = &probe;
    return context->manager && context->manager != INVALID_HANDLE_VALUE;
}

static void contextFree(freerdp_peer* peer, rdpContext*)
{
    auto* context = reinterpret_cast<PeerContext*>(peer->context);
    if (context->manager && context->manager != INVALID_HANDLE_VALUE) {
        WTSCloseServer(context->manager);
    }
}

static BOOL accepted(freerdp_listener*, freerdp_peer* peer)
{
    peer->ContextSize = sizeof(PeerContext);
    peer->ContextNew = contextNew;
    peer->ContextFree = contextFree;
    if (!freerdp_peer_context_new(peer)) {
        return FALSE;
    }
    auto* context = reinterpret_cast<PeerContext*>(peer->context);
    auto* settings = peer->context->settings;
    freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE);
    auto* cert = freerdp_certificate_new_from_file(certificate);
    auto* key = freerdp_key_new_from_file(privateKey);
    if (!cert || !key) {
        freerdp_certificate_free(cert);
        freerdp_key_free(key);
        freerdp_peer_context_free(peer);
        return FALSE;
    }
    // These setters transfer ownership to settings (they do not clone).
    if (!freerdp_settings_set_pointer_len(settings, FreeRDP_RdpServerCertificate, cert, 1)) {
        freerdp_certificate_free(cert);
        freerdp_key_free(key);
        freerdp_peer_context_free(peer);
        return FALSE;
    }
    if (!freerdp_settings_set_pointer_len(settings, FreeRDP_RdpServerRsaKey, key, 1)) {
        freerdp_key_free(key);
        freerdp_peer_context_free(peer);
        return FALSE;
    }
    freerdp_settings_set_bool(settings, FreeRDP_SupportDynamicChannels, TRUE);
    peer->PostConnect = [](freerdp_peer*) -> BOOL { std::puts("RDP_CONNECTED"); return TRUE; };
    peer->Activate = [](freerdp_peer*) -> BOOL { std::puts("RDP_ACTIVE"); return TRUE; };
    if (!peer->Initialize(peer)) {
        freerdp_peer_context_free(peer);
        return FALSE;
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(40);
    bool managerOpened = false;
    while (std::chrono::steady_clock::now() < deadline && !probe.failed && probe.frames < 12) {
        HANDLE events[32]{};
        DWORD count = peer->GetEventHandles(peer, events, 31);
        if (!count) {
            break;
        }
        events[count++] = WTSVirtualChannelManagerGetEventHandle(context->manager);
        WaitForMultipleObjects(count, events, FALSE, 100);
        if (!peer->CheckFileDescriptor(peer)) {
            break;
        }
        if (!managerOpened && WTSVirtualChannelManagerIsChannelJoined(context->manager, DRDYNVC_SVC_CHANNEL_NAME)) {
            managerOpened = WTSVirtualChannelManagerOpen(context->manager);
        }
        if (!WTSVirtualChannelManagerCheckFileDescriptor(context->manager)) {
            break;
        }
        if (!probe.enumerator && WTSVirtualChannelManagerGetDrdynvcState(context->manager) == DRDYNVC_STATE_READY) {
            std::puts("DVC_READY");
            auto* camera = cam_dev_enum_server_context_new(context->manager);
            if (!camera) {
                break;
            }
            probe.enumerator = camera;
            camera->SelectVersionRequest = version;
            camera->DeviceAddedNotification = added;
            if (camera->Initialize(camera, FALSE) != CHANNEL_RC_OK || camera->Open(camera) != CHANNEL_RC_OK) {
                probe.failed = true;
            }
        }
    }
    if (probe.enumerator) {
        cam_dev_enum_server_context_free(probe.enumerator);
        probe.enumerator = nullptr;
    }
    if (probe.device) {
        camera_device_server_context_free(probe.device);
        probe.device = nullptr;
    }
    peer->Disconnect(peer);
    freerdp_peer_context_free(peer);
    freerdp_peer_free(peer);
    return TRUE;
}

int main(int argc, char** argv)
{
    if (argc != 3) {
        std::fprintf(stderr, "Usage: rdpecam-probe CERTIFICATE PRIVATE_KEY\n");
        return 2;
    }
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    certificate = argv[1];
    privateKey = argv[2];
    if (!WTSRegisterWtsApiFunctionTable(FreeRDP_InitWtsApi())) {
        return 2;
    }
    auto* listener = freerdp_listener_new();
    listener->PeerAccepted = accepted;
    if (!listener->Open(listener, "127.0.0.1", 3389)) {
        freerdp_listener_free(listener);
        return 2;
    }
    std::puts("LISTENING_RAW_NV12");
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(90);
    while (!probe.frames && !probe.failed && std::chrono::steady_clock::now() < deadline) {
        HANDLE events[32]{};
        const DWORD count = listener->GetEventHandles(listener, events, 32);
        if (!count) {
            break;
        }
        WaitForMultipleObjects(count, events, FALSE, 200);
        if (!listener->CheckFileDescriptor(listener)) {
            break;
        }
    }
    listener->Close(listener);
    freerdp_listener_free(listener);
    std::printf("RESULT frames=%u unique=%zu failed=%d\n", probe.frames.load(), probe.hashes.size(), probe.failed.load());
    return !probe.failed && probe.frames >= 12 && probe.hashes.size() >= 2 ? 0 : 1;
}
