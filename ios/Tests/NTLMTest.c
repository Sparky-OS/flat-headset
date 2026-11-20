// SPDX-License-Identifier: GPL-3.0-or-later
#include <winpr/ssl.h>
#include <openssl/provider.h>
#include <stdio.h>
int TestSspiNTLM(int argc, char* argv[]);
int main(int argc, char* argv[])
{
    if (!winpr_InitializeSSL(WINPR_SSL_INIT_DEFAULT)) return 1;
    // Upstream's test uses fixed public fixture credentials and in-memory tokens.
    const int result = TestSspiNTLM(argc, argv);
    if (OSSL_PROVIDER_available(NULL, "legacy")) return 2;
    if (!result) puts("PASS: upstream NTLM exchanges without legacy provider; no network");
    return result;
}
