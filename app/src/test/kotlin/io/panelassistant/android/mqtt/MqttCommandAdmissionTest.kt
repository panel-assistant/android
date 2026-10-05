package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttCommandAdmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttCommandAdmissionTest {
    @Test fun closeDrainsTheAdmittedCommandBeforeRefusingLaterWork() {
        val admission = MqttCommandAdmission()
        val commandEntered = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var commands = 0

        val admitted = executor.submit<Boolean> {
            admission.run {
                commandEntered.countDown()
                assertTrue(releaseCommand.await(2, TimeUnit.SECONDS))
                commands++
            }
        }
        assertTrue(commandEntered.await(2, TimeUnit.SECONDS))
        val closed = executor.submit { admission.closeAndDrain() }
        assertFalse(closed.isDone)

        releaseCommand.countDown()
        assertTrue(admitted.get(2, TimeUnit.SECONDS))
        closed.get(2, TimeUnit.SECONDS)
        assertFalse(admission.run { commands++ })
        assertEquals(1, commands)
        executor.shutdownNow()
    }

    @Test fun failureReleasesTheActiveSlotAndCloseIsIdempotent() {
        val admission = MqttCommandAdmission()

        runCatching { admission.run { error("command failed") } }
        admission.closeAndDrain()
        admission.closeAndDrain()

        assertFalse(admission.run { error("closed admission ran") })
    }
}
