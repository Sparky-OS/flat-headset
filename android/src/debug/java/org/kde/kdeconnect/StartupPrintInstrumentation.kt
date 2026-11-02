/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.PrintJobMonitor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the manifest Application and the real FileObserver in a disposable emulator. */
class StartupPrintInstrumentation : Instrumentation() {
    private var mode = "print"
    private val applicationCreated = CountDownLatch(1)
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "print"
        start()
    }

    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        applicationCreated.countDown()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(applicationCreated.await(30, TimeUnit.SECONDS)) { "Application.onCreate did not finish" }
            check(targetContext.applicationContext is KdeConnect)
            check(KdeConnect.getInstance() === targetContext.applicationContext)
            check(targetContext.applicationInfo.minSdkVersion == 24)
            if (mode != "startup") checkPrint()
            result.putString("stream", "PASS: real KdeConnect Application, API ${Build.VERSION.SDK_INT}, minSdk 24, mode=$mode\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun checkPrint() {
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        // GlobalApp's own monitor remains active and delivers the production notification.
        val field = GlobalApp::class.java.getDeclaredField("printJobMonitor").apply { isAccessible = true }
        check(field.get(targetContext.applicationContext) is PrintJobMonitor)
        val name = "rdp_print_346e_${SystemClock.elapsedRealtime()}.pdf"
        val ignored = "ignored_346e.pdf"
        shell("mkdir -p /sdcard/Download")
        try {
            shell("echo ignored > /sdcard/Download/$ignored")
            SystemClock.sleep(500)
            check(manager.activeNotifications.isEmpty()) { "Unrelated file produced a notification" }
            shell("echo '%PDF-1.4 proof' > /sdcard/Download/$name")
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (manager.activeNotifications.isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            val notifications = manager.activeNotifications
            check(notifications.size == 1) { "CLOSE_WRITE must produce exactly one print notification: ${notifications.size}" }
            val notification = notifications.single().notification
            check(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == name)
            check(notification.contentIntent != null)
            check(notification.actions.size == 2)
            if (Build.VERSION.SDK_INT >= 26) check(notification.channelId == "rdp_print")
            SystemClock.sleep(300)
            check(manager.activeNotifications.size == 1) { "Duplicate print notification" }
        } finally {
            manager.cancelAll()
            shell("rm -f /sdcard/Download/$name /sdcard/Download/$ignored")
        }
    }
}
