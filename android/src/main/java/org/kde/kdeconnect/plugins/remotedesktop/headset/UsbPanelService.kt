/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Bound only by our foreground headset session; no independent pairing or control server. */
class UsbPanelService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var announcement: NativePanelAnnouncement? = null
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != START) return@Handler false
        val data = message.data
        val reply = message.replyTo
        worker.execute {
            try {
                announcement?.close()
                announcement = NativePanelAnnouncement(this, JSONObject(data.getString("panel")!!),
                    data.getInt("port"), data.getString("interface"))
                reply?.send(Message.obtain(null, READY))
            } catch (error: Exception) {
                android.util.Log.w("HeadsetUsb", "USB discovery failed", error)
                reply?.send(Message.obtain(null, FAILED))
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = incoming.binder
    override fun onDestroy() {
        try {
            // Finish the process-global native instance before Android can bind a
            // replacement service after a link disappears and returns.
            worker.submit { announcement?.close(); announcement = null }.get(10, TimeUnit.SECONDS)
        } finally {
            worker.shutdown()
            super.onDestroy()
        }
    }
    companion object { const val START = 1; const val READY = 2; const val FAILED = 3 }
}

internal class UsbPanelMirror(context: Context, private val panel: JSONObject, private val port: Int,
                              private val primary: String, private val onFailure: () -> Unit) : Closeable {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var binding: ServiceConnection? = null
    private var key: String? = null
    private val scan = object : Runnable {
        override fun run() {
            if (closed) return
            try { update() } catch (error: Exception) { fail(error) }
            if (!closed) handler.postDelayed(this, 2000)
        }
    }
    init { handler.post(scan) }

    private fun update() {
        val candidates = NetworkInterface.getNetworkInterfaces().toList().filter {
            it.name != primary && (it.name.startsWith("usb") || it.name.startsWith("rndis")) && it.isUp && !it.isLoopback
        }.mapNotNull { network ->
            val address = network.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
            address?.let { network.name to it.hostAddress }
        }
        require(candidates.size <= 1) { "More than one USB headset interface" }
        val candidate = candidates.singleOrNull()
        val next = candidate?.let { it.first + "/" + it.second }
        if (next == key) return
        unbind()
        key = next
        if (candidate == null) return
        val connection = object : ServiceConnection {
            private val timeout = Runnable { if (!closed && binding === this) fail(IllegalStateException("USB discovery timed out")) }
            private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (closed || binding !== this) return@Handler true
                handler.removeCallbacks(timeout)
                if (message.what != UsbPanelService.READY) fail(IllegalStateException("USB discovery refused"))
                true
            })
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed || binding !== this) return
                try {
                    Messenger(service).send(Message.obtain(null, UsbPanelService.START).apply {
                        data = Bundle().apply {
                            putString("panel", panel.toString()); putInt("port", port); putString("interface", candidate.first)
                        }
                        replyTo = reply
                    })
                    handler.postDelayed(timeout, 20000)
                } catch (error: Exception) { fail(error) }
            }
            override fun onServiceDisconnected(name: ComponentName) { handler.removeCallbacks(timeout); if (!closed && binding === this) fail(IllegalStateException("USB discovery stopped")) }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        binding = connection
        if (!context.bindService(Intent(context, UsbPanelService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            binding = null
            throw IllegalStateException("Cannot bind USB discovery")
        }
    }
    private fun unbind() { binding?.let { context.unbindService(it) }; binding = null; key = null }
    private fun fail(error: Exception) {
        if (closed) return
        android.util.Log.w("HeadsetUsb", "USB discovery unavailable", error)
        close()
        onFailure()
    }
    override fun close() {
        closed = true
        handler.removeCallbacks(scan)
        handler.post { unbind() }
    }
}
