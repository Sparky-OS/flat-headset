/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.QrCode
import org.json.JSONObject
import org.kde.kdeconnect.plugins.remotedesktop.RemoteDesktopPlugin
import org.kde.kdeconnect.plugins.remotedesktop.buildFreeRdpUri
import org.kde.kdeconnect_tp.R
import java.util.UUID
import java.util.concurrent.Executors

class StandaloneHeadsetActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var control: PanelControlServer? = null
    private var announcement: PanelAnnouncement? = null
    @Volatile private var active = false
    private var viewing = false
    private var waitingScene: WaitingSceneView? = null
    private var reconnecting = false
    private var announcedPanel: JSONObject? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var directWifi: HeadsetDirectWifi? = null
    @Volatile private var sharingGeneration = 0
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var linkFormat: Spinner
    private lateinit var transport: Spinner
    private lateinit var correction: Spinner
    private val noLenses: Boolean get() = correction.selectedItemPosition != FittedLensCorrection.QR_PROFILE
    private lateinit var scanViewer: Button
    private lateinit var viewerProfile: TextView
    private val directPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSharing() else status.setText(R.string.headset_direct_permission)
    }
    private val viewer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewing = false
        if (result.resultCode == Activity.RESULT_OK) stopSharing()
        else if (active) { reconnecting = true; status.setText(R.string.headset_waiting); showWaiting() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.headset_title)
        status = TextView(this).apply { setText(R.string.headset_discovery_description) }
        val fit = getSharedPreferences(HeadsetFitOverlay.PREFERENCES, MODE_PRIVATE)
        transport = Spinner(this).apply {
            adapter = ArrayAdapter(this@StandaloneHeadsetActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.headset_transport_network), getString(R.string.headset_transport_direct)))
        }
        correction = Spinner(this).apply {
            adapter = ArrayAdapter(this@StandaloneHeadsetActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.headset_correction_off), getString(R.string.headset_correction_fitted),
                    getString(R.string.headset_correction_profile)))
            setSelection(fit.getInt(FittedLensCorrection.MODE, 0).coerceIn(0, 2))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    fit.edit().putInt(FittedLensCorrection.MODE, position).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        linkFormat = Spinner(this).apply {
            adapter = ArrayAdapter(this@StandaloneHeadsetActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.headset_link_native), getString(R.string.headset_link_sbs720),
                    getString(R.string.headset_link_tb720), getString(R.string.headset_link_halfsbs720), getString(R.string.headset_link_fulltb720), getString(R.string.headset_link_automatic)))
            setSelection(HeadsetLink.choices.indexOf(fit.getString(HeadsetLink.PREFERENCE, HeadsetLink.DEFAULT)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    fit.edit().putString(HeadsetLink.PREFERENCE, HeadsetLink.choices[position]).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        start = Button(this).apply { setText(R.string.headset_wait_for_computer); setOnClickListener { startSharing() } }
        viewerProfile = TextView(this)
        scanViewer = Button(this).apply {
            setText(R.string.headset_scan_viewer)
            setOnClickListener {
                Initialize.initialize(this@StandaloneHeadsetActivity)
                QrCode.scanQrCodeAndSaveDeviceParams()
            }
        }
        val movie = Button(this).apply {
            setText(R.string.headset_movie_mode)
            setOnClickListener {
                stopSharing()
                worker.execute { runOnUiThread {
                    startActivity(Intent(this@StandaloneHeadsetActivity, MovieActivity::class.java).apply {
                        putExtra(HeadsetActivity.EXTRA_NO_LENSES, noLenses)
                    })
                } }
            }
        }
        val stop = Button(this).apply { setText(R.string.headset_stop); setOnClickListener { stopSharing() } }
        fun fitSwitch(label: Int, key: String) = Switch(this).apply {
            setText(label)
            isChecked = fit.getBoolean(key, true)
            setOnCheckedChangeListener { _, enabled -> fit.edit().putBoolean(key, enabled).apply() }
        }
        val controls = ScrollView(this).apply {
            addView(LinearLayout(this@StandaloneHeadsetActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(status)
                addView(TextView(this@StandaloneHeadsetActivity).apply { setText(R.string.headset_transport_title) })
                addView(transport)
                addView(TextView(this@StandaloneHeadsetActivity).apply { setText(R.string.headset_link_title) })
                addView(linkFormat)
                addView(fitSwitch(R.string.headset_show_link_status, HeadsetNetworkOverlay.SHOW_STATUS))
                addView(TextView(this@StandaloneHeadsetActivity).apply { setText(R.string.headset_link_hint) })
                addView(TextView(this@StandaloneHeadsetActivity).apply { setText(R.string.headset_correction_title) })
                addView(correction)
                addView(TextView(this@StandaloneHeadsetActivity).apply { setText(R.string.headset_correction_hint) })
                addView(viewerProfile); addView(scanViewer)
                addView(TextView(this@StandaloneHeadsetActivity).apply { setText(R.string.headset_notch_warning) })
                addView(fitSwitch(R.string.headset_symmetric_notch, HeadsetFitOverlay.SYMMETRIC_NOTCH))
                addView(fitSwitch(R.string.headset_alignment, HeadsetFitOverlay.ALIGNMENT))
                addView(Switch(this@StandaloneHeadsetActivity).apply {
                    setText(R.string.headset_hevc)
                    isChecked = fit.getBoolean("hevc", false)
                    setOnCheckedChangeListener { _, enabled -> fit.edit().putBoolean("hevc", enabled).apply() }
                })
                addView(Switch(this@StandaloneHeadsetActivity).apply {
                    setText(R.string.headset_wait_reduce_motion)
                    isChecked = fit.getBoolean(WaitingScenes.REDUCE_MOTION, false)
                    setOnCheckedChangeListener { _, enabled -> fit.edit().putBoolean(WaitingScenes.REDUCE_MOTION, enabled).apply() }
                })
                addView(start); addView(stop); addView(movie)
            })
        }
        setContentView(FrameLayout(this).apply { addView(controls) })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (active) stopSharing() else finish() }
        })
        if (Build.VERSION.SDK_INT < 24) { start.isEnabled = false; status.setText(R.string.headset_upnp_android_version) }
        else {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshAnnouncement()
                override fun onLost(network: Network) = refreshAnnouncement()
            }.also { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        updateWaitingBars()
        waitingScene?.setResumed(true)
        Initialize.initialize(this)
        viewerProfile.setText(if (QrCode.getSavedDeviceParams() == null) R.string.headset_profile_default else R.string.headset_profile_saved)
    }

    override fun onPause() { waitingScene?.setResumed(false); super.onPause() }

    private fun updateWaitingBars() {
        if (active) supportActionBar?.hide() else supportActionBar?.show()
        WindowCompat.setDecorFitsSystemWindows(window, !active)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (active) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateWaitingBars()
    }

    private fun showWaiting() {
        updateWaitingBars()
        waitingScene?.close()
        val content = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        waitingScene?.let { content.removeView(it) }
        waitingScene = WaitingSceneView(this, noLenses).also {
            content.addView(it, FrameLayout.LayoutParams(-1, -1))
            it.setWaiting(true, reconnecting)
            it.setResumed(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        }
    }

    private fun refreshAnnouncement() {
        if (worker.isShutdown || directWifi != null) return
        val generation = sharingGeneration
        worker.execute {
            if (!active || generation != sharingGeneration || directWifi != null) return@execute
            val panel = announcedPanel ?: return@execute
            val server = control ?: return@execute
            announcement?.close()
            announcement = null
            try { announcement = PanelAnnouncement(this, panel, server.port) }
            catch (_: Exception) { runOnUiThread { if (active) status.setText(R.string.headset_waiting) } }
        }
    }

    private fun startSharing() {
        if (active) return
        val direct = transport.selectedItemPosition == 1
        if (direct && ContextCompat.checkSelfPermission(this, HeadsetDirectWifi.permission()) != PackageManager.PERMISSION_GRANTED) {
            directPermission.launch(HeadsetDirectWifi.permission())
            return
        }
        active = true
        val generation = ++sharingGeneration
        reconnecting = false
        if (!direct) showWaiting()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        start.isEnabled = false
        correction.isEnabled = false
        linkFormat.isEnabled = false
        transport.isEnabled = false
        scanViewer.isEnabled = false
        val panel = PanelDescriptor.measure(this, noLenses, direct)
        if (direct) {
            status.setText(R.string.headset_direct_creating)
            lateinit var session: HeadsetDirectWifi
            session = HeadsetDirectWifi(this, { name ->
                if (active && sharingGeneration == generation && directWifi === session) {
                    beginSharing(panel, generation, name)
                }
            }, { message ->
                if (sharingGeneration == generation) { stopSharing(); status.setText(message) }
            }, waiting = {
                if (active && sharingGeneration == generation) status.setText(R.string.headset_direct_waiting)
            })
            directWifi = session
            session.start()
        } else beginSharing(panel, generation)
    }

    private fun beginSharing(panel: JSONObject, generation: Int, selectedInterface: String? = null) {
        worker.execute {
            if (!active || generation != sharingGeneration) return@execute
            try {
                announcedPanel = panel
                control = PanelControlServer(this, panel, { address, message ->
                    runOnUiThread {
                        if (active && generation == sharingGeneration && !viewing) {
                            viewing = true
                            val connection = RemoteDesktopPlugin.ConnectionInfo(UUID.randomUUID().toString(), address,
                                message.getInt("port"), message.getString("username"), message.getString("password"),
                                message.getString("certificateFingerprint"), "nla", panel)
                            viewer.launch(Intent(this, HeadsetActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                data = buildFreeRdpUri(connection)
                                putExtra("headsetReconnecting", reconnecting)
                                putExtra("headsetDirectWifi", selectedInterface != null)
                                putExtra(HeadsetLink.EXTRA, panel.optString("linkFormat", HeadsetLink.NATIVE))
                                putExtra(HeadsetActivity.EXTRA_NO_LENSES, panel.getString("lensProfile") in listOf("none", FittedLensCorrection.PROFILE))
                            })
                        }
                    }
                }, { runOnUiThread { if (active && generation == sharingGeneration) status.setText(
                    if (selectedInterface != null) R.string.headset_direct_connected else R.string.headset_waiting) } })
                announcement = PanelAnnouncement(this, panel, control!!.port, selectedInterface)
                runOnUiThread { if (active && generation == sharingGeneration) status.setText(
                    if (selectedInterface != null) R.string.headset_direct_connected else R.string.headset_waiting) }
            } catch (_: Exception) {
                runOnUiThread { if (generation == sharingGeneration) { stopSharing(); status.setText(R.string.headset_discovery_failed) } }
            }
        }
    }

    private fun stopSharing() {
        active = false
        sharingGeneration++
        directWifi?.close()
        directWifi = null
        updateWaitingBars()
        waitingScene?.setWaiting(false)
        viewing = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        start.isEnabled = Build.VERSION.SDK_INT >= 24
        correction.isEnabled = true
        linkFormat.isEnabled = true
        transport.isEnabled = true
        scanViewer.isEnabled = true
        status.setText(R.string.headset_discovery_description)
        worker.execute { announcement?.close(); announcement = null; control?.close(); control = null; announcedPanel = null }
    }

    override fun onDestroy() {
        waitingScene?.close()
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        stopSharing()
        worker.shutdown()
        super.onDestroy()
    }
}
