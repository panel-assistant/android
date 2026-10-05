package io.panelassistant.android.camera

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The two small rules the Android-bound indicator and activity lean on, pinned without Android. */
class CameraIndicationAndPromptTest {

    /** Stands in for the durable preference; survives a simulated process restart below. */
    private class DurableStore : CameraPermissionPrompt.Store {
        override var declined: Boolean = false
    }

    @Before fun resetPrompt() = CameraPermissionPrompt.reset()
    @After fun clearPrompt() = CameraPermissionPrompt.reset()

    @Test fun theOverlayAloneIsPositiveOnlyWhileTheScreenIsLit() {
        assertTrue(CameraIndicationPolicy.positive(overlayAttached = true, screenOff = false, ledLit = false))
        assertFalse("a dark screen with no LED is not telling the room", CameraIndicationPolicy.positive(overlayAttached = true, screenOff = true, ledLit = false))
        assertTrue(CameraIndicationPolicy.positive(overlayAttached = true, screenOff = true, ledLit = true))
        assertFalse("no overlay is never positive, lit LED or not", CameraIndicationPolicy.positive(overlayAttached = false, screenOff = false, ledLit = true))
    }

    @Test fun theReportedRouteNamesWhatIsActuallyLit() {
        assertEquals(CameraIndication.NONE, CameraIndicationPolicy.route(overlayAttached = false, ledLit = false))
        assertEquals(CameraIndication.OVERLAY, CameraIndicationPolicy.route(overlayAttached = true, ledLit = false))
        assertEquals(CameraIndication.LED, CameraIndicationPolicy.route(overlayAttached = true, ledLit = true))
    }

    @Test fun thePromptIsAskedOnlyWhenTheOwnerWantsItAndNotWhileInFlight() {
        assertFalse(CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        assertTrue(CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.asking()
        assertFalse("the dialog is up; do not ask again", CameraPermissionPrompt.shouldAsk())
    }

    @Test fun aPausedActivityReleasesAnUnansweredRequestSoItCannotStrandTheState() {
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        CameraPermissionPrompt.asking()
        assertFalse(CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.activityPaused()
        assertTrue("a request Android never answered must not block the next resume", CameraPermissionPrompt.shouldAsk())
    }

    @Test fun aDenialIsRememberedDurablyUntilAFreshEnable() {
        val store = DurableStore()
        CameraPermissionPrompt.install(store)
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        CameraPermissionPrompt.asking()
        CameraPermissionPrompt.answered(granted = false)
        assertTrue("the denial is written through to the durable store", store.declined)
        assertFalse("denied: no re-ask on the next resume or recreation", CameraPermissionPrompt.shouldAsk())
        // A process restart: the in-memory object is fresh, the store is the same preference.
        CameraPermissionPrompt.reset()
        CameraPermissionPrompt.install(store)
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        assertFalse("still denied after a restart that is not a fresh enable", CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = true)
        assertFalse("a fresh enable clears the durable denial", store.declined)
        assertTrue("a fresh enable is the one event that asks again", CameraPermissionPrompt.shouldAsk())
    }

    @Test fun theResumedActivityIsSignalledWhenAnAskBecomesDueNotOnlyOnResume() {
        var fired = 0
        CameraPermissionPrompt.setListener { fired++ }
        assertEquals("nothing due yet", 0, fired)
        // The owner publishes after the switch turns on while the activity is already resumed.
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = true)
        assertEquals("the listener is the signal", 1, fired)
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        assertEquals("a republish that changes nothing does not re-signal", 1, fired)
        CameraPermissionPrompt.asking()
        CameraPermissionPrompt.answered(granted = false)
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        assertEquals("a remembered denial does not re-signal", 1, fired)
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = true)
        assertEquals("a fresh enable does", 2, fired)
    }

    @Test fun registeringWhileAnAskIsAlreadyDueFiresAtOnceAndAPausedActivityIsNotSignalled() {
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        var fired = 0
        CameraPermissionPrompt.setListener { fired++ }
        assertEquals("a flip that happened while backgrounded prompts on resume", 1, fired)
        CameraPermissionPrompt.setListener(null)
        CameraPermissionPrompt.answered(granted = false)
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = true)
        assertEquals("no listener while paused; the next resume asks", 1, fired)
    }

    @Test fun aGrantEndsTheWantAndACameralessOwnerNeverWants() {
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        CameraPermissionPrompt.asking()
        CameraPermissionPrompt.answered(granted = true)
        assertFalse(CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.publish(wantsPermission = false, freshEnable = true)
        assertFalse("the owner of a camera-less profile publishes no want", CameraPermissionPrompt.shouldAsk())
    }
}
