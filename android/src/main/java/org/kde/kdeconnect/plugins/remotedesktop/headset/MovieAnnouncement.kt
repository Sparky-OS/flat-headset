/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface
import java.util.UUID
import org.json.JSONObject
import org.kde.kdeconnect.headset.upnp.NativeRenderer

class MovieAnnouncement(context: Context, panel: JSONObject, listener: NativeRenderer.Listener, capabilities: MovieCapabilities) :
    Closeable {
    private val multicast =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("Flat Headset movies")
            .apply { setReferenceCounted(false) }
    private val renderer = NativeRenderer(listener)
    val udn =
        "uuid:" + UUID.nameUUIDFromBytes(panel.getString("identity").toByteArray(Charsets.US_ASCII))
    val address: String

    init {
        val web = File(context.cacheDir, "headset-movies").apply { mkdirs() }
        for (service in MovieServices.all) File(web, service.name + ".xml")
            .writeText(service.scpd())
        val description =
            """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:stereo="urn:sparky-os:headset:1">
            <specVersion><major>1</major><minor>0</minor></specVersion><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:3</deviceType>
            <friendlyName>${MovieServices.escape(panel.getString("name"))}</friendlyName>
            <modelDescription>FlatHeadset/1.0 Panel/${panel.getInt("width")}x${panel.getInt("height")}</modelDescription>
            <manufacturer>KDE contributors</manufacturer><modelName>Flat Headset</modelName><UDN>$udn</UDN>
            <stereo:identity>${panel.getString("identity")}</stereo:identity>
            <stereo:panelWidth>${panel.getInt("width")}</stereo:panelWidth>
            <stereo:panelHeight>${panel.getInt("height")}</stereo:panelHeight>
            ${capabilities.description()}
            <serviceList>${MovieServices.all.joinToString("") { it.description() }}</serviceList>
            </device></root>"""
                .trimIndent()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.getLinkProperties(connectivity.activeNetwork)?.interfaceName
        val interfaces =
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val name =
            interfaces.firstOrNull { it.name == active }?.name
                ?: interfaces
                    .firstOrNull {
                        it.name.startsWith("rndis") ||
                            it.name.startsWith("usb") ||
                            it.name == "eth0"
                    }
                    ?.name
                ?: error("No local network interface")
        try {
            multicast.acquire()
            address = renderer.start(name, web.absolutePath, description)
        } catch (error: Exception) {
            renderer.close()
            if (multicast.isHeld) multicast.release()
            throw error
        }
    }

    fun notify(service: MovieServices.Service, properties: String) =
        renderer.notifyProperties(udn, service.id, properties)

    override fun close() {
        renderer.close()
        if (multicast.isHeld) multicast.release()
    }
}
