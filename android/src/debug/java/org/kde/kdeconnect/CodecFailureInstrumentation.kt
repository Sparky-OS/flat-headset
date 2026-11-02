/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/** Run with am instrument; screenshots and assertions stay on the disposable emulator. */
class CodecFailureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val prefs = targetContext.getSharedPreferences("headset-fit", 0)
            val previous = prefs.getBoolean("hevc", false)
            prefs.edit().putBoolean("hevc", true).commit()
            try {
                val activity = startActivitySync(Intent(targetContext, CodecFailureProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CodecFailureProofActivity
                waitForIdleSync()
                check(activity.resultCodeFromReceiver == -1)
                fun texts(view: View): List<String> = when (view) {
                    is TextView -> listOf(view.text.toString())
                    is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                    else -> emptyList()
                }
                var eyes = emptyList<String>()
                runOnMainSync { eyes = texts(activity.window.decorView).filter { it.contains("HEVC could not be used") } }
                check(eyes.size == 2 && eyes[0] == eyes[1]) { "Failure must be readable in both eyes: $eyes" }
                check(prefs.getBoolean("hevc", false)) { "Failure changed codec preference" }
                awaitWindow(activity, 2)
                capture("hevc-error-sbs.png", paired = true)
                runOnMainSync { activity.showChoices() }
                waitForIdleSync()
                awaitWindow(activity, 1)
                capture("hevc-choice.png")
                // Select the actual dialog action through its accessibility node.
                val root = uiAutomation.rootInActiveWindow
                val choices = root.findAccessibilityNodeInfosByText("Use H.264")
                check(choices.size == 1) { "Explicit H.264 choice missing" }
                check(choices.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                waitForIdleSync()
                check(!prefs.getBoolean("hevc", true) && activity.choiceMade)
                runOnMainSync { activity.finish() }
                result.putString("stream", "PASS: real HEVC decoder error, fatal result, identical per-eye reason, unchanged preference until explicit H.264 choice\n")
            } finally { prefs.edit().putBoolean("hevc", previous).commit() }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun awaitWindow(activity: Activity, textCount: Int) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var focused = false
            runOnMainSync { focused = activity.hasWindowFocus() }
            val root = uiAutomation.rootInActiveWindow
            if ((focused || textCount == 1) && root?.packageName == targetContext.packageName &&
                root.findAccessibilityNodeInfosByText("HEVC could not be used").size >= textCount) return
            android.os.SystemClock.sleep(50)
        }
        error("Failure window did not become visible")
    }
    private fun capture(name: String, paired: Boolean = false) {
        uiAutomation.waitForIdle(500, 5000)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        if (paired) {
            check(bitmap.width > bitmap.height)
            val half = bitmap.width / 2
            var different = 0
            var white = 0
            var pixels = 0
            for (y in bitmap.height / 8 until bitmap.height * 7 / 8) {
                for (x in 0 until half) {
                    val left = bitmap.getPixel(x, y)
                    if (left != bitmap.getPixel(x + half, y)) different++
                    if (android.graphics.Color.red(left) > 200 && android.graphics.Color.green(left) > 200 &&
                        android.graphics.Color.blue(left) > 200) white++
                    pixels++
                }
            }
            check(white > 50 && different < pixels / 1000) {
                "Capture is not the paired failure screen: white=$white different=$different pixels=$pixels"
            }
        }
        File(targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
