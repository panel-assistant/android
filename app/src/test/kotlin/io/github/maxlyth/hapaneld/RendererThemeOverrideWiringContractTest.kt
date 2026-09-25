package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The override report is only as good as the observation that feeds it, and that observation lives
 * in `DashboardActivity`, which has no JVM test. These pins hold the three wiring facts a green
 * projection test cannot see: the page is asked for the EFFECTIVE scheme, the answer is published
 * into the live generation tuple, and the server hands the configured policy to the projection.
 */
class RendererThemeOverrideWiringContractTest {

    private fun source(vararg candidates: String): String =
        candidates.map(::File).firstOrNull { it.isFile }?.readText()
            ?: error("source not found: ${candidates.first()}")

    private val dashboard = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt",
    )
    private val server = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
    )

    @Test fun theThemeCaptureAsksForTheEffectiveSchemeAndPublishesIt() {
        val capture = dashboard.substringAfter("private fun captureDashboardTheme(").substringBefore("\n    }\n")
        assertTrue(
            "the capture must run the shared observation script, not a private one",
            capture.contains("ExternalAuthProtocol.THEME_OBSERVATION_JS"),
        )
        assertTrue(
            "the effective scheme must reach the live generation tuple",
            capture.contains("RendererAdmissionRuntime.setEffectiveTheme(activityOwner, observed.effectiveDark)"),
        )
        assertTrue(
            "only the newest asynchronous observation may publish",
            capture.contains("observationEpoch != themeObservationEpoch"),
        )
        assertTrue(
            "a disconnected page cannot publish a late observation",
            capture.contains("!frontendConnected"),
        )
        assertTrue(
            "a garbage answer must not erase the stored observation",
            capture.contains("null -> if (observed.valid) Config(this).clearDashboardThemeDark()"),
        )
    }

    @Test fun disconnectInvalidatesEveryPendingThemeObservation() {
        val setter = dashboard.substringAfter("private var frontendConnected = false").substringBefore("private val retryPolicy")
        assertTrue(
            "disconnect, page replacement and teardown all pass through the connected setter",
            setter.contains("if (!value) themeObservationEpoch++"),
        )
    }

    @Test fun theCaptureStillRunsOnConnectAndOnEveryThemeUpdate() {
        // Both triggers predate this lane; losing either would make the report go stale silently.
        assertTrue(dashboard.contains("ExternalBusProtocol.Incoming.ThemeUpdate -> captureDashboardTheme(generation, session)"))
        val connectBlock = dashboard.substringAfter("captureDashboardTheme(generation, session)\n")
        assertTrue(connectBlock.contains("Log.i(TAG, \"frontend connected\")"))
    }

    @Test fun theServerHandsTheConfiguredPolicyToTheProjection() {
        val call = server.substringAfter("private fun rendererAdmission(): RendererAdmissionPresentation {").substringBefore("\n    }\n")
        assertTrue(
            "without the policy the projection defaults to Follow and can never report an override",
            call.contains("themePolicy = config.dashboardTheme"),
        )
        assertTrue(
            "the projection must judge overrides against the policy the renderer actually baked",
            call.contains("themeEffectivePolicy = config.dashboardThemeEffective"),
        )
    }
}
