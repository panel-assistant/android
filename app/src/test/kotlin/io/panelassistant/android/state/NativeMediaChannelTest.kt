package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.media.MediaStream
import io.panelassistant.android.media.PanelMediaPlayer
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The `media` channel through the real state core and dispatcher: described by profile, commanded natively. */
internal class NativeMediaChannelTest : PanelRuntimeRig() {
    private class Recorded(val url: String, val onPrepared: () -> Unit) : MediaStream {
        override val durationMs = 60_000
        override fun start() {}
        override fun pause() {}
        override fun release() {}
    }

    private val opened = CopyOnWriteArrayList<Recorded>()
    private val announced = CopyOnWriteArrayList<String>()
    private var muted = false
    private fun player() = PanelMediaPlayer(
        streams = { url, prepared, _ -> Recorded(url, prepared).also { opened += it } },
        post = { it() },
        announce = { url, _ -> announced += url; true },
        cancelAnnouncement = {},
        muted = { muted },
        setMuted = { muted = it },
    )

    @Test(timeout = 60_000)
    fun aSpeakerPanelDescribesMediaAndRunsItsCommands() {
        val media = player()
        val rig = rig(media = media)
        val observed = CopyOnWriteArrayList<String>()
        rig.core.addStateSink { channel, observation ->
            if (channel == "media") observed += (observation as StateConverger.Observation.Known).payload
        }
        media.setChangeListener(rig.core::mediaChanged)
        try {
            rig.start()
            val shape = rig.core.nativeChannelShape()
            assertTrue("media is described", "media" in shape.served)
            assertFalse("media is not unsupported", "media" in shape.unsupported)

            val url = "https://ha.example/api/media?authSig=secret"
            val play = JSONObject().put("action", "play").put("url", url).put("announce", false)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "media", play.toString()))
            assertEquals(listOf(url), opened.map { it.url })
            opened.single().onPrepared()
            awaitObserved(observed, """{"state":"playing","muted":false}""")

            val announce = JSONObject().put("action", "play").put("url", "http://ha/tts.mp3").put("announce", true)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "media", announce.toString()))
            assertEquals(listOf("http://ha/tts.mp3"), announced)

            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "media", """{"action":"mute","muted":true}"""))
            awaitObserved(observed, """{"state":"playing","muted":true}""")
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 60_000)
    fun aPanelWithoutASpeakerStatesMediaUnsupported() {
        val rig = rig(media = null)
        try {
            rig.start()
            val shape = rig.core.nativeChannelShape()
            assertTrue("media is unsupported", "media" in shape.unsupported)
            assertFalse("media is not described", "media" in shape.served)
            assertEquals(
                PanelAssistantCommandResult.Refused("unknown_channel"),
                submitNative(rig, "media", """{"action":"stop"}"""),
            )
        } finally {
            rig.close()
        }
    }

    private fun awaitObserved(observed: List<String>, expected: String) {
        val expectedJson = JSONObject(expected)
        val expectedKeys = expectedJson.keys().asSequence().toSet()
        fun received(): Boolean = observed.any { payload ->
            runCatching {
                val actual = JSONObject(payload)
                actual.keys().asSequence().toSet() == expectedKeys &&
                    actual.get("state") == expectedJson.get("state") &&
                    actual.get("muted") == expectedJson.get("muted")
            }.getOrDefault(false)
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!received() && System.nanoTime() < deadline) {
            drainStatePump()
            Thread.sleep(10)
        }
        assertTrue("expected $expected in $observed", received())
    }
}
