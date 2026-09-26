package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.WebViewSpec
import io.github.maxlyth.hapaneld.http.PanelHealth

/**
 * Auto-heals the panel's **System WebView** when it's too old to render the Home Assistant dashboard.
 *
 * These panels have no Play Store, so a stock WebView (often Chromium ~83–107) leaves the HA frontend
 * **blank** and there's no built-in way to update it — the single most common first-run failure. When
 * the profile declares a known-good build ([DeviceProfile.recommendedWebView]), ha-paneld downloads it
 * from the `webview-mirror` release and installs it over root via the pinned-signer [AppInstaller]
 * (the same path the Companion updater uses). The build always uses the `com.android.webview` package,
 * so the framework auto-selects it as the provider — no allowlist edit, no extra app.
 *
 * The install-or-not decision keys on the **real** Chromium engine version (from the WebView UA), NOT
 * the package versionName — a Cromite/LineageOS build stamps the OEM stock version to clear a
 * signature-locked provider gate, so the package version lies. Network + root: call OFF the main thread.
 */
object WebViewInstaller {
    const val WEBVIEW_PKG = "com.android.webview"
    private const val TAG = "ha-paneld/webview"

    /**
     * The result of a [heal] attempt. [status] is the exact human-readable message shown in the UI /
     * InstallProgress; callers switch on the variant rather than re-parsing it.
     */
    sealed interface HealResult {
        val status: String
        val presentation: InstallPresentation?
        /** A no-op decision (no recommendation, engine already fine, or the pin is not newer). */
        data class NoAction(
            override val status: String,
            override val presentation: InstallPresentation? = null,
        ) : HealResult
        /** The recommended WebView was installed and the dashboard should be reactivated. */
        data class Installed(
            override val status: String,
            override val presentation: InstallPresentation? = null,
        ) : HealResult
        /** The install was attempted but failed. [terminal] = a durable rejection (retrying the same pin
         *  cannot help), as opposed to a transient failure that a later tick may clear. */
        data class Failed(
            override val status: String,
            val terminal: Boolean,
            override val presentation: InstallPresentation? = null,
        ) : HealResult
    }

    /** What [heal] should do — a pure decision so the gating logic is unit-testable without a device. */
    sealed class Decision {
        /** No known-good build for this panel → leave the WebView alone. */
        object NoRecommendation : Decision()
        /** The engine already renders HA (≥ threshold) or is unknown → don't touch it. */
        data class UpToDate(val engineMajor: Int?) : Decision()
        /** The recommended build is no newer than what's already running → nothing to gain. */
        data class NotNewer(val version: String) : Decision()
        /** Install [spec]. */
        data class Install(val spec: WebViewSpec) : Decision()
    }

    /**
     * Decide whether to install. [engineVersion] is the real four-part Chromium version from the
     * WebView UA (null = unknown). [force] allows reinstalling an equal build from the manual
     * "Update WebView" button. [autoUpdate] is the
     * scheduled auto-update intent: it drops the "engine already renders HA → leave it" short-circuit so
     * a WORKING engine still advances to a newer pinned build — the heal path (autoUpdate=false) instead
     * only touches an engine genuinely below [minChromium]. Either way an unknown engine is never
     * disturbed and a pin that isn't newer is a no-op, so there's no reinstall loop from [decide] alone.
     */
    fun decide(rec: WebViewSpec?, engineVersion: String?, minChromium: Int, force: Boolean, autoUpdate: Boolean = false): Decision {
        if (rec == null) return Decision.NoRecommendation
        val engine = versionParts(engineVersion) ?: return Decision.UpToDate(null)
        val recommended = versionParts(rec.version) ?: return Decision.NotNewer(rec.version)
        if (!force && !autoUpdate && engine[0] >= minChromium) return Decision.UpToDate(engine[0])
        return if (isOlder(engine, recommended) || (force && engine == recommended)) {
            Decision.Install(rec)
        } else Decision.NotNewer(rec.version)
    }

    private fun versionParts(version: String?): List<Int>? {
        val parts = version?.split('.') ?: return null
        if (parts.size != 4) return null
        return parts.map {
            if (it.isEmpty() || !it.all(Char::isDigit)) return null
            it.toIntOrNull() ?: return null
        }
    }

    private fun isOlder(engine: List<Int>, recommended: List<Int>): Boolean =
        engine.zip(recommended).firstOrNull { (current, pin) -> current != pin }
            ?.let { (current, pin) -> current < pin } == true

