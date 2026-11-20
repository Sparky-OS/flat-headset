// SPDX-License-Identifier: GPL-3.0-or-later
#include <freerdp/freerdp.h>
#include <freerdp/client.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/codec/h264.h>
#include <libavcodec/avcodec.h>
#include <openssl/crypto.h>
#include <openssl/provider.h>
#include <winpr/crypto.h>
#include <winpr/ssl.h>
#include <string.h>
#include <stdio.h>

static int protocol_crypto(void)
{
    // Known answers for NTLMv2's digest, MAC and stream-cipher primitives.
    // MD4/MD5: WinPR TestCryptoHash; HMAC-MD5: RFC 2202 case 1.
    const unsigned char md4[16] = {0xdb,0x34,0x6d,0x69,0x1d,0x7a,0xcc,0x4d,0xc2,0x62,0x5d,0xb1,0x9f,0x9e,0x3f,0x52};
    const unsigned char md5[16] = {0x09,0x8f,0x6b,0xcd,0x46,0x21,0xd3,0x73,0xca,0xde,0x4e,0x83,0x26,0x27,0xb4,0xf6};
    const unsigned char hmac[16] = {0x92,0x94,0x72,0x7a,0x36,0x38,0xbb,0x1c,0x13,0xf4,0x8e,0xf8,0x15,0x8b,0xfc,0x9d};
    // RFC 6229, 40-bit key, first 16 stream bytes.
    const unsigned char rc4[16] = {0xb2,0x39,0x63,0x05,0xf0,0x3d,0xc0,0x27,0xcc,0xc3,0x52,0x4a,0x0a,0x11,0x18,0xa8};
    unsigned char out[16], key[16], zeros[16] = {0};
    const unsigned char rc4key[] = {1,2,3,4,5};
    if (!winpr_Digest(WINPR_MD_MD4, "test", 4, out, sizeof(out)) || memcmp(out, md4, 16) ||
        !winpr_Digest(WINPR_MD_MD5, "test", 4, out, sizeof(out)) || memcmp(out, md5, 16))
        return 0;
    memset(key, 0x0b, sizeof(key));
    if (!winpr_HMAC(WINPR_MD_MD5, key, sizeof(key), "Hi There", 8, out, sizeof(out)) || memcmp(out,hmac,16))
        return 0;
    WINPR_RC4_CTX* ctx = winpr_RC4_New(rc4key, sizeof(rc4key));
    if (!ctx) return 0;
    const BOOL ok = winpr_RC4_Update(ctx, sizeof(out), zeros, out);
    winpr_RC4_Free(ctx);
    return ok && !memcmp(out,rc4,16) && !OSSL_PROVIDER_available(NULL,"legacy");
}

int main(void)
{
    if (!winpr_InitializeSSL(WINPR_SSL_INIT_DEFAULT) || !protocol_crypto()) return 4;
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
    puts("PASS: FreeRDP client/channel/H.264 lifecycle; H.264/HEVC and OpenSSL linked; NTLM primitives pass without legacy provider");
cleanup:
    freerdp_client_context_free(context);
    return result;
}
