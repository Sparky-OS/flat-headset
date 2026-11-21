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
class PanelControlServerTest {
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

    /** Exercise the actual TLS listener and measured descriptor, not a direct viewer intent. */
    private fun exchange(format: String, accepted: Boolean = true,
                         change: (JSONObject) -> Unit = {}) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Activity.MODE_PRIVATE)
                .edit().putString(HeadsetLink.PREFERENCE, format).commit()
            val panel = PanelDescriptor.measure(activity, true)
            TrustedDevices.getDeviceSettings(activity, "paired-pc").edit()
                .putString("certificate", android.util.Base64.encodeToString(clientCertificate.encoded, 0)).commit()
            TrustedDevices.addTrustedDevice(activity, "paired-pc")
            assertTrue(panel.get("scale") is Double)
            val events = LinkedBlockingQueue<String>()
            PanelControlServer(activity, panel, { _, message ->
                events.offer("connect:${message.getString("identity")}")
            }, { events.offer("disconnect") }).use { server ->
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("panel", SslHelper.certificate)
                }
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(store)
                }
                store.setKeyEntry("client", clientKeys.private, charArrayOf(), arrayOf(clientCertificate))
                val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, charArrayOf()) }
                val tls = SSLContext.getInstance("TLSv1.2").apply { init(keys.keyManagers, trust.trustManagers, null) }
                (tls.socketFactory.createSocket("127.0.0.1", server.port) as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    val announcement = JSONObject(socket.inputStream.bufferedReader().readLine())
                    // JSON transports numbers, not Java's Integer/Double distinction. The
                    // production Qt host also writes the advertised scale 1.0 back as 1.
                    val echoed = announcement.getJSONObject("panel")
                    assertEquals(1, echoed.get("scale"))
                    val reply = JSONObject().apply {
                        put("action", "connect")
                        put("identity", panel.getString("identity"))
                        put("port", 3391)
                        put("certificateFingerprint", "ab".repeat(32))
                        put("username", "test-user")
                        put("password", "test-password")
                        put("security", "nla")
                        if (format != HeadsetLink.NATIVE) put("panel", echoed)
                    }
                    change(reply)
                    socket.outputStream.bufferedWriter().apply {
                        write(reply.toString() + "\n")
                        flush()
                    }
                    assertEquals(format, if (accepted) "connect:${panel.getString("identity")}" else "disconnect",
                        events.poll(5, TimeUnit.SECONDS))
                }
            }
            TrustedDevices.removeTrustedDevice(activity, "paired-pc")
        }
    }

    @Test fun trackedHeadsetIgnoresStaticLinkPreferences() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            for (format in HeadsetLink.formats) {
                activity.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Activity.MODE_PRIVATE)
                    .edit().putString(HeadsetLink.PREFERENCE, format).commit()
                val panel = PanelDescriptor.tracked(activity)
                assertEquals(HeadsetLink.HALF_SBS_720, panel.getString("linkFormat"))
                assertEquals("orientation", panel.getString("tracking"))
                assertEquals(true, panel.getBoolean("splitEye"))
                val flat = PanelDescriptor.measure(activity, true)
                assertEquals(false, flat.has("tracking"))
                assertTrue(flat.getString("name").startsWith("Flat Headset ("))
                assertTrue(panel.getString("name").startsWith("StereoDroidVR ("))
                assertEquals(flat.getString("identity"), panel.getString("identity"))
                assertEquals(format, activity.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Activity.MODE_PRIVATE)
                    .getString(HeadsetLink.PREFERENCE, null))
            }
        }
    }

    @Test fun unpairedControlCannotReadPanel() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val panel = PanelDescriptor.measure(activity, true)
            val events = LinkedBlockingQueue<String>()
            PanelControlServer(activity, panel, { _, _ -> events.offer("connect") }, {}).use { server ->
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("panel", SslHelper.certificate)
                }
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                val tls = SSLContext.getInstance("TLSv1.2").apply { init(null, trust.trustManagers, null) }
                var received = false
                (tls.socketFactory.createSocket("127.0.0.1", server.port) as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    try {
                        socket.startHandshake()
                        received = socket.inputStream.read() >= 0
                    } catch (error: java.io.IOException) {
                        if (error is java.net.SocketTimeoutException) throw error
                        // The peer must be refused before the panel descriptor is disclosed.
                    }
                }
                assertEquals("An unpaired peer received the panel descriptor", false, received)
                assertEquals(null, events.poll())
            }
        }
    }

    @Test fun revokedPairCannotConnectOnAnEstablishedChannel() = exchange(HeadsetLink.SBS_720, false) {
        TrustedDevices.removeTrustedDevice(RuntimeEnvironment.getApplication(), "paired-pc")
    }

    @Test fun nativeStillConnectsWithoutDescriptorEcho() = exchange(HeadsetLink.NATIVE)
    @Test fun fullSideBySideConnectsAfterJsonRoundTrip() = exchange(HeadsetLink.SBS_720)
    @Test fun halfTopBottomConnectsAfterJsonRoundTrip() = exchange(HeadsetLink.HALF_TB_720)
    @Test fun halfSideBySideConnectsAfterJsonRoundTrip() = exchange(HeadsetLink.HALF_SBS_720)
    @Test fun fullTopBottomConnectsAfterJsonRoundTrip() = exchange(HeadsetLink.TB_720)

    @Test fun changedPanelFieldsAreRejected() {
        val replacements = mapOf("version" to 1, "linkFormat" to HeadsetLink.HALF_SBS_720,
            "identity" to "different-device", "name" to "different-name", "width" to 2,
            "height" to 2, "refreshMilliHz" to 1, "scale" to 1.25,
            "widthMm" to 9999, "heightMm" to 9999, "splitEye" to false,
            "lensProfile" to "different-lens")
        for ((key, value) in replacements) exchange(HeadsetLink.SBS_720, false) {
            it.getJSONObject("panel").put(key, value)
        }
    }

    @Test fun missingAndMistypedPanelFieldsAreRejected() {
        for (key in listOf("scale", "width", "splitEye", "identity", "linkFormat")) {
            exchange(HeadsetLink.SBS_720, false) { it.getJSONObject("panel").remove(key) }
        }
        for (key in listOf("scale", "width", "splitEye")) {
            exchange(HeadsetLink.SBS_720, false) {
                val panel = it.getJSONObject("panel")
                panel.put(key, panel.get(key).toString())
            }
        }
    }

    @Test fun invalidConnectionParametersAreRejected() {
        val replacements = mapOf("identity" to "different-device", "port" to 0,
            "certificateFingerprint" to "invalid", "username" to "", "password" to "",
            "security" to "rdp")
        for ((key, value) in replacements) exchange(HeadsetLink.SBS_720, false) { it.put(key, value) }
    }
}
