/* SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.kde.kdeconnect_tp.R

/** Head-fixed status and recenter control, repeated identically for the two eyes. */
class PoseStatusView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * resources.displayMetrics.scaledDensity }
    private var source = context.getString(R.string.stereopose_source_game, 60)
    private var text = context.getString(R.string.stereopose_pending)
    fun state(state: Int, reason: Int, sensor: Int = 15, rate: Int = 60) {
        source = context.getString(if (sensor == 11) R.string.stereopose_source_compass else R.string.stereopose_source_game, rate)
        val message = when (reason) {
            1 -> R.string.stereopose_no_sensor
            2 -> R.string.stereopose_version
            3 -> R.string.stereopose_denied
            6 -> R.string.stereopose_pc_missing
            7 -> R.string.stereopose_timing
            8 -> if (state == 3) R.string.stereopose_lost else R.string.stereopose_stale
            9 -> R.string.stereopose_invalid
            10, 12 -> R.string.stereopose_recenter
            11 -> R.string.stereopose_disconnected
            else -> when (state) {
                1 -> R.string.stereopose_active
                2 -> R.string.stereopose_paused
                3, 4 -> R.string.stereopose_unavailable
                5 -> R.string.stereopose_stopped
                else -> R.string.stereopose_pending
            }
        }
        text = context.getString(message)
        contentDescription = "$text. ${context.getString(R.string.stereopose_recenter_action)}"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        for (eye in 0..1) {
            val x = eye * width / 2f + 12 * resources.displayMetrics.density
            paint.color = Color.BLACK; paint.alpha = 190
            canvas.drawRect(eye * width / 2f + 12 * resources.displayMetrics.density, 0f, (eye + 1) * width / 2f - 12 * resources.displayMetrics.density, height.toFloat(), paint)
            paint.color = Color.WHITE; paint.alpha = 255
            canvas.drawText(text, x, 20 * resources.displayMetrics.density, paint)
            canvas.drawText(source, x, 38 * resources.displayMetrics.density, paint)
            canvas.drawText(context.getString(R.string.stereopose_recenter_action), x, 56 * resources.displayMetrics.density, paint)
        }
    }
}
