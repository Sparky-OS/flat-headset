/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.security.RsaHelper
import org.kde.kdeconnect.helpers.security.SslHelper

/** KDE Connect owns both identity and revocation; no second approval store. */
class PairedTls(context: Context) {
    private val context = context.applicationContext

    fun isPaired(certificate: X509Certificate): Boolean =
        TrustedDevices.getAllTrustedDevices(context).any { id ->
            runCatching { TrustedDevices.getDeviceCertificate(context, id) == certificate }.getOrDefault(false)
        }

    fun create(peer: X509Certificate? = null): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("headset", RsaHelper.getPrivateKey(context), charArrayOf(), arrayOf(SslHelper.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, charArrayOf()) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            private fun check(chain: Array<X509Certificate>) {
                if (chain.isEmpty() || !isPaired(chain[0]) || (peer != null && peer != chain[0]))
                    throw CertificateException("KDE Connect pairing required")
                chain[0].checkValidity()
            }
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (peer == null) throw CertificateException("A paired server identity is required")
                check(chain)
            }
        }
        return SSLContext.getInstance("TLSv1.2").apply { init(keys.keyManagers, arrayOf(trust), null) }
    }
}
