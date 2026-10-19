/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package com.freerdp.freerdpcore.presentation

import android.app.Application
import android.view.MotionEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionViewGestureTest {
    private lateinit var context: Application
    private lateinit var view: SessionView
    private lateinit var listener: RecordingListener

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("mousepad_single_tap_key", "left")
            .putBoolean("doubletap_drag_enabled", true)
            .commit()
        listener = RecordingListener()
        view = SessionView(context).apply {
            setKdeTouchpadMode(true)
            setSessionViewListener(listener)
        }
    }

    @Test
    fun oneFingerTapSendsOneAtomicLeftClick() {
        val downTime = 1_000L
        dispatch(downTime, downTime, MotionEvent.ACTION_DOWN)
        dispatch(downTime, downTime + 50, MotionEvent.ACTION_UP)

        assertEquals(listOf(SessionView.SessionViewListener.BUTTON_LEFT), listener.clickButtons)
    }

    @Test
    fun doubleTapWithoutDragSendsExactlyTwoClicks() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("doubletap_drag_enabled", false)
            .commit()
        val downTime = 2_000L
        dispatch(downTime, downTime, MotionEvent.ACTION_DOWN)
        dispatch(downTime, downTime + 40, MotionEvent.ACTION_UP)
        dispatch(downTime + 100, downTime + 100, MotionEvent.ACTION_DOWN)
        dispatch(downTime + 100, downTime + 140, MotionEvent.ACTION_UP)

        assertEquals(
            listOf(
                SessionView.SessionViewListener.BUTTON_LEFT,
                SessionView.SessionViewListener.BUTTON_LEFT
            ),
            listener.clickButtons
        )
    }

    private fun dispatch(downTime: Long, eventTime: Long, action: Int) {
        MotionEvent.obtain(downTime, eventTime, action, 100f, 100f, 0).also {
            view.onTouchEvent(it)
            it.recycle()
        }
    }

    private class RecordingListener : SessionView.SessionViewListener {
        val clickButtons = mutableListOf<Int>()

        override fun onSessionViewBeginTouch() = Unit
        override fun onSessionViewEndTouch() = Unit
        override fun onSessionViewLeftTouch(x: Int, y: Int, down: Boolean) = Unit
        override fun onSessionViewMiddleTouch(x: Int, y: Int, down: Boolean) = Unit
        override fun onSessionViewRightTouch(x: Int, y: Int, down: Boolean) = Unit
        override fun onSessionViewClick(x: Int, y: Int, button: Int) {
            clickButtons += button
        }
        override fun onSessionViewMove(x: Int, y: Int) = Unit
        override fun onSessionViewMouseMove(x: Int, y: Int) = Unit
        override fun onSessionViewScroll(down: Boolean) = Unit
        override fun onSessionViewHScroll(right: Boolean) = Unit
        override fun onSessionViewPan(distanceX: Float, distanceY: Float) = Unit
    }
}
