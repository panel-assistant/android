package io.panelassistant.android.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SuRootKnownUnusableTest {
    @Test
    fun `a missing su binary is known unusable after its first launch, and asking starts nothing`() {
        val launches = AtomicInteger()
        val su = SuShell(launch = { launches.incrementAndGet(); throw IOException("Cannot run program \"su\": error=2, No such file or directory") })

        assertFalse("nothing has been tried yet", su.rootKnownUnusable())
        assertFalse(su.available())
        val afterFirstUse = launches.get()
        assertTrue(su.rootKnownUnusable())
        repeat(5) { assertTrue(su.rootKnownUnusable()) }
        assertTrue("asking never launches su", launches.get() == afterFirstUse)
    }
}
