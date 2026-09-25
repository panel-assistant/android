package io.github.maxlyth.hapaneld.control

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.platform.RootShell
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller monitor is never held across the seven-day baseline fit or the root backlight write,
 * and a result computed from state that has since been superseded is discarded rather than applied.
 * Every blocking point is a latch the test owns, so nothing here depends on timing except the bound
 * on how long a call that must not wait is allowed to take.
 */
class AutoBrightnessControllerLockTest {
    private val closeables = mutableListOf<() -> Unit>()

    @After fun tearDown() = closeables.asReversed().forEach { it() }

    @Test(timeout = 30_000)
    fun `setHaSourceAvailable returns while a tick is blocked inside su`() {
        val h = harness()
        h.controller.submitPanelLux(LUX)
        assertTrue("the tick reaches the root backlight write", h.root.entered.await(5, TimeUnit.SECONDS))

        returnsPromptly("setHaSourceAvailable(true)") { h.controller.setHaSourceAvailable(true) }
        returnsPromptly("setHaSourceAvailable(false)") { h.controller.setHaSourceAvailable(false) }
        assertEquals("still inside su", 1, h.root.writes.size)
    }

    @Test(timeout = 30_000)
    fun `setHaSourceAvailable never waits for the controller monitor`() {
        val h = harness()
        synchronized(h.controller) {
            returnsPromptly("setHaSourceAvailable(true)") { h.controller.setHaSourceAvailable(true) }
            returnsPromptly("setHaSourceAvailable(false)") { h.controller.setHaSourceAvailable(false) }
        }
    }

    @Test(timeout = 30_000)
    fun `Home Assistant availability hands its evaluation to the controller thread`() {
        val ticked = CountDownLatch(1)
        val h = harness(haEntity = "sensor.room_lux", wallClockMs = { ticked.countDown(); System.currentTimeMillis() })
        h.root.release.countDown()
        h.controller.submitHaLux(LUX)
        assertTrue("a tick reads the unavailable source", ticked.await(5, TimeUnit.SECONDS))
        h.controller.status() // the monitor round trip: that tick has finished with the source unavailable
        assertEquals("an unavailable source is not evaluated", 0, h.root.writes.size)

        h.controller.setHaSourceAvailable(true)

        assertTrue("availability starts an evaluation", h.root.awaitWrites(1))
    }

    @Test(timeout = 30_000)
    fun `a write superseded while inside su is not recorded and current state is re-asserted`() {
        val h = harness()
        h.controller.submitPanelLux(LUX)
        assertTrue(h.root.entered.await(5, TimeUnit.SECONDS))
        val automatic = h.root.writes.single()
        val manual = if (automatic > 128) 20 else 240

        returnsPromptly("noteExternalBrightness") {
            assertTrue(h.controller.noteExternalBrightness(manual, BrightnessPreferenceOrigin.PANEL_CONTROLS))
        }
        h.root.release.countDown()

        assertTrue("current state is re-asserted after the superseded write", h.root.awaitWrites(2))
        val reasserted = h.root.writes[1]
        assertTrue("re-asserted $reasserted tracks the manual level $manual", abs(reasserted - manual) < 4)
        assertEquals("the superseded $automatic is not recorded as applied", reasserted, h.controller.status().appliedTarget)
    }

    @Test(timeout = 30_000)
    fun `a result superseded by a manual level before its write is never written`() {
        val (h, gate) = blockedBeforeWrite()
        returnsPromptly("noteExternalBrightness") {
            assertTrue(h.controller.noteExternalBrightness(240, BrightnessPreferenceOrigin.PANEL_CONTROLS))
        }
        gate.release()

        assertTrue(gate.awaitDone(1))
        assertEquals("the superseded automatic target never reaches su", emptyList<Int>(), h.root.writes.toList())
        assertEquals(240, h.controller.status().appliedTarget)
    }

    @Test(timeout = 30_000)
    fun `a result superseded by resuming full auto before its write is never written`() {
        val (h, gate) = blockedBeforeWrite()
        returnsPromptly("resumeFullAuto") { h.controller.resumeFullAuto() }
        gate.release()

        assertTrue("the resumed evaluation reaches the actuator", gate.awaitDone(2))
        assertEquals("only the resumed evaluation writes", 1, h.root.writes.size)
    }

    @Test(timeout = 30_000)
    fun `a result superseded by a history reset before its write is never written`() {
        val (h, gate) = blockedBeforeWrite()
        returnsPromptly("resetHistory") { h.controller.resetHistory() }
        gate.release()

        assertTrue("the reset evaluation reaches the actuator", gate.awaitDone(2))
        assertEquals("only the reset evaluation writes", 1, h.root.writes.size)
    }

    @Test(timeout = 30_000)
    fun `a result superseded by close before its write is never written`() {
        val (h, gate) = blockedBeforeWrite()
        returnsPromptly("closeAndJoin") { h.controller.closeAndJoin(timeoutMs = 100L) }
        gate.release()

        assertTrue(gate.awaitDone(1))
        assertEquals("a closed controller writes nothing", emptyList<Int>(), h.root.writes.toList())
    }

