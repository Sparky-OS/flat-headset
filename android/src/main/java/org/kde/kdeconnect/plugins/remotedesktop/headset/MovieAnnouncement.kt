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

class MovieAnnouncement(context: Context, panel: JSONObject, listener: NativeRenderer.Listener, capabilities: MovieCapabilities,
                        controller: (java.net.InetAddress, java.security.cert.X509Certificate) -> Unit) :
    Closeable {
    private val multicast =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("Flat Headset movies")
            .apply { setReferenceCounted(false) }
    private val renderer = NativeRenderer(object : NativeRenderer.Listener {
        override fun onAction(serviceId: String, action: String, requestXml: String) = "!606"
        override fun snapshot(serviceId: String) = ""
    })
    private val secure: PairedMovieServer
    val udn =
        "uuid:" + UUID.nameUUIDFromBytes(panel.getString("identity").toByteArray(Charsets.US_ASCII))
    val address: String

    init {
        val web = File(context.cacheDir, "headset-movies").apply { mkdirs() }
        for (service in MovieServices.all) File(web, service.name + ".xml")
            .writeText(service.scpd())
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.getLinkProperties(connectivity.activeNetwork)?.interfaceName
        val interfaces = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val network = interfaces.firstOrNull { it.name == active }
            ?: interfaces.firstOrNull { it.name.startsWith("rndis") || it.name.startsWith("usb") || it.name == "eth0" }
            ?: error("No local network interface")
        val host = network.inetAddresses.toList().first { it is java.net.Inet4Address }.hostAddress
        secure = PairedMovieServer(PairedTls(context), panel.getString("identity"), listener, controller)
        val endpoint = "https://$host:${secure.port}${secure.path}"
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
            <stereo:pairedControl>true</stereo:pairedControl>
            <serviceList>${MovieServices.all.joinToString("") { it.description(endpoint) }}</serviceList>
            </device></root>"""
                .trimIndent()
        try {
            multicast.acquire()
            address = renderer.start(network.name, web.absolutePath, description)
        } catch (error: Exception) {
            secure.close()
            renderer.close()
            if (multicast.isHeld) multicast.release()
            throw error
        }
    }

    fun notify(service: MovieServices.Service, properties: String) =
        secure.notify(service, properties)

    override fun close() {
        secure.close()
        renderer.close()
        if (multicast.isHeld) multicast.release()
    }
}
