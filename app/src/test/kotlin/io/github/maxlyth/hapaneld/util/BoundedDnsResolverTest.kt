package io.github.maxlyth.hapaneld.util

import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDnsResolverTest {
    // The lookup stays stuck until after the assertion, so a lookup that pinned its caller never returns.
    @Test(timeout = 10_000)
    fun `a stuck platform lookup cannot pin its caller`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        BoundedDnsResolver(
            lookup = {
                entered.countDown()
                release.await()
                arrayOf(InetAddress.getLoopbackAddress())
            },
            maxConcurrentLookups = 1,
            queueCapacity = 1,
            threadPrefix = "bounded-dns-test",
        ).use { resolver ->
            assertThrows(SocketTimeoutException::class.java) { resolver.resolveOne("stuck.test", 75) }
            assertTrue("lookup was not exercised", entered.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
        }
    }

    @Test(timeout = 2_000)
    fun `a completed lookup returns its resolved address`() {
        val expected = InetAddress.getByName("127.0.0.1")
        BoundedDnsResolver(lookup = { arrayOf(expected) }, threadPrefix = "bounded-dns-test").use { resolver ->
            assertEquals(expected, resolver.resolveOne("collector.test", 500))
        }
    }
}
