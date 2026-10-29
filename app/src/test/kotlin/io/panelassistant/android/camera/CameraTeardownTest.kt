package io.panelassistant.android.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generation rule used by the camera owner's delayed finish. */
class CameraTeardownTest {

    @Test fun theRuleIsGenerationEqualityUnlessTheSubsystemIsStopping() {
        assertTrue("its own generation", CameraTeardown.ownsSessionGlobals(false, endedGeneration = 4L, currentGeneration = 4L))
        assertFalse("a newer session has started", CameraTeardown.ownsSessionGlobals(false, endedGeneration = 4L, currentGeneration = 5L))
        assertTrue("stopping owns them regardless", CameraTeardown.ownsSessionGlobals(true, endedGeneration = 4L, currentGeneration = 5L))
    }
}
