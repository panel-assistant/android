package io.panelassistant.android.http

/**
 * Coalesced renderer work caused by a committed configuration change. The built-in renderer reconciles
 * what it bakes in or was admitted with itself ([io.panelassistant.android.config.RendererApply]), so a
 * change to those only brings it to the front ([relaunchBuiltin]); only a change the next page load
 * carries ([io.panelassistant.android.config.RendererApply.RELOAD]) asks it to reload.
 */
internal data class RendererConfigEffects(
    val dashboardChanged: Boolean,
    val reloadBuiltin: Boolean,
    val relaunchBuiltin: Boolean,
    val darkMode: Boolean?,
    /** [relaunchBuiltin] is for a new home dashboard alone, which a renderer held before its first load has
     *  nothing to show for ([io.panelassistant.android.setupQuestionHoldsRenderer]). */
    val relaunchForHomeOnly: Boolean = false,
) {
    companion object {
        private val CREDENTIAL_KEYS = setOf(
            "ha_url", "ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id",
        )

        private fun changed(previous: Map<String, String>, accepted: Map<String, String>, key: String): Boolean {
            val next = accepted[key] ?: return false
            val before = previous[key]
            return if (key == "ha_url") next.trimEnd('/') != before?.trimEnd('/') else next != before
        }

        fun credentialsChanged(previous: Map<String, String>, accepted: Map<String, String>): Boolean {
            fun changed(key: String): Boolean = changed(previous, accepted, key)
            val accessReplacesRefresh = accepted["ha_token"]?.isNotEmpty() == true &&
                "ha_refresh_token" !in accepted && previous["ha_refresh_token"].orEmpty().isNotEmpty()
            val urlClearDropsCredentials = accepted["ha_url"]?.isEmpty() == true &&
                listOf("ha_token", "ha_refresh_token", "ha_client_id").any { previous[it].orEmpty().isNotEmpty() }
            return CREDENTIAL_KEYS.any(::changed) || accessReplacesRefresh || urlClearDropsCredentials
        }

        /**
         * The renderer work a committed change from [previous] to [accepted] needs. A caller whose credential
         * group or home path has its own owner passes that owner's verdict as [credentialChanged] or
         * [homeChanged].
         */
        fun between(
            previous: Map<String, String>,
            accepted: Map<String, String>,
            credentialChanged: Boolean = credentialsChanged(previous, accepted),
            homeChanged: Boolean = changed(previous, accepted, "home_dashboard"),
        ): RendererConfigEffects {
            fun changed(key: String): Boolean = changed(previous, accepted, key)
            return coalesce(
                dashboardChanged = changed("dashboard_package"),
                credentialChanged = credentialChanged,
                zoomChanged = changed("dashboard_zoom"),
                fullscreenChanged = changed("dashboard_fullscreen"),
                nativeKioskChanged = changed("dashboard_native_kiosk"),
                homeChanged = homeChanged,
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
            nativeKioskChanged: Boolean = false,
            // A new home path only took effect on the next incidental reload, so editing it in the UI
            // appeared to do nothing. A change reloads the built-in renderer onto the new home now.
            homeChanged: Boolean = false,
            darkMode: Boolean?,
            // The colour-scheme policy is baked into a document-start script, which cannot be replaced
            // in a live WebView, so a change must reach a fresh page load rather than a foregrounding.
            themePolicyChanged: Boolean = false,
        ): RendererConfigEffects {
            // A reload on top of the renderer's own rebuild for a credential, home or theme change would load
            // the rebuilt page a second time; those are foregrounded and the renderer reconciles them.
            val reload = !dashboardChanged && (zoomChanged || darkMode != null)
            val relaunchForOther = credentialChanged || themePolicyChanged || fullscreenChanged || nativeKioskChanged
            val relaunch = !dashboardChanged && !reload && (relaunchForOther || homeChanged)
            return RendererConfigEffects(dashboardChanged, reload, relaunch, darkMode, relaunchForHomeOnly = relaunch && !relaunchForOther)
        }
    }
}