    @Test(timeout = 30_000)
    fun `an evaluation superseded during its baseline fit is discarded and repeated`() {
        val fit = BlockingFit()
        val gateCalls = AtomicInteger()
        val h = harness(baselineCache = fit.cache, actuationGate = { action -> gateCalls.incrementAndGet(); action(); true })
        h.root.release.countDown()
        h.controller.submitPanelLux(LUX)
        assertTrue("the first fit runs", fit.entered.await(5, TimeUnit.SECONDS))

        returnsPromptly("resetHistory") { h.controller.resetHistory() }
        fit.release.countDown()

        assertTrue("the repeated evaluation writes", h.root.awaitWrites(1))
        assertEquals("the superseded evaluation never reached the actuator", 1, gateCalls.get())
        assertEquals(2, fit.count.get())
        assertEquals(1, h.root.writes.size)
    }

    @Test(timeout = 30_000)
    fun `an evaluation superseded by a manual level during its fit is repeated`() {
        val fit = BlockingFit()
        val h = harness(baselineCache = fit.cache)
        h.root.release.countDown()
        h.controller.submitPanelLux(LUX)
        assertTrue("the first fit runs", fit.entered.await(5, TimeUnit.SECONDS))

        returnsPromptly("noteExternalBrightness") {
            // Nothing is applied yet, so name the prior level or the capture sees no change to record.
            assertTrue(h.controller.noteExternalBrightness(240, BrightnessPreferenceOrigin.PANEL_CONTROLS, priorAppliedLevel = 100))
        }
        fit.release.countDown()

        assertNotNull("the discarded evaluation is repeated", awaitValue { h.controller.status().automaticTarget })
        assertEquals(2, fit.count.get())
    }

    @Test(timeout = 30_000)
    fun `consecutive evaluations reuse one baseline fit`() {
        val fits = AtomicInteger()
        val cache = AdaptiveBaselineCache { now, rows, zone, location, fallback ->
            fits.incrementAndGet()
            AdaptiveAmbientModel.estimate(now, rows, zone, location, fallback)
        }
        val h = harness(baselineCache = cache)
        h.root.release.countDown()
        h.controller.submitPanelLux(LUX)
        assertTrue(h.root.awaitWrites(1))

        h.controller.resumeFullAuto()

        assertTrue("the forced evaluation writes", h.root.awaitWrites(2))
        assertEquals("the second evaluation reused the stored fit", 1, fits.get())
    }

    @Test(timeout = 30_000)
    fun `close does not interrupt a tick inside su`() {
        val h = harness()
        h.controller.submitPanelLux(LUX)
        assertTrue(h.root.entered.await(5, TimeUnit.SECONDS))

        assertFalse("the in-flight write outlives the close bound", h.controller.closeAndJoin(timeoutMs = 200L))
        h.root.release.countDown()

        assertTrue(h.root.exited.await(5, TimeUnit.SECONDS))
        assertFalse("a root command is never interrupted by teardown", h.root.interrupted.get())
    }

    private class Harness(val controller: AutoBrightnessController, val root: BlockingRootShell)

    /** Blocks the first actuation before its action runs; counts completed actuations. */
    private class HeldGate {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val calls = AtomicInteger()
        private val done = Semaphore(0)

        fun actuate(action: () -> Unit): Boolean {
            if (calls.getAndIncrement() == 0) {
                entered.countDown()
                released.await(10, TimeUnit.SECONDS)
            }
            action()
            done.release()
            return true
        }

        fun release() = released.countDown()
        fun awaitDone(count: Int) = done.tryAcquire(count, 5, TimeUnit.SECONDS)
    }

