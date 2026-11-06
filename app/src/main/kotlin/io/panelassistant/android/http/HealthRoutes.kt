package io.panelassistant.android.http

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.setupEverCompleted
import io.panelassistant.android.homeTakeoverPending
import io.panelassistant.android.sensors.HaLifecycleRuntime
import io.panelassistant.android.sensors.HaNetworkPathRuntime
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The same live response is mounted at the root and under /api/v1; neither redirects. */
internal fun Route.healthRoute(
    config: Config,
    packageName: String,
    buildToken: () -> String,
    renderConfigConcurrencyHash: () -> String,
    panelAssistantRestartHealth: () -> String,
) = ops {
    // The stub serves both spellings over HTTP (protocol section 20.7); this is the `health` read.
    served("/health", name = "health") {
        call.respondText(healthLine(config, packageName, buildToken, renderConfigConcurrencyHash, panelAssistantRestartHealth))
    }
}

/** The one health line, served at `/health` and read by Panel Assistant over its session. */
internal fun healthLine(
    config: Config,
    packageName: String,
    buildToken: () -> String,
    renderConfigConcurrencyHash: () -> String,
    panelAssistantRestartHealth: () -> String,
): String =
    "ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${renderConfigConcurrencyHash()}${panelAssistantDiscoveryHealthToken(config.deviceUid, config.androidId)}${packageHealthToken(packageName)}${versionCodeHealthToken(BuildConfig.VERSION_CODE)}${protocolHealthToken()} setup_complete=${if (config.setupEverCompleted) 1 else 0} home_pending=${if (config.homeTakeoverPending) 1 else 0}${haLifecycleHealthToken()}${haNetworkHealthToken()}${panelAssistantRestartHealth()}\n"

/**
 * The lifecycle suffix on `/health`. Appended rather than given its own endpoint because every page
 * already reads `/health` through `buildwatch.js`'s `health` subscription (its cadence is the `health`
 * family in ManageFamilies), so this needs no new route and no second loop. Absent entirely when the panel is not watching, which keeps the line unchanged
 * for every existing consumer.
 */
private fun haLifecycleHealthToken(): String =
    haLifecycleHealthToken(HaLifecycleRuntime.watching, HaLifecycleRuntime.snapshot())

/**
 * The network-path tokens ride the same `/health` line and the same `health` subscription as the
 * lifecycle token, so the banner, the diagnostics row and the native chip all render one
 * observation. Empty while no service owns the monitor or no socket is held.
 */
private fun haNetworkHealthToken(): String = HaNetworkPathRuntime.healthToken()
