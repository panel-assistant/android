package io.panelassistant.android.http

import io.panelassistant.android.DiscoveryResult
import io.panelassistant.android.control.SystemController
import io.ktor.http.Parameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun builtinRendererNeedsConnection(
    currentPackage: String,
    currentHaUrl: String,
    currentHasCredentials: Boolean,
    requestedPackage: String?,
    requestedHaUrl: String?,
    requestHasCredentials: Boolean,
): Boolean {
    if ((requestedPackage ?: currentPackage) != SystemController.BUILTIN_DASHBOARD) return false
    val effectiveUrl = (requestedHaUrl ?: currentHaUrl).trim()
    val effectiveCredentials = effectiveUrl.isNotBlank() && (currentHasCredentials || requestHasCredentials)
    return !effectiveCredentials
}

/** A handover POST after the panel has decided whether the address it was given actually answers. */
internal data class HandoverConfigPost(
    val parameters: Parameters,
    /** Null when this POST carried no handover to settle, so the response says nothing about one. */
    val outcome: HaUrlHandover.Outcome? = null,
    val url: String = "",
)

/**
 * Turn a POST carrying a handed-over Home Assistant URL into the settings write it should become.
 *
 * Rewrites the posted parameters rather than persisting separately, so an accepted address takes
 * exactly the same commit and live-apply path as one a person typed — the renderer reconfigure, the
 * read-back check and the mutation plan all behave identically.
 *
 * Three outcomes:
 *  - **already configured** — [currentHaUrl] is set, so the handover is dropped and [verify] is never
 *    called. Adoption is re-runnable, and a retry must converge rather than overwrite a working panel
 *    or spend a probe proving something the panel no longer needs.
 *  - **verified** — promoted to `ha_url`, with the handover field and its reason cleared.
 *  - **failed** — the address is kept with its reason, so the wizard can offer a correction that shows
 *    what was tried instead of an empty field.
 *
 * The provenance marker `ha_setup_handover` is deliberately untouched: it rides the POST as an ordinary
 * key and is stored whatever [verify] decides, because an address that did not answer still means Home
 * Assistant deployed this panel.
 *
 * [verify] is a parameter rather than a call so the whole decision is exercisable without a network.
 */
internal suspend fun rewritePostForHandover(
    posted: Parameters,
    currentHaUrl: String,
    verify: suspend (String) -> HaUrlHandover.Outcome,
): HandoverConfigPost = rewriteHandover(stripAlternates(posted), currentHaUrl, alternatesOf(posted), verify)

/** The other addresses Panel Assistant knows, in its order; admission has already normalised and bounded them. */
private fun alternatesOf(posted: Parameters): List<String> =
    posted[HANDOVER_ALTERNATES_KEY]?.split(' ')?.filter(String::isNotBlank).orEmpty()

/** The alternates are only candidates for this one decision, never a stored setting. */
private fun stripAlternates(posted: Parameters): Parameters =
    if (HANDOVER_ALTERNATES_KEY !in posted) posted else Parameters.build {
        for (name in posted.names()) {
            if (name == HANDOVER_ALTERNATES_KEY) continue
            posted.getAll(name).orEmpty().forEach { append(name, it) }
        }
    }

/**
 * Probe every candidate at once and keep the first, in Panel Assistant's order, that answers as Home
 * Assistant. Parallel so a list of dead addresses costs one probe's time, not one per address. When none
 * answers, the offered address and its own reason stand, exactly as before alternates existed.
 */
private suspend fun firstVerified(
    offered: String,
    alternates: List<String>,
    verify: suspend (String) -> HaUrlHandover.Outcome,
): Pair<String, HaUrlHandover.Outcome> = coroutineScope {
    val candidates = (listOf(offered) + alternates).distinct()
    val outcomes = candidates.map { async { verify(it) } }.awaitAll()
    candidates.zip(outcomes).firstOrNull { it.second.verified } ?: (offered to outcomes.first())
}

private suspend fun rewriteHandover(
    posted: Parameters,
    currentHaUrl: String,
    alternates: List<String>,
    verify: suspend (String) -> HaUrlHandover.Outcome,
): HandoverConfigPost {
    val offered = posted["ha_url_handover"]?.trim().orEmpty()
    if (offered.isBlank()) {
        // Saving an address directly is how the correction card is answered, so it closes the failed
        // handover out. Without this the attempted address and its reason would outlive the problem
        // they describe, with no way to clear them: the reason is refused from the network by design,
        // so nothing else could ever retract it.
        if (posted["ha_url"]?.isNotBlank() == true) {
            return HandoverConfigPost(
                Parameters.build {
                    for (name in posted.names()) {
                        if (name == "ha_url_handover") continue
                        posted.getAll(name).orEmpty().forEach { append(name, it) }
                    }
                    append("ha_url_handover", "")
                    append("ha_url_handover_reason", "")
                },
            )
        }
        return HandoverConfigPost(posted)
    }
    val carried = Parameters.build {
        for (name in posted.names()) {
            if (name == "ha_url_handover" || name == "ha_setup_handover") continue
            posted.getAll(name).orEmpty().forEach { append(name, it) }
        }
        // A handed-over address means Home Assistant deployed this panel whether or not the address
        // then answers, so provenance is recorded here rather than left to the caller to remember.
        // Every step that skips a question reads this marker, never the address's presence.
        append("ha_setup_handover", "true")
    }
    if (currentHaUrl.isNotBlank()) return HandoverConfigPost(carried)
    val (chosen, outcome) = firstVerified(offered, alternates, verify)
    val augmented = Parameters.build {
        for (name in carried.names()) {
            carried.getAll(name).orEmpty().forEach { append(name, it) }
        }
        if (outcome.verified) {
            append("ha_url", chosen)
            append("ha_url_handover", "")
            append("ha_url_handover_reason", "")
        } else {
            append("ha_url_handover", offered)
            append("ha_url_handover_reason", outcome.reason)
        }
    }
    return HandoverConfigPost(augmented, outcome, chosen)
}

/** Every other address of the same Home Assistant, space-separated, for the panel to try (see [rewritePostForHandover]). */
internal const val HANDOVER_ALTERNATES_KEY = "ha_url_handover_alternates"

/** Same number as Panel Assistant's `MAX_HANDOVER_ALTERNATES`: it bounds the form and the parallel probes. */
internal const val MAX_HANDOVER_ALTERNATES = 16

internal data class ConfigDiscoverySuggestions(
    val haUrl: String = "",
    /** Why [haUrl] is empty, so setup can explain rather than leave the user at a blank field. */
    val haDiscovery: DiscoveryResult = DiscoveryResult(),
)

internal const val SETUP_PRESENCE_HEADER = "X-ha-paneld-setup-presence"

internal const val SETUP_PRESENCE_ACTIVE = "active"