    /** A baseline fit whose first run blocks until [release]. */
    private class BlockingFit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val cache = AdaptiveBaselineCache { now, rows, zone, location, fallback ->
            if (count.getAndIncrement() == 0) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            AdaptiveAmbientModel.estimate(now, rows, zone, location, fallback)
        }
    }

    /** Answers backlight discovery; each backlight write blocks until [release], recording its level. */
    private class BlockingRootShell : RootShell {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val writes = CopyOnWriteArrayList<Int>()
        private val written = Object()

        override fun available() = true
        override fun run(cmd: String): Boolean {
            val level = WRITE.find(cmd)?.groupValues?.get(1)?.toInt() ?: return true
            writes += level
            synchronized(written) { written.notifyAll() }
            entered.countDown()
            try {
                release.await(20, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted.set(true)
            } finally {
                exited.countDown()
            }
            return true
        }
        override fun runOutput(cmd: String): String? = when {
            cmd.contains("ls -d /sys/class/backlight") -> "/sys/class/backlight/panel/"
            cmd.contains("max_brightness") -> "255"
            else -> null
        }
        override fun runBytes(cmd: String): ByteArray? = null
        override fun fireAndForget(cmd: String) = true

        fun awaitWrites(count: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            synchronized(written) {
                while (writes.size < count) {
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (left <= 0) return false
                    written.wait(left)
                }
            }
            return true
        }

        companion object {
            private val WRITE = Regex("""^echo (\d+) > /sys/class/backlight/panel/brightness$""")
        }
    }

    /** A tick that has decided on a write and is held at the actuator, before the write runs. */
    private fun blockedBeforeWrite(): Pair<Harness, HeldGate> {
        val gate = HeldGate()
        val h = harness(actuationGate = gate::actuate)
        h.root.release.countDown()
        h.controller.submitPanelLux(LUX)
        assertTrue("the tick decided on a write", gate.entered.await(5, TimeUnit.SECONDS))
        return h to gate
    }

    private fun harness(
        actuationGate: ((() -> Unit) -> Boolean) = { action -> action(); true },
        baselineCache: AdaptiveBaselineCache = AdaptiveBaselineCache(),
        haEntity: String = "",
        wallClockMs: () -> Long = System::currentTimeMillis,
    ): Harness {
        val files = Files.createTempDirectory("auto-brightness-lock").toFile().also { it.deleteOnExit() }
        val prefs = proxyPreferences()
        val context = FakeContext(files, prefs)
        val config = newConfig(prefs, context.contentResolver).apply {
            setAutoBrightness(true)
            if (haEntity.isNotEmpty()) setAutoBrightnessHaEntity(haEntity)
        }
        val root = BlockingRootShell()
        val clock = { System.nanoTime() / 1_000_000L }
        val controller = AutoBrightnessController(
            context = context,
            brightness = BrightnessController(context, root, FakeDaemon()),
            config = config,
            actuationGate = actuationGate,
            wallClockMs = wallClockMs,
            elapsedRealtimeMs = clock,
            history = AmbientHistoryRuntime(context),
            preference = ManualBrightnessAuthority(
                MemoryPreferenceStore(),
                wallClockMs = System::currentTimeMillis,
                elapsedRealtimeMs = clock,
                bootCount = { 1 },
            ),
            baselineCache = baselineCache,
            canWriteBrightness = { true },
        )
        closeables += {
            root.release.countDown()
            controller.closeAndJoin(timeoutMs = 5_000L)
        }
        return Harness(controller, root)
    }

    private fun returnsPromptly(label: String, call: () -> Unit) {
        val failure = arrayOfNulls<Throwable>(1)
        val caller = Thread { runCatching(call).onFailure { failure[0] = it } }.apply { isDaemon = true; start() }
        caller.join(PROMPT_MS)
        assertFalse("$label waited behind the in-flight tick", caller.isAlive)
        failure[0]?.let { throw it }
    }

    private fun <T : Any> awaitValue(read: () -> T?): T? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    private class MemoryPreferenceStore : ManualBrightnessPreferenceStore {
        @Volatile private var value: ManualBrightnessPreferenceRecord? = null
        override fun load() = value
        override fun save(record: ManualBrightnessPreferenceRecord) { value = record }
        override fun clear() { value = null }
    }

    private class FakeContext(private val files: File, private val prefs: SharedPreferences) : ContextWrapper(null) {
        private val resolver = object : ContentResolver(null) {}
        override fun getApplicationContext(): Context = this
        override fun getContentResolver(): ContentResolver = resolver
        override fun getNoBackupFilesDir(): File = files
        override fun getFilesDir(): File = files
        override fun getDatabasePath(name: String): File = File(files, name)
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "io.github.maxlyth.hapaneld"
    }

    private companion object {
        const val LUX = 50.0
        const val PROMPT_MS = 2_000L

        fun newConfig(prefs: SharedPreferences, resolver: ContentResolver): Config {
            val constructor = Config::class.java.declaredConstructors.single {
                it.parameterTypes.contentEquals(
                    arrayOf(
                        SharedPreferences::class.java,
                        ContentResolver::class.java,
                        SharedPreferences::class.java,
                        SharedPreferences::class.java,
                        Resources::class.java,
                    ),
                )
            }
            constructor.isAccessible = true
            return constructor.newInstance(prefs, resolver, prefs, prefs, null) as Config
        }

        fun proxyPreferences(): SharedPreferences {
            val values = ConcurrentHashMap<String, Any>()
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" ->
                        values[args!![0] as String] ?: args[1]
                    "contains" -> values.containsKey(args!![0] as String)
                    "edit" -> editor(values)
                    else -> null
                }
            } as SharedPreferences
        }

        private fun editor(values: MutableMap<String, Any>): SharedPreferences.Editor =
            Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                        val value = args!![1]
                        if (value == null) values.remove(args[0] as String) else values[args[0] as String] = value
                        proxy
                    }
                    "remove" -> { values.remove(args!![0] as String); proxy }
                    "clear" -> { values.clear(); proxy }
                    "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
    }
}
