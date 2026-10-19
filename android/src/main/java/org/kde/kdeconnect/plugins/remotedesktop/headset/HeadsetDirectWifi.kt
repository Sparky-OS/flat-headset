/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.kde.kdeconnect_tp.R
import java.net.Inet4Address
import java.net.NetworkInterface

/** Waits for Android's user-approved P2P negotiation before exposing the receiver. */
@SuppressLint("MissingPermission") // The activity requests the version-specific permission before start().
internal class HeadsetDirectWifi(
    private val context: Context,
    private val ready: (String) -> Unit,
    private val failed: (Int) -> Unit,
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager,
    private val interfaceReady: (String) -> Boolean = { name ->
        NetworkInterface.getByName(name)?.let { it.isUp && it.inetAddresses.toList().any { it is Inet4Address } } == true
    },
    private val waiting: () -> Unit = {},
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var closed = false
    private var startingDiscovery = false
    private var discovering = false
    private var armed = false
    private var registered = false
    private var announcedInterface: String? = null
    private var announcedGroup: String? = null
    private val timeout = Runnable { fail(R.string.headset_direct_timeout) }
    private val poll = Runnable { inspectGroup() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                fail(R.string.headset_direct_unavailable)
            } else if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION && armed) {
                inspectGroup()
            }
        }
    }

    fun start() {
        if (closed || channel != null) return
        if (manager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            fail(R.string.headset_direct_unavailable); return
        }
        if (ContextCompat.checkSelfPermission(context, permission()) != PackageManager.PERMISSION_GRANTED) {
            fail(R.string.headset_direct_permission); return
        }
        try {
            channel = manager.initialize(context, Looper.getMainLooper()) { fail(R.string.headset_direct_lost) }
            val current = channel ?: run { fail(R.string.headset_direct_unavailable); return }
            // These two protected broadcasts come from the Android Wi-Fi service.
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            handler.postDelayed(timeout, 120000)
            manager.requestGroupInfo(current) { existing ->
                if (closed) return@requestGroupInfo
                if (existing != null) { fail(R.string.headset_direct_busy); return@requestGroupInfo }
                try {
                    armed = true
                    startingDiscovery = true
                    // NetworkManager initiates GO negotiation, not a join to an autonomous
                    // group. Stay discoverable and let Android ask the user to accept it.
                    manager.discoverPeers(current, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            startingDiscovery = false
                            discovering = true
                            if (closed) stopDiscoveryAndClose()
                            else if (announcedInterface == null) { waiting(); inspectGroup() }
                            else stopDiscovery()
                        }
                        override fun onFailure(reason: Int) {
                            startingDiscovery = false
                            if (closed) closeChannel() else fail(if (reason == WifiP2pManager.BUSY)
                                R.string.headset_direct_busy else R.string.headset_direct_unavailable)
                        }
                    })
                } catch (_: SecurityException) { startingDiscovery = false; fail(R.string.headset_direct_permission) }
                catch (_: RuntimeException) { startingDiscovery = false; fail(R.string.headset_direct_unavailable) }
            }
        } catch (_: SecurityException) { fail(R.string.headset_direct_permission) }
        catch (error: RuntimeException) { Log.w("HeadsetDirectWifi", "Cannot start Wi-Fi Direct", error); fail(R.string.headset_direct_unavailable) }
    }

    private fun inspectGroup() {
        if (closed || !armed) return
        try {
            manager?.requestGroupInfo(channel!!) { group ->
                if (closed) return@requestGroupInfo
                val name = group?.`interface`
                // Either endpoint can win GO negotiation. An empty GO is not a peer link.
                val hasPeer = group != null && (!group.isGroupOwner || group.clientList.isNotEmpty())
                val key = group?.let { "${it.networkName}/${it.owner?.deviceAddress}/${it.isGroupOwner}" }
                if (name.isNullOrBlank() || !hasPeer ||
                    !runCatching { interfaceReady(name) }.getOrDefault(false)) {
                    if (announcedInterface != null) fail(R.string.headset_direct_lost) else schedulePoll()
                } else if (announcedInterface == null) {
                    announcedInterface = name
                    announcedGroup = key
                    handler.removeCallbacks(timeout)
                    handler.removeCallbacks(poll)
                    stopDiscovery()
                    ready(name)
                } else if (announcedInterface != name || announcedGroup != key) {
                    fail(R.string.headset_direct_lost)
                }
            }
        } catch (_: SecurityException) { fail(R.string.headset_direct_permission) }
        catch (_: RuntimeException) { fail(R.string.headset_direct_lost) }
    }

    private fun schedulePoll() { handler.removeCallbacks(poll); handler.postDelayed(poll, 1000) }

    private fun fail(message: Int) {
        if (closed) return
        close()
        failed(message)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(poll)
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        if (!startingDiscovery) stopDiscoveryAndClose()
        // The pending discoverPeers callback releases a late success after Stop.
        // This app did not create the negotiated group: never remove a global group
        // belonging to Android/another app. The PC owns and releases its connection.
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        try { manager?.stopPeerDiscovery(channel!!, null) }
        catch (_: RuntimeException) { /* Closing the channel is still required. */ }
    }

    private fun stopDiscoveryAndClose() { stopDiscovery(); closeChannel() }

    private fun closeChannel() {
        if (Build.VERSION.SDK_INT >= 27) channel?.close()
        channel = null
    }

    companion object {
        fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
    }
}
