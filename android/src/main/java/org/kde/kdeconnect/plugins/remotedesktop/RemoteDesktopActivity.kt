/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.remotedesktop

import org.kde.kdeconnect.plugins.remotedesktop.headset.HeadsetActivity
import org.kde.kdeconnect.plugins.remotedesktop.headset.PanelDescriptor
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.freerdp.freerdpcore.presentation.SessionActivity
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.extensions.viewBinding
import org.kde.kdeconnect.plugins.remotedesktop.RemoteDesktopPlugin.Companion.EXTRA_DEVICE_ID
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityRemoteDesktopBinding

internal fun buildFreeRdpUri(connection: RemoteDesktopPlugin.ConnectionInfo): Uri {
    val rawHost = connection.host.hostAddress.orEmpty()
    val uriHost = if (rawHost.contains(':')) "[${rawHost.replace("%", "%25")}]" else rawHost
    val authority = "${Uri.encode(connection.username)}@$uriHost:${connection.port}"
    val certificatePolicy = if (connection.certificateFingerprint.isEmpty()) {
        "tofu"
    } else {
        // AutoDeny prevents a mismatch from falling back to an interactive
        // certificate callback or a previously selected accept-all policy.
        "deny,fingerprint:sha256:${connection.certificateFingerprint}"
    }
    return Uri.Builder()
        .scheme("freerdp")
        .encodedAuthority(authority)
        .appendPath("connect")
        .appendQueryParameter("p", connection.password)
        .appendQueryParameter("cert", certificatePolicy)
        .appendQueryParameter("sec", connection.securityMode)
        // KRdp implements the RDP graphics pipeline with AVC420. Advertising
        // the unrelated progressive codec makes FreeRDP initialize an extra
        // path which KRdp neither needs nor negotiates.
        .appendQueryParameter("gfx", "AVC420")
        .appendQueryParameter("network", "lan")
        .appendQueryParameter("clipboard", "-")
        .appendQueryParameter("audio-mode", "2")
        .apply {
            connection.panel?.let {
                appendQueryParameter("size", "${it.getInt("width")}x${it.getInt("height")}")
            }
        }
        .build()
}

class RemoteDesktopActivity : BaseActivity<ActivityRemoteDesktopBinding>(), RemoteDesktopPlugin.Listener {
    override val binding: ActivityRemoteDesktopBinding by viewBinding(ActivityRemoteDesktopBinding::inflate)

    private var deviceId: String? = null
    private var launchedSessionId: String? = null
    private var observedDevice: Device? = null
    private var observedPlugin: RemoteDesktopPlugin? = null
    private var started = false
    private val pluginsChangedListener = Device.PluginsChangedListener {
        runOnUiThread { if (started) observePlugin() }
    }

