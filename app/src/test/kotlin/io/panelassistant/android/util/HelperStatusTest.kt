package io.panelassistant.android.util

import io.panelassistant.android.AppIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelperStatusTest {
    private val build = "a".repeat(64)
    private val both = "${AppIdentity.LEGACY},${AppIdentity.SUCCESSOR}"

    private fun reply(caller: String = "LEGACY", packages: String = both, buildId: String = build) =
        "HELPERSTATUS 1 BUILD=$buildId CALLER=$caller PACKAGES=$packages"

    @Test fun parsesTheExactRecord() {
        assertEquals(
            HelperStatus(build, "LEGACY", linkedSetOf(AppIdentity.LEGACY, AppIdentity.SUCCESSOR)),
            parseHelperStatus(reply()),
        )
    }

    @Test fun refusesAnythingThatIsNotTheExactRecord() {
        assertNull(parseHelperStatus(null))
        assertNull(parseHelperStatus("ERR"))
        assertNull(parseHelperStatus("HELPERSTATUS 2 BUILD=$build CALLER=LEGACY PACKAGES=$both"))
        assertNull(parseHelperStatus(reply(buildId = "A".repeat(64))))
        assertNull(parseHelperStatus(reply(caller = "OTHER")))
        assertNull(parseHelperStatus(reply(packages = "${AppIdentity.LEGACY},,${AppIdentity.SUCCESSOR}")))
        assertNull(parseHelperStatus(reply(packages = "${AppIdentity.LEGACY},${AppIdentity.LEGACY}")))
        assertNull(parseHelperStatus(reply() + " EXTRA=1"))
    }

    @Test fun aBundledDualIdentityHelperThatKnowsThisCallerIsConfirmed() {
        assertNull(dualUidHelperRefusal(parseHelperStatus(reply()), build, AppIdentity.LEGACY))
        assertNull(
            dualUidHelperRefusal(parseHelperStatus(reply(caller = "SUCCESSOR")), build, AppIdentity.SUCCESSOR),
        )
    }

    @Test fun aHelperThatPredatesTheVerbIsRefused() {
        assertEquals(
            "helper did not report a dual-identity status",
            dualUidHelperRefusal(parseHelperStatus("ERR"), build, AppIdentity.LEGACY),
        )
    }

    @Test fun anotherHelperBuildIsRefused() {
        assertEquals(
            "running helper is not the bundled build",
            dualUidHelperRefusal(parseHelperStatus(reply()), "b".repeat(64), AppIdentity.LEGACY),
        )
    }

    @Test fun aHelperThatAcceptsOnlyOneIdIsRefused() {
        assertEquals(
            "running helper does not accept both application ids",
            dualUidHelperRefusal(parseHelperStatus(reply(packages = AppIdentity.LEGACY)), build, AppIdentity.LEGACY),
        )
    }

    @Test fun aCallerAuthenticatedAsTheOtherIdentityIsRefused() {
        assertEquals(
            "helper authenticated this caller as LEGACY",
            dualUidHelperRefusal(parseHelperStatus(reply()), build, AppIdentity.SUCCESSOR),
        )
        assertEquals(
            "helper authenticated this caller as ROOT",
            dualUidHelperRefusal(parseHelperStatus(reply(caller = "ROOT")), build, AppIdentity.LEGACY),
        )
    }

    @Test fun anUnknownOwnPackageIsRefused() {
        assertEquals(
            "com.example.other is not a panel application id",
            dualUidHelperRefusal(parseHelperStatus(reply()), build, "com.example.other"),
        )
    }
}
