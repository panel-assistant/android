package io.panelassistant.android.control

import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.DaemonLongResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The `SCREEN_BRIGHTNESS` observer over a real [ScreenController] on the su `bl_power` route, whose
 * `bl_power` read goes to the root helper. Android delivers the observer on the main looper, so a
 * privileged read there freezes the panel's UI; panels logged main-thread waits of up to 16 s inside
 * `Su.runOutput` when that read still went to su.
 */
class BrightnessSettingObserverTest {
    private val lane = Executors.newSingleThreadExecutor { Thread(it, LANE) }
    private val root = GatedHelper()
    private val backlight = FakeBacklight(level = 120)
    private val screen = ScreenController(backlight, FakeScreenPower(), FakeRootShell(), root, route = ScreenOff.SU_BLPOWER)
    private val journal = BrightnessWriteAttribution()
    private val notes = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
    private var setting: Int? = null

    private val observer = BrightnessSettingObserver(
        handler = null,
        initialLevel = 50,
        readSetting = { setting },
        levelFromSetting = { it },
        consumeOwnedWrite = { journal.consume(it, NOW_MS) },
        observedDark = screen::observedDark,
        noteExternal = { level, prior -> notes += level to prior },
        background = { task -> lane.execute(task) },
    )

    @After fun tearDown() {
        root.gate.countDown()
        lane.shutdownNow()
    }

    @Test fun `a change returns on the delivering thread without a privileged read`() {
        root.blPower = "0"
        val caller = Thread({ change(80) }, "main").apply { isDaemon = true; start() }
        caller.join(PROMPT_MS)

        assertFalse("onChange waited inside the privileged read on the delivering thread", caller.isAlive)
        assertTrue("the delivering thread entered the privileged read", "main" !in root.readers)
        assertTrue("nothing is attributed before the darkness check", notes.isEmpty())

        root.gate.countDown()
        drain()
        assertEquals(listOf(LANE), root.readers)
        assertEquals(listOf(80 to 50), notes)
    }

    @Test fun `owned writes and a dark screen are not attributed, and later changes keep their order`() {
        root.gate.countDown()
        root.blPower = "0"

        journal.record(90, NOW_MS)
        change(90)            // ha-paneld's own write: consumed at delivery, no privileged read
        drain()
        assertTrue(root.readers.isEmpty())

        root.blPower = "4"
        change(10)            // the backlight is off: not a preference
        drain()

        root.blPower = "0"
        change(140)
        change(200)
        drain()

        assertEquals(listOf(140 to 10, 200 to 140), notes)
    }

    private fun change(value: Int) {
        setting = value
        observer.onChange(false)
    }

    private fun drain() {
        lane.submit {}.get(5, TimeUnit.SECONDS)
    }

    /** A root helper whose `BLPOWER` read waits for [gate], as it does behind other helper work, and
     *  names its caller. */
    private class GatedHelper : Daemon {
        val gate = CountDownLatch(1)
        val readers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var blPower = "0"
        override fun available() = true
        override fun send(cmd: String): String? {
            readers += Thread.currentThread().name
            gate.await(10, TimeUnit.SECONDS)
            return if (cmd == "BLPOWER") blPower else null
        }
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult =
            send(cmd)?.let(DaemonLongResult::Reply) ?: DaemonLongResult.NotSubmitted
        override fun sendBytes(cmd: String): ByteArray? = null
    }

    private companion object {
        const val LANE = "observer-lane"
        const val PROMPT_MS = 2_000L
        const val NOW_MS = 1_000L
    }
}
