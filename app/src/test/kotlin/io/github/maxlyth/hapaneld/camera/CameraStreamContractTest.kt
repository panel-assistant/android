package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream's standing commitments, read off the sources so they cannot be undone quietly: the trial
 * is video only and the transport stack adds no dependency; the encoder is fed from the paced frame
 * path, never a second capture surface, so the frame-rate ceiling stays a property of the code; the
 * transport is drained before the camera owner; and the service binds the
 * stream port through the owner's switch, never unconditionally.
 */
class CameraStreamContractTest {
    private val cameraDir = TestSources.appDir("src/main/kotlin/io/github/maxlyth/hapaneld/camera")
    private val service by lazy { TestSources.kotlin("PaneldService.kt").readText() }
    private val owner by lazy { TestSources.kotlin("camera/CameraSessionOwner.kt").readText() }
    private val encoder by lazy { TestSources.kotlin("camera/H264Encoder.kt").readText() }

    private fun cameraSources(): List<File> = cameraDir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().sortedBy { it.name }

    @Test fun theCameraPackageNeverTouchesTheMicrophoneOrAnAudioTrack() {
        assertTrue(cameraSources().isNotEmpty())
        cameraSources().forEach { file ->
            val text = file.readText()
            listOf("AudioRecord", "MediaRecorder", "MicrophoneSource", "RECORD_AUDIO", "MIMETYPE_AUDIO", "m=audio").forEach { forbidden ->
                assertFalse("${file.name} mentions $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test fun theTransportIsHandWrittenOnTheAlreadyPinnedStackNotAStreamingLibrary() {
        val build = TestSources.appFile("build.gradle.kts").readText()
        val catalog = TestSources.repoFile("gradle/libs.versions.toml").readText()
        val settings = TestSources.repoFile("settings.gradle.kts").readText()
        listOf(build, catalog).forEach { text ->
            assertFalse(text.contains("pedroSG94"))
            assertFalse(text.contains("RootEncoder", ignoreCase = true))
            assertFalse(text.contains("rtsp", ignoreCase = true))
        }
        assertFalse("no new repository was admitted for the transport", settings.contains("jitpack", ignoreCase = true))
        cameraSources().forEach { file ->
            assertFalse("${file.name} imports a third-party streaming package", Regex("^import com\\.pedro", RegexOption.MULTILINE).containsMatchIn(file.readText()))
        }
    }

    @Test fun theEncoderIsFedFromThePacedFramePathNotASecondCaptureSurface() {
        assertTrue(owner.contains("camera.createCaptureSession(listOf(r.surface)"))
        assertFalse("no encoder input surface joins the capture session", owner.contains("createInputSurface"))
        assertFalse(encoder.contains("createInputSurface()"))
        assertTrue("frames enter the encoder through the owner's paced delivery", owner.contains("attempt.encoder?.takeIf { attempt.encoderPacer?.admit(now) ?: true }"))
        assertTrue(encoder.contains("COLOR_FormatYUV420Flexible"))
    }

    @Test fun theServiceDrainsTheTransportBeforeTheCameraAndTheOwnerAloneTurnsListeningOn() {
        val transportStop = service.indexOf("closeOwner(\"camera stream\") { cameraStream.stop() }")
        val cameraStop = service.indexOf("closeOwnerResult(\"camera\")")
        assertTrue("both drains exist", transportStop > 0 && cameraStop > 0)
        assertTrue("the transport is drained before the camera owner", transportStop < cameraStop)
        assertFalse("listening is the owner's decision, made from the switch", service.contains("cameraStream.setListening"))
        // The switch is read before the capability: the capability may enumerate Android's cameras
        // and this runs from the owner's init while the service constructs on the main thread.
        assertTrue(owner.contains("transport.setListening(enabled() && hasCamera())"))
        assertEquals("the transport only ever learns the camera through the field", 1, Regex("source = \\{ camera \\}").findAll(service).count())
    }

    @Test fun theTransportLearnsNewParameterSetsBeforeAnyStreamWaiterIsWoken() {
        // A DESCRIBE that wakes on a new encoder must find that encoder's SPS/PPS in the transport,
        // never the previous encoder's retained pair; so the publication precedes the wake-up.
        val body = owner.substringAfter("override fun onParameterSets(sets: ParameterSets) {").substringBefore("override fun onAccessUnit(")
        val published = body.indexOf("transport.onParameterSets(sets)")
        val woken = body.indexOf("ready.complete(StreamOutcome.Ready(params))")
        assertTrue("both the publication and the wake-up exist", published >= 0 && woken >= 0)
        assertTrue("the transport learns the sets before the waiters wake", published < woken)
        assertTrue("the transport forgets them when the encoder stops", TestSources.kotlin("camera/CameraRtspServer.kt").readText().substringAfter("override fun onEncoderStopped()").substringBefore("}").contains("sets = null"))
    }

    @Test fun aClientsWriterIsRunningBeforeItsReaderCanFinishARequest() {
        // A graceful end (TEARDOWN, a refusal) lets the writer drain the final response by joining it;
        // a join on a thread not yet started returns at once and the response is lost with the socket.
        // Found by the mutation session's load, where the acceptor was descheduled between the two starts.
        val start = TestSources.kotlin("camera/CameraRtspServer.kt").readText().substringAfter("fun start() {").substringBefore("override fun describe(")
        val writer = start.indexOf("writer.start()")
        val reader = start.indexOf("reader.start()")
        assertTrue("both threads are started", writer >= 0 && reader >= 0)
        assertTrue("the writer is started first", writer < reader)
    }

    @Test fun theEncoderTakesParameterSetsFromTheOutputFormatAsWellAsACodecConfigBuffer() {
        // Android's documented place for the SPS/PPS is the output format's csd-0/csd-1; a codec-config
        // buffer is what some encoders emit instead. Both are accepted; the first to arrive wins.
        val formatChange = encoder.substringAfter("override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {").substringBefore("override fun")
        assertTrue(formatChange.contains("ParameterSets.fromCsd(bytesOf(format, \"csd-0\"), bytesOf(format, \"csd-1\"))"))
        assertTrue("the same sets arriving twice are published once", formatChange.contains("configSeen"))
        assertTrue(encoder.contains("BUFFER_FLAG_CODEC_CONFIG"))
    }

    @Test fun theStreamUrlNeverEntersTheDiagnosticDumpButTheStatusSummaryMayCarryIt() {
        val presentation = TestSources.kotlin("camera/CameraPresentation.kt").readText()
        val diag = presentation.substringAfter("fun diagnosticLine()").substringBefore("companion object")
        assertFalse(diag.contains("rtsp://"))
        assertFalse(diag.contains("summary"))
        assertTrue(owner.contains("\"stream at rtsp://\$address:\${facts.port}\$STREAM_PATH (not for this panel's own dashboard)\""))
    }

    /**
     * The capture session must be given a real capture callback. It used to be passed `null`, which is
     * why a snapshot could only answer with the first frame that arrived: with no callback there is no
     * `CONTROL_AE_STATE` to read, so the session cannot know whether exposure has converged. Reverting
     * that one argument would silently restore the dark-snapshot defect and no behavioural test would
     * notice, because the Android wiring is not reachable from a JVM test.
     */
    @Test fun theCaptureSessionGetsACallbackSoExposureStateIsObservable() {
        val owner = TestSources.kotlin("camera/CameraSessionOwner.kt").readText()
        assertTrue(
            "the repeating request must carry a capture callback, not null",
            owner.contains("s.setRepeatingRequest(request, exposureWatcher(attempt), h)"),
        )
        assertFalse("a null callback is the defect this replaced", owner.contains("setRepeatingRequest(request, null,"))
        listOf("CONTROL_AE_STATE_CONVERGED", "CONTROL_AE_STATE_LOCKED", "CONTROL_AE_STATE_FLASH_REQUIRED").forEach {
            assertTrue("$it must count as settled", owner.contains(it))
        }
    }

    /**
     * The exposure disposition the session records must actually reach the projection. Like the capture
     * callback, this is wiring no JVM test can execute — the owner needs a camera device — so a
     * regression that pinned the projection to the default would leave every unit test green while the
     * panel reported `none` however many snapshots the budget had answered.
     */
    @Test fun theProjectionIsFedTheSessionsOwnExposureDisposition() {
        val owner = TestSources.kotlin("camera/CameraSessionOwner.kt").readText()
        assertTrue(
            "the projection must read the session's recorded disposition",
            owner.contains("snapshotExposure = state.lastSnapshotExposure"),
        )
        assertTrue(
            "and its fallback count",
            owner.contains("snapshotExposureFallbacks = state.snapshotExposureFallbacks"),
        )
    }

    /**
     * The processing choices must actually reach the capture request. Like the exposure callback, this
     * is Android wiring no JVM test can execute: deleting the call would leave every unit test green
     * while the panel silently went back to the pipeline defaults.
     */
    @Test fun theCaptureRequestGetsTheProcessingChoices() {
        val owner = TestSources.kotlin("camera/CameraSessionOwner.kt").readText()
        assertTrue(
            "the request builder must be handed the processing policy",
            owner.contains("applyProcessing(this, characteristics, forStream)"),
        )
        listOf(
            "CaptureRequest.NOISE_REDUCTION_MODE",
            "CaptureRequest.EDGE_MODE",
            "CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION",
        ).forEach { assertTrue("$it must be set from the policy", owner.contains(it)) }
        // The split must follow live stream demand, not whatever opened the session. Deciding it once
        // at open was the defect the first submission carried: a stream joining a snapshot-opened
        // session inherited the expensive pipeline on every frame, and a snapshot on a stream-opened
        // session was stuck with the cheap one.
        assertFalse(
            "the open-time flag is the defect; it must not come back",
            owner.contains("openedForStream"),
        )
        assertTrue(
            "the request must be rebuilt from live stream demand",
            owner.contains("forStream = state.encoderWanted"),
        )
        assertEquals(
            "one definition and both stream-demand transitions: an encoder starting and one stopping",
            3,
            Regex("refreshProcessing\\(").findAll(owner).count(),
        )
    }
}
