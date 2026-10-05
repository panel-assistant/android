package io.panelassistant.android

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Minimal active-call gate used by the service-runtime lifecycle composition tests. */
internal class MqttCommandAdmission {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var accepting = true
    private var active = 0

    fun run(command: () -> Unit): Boolean {
        lock.withLock {
            if (!accepting) return false
            active++
        }
        try {
            command()
            return true
        } finally {
            lock.withLock {
                active--
                if (active == 0) idle.signalAll()
            }
        }
    }

    fun closeAndDrain() = lock.withLock {
        accepting = false
        while (active > 0) idle.awaitUninterruptibly()
    }
}
