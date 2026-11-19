/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.StringReader
import java.net.InetAddress
import java.net.URI
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.xml.parsers.DocumentBuilderFactory
import org.kde.kdeconnect.headset.upnp.NativeRenderer
import org.xml.sax.InputSource

/** SOAP and GENA use the existing KDE Connect identities on mutually authenticated TLS. */
class PairedMovieServer(
    private val trust: PairedTls,
    private val identity: String,
    private val listener: NativeRenderer.Listener,
    private val controller: (InetAddress, X509Certificate) -> Unit,
) : Closeable {
    private val server = (trust.create().serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true }
    private val acceptor = Executors.newSingleThreadExecutor()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    @Volatile private var closed = false
    val port get() = server.localPort
    val path get() = "/paired/$identity"
    private data class Subscription(val service: MovieServices.Service, val callback: URI, val peer: X509Certificate,
                                    @Volatile var expires: Long, var sequence: Long = 0)

    init {
        acceptor.execute {
            while (!closed) {
                try {
                    val socket = server.accept() as SSLSocket
                    sockets.add(socket)
                    try {
                        workers.execute {
                            try { socket.use { handle(it) } }
                            catch (error: Exception) { android.util.Log.i("PairedMovie", "Request refused: ${error.javaClass.simpleName}") }
                            finally { sockets.remove(socket) }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
                } catch (_: Exception) { if (!closed) android.util.Log.w("PairedMovie", "Movie listener failed") }
            }
        }
    }

    private fun line(input: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            require(next >= 0 && bytes.size() < 4096)
            if (next == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(next)
        }
    }

    private fun reply(socket: SSLSocket, code: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 412 -> "Precondition Failed"; else -> "Internal Server Error" }
        socket.outputStream.write(("HTTP/1.1 $code $reason\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
            headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.US_ASCII))
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
    }

    private fun handle(socket: SSLSocket) {
        socket.soTimeout = 5000
        socket.startHandshake()
        val peer = socket.session.peerCertificates.first() as X509Certificate
        if (!trust.isPaired(peer)) return reply(socket, 403)
        val input = BufferedInputStream(socket.inputStream)
        val request = line(input).split(' ')
        require(request.size == 3 && request[2] == "HTTP/1.1")
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val row = line(input)
            if (row.isEmpty()) break
            headerBytes += row.length
            require(headerBytes < 16384 && ':' in row)
            val key = row.substringBefore(':').lowercase(java.util.Locale.ROOT)
            require(!headers.containsKey(key))
            headers[key] = row.substringAfter(':').trim()
        }
        require("transfer-encoding" !in headers)
        val length = headers["content-length"]?.toInt() ?: 0
        require(length in 0..65536)
        val bytes = ByteArray(length)
        var count = 0
        while (count < length) { val read = input.read(bytes, count, length - count); require(read > 0); count += read }
        if (!trust.isPaired(peer)) return reply(socket, 403)
        val service = MovieServices.all.firstOrNull { request[1] == "$path/${it.name}/control" || request[1] == "$path/${it.name}/event" }
            ?: return reply(socket, 404)
        if (request[0] == "POST" && request[1].endsWith("/control")) {
            val soapAction = headers["soapaction"]?.removeSurrounding("\"") ?: return reply(socket, 400)
            if (!soapAction.startsWith(service.type + "#")) return reply(socket, 400)
            val action = soapAction.removePrefix(service.type + "#")
            if (action !in service.actions) return reply(socket, 400)
            val xml = bytes.toString(Charsets.UTF_8)
            require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val body = document.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body")
            require(body.length == 1)
            val actions = (0 until body.item(0).childNodes.length).map { body.item(0).childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            require(actions.size == 1 && actions[0].localName == action && actions[0].namespaceURI == service.type)
            val arguments = (0 until actions[0].childNodes.length).map { actions[0].childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            val inner = "<u:$action xmlns:u=\"${service.type}\">" + arguments.joinToString("") {
                require((0 until it.childNodes.length).none { index -> it.childNodes.item(index).nodeType == org.w3c.dom.Node.ELEMENT_NODE })
                "<${it.localName}>${MovieServices.escape(it.textContent)}</${it.localName}>"
            } + "</u:$action>"
            if (action == "SetAVTransportURI") controller(socket.inetAddress, peer)
            val result = listener.onAction(service.id, action, inner)
            val error = result.startsWith('!')
            val response = if (!error) result else "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>${result.drop(1).toInt()}</errorCode><errorDescription>Action failed</errorDescription></UPnPError></detail></s:Fault>"
            reply(socket, if (error) 500 else 200, "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$response</s:Body></s:Envelope>")
            return
        }
        if (!request[1].endsWith("/event")) return reply(socket, 400)
        subscriptions.entries.removeIf { it.value.expires < android.os.SystemClock.elapsedRealtime() || !trust.isPaired(it.value.peer) }
        val sid = headers["sid"]
        if (request[0] == "UNSUBSCRIBE") {
            if (sid == null) return reply(socket, 412)
            val subscription = subscriptions[sid]
            if (subscription == null || subscription.peer != peer || subscription.service != service) return reply(socket, 412)
            subscriptions.remove(sid)
            return reply(socket, 200)
        }
        if (request[0] != "SUBSCRIBE") return reply(socket, 400)
        val seconds = headers["timeout"]?.removePrefix("Second-")?.toLongOrNull()?.coerceIn(1, 1800) ?: 1800L
        if (sid != null) {
            val subscription = subscriptions[sid]
            if (subscription == null || subscription.peer != peer || subscription.service != service || "callback" in headers || "nt" in headers) return reply(socket, 412)
            subscription.expires = android.os.SystemClock.elapsedRealtime() + seconds * 1000
            return reply(socket, 200, headers = mapOf("SID" to sid, "TIMEOUT" to "Second-$seconds"))
        }
        if (subscriptions.size >= 8 || headers["nt"] != "upnp:event") return reply(socket, 412)
        val callback = URI(headers["callback"]?.removeSurrounding("<", ">") ?: return reply(socket, 412))
        if (callback.scheme != "https" || callback.userInfo != null || callback.fragment != null || InetAddress.getByName(callback.host) != socket.inetAddress) return reply(socket, 412)
        val id = "uuid:" + UUID.randomUUID()
        val subscription = Subscription(service, callback, peer, android.os.SystemClock.elapsedRealtime() + seconds * 1000)
        subscriptions[id] = subscription
        reply(socket, 200, headers = mapOf("SID" to id, "TIMEOUT" to "Second-$seconds"))
        sendEvent(id, subscription, listener.snapshot(service.id))
    }

    private fun sendEvent(id: String, subscription: Subscription, properties: String) {
        synchronized(subscription) {
            if (!trust.isPaired(subscription.peer) || subscription.expires < android.os.SystemClock.elapsedRealtime()) { subscriptions.remove(id); return }
            try {
                val callback = subscription.callback
                (trust.create(subscription.peer).socketFactory.createSocket() as SSLSocket).use { socket ->
                    sockets.add(socket)
                    try {
                    socket.connect(java.net.InetSocketAddress(callback.host, if (callback.port < 0) 443 else callback.port), 5000)
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    val body = properties.toByteArray(Charsets.UTF_8)
                    val path = callback.rawPath.ifEmpty { "/" } + (callback.rawQuery?.let { "?$it" } ?: "")
                    val header = "NOTIFY $path HTTP/1.1\r\nHOST: ${callback.rawAuthority}\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: ${body.size}\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: $id\r\nSEQ: ${subscription.sequence}\r\nConnection: close\r\n\r\n"
                    socket.outputStream.write(header.toByteArray(Charsets.US_ASCII)); socket.outputStream.write(body); socket.outputStream.flush()
                    require(line(BufferedInputStream(socket.inputStream)).startsWith("HTTP/1.1 200"))
                    subscription.sequence = if (subscription.sequence == 0xffffffffL) 1 else subscription.sequence + 1
                    } finally { sockets.remove(socket) }
                }
            } catch (_: Exception) { subscriptions.remove(id) }
        }
    }

    fun notify(service: MovieServices.Service, properties: String) {
        subscriptions.filterValues { it.service == service }.forEach { (id, subscription) ->
            try { workers.execute { sendEvent(id, subscription, properties) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { subscriptions.remove(id) }
        }
    }

    override fun close() {
        closed = true
        server.close()
        sockets.forEach { it.close() }
        subscriptions.clear()
        acceptor.shutdownNow()
        workers.shutdownNow()
    }
}
