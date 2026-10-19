/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.remotedesktop

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.BuildConfig
import org.kde.kdeconnect_tp.R
import java.net.Inet6Address
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class RemoteDesktopPluginTest {
    private lateinit var context: Context
    private lateinit var device: Device
    private lateinit var plugin: RemoteDesktopPlugin
    private lateinit var lanHost: InetAddress
    private val sentPackets = mutableListOf<NetworkPacket>()

    @Before
    fun setUp() {
        context = spyk(ApplicationProvider.getApplicationContext<Application>())
        every { context.getString(any()) } returns "test error"
        lanHost = InetAddress.getByName("192.0.2.15")
        device = mockk {
            every { deviceId } returns "desktop-id"
            every { name } returns "Plasma desktop"
            every { lanHostAddress } returns lanHost
            every { sendPacket(capture(sentPackets)) } returns Unit
            every { sendPacket(capture(sentPackets), any()) } answers {
                arg<Device.SendPacketStatusCallback>(1).onSuccess()
            }
        }
        plugin = RemoteDesktopPlugin().apply {
            setContext(context, device)
            // Suppress the UI notification; these tests exercise protocol state.
            addListener(object : RemoteDesktopPlugin.Listener {
                override fun onRemoteDesktopStateChanged() = Unit
            })
        }
    }

    @Test
    fun acceptsPinnedV2ConnectionFromAuthenticatedLanHost() {
        val fingerprint = "ab".repeat(32)

        assertTrue(plugin.onPacketReceived(connectPacket("session-1", fingerprint)))

        val connection = plugin.consumePendingConnection()
        assertEquals("session-1", connection?.sessionId)
        assertSame(lanHost, connection?.host)
        assertEquals(1739, connection?.port)
        assertEquals("kdeconnect", connection?.username)
        assertEquals("one-time password", connection?.password)
        assertEquals(fingerprint, connection?.certificateFingerprint)
        assertEquals(RemoteDesktopPlugin.SECURITY_TLS, connection?.securityMode)
        assertEquals(RemoteDesktopPlugin.STATUS_READY, plugin.status)
    }

    @Test
    fun invalidForeignConnectionDoesNotReplaceActiveSession() {
        assertTrue(plugin.onPacketReceived(connectPacket("active-session", "cd".repeat(32))))
        plugin.consumePendingConnection()
        sentPackets.clear()

        assertTrue(plugin.onPacketReceived(connectPacket("foreign-session", "not-a-sha256-fingerprint")))

        assertEquals("active-session", plugin.currentSessionId)
        assertEquals(RemoteDesktopPlugin.STATUS_READY, plugin.status)
        assertEquals(1, sentPackets.size)
        assertEquals("foreign-session", sentPackets.single().getString("sessionId"))
        assertEquals(RemoteDesktopPlugin.STATUS_FAILED, sentPackets.single().getString("status"))
        assertNull(plugin.consumePendingConnection())
    }

    @Test
    fun v2ConnectionRequiresBoundedSessionIdAndFingerprint() {
        assertTrue(plugin.onPacketReceived(connectPacket("", "ef".repeat(32))))
        assertNull(plugin.currentSessionId)
        assertEquals(RemoteDesktopPlugin.STATUS_FAILED, plugin.status)
        assertEquals(RemoteDesktopPlugin.STATUS_FAILED, sentPackets.last().getString("status"))

        sentPackets.clear()
        assertTrue(plugin.onPacketReceived(connectPacket("x".repeat(129), "")))
        assertNull(plugin.currentSessionId)
        assertEquals(RemoteDesktopPlugin.STATUS_FAILED, sentPackets.single().getString("status"))
    }

    @Test
    fun connectionRejectsUnpinnedRdpSecurity() {
        val packet = connectPacket("insecure-session", "78".repeat(32)).apply {
            this["security"] = "rdp"
        }

        assertTrue(plugin.onPacketReceived(packet))

        assertNull(plugin.currentSessionId)
        assertEquals(RemoteDesktopPlugin.STATUS_FAILED, plugin.status)
        assertEquals(RemoteDesktopPlugin.STATUS_FAILED, sentPackets.last().getString("status"))
    }

    @Test
    fun clientConnectedUsesTheCurrentSession() {
        plugin.onPacketReceived(connectPacket("session-2", "12".repeat(32)))
        sentPackets.clear()

        plugin.markClientConnected("session-2")

        assertEquals(RemoteDesktopPlugin.STATUS_CONNECTED, plugin.status)
        assertEquals(1, sentPackets.size)
        assertEquals("kdeconnect.virtualmonitor.request", sentPackets.single().type)
        assertEquals("clientConnected", sentPackets.single().getString("action"))
        assertEquals("session-2", sentPackets.single().getString("sessionId"))
    }

    @Test
    fun awaitingDesktopPermissionKeepsTheRequestedSessionActive() {
        plugin.onPacketReceived(NetworkPacket("kdeconnect.virtualmonitor").apply {
            this["protocolVersion"] = RemoteDesktopPlugin.PROTOCOL_VERSION
            this["action"] = "capabilities"
            this["supports_remote_desktop"] = true
        })
        sentPackets.clear()

        plugin.requestSession(RemoteDesktopPlugin.SessionMode.MONITOR, 0, 1280, 720, 1.0, 70)
        val sessionId = sentPackets.single { it.getString("action") == "request" }.getString("sessionId")

        plugin.onPacketReceived(NetworkPacket("kdeconnect.virtualmonitor").apply {
            this["protocolVersion"] = RemoteDesktopPlugin.PROTOCOL_VERSION
            this["action"] = "status"
            this["sessionId"] = sessionId
            this["status"] = RemoteDesktopPlugin.STATUS_AWAITING_PERMISSION
        })

        assertEquals(RemoteDesktopPlugin.STATUS_AWAITING_PERMISSION, plugin.status)
        assertEquals(sessionId, plugin.currentSessionId)
        assertNull(plugin.consumePendingConnection())
    }

    @Test
    fun capabilityRefreshExplicitlyRequestsFreshHostPreflight() {
        plugin.requestCapabilities()

        val packet = sentPackets.single()
        assertEquals("kdeconnect.virtualmonitor.request", packet.type)
        assertEquals(RemoteDesktopPlugin.PROTOCOL_VERSION, packet.getInt("protocolVersion"))
        assertEquals("requestCapabilities", packet.getString("action"))
    }

    @Test
    fun unavailableHostReasonIsReportedAndClearedAfterRecovery() {
        plugin.onPacketReceived(NetworkPacket("kdeconnect.virtualmonitor").apply {
            this["protocolVersion"] = RemoteDesktopPlugin.PROTOCOL_VERSION
            this["action"] = "capabilities"
            this["supports_remote_desktop"] = false
            this["remoteDesktopUnavailableReason"] = "KRdp is incompatible"
        })

        assertFalse(plugin.remoteDesktopAvailable)
        assertEquals("KRdp is incompatible", plugin.remoteDesktopUnavailableReason)

        plugin.onPacketReceived(NetworkPacket("kdeconnect.virtualmonitor").apply {
            this["protocolVersion"] = RemoteDesktopPlugin.PROTOCOL_VERSION
            this["action"] = "capabilities"
            this["supports_remote_desktop"] = true
            this["remoteDesktopUnavailableReason"] = "stale reason"
        })

        assertTrue(plugin.remoteDesktopAvailable)
        assertNull(plugin.remoteDesktopUnavailableReason)
    }

    @Test
    fun freeRdpUriHardPinsCertificateAndUsesAvc420() {
        val uri = buildFreeRdpUri(
            RemoteDesktopPlugin.ConnectionInfo(
                "session-3",
                lanHost,
                1740,
                "user name",
                "p&ss word",
                "34".repeat(32)
            )
        )

        assertEquals("freerdp", uri.scheme)
        assertEquals("192.0.2.15", uri.host)
        assertEquals(1740, uri.port)
        assertEquals("p&ss word", uri.getQueryParameter("p"))
        assertEquals("deny,fingerprint:sha256:${"34".repeat(32)}", uri.getQueryParameter("cert"))
        assertEquals("tls", uri.getQueryParameter("sec"))
        assertEquals("AVC420", uri.getQueryParameter("gfx"))
        assertFalse(uri.queryParameterNames.contains("dynamic-resolution"))
        assertEquals("-", uri.getQueryParameter("clipboard"))
        assertEquals("2", uri.getQueryParameter("audio-mode"))
    }

    @Test
    fun freeRdpUriRetainsIpv6Scope() {
        val addressBytes = ByteArray(16).apply {
            this[0] = 0xfe.toByte()
            this[1] = 0x80.toByte()
            this[15] = 1
        }
        val scopedAddress = Inet6Address.getByAddress(null, addressBytes, 7)
        val uri = buildFreeRdpUri(
            RemoteDesktopPlugin.ConnectionInfo("legacy", scopedAddress, 1741, "user", "password", "")
        )

        assertTrue(uri.encodedAuthority?.contains("%257") == true)
        assertFalse(uri.toString().contains("%257%25"))
        assertEquals("tofu", uri.getQueryParameter("cert"))
    }

    @Test
    fun diagnosticsCanBeSentWithoutSessionSecrets() {
        val fingerprint = "56".repeat(32)
        plugin.onPacketReceived(connectPacket("diagnostic-session", fingerprint))

        plugin.sendDiagnosticsToDesktop()

        val packet = sentPackets.last()
        assertEquals("kdeconnect.virtualmonitor", packet.type)
        assertEquals("diagnostic", packet.getString("action"))
        assertEquals("diagnostic-session", packet.getString("sessionId"))
        val entries = packet.getJSONArray("entries")?.toString().orEmpty()
        assertTrue(entries.contains("RDP endpoint ready"))
        assertFalse(entries.contains("one-time password"))
        assertFalse(entries.contains(fingerprint))
        assertFalse(plugin.diagnosticLog().contains("one-time password"))
        assertFalse(plugin.diagnosticLog().contains(fingerprint))
    }

    @Test
    fun diagnosticsIdentifyTheInstalledAndroidBuild() {
        plugin.onCreate()

        val log = plugin.diagnosticLog()
        assertTrue(log.contains("Android RDP client build=${BuildConfig.RDP_DIAGNOSTIC_BUILD_ID}"))
        assertTrue(log.contains("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        assertTrue(log.contains("type=${BuildConfig.BUILD_TYPE}"))

        plugin.sendDiagnosticsToDesktop()

        val packet = sentPackets.last()
        assertEquals(BuildConfig.RDP_DIAGNOSTIC_BUILD_ID, packet.getString("clientBuildId"))
        assertEquals(BuildConfig.VERSION_NAME, packet.getString("clientVersionName"))
        assertEquals(BuildConfig.VERSION_CODE, packet.getInt("clientVersionCode"))
        assertEquals(BuildConfig.BUILD_TYPE, packet.getString("clientBuildType"))
        assertTrue(packet.getInt("clientAndroidSdk") > 0)
    }

    @Test
    fun deviceTileUsesTheStaticScreenStreamIcon() {
        val button = plugin.getUiButtons().single()

        assertEquals(R.drawable.ic_open_in_full_24dp, button.iconRes)
    }

    private fun connectPacket(sessionId: String, fingerprint: String) =
        NetworkPacket("kdeconnect.virtualmonitor.request").apply {
            this["protocolVersion"] = RemoteDesktopPlugin.PROTOCOL_VERSION
            this["action"] = "connect"
            this["sessionId"] = sessionId
            this["port"] = 1739
            this["username"] = "kdeconnect"
            this["password"] = "one-time password"
            this["certificateFingerprint"] = fingerprint
            this["security"] = RemoteDesktopPlugin.SECURITY_TLS
        }
}
