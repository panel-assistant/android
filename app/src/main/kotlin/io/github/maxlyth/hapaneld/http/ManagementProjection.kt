package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.config.Capabilities

/** Service-owned values projected from one coherent set of controller observations. */
internal data class ManagementProjection(
    val facts: Map<String, String>,
    val live: Map<String, String>,
    val capabilities: Capabilities,
    val capabilityRows: List<DiagReader.Cap>,
    /** Whether the Wi-Fi instability behind the `Wi-Fi stability` fact is chronic, decided from the
     *  SAME outage read that produced the fact — so the `/diag` gate can never disagree with the
     *  text it is gating. Undefaulted on purpose: a new caller must state it. */
    val wifiChronic: Boolean,
)
