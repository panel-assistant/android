package io.panelassistant.android.shizuku

import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Code loaded by Shizuku into a separate UID-2000 process. Every operation has a fixed executable and
 * typed arguments; this class intentionally cannot evaluate a caller-provided command line.
 */
class ShizukuShellService : IShizukuShellService.Stub() {
    override fun protocolVersion(): Int = ShizukuPolicy.PROTOCOL_VERSION

    override fun identityUid(): Int = Process.myUid()

    override fun captureScreenshot(): ParcelFileDescriptor? {
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        thread(name = "hapaneld-shizuku-screencap", isDaemon = true) {
            pipe[1].use { writeFd ->
                val process = runCatching { ProcessBuilder(SCREENCAP, "-p").start() }.getOrNull()
                    ?: return@thread
                ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                    val pump = thread(name = "hapaneld-shizuku-screencap-output", isDaemon = true) {
                        runCatching { copyBounded(process.inputStream, output, ShizukuPolicy.MAX_SCREENSHOT_BYTES.toLong()) }
                    }
                    if (!process.waitFor(SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    pump.join(1_000)
                }
            }
        }
        return pipe[0]
    }

    override fun inputKey(keyCode: Int): Boolean =
        ShizukuPolicy.validKeyCode(keyCode) && runEffect(INPUT, "keyevent", keyCode.toString())

    override fun inputTap(x: Int, y: Int): Boolean =
        ShizukuPolicy.validCoordinate(x) && ShizukuPolicy.validCoordinate(y) &&
            runEffect(INPUT, "tap", x.toString(), y.toString())

    override fun readDensity(): String? = runText(WM, "density")

    override fun setDensity(dpi: Int): Boolean =
        ShizukuPolicy.validDensity(dpi) && runEffect(WM, "density", dpi.toString())

    override fun resetDensity(): Boolean = runEffect(WM, "density", "reset")

    override fun readFontScale(): String? = runText(SETTINGS, "get", "system", "font_scale")

    override fun setFontScale(scale: Float): Boolean =
        ShizukuPolicy.validFontScale(scale) &&
            runEffect(SETTINGS, "put", "system", "font_scale", scale.toString())

    override fun resetFontScale(): Boolean =
        runEffect(SETTINGS, "delete", "system", "font_scale")

    override fun readRoomClimate(): String? {
        val inventory = readTextBounded(File(PROC_INPUT_DEVICES), MAX_INPUT_INVENTORY_BYTES) ?: return null
        return RoomClimateShellReader.read(inventory) { node ->
            runTextWithTimeout(ROOM_CLIMATE_TIMEOUT_MS, GETEVENT, "-lp", node)
        }
    }

    override fun installApk(
        source: ParcelFileDescriptor?,
        length: Long,
        allowDowngrade: Boolean,
        timeoutMs: Long,
    ): String? {
        if (source == null) return null
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            val deadline = ShizukuPolicy.installServiceDeadline(timeoutMs)
            if (!ShizukuPolicy.validApkLength(length) || deadline == null) return null
            val args = mutableListOf(PM, "install", "-S", length.toString(), "-r")
            if (allowDowngrade) args += "-d"
            val process = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
                ?: return null
            return ShizukuInstallRunner(deadline).run(
                source = input,
                expectedBytes = length,
                process = RuntimeShizukuInstallProcess(process),
            )
        }
    }

    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code == DESTROY_TRANSACTION) {
            thread(name = "hapaneld-shizuku-destroy", isDaemon = true) { exitProcess(0) }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun runEffect(vararg args: String): Boolean =
        runProcess(args.toList())?.first == 0

    private fun runText(vararg args: String): String? =
        runProcess(args.toList())?.takeIf { it.first == 0 }?.second?.trim()

    private fun runTextWithTimeout(timeoutMs: Long, vararg args: String): String? =
        runProcess(args.toList(), timeoutMs)?.takeIf { it.first == 0 }?.second?.trim()

    private fun runProcess(args: List<String>, timeoutMs: Long = SHORT_TIMEOUT_MS): Pair<Int, String>? {
        val process = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            ?: return null
        return ShizukuCommandRunner(MAX_REPLY_BYTES.toLong()).run(
            RuntimeShizukuCommandProcess(process),
            timeoutMs,
        )
    }

    private fun readTextBounded(file: File, max: Long): String? = runCatching {
        val output = ByteArrayOutputStream()
        file.inputStream().use { copyBounded(it, output, max) }
        output.toString(Charsets.UTF_8.name())
    }.getOrNull()

    companion object {
        private const val SYSTEM_BIN = "/system/bin"
        private const val SCREENCAP = "$SYSTEM_BIN/screencap"
        private const val INPUT = "$SYSTEM_BIN/input"
        private const val WM = "$SYSTEM_BIN/wm"
        private const val SETTINGS = "$SYSTEM_BIN/settings"
        private const val PM = "$SYSTEM_BIN/pm"
        private const val GETEVENT = "$SYSTEM_BIN/getevent"
        private const val PROC_INPUT_DEVICES = "/proc/bus/input/devices"
        private const val SHORT_TIMEOUT_MS = 10_000L
        private const val ROOM_CLIMATE_TIMEOUT_MS = 2_000L
        private const val MAX_REPLY_BYTES = 16 * 1024
        private const val MAX_INPUT_INVENTORY_BYTES = 64 * 1024L
        private const val DESTROY_TRANSACTION = 16_777_115
    }
}

