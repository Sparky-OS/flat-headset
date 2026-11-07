/* SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.services.LibFreeRDP
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deterministic Android adapter proof. These samples are injected, never physical-sensor evidence. */
class PoseEndpointInstrumentation : Instrumentation() {
    private val created = CountDownLatch(1)
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun callApplicationOnCreate(app: Application) { super.callApplicationOnCreate(app); created.countDown() }
    override fun onStart() {
        val result = Bundle()
        var activity: SensorChannelProofActivity? = null
        var code = Activity.RESULT_CANCELED
        try {
            check(created.await(30, TimeUnit.SECONDS))
            val fp = requireNotNull(args.getString("fingerprint"))
            require(fp.matches(Regex("[a-fA-F0-9]{64}")))
            val mount = args.getString("mount", "1").toInt()
            require(mount == 1 || mount == 3)
            val uri = Uri.Builder().scheme("freerdp").encodedAuthority("fixture@10.0.2.2:3389").appendPath("connect")
                .appendQueryParameter("sec", "tls").appendQueryParameter("p", "fixture")
                .appendQueryParameter("cert", "fingerprint:sha256:$fp").appendQueryParameter("size", "640x480")
                .appendQueryParameter("log-level", "DEBUG").build()
            activity = startActivitySync(Intent(targetContext, SensorChannelProofActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("poseInjected", true)) as SensorChannelProofActivity
            val target = activity
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (target.pose?.proofReady() != true && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            check(target.pose?.proofReady() == true) { "No SELECT/source start" }
            val pose = requireNotNull(target.pose)
            fun feed(label: String, q: FloatArray, duration: Long, age: Long = 0) {
                val until = SystemClock.elapsedRealtime() + duration
                while (SystemClock.elapsedRealtime() < until) {
                    val capture = pose.proofRotation(q, age)
                    Log.i("PoseFixture", "sample=$capture case=$label mount=$mount xyzw=${q.joinToString(",")}")
                    SystemClock.sleep(20)
                }
            }
            val h = 0.70710677f
            val neutral = if (mount == 1) floatArrayOf(.5f,.5f,-.5f,.5f) else floatArrayOf(.5f,-.5f,.5f,.5f)
            val cases = if (mount == 1) arrayOf(
                floatArrayOf(0f,h,0f,h),floatArrayOf(h,0f,-h,0f),floatArrayOf(0f,0f,-h,h),
                floatArrayOf(h,h,0f,0f),floatArrayOf(0f,h,-h,0f),floatArrayOf(h,0f,0f,h)
            ) else arrayOf(
                floatArrayOf(0f,-h,0f,h),floatArrayOf(h,0f,h,0f),floatArrayOf(h,-h,0f,0f),
                floatArrayOf(0f,0f,h,h),floatArrayOf(h,0f,0f,h),floatArrayOf(0f,-h,h,0f)
            )
            feed("neutral", neutral, 1500)
            for ((index,q) in cases.withIndex()) feed("axis$index",q,350)
            feed("sign",cases.last().map { -it }.toFloatArray(),350)
            // A slow transport leaves only one in-flight pose and one latest reading.
            Log.i("PoseFixture", "STALL_BEGIN")
            pose.proofWriteDelay(500)
            feed("stalled",neutral,800)
            pose.proofWriteDelay(0)
            feed("recovered",neutral,1000)
            // Old captured samples are rejected before they can refresh the source.
            feed("old",neutral,400,300000000)
            feed("after-old",neutral,600)
            runOnMainSync { callActivityOnPause(target) }
            SystemClock.sleep(400)
            runOnMainSync { callActivityOnResume(target) }
            feed("resume-before-recenter",neutral,400)
            pose.requestRecenter()
            feed("recentered",neutral,1000)
            result.putString("stream", "PASS INJECTED_ANDROID_ADAPTER mount=$mount; server and independent ray checker decide endpoint correctness\n")
            code = Activity.RESULT_OK
        } catch (failure: Throwable) { result.putString("stream",failure.stackTraceToString()) }
        finally {
            for (session in GlobalApp.getSessions()) LibFreeRDP.disconnect(session.instance)
            activity?.let { target -> runOnMainSync { if (!target.isFinishing) target.finish() } }
        }
        finish(code,result)
    }
}
