package io.panelassistant.android.http

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
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
) {
    get("/health") {
        call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${renderConfigConcurrencyHash()}${panelAssistantDiscoveryHealthToken(config.deviceUid, config.androidId)}${packageHealthToken(packageName)}${versionCodeHealthToken(BuildConfig.VERSION_CODE)}${haLifecycleHealthToken()}${haNetworkHealthToken()}${panelAssistantRestartHealth()} pa_notice=${if (config.migrationNoticeVisible()) 1 else 0}\n")
    }
}

internal fun Route.migrationNoticeRoute(config: Config) {
    post("/migration-notice/dismiss") {
        val persisted = config.dismissMigrationNotice()
        call.respondText(
            """{"ok":$persisted}""",
            ContentType.Application.Json,
            if (persisted) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }
}

/**
 * The lifecycle suffix on `/health`. Appended rather than given its own endpoint because every page
 * already polls `/health` every ten seconds through `buildwatch.js`, so this needs no new route and
 * no second poll loop. Absent entirely when the panel is not watching, which keeps the line unchanged
 * for every existing consumer.
 */
private fun haLifecycleHealthToken(): String =
    haLifecycleHealthToken(HaLifecycleRuntime.watching, HaLifecycleRuntime.snapshot())

/**
 * The network-path tokens ride the same `/health` line and the same ten-second poll as the
 * lifecycle token, so the banner, the diagnostics row and the native chip all render one
 * observation. Empty while no service owns the monitor or no socket is held.
 */
private fun haNetworkHealthToken(): String = HaNetworkPathRuntime.healthToken()
