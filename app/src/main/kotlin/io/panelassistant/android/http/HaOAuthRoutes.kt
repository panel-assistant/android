package io.panelassistant.android.http

import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.sensors.HaCurrentUserStatus
import io.panelassistant.android.util.HaLink
import io.panelassistant.android.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException

internal const val MAX_HA_OAUTH_START_BODY_BYTES = 4L * 1024L
internal const val MAX_HA_OAUTH_CODE_CHARS = 4 * 1024
internal const val HA_OAUTH_UI_LOCALE_FIELD = "ui_locale"
internal const val HA_OAUTH_RETURN_SURFACE_FIELD = "return_surface"
internal const val HA_OAUTH_PRESERVE_ENGLISH_FIELD = "preserve_explicit_english"

internal sealed class HaOAuthCompletion {
    data class Success(
        val ambientWarning: Boolean = false,
        val reloadMayBeNeeded: Boolean = false,
    ) : HaOAuthCompletion()

    object Stale : HaOAuthCompletion()
    object CommitFailed : HaOAuthCompletion()
}

internal data class HaOAuthRouteDependencies(
    val panelPort: Int,
    val start: (haUrl: String, panelOrigin: String) -> HaOAuthStart,
    /** Optional context-aware start used by localized callers. Keeping the legacy start preserves the
     * on-panel and Configure paths until their server wiring supplies trusted request context. */
    val startWithContext: ((haUrl: String, panelOrigin: String, context: HaOAuthStartContext) -> HaOAuthStart)? = null,
    /** Add catalogue copy to an already canonicalized, closed request selection. */
    val startContext: (selection: HaOAuthStartSelection) -> HaOAuthStartContext = {
        HaOAuthStartContext(
            locale = it.locale,
            returnSurface = it.returnSurface,
            preserveExplicitEnglish = it.preserveExplicitEnglish,
        )
    },
    /** Debug builds alone may admit the opt-in pseudolocale. */
    val allowPseudoLocale: Boolean = false,
    val claim: (state: String, panelOrigin: String) -> HaOAuthClaim,
    val exchange: suspend (attempt: HaOAuthAttempt, code: String) -> HaLink.AuthorizationCodeExchange,
    val complete: suspend (attempt: HaOAuthAttempt, tokens: HaLink.OAuthTokens) -> HaOAuthCompletion,
    val status: suspend () -> HaCurrentUserStatus = { HaCurrentUserStatus.NotConfigured },
    /** Where a completed sign-in should send the browser: the setup wizard while setup is unfinished,
     *  otherwise Configure. Without it the callback dead-ends on a static "Back to Configure" page and
     *  a guided-setup user is stranded there instead of returning to their next step. */
    val successReturnPath: () -> String = { "/configure#cfg-ha_url" },
)

/** Browser OAuth transport. Mount beneath the guarded `/api/v1` route; credential persistence and
 * live reconfiguration remain owned by the server through the injected completion callback. */
