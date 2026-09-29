package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation

/** Everything the dashboard shows that costs a root/probe round-trip, gathered once. */
internal class ManagementSnapshot(
    val facts: Map<String, String>,
    val live: Map<String, String>,
    val caps: Capabilities,
    val capabilityRows: List<DiagReader.Cap>,
    val privilege: PrivilegedRouteObservation,
    val densityCur: Int?,
    val densityBase: Int?,
    val fontScale: Float,
    val wifiChronic: Boolean,
)
