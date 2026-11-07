/* SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.freerdp.freerdpcore.services.LibFreeRDP
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug-only client for the loopback RDPECAM protocol fixture. */
class SensorChannelInstrumentation : Instrumentation() {
    private val applicationCreated = CountDownLatch(1)
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        applicationCreated.countDown()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(applicationCreated.await(30, TimeUnit.SECONDS))
            val fingerprint = requireNotNull(arguments.getString("fingerprint"))
            require(fingerprint.matches(Regex("[0-9A-Fa-f]{64}")))
            val camera = LibFreeRDP.hasCameraRedirectionSupport()
            val uri = Uri.Builder().scheme("freerdp").encodedAuthority("fixture@10.0.2.2:3389")
                .appendPath("connect").appendQueryParameter("sec", "tls")
                .appendQueryParameter("p", "fixture") // Public dummy value; server has no account authentication.
                .appendQueryParameter("cert", "fingerprint:sha256:$fingerprint")
                .appendQueryParameter("size", "640x480")
                .appendQueryParameter("log-level", "DEBUG")
            // Match the production capability gate. The red build still connects without RDPECAM.
            if (camera) uri.appendQueryParameter("dvc", "rdpecam")
            val connected = CountDownLatch(1)
            val states = StringBuffer()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    states.append("state=$code\n")
                    if (code == SessionActivity.CONNECTION_STATE_CONNECTED) connected.countDown()
                }
            }
            activity = startActivitySync(Intent(targetContext, SensorChannelProofActivity::class.java)
                .setData(uri.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SessionActivity.EXTRA_CONNECTION_STATE_RECEIVER, receiver))
            check(connected.await(30, TimeUnit.SECONDS)) { "RDP did not connect: $states" }
            // The server independently validates frame sizes and changing sample bytes.
            SystemClock.sleep(15000)
            result.putString("stream", "RDP_CONNECTED cameraCompiled=$camera\n$states" +
                "PASS: fixture client connected; see server log for camera-frame assertions\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            for (session in GlobalApp.getSessions()) LibFreeRDP.disconnect(session.instance)
            activity?.let { current -> runOnMainSync { if (!current.isFinishing) current.finish() } }
        }
    }
}

/** The fixture explicitly approves the experimental camera channel on its pinned local peer. */
class SensorChannelProofActivity : SessionActivity() {
    private var pose: org.kde.kdeconnect.plugins.remotedesktop.headset.StereoPoseController? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pose = org.kde.kdeconnect.plugins.remotedesktop.headset.StereoPoseController(this,
            intent.data?.host == "10.0.2.2") { status, reason -> android.util.Log.i("PoseProof", "STATE $status reason=$reason") }
    }
    override fun onStereoPosePacket(instance: Long, cookie: Long, event: Int, packet: ByteArray): Int =
        pose?.nativeEvent(instance, cookie, event, packet) ?: 0
    override fun onResume() { super.onResume(); pose?.setResumed(true) }
    override fun onPause() { pose?.setResumed(false); super.onPause() }
    override fun onDestroy() { pose?.close(); pose = null; super.onDestroy() }
    override fun OnExperimentalFeature(feature: Int): Boolean =
        feature == LibFreeRDP.EXPERIMENTAL_CAMERA && intent.data?.host == "10.0.2.2"
}
