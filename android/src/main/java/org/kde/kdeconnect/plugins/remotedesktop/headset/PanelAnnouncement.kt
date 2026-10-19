/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import org.json.JSONObject
import org.kde.kdeconnect.headset.upnp.NativeRenderer
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface
import java.net.Inet4Address
import java.util.UUID

class PanelAnnouncement(context: Context, panel: JSONObject, port: Int, directInterface: String? = null) : Closeable {
    private val multicast = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("Flat Headset discovery").apply { setReferenceCounted(false) }
    private val renderer = NativeRenderer(object : NativeRenderer.Listener {
        override fun onAction(serviceId: String, action: String, requestXml: String) = "!401"
        override fun snapshot(serviceId: String) = ""
    })
    val address: String

    init {
        val identity = panel.getString("identity")
        val uuid = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.US_ASCII))
        val web = File(context.cacheDir, "headset-discovery").apply { mkdirs() }
        val description = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:stereo="urn:sparky-os:headset:1">
            <specVersion><major>1</major><minor>0</minor></specVersion><device>
            <deviceType>urn:schemas-sparky-os:device:Headset:1</deviceType>
            <friendlyName>Flat Headset</friendlyName><manufacturer>KDE contributors</manufacturer>
            <modelName>Flat Headset</modelName><UDN>uuid:$uuid</UDN>
            <stereo:identity>$identity</stereo:identity><stereo:controlPort>$port</stereo:controlPort>
            </device></root>""".trimIndent()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.getLinkProperties(connectivity.activeNetwork)?.interfaceName
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList().filter {
                it.isUp && !it.isLoopback && it.inetAddresses.toList().any { address -> address is Inet4Address }
            }.map { it.name }
            val name = PanelNetworkInterface.select(interfaces, active, directInterface)
            multicast.acquire()
            address = renderer.start(name, web.absolutePath, description)
        } catch (error: Exception) {
            renderer.close()
            if (multicast.isHeld) multicast.release()
            throw error
        }
    }

    override fun close() { renderer.close(); if (multicast.isHeld) multicast.release() }
}
