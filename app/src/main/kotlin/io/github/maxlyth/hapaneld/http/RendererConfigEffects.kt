package io.github.maxlyth.hapaneld.http

/** Coalesced renderer work caused by a committed configuration change. */
internal data class RendererConfigEffects(
    val dashboardChanged: Boolean,
    val reloadBuiltin: Boolean,
    val relaunchBuiltin: Boolean,
    val darkMode: Boolean?,
) {
    companion object {
        private val CREDENTIAL_KEYS = setOf(
            "ha_url", "ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id",
        )

        fun credentialsChanged(previous: Map<String, String>, accepted: Map<String, String>): Boolean {
            fun changed(key: String): Boolean {
                val next = accepted[key] ?: return false
                val before = previous[key]
                return if (key == "ha_url") next.trimEnd('/') != before?.trimEnd('/') else next != before
            }
            val accessReplacesRefresh = accepted["ha_token"]?.isNotEmpty() == true &&
                "ha_refresh_token" !in accepted && previous["ha_refresh_token"].orEmpty().isNotEmpty()
            val urlClearDropsCredentials = accepted["ha_url"]?.isEmpty() == true &&
                listOf("ha_token", "ha_refresh_token", "ha_client_id").any { previous[it].orEmpty().isNotEmpty() }
            return CREDENTIAL_KEYS.any(::changed) || accessReplacesRefresh || urlClearDropsCredentials
        }

        fun between(previous: Map<String, String>, accepted: Map<String, String>): RendererConfigEffects {
            fun changed(key: String): Boolean {
                val next = accepted[key] ?: return false
                val before = previous[key]
                return if (key == "ha_url") next.trimEnd('/') != before?.trimEnd('/') else next != before
            }
            return coalesce(
                dashboardChanged = changed("dashboard_package"),
                credentialChanged = credentialsChanged(previous, accepted),
                zoomChanged = changed("dashboard_zoom"),
                fullscreenChanged = changed("dashboard_fullscreen"),
                overscrollChanged = changed("dashboard_overscroll"),
                nativeKioskChanged = changed("dashboard_native_kiosk"),
                homeChanged = changed("home_dashboard"),
                darkMode = accepted["dark_mode"]?.toBooleanStrictOrNull()
                    ?.takeIf { changed("dark_mode") && android.os.Build.VERSION.SDK_INT < 29 },
                // Unlike dark_mode this has no SDK gate: the policy is the only lever that re-themes
                // Home Assistant, and it is meaningful on every panel (Android 10+ has no HA theme
                // lever at all today, which is half of what this setting exists to fix).
                themePolicyChanged = changed("dashboard_theme"),
            )
        }

        fun coalesce(
            dashboardChanged: Boolean,
            credentialChanged: Boolean,
            zoomChanged: Boolean,
            fullscreenChanged: Boolean,
            overscrollChanged: Boolean,
            nativeKioskChanged: Boolean = false,
            // A new home path only took effect on the next incidental reload, so editing it in the UI
            // appeared to do nothing. A change reloads the built-in renderer onto the new home now.
            homeChanged: Boolean = false,
            darkMode: Boolean?,
            // The colour-scheme policy is baked into a document-start script, which cannot be replaced
            // in a live WebView, so a change must reach a fresh page load rather than a foregrounding.
            themePolicyChanged: Boolean = false,
        ): RendererConfigEffects {
            val reload = !dashboardChanged &&
                (credentialChanged || zoomChanged || homeChanged || darkMode != null || themePolicyChanged)
            val relaunch = !dashboardChanged && !reload &&
                (fullscreenChanged || overscrollChanged || nativeKioskChanged)
            return RendererConfigEffects(dashboardChanged, reload, relaunch, darkMode)
        }
    }
}