    private val connectionStateReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val sessionId = launchedSessionId ?: return
            when (resultCode) {
                SessionActivity.CONNECTION_STATE_CONNECTED -> plugin()?.markClientConnected(sessionId)
				SessionActivity.CONNECTION_STATE_DISCONNECTED -> plugin()?.markClientDisconnected(sessionId)
                SessionActivity.CONNECTION_STATE_INPUT_DIAGNOSTIC -> {
                    resultData
                        ?.getString(SessionActivity.EXTRA_CONNECTION_STATE_DETAIL)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { plugin()?.recordClientDiagnostic(it) }
                }
                SessionActivity.CONNECTION_STATE_FAILED -> {
                    val detail = resultData
                        ?.getString(SessionActivity.EXTRA_CONNECTION_STATE_DETAIL)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                    plugin()?.recordClientDiagnostic(
                        detail?.let { "Native connection failed: $it" } ?: "Native connection failed"
                    )
                }
            }
        }
    }

    private val rdpSessionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sessionId = launchedSessionId
        launchedSessionId = null
        if (sessionId != null) {
            val currentDeviceId = deviceId
            if (currentDeviceId != null) {
                KdeConnect.getInstance().clearRemoteDesktopSession(currentDeviceId, sessionId)
            }
            if (result.resultCode == Activity.RESULT_CANCELED) {
                val nativeError = result.data
                    ?.getStringExtra(SessionActivity.EXTRA_CONNECTION_ERROR)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                val failureMessage = nativeError?.let {
                    getString(R.string.remote_desktop_connection_failed_details, it)
                } ?: getString(R.string.remote_desktop_connection_failed)
                plugin()?.markClientConnectionFailed(sessionId, failureMessage)
            } else {
                plugin()?.markClientFinished(sessionId)
            }
        }
        updateState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        launchedSessionId = savedInstanceState?.getString(STATE_LAUNCHED_SESSION_ID)
        if (deviceId == null) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.open_remote_desktop)

        binding.modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.remote_desktop_mode_monitor),
                getString(R.string.remote_desktop_mode_workspace),
                getString(R.string.remote_desktop_mode_virtual_monitor),
                getString(R.string.remote_desktop_mode_headset),
                getString(R.string.remote_desktop_mode_headset_no_lenses)
            )
        )
        binding.modeSpinner.setSelection(0)
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateState()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.qualitySlider.addOnChangeListener { _, value, _ ->
            binding.qualityValue.text = getString(R.string.remote_desktop_quality_value, value.toInt())
        }
        binding.qualitySlider.value = DEFAULT_QUALITY.toFloat()

        binding.buttonStart.setOnClickListener { requestSession() }
        binding.buttonStop.setOnClickListener { plugin()?.stopSession() }
        binding.buttonCopyDiagnostics.setOnClickListener { copyDiagnostics() }
        binding.buttonClearDiagnostics.setOnClickListener { plugin()?.clearDiagnosticLog() }
        binding.buttonSendDiagnostics.setOnClickListener { plugin()?.sendDiagnosticsToDesktop() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (launchedSessionId == null) {
                    plugin()?.stopSession()
                }
                finish()
            }
        })
        updateState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        launchedSessionId?.let { outState.putString(STATE_LAUNCHED_SESSION_ID, it) }
    }

    override fun onStart() {
        super.onStart()
        started = true
        observedDevice = deviceId?.let { KdeConnect.getInstance().getDevice(it) }
        observedDevice?.addPluginsChangedListener(pluginsChangedListener)
        observePlugin()
    }

    private fun observePlugin() {
        val current = plugin()
        if (observedPlugin !== current) {
            observedPlugin?.removeListener(this)
            observedPlugin = current
            current?.addListener(this)
            current?.requestCapabilities()
        }
        consumeConnection()
        updateState()
    }

    override fun onStop() {
        started = false
        observedDevice?.removePluginsChangedListener(pluginsChangedListener)
        observedDevice = null
        observedPlugin?.removeListener(this)
        observedPlugin = null
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onRemoteDesktopStateChanged() {
        runOnUiThread {
            consumeConnection()
            updateState()
        }
    }

    private fun requestSession() {
        val plugin = plugin() ?: return
        val metrics = resources.displayMetrics
        val mode = when (binding.modeSpinner.selectedItemPosition) {
            1 -> RemoteDesktopPlugin.SessionMode.WORKSPACE
            2, 3, 4 -> RemoteDesktopPlugin.SessionMode.VIRTUAL_MONITOR
            else -> RemoteDesktopPlugin.SessionMode.MONITOR
        }
        val monitor = plugin.monitors.getOrNull(binding.monitorSpinner.selectedItemPosition)
        plugin.requestSession(
            mode = mode,
            monitorIndex = monitor?.index ?: 0,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            scale = metrics.density.toDouble(),
            quality = binding.qualitySlider.value.toInt(),
            panel = if (binding.modeSpinner.selectedItemPosition >= 3) {
                PanelDescriptor.measure(this, binding.modeSpinner.selectedItemPosition == 4)
            } else null
        )
    }

    private fun consumeConnection() {
        if (launchedSessionId != null) {
            return
        }
        val plugin = plugin() ?: return
        val connection = plugin.consumePendingConnection() ?: return
        val currentDeviceId = deviceId ?: return
        if (!KdeConnect.getInstance().registerRemoteDesktopSession(currentDeviceId, connection.sessionId)) {
            plugin.markClientConnectionFailed(connection.sessionId, getString(R.string.remote_desktop_session_busy))
            return
        }
        launchedSessionId = connection.sessionId
        plugin.markClientStarted(connection.sessionId)

        val uri = buildFreeRdpUri(connection)
        val activity = if (connection.panel != null) HeadsetActivity::class.java else SessionActivity::class.java
        val intent = Intent(this, activity).apply {
            putExtra("headsetLinkFormat", connection.panel?.optString("linkFormat", "native") ?: "native")
            putExtra(HeadsetActivity.EXTRA_NO_LENSES, connection.panel?.optString("lensProfile") in listOf("none", "shinecon-fit-d"))
            action = Intent.ACTION_VIEW
            data = uri
            putExtra(SessionActivity.EXTRA_CONNECTION_STATE_RECEIVER, connectionStateReceiver)
            putExtra(SessionActivity.EXTRA_INPUT_MODE, SessionActivity.INPUT_MODE_KDE_TOUCHPAD)
        }
        try {
            rdpSessionLauncher.launch(intent)
        } catch (exception: RuntimeException) {
            launchedSessionId = null
            KdeConnect.getInstance().clearRemoteDesktopSession(currentDeviceId, connection.sessionId)
            plugin.markClientConnectionFailed(
                connection.sessionId,
                exception.localizedMessage ?: getString(R.string.remote_desktop_client_failed)
            )
        }
    }

    private fun updateState() {
        val plugin = plugin()
        if (plugin == null) {
            binding.statusText.setText(R.string.remote_desktop_unavailable)
            binding.buttonStart.isEnabled = false
            binding.buttonStop.isEnabled = false
            return
        }

        val monitorLabels = plugin.monitors.map { monitor ->
            val primary = if (monitor.primary) getString(R.string.remote_desktop_primary_monitor_suffix) else ""
            "${monitor.name} (${monitor.width}×${monitor.height})$primary"
        }
        val previousSelection = binding.monitorSpinner.selectedItemPosition.coerceAtLeast(0)
        binding.monitorSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            monitorLabels.ifEmpty { listOf(getString(R.string.remote_desktop_no_monitors)) }
        )
        binding.monitorSpinner.setSelection(previousSelection.coerceAtMost(binding.monitorSpinner.count - 1))

        binding.statusText.text = when (plugin.status) {
            RemoteDesktopPlugin.STATUS_REQUESTING -> getString(R.string.remote_desktop_status_requesting)
            RemoteDesktopPlugin.STATUS_AWAITING_PERMISSION -> getString(R.string.remote_desktop_status_awaiting_permission)
            RemoteDesktopPlugin.STATUS_STARTING -> getString(R.string.remote_desktop_status_starting)
            RemoteDesktopPlugin.STATUS_READY -> getString(R.string.remote_desktop_status_ready)
            RemoteDesktopPlugin.STATUS_CONNECTING -> getString(R.string.remote_desktop_status_connecting)
            RemoteDesktopPlugin.STATUS_CONNECTED -> getString(R.string.remote_desktop_status_connected)
            RemoteDesktopPlugin.STATUS_STOPPING -> getString(R.string.remote_desktop_status_stopping)
            RemoteDesktopPlugin.STATUS_FAILED -> plugin.message ?: getString(R.string.remote_desktop_client_failed)
            else -> if (plugin.remoteDesktopAvailable) {
                getString(R.string.remote_desktop_status_available)
            } else {
                plugin.remoteDesktopUnavailableReason ?: getString(R.string.remote_desktop_unavailable)
            }
        }

        val busy = plugin.currentSessionId != null && plugin.status !in setOf(
            RemoteDesktopPlugin.STATUS_IDLE,
            RemoteDesktopPlugin.STATUS_STOPPED,
            RemoteDesktopPlugin.STATUS_FAILED
        )
        binding.buttonStart.isEnabled = plugin.remoteDesktopAvailable && !busy && launchedSessionId == null
        binding.buttonStop.isEnabled = busy || launchedSessionId != null
        binding.monitorLabel.visibility = if (binding.modeSpinner.selectedItemPosition == 0) View.VISIBLE else View.GONE
        binding.monitorSpinner.visibility = binding.monitorLabel.visibility
        binding.diagnosticsText.text = plugin.diagnosticLog().ifEmpty {
            getString(R.string.remote_desktop_diagnostics_empty)
        }
        binding.buttonCopyDiagnostics.isEnabled = plugin.diagnosticLog().isNotEmpty()
        binding.buttonSendDiagnostics.isEnabled = plugin.diagnosticLog().isNotEmpty()
    }

    private fun copyDiagnostics() {
        val diagnostics = plugin()?.diagnosticLog().orEmpty()
        if (diagnostics.isEmpty()) {
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.remote_desktop_diagnostics), diagnostics))
        Toast.makeText(this, R.string.remote_desktop_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun plugin(): RemoteDesktopPlugin? =
        KdeConnect.getInstance().getDevicePlugin(deviceId, RemoteDesktopPlugin::class.java)

    companion object {
        private const val DEFAULT_QUALITY = 70
        private const val STATE_LAUNCHED_SESSION_ID = "launchedSessionId"
    }
}
