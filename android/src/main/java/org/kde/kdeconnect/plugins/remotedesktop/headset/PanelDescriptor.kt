/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import android.util.DisplayMetrics
import org.json.JSONObject
import org.kde.kdeconnect.helpers.security.SslHelper
import java.security.MessageDigest
import kotlin.math.roundToInt

object PanelDescriptor {
    @Suppress("DEPRECATION")
    fun measure(activity: Activity, noLenses: Boolean, directWifi: Boolean = false): JSONObject {
        val display = activity.windowManager.defaultDisplay
        val mode = display.mode
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val width = maxOf(mode.physicalWidth, mode.physicalHeight)
        val height = minOf(mode.physicalWidth, mode.physicalHeight)
        val landscape = metrics.widthPixels >= metrics.heightPixels
        val dpiX = if (landscape) metrics.xdpi else metrics.ydpi
        val dpiY = if (landscape) metrics.ydpi else metrics.xdpi
        val identity = MessageDigest.getInstance("SHA-256").digest(SslHelper.certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        val selected = activity.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Activity.MODE_PRIVATE)
            .getString(HeadsetLink.PREFERENCE, HeadsetLink.DEFAULT)
        val link = if (selected == HeadsetLink.AUTOMATIC)
            HeadsetLinkPolicy.choose(if (directWifi) null else HeadsetWifi.read(activity), HeadsetLinkStats.read(activity, directWifi)).format
        else selected.takeIf { it in HeadsetLink.formats } ?: HeadsetLink.DEFAULT
        return JSONObject().apply {
            put("version", if (link == HeadsetLink.NATIVE) 1 else 2)
            if (link != HeadsetLink.NATIVE) put("linkFormat", link)
            put("identity", identity)
            put("name", "Flat Headset (${android.os.Build.MODEL.filter { !it.isISOControl() }.take(80)})")
            put("width", width)
            put("height", height)
            put("refreshMilliHz", (display.refreshRate * 1000).roundToInt())
            put("scale", 1.0)
            put("widthMm", if (dpiX > 0 && dpiY > 0) (width * 25.4 / dpiX).roundToInt() else 0)
            put("heightMm", if (dpiX > 0 && dpiY > 0) (height * 25.4 / dpiY).roundToInt() else 0)
            put("splitEye", true)
            put("lensProfile", when {
                FittedLensCorrection.enabled(activity, noLenses) -> FittedLensCorrection.PROFILE
                noLenses -> "none"
                else -> "cardboard-default"
            })
        }
    }
}
