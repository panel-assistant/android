package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * URL navigate — push a URL for the panel to display. ha-paneld cannot reach into the HA Companion
 * WebView directly, so it fires an `ACTION_VIEW` intent, by default targeting the Companion package
 * so the dashboard host handles it; falls back to the system default if that fails.
 *
 * ⚠️ Least-certain capability: depends on the Companion build honouring an external VIEW intent for
 * in-app navigation. Needs on-device validation; the deep-link contract may differ by build. The
 * uniform `text.<panel>_navigate` MQTT entity is stable regardless of how this resolves.
 */
class NavigateController(private val context: Context) {

    fun navigate(url: String, companionPackage: String? = DEFAULT_COMPANION) {
        if (CompanionDataOperationGate.blocksImplicitNavigation()) {
            Log.i(TAG, "navigate suppressed while Companion data operation is active")
            return
        }
        val targeted = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (companionPackage != null) setPackage(companionPackage)
        }
        try {
            context.startActivity(targeted)
            Log.d(TAG, "navigate -> $url (pkg=$companionPackage)")
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Log.d(TAG, "navigate -> $url (system default)")
            } catch (e2: Exception) {
                Log.w(TAG, "navigate failed for $url", e2)
            }
        }
    }

    companion object {
        private const val TAG = "ha-paneld/navigate"
        private const val DEFAULT_COMPANION = "io.homeassistant.companion.android.minimal"

        internal fun homeUrl(path: String): String = "homeassistant://navigate$path"
    }
}
