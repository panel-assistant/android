package io.panelassistant.android.state

import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.LedBackend
import io.panelassistant.android.hardware.SocketLedController
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

internal class NativeLedActuationTest : PanelRuntimeRig() {
    private class Led : LedController {
        var accepted = true
        override fun available() = true
        override fun backend() = LedBackend.LEDJNI
        override fun colorCapable() = true
        override fun setRgb(r: Int, g: Int, b: Int) = accepted
        override fun off() = accepted
    }

    @Test fun onlyConfirmedAbsenceMarksTheLedUnsupported() {
        for (reply in listOf("none", null, "ERR", "sysfs")) {
            val replies = reply?.let { mapOf("LEDPROBE" to it) }.orEmpty()
            val rig = rig(led = SocketLedController(FakeDaemon(replies, available = reply != null)))
            try {
                rig.start()
                val native = PanelAssistantStateReporter(log = {})
                rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
                val hello = native.offer()
                assertEquals("served, reply=$reply", reply != "none", hello.descriptors.any { it.channel == "led" })
                assertEquals("unsupported, reply=$reply", reply == "none", "led" in hello.unsupported)
            } finally { rig.close() }
        }
    }

    @Test fun losingTheHelperDoesNotAdvertiseAnAbsentLedAgain() {
        var reply: String? = "none"
        val daemon = object : Daemon by FakeDaemon() {
            override fun send(cmd: String): String? = if (cmd == "LEDPROBE") reply else null
        }
        val led = SocketLedController(daemon)
        val rig = rig(led = led)
        try {
            rig.start()
            val native = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            assertTrue("led" in native.offer().unsupported)
            reply = null
            assertEquals(LedBackend.ABSENT, led.backend())
            assertTrue("led" in native.offer().unsupported)
            reply = "sysfs"
            assertEquals(LedBackend.HELPER_SYSFS, led.backend())
            assertTrue(native.offer().descriptors.any { it.channel == "led" })
        } finally { rig.close() }
    }

    @Test fun refusedOutputWithdrawsKnownStateAndSuccessfulRetryRestoresIt() {
        val led = Led()
        val rig = rig(led = led)
        val states = CopyOnWriteArrayList<StateConverger.Observation>()
        try {
            rig.core.addStateSink { channel, observation -> if (channel == "led") states += observation }
            rig.start()
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "led", CommandValue.Light(true, rgb = Triple(200, 0, 0))))
            assertTrue(states.last() is StateConverger.Observation.Known)
            led.accepted = false
            val result = submitNative(rig, "led", CommandValue.Light(false))
            assertTrue("refused output fails the native command: $result", result is PanelAssistantCommandResult.Failed)
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            assertEquals(StateConverger.Observation.Unavailable, states.last())
            assertEquals(PanelAssistantCommandResult.Applied, run { led.accepted = true; submitNative(rig, "led", CommandValue.Light(false)) })
            assertTrue(states.last() is StateConverger.Observation.Known)
        } finally { rig.close() }
    }
    @Test fun aLaterEffectFrameFailureWithdrawsThePreviouslyKnownActiveEffect() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        var armed = false
        val led = object : LedController {
            override fun available() = true
            override fun backend() = LedBackend.LEDJNI
            override fun colorCapable() = true
            fun write(): Boolean {
                if (!armed || writes.incrementAndGet() == 1) return true
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return false
            }
            override fun setRgb(r: Int, g: Int, b: Int) = write()
            override fun off() = write()
        }
        val rig = rig(led = led)
        val states = CopyOnWriteArrayList<StateConverger.Observation>()
        try {
            rig.core.addStateSink { channel, observation -> if (channel == "led") states += observation }
            rig.start()
            armed = true
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "led", CommandValue.Light(true, effect = "pulse")))
            val known = states.last() as StateConverger.Observation.Known
            assertEquals("pulse", JSONObject(known.payload).getString("effect"))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            val until = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < until && states.last() != StateConverger.Observation.Unavailable) {
                rig.core.reconcileAll()
                Thread.sleep(5)
            }
            assertEquals(StateConverger.Observation.Unavailable, states.last())
        } finally { release.countDown(); rig.close() }
    }

}
