/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import org.kde.kdeconnect.plugins.remotedesktop.headset.PanelView

/** Analytic fixture for the disposable Android display rig; absent from release builds. */
class PanelProofActivity : Activity() {
    private lateinit var panel: PanelView

    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        com.google.cardboard.sdk.Initialize.initialize(this)
        when (intent.getStringExtra("qrReference")) {
            "fixture" -> com.google.cardboard.sdk.qrcode.CardboardParamsUtils.writeDeviceParams(
                assets.open("qr-proof/profile.bin").use { it.readBytes() }, this)
            "v1" -> com.google.cardboard.sdk.qrcode.CardboardParamsUtils.writeDeviceParams(
                com.google.cardboard.sdk.QrCode.getCardboardV1DeviceParams(), this)
        }
        com.google.cardboard.sdk.QrCode.getSavedDeviceParams()?.let { params ->
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(params)
                .joinToString("") { "%02x".format(it) }
            android.util.Log.i("PanelProof", "Saved QR profile sha256=$hash bytes=${params.size}")
            val lens = com.google.cardboard.sdk.LensDistortion(params, 2340, 1080)
            for (eye in 0..1) {
                val projection = FloatArray(16)
                lens.getEyeProjectionMatrix(eye, 0.1f, 100f, projection)
                val view = FloatArray(16)
                lens.getEyeFromHeadMatrix(eye, view)
                android.util.Log.i("PanelProof", "QR eye=$eye projection=${projection.contentToString()} view=${view.contentToString()}")
            }
            lens.close()
        }
        panel = PanelView(this, intent.getBooleanExtra("noLenses", true))
        // This static optical fixture has no replacement frames or latency claim.
        if (intent.hasExtra("qrReference")) panel.preferNewestFrames = false
        if (!intent.getBooleanExtra("noLenses", true)) {
            val distortion = com.google.cardboard.sdk.LensDistortion(
                com.google.cardboard.sdk.QrCode.getCardboardV1DeviceParams(), 2400, 1080)
            val mesh = distortion.getDistortionMesh(0)!!
            android.util.Log.i("PanelProof", "SDK mesh: ${mesh.nVertices} vertices, ${mesh.nIndices} indices")
            check(mesh.nVertices == 1600 && mesh.nIndices == 3158) { "Incomplete Cardboard mesh" }
            distortion.close()
        }
        setContentView(panel)
        val stereo = intent.getBooleanExtra("stereo", true)
        val marker = intent.getIntExtra("frameMarker", -1)
        val pixels = IntArray(2400 * 1080) { index ->
            val x = index % 2400
            val y = index / 2400
            Color.rgb(if (marker >= 0 && y < 16) marker * 8 else ((x % 1200) % 32) * 8,
                if (marker >= 0 && y < 16) 0 else (y % 32) * 8, if (stereo && x >= 1200) 224 else 32)
        }
        val frame = Bitmap.createBitmap(pixels, 2400, 1080, Bitmap.Config.ARGB_8888)
        panel.submit(frame)
        frame.recycle()
    }
    override fun onResume() { super.onResume(); panel.onResume() }
    override fun onPause() { panel.onPause(); super.onPause() }
    override fun onDestroy() { panel.release(); super.onDestroy() }
}
