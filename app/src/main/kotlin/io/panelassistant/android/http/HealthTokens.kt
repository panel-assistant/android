package io.panelassistant.android.http

import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.panelAssistantDiscoveryId

/**
 * The lifecycle suffix on `/health`, rendered from ONE atomic snapshot so its tokens can never come
 * from different moments. Empty when the panel is not watching or no service owns lifecycle tracking,
 * which keeps the line unchanged for every existing consumer.
 * Pure — unit-tested in `HaLifecycleSurfaceContractTest`.
 */
internal fun haLifecycleHealthToken(watching: Boolean, snap: HaLifecycle.Snapshot?): String {
    if (!watching || snap == null) return ""
    // The refusal rides the same observation because the diagnostics row explains it and that row is
    // now refreshed from this line; deriving it from a second read would reintroduce the divergence
    // between surfaces that the one-shot banner had.
    val refused = if (snap.refused) " ha_refused=1" else ""
    val elapsed = snap.elapsedMs?.let { " ha_elapsed_ms=$it" }.orEmpty()
    val expected = snap.expectedMs?.let { " ha_expected_ms=$it" }.orEmpty()
    val grace = " ha_grace_ms=${snap.offlineGraceRemainingMs}"
    return " ha=${snap.state.wireValue}$refused ha_reason=${snap.reason.wireValue}$elapsed$expected$grace"
}

/** Add Panel Assistant's stable discovery pseudonym without exposing the Android ID itself. */
internal fun panelAssistantDiscoveryHealthToken(deviceUid: String, androidId: String = ""): String =
    panelAssistantDiscoveryId(deviceUid)?.let { did ->
        " did=$did identity=install" + panelAssistantDiscoveryId(androidId)?.let { " legacy_did=$it" }.orEmpty()
    }.orEmpty()

/** Which application id answered. Panel Assistant reads an absent token as the app published before 0.9.11. */
internal fun packageHealthToken(packageName: String): String = " pkg=$packageName"

/** The build number beside the version name, so a reader can tell two builds of one release apart. */
internal fun versionCodeHealthToken(versionCode: Int): String = " vc=$versionCode"

/**
 * The native protocol range this build speaks, so Panel Assistant can tell from the health line alone
 * that a panel needs a newer app before any hello succeeds. A line without it predates protocol 5.
 */
internal fun protocolHealthToken(
    min: Int = io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol.PROTOCOL_MIN,
    max: Int = io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol.PROTOCOL_MAX,
): String = " proto=$min-$max"

/**
 * This panel's ADB serial, learned from its own adbd's mDNS announcement, so Panel Assistant can fold the
 * `_adb._tcp` discovery card into this panel's. Absent while unknown: released parsers reject an empty value.
 */
internal fun adbSerialHealthToken(serial: String?): String = serial?.let { " adb=$it" }.orEmpty()
