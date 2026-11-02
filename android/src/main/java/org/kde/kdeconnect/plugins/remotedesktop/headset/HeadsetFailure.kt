/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.freerdp.freerdpcore.presentation.SessionActivity
import org.kde.kdeconnect_tp.R

/** Failure details travel through the same result as other FreeRDP session errors. */
internal object HeadsetFailure {
    const val CODEC = "headsetCodecFailure"
    const val SCENE = "headsetSceneFailure"
    const val STILL = "headsetStillFailure"

    fun view(context: Context, message: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.BLACK)
        repeat(2) {
            addView(TextView(context).apply {
                text = message + "\n\n" + context.getString(R.string.headset_failure_back)
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(24, 16, 24, 16)
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    fun offer(context: Context, result: Intent?, changed: () -> Unit = {}) {
        val reason = result?.getStringExtra(SessionActivity.EXTRA_CONNECTION_ERROR) ?: return
        val settings = context.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Context.MODE_PRIVATE)
        val dialog = AlertDialog.Builder(context).setMessage(reason)
            .setNegativeButton(android.R.string.cancel, null)
        if (result.getBooleanExtra(CODEC, false)) {
            dialog.setPositiveButton(R.string.headset_use_h264) { _, _ ->
                settings.edit().putBoolean("hevc", false).apply()
                changed()
            }
        } else {
            val scene = result.getStringExtra(SCENE) ?: return
            if (!result.getBooleanExtra(STILL, false)) {
                dialog.setPositiveButton(R.string.headset_use_still) { _, _ ->
                    settings.edit().putString(WaitingScenes.SCENE_CHOICE, scene)
                        .putString(WaitingScenes.STILL_CHOICE, scene).apply()
                    changed()
                }
            }
            dialog.setNeutralButton(R.string.headset_another_scene) { _, _ ->
                settings.edit().remove(WaitingScenes.SCENE_CHOICE).remove(WaitingScenes.STILL_CHOICE).apply()
                changed()
            }
        }
        dialog.show()
    }
}
