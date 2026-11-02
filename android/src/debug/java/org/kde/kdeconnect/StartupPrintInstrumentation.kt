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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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
        val events = LinkedBlockingQueue<File>()
        val monitor = PrintJobMonitor { events.add(it) }
        // GlobalApp's own monitor remains active and delivers the production notification.
        val field = GlobalApp::class.java.getDeclaredField("printJobMonitor").apply { isAccessible = true }
        check(field.get(targetContext.applicationContext) is PrintJobMonitor)
        val name = "rdp_print_346e_${SystemClock.elapsedRealtime()}.pdf"
        val ignored = "ignored_346e.pdf"
        shell("mkdir -p /sdcard/Download")
        monitor.startWatching()
        try {
            shell("echo ignored > /sdcard/Download/$ignored")
            check(events.poll(500, TimeUnit.MILLISECONDS) == null) { "Unrelated filename was accepted" }
            check(manager.activeNotifications.isEmpty()) { "Unrelated file produced a notification" }
            shell("echo '%PDF-1.4 proof' > /sdcard/Download/$name")
            check(events.poll(5, TimeUnit.SECONDS)?.absolutePath == "/sdcard/Download/$name") { "CLOSE_WRITE did not reach the print listener" }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (manager.activeNotifications.isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            val notification = manager.activeNotifications.single().notification
            check(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == name)
            check(notification.contentIntent != null)
            check(notification.actions.size == 2)
            if (Build.VERSION.SDK_INT >= 26) check(notification.channelId == "rdp_print")
            check(events.poll(300, TimeUnit.MILLISECONDS) == null) { "Duplicate print callback" }
        } finally {
            monitor.stopWatching()
            manager.cancelAll()
            shell("rm -f /sdcard/Download/$name /sdcard/Download/$ignored")
        }
    }
}
