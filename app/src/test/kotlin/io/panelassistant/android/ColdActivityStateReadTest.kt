package io.panelassistant.android

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdActivityStateReadTest {
    @Test fun `cold Config construction leaves the launching thread free until state is ready`() {
        runBlocking {
            val caller = Thread.currentThread()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reader = AtomicReference<Thread>()
            val preferences = Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, _ ->
                if (method.name == "contains") {
                    reader.set(Thread.currentThread())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    false
                } else error("Unexpected preference operation: ${method.name}")
            } as SharedPreferences

            try {
                val config = async(start = CoroutineStart.UNDISPATCHED) {
                    readActivityStateOffMain { Config(preferences) }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNotEquals(caller, reader.get())
                assertFalse("the UI can continue while SQLite is delayed", config.isCompleted)
                release.countDown()
                config.await()
            } finally {
                release.countDown()
            }
        }
    }
}
