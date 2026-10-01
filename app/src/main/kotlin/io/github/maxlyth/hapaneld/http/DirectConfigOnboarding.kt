package io.github.maxlyth.hapaneld.http

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.HaDiscovery
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.util.closeBody
import io.ktor.http.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves offered or discovered HA addresses before the ordinary Configure transaction. */
internal class DirectConfigOnboarding(
    private val config: Config,
    private val configDiscoverySuggestions: () -> ConfigDiscoverySuggestions,
    private val onDiscovery: (DiscoveryResult) -> Unit,
    private val effectiveDashboardIsBuiltin: () -> Boolean,
) {
    data class OnboardingConfigPost(
        val parameters: Parameters,
        val haUrlDiscovered: Boolean,
        /** Carried so the response can explain a failed discovery without browsing a second time. */
        val haDiscovery: DiscoveryResult = DiscoveryResult(),
    )

    /**
     * Verify a handed-over Home Assistant URL from this panel's own network before accepting it.
     *
     * Rewrites the posted parameters rather than persisting separately, so an accepted address takes
     * exactly the same commit and live-apply path as one a person typed — the renderer reconfigure, the
     * read-back check and the mutation plan all behave identically. This mirrors
     * [augmentPostWithDiscoveredHaUrlForMqttOnboarding], which fills the same field from discovery.
     *
     * Three outcomes:
     *  - **already configured** — the panel has a URL, so the handover is dropped untouched. Adoption is
     *    re-runnable, and a retry must converge rather than overwrite a working panel.
     *  - **verified** — promoted to `ha_url`, and the handover field and its reason are cleared.
     *  - **failed** — the address is kept, with the reason, so the wizard can offer a correction that
     *    shows what was tried instead of an empty field.
     *
     * The provenance marker `ha_setup_handover` is NOT touched here. It rides the POST as an ordinary
     * key and is stored whatever this decides, because a handed-over address that did not answer still
     * means Home Assistant deployed this panel.
     */
    suspend fun augmentPostWithVerifiedHandover(posted: Parameters): HandoverConfigPost =
        rewritePostForHandover(posted, config.haUrl) { offered ->
            withContext(Dispatchers.IO) { verifyHandedOverHaUrl(offered) }
                .also { Log.i(TAG, "config: handed-over Home Assistant URL verification ${it.name.lowercase()}") }
        }

    /**
     * One bounded unauthenticated `GET {url}/api/` from the panel itself.
     *
     * Home Assistant answers that with 401; anything else answering means something that is not Home
     * Assistant holds the address. See [HaUrlHandover] for why a 200 there is a failure rather than a
     * weaker success.
     */
    private fun verifyHandedOverHaUrl(url: String): HaUrlHandover.Outcome {
        val probe = HaUrlHandover.probeUrl(url) ?: return HaUrlHandover.Outcome.INVALID
        return runCatching {
            val connection = java.net.URL(probe).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = HaUrlHandover.CONNECT_TIMEOUT_MS
                connection.readTimeout = HaUrlHandover.READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                HaUrlHandover.classifyStatus(connection.responseCode).also { connection.closeBody() }
            } finally {
                connection.disconnect()
            }
        }.getOrElse(HaUrlHandover::classifyFailure)
    }

    suspend fun augmentPostWithDiscoveredHaUrlForMqttOnboarding(posted: Parameters): OnboardingConfigPost {
        if (!shouldDiscoverHaUrlForMqttOnboarding(config.haUrl, posted)) {
            return OnboardingConfigPost(posted, false)
        }
        val suggestions = withContext(Dispatchers.IO) { configDiscoverySuggestions() }
        val found = suggestions.haDiscovery
        onDiscovery(found)
        val discovered = suggestions.haUrl.trim()
        if (discovered.isBlank() || config.haUrl.isNotBlank()) return OnboardingConfigPost(posted, false, found)
        val spec = SettingsRegistry.spec("ha_url") ?: return OnboardingConfigPost(posted, false, found)
        val normalized = when (val validation = SettingValue.validate(spec, discovered)) {
            is Validation.Ok -> validation.normalized
            is Validation.Bad -> {
                Log.w(TAG, "config: ignoring invalid discovered Home Assistant URL during MQTT onboarding")
                return OnboardingConfigPost(posted, false, found)
            }
        }
        val augmented = Parameters.build {
            for (name in posted.names()) {
                posted.getAll(name).orEmpty().forEach { append(name, it) }
            }
            append("ha_url", normalized)
        }
        Log.i(TAG, "config: discovered Home Assistant URL during MQTT onboarding")
        return OnboardingConfigPost(augmented, true, found)
    }

    /**
     * What to tell the user immediately after they save MQTT settings, so the next step is never a guess.
     *
     * The message must NOT be conditional on discovery having succeeded. It previously returned null
     * whenever `ha_url` was still blank, which is exactly the state a panel on a different network segment
     * from Home Assistant is always left in: mDNS is link-local, discovery cannot reach across the
     * boundary, so the URL stayed empty and the user got no message at all — silence at the precise moment
     * they most needed direction. Now a failed discovery produces guidance *and* the reason for it.
     */
    fun mqttOnboardingSignInMessage(
        haUrlDiscovered: Boolean,
        changedKeys: Collection<String>,
        discovery: DiscoveryResult,
    ): String? {
        val onboardingKeys = setOf("mqtt_broker", "mqtt_user", "mqtt_password", "ha_url", "dashboard_package")
        if (!haUrlDiscovered && changedKeys.none { it in onboardingKeys }) return null
        if (!effectiveDashboardIsBuiltin()) return null
        if (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()) return null
        if (config.haUrl.isBlank()) {
            val next = "MQTT settings saved. Next: enter the Home Assistant URL in the Home Assistant " +
                "connection card below."
            val why = HaDiscovery.unavailableExplanation(discovery)
                ?: return "$next It was not found automatically on this network."
            return "$next It could not be found automatically because $why."
        }
        return "MQTT settings saved — preparing Home Assistant sign-in. " +
            "The panel should show the sign-in workflow; you can also use Browser sign-in below."
    }

    private companion object {
        const val TAG = "ha-paneld/http"
    }
}
