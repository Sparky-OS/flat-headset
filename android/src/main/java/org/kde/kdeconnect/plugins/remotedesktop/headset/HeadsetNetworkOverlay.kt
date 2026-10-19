/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.kde.kdeconnect_tp.R

/** The same status in both eyes. Does not touch the streamed picture or output geometry. */
internal class HeadsetNetworkOverlay(context: Context, private val format: String, private val directWifi: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val settings = context.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Context.MODE_PRIVATE)
    private var lines = emptyList<String>()
    private val refresh = object : Runnable {
        override fun run() {
            val wifi = if (directWifi) null else HeadsetWifi.read(context)
            val choice = HeadsetLinkPolicy.choose(wifi, HeadsetLinkStats.read(context, directWifi))
            val reason = when (choice.reason) {
                HeadsetLinkPolicy.Reason.UNKNOWN_LINK -> R.string.headset_auto_unknown
                HeadsetLinkPolicy.Reason.PHY_HEADROOM -> R.string.headset_auto_phy
                HeadsetLinkPolicy.Reason.LIMITED_LINK -> R.string.headset_auto_limited
                HeadsetLinkPolicy.Reason.MEASURED_DELAY -> R.string.headset_auto_measured
            }
            lines = listOfNotNull(wifi?.let {
                context.getString(R.string.headset_wifi_status, it.standardName, it.band, it.frequency, it.txMbps.takeIf { speed -> speed > 0 }?.toString() ?: "?", it.rxMbps.takeIf { speed -> speed > 0 }?.toString() ?: "?", it.rssi.takeIf { signal -> signal in -126..0 }?.toString() ?: "?")
            } ?: context.getString(if (directWifi) R.string.headset_direct_status else R.string.headset_wifi_unavailable),
                context.getString(R.string.headset_link_current, context.getString(HeadsetLink.label(format))),
                if (settings.getString(HeadsetLink.PREFERENCE, HeadsetLink.DEFAULT) == HeadsetLink.AUTOMATIC)
                    context.getString(R.string.headset_auto_next, context.getString(HeadsetLink.label(choice.format)), context.getString(reason)) else null)
            invalidate()
            postDelayed(this, 2000)
        }
    }
    fun setActive(active: Boolean) {
        removeCallbacks(refresh)
        visibility = if (active) VISIBLE else GONE
        if (active) post(refresh)
    }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        if (!settings.getBoolean(SHOW_STATUS, true)) return
        paint.textSize = (width / 2f / 70).coerceAtLeast(12f)
        for (eye in 0..1) {
            val centre = width * (eye * .5f + .25f)
            lines.forEachIndexed { index, text ->
                val size = paint.textSize
                val measured = paint.measureText(text)
                if (measured > width * .45f) paint.textSize *= width * .45f / measured
                paint.setShadowLayer(3f, 0f, 0f, Color.BLACK)
                canvas.drawText(text, centre, height * .09f + index * size * 1.5f, paint)
                paint.textSize = size
            }
        }
    }
    companion object { const val SHOW_STATUS = "show-link-status" }
}
