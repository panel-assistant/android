package io.panelassistant.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityLearningBootstrapProblemTest {
    @Test fun `authentication failures are classified without exposing their detail`() {
        assertEquals(
            EntityBootstrapProblem.AUTHENTICATION,
            classifyEntityBootstrapProblem("degraded", "states request failed: HTTP 401"),
        )
        assertEquals(
            EntityBootstrapProblem.AUTHENTICATION,
            classifyEntityBootstrapProblem("degraded", "Home Assistant credential rejected"),
        )
        assertEquals(
            EntityBootstrapProblem.AUTHENTICATION,
            classifyEntityBootstrapProblem("degraded", "Home Assistant token unavailable"),
        )
    }

    @Test fun `other failed scans get generic retry guidance`() {
        assertEquals(
            EntityBootstrapProblem.SYNCHRONIZATION,
            classifyEntityBootstrapProblem("degraded", "connection timed out for private-host.example"),
        )
    }

    @Test fun `success target change and empty errors clear the problem`() {
        assertNull(classifyEntityBootstrapProblem("active", ""))
        assertNull(classifyEntityBootstrapProblem("active", "states request failed: HTTP 401"))
        assertNull(classifyEntityBootstrapProblem("learning", ""))
        assertNull(classifyEntityBootstrapProblem("degraded", ""))
    }
}
