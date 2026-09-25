package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootLocalSocketTest {
    @Test fun `only a root peer may serve privileged helper protocols`() {
        assertTrue(trustedRootSocketPeer(0))
        assertFalse(trustedRootSocketPeer(1000))
        assertFalse(trustedRootSocketPeer(2000))
        assertFalse(trustedRootSocketPeer(10_123))
    }

}
