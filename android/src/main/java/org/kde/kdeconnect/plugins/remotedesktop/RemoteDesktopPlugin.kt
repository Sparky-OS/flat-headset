/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.remotedesktop

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.kde.kdeconnect.Device
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.BuildConfig
import org.kde.kdeconnect_tp.R
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

@LoadablePlugin
class RemoteDesktopPlugin : Plugin() {
    enum class SessionMode(val wireName: String) {
        MONITOR("monitor"),
        WORKSPACE("workspace"),
        VIRTUAL_MONITOR("virtualMonitor")
    }

    data class Monitor(
        val index: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val scale: Double,
        val primary: Boolean
    )

    data class ConnectionInfo(
        val sessionId: String,
        val host: InetAddress,
        val port: Int,
        val username: String,
        val password: String,
        val certificateFingerprint: String,
        val securityMode: String = SECURITY_TLS,
        val panel: JSONObject? = null
    )

    interface Listener {
        fun onRemoteDesktopStateChanged()
    }

    private val listeners = mutableSetOf<Listener>()
    private val diagnosticEntries = ArrayDeque<String>()
    private val diagnosticLock = Any()

    var remoteDesktopAvailable: Boolean = false
        private set
    var remoteDesktopUnavailableReason: String? = null
        private set
    var monitors: List<Monitor> = emptyList()
        private set
    var sessionModes: Set<String> = emptySet()
        private set
    var status: String = STATUS_IDLE
        private set
    var message: String? = null
        private set
    var currentSessionId: String? = null
        private set

