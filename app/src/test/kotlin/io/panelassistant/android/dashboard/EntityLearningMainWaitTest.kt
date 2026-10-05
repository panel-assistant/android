package io.panelassistant.android.dashboard

import android.content.SharedPreferences
import io.panelassistant.android.Config
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class EntityLearningMainWaitTest {
    @Test fun rendererBootstrapReadDoesNotWaitForInitializationMonitor() {
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> false
                "getBoolean" -> args?.get(0) == "dashboard_entity_learning"
                else -> error("unexpected preference read: ${method.name}")
            }
        } as SharedPreferences
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as Unsafe
        val manager = unsafe.allocateInstance(EntityLearningManager::class.java) as EntityLearningManager
        EntityLearningManager::class.java.getDeclaredField("config").apply { isAccessible = true }
            .set(manager, Config(prefs))
        EntityLearningRuntime.attach(manager)

        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val readerFinished = CountDownLatch(1)
        val readerFailure = AtomicReference<Throwable?>()
        val owner = thread(name = "entity-init-owner") {
            synchronized(manager) {
                ownerEntered.countDown()
                releaseOwner.await()
            }
        }
        try {
            assertTrue(ownerEntered.await(1, TimeUnit.SECONDS))
            val reader = thread(name = "main") {
                try {
                    assertNull(EntityLearningRuntime.bootstrapProblem())
                } catch (failure: Throwable) {
                    readerFailure.set(failure)
                } finally {
                    readerFinished.countDown()
                }
            }
            try {
                assertTrue("renderer read waited for catalogue initialization", readerFinished.await(500, TimeUnit.MILLISECONDS))
                readerFailure.get()?.let { throw it }
            } finally {
                releaseOwner.countDown()
                reader.join(1_000)
            }
        } finally {
            releaseOwner.countDown()
            owner.join(1_000)
            EntityLearningRuntime.detach(manager)
        }
    }
}
