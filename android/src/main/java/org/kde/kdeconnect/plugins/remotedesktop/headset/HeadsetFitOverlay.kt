/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat

internal fun symmetricEyeMargin(width: Int, leftInset: Int, rightInset: Int): Int =
    maxOf(0, leftInset, rightInset).coerceAtMost((width / 4 - 1).coerceAtLeast(0))

class HeadsetFitOverlay(context: Context) : View(context) {
    private val settings = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            invalidate()
            insets
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDraw(canvas: Canvas) {
        val cutout = ViewCompat.getRootWindowInsets(this)?.displayCutout
        val margin = symmetricEyeMargin(width, cutout?.safeInsetLeft ?: 0, cutout?.safeInsetRight ?: 0)
        if (settings.getBoolean(SYMMETRIC_NOTCH, true) && margin > 0) {
            paint.color = Color.BLACK
            val eyeWidth = width / 2f
            for (eye in 0..1) {
                val left = eye * eyeWidth
                canvas.drawRect(left, 0f, left + margin, height.toFloat(), paint)
                canvas.drawRect(left + eyeWidth - margin, 0f, left + eyeWidth, height.toFloat(), paint)
            }
        }
        if (settings.getBoolean(ALIGNMENT, true)) {
            paint.color = Color.WHITE
            paint.strokeWidth = 2f * resources.displayMetrics.density
            val centre = width / 2f
            val tip = height - 8f * resources.displayMetrics.density
            val arrow = 12f * resources.displayMetrics.density
            canvas.drawLine(centre, height / 2f, centre, tip, paint)
            canvas.drawLine(centre - arrow, tip - arrow, centre, tip, paint)
            canvas.drawLine(centre + arrow, tip - arrow, centre, tip, paint)
        }
    }

    companion object {
        const val PREFERENCES = "headset-fit"
        const val ALIGNMENT = "alignment"
        const val SYMMETRIC_NOTCH = "symmetric-notch"

        fun allowFullPanel(window: Window) {
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }
}
