package io.github.maxlyth.hapaneld.http

import io.panelassistant.android.BuildConfig
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.HaAuthSnapshot
import io.github.maxlyth.hapaneld.stableOwner
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.sensors.HaCurrentUserClient
import io.github.maxlyth.hapaneld.util.HaLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HaOAuthRuntime(
    private val config: Config,
    private val catalogueLoader: () -> CatalogueLoader,
    private val haOAuthExchange: (String, String, String) -> HaLink.AuthorizationCodeExchange,
    private val autoBrightnessHttpApi: AutoBrightnessHttpApi,
    private val applyAccepted: suspend (
        accepted: Map<String, String>, expectedHaAuthOwner: HaAuthOwner, expectedHaOAuthEpoch: Long,
    ) -> ApplyAcceptedResult,
    private val setupNeedsUser: () -> Boolean,
) {
    private val haOAuthFlow = HaOAuthFlow()
    private val haOAuthStartLock = Any()
    private val haCurrentUser = HaCurrentUserClient(config)

    fun pendingCount(): Int = haOAuthFlow.pendingCount()

    fun routes(): HaOAuthRouteDependencies =
        HaOAuthRouteDependencies(
            panelPort = config.httpPort,
            start = { haUrl, panelOrigin -> startHaOAuth(haUrl, panelOrigin) },
            startWithContext = { haUrl, panelOrigin, context ->
                startHaOAuth(haUrl, panelOrigin, context)
            },
            startContext = { selection ->
                val strings = catalogueLoader().strings(selection.locale)
                HaOAuthStartContext(
                    locale = selection.locale,
                    returnSurface = selection.returnSurface,
                    preserveExplicitEnglish = selection.preserveExplicitEnglish,
                    copy = haOAuthCallbackCopy(strings),
                    contentLanguages = strings.languages(setOf("oauth.callback.")).toSet(),
                )
            },
            allowPseudoLocale = BuildConfig.DEBUG,
            claim = haOAuthFlow::claim,
            exchange = { attempt, code -> withContext(Dispatchers.IO) {
                haOAuthExchange(attempt.haUrl, code, attempt.clientId)
            } },
            complete = ::completeHaOAuth,
            status = haCurrentUser::status,
            // Same journey rule as the QR: an unfinished panel returns to guided setup so
            // the wizard can show the render-proof/completion step, a finished one to
            // Configure. Evaluated at callback time, after the token has committed.
            successReturnPath = {
                if (!setupNeedsUser()) "/configure#cfg-ha_url" else "/setup"
            },
        )

    private fun startHaOAuth(
        haUrl: String,
        panelOrigin: String,
        context: HaOAuthStartContext = HaOAuthStartContext.ENGLISH_CONFIGURE,
    ): HaOAuthStart = synchronized(haOAuthStartLock) {
        val authority = config.beginHaOAuthAttempt()
        haOAuthFlow.start(haUrl, panelOrigin, authority, context)
    }

    private fun haOAuthCallbackCopy(strings: AppStrings): HaOAuthCallbackCopy = HaOAuthCallbackCopy(
        successHeading = strings.get("oauth.callback.success_heading"),
        failureHeading = strings.get("oauth.callback.failure_heading"),
        continueAction = strings.get("oauth.callback.action.continue"),
        backToConfigureAction = strings.get("oauth.callback.action.back_to_configure"),
        backToSetupAction = strings.get("oauth.callback.action.back_to_setup"),
        cancelled = strings.get("oauth.callback.cancelled"),
        invalidCode = strings.get("oauth.callback.invalid_code"),
        rejected = strings.get("oauth.callback.rejected"),
        transient = strings.get("oauth.callback.transient"),
        stale = strings.get("oauth.callback.stale"),
        commitFailed = strings.get("oauth.callback.commit_failed"),
        configured = strings.get("oauth.callback.configured"),
        reloadMayBeNeeded = strings.get("oauth.callback.reload_may_be_needed"),
        ambientWarning = strings.get("oauth.callback.ambient_warning"),
    )

    private suspend fun completeHaOAuth(
        attempt: HaOAuthAttempt,
        tokens: HaLink.OAuthTokens,
    ): HaOAuthCompletion {
        val expiry = System.currentTimeMillis() / 1_000L + tokens.expiresInSec
        val accepted = linkedMapOf(
            "ha_url" to attempt.haUrl,
            "ha_token" to tokens.accessToken,
            "ha_refresh_token" to tokens.refreshToken,
            "ha_token_expiry" to expiry.toString(),
            "ha_client_id" to attempt.clientId,
        )
        val newOwner = HaAuthSnapshot(
            attempt.haUrl,
            tokens.accessToken,
            tokens.refreshToken,
            expiry,
            attempt.clientId,
        ).stableOwner()
        val result = runCatching {
            applyAccepted(
                accepted,
                attempt.expectedOwner,
                attempt.expectedEpoch,
            )
        }
        if (result.isFailure) {
            return if (config.haAuthSnapshot().stableOwner() == newOwner &&
                config.isHaOAuthAttemptCurrent(attempt.expectedEpoch)
            ) {
                HaOAuthCompletion.Success(reloadMayBeNeeded = true)
            } else {
                HaOAuthCompletion.CommitFailed
            }
        }
        when (result.getOrThrow()) {
            ApplyAcceptedResult.Stale -> return HaOAuthCompletion.Stale
            ApplyAcceptedResult.CommitFailed,
            is ApplyAcceptedResult.CompatibilityRefused -> return HaOAuthCompletion.CommitFailed
            ApplyAcceptedResult.Applied -> Unit
        }
        val ambientWarning = runCatching {
            config.autoBrightnessHaEntity.takeIf(String::isNotBlank)?.let { entityId ->
                val validation = autoBrightnessHttpApi.validateHaSource(entityId)
                validation.action.statusCode !in 200..299
            } ?: false
        }.getOrDefault(true)
        return HaOAuthCompletion.Success(ambientWarning = ambientWarning)
    }
}
