/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/** Media is fetched only from the paired controller that supplied its URI. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PairedMovieDataSource(
    private val trust: PairedTls,
    private val source: () -> Pair<InetAddress, X509Certificate>?,
    private val userAgent: String,
    private val seek: () -> Long,
) : BaseDataSource(true) {
    private var connection: HttpsURLConnection? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var peer: X509Certificate? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (address, certificate) = source() ?: throw IOException("No paired movie controller")
        if (!trust.isPaired(certificate)) throw IOException("KDE Connect pairing was removed")
        val url = URL(dataSpec.uri.toString())
        if (url.protocol != "https" || url.userInfo != null || InetAddress.getByName(url.host) != address)
            throw IOException("Movie URI must use the paired controller's HTTPS endpoint")
        val http = url.openConnection() as HttpsURLConnection
        connection = http
        http.sslSocketFactory = trust.create(certificate).socketFactory
        http.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session -> session.peerCertificates.firstOrNull() == certificate && trust.isPaired(certificate) }
        http.instanceFollowRedirects = false
        http.connectTimeout = 10000
        http.readTimeout = 15000
        http.setRequestProperty("User-Agent", userAgent)
        http.setRequestProperty("Accept-Encoding", "identity")
        dataSpec.httpRequestHeaders.forEach { (name, value) -> http.setRequestProperty(name, value) }
        if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
            http.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
        }
        val offset = seek()
        if (offset > 0) http.setRequestProperty("TimeSeekRange.dlna.org", "npt=${offset / 1000}.${(offset % 1000).toString().padStart(3, '0')}-")
        val code = http.responseCode
        if (code !in 200..299 || (dataSpec.position > 0 && code != 206))
            throw IOException("Movie server refused the request: HTTP $code")
        if (!trust.isPaired(certificate)) throw IOException("KDE Connect pairing was removed")
        input = http.inputStream
        peer = certificate
        uri = dataSpec.uri
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else http.contentLengthLong
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (peer?.let(trust::isPaired) != true) throw IOException("KDE Connect pairing was removed")
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = input!!.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (remaining > 0) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = connection?.headerFields?.filterKeys { it != null } ?: emptyMap()
    override fun close() {
        try { input?.close() } finally {
            input = null
            connection?.disconnect()
            connection = null
            uri = null
            peer = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
