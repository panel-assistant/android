package io.github.maxlyth.hapaneld.camera

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The transport against a real RTSP client: ffmpeg, which is what Home Assistant's Generic Camera
 * validation (PyAV) and its `stream` component are. A libx264 sample generated on the fly is served in
 * a loop through [CameraRtspServer] exactly as the encoder's output would be, and ffprobe/ffmpeg must
 * identify and decode it — over interleaved TCP when asked, and by falling back to it after our `461`
 * when not asked. Skipped, visibly, where ffmpeg is not installed; the container and CI runners have it.
 */
class RtspFfmpegInteropTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())

    private fun tool(name: String): Boolean = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }

    /** A short libx264 sample as the encoder would hand it over: the parameter sets once, and access units without them. */
    private class Sample(val sets: ParameterSets, val units: List<List<ByteArray>>)

    /**
     * Each access unit begins at a delimiter the encoder was told to emit. libx264 repeats the
     * parameter sets on every IDR; they are stripped from every unit so that, exactly as with the
     * panel's encoder, a client joining mid-stream decodes only because the transport re-sends the
     * sets it captured once ahead of each IDR.
     */
    private fun sample(): Sample {
        val process = ProcessBuilder(
            "ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=15", "-frames:v", "45",
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-pix_fmt", "yuv420p",
            "-x264-params", "keyint=15:min-keyint=15:aud=1:repeat-headers=1:annexb=1", "-f", "h264", "-",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val bytes = process.inputStream.readBytes()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        val units = ArrayList<List<ByteArray>>()
        var current = ArrayList<ByteArray>()
        AnnexB.split(bytes).forEach { nal ->
            if (AnnexB.nalType(nal) == AnnexB.NAL_AUD && current.isNotEmpty()) {
                units += current
                current = ArrayList()
            }
            current += nal
        }
        if (current.isNotEmpty()) units += current
        assertTrue("the sample has frames", units.size >= 30)
        val sets = requireNotNull(ParameterSets.fromNalUnits(units.first())) { "the first IDR carries the sets" }
        val parameterTypes = setOf(AnnexB.NAL_SPS, AnnexB.NAL_PPS)
        return Sample(sets, units.map { unit -> unit.filter { AnnexB.nalType(it) !in parameterTypes } })
    }

    private inner class LoopingSource(private val units: List<List<ByteArray>>, val sets: ParameterSets) : CameraStreamSource {
        @Volatile private var running = true
        private var server: CameraRtspServer? = null
        private val feeder = Thread {
            var pts = 0L
            var index = 0
            while (running) {
                server?.onAccessUnit(units[index], AnnexB.isKeyFrame(units[index]), pts, attempt = 1L)
                index = (index + 1) % units.size
                pts += 1_000_000L / 15
                Thread.sleep(1_000L / 15)
            }
        }

        fun start(server: CameraRtspServer) {
            this.server = server
            server.onParameterSets(sets, attempt = 1L)
            feeder.isDaemon = true
            feeder.start()
        }

        fun stop() {
            running = false
            feeder.join(2_000)
        }

        override fun acquireStream(request: StreamRequest): StreamAdmission =
            StreamAdmission.Granted(AutoCloseable {}, StreamParams(320, 240, 15, 500, "libx264-sample", sets), session = 1L)

        override fun requestKeyFrame() = Unit
    }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(40, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("timed out: ${command.joinToString(" ")}\n$output")
        }
        return process.exitValue() to output
    }

    private fun withServer(block: (url: String) -> Unit) {
        val sample = sample()
        val source = LoopingSource(sample.units, sample.sets)
        val server = CameraRtspServer(port = 0, source = { source })
        server.setListening(true)
        source.start(server)
        try {
            block("rtsp://127.0.0.1:${server.boundPort}/live")
        } finally {
            source.stop()
            server.stop()
        }
    }

    @Test fun ffprobeIdentifiesTheTrackOverInterleavedTcp() {
        assumeTrue("ffmpeg and ffprobe on PATH", tool("ffmpeg") && tool("ffprobe"))
        withServer { url ->
            val (status, output) = run(
                "ffprobe", "-v", "error", "-rtsp_transport", "tcp", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", url,
            )
            assertEquals(output, 0, status)
            assertEquals("h264,320,240", output.trim())
        }
    }

    @Test fun ffmpegDecodesFramesAfterFallingBackFromUdpToTcpOnOurUnsupportedTransport() {
        assumeTrue("ffmpeg on PATH", tool("ffmpeg"))
        withServer { url ->
            // No -rtsp_transport: ffmpeg asks for UDP first, is told 461, and retries over TCP.
            val (status, output) = run("ffmpeg", "-nostdin", "-v", "warning", "-i", url, "-frames:v", "20", "-f", "null", "-")
            assertEquals(output, 0, status)
            assertTrue(output, output.lines().none { it.contains("error", ignoreCase = true) && it.contains("decod", ignoreCase = true) })
        }
    }

    @Test fun theSampleCarriesItsOwnParameterSetsOnceAndNoAccessUnitCarriesThemInline() {
        assumeTrue("ffmpeg on PATH", tool("ffmpeg"))
        val sample = sample()
        assertTrue("libx264 emitted its own SPS", !sample.sets.sps.contentEquals(sps) && !sample.sets.pps.contentEquals(pps))
        assertTrue("the first access unit is an IDR", AnnexB.isKeyFrame(sample.units.first()))
        assertTrue("more than one IDR, so a joining client depends on re-injection", sample.units.count { AnnexB.isKeyFrame(it) } >= 2)
        assertTrue("no unit carries the sets inline", sample.units.none { ParameterSets.fromNalUnits(it) != null })
    }
}
