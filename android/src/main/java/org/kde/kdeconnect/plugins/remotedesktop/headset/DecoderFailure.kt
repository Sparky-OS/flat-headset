/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.media.MediaCodec

/** Keep the decoder's technical reason, including nested Media3 codec exceptions. */
internal object DecoderFailure {
    fun describe(error: Throwable): String {
        val reasons = linkedSetOf<String>()
        var cause: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            reasons.add(cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName)
            if (cause is MediaCodec.CodecException) {
                reasons.add("${cause.diagnosticInfo}; errorCode=${cause.errorCode}; " +
                    "isRecoverable=${cause.isRecoverable}; isTransient=${cause.isTransient}")
            }
            cause = cause.cause
        }
        return reasons.joinToString("\n")
    }
}
