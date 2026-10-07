package io.panelassistant.android.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelMediaPlayerTest {
    /** A recording stand-in for one MediaPlayer; the test drives its prepare and end callbacks. */
    private class FakeStream(val url: String, val onPrepared: () -> Unit, val onEnded: () -> Unit) : MediaStream {
        val calls = mutableListOf<String>()
        override var durationMs = 120_000
        override fun start() { calls += "start" }
        override fun pause() { calls += "pause" }
        override fun release() { calls += "release" }
    }

    private class Harness(duration: Int = 120_000) {
        val streams = mutableListOf<FakeStream>()
        val announced = mutableListOf<String>()
        val streamed = mutableListOf<Boolean>()
        var cancelled = 0
        var muted = false
        var changes = 0
        val player = PanelMediaPlayer(
            streams = { url, prepared, ended -> FakeStream(url, prepared, ended).also { it.durationMs = duration; streams += it } },
            post = { it() },
            announce = { url, stream -> announced += url; streamed += stream; true },
            cancelAnnouncement = { cancelled++ },
            muted = { muted },
            setMuted = { muted = it },
        ).also { it.setChangeListener { changes++ } }

        fun state(): String = JSONObject(player.observation()).getString("state")
        fun last() = streams.last()
    }

    private val url = "https://ha.example:8123/api/tts_proxy/abc.mp3?authSig=secret"

    @Test fun playStreamsTheUrlDirectlyAndReportsBufferingThenPlaying() {
        val h = Harness()
        assertEquals("""{"state":"idle","muted":false}""", h.player.observation())
        assertTrue(h.player.command(PanelMediaCommand.Play(url, announce = false)))
        assertEquals(listOf(url), h.streams.map { it.url })
        assertEquals("buffering", h.state())
        h.last().onPrepared()
        assertEquals(listOf("start"), h.last().calls)
        assertEquals("playing", h.state())
        assertTrue("every change re-converges the channel", h.changes >= 2)
        assertTrue("media play never reaches the announcement lane", h.announced.isEmpty())
    }

    @Test fun aNewPlayReplacesTheCurrentStreamAndEndingReturnsToIdle() {
        val h = Harness()
        h.player.command(PanelMediaCommand.Play(url, announce = false))
        val first = h.last()
        first.onPrepared()
        h.player.command(PanelMediaCommand.Play("http://radio.example/live", announce = false))
        assertEquals("release", first.calls.last())
        // A late callback from the replaced stream must not touch the new one.
        first.onEnded()
        assertEquals("buffering", h.state())
        h.last().onPrepared()
        h.last().onEnded()
        assertEquals("idle", h.state())
        assertEquals("release", h.last().calls.last())
    }

    @Test fun announcePlaysOnTheLaneAndPausesMediaUntilItFinishes() {
        val h = Harness()
        h.player.command(PanelMediaCommand.Play(url, announce = false))
        h.last().onPrepared()
        assertTrue(h.player.command(PanelMediaCommand.Play("http://ha/tts.mp3", announce = true)))
        assertEquals(listOf("http://ha/tts.mp3"), h.announced)
        assertEquals("an announcement opens no media stream", 1, h.streams.size)

        h.player.hold(PanelMediaPlayer.Hold.ANNOUNCEMENT, true)
        assertEquals(listOf("start", "pause"), h.last().calls)
        assertEquals("the lane speaking reads as playing", "playing", h.state())
        h.player.hold(PanelMediaPlayer.Hold.ANNOUNCEMENT, false)
        assertEquals(listOf("start", "pause", "start"), h.last().calls)
        assertEquals("playing", h.state())
    }

    @Test fun aLiveStreamIsReopenedFromItsUrlWhenTheHoldEnds() {
        val h = Harness(duration = -1)
        h.player.command(PanelMediaCommand.Play("http://radio.example/live", announce = false))
        val live = h.last()
        live.onPrepared()
        h.player.hold(PanelMediaPlayer.Hold.ANNOUNCEMENT, true)
        h.player.hold(PanelMediaPlayer.Hold.ANNOUNCEMENT, false)
        assertEquals(2, h.streams.size)
        assertEquals("release", live.calls.last())
        assertEquals("http://radio.example/live", h.last().url)
        assertEquals("buffering", h.state())
        h.last().onPrepared()
        assertEquals("playing", h.state())
    }

    @Test fun aVoiceTurnHoldsMediaAndAUserPauseOutlivesTheHold() {
        val h = Harness()
        h.player.command(PanelMediaCommand.Play(url, announce = false))
        h.last().onPrepared()
        h.player.hold(PanelMediaPlayer.Hold.VOICE, true)
        assertEquals("paused", h.state())
        h.player.command(PanelMediaCommand.Pause)
        h.player.hold(PanelMediaPlayer.Hold.VOICE, false)
        assertEquals(listOf("start", "pause"), h.last().calls)
        assertEquals("paused", h.state())
        h.player.command(PanelMediaCommand.Resume)
        assertEquals("start", h.last().calls.last())
        assertEquals("playing", h.state())
    }

    @Test fun mediaPreparedDuringAHoldWaitsForItToEnd() {
        val h = Harness()
        h.player.command(PanelMediaCommand.Play(url, announce = false))
        h.player.hold(PanelMediaPlayer.Hold.ANNOUNCEMENT, true)
        h.last().onPrepared()
        assertTrue(h.last().calls.isEmpty())
        h.player.hold(PanelMediaPlayer.Hold.ANNOUNCEMENT, false)
        assertEquals(listOf("start"), h.last().calls)
    }

    @Test fun stopEndsMediaAndTheCurrentAnnouncement() {
        val h = Harness()
        h.player.command(PanelMediaCommand.Play(url, announce = false))
        h.last().onPrepared()
        h.player.command(PanelMediaCommand.Stop)
        assertEquals("release", h.last().calls.last())
        assertEquals(1, h.cancelled)
        assertEquals("idle", h.state())
        h.player.command(PanelMediaCommand.Resume)
        assertEquals("a stopped player has nothing to resume", 1, h.streams.size)
    }

    @Test fun muteSetsTheStreamMuteAndReportsIt() {
        val h = Harness()
        val before = h.changes
        h.player.command(PanelMediaCommand.Mute(true))
        assertTrue(h.muted)
        assertTrue(JSONObject(h.player.observation()).getBoolean("muted"))
        assertTrue(h.changes > before)
        h.player.command(PanelMediaCommand.Mute(false))
        assertFalse(h.muted)
    }

    @Test fun aStreamedAnnouncementParsesOnlyWithAnnounceAndReachesTheLaneAsStreamed() {
        fun parse(json: String) = PanelMediaCommand.parse(JSONObject(json))
        val streamed = parse("""{"action":"play","url":"$url","announce":true,"stream":true}""")
        assertEquals(PanelMediaCommand.Play(url, announce = true, stream = true), streamed)
        assertEquals(PanelMediaCommand.Play(url, true, false), parse("""{"action":"play","url":"$url","announce":true,"stream":false}"""))
        assertEquals("stream without announce is plain media", PanelMediaCommand.Play(url, false, false), parse("""{"action":"play","url":"$url","stream":true}"""))
        assertNull(parse("""{"action":"play","url":"$url","announce":true,"stream":"yes"}"""))

        val h = Harness()
        assertTrue(h.player.command(streamed!!))
        assertTrue(h.player.command(PanelMediaCommand.Play(url, announce = true)))
        assertEquals(listOf(true, false), h.streamed)
        assertTrue("a streamed announcement opens no media stream", h.streams.isEmpty())
    }

    @Test fun commandsParseExactlyTheSection18Shapes() {
        fun parse(json: String) = PanelMediaCommand.parse(JSONObject(json))
        assertEquals(PanelMediaCommand.Play(url, true), parse("""{"action":"play","url":"$url","announce":true}"""))
        assertEquals(PanelMediaCommand.Play("http://h/a", false), parse("""{"action":"play","url":"http://h/a","announce":false}"""))
        assertEquals(PanelMediaCommand.Pause, parse("""{"action":"pause"}"""))
        assertEquals(PanelMediaCommand.Resume, parse("""{"action":"resume"}"""))
        assertEquals(PanelMediaCommand.Stop, parse("""{"action":"stop"}"""))
        assertEquals(PanelMediaCommand.Mute(true), parse("""{"action":"mute","muted":true}"""))
        listOf(
            """{"action":"play","url":"/api/tts_proxy/x.mp3","announce":false}""",
            """{"action":"play","url":"file:///sdcard/x.mp3","announce":false}""",
            """{"action":"play","url":"ftp://h/x.mp3","announce":false}""",
            """{"action":"play","url":"http:///nohost","announce":false}""",
            """{"action":"play","url":"http://h/a b","announce":false}""",
            """{"action":"play","url":"http://h/a","announce":"yes"}""",
            """{"action":"play"}""",
            """{"action":"mute"}""",
            """{"action":"mute","muted":"true"}""",
            """{"action":"volume_set","volume":0.5}""",
            """{}""",
        ).forEach { assertNull(it, parse(it)) }
        assertNull(PanelMediaCommand.parse("play"))
    }
}
