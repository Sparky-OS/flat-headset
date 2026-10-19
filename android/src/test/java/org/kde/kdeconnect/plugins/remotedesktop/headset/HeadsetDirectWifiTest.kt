/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect_tp.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 28, 33])
class HeadsetDirectWifiTest {
    private lateinit var context: Context
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var group: WifiP2pGroup? = null
    private var ready = false
    private var waiting = false
    private var failure = 0
    private var addressReady = true
    private val discover = slot<WifiP2pManager.ActionListener>()

    @Before fun setup() {
        ShadowLog.stream = System.err
        context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, true)
        shadowOf(context as Application).grantPermissions(HeadsetDirectWifi.permission())
        manager = mockk()
        channel = mockk(relaxed = true)
        every { manager.initialize(any(), any(), any()) } returns channel
        every { manager.requestGroupInfo(channel, any()) } answers {
            secondArg<WifiP2pManager.GroupInfoListener>().onGroupInfoAvailable(group)
        }
        every { manager.discoverPeers(channel, capture(discover)) } just Runs
        every { manager.stopPeerDiscovery(channel, any()) } just Runs
    }
    private fun session() = HeadsetDirectWifi(context, { assertEquals("p2p-test", it); ready = true },
        { failure = it }, manager, { addressReady }, { waiting = true })
    private fun connected(owner: Boolean = false, clients: Int = 1, network: String = "DIRECT-test") {
        group = mockk {
            every { `interface` } returns "p2p-test"
            every { isGroupOwner } returns owner
            every { networkName } returns network
            every { getOwner() } returns WifiP2pDevice().apply { deviceAddress = "02:00:00:00:00:01" }
            every { clientList } returns List(clients) { WifiP2pDevice() }
        }
        context.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun leavesExistingGroupAlone() {
        group = mockk()
        session().use { it.start() }
        assertEquals(R.string.headset_direct_busy, failure)
        verify(exactly = 0) { manager.discoverPeers(any(), any()); manager.removeGroup(any(), any()) }
    }
    @Test fun negotiatesBeforeAdvertisingInsteadOfCreatingAnAutonomousGroup() {
        session().use {
            it.start(); discover.captured.onSuccess()
            assertTrue(waiting); assertFalse(ready)
            verify(exactly = 0) { manager.createGroup(any(), any()) }
            connected(); assertTrue(ready)
        }
        // Android and the PC manage this negotiated group; do not remove a global group.
        verify(exactly = 0) { manager.removeGroup(any(), any()) }
    }
    @Test fun eitherNegotiatedRoleRequiresAPeerAndAnAddress() {
        addressReady = false
        session().use {
            it.start(); discover.captured.onSuccess()
            connected(owner = true, clients = 0); assertFalse(ready)
            addressReady = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertFalse(ready)
            connected(owner = true, clients = 1); assertTrue(ready)
        }
    }
    @Test fun clientWaitsForDhcpAddress() {
        addressReady = false
        session().use {
            it.start(); discover.captured.onSuccess(); connected(); assertFalse(ready)
            addressReady = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(ready); assertEquals(0, failure)
        }
    }
    @Test fun stopDuringDiscoveryCleansUpLateSuccess() {
        val session = session()
        session.start(); session.close(); discover.captured.onSuccess()
        assertFalse(ready); assertFalse(waiting)
        verify(exactly = 1) { manager.stopPeerDiscovery(channel, any()) }
        verify(exactly = 0) { manager.removeGroup(any(), any()) }
    }
    @Test fun permissionDeniedDoesNotDiscover() {
        shadowOf(context as Application).denyPermissions(HeadsetDirectWifi.permission())
        session().use { it.start() }
        assertEquals(R.string.headset_direct_permission, failure)
        verify(exactly = 0) { manager.discoverPeers(any(), any()) }
    }
    @Test fun missingAddressTimesOutWithoutRemovingGlobalGroups() {
        addressReady = false
        session().use {
            it.start(); discover.captured.onSuccess(); connected()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        }
        assertEquals(R.string.headset_direct_timeout, failure)
        verify(exactly = 1) { manager.stopPeerDiscovery(channel, any()) }
        verify(exactly = 0) { manager.removeGroup(any(), any()) }
    }
    @Test fun missingHardwareDoesNotStart() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT, false)
        session().use { it.start() }
        assertEquals(R.string.headset_direct_unavailable, failure)
        verify(exactly = 0) { manager.discoverPeers(any(), any()) }
    }
    @Test fun losingTheGroupEndsTheSession() {
        session().use {
            it.start(); discover.captured.onSuccess(); connected(); assertTrue(ready)
            group = null
            context.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(R.string.headset_direct_lost, failure)
        }
    }
    @Test fun replacementGroupDoesNotReuseTheAdvertisedSession() {
        session().use {
            it.start(); discover.captured.onSuccess(); connected()
            connected(network = "DIRECT-another")
            assertEquals(R.string.headset_direct_lost, failure)
        }
        verify(exactly = 0) { manager.removeGroup(any(), any()) }
    }
    @Test fun directInterfaceCannotFallBackToHomeWifi() {
        assertEquals("p2p-test", PanelNetworkInterface.select(listOf("wlan0", "p2p-test"), "wlan0", "p2p-test"))
        assertThrows(IllegalArgumentException::class.java) {
            PanelNetworkInterface.select(listOf("wlan0"), "wlan0", "p2p-missing")
        }
        assertEquals("wlan0", PanelNetworkInterface.select(listOf("wlan0", "rndis0"), "wlan0", null))
        assertEquals("rndis0", PanelNetworkInterface.select(listOf("rndis0"), null, null))
    }
    @Test fun directTimingHistoryIsIndependent() {
        for (direct in listOf(false, true)) {
            context.getSharedPreferences(if (direct) "headset-direct-link-timing" else "headset-link-timing", 0).edit().clear().commit()
        }
        val stats = HeadsetLinkStats(context, HeadsetLink.HALF_TB_720, true)
        repeat(60) { i -> stats.submitted(1_000_000_000L + i * 16_000_000, 1_002_000_000L + i * 16_000_000) }
        assertTrue(HeadsetLinkStats.read(context).isEmpty())
        assertTrue(HeadsetLinkStats.read(context, true).containsKey(HeadsetLink.HALF_TB_720))
    }
}
