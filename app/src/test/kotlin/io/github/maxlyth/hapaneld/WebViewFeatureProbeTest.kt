package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The no-provider case: `WebViewFeature.isFeatureSupported` raises `NoClassDefFoundError` for the life
 * of the process instead of answering. On a HOME activity that is a crash loop, not a crash, so these
 * assert the throw is absorbed as "absent" and classified with the existing verdict.
 */
class WebViewFeatureProbeTest {
    /** Exactly the shape androidx.webkit produces once its lazy holder has failed to initialise. */
    private val poisonedProbe = WebViewFeatureProbe {
        throw NoClassDefFoundError("androidx/webkit/internal/WebViewGlueCommunicator")
    }

    private val capableProbe = WebViewFeatureProbe { true }

    private val incapableProbe = WebViewFeatureProbe { false }

    private fun probeSupporting(vararg features: String) =
        WebViewFeatureProbe { it in features.toSet() }

    /**
     * Assert first, then return. A helper that stopped absorbing would otherwise throw straight out of
     * the test, which arrives as a JUnit *error* rather than an assertion failure — and an error is not
     * evidence that the contract fired.
     */
    private fun absorbed(feature: String, probe: WebViewFeatureProbe): Boolean = try {
        webViewFeatureSupported(feature, probe)
    } catch (escaped: Throwable) {
        fail("the probe escaped webViewFeatureSupported instead of reading as absent: $escaped")
        false
    }

    private fun admissionOf(probe: WebViewFeatureProbe): AdmissionOutcome? = try {
        secureBridgeAdmission(probe)
    } catch (escaped: Throwable) {
        fail("the probe escaped secureBridgeAdmission instead of being classified: $escaped")
        null
    }

    @Test fun aThrowingProbeReadsAsAbsentInsteadOfEscaping() {
        assertFalse(absorbed("WEB_MESSAGE_LISTENER", poisonedProbe))
        assertFalse(absorbed("DOCUMENT_START_SCRIPT", poisonedProbe))
        assertFalse(absorbed("FORCE_DARK", poisonedProbe))
        assertFalse(absorbed("FORCE_DARK_STRATEGY", poisonedProbe))
    }

    @Test fun aCheckedExceptionIsAbsorbedToo() {
        // runCatching, not catch(Error): the Error is what this exists for, but an ordinary exception
        // from a half-broken provider must read as absent too rather than reaching the activity.
        assertFalse(absorbed("WEB_MESSAGE_LISTENER", WebViewFeatureProbe { throw IllegalStateException("glue") }))
    }

    @Test fun aWorkingProbeIsPassedThroughUnchanged() {
        // The invariant: nothing about a provider that answers changes.
        assertTrue(absorbed("WEB_MESSAGE_LISTENER", capableProbe))
        assertFalse(absorbed("WEB_MESSAGE_LISTENER", incapableProbe))
    }

    @Test fun aMissingProviderIsClassifiedBridgeUnavailable() {
        assertEquals(AdmissionOutcome.BRIDGE_UNAVAILABLE, admissionOf(poisonedProbe))
    }

    @Test fun aCapableProviderIsAdmitted() {
        assertNull(admissionOf(probeSupporting("WEB_MESSAGE_LISTENER", "DOCUMENT_START_SCRIPT")))
    }

    @Test fun eitherHalfMissingIsTheSameExistingVerdict() {
        // No new classification: a half-capable provider and a throwing one land on one outcome.
        assertEquals(
            AdmissionOutcome.BRIDGE_UNAVAILABLE,
            admissionOf(probeSupporting("WEB_MESSAGE_LISTENER")),
        )
        assertEquals(
            AdmissionOutcome.BRIDGE_UNAVAILABLE,
            admissionOf(probeSupporting("DOCUMENT_START_SCRIPT")),
        )
        assertEquals(AdmissionOutcome.BRIDGE_UNAVAILABLE, admissionOf(incapableProbe))
    }

    @Test fun everyFeatureCheckInTheAppGoesThroughTheGuardedHelper() {
        // The helper only helps while it is the single door. A new bare call re-opens the crash loop in
        // whichever activity adds it, so this fails on the call rather than on the next broken panel.
        val roots = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).filter { it.isDirectory }
        assertTrue("no main Kotlin source root found", roots.isNotEmpty())

        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("isFeatureSupported") }
                .map { it.toRelativeString(root) }
        }.sorted()

        assertEquals(
            "isFeatureSupported must be called only from WebViewFeatures.kt — route new checks through webViewFeatureSupported()",
            listOf("io/github/maxlyth/hapaneld/WebViewFeatures.kt"),
            offenders,
        )
    }

    @Test fun theSoleCallSiteIsTheProbeItself() {
        // Pin it to one occurrence: the file being the only holder is not enough if it grows a second,
        // unguarded call of its own.
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/WebViewFeatures.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/WebViewFeatures.kt"),
        ).first { it.isFile }.readText()

        // Count invocations, not the KDoc above that names the method while explaining why it is caged.
        assertEquals(1, Regex("""\bisFeatureSupported\s*\(""").findAll(source).count())
        assertTrue(
            "the sole call must sit inside SystemWebViewFeatureProbe",
            source.contains("WebViewFeatureProbe { WebViewFeature.isFeatureSupported(it) }"),
        )
    }
}