    private var pendingConnection: ConnectionInfo? = null
    private var nativePanelVersion = 0
    private var requestedPanel: JSONObject? = null

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_remote_desktop)

    override val description: String
        get() = context.getString(R.string.pref_plugin_remote_desktop_desc)

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.open_remote_desktop),
            R.drawable.ic_open_in_full_24dp
        ) { parentActivity: Activity ->
            parentActivity.startActivity(Intent(parentActivity, RemoteDesktopActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_ID, device.deviceId)
            })
        }
    )

    override fun onCreate(): Boolean {
        appendBuildDiagnostic()
        appendDiagnostic("Plugin initialized for ${device.name}; LAN=${device.lanHostAddress?.hostAddress ?: "unavailable"}")
        sendCapabilities()
        return true
    }

    override fun onDestroy() {
        appendDiagnostic("Plugin stopped")
        currentSessionId?.let { NotificationManagerCompat.from(context).cancel(notificationId(it)) }
        listeners.clear()
        pendingConnection = null
        super.onDestroy()
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        appendDiagnostic(
            "Received ${np.type.substringAfterLast('.')} action=${np.getString("action", "none")}" +
                np.getString("sessionId").takeIf(String::isNotEmpty)?.let { " session=${shortSessionId(it)}" }.orEmpty()
        )
        return when (np.type) {
            PACKET_TYPE_VIRTUAL_MONITOR -> handleCapabilitiesOrStatus(np)
            PACKET_TYPE_VIRTUAL_MONITOR_REQUEST -> handleConnectionRequest(np)
            else -> false
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun requestCapabilities() {
        appendDiagnostic("Refreshing remote desktop capabilities")
        device.sendPacket(NetworkPacket(PACKET_TYPE_VIRTUAL_MONITOR_REQUEST).apply {
            this["protocolVersion"] = PROTOCOL_VERSION
            this["action"] = ACTION_REQUEST_CAPABILITIES
        })
    }

    fun diagnosticLog(): String = synchronized(diagnosticLock) {
        diagnosticEntries.joinToString(separator = "\n")
    }

    fun clearDiagnosticLog() {
        synchronized(diagnosticLock) {
            diagnosticEntries.clear()
        }
        appendDiagnostic("Diagnostics cleared")
        appendBuildDiagnostic()
        notifyListeners()
    }

    fun recordClientDiagnostic(event: String) {
        appendDiagnostic("FreeRDP: $event")
        notifyListeners()
    }

    fun sendDiagnosticsToDesktop() {
        val host = device.lanHostAddress
        if (host == null) {
            appendDiagnostic("Diagnostics not sent: no authenticated LAN endpoint")
            notifyListeners()
            return
        }

        val entries: List<String> = synchronized(diagnosticLock) {
            diagnosticEntries.toList().takeLast(MAX_DIAGNOSTIC_PACKET_ENTRIES)
        }
        if (entries.isEmpty()) {
            appendDiagnostic("Diagnostics not sent: log is empty")
            notifyListeners()
            return
        }

        val packet = NetworkPacket(PACKET_TYPE_VIRTUAL_MONITOR).apply {
            this["protocolVersion"] = PROTOCOL_VERSION
            this["action"] = ACTION_DIAGNOSTIC
            this["client"] = "android"
            this["clientBuildId"] = BuildConfig.RDP_DIAGNOSTIC_BUILD_ID
            this["clientVersionName"] = BuildConfig.VERSION_NAME
            this["clientVersionCode"] = BuildConfig.VERSION_CODE
            this["clientBuildType"] = BuildConfig.BUILD_TYPE
            this["clientAndroidSdk"] = Build.VERSION.SDK_INT
            this["entries"] = JSONArray(entries)
            currentSessionId?.let { this["sessionId"] = it }
        }
        appendDiagnostic("Sending ${entries.size} diagnostic entries to ${host.hostAddress}")
        device.sendPacket(packet, object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                appendDiagnostic("Diagnostic log sent to the connected computer")
                notifyListeners()
            }

            override fun onFailure(e: Throwable) {
                appendDiagnostic("Diagnostic log could not be sent: ${e.safeDiagnosticMessage()}")
                notifyListeners()
            }
        })
        notifyListeners()
    }

    fun requestSession(
        mode: SessionMode,
        monitorIndex: Int,
        width: Int,
        height: Int,
        scale: Double,
        quality: Int,
        panel: JSONObject? = null
    ) {
        if (currentSessionId != null) {
            appendDiagnostic("Session request ignored because another session is active")
            notifyListeners()
            return
        }
        if (!remoteDesktopAvailable) {
            appendDiagnostic(
                "Session request rejected: " +
                    (remoteDesktopUnavailableReason ?: "remote desktop capability is unavailable")
            )
            status = STATUS_FAILED
            message = remoteDesktopUnavailableReason ?: context.getString(R.string.remote_desktop_unavailable)
            notifyListeners()
            return
        }
        if (device.lanHostAddress == null) {
            appendDiagnostic("Session request rejected: no authenticated LAN endpoint")
            status = STATUS_FAILED
            message = context.getString(R.string.remote_desktop_lan_required)
            notifyListeners()
            return
        }

        if (panel != null && nativePanelVersion != 1) {
            status = STATUS_FAILED
            message = context.getString(R.string.remote_desktop_native_panel_unavailable)
            notifyListeners()
            return
        }
        requestedPanel = panel
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        pendingConnection = null
        status = STATUS_REQUESTING
        message = null
        appendDiagnostic(
            "Requesting session=${shortSessionId(sessionId)} mode=${mode.wireName} monitor=$monitorIndex " +
                "size=${width}x$height scale=$scale quality=$quality host=${device.lanHostAddress?.hostAddress}"
        )

        val request = NetworkPacket(PACKET_TYPE_VIRTUAL_MONITOR_REQUEST).apply {
            this["protocolVersion"] = PROTOCOL_VERSION
            this["action"] = ACTION_REQUEST
            this["sessionId"] = sessionId
            this["mode"] = mode.wireName
            this["monitorIndex"] = monitorIndex
            this["width"] = width.coerceIn(320, 7680)
            this["height"] = height.coerceIn(240, 4320)
            this["scale"] = scale.coerceIn(0.5, 4.0)
            this["quality"] = quality.coerceIn(0, 100)
            if (panel != null) this["panel"] = panel
        }
        device.sendPacket(request, object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                appendDiagnostic("Session request ${shortSessionId(sessionId)} sent over KDE Connect")
                notifyListeners()
            }

            override fun onFailure(e: Throwable) {
                appendDiagnostic("Session request ${shortSessionId(sessionId)} could not be sent: ${e.safeDiagnosticMessage()}")
                notifyListeners()
            }
        })
        notifyListeners()
    }

    fun consumePendingConnection(): ConnectionInfo? {
        val connection = pendingConnection
        pendingConnection = null
        connection?.let {
            NotificationManagerCompat.from(context).cancel(notificationId(it.sessionId))
        }
        return connection
    }

    fun markClientStarted(sessionId: String) {
        if (sessionId != currentSessionId) {
            return
        }
        status = STATUS_CONNECTING
        appendDiagnostic("Launching FreeRDP for session=${shortSessionId(sessionId)}")
        notifyListeners()
    }

    fun markClientConnected(sessionId: String) {
        if (sessionId != currentSessionId) {
            return
        }
		if (status == STATUS_CONNECTED) {
			return
		}
        status = STATUS_CONNECTED
        appendDiagnostic("FreeRDP connected for session=${shortSessionId(sessionId)}")
        device.sendPacket(NetworkPacket(PACKET_TYPE_VIRTUAL_MONITOR_REQUEST).apply {
            this["protocolVersion"] = PROTOCOL_VERSION
            this["action"] = ACTION_CLIENT_CONNECTED
            this["sessionId"] = sessionId
        })
        notifyListeners()
    }

    fun markClientConnectionFailed(
        sessionId: String,
        failureMessage: String = context.getString(R.string.remote_desktop_connection_failed)
    ) {
        if (sessionId != currentSessionId) {
            return
        }
        appendDiagnostic("FreeRDP failed for session=${shortSessionId(sessionId)}: $failureMessage")
        sendStop(sessionId)
        currentSessionId = null
        pendingConnection = null
        status = STATUS_FAILED
        message = failureMessage
        notifyListeners()
    }

    fun markClientDisconnected(sessionId: String) {
        if (sessionId != currentSessionId) {
            return
        }
        appendDiagnostic("FreeRDP disconnected for session=${shortSessionId(sessionId)}")
        sendStop(sessionId)
        currentSessionId = null
        pendingConnection = null
        status = STATUS_IDLE
        message = null
        notifyListeners()
    }

    fun stopSession() {
        val sessionId = currentSessionId ?: return
        appendDiagnostic("Stopping session=${shortSessionId(sessionId)}")
        status = STATUS_STOPPING
        sendStop(sessionId)
        pendingConnection = null
        NotificationManagerCompat.from(context).cancel(notificationId(sessionId))
        notifyListeners()
    }

    fun markClientFinished(sessionId: String) {
        if (sessionId != currentSessionId) {
            return
        }
        appendDiagnostic("FreeRDP activity finished for session=${shortSessionId(sessionId)}")
        stopSession()
        currentSessionId = null
        status = STATUS_IDLE
        message = null
        notifyListeners()
    }

    private fun sendStop(sessionId: String) {
        device.sendPacket(NetworkPacket(PACKET_TYPE_VIRTUAL_MONITOR_REQUEST).apply {
            this["protocolVersion"] = PROTOCOL_VERSION
            this["action"] = ACTION_STOP
            this["sessionId"] = sessionId
        })
    }

    private fun sendCapabilities() {
        if (!isDeviceInitialized) {
            appendDiagnostic("Capabilities not sent because the device is not initialized")
            return
        }

        val metrics = context.resources.displayMetrics
        val resolution = JSONObject().apply {
            put("resolution", "${metrics.widthPixels}x${metrics.heightPixels}")
            put("scale", metrics.density.toDouble())
        }
        val features = JSONObject().apply {
            put("graphicsPipeline", true)
            put("h264", true)
            put("avc444", true)
            put("progressive", true)
            put("nla", true)
            put("tls", true)
            put("tlsFingerprint", true)
        }
        device.sendPacket(NetworkPacket(PACKET_TYPE_VIRTUAL_MONITOR).apply {
            this["protocolVersion"] = PROTOCOL_VERSION
            this["action"] = ACTION_CAPABILITIES
            this["supports_rdp"] = true
            this["supports_virt_mon"] = false
            this["supports_remote_desktop"] = false
            this["resolutions"] = JSONArray().put(resolution)
            this["sessionModes"] = JSONArray()
            this["rdpFeatures"] = features
        })
        appendDiagnostic("Advertised Android RDP client capabilities")
    }

    private fun handleCapabilitiesOrStatus(np: NetworkPacket): Boolean {
        if (np.has("supports_remote_desktop")) {
            remoteDesktopAvailable = np.getBoolean("supports_remote_desktop")
            nativePanelVersion = np.getInt("nativePanelVersion", 0)
            remoteDesktopUnavailableReason = np.getStringOrNull("remoteDesktopUnavailableReason")
                ?.take(MAX_UNAVAILABLE_REASON_LENGTH)
                ?.takeIf(String::isNotBlank)
            if (remoteDesktopAvailable) {
                remoteDesktopUnavailableReason = null
            }
        }

        np.getJSONArray("monitors")?.let { monitors = it.toMonitorList() }
        np.getStringSet("sessionModes")?.let { sessionModes = it }
        if (np.has("supports_remote_desktop")) {
            appendDiagnostic(
                "Desktop capabilities: available=$remoteDesktopAvailable monitors=${monitors.size} " +
                    "modes=${sessionModes.sorted().joinToString()}" +
                    remoteDesktopUnavailableReason?.let { " reason=$it" }.orEmpty()
            )
        }

        if (np.getString("action") == ACTION_STATUS) {
            val sessionId = np.getString("sessionId")
            if (sessionId.isEmpty() || sessionId == currentSessionId) {
                status = np.getString("status", status)
                message = np.getStringOrNull("message")
                appendDiagnostic(
                    "Desktop status=${status}" +
                        sessionId.takeIf(String::isNotEmpty)?.let { " session=${shortSessionId(it)}" }.orEmpty() +
                        message?.let { " message=$it" }.orEmpty()
                )
                if (status == STATUS_STOPPED || status == STATUS_FAILED) {
                    currentSessionId?.let { NotificationManagerCompat.from(context).cancel(notificationId(it)) }
                    pendingConnection = null
                    currentSessionId = null
                }
            }
        }

        notifyListeners()
        return true
    }

    private fun handleConnectionRequest(np: NetworkPacket): Boolean {
        val action = np.getString("action")
        if (action != ACTION_CONNECT && !np.has("password")) {
            return false
        }

        val host = device.lanHostAddress
        val protocolVersion = np.getInt("protocolVersion", 1)
        val receivedSessionId = np.getString("sessionId")
        val sessionId = receivedSessionId.ifEmpty {
            if (protocolVersion >= PROTOCOL_VERSION) "" else currentSessionId ?: UUID.randomUUID().toString()
        }
        val port = np.getInt("port")
        val username = np.getString("username", "kdeconnect")
        val password = np.getString("password")
        val fingerprint = np.getString("certificateFingerprint").lowercase()
        val securityMode = np.getString("security", SECURITY_TLS).lowercase()

        val error = when {
            sessionId.isEmpty() || sessionId.length > MAX_SESSION_ID_LENGTH -> context.getString(R.string.remote_desktop_invalid_session)
            currentSessionId != null && currentSessionId != sessionId -> context.getString(R.string.remote_desktop_session_busy)
            host == null -> context.getString(R.string.remote_desktop_lan_required)
            port !in 1..65535 -> context.getString(R.string.remote_desktop_invalid_endpoint)
            password.isEmpty() -> context.getString(R.string.remote_desktop_invalid_credentials)
            securityMode !in SUPPORTED_SECURITY_MODES -> context.getString(R.string.remote_desktop_invalid_security)
            protocolVersion >= PROTOCOL_VERSION && fingerprint.isEmpty() -> context.getString(R.string.remote_desktop_invalid_certificate)
            fingerprint.isNotEmpty() && !SHA256_FINGERPRINT.matches(fingerprint) -> context.getString(R.string.remote_desktop_invalid_certificate)
            else -> null
        }
        if (error != null || host == null) {
            appendDiagnostic(
                "Rejected RDP endpoint for session=${shortSessionId(sessionId)}: " +
                    (error ?: context.getString(R.string.remote_desktop_invalid_endpoint))
            )
            sendFailure(sessionId, error ?: context.getString(R.string.remote_desktop_invalid_endpoint))
            if (currentSessionId == null || currentSessionId == sessionId) {
                currentSessionId?.let { NotificationManagerCompat.from(context).cancel(notificationId(it)) }
                currentSessionId = null
                pendingConnection = null
                status = STATUS_FAILED
                message = error
            }
            notifyListeners()
            return true
        }

        val confirmedPanel = np.getJSONObject("panel")
        val expectedPanel = requestedPanel
        if (expectedPanel != null && (confirmedPanel == null || expectedPanel.keys().asSequence().any {
                val expected = expectedPanel.opt(it)
                val actual = confirmedPanel.opt(it)
                if (expected is Number && actual is Number) expected.toDouble() != actual.toDouble() else expected != actual
            })) {
            sendFailure(sessionId, context.getString(R.string.remote_desktop_native_panel_unavailable))
            status = STATUS_FAILED
            message = context.getString(R.string.remote_desktop_native_panel_unavailable)
            currentSessionId = null
            pendingConnection = null
            notifyListeners()
            return true
        }
        currentSessionId = sessionId
        pendingConnection = ConnectionInfo(sessionId, host, port, username, password, fingerprint, securityMode, np.getJSONObject("panel"))
        status = STATUS_READY
        message = null
        appendDiagnostic(
            "RDP endpoint ready for session=${shortSessionId(sessionId)} host=${host.hostAddress} port=$port " +
                "security=$securityMode certificatePinned=${fingerprint.isNotEmpty()}"
        )

        val hasListeners = synchronized(listeners) { listeners.isNotEmpty() }
        if (!hasListeners) {
            showConnectionNotification(sessionId)
        }
        notifyListeners()
        return true
    }

    private fun sendFailure(sessionId: String, error: String) {
        device.sendPacket(NetworkPacket(PACKET_TYPE_VIRTUAL_MONITOR).apply {
            this["protocolVersion"] = PROTOCOL_VERSION
            this["action"] = ACTION_STATUS
            this["sessionId"] = sessionId
            this["status"] = STATUS_FAILED
            this["message"] = error
            this["active"] = false
        })
    }

    private fun showConnectionNotification(sessionId: String) {
        val intent = Intent(context, RemoteDesktopActivity::class.java).apply {
            putExtra(EXTRA_DEVICE_ID, device.deviceId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_device_desktop_shortcut)
            .setContentTitle(context.getString(R.string.remote_desktop_request_title, device.name))
            .setContentText(context.getString(R.string.remote_desktop_request_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(sessionId), notification)
        } catch (exception: SecurityException) {
            Log.w(LOG_TAG, "Cannot show remote desktop notification", exception)
        }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach(Listener::onRemoteDesktopStateChanged)
    }

    private fun appendDiagnostic(event: String) {
        val sanitized = event
            .replace(SECRET_QUERY_PARAMETER, "$1<redacted>")
            .replace(CONTROL_CHARACTERS, " ")
            .take(MAX_DIAGNOSTIC_ENTRY_LENGTH)
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        synchronized(diagnosticLock) {
            while (diagnosticEntries.size >= MAX_DIAGNOSTIC_ENTRIES) {
                diagnosticEntries.removeFirst()
            }
            diagnosticEntries.addLast("$timestamp  $sanitized")
        }
        Log.i(LOG_TAG, sanitized)
    }

    private fun appendBuildDiagnostic() {
        appendDiagnostic(
            "Android RDP client build=${BuildConfig.RDP_DIAGNOSTIC_BUILD_ID} " +
                "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "type=${BuildConfig.BUILD_TYPE} sdk=${Build.VERSION.SDK_INT}"
        )
    }

    private fun Throwable.safeDiagnosticMessage(): String =
        localizedMessage?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun shortSessionId(sessionId: String): String = sessionId.take(8).ifEmpty { "missing" }

    private fun JSONArray.toMonitorList(): List<Monitor> = (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        Monitor(
            index = item.optInt("index", index),
            name = item.optString("name", context.getString(R.string.remote_desktop_monitor_number, index + 1)),
            width = item.optInt("width", 0),
            height = item.optInt("height", 0),
            scale = item.optDouble("scale", 1.0),
            primary = item.optBoolean("primary", index == 0)
        )
    }

    private fun notificationId(sessionId: String): Int = sessionId.hashCode()

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_VIRTUAL_MONITOR,
        PACKET_TYPE_VIRTUAL_MONITOR_REQUEST
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_VIRTUAL_MONITOR,
        PACKET_TYPE_VIRTUAL_MONITOR_REQUEST
    )

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val PROTOCOL_VERSION = 2

        const val STATUS_IDLE = "idle"
        const val STATUS_REQUESTING = "requesting"
        const val STATUS_AWAITING_PERMISSION = "awaitingPermission"
        const val STATUS_STARTING = "starting"
        const val STATUS_READY = "ready"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_STOPPING = "stopping"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_FAILED = "failed"

        private const val PACKET_TYPE_VIRTUAL_MONITOR = "kdeconnect.virtualmonitor"
        private const val PACKET_TYPE_VIRTUAL_MONITOR_REQUEST = "kdeconnect.virtualmonitor.request"
        private const val ACTION_CAPABILITIES = "capabilities"
        private const val ACTION_REQUEST_CAPABILITIES = "requestCapabilities"
        private const val ACTION_REQUEST = "request"
        private const val ACTION_CONNECT = "connect"
        private const val ACTION_CLIENT_CONNECTED = "clientConnected"
        private const val ACTION_DIAGNOSTIC = "diagnostic"
        private const val ACTION_STOP = "stop"
        private const val ACTION_STATUS = "status"
        private const val LOG_TAG = "RemoteDesktopPlugin"
        private const val MAX_SESSION_ID_LENGTH = 128
        private const val MAX_DIAGNOSTIC_ENTRIES = 100
        private const val MAX_DIAGNOSTIC_PACKET_ENTRIES = 50
        private const val MAX_DIAGNOSTIC_ENTRY_LENGTH = 500
        private const val MAX_UNAVAILABLE_REASON_LENGTH = 300
        const val SECURITY_TLS = "tls"
        const val SECURITY_NLA = "nla"
        private val SUPPORTED_SECURITY_MODES = setOf(SECURITY_TLS, SECURITY_NLA)
        private val SHA256_FINGERPRINT = Regex("^[0-9a-f]{64}$")
        private val SECRET_QUERY_PARAMETER = Regex("(?i)([?&](?:p|password|token)=)[^&\\s]+")
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    }
}
