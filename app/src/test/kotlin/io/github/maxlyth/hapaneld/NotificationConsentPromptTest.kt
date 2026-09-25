package io.github.maxlyth.hapaneld

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationConsentPromptTest {
    @Test fun asksOnceForAVersionThenNeverAgainForThatVersion() {
        assertTrue(ask(lastAsked = null))
        assertFalse("A version that has asked never asks again, whatever the answer was", ask(lastAsked = VERSION))
    }

    @Test fun aDifferentVersionMayAskOnce() {
        assertTrue(ask(lastAsked = VERSION - 1))
        assertTrue(ask(lastAsked = VERSION + 1))
    }

    @Test fun neverAsksBelowAndroid13() {
        assertFalse(ask(sdk = 32, lastAsked = null))
        assertFalse(ask(sdk = 27, lastAsked = null))
        assertTrue(ask(sdk = 33, lastAsked = null))
    }

    @Test fun neverAsksWhenTheInstallerAlreadyGranted() {
        assertFalse(ask(granted = true, lastAsked = null))
    }

    @Test fun neverAsksWithoutAStandingScreenOrWhileAnAutomaticReturnIsDue() {
        assertFalse("The dashboard route is never interrupted", ask(standing = false, lastAsked = null))
        assertFalse("A dialog must not race the automatic return", ask(autoReturn = true, lastAsked = null))
    }

    private fun ask(
        sdk: Int = 34,
        granted: Boolean = false,
        standing: Boolean = true,
        autoReturn: Boolean = false,
        lastAsked: Long?,
    ): Boolean = NotificationConsentPrompt.shouldAsk(
        sdkInt = sdk,
        granted = granted,
        standingScreenPresented = standing,
        autoReturnPending = autoReturn,
        currentVersionCode = VERSION,
        lastAskedVersionCode = lastAsked,
    )

    private companion object {
        const val VERSION = 900L
    }
}
