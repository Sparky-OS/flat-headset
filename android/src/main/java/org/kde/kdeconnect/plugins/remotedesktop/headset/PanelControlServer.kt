/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import org.json.JSONObject
import org.kde.kdeconnect.helpers.security.RsaHelper
import org.kde.kdeconnect.helpers.security.SslHelper
import java.io.Closeable
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Certificate-pinned control channel, active only while the user opens the viewer. */
class PanelControlServer(context: Context, private val panel: JSONObject,
                         private val connect: (InetAddress, JSONObject) -> Unit,
                         private val disconnected: () -> Unit) : Closeable {
    private val worker = Executors.newSingleThreadExecutor()
    private val channels = Executors.newFixedThreadPool(4)
    private val slots = Semaphore(4)
    private val clients = ConcurrentHashMap.newKeySet<SSLSocket>()
    @Volatile private var closed = false
    private val server: SSLServerSocket
    val port: Int get() = server.localPort

    init {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("panel", RsaHelper.getPrivateKey(context), charArrayOf(), arrayOf(SslHelper.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, charArrayOf()) }
        val tls = SSLContext.getInstance("TLSv1.2").apply { init(keys.keyManagers, null, null) }
        server = tls.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        worker.execute {
            while (!closed) {
                try {
                    val socket = server.accept() as SSLSocket
                    if (!slots.tryAcquire()) { socket.close(); continue }
                    clients.add(socket)
                    channels.execute {
                        try { socket.use { handle(it) } }
                        catch (error: Exception) {
                            android.util.Log.i("PanelControl", "Control channel closed: ${error.javaClass.simpleName}")
                        } finally {
                            clients.remove(socket)
                            slots.release()
                            if (!closed && clients.isEmpty()) disconnected()
                        }
                    }
                } catch (_: Exception) {
                    if (!closed) disconnected()
                }
            }
        }
    }

    private fun handle(socket: SSLSocket) {
        socket.soTimeout = 15000
        socket.startHandshake()
        val output = socket.outputStream.bufferedWriter(Charsets.UTF_8)
        output.write(JSONObject().put("panel", panel).toString() + "\n")
        output.flush()
        val input = socket.inputStream
        var connected = false
        while (!closed) {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw java.io.EOFException()
                if (next == 10) break
                require(bytes.size() < 16384)
                bytes.write(next)
            }
            val message = JSONObject(bytes.toString("UTF-8"))
            when (message.getString("action")) {
                "ping" -> { output.write("{\"action\":\"pong\"}\n"); output.flush() }
                "connect" -> {
                    require(!connected)
                    if (panel.optInt("version") == 2) {
                        val accepted = message.getJSONObject("panel")
                        require(panel.keys().asSequence().all { key ->
                            val expected = panel.get(key)
                            val actual = accepted.opt(key)
                            // JSON does not preserve Integer/Double storage: scale 1.0
                            // returns from the host as 1. Compare numeric values while
                            // retaining type checks for strings and booleans.
                            accepted.has(key) && if (expected is Number && actual is Number) {
                                expected.toDouble() == actual.toDouble()
                            } else {
                                expected == actual
                            }
                        }) { "Host accepted a different panel descriptor" }
                    }
                    require(message.getString("identity") == panel.getString("identity"))
                    require(message.getInt("port") in 1..65535)
                    require(message.getString("certificateFingerprint").matches(Regex("[a-f0-9]{64}")))
                    require(message.getString("username").length in 1..256)
                    require(message.getString("password").length in 1..256)
                    require(message.getString("security") == "nla")
                    connected = true
                    android.util.Log.i("PanelControl", "RDP endpoint received over TLS")
                    // The RDP endpoint must be the PC on this TLS connection.
                    connect(socket.inetAddress, message)
                }
                else -> error("Unknown control request")
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
        clients.forEach { it.close() }
        worker.shutdownNow()
        channels.shutdownNow()
    }
}
