/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import java.io.FileNotFoundException
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.google.cardboard.sdk.Initialize
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [WaitingSceneFailureTest.NoCardboard::class])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WaitingSceneFailureTest {
    @Implements(Initialize::class)
    class NoCardboard {
        companion object {
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun initialize(context: Context) {}
        }
    }
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val listener = slot<Player.Listener>()
    private val analytics = slot<AnalyticsListener>()
    private lateinit var context: Context
    private var view: WaitingSceneView? = null

    @Before fun setup() {
        val application = RuntimeEnvironment.getApplication()
        val assets = mockk<AssetManager>()
        val catalogue = """[{"id":"synthetic","connect":"throw.mkv","reconnect":"drop.mkv","still":"rest.png"}]"""
        every { assets.open(any()) } answers {
            val path = firstArg<String>()
            when {
                path.endsWith("catalog.json") -> catalogue.byteInputStream()
                path.contains("invalid") && path.endsWith("rest.png") -> byteArrayOf(0).inputStream()
                path.contains("invalid") -> throw FileNotFoundException(path)
                path.endsWith("rest.png") -> javaClass.getResourceAsStream("/waiting-scene-still.png")!!
                else -> byteArrayOf(0).inputStream()
            }
        }
        // Keep these JVM tests independent of the app manifest and native startup.
        // Device instrumentation exercises the shipped translated resources.
        val resources = object : Resources(application.assets, application.resources.displayMetrics, application.resources.configuration) {
            override fun getString(id: Int): String = "Back to choices"
            override fun getString(id: Int, vararg args: Any): String = args.joinToString(": ")
        }
        context = object : ContextWrapper(application) {
            override fun getAssets(): AssetManager = assets
            override fun getResources(): Resources = resources
        }
        context.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        mockkConstructor(ExoPlayer.Builder::class)
        every { anyConstructed<ExoPlayer.Builder>().build() } returns player
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        every { player.addListener(capture(listener)) } just Runs
        every { player.addAnalyticsListener(capture(analytics)) } just Runs
    }

    @After fun cleanup() { view?.close(); unmockkAll() }

    private fun start(invalid: Boolean = false, still: Boolean = false): WaitingSceneView {
        context.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(WaitingScenes.REDUCE_MOTION, still).commit()
        return WaitingSceneView(context, true, "headset-waiting-proof/" + if (invalid) "invalid" else "valid").also {
            view = it; it.setWaiting(true); it.setResumed(true)
        }
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun explained(reason: String) {
        val root = view!!
        assertFalse("No automatic still replacement", (0 until root.childCount).any { root.getChildAt(it) is PanelView })
        val eyes = texts(root).filter { it.contains(reason) && it.contains("synthetic") }
        assertEquals("The scene and its own reason must be visible in both eyes", 2, eyes.size)
        root.setResumed(false); root.setResumed(true)
        assertEquals("Pause does not discard the failure", eyes, texts(root).filter { it.contains(reason) })
    }

    @Test fun missingMovieIsExplainedWithoutSelectingStill() { start(invalid = true); explained("throw.mkv") }

    @Test fun invalidMovieKeepsTheParserReason() {
        start()
        listener.captured.onPlayerError(PlaybackException("Invalid Matroska header", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        explained("Invalid Matroska header")
    }

    @Test fun rejectedStereoGeometryIsExplained() {
        start()
        analytics.captured.onVideoInputFormatChanged(mockk(relaxed = true), Format.Builder().setWidth(641).setHeight(480).build(), null)
        explained("stereo")
    }

    @Test fun decoderFailureKeepsItsCause() {
        start()
        listener.captured.onPlayerError(PlaybackException("Decoder failed", IllegalStateException("vendor decoder rejected frame"), PlaybackException.ERROR_CODE_DECODING_FAILED))
        explained("vendor decoder rejected frame")
    }

    @Test fun failedStillIsExplainedInsteadOfNeutralStatus() { start(invalid = true, still = true); explained("sTER") }

    @Test fun reduceMotionSelectsStillWithoutStartingMovieDecoder() {
        val root = start(still = true)
        assertTrue((0 until root.childCount).any { root.getChildAt(it) is PanelView })
        verify(exactly = 0) { player.prepare() }
    }
}