private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, max: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        copied += read
        if (copied > max) throw java.io.IOException("stream too large")
        output.write(buffer, 0, read)
    }
}

internal interface ShizukuCommandProcess {
    val output: InputStream
    fun waitFor(timeoutMs: Long): Boolean
    fun exitValue(): Int
    fun closeStreams()
    fun destroyForcibly()
}

internal class ShizukuCommandRunner(
    private val maxReplyBytes: Long,
    private val readerJoinTimeoutMs: Long = 1_000L,
    private val destroyWaitTimeoutMs: Long = 1_000L,
) {
    fun run(process: ShizukuCommandProcess, timeoutMs: Long): Pair<Int, String>? {
        val output = BoundedProcessOutput(process.output, maxReplyBytes)
        return try {
            if (!process.waitFor(timeoutMs)) {
                terminate(process, output)
                null
            } else {
                when (val result = output.await(readerJoinTimeoutMs)) {
                    is BoundedOutputResult.Complete -> process.exitValue() to result.text
                    BoundedOutputResult.Failed, null -> {
                        terminate(process, output)
                        null
                    }
                }
            }
        } catch (_: Exception) {
            terminate(process, output)
            null
        }
    }

    private fun terminate(process: ShizukuCommandProcess, output: BoundedProcessOutput) {
        runCatching { process.closeStreams() }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(destroyWaitTimeoutMs) }
        runCatching { output.await(readerJoinTimeoutMs) }
    }
}

internal sealed interface BoundedOutputResult {
    data class Complete(val text: String) : BoundedOutputResult
    data object Failed : BoundedOutputResult
}

internal class BoundedProcessOutput(input: InputStream, maxBytes: Long) {
    private val result = AtomicReference<BoundedOutputResult?>()
    private val reader = thread(name = "hapaneld-shizuku-output", isDaemon = true) {
        result.set(
            runCatching {
                input.use {
                    val output = ByteArrayOutputStream()
                    copyBounded(it, output, maxBytes)
                    output.toString(Charsets.UTF_8.name())
                }
            }.fold(
                onSuccess = BoundedOutputResult::Complete,
                onFailure = { BoundedOutputResult.Failed },
            ),
        )
    }

    fun await(timeoutMs: Long): BoundedOutputResult? {
        reader.join(timeoutMs)
        return if (reader.isAlive) null else result.get() ?: BoundedOutputResult.Failed
    }
}

private class RuntimeShizukuCommandProcess(
    private val delegate: java.lang.Process,
) : ShizukuCommandProcess {
    override val output: InputStream = delegate.inputStream
    override fun waitFor(timeoutMs: Long): Boolean =
        delegate.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    override fun exitValue(): Int = delegate.exitValue()
    override fun closeStreams() {
        runCatching { delegate.outputStream.close() }
        runCatching { delegate.inputStream.close() }
        runCatching { delegate.errorStream.close() }
    }
    override fun destroyForcibly() {
        delegate.destroyForcibly()
    }
}

private class RuntimeShizukuInstallProcess(
    private val delegate: java.lang.Process,
) : ShizukuInstallProcess {
    override val input = delegate.outputStream
    override val output = delegate.inputStream
    override fun waitFor(timeoutMs: Long): Boolean =
        delegate.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    override fun destroyForcibly() {
        delegate.destroyForcibly()
    }
}
