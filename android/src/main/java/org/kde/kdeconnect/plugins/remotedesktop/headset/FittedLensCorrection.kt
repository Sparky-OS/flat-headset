/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.opengl.GLES20

internal object FittedLensCorrection {
    const val MODE = "lens-mode"
    const val FITTED = 1
    const val QR_PROFILE = 2
    const val PROFILE = "shinecon-fit-d"

    fun enabled(context: Context, noLenses: Boolean): Boolean =
        noLenses && context.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Context.MODE_PRIVATE)
            .getInt(MODE, 0) == FITTED

    fun fragment(context: Context): String =
        context.assets.open("headset_fitted.frag").bufferedReader().use { it.readText() }

    fun bind(program: Int, width: Int, height: Int, flipY: Boolean, enabled: Boolean) {
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "panelSize"), width.toFloat(), height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "fittedStrength"), if (enabled) .13f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "flipY"), if (flipY) 1f else 0f)
        val filter = if (enabled) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
    }
}
