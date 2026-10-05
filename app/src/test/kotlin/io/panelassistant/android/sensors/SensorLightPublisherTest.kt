package io.panelassistant.android.sensors

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLightPublisherTest {
    @Test fun unexposedIlluminanceNeverStartsOrWakesTheLazyPublisher() {
        val published = mutableListOf<Int>()
        val publisher = SensorLightPublisher(publish = published::add)

        assertTrue(!submitIlluminanceIfExposed(false, 100, publisher::submit))
        publisher.close()

        assertTrue(publisher.awaitTermination(0L))
        assertTrue(published.isEmpty())
    }

    @Test fun exposedIlluminanceIsAdmittedToThePublisher() {
        val published = Collections.synchronizedList(mutableListOf<Int>())
        val consumed = CountDownLatch(1)
        val publisher = SensorLightPublisher(publish = { published += it; consumed.countDown() })

        assertTrue(submitIlluminanceIfExposed(true, 123, publisher::submit))
        assertTrue(consumed.await(5, TimeUnit.SECONDS))
        publisher.close()
        assertTrue(publisher.awaitTermination(5_000L))
        assertEquals(listOf(123), published)
    }

    @Test fun rawAutoBrightnessSamplesContinueWhileMqttPublicationIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rawLux = mutableListOf<Float>()
        val publisher = SensorLightPublisher(
            publish = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
            threadName = "sensor-light-composition-test",
        )
        val callbacks = SensorRunCallbacks(
            onLux = publisher::submit,
            onLuxRaw = rawLux::add,
            onProximity = { _, _, _ -> },
            onGesture = {},
            onTemperature = {},
            onHumidity = {},
        )

        callbacks.light(100f, now = 100L)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        callbacks.light(110f, now = 200L)

        assertEquals(listOf(100f, 110f), rawLux)
        release.countDown()
        publisher.close()
        assertTrue(publisher.awaitTermination(5_000L))
    }

    @Test fun blockedMqttPublicationDoesNotBlockSamplesAndKeepsLatestPendingLux() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val published = Collections.synchronizedList(mutableListOf<Int>())
        val publisher = SensorLightPublisher(
            publish = { lux ->
                published += lux
                if (lux == 100) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                finished.countDown()
            },
            threadName = "sensor-light-publisher-test",
        )

        publisher.submit(100)
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val submissionsReturned = CountDownLatch(1)
        Thread {
            publisher.submit(200)
            publisher.submit(300)
            submissionsReturned.countDown()
        }.start()

        assertTrue("sensor submissions must not wait for MQTT", submissionsReturned.await(1, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        publisher.close()
        assertTrue(publisher.awaitTermination(5_000L))
        assertEquals(listOf(100, 300), published)
    }
}
