// SPDX-License-Identifier: GPL-3.0-or-later
#include <freerdp/freerdp.h>
#include <freerdp/client.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/codec/h264.h>
#include <libavcodec/avcodec.h>
#include <openssl/crypto.h>
#include <stdio.h>

int main(void)
{
    if (!avcodec_find_decoder(AV_CODEC_ID_H264) ||
        !avcodec_find_decoder(AV_CODEC_ID_HEVC) || !OpenSSL_version_num())
        return 1;
    RDP_CLIENT_ENTRY_POINTS entry = { 0 };
    entry.Size = sizeof(entry);
    entry.Version = RDP_CLIENT_INTERFACE_VERSION;
    entry.ContextSize = sizeof(rdpClientContext);
    rdpContext* context = freerdp_client_context_new(&entry);
    if (!context)
        return 2;
    int result = 3;
    // Exercise only this build's declared channels; no network connection is made.
    const FreeRDP_Settings_Keys_Bool disabled[] = {
        FreeRDP_NetworkAutoDetect, FreeRDP_SupportHeartbeatPdu,
        FreeRDP_SupportMultitransport, FreeRDP_DeviceRedirection,
        FreeRDP_AudioPlayback, FreeRDP_AudioCapture
    };
    for (size_t i = 0; i < sizeof(disabled) / sizeof(disabled[0]); ++i)
        if (!freerdp_settings_set_bool(context->settings, disabled[i], FALSE))
            goto cleanup;
    if (!freerdp_settings_set_bool(context->settings, FreeRDP_SupportGraphicsPipeline, TRUE) ||
        !freerdp_settings_set_bool(context->settings, FreeRDP_SupportDisplayControl, TRUE) ||
        !freerdp_settings_set_bool(context->settings, FreeRDP_RedirectClipboard, TRUE) ||
        !freerdp_client_load_addins(context->channels, context->settings))
        goto cleanup;
    H264_CONTEXT* decoder = h264_context_new(FALSE);
    if (!decoder)
        goto cleanup;
    h264_context_free(decoder);
    result = 0;
    puts("PASS: FreeRDP client/channel/H.264 lifecycle; H.264/HEVC and OpenSSL linked");
cleanup:
    freerdp_client_context_free(context);
    return result;
}
