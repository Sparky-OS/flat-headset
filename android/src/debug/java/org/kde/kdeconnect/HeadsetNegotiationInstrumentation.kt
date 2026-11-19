/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import java.io.File
import java.util.concurrent.TimeUnit

/** Actual Activity handoff and GL submission on a disposable emulated display. */
class HeadsetNegotiationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val prefs = targetContext.getSharedPreferences("headset-fit", 0)
        val previous = prefs.getBoolean("hevc", false)
        var activity: PanelProofActivity? = null
        try {
            prefs.edit().putBoolean("hevc", true).commit()
            activity = startActivitySync(Intent(targetContext, PanelProofActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("negotiationProof", true)) as PanelProofActivity
            check(activity.submitted.await(5, TimeUnit.SECONDS)) { "No GL submission with HEVC selected and no active HEVC session" }
            waitForIdleSync()
            uiAutomation.waitForIdle(500, 5000)
            val focusDeadline = android.os.SystemClock.elapsedRealtime() + 10000
            var focused = false
            while (!focused && android.os.SystemClock.elapsedRealtime() < focusDeadline) {
                runOnMainSync { focused = activity.hasWindowFocus() }
                if (!focused) android.os.SystemClock.sleep(50)
            }
            check(focused) { "Probe window never gained focus" }
            // Wait for SurfaceFlinger to replace the cold-start preview window.
            android.os.SystemClock.sleep(1500)
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "hevc-no-offer-picture.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            var left = 0
            var right = 0
            for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
                for (x in bitmap.width / 8 until bitmap.width * 3 / 8) {
                    if (kotlin.math.abs(Color.blue(bitmap.getPixel(x, y)) - 32) < 16) left++
                    if (kotlin.math.abs(Color.blue(bitmap.getPixel(x + bitmap.width / 2, y)) - 224) < 16) right++
                }
            }
            bitmap.recycle()
            check(left > 1000 && right > 1000) { "Both eye markers must be visible: left=$left right=$right" }
            check(prefs.getBoolean("hevc", false))
            result.putString("stream", "PASS actual callback, GL submission, left-first eye pixels left=$left right=$right, HEVC preference retained\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            prefs.edit().putBoolean("hevc", previous).commit()
        }
    }
}
