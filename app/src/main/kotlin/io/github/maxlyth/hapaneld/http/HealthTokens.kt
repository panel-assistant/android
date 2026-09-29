package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.sensors.HaLifecycle
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId

/**
 * The lifecycle suffix on `/health`, rendered from ONE atomic snapshot so the state and its source can
 * never come from different moments. Empty when the panel is not watching or no service owns lifecycle
 * tracking, which keeps the line unchanged for every existing consumer. `ha_src` appears only when a
 * source actually OBSERVED the state: the initial `normal` and a locally noticed `connection_lost` are
 * the panel's own inferences, and naming a source for them would claim an observation nobody made.
 * Pure — unit-tested in `HaLifecycleSurfaceContractTest`.
 */
internal fun haLifecycleHealthToken(watching: Boolean, snap: HaLifecycle.Snapshot?): String {
    if (!watching || snap == null) return ""
    val src = snap.source?.let { " ha_src=${it.name.lowercase()}" }.orEmpty()
    // The refusal rides the same observation because the diagnostics row explains it and that row is
    // now refreshed from this line; deriving it from a second read would reintroduce the divergence
    // between surfaces that the one-shot banner had.
    val refused = if (snap.refused) " ha_refused=1" else ""
    return " ha=${snap.state.wireValue}$src$refused"
}

/** Add Panel Assistant's stable discovery pseudonym without exposing the Android ID itself. */
internal fun panelAssistantDiscoveryHealthToken(deviceUid: String, androidId: String = ""): String =
    panelAssistantDiscoveryId(deviceUid)?.let { did ->
        " did=$did identity=install" + panelAssistantDiscoveryId(androidId)?.let { " legacy_did=$it" }.orEmpty()
    }.orEmpty()

/** Which installed identity answered: during the application-id migration a panel can hold both. */
internal fun packageHealthToken(packageName: String): String = " pkg=$packageName"

/** The build number beside the version name, so a reader can tell two builds of one release apart. */
internal fun versionCodeHealthToken(versionCode: Int): String = " vc=$versionCode"
