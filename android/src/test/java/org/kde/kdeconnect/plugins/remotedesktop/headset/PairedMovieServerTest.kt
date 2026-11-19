/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.security.RsaHelper
import org.kde.kdeconnect.helpers.security.SslHelper
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PairedMovieServerTest {
    private lateinit var clientKeys: java.security.KeyPair
    private lateinit var clientCertificate: java.security.cert.Certificate
    @Before fun prepareIdentity() {
        val context = RuntimeEnvironment.getApplication()
        DeviceHelper.initializeDeviceId(context)
        RsaHelper.initialiseRsaKeys(context)
        SslHelper.initialiseCertificate(context)
        clientKeys = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = org.bouncycastle.asn1.x500.X500Name("CN=paired-pc")
        val now = System.currentTimeMillis()
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(name, java.math.BigInteger.ONE,
            java.util.Date(now - 60000), java.util.Date(now + 86400000), name, clientKeys.public)
        clientCertificate = SslHelper.parseCertificate(builder.build(
            org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(clientKeys.private)).encoded)
    }

    private fun client(authenticated: Boolean): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("phone", SslHelper.certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        if (authenticated) store.setKeyEntry("pc", clientKeys.private, charArrayOf(), arrayOf(clientCertificate))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, charArrayOf()) }
        return SSLContext.getInstance("TLSv1.2").apply { init(if (authenticated) keys.keyManagers else null, trust.trustManagers, null) }
    }

    private fun pair() {
        val context = RuntimeEnvironment.getApplication()
        TrustedDevices.getDeviceSettings(context, "paired-pc").edit()
            .putString("certificate", android.util.Base64.encodeToString(clientCertificate.encoded, 0)).commit()
        TrustedDevices.addTrustedDevice(context, "paired-pc")
    }

    private fun request(tls: SSLContext, server: PairedMovieServer, method: String,
                        suffix: String, headers: String = "", body: String = ""): String =
        (tls.socketFactory.createSocket("127.0.0.1", server.port) as SSLSocket).use { socket ->
            socket.soTimeout = 5000
            socket.startHandshake()
            val bytes = body.toByteArray()
            socket.outputStream.write(("$method ${server.path}/RenderingControl/$suffix HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${bytes.size}\r\n$headers\r\n").toByteArray())
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
            socket.inputStream.bufferedReader().readText()
        }

    private fun listener(events: LinkedBlockingQueue<String>) = object : org.kde.kdeconnect.headset.upnp.NativeRenderer.Listener {
        override fun onAction(serviceId: String, action: String, requestXml: String): String {
            events.offer(action)
            return "<u:GetVolumeResponse xmlns:u=\"${MovieServices.rendering.type}\"><CurrentVolume>73</CurrentVolume></u:GetVolumeResponse>"
        }
        override fun snapshot(serviceId: String) = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>volume=73</LastChange></e:property></e:propertyset>"
    }

    @Test fun pairedSoapAndRevocationUseTheRealTlsListener() {
        pair()
        val context = RuntimeEnvironment.getApplication()
        val events = LinkedBlockingQueue<String>()
        PairedMovieServer(PairedTls(context), "ab".repeat(32), listener(events)) { _, _ -> }.use { server ->
            val tls = client(true)
            val type = MovieServices.rendering.type
            val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:GetVolume xmlns:u=\"$type\"><InstanceID>0</InstanceID><Channel>Master</Channel></u:GetVolume></s:Body></s:Envelope>"
            val response = request(tls, server, "POST", "control", "SOAPACTION: \"$type#GetVolume\"\r\n", body)
            assertTrue(response, response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("<CurrentVolume>73</CurrentVolume>"))
            assertEquals("GetVolume", events.poll(5, TimeUnit.SECONDS))
            TrustedDevices.removeTrustedDevice(context, "paired-pc")
            val revoked = runCatching { request(tls, server, "POST", "control", "SOAPACTION: \"$type#GetVolume\"\r\n", body) }.getOrDefault("")
            assertTrue(revoked, !revoked.startsWith("HTTP/1.1 200"))
            assertEquals(null, events.poll())
        }
    }

    @Test fun declarationsAndForeignSoapNamespacesCannotReachTheListener() {
        pair()
        val events = LinkedBlockingQueue<String>()
        PairedMovieServer(PairedTls(RuntimeEnvironment.getApplication()), "ab".repeat(32), listener(events)) { _, _ -> }.use { server ->
            val type = MovieServices.rendering.type
            val envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:GetVolume xmlns:u=\"$type\"><InstanceID>0</InstanceID><Channel>Master</Channel></u:GetVolume></s:Body></s:Envelope>"
            for (body in listOf("<!DOCTYPE x [<!ENTITY external SYSTEM \"file:///not-a-media-resource\">]>" + envelope,
                                envelope.replace("http://schemas.xmlsoap.org/soap/envelope/", "urn:wrong-envelope"))) {
                val response = runCatching { request(client(true), server, "POST", "control", "SOAPACTION: \"$type#GetVolume\"\r\n", body) }.getOrDefault("")
                assertTrue(response, !response.startsWith("HTTP/1.1 200"))
                assertEquals(null, events.poll())
            }
        }
    }

    @Test fun missingClientIdentityCannotReachSoap() {
        pair()
        val events = LinkedBlockingQueue<String>()
        PairedMovieServer(PairedTls(RuntimeEnvironment.getApplication()), "ab".repeat(32), listener(events)) { _, _ -> }.use { server ->
            val response = runCatching { request(client(false), server, "POST", "control") }.getOrDefault("")
            assertTrue(response, !response.startsWith("HTTP/1.1 200"))
            assertEquals(null, events.poll())
        }
    }

    @Test fun eventCallbackRequiresPairedHttpsAndCarriesInitialState() {
        pair()
        val tls = client(true)
        val events = LinkedBlockingQueue<String>()
        (tls.serverSocketFactory.createServerSocket(0) as javax.net.ssl.SSLServerSocket).use { callback ->
            callback.needClientAuth = true
            callback.soTimeout = 5000
            PairedMovieServer(PairedTls(RuntimeEnvironment.getApplication()), "ab".repeat(32), listener(events)) { _, _ -> }.use { server ->
                val rejected = request(tls, server, "SUBSCRIBE", "event", "NT: upnp:event\r\nCALLBACK: <http://127.0.0.1:${callback.localPort}/events>\r\n")
                assertTrue(rejected, rejected.startsWith("HTTP/1.1 412"))
                val received = LinkedBlockingQueue<String>()
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                val job = executor.submit {
                    (callback.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.inputStream.bufferedReader()
                        val head = mutableListOf<String>()
                        while (true) { val row = reader.readLine(); if (row.isEmpty()) break; head.add(row) }
                        val size = head.first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                        val data = CharArray(size)
                        var count = 0
                        while (count < size) { val read = reader.read(data, count, size - count); check(read > 0); count += read }
                        received.offer(head.joinToString("\n") + "\n" + String(data))
                        socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.outputStream.flush()
                    }
                }
                try {
                    // Read only the HTTP reply: the publisher sends the callback before closing its socket.
                    (tls.socketFactory.createSocket("127.0.0.1", server.port) as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        socket.outputStream.write(("SUBSCRIBE ${server.path}/RenderingControl/event HTTP/1.1\r\nHost: localhost\r\nNT: upnp:event\r\nCALLBACK: <https://127.0.0.1:${callback.localPort}/events>\r\nTIMEOUT: Second-300\r\n\r\n").toByteArray())
                        socket.outputStream.flush()
                        val response = socket.inputStream.bufferedReader().readText()
                        assertTrue(response, response.startsWith("HTTP/1.1 200"))
                    }
                    job.get(5, TimeUnit.SECONDS)
                    val notification = received.poll(5, TimeUnit.SECONDS)!!
                    assertTrue(notification, notification.startsWith("NOTIFY /events HTTP/1.1"))
                    assertTrue(notification.contains("SEQ: 0"))
                    assertTrue(notification.contains("volume=73"))
                } finally { executor.shutdownNow() }
            }
        }
    }
}
