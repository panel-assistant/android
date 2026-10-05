package io.panelassistant.android.mqtt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttTeardownGateTest {
    @Test fun `wedged disconnect retains one thread and rejects later teardown`() {
        val gate = MqttTeardownGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(gate.execute { entered.countDown(); release.await() })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) { assertFalse(gate.execute { error("queued teardown ran") }) }
        } finally {
            release.countDown()
            gate.close()
        }
    }

    @Test fun `disconnect completion stays pending for its owner and fails on gate rejection`() {
        val gate = MqttTeardownGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val active = gate.submit {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(active.isDone)

            val rejected = gate.submit { error("rejected teardown ran") }
            assertTrue(rejected.isCompletedExceptionally)

            release.countDown()
            active.get(1, TimeUnit.SECONDS)
            assertTrue(active.isDone)
        } finally {
            release.countDown()
            gate.close()
        }
    }

    @Test fun `final publication retains one bounded slot through ack or timeout`() {
        val gate = MqttFinalPublishGate()
        val entered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            assertTrue(gate.execute(2_000, { finished.countDown() }) {
                entered.countDown()
                releaseWorker.await()
                // Deliberately never invokes finish: timeout owns cleanup.
            } == FinalPublishAdmission.ADMITTED)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) {
                assertTrue(gate.execute(2_000, {}, {}) == FinalPublishAdmission.BUSY)
            }
            releaseWorker.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
        } finally {
            releaseWorker.countDown()
            gate.close()
        }
    }
}
