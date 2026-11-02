/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.data.AppDatabase
import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.domain.ConnectionReference
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.freerdp.freerdpcore.services.ManualBookmarkGateway
import org.kde.kdeconnect.plugins.remotedesktop.headset.HeadsetActivity
import org.kde.kdeconnect.plugins.remotedesktop.headset.WaitingScenes
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only in a disposable emulator against an explicitly supplied, isolated RDP server. */
class SessionCompatibilityInstrumentation : Instrumentation() {
    private val applicationCreated = CountDownLatch(1)
    private lateinit var arguments: Bundle
    private val proof = StringBuilder()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        applicationCreated.countDown()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(applicationCreated.await(30, TimeUnit.SECONDS))
            check(targetContext.applicationContext is KdeConnect)
            check(targetContext.applicationInfo.minSdkVersion == 24)
            when (arguments.getString("mode")) {
                "parcel" -> checkParcel()
                "save" -> saveBookmark()
                "session" -> checkSession()
                else -> error("mode must be parcel, save or session")
            }
            result.putString("stream", "${proof}PASS: API ${Build.VERSION.SDK_INT}, mode=${arguments.getString("mode")}\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "$proof${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkParcel() {
        // Exercise every boolean, including fields without public setters, in both states.
        for (invert in listOf(false, true)) {
            val original = BookmarkBase().apply { label = "parcel fixture"; hostname = "192.0.2.1"; port = 3390 }
            val originalParts = parts(original)
            var count = 0
            for (part in originalParts) {
                for (field in part.javaClass.declaredFields.sortedBy { it.name }) {
                    if (field.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(field.modifiers)) {
                        field.isAccessible = true
                        field.setBoolean(part, (count++ % 2 == 0) xor invert)
                    }
                }
            }
            check(count == 20) { "Expected 20 parcelled booleans, found $count" }
            val parcel = Parcel.obtain()
            try {
                original.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                parcel.setDataPosition(0)
                val restored = BookmarkBase.CREATOR.createFromParcel(parcel)
                check(parcel.dataAvail() == 0)
                check(restored.label == original.label && restored.hostname == original.hostname && restored.port == original.port)
                for ((before, after) in originalParts.zip(parts(restored))) {
                    for (field in before.javaClass.declaredFields) {
                        if (field.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(field.modifiers)) {
                            field.isAccessible = true
                            check(field.getBoolean(before) == field.getBoolean(after)) { field.name }
                        }
                    }
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                proof.append("parcel invert=$invert flags=$count bytes=${bytes.size} sha256=$digest\n")
            } finally { parcel.recycle() }
        }
    }

    private fun parts(bookmark: BookmarkBase): List<Any> = listOf(bookmark, bookmark.performanceFlags, bookmark.advancedSettings, bookmark.debugSettings)

    private fun gateway() = ManualBookmarkGateway(AppDatabase.getInstance(targetContext).bookmarkDao())
    private fun preferences() = targetContext.getSharedPreferences("session-compatibility-proof", Context.MODE_PRIVATE)

    private fun saveBookmark() {
        val host = requireNotNull(arguments.getString("server"))
        val bookmark = BookmarkBase().apply {
            label = "Isolated RDP session proof"
            hostname = host
            username = "fixture"
            password = "fixture" // Public dummy values; the isolated server does not authenticate.
            debugSettings.debugLevel = "DEBUG"
            port = 3389
            screenSettings.setResolution("custom", 2400, 1080)
            screenSettings.colors = 32
            advancedSettings.security = 1 // Explicit RDP security for this isolated, unauthenticated fixture.
            advancedSettings.redirectSound = 2
            advancedSettings.redirectMicrophone = false
            advancedSettings.redirectCamera = false
            advancedSettings.redirectPrinter = false
            advancedSettings.redirectSDCard = false
        }
        val store = gateway()
        val id = store.insert(bookmark)
        check(id > 0)
        check(store.findById(id).hostname == host)
        check(preferences().edit().putLong("bookmark", id).putString("server", host).commit())
        check(targetContext.getSharedPreferences("headset-fit", Context.MODE_PRIVATE).edit()
            .putBoolean(WaitingScenes.REDUCE_MOTION, true).putBoolean("hevc", false).commit())
        proof.append("bookmark saved id=$id host=$host resolution=2400x1080; process must restart before session\n")
    }

    private fun checkSession() {
        val id = preferences().getLong("bookmark", -1)
        check(id > 0)
        val bookmark = requireNotNull(gateway().findById(id))
        check(bookmark.hostname == preferences().getString("server", ""))
        check(bookmark.screenSettings.width == 2400 && bookmark.screenSettings.height == 1080)
        check(bookmark.advancedSettings.security == 1)
        proof.append("bookmark reloaded after process restart id=$id\n")
        val intent = Intent(targetContext, HeadsetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SessionActivity.PARAM_CONNECTION_REFERENCE, ConnectionReference.getBookmarkReference(id))
            .putExtra(HeadsetActivity.EXTRA_NO_LENSES, true)
            .putExtra("headsetProofDisableWifiLock", true)
        val activity = startActivitySync(intent) as HeadsetActivity
        val samples = HeadsetActivity::class.java.getDeclaredField("proofSamples").apply { isAccessible = true }
        val firstFrame = HeadsetActivity::class.java.getDeclaredField("firstFrame").apply { isAccessible = true }
        val failed = HeadsetActivity::class.java.getDeclaredField("failureResult").apply { isAccessible = true }
        await("three changing RDP markers and a submitted GL frame", 45000) {
            check(failed.get(activity) == null) { "Headset reported a failure" }
            !firstFrame.getBoolean(activity) && samples.getInt(activity) >= 3
        }
        check(GlobalApp.getSessions().size == 1)
        proof.append("real RDP connected, changing paired markers=${samples.getInt(activity)}, GL frame submitted\n")
        val screenshot = requireNotNull(uiAutomation.takeScreenshot())
        File(targetContext.filesDir, "session-proof.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        // This is the production disconnect action used by the session toolbar.
        val disconnect = SessionActivity::class.java.getDeclaredMethod("requestDisconnect").apply { isAccessible = true }
        runOnMainSync { disconnect.invoke(activity) }
        await("activity destroyed and native session removed", 15000) { activity.isDestroyed && GlobalApp.getSessions().isEmpty() }
        proof.append("clean disconnect: activity destroyed, zero registered native sessions\n")
    }

    private fun await(description: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $description" }
            SystemClock.sleep(100)
        }
    }
}