internal fun Route.haOAuthRoutes(dependencies: HaOAuthRouteDependencies) {
    route("/ha/oauth") {
        get("/status") {
            call.noStoreHaOAuth()
            val status = try {
                dependencies.status()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                HaCurrentUserStatus.Unavailable
            }
            val json = when (status) {
                is HaCurrentUserStatus.Connected ->
                    """{"phase":"connected","display_name":${status.displayName?.let(Json::str) ?: "null"},"language":${status.language?.let(Json::str) ?: "null"}}"""
                HaCurrentUserStatus.NotConfigured -> """{"phase":"not_configured"}"""
                HaCurrentUserStatus.Rejected -> """{"phase":"rejected"}"""
                HaCurrentUserStatus.Unavailable -> """{"phase":"unavailable"}"""
            }
            call.respondText(json, ContentType.Application.Json)
        }
        post("/start") {
            call.noStoreHaOAuth()
            val parameters = receiveBoundedFormParameters(call, MAX_HA_OAUTH_START_BODY_BYTES) ?: return@post
            val rawUrl = parameters["ha_url"].orEmpty()
            val spec = requireNotNull(SettingsRegistry.spec("ha_url"))
            val haUrl = when (val validation = SettingValue.validate(spec, rawUrl)) {
                is Validation.Ok -> validation.normalized.takeIf(String::isNotBlank)
                is Validation.Bad -> null
            }
            if (haUrl == null) {
                call.respondText(
                    """{"ok":false,"error":"invalid-ha-url","message":"Enter a valid Home Assistant URL first."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            val panelOrigin = panelHttpOrigin(call.request.headers[HttpHeaders.Host], dependencies.panelPort)
            if (panelOrigin == null) {
                call.respondText(
                    """{"ok":false,"error":"invalid-panel-origin","message":"Open Configure using this panel's normal IP or .local address."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            val started = dependencies.startOAuth(
                haUrl,
                panelOrigin,
                oauthStartSelection(
                    parameters[HA_OAUTH_UI_LOCALE_FIELD],
                    parameters[HA_OAUTH_RETURN_SURFACE_FIELD],
                    parameters[HA_OAUTH_PRESERVE_ENGLISH_FIELD],
                    dependencies.allowPseudoLocale,
                ),
            )
            call.respondText(
                """{"ok":true,"authorization_url":${Json.str(started.authorizationUrl)}}""",
                ContentType.Application.Json,
            )
        }

        get("/panel-start") {
            call.noStoreHaOAuth()
            val rawUrl = call.request.queryParameters["ha_url"].orEmpty()
            val spec = requireNotNull(SettingsRegistry.spec("ha_url"))
            val haUrl = when (val validation = SettingValue.validate(spec, rawUrl)) {
                is Validation.Ok -> validation.normalized.takeIf(String::isNotBlank)
                is Validation.Bad -> null
            }
            if (haUrl == null) {
                call.respondText("Enter a valid Home Assistant URL first.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@get
            }
            val panelOrigin = panelHttpOrigin(call.request.headers[HttpHeaders.Host], dependencies.panelPort)
            if (panelOrigin == null) {
                call.respondText(
                    "Open this sign-in through the panel's local HTTP endpoint.",
                    ContentType.Text.Plain,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }
            val started = dependencies.startOAuth(
                haUrl,
                panelOrigin,
                oauthStartSelection(
                    call.request.queryParameters[HA_OAUTH_UI_LOCALE_FIELD],
                    call.request.queryParameters[HA_OAUTH_RETURN_SURFACE_FIELD],
                    call.request.queryParameters[HA_OAUTH_PRESERVE_ENGLISH_FIELD],
                    dependencies.allowPseudoLocale,
                ),
            )
            call.respondRedirect(started.authorizationUrl)
        }

        get("/callback") {
            call.noStoreHaOAuth()
            val panelOrigin = panelHttpOrigin(call.request.headers[HttpHeaders.Host], dependencies.panelPort)
                ?: return@get call.respondHaOAuthPage(false, "This sign-in link does not belong to this panel address.")
            val claim = dependencies.claim(call.request.queryParameters["state"].orEmpty(), panelOrigin)
            val attempt = when (claim) {
                is HaOAuthClaim.Claimed -> claim.attempt
                HaOAuthClaim.Invalid -> return@get call.respondHaOAuthPage(
                    false,
                    "This sign-in link has expired or was already used.",
                )
                HaOAuthClaim.WrongOrigin -> return@get call.respondHaOAuthPage(
                    false,
                    "This sign-in link was opened on a different panel address.",
                )
            }
            val context = attempt.startContext
            val copy = context.copy
            if (!call.request.queryParameters["error"].isNullOrBlank()) {
                call.respondHaOAuthPage(false, copy.cancelled, context)
                return@get
            }
            val code = call.request.queryParameters["code"].orEmpty()
            if (code.isBlank() || code.length > MAX_HA_OAUTH_CODE_CHARS) {
                call.respondHaOAuthPage(false, copy.invalidCode, context)
                return@get
            }
            val exchange = try {
                dependencies.exchange(attempt, code)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                HaLink.AuthorizationCodeExchange.Transient
            }
            val tokens = when (exchange) {
                is HaLink.AuthorizationCodeExchange.Success -> exchange.tokens
                HaLink.AuthorizationCodeExchange.Rejected -> {
                    call.respondHaOAuthPage(
                        false,
                        copy.rejected,
                        context,
                    )
                    return@get
                }
                HaLink.AuthorizationCodeExchange.Transient -> {
                    call.respondHaOAuthPage(
                        false,
                        copy.transient,
                        context,
                    )
                    return@get
                }
            }
            val completion = try {
                dependencies.complete(attempt, tokens)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                HaOAuthCompletion.CommitFailed
            }
            when (completion) {
                HaOAuthCompletion.Stale -> call.respondHaOAuthPage(
                    false,
                    copy.stale,
                    context,
                )
                HaOAuthCompletion.CommitFailed -> call.respondHaOAuthPage(
                    false,
                    copy.commitFailed,
                    context,
                )
                is HaOAuthCompletion.Success -> {
                    val reload = if (completion.reloadMayBeNeeded) " ${copy.reloadMayBeNeeded}" else ""
                    val ambient = if (completion.ambientWarning) {
                        " ${copy.ambientWarning}"
                    } else ""
                    val successContext = if (!context.useLegacySuccessReturn) context else {
                        // Compatibility for callers not yet carrying context in the attempt. Admit only
                        // the two historical server destinations; any other value fails closed.
                        context.copy(
                            returnSurface = if (dependencies.successReturnPath() == "/setup") {
                                HaOAuthReturnSurface.SETUP
                            } else {
                                HaOAuthReturnSurface.CONFIGURE
                            },
                            useLegacySuccessReturn = false,
                        )
                    }
                    call.respondHaOAuthPage(
                        true,
                        "${copy.configured}$reload$ambient",
                        successContext,
                    )
                }
            }
        }
    }
}

private fun HaOAuthRouteDependencies.startOAuth(
    haUrl: String,
    panelOrigin: String,
    selection: HaOAuthStartSelection?,
): HaOAuthStart {
    if (selection == null || startWithContext == null) return start(haUrl, panelOrigin)
    val context = runCatching { startContext(selection) }.getOrNull()
        ?.takeIf {
            it.locale == selection.locale &&
                it.returnSurface == selection.returnSurface &&
                it.preserveExplicitEnglish == selection.preserveExplicitEnglish
        }
        ?: HaOAuthStartContext.ENGLISH_CONFIGURE
    return startWithContext.invoke(haUrl, panelOrigin, context)
}

/** All fields absent means a legacy caller. Any partial/malformed request uses a complete closed
 * English Configure selection, never a partially trusted locale or destination. */
internal fun oauthStartSelection(
    rawLocale: String?,
    rawSurface: String?,
    rawPreserveExplicitEnglish: String?,
    allowPseudo: Boolean = false,
): HaOAuthStartSelection? {
    if (rawLocale == null && rawSurface == null && rawPreserveExplicitEnglish == null) return null
    if (!allowPseudo && rawLocale?.trim()?.replace('_', '-')?.equals(AppLocale.PSEUDO, ignoreCase = true) == true) {
        return HaOAuthStartSelection(AppLocale.ENGLISH, HaOAuthReturnSurface.CONFIGURE, false)
    }
    val locale = AppLocale.canonical(rawLocale, allowPseudo = allowPseudo)
    val surface = when (rawSurface) {
        "configure" -> HaOAuthReturnSurface.CONFIGURE
        "setup" -> HaOAuthReturnSurface.SETUP
        else -> null
    }
    val preserve = when (rawPreserveExplicitEnglish) {
        null -> false
        "0" -> false
        "1" -> true
        else -> return HaOAuthStartSelection(AppLocale.ENGLISH, HaOAuthReturnSurface.CONFIGURE, false)
    }
    if (locale == null || surface == null || (preserve && (locale != AppLocale.ENGLISH || surface != HaOAuthReturnSurface.SETUP))) {
        return HaOAuthStartSelection(AppLocale.ENGLISH, HaOAuthReturnSurface.CONFIGURE, false)
    }
    return HaOAuthStartSelection(locale, surface, preserve)
}

internal fun ApplicationCall.noStoreHaOAuth() {
    if (response.headers[HttpHeaders.CacheControl] == null) {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
    }
    if (response.headers["Referrer-Policy"] == null) {
        response.headers.append("Referrer-Policy", "no-referrer")
    }
}

private suspend fun ApplicationCall.respondHaOAuthPage(
    success: Boolean,
    message: String,
    context: HaOAuthStartContext = HaOAuthStartContext.ENGLISH_CONFIGURE,
) {
    response.headers.append(HttpHeaders.ContentLanguage, context.contentLanguages.sorted().joinToString(", "))
    val heading = if (success) context.copy.successHeading else context.copy.failureHeading
    val status = if (success) "success" else "failure"
    // On success the browser is sent onward automatically: the wizard tab navigated here during
    // sign-in, so a still-open tab to broadcast to no longer exists — this fresh page IS the return
    // path. A short pause lets the user read the confirmation first. A failure keeps a manual link only,
    // since auto-forwarding an error would hide it. The path is a server-chosen constant, safe to inline.
    val safeReturn = escapeHaOAuthHtml(context.returnPath())
    val link = when {
        success -> context.copy.continueAction
        context.returnSurface == HaOAuthReturnSurface.SETUP -> context.copy.backToSetupAction
        else -> context.copy.backToConfigureAction
    }
    val forward = if (success) {
        """setTimeout(function(){location.assign("$safeReturn");},1400);"""
    } else ""
    respondText(
        """<!doctype html><html lang="${escapeHaOAuthHtml(context.locale)}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHaOAuthHtml(heading)}</title>
<link rel="stylesheet" href="/info.css"></head><body><div class="wrap"><div class="card">
<h1>${escapeHaOAuthHtml(heading)}</h1><p>${escapeHaOAuthHtml(message)}</p><p><a class="pbtn" href="$safeReturn">${escapeHaOAuthHtml(link)}</a></p>
</div></div><script>document.body.dataset.haOauthStatus="$status";history.replaceState(null,"","$HA_OAUTH_CALLBACK_PATH");if("BroadcastChannel" in window){var c=new BroadcastChannel("ha-paneld-ha-oauth");c.postMessage({status:"$status"});}$forward</script>
</body></html>""",
        ContentType.Text.Html,
        if (success) HttpStatusCode.OK else HttpStatusCode.BadRequest,
    )
}

private fun escapeHaOAuthHtml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            },
        )
    }
}