    /**
     * Loop guard for the scheduled auto-update ([io.github.maxlyth.hapaneld.PaneldService] `autoUpdateWebView`):
     * skip re-attempting the same pinned [recVersion] once a prior tick recorded it AND the running
     * [engineVersion] still hasn't reached [recVersion] — i.e. the provider isn't actually switching
     * (variant / signature-locked hardware where `pm install` can't change the WebView signer), so
     * re-downloading ~90 MB (and, on the built-in renderer, restarting the process) every 24 h tick would be
     * pointless. A pin bump ([recVersion] differs from the recorded one) clears it and re-attempts; an
     * unknown engine ([engineVersion] == null) counts as "still not switched" so a records-then-can't-verify
     * panel also stops retrying. Kept pure + unit-tested because this predicate is the only thing standing
     * between an opt-in panel and a daily re-download/restart loop, and a regression here stays green.
     */
    fun shouldSkipAutoUpdate(lastVersion: String, recVersion: String, engineVersion: String?): Boolean {
        if (lastVersion != recVersion) return false
        val engine = versionParts(engineVersion) ?: return true
        val recommended = versionParts(recVersion) ?: return true
        return isOlder(engine, recommended)
    }

    /** Whether an auto-update result is durable evidence that retrying the same pin cannot help. Network,
     *  staging, storage, and temporarily unavailable privilege failures remain retryable on the next tick;
     *  successful/no-op decisions and package-manager rejection of a provider swap are terminal until the
     *  profile pin changes or the user explicitly retries. An unknown engine is not durable evidence of
     *  being current: the UA read may have timed out, so the next tick must be allowed to try again.
     *  The terminal-vs-retryable classification is the
     *  producer's ([InstallOutcome]), carried on [HealResult.Failed.terminal]. */
    internal fun shouldRecordAutoAttempt(result: HealResult, engineVersion: String?): Boolean =
        versionParts(engineVersion) != null && when (result) {
            is HealResult.Failed -> result.terminal
            is HealResult.NoAction, is HealResult.Installed -> true
        }

    /** Heal the WebView per [decide], returning a typed [HealResult] whose [HealResult.status] is a short
     *  human status ("OK: …" on a successful install). [autoUpdate] = the scheduled update-to-pin path
     *  (advance a working engine to a newer pin). */
    suspend fun heal(context: Context, profile: DeviceProfile, engineVersion: String?, force: Boolean = false, autoUpdate: Boolean = false): HealResult =
        when (val d = decide(profile.recommendedWebView, engineVersion, PanelHealth.MIN_CHROMIUM, force, autoUpdate)) {
            is Decision.NoRecommendation -> HealResult.NoAction(
                "no known-good WebView for this panel",
                presentation("managed-no-recommendation"),
            )
            is Decision.UpToDate -> HealResult.NoAction(
                "up to date (Chromium ${d.engineMajor ?: "?"})",
                presentation("managed-up-to-date", "current" to "Chromium ${d.engineMajor ?: "?"}"),
            )
            is Decision.NotNewer -> HealResult.NoAction(
                "already current (${d.version})",
                presentation("managed-no-newer", "current" to d.version),
            )
            is Decision.Install -> {
                Log.i(TAG, "healing WebView → ${d.spec.version} (engine was $engineVersion)")
                when (val outcome = AppInstaller.install(
                    context,
                    d.spec.url,
                    AppInstaller.Pin(WEBVIEW_PKG, d.spec.certSha256, d.spec.apkSha256),
                    allowShizuku = false,
                )) {
                    InstallOutcome.Succeeded ->
                        HealResult.Installed(
                            "OK: installed WebView ${d.spec.version} — reloading the dashboard",
                            presentation(
                                "managed-install-committed",
                                "version" to d.spec.version,
                            ),
                        )
                    is InstallOutcome.Rejected -> HealResult.Failed(
                        outcome.message,
                        terminal = true,
                        presentation = outcome.presentation,
                    )
                    is InstallOutcome.Retryable -> HealResult.Failed(
                        outcome.message,
                        terminal = false,
                        presentation = outcome.presentation,
                    )
                }
            }
        }

    private fun presentation(code: String, vararg params: Pair<String, String>): InstallPresentation? =
        InstallPresentation.create(code, mapOf("component" to "webview", *params))
}
