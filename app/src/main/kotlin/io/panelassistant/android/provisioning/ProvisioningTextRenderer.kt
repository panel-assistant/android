package io.panelassistant.android.provisioning

/** Core-owned, bounded terminal prose. Profile-authored notes and raw observation text never enter it. */
internal object ProvisioningTextRenderer {
    private const val MAX_IDENTITY_CHARS = 96
    private const val MAX_OUTPUT_CHARS = 8 * 1024

    fun render(plan: ProvisioningPlan): String {
        val panel = sanitizeIdentity(plan.profile.displayName).ifEmpty { "Unknown panel" }
        val profileId = sanitizeIdentity(plan.profile.ref.id).ifEmpty { "unknown" }
        val lines = mutableListOf(
            "ha-paneld provisioning plan",
            "Panel: $panel",
            "Profile: $profileId@${plan.profile.ref.revision.take(12)} (${plan.profile.origin.wireName()})",
            "Status: ${if (plan.state == ProvisioningPlanState.SATISFIED) "satisfied" else "attention needed"}",
        )
        if (plan.items.isEmpty()) {
            lines += "No profile-specific provisioning guidance."
        } else {
            plan.items.forEach { item ->
                lines += "- ${item.title()} [${item.importance.wireName()}]: ${item.guidance()}"
            }
        }
        return lines.joinToString(separator = "\n", postfix = "\n").take(MAX_OUTPUT_CHARS)
    }

    internal fun sanitizeIdentity(raw: String): String {
        val withoutAnsi = raw.replace(ANSI_ESCAPE, "")
        val safe = buildString {
            withoutAnsi.codePoints().forEach { codePoint ->
                if (!codePoint.isUnsafeTerminalCodePoint()) appendCodePoint(codePoint)
            }
        }.trim()
        return safe.codePoints()
            .limit(MAX_IDENTITY_CHARS.toLong())
            .toArray()
            .let { String(it, 0, it.size) }
    }

    private fun ProvisioningPlanItem.title(): String = when (id) {
        "access.helper" -> "Root helper"
        "software.webview" -> "System WebView"
        else -> "Provisioning item"
    }

    private fun ProvisioningPlanItem.guidance(): String = when (reasonCode) {
        "helper_compatible" -> "The compatible helper is available."
        "daemon_driver_without_helper" -> "This panel needs the root helper for profiled hardware controls; install it from the trusted host."
        "sandbox_controls_without_helper" ->
            "Display sizing, CPU governor, screenshots and performance readings need the root helper on this panel; install it from the trusted host where a root route exists."
        "helper_incompatible" -> "The responding helper is incompatible; replace it from the trusted host."
        "helper_identity_unavailable" -> "A helper responds, but its compatibility cannot be verified."
        "helper_observation_not_ready", "helper_probe_failed", "helper_probe_unsupported" ->
            "The helper state could not be determined."
        "webview_recommendation_satisfied" -> "The active engine satisfies this profile's recommendation."
        "webview_outdated" -> "The active engine is older than this profile's release-owned recommendation."
        "webview_missing" -> "No active engine was detected; a release-owned recommendation is available."
        "webview_version_unreadable", "webview_observation_not_ready", "webview_probe_failed",
        "webview_probe_unsupported", "webview_identity_unavailable" ->
            "The active engine could not be compared with this profile's recommendation."
        else -> "Review this item in ha-paneld."
    }

    private fun ProvisioningImportance.wireName(): String = name.lowercase()

    private fun io.panelassistant.android.device.profile.ProfileOrigin.wireName(): String = name.lowercase()

    private fun Int.isUnsafeTerminalCodePoint(): Boolean =
        this in 0x00..0x1f ||
            this in 0x7f..0x9f ||
            this in BIDI_AND_LAYOUT_CONTROLS ||
            this == 0x2028 ||
            this == 0x2029

    private val ANSI_ESCAPE = Regex(
        pattern = """\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)?)""",
    )

    private val BIDI_AND_LAYOUT_CONTROLS = setOf(
        0x061c,
        0x200e,
        0x200f,
        0x202a,
        0x202b,
        0x202c,
        0x202d,
        0x202e,
        0x2066,
        0x2067,
        0x2068,
        0x2069,
    )
}
