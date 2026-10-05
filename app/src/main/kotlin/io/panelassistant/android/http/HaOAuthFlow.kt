package io.panelassistant.android.http

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.HaOAuthAttemptAuthority
import io.panelassistant.android.i18n.AppLocale
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

internal const val HA_OAUTH_CALLBACK_PATH = "/api/v1/ha/oauth/callback"

internal enum class HaOAuthReturnSurface { CONFIGURE, SETUP }

/** The only request-derived values admitted into an OAuth attempt. */
internal data class HaOAuthStartSelection(
    val locale: String,
    val returnSurface: HaOAuthReturnSurface,
    val preserveExplicitEnglish: Boolean,
) {
    init {
        require(AppLocale.canonical(locale, allowPseudo = true) == locale)
        require(returnSurface == HaOAuthReturnSurface.SETUP || !preserveExplicitEnglish)
        require(locale == AppLocale.ENGLISH || !preserveExplicitEnglish)
    }
}

/** Trusted, catalogue-derived visible copy for a callback which has successfully claimed its attempt. */
internal data class HaOAuthCallbackCopy(
    val successHeading: String,
    val failureHeading: String,
    val continueAction: String,
    val backToConfigureAction: String,
    val backToSetupAction: String,
    val cancelled: String,
    val invalidCode: String,
    val rejected: String,
    val transient: String,
    val stale: String,
    val commitFailed: String,
    val configured: String,
    val reloadMayBeNeeded: String,
    val ambientWarning: String,
) {
    init {
        require(listOf(
            successHeading, failureHeading, continueAction, backToConfigureAction, backToSetupAction, cancelled,
            invalidCode, rejected, transient, stale, commitFailed, configured,
            reloadMayBeNeeded, ambientWarning,
        ).all { it.isNotBlank() && it.length <= MAX_HA_OAUTH_COPY_CHARS })
    }

    companion object {
        val ENGLISH = HaOAuthCallbackCopy(
            successHeading = "Home Assistant configured",
            failureHeading = "Home Assistant sign-in not completed",
            continueAction = "Continue",
            backToConfigureAction = "Back to Configure",
            backToSetupAction = "Back to Setup",
            cancelled = "Home Assistant sign-in was cancelled.",
            invalidCode = "Home Assistant did not return a valid sign-in code.",
            rejected = "Home Assistant did not accept this sign-in. Start a new sign-in.",
            transient = "The panel could not complete sign-in. Check its Home Assistant connection and try again.",
            stale = "Home Assistant settings changed while sign-in was open. Start a new sign-in.",
            commitFailed = "The panel could not save this sign-in. Start a new sign-in.",
            configured = "Home Assistant is configured.",
            reloadMayBeNeeded = "The dashboard may need a manual reload.",
            ambientWarning = "Home Assistant is configured, but the selected ambient-light source needs attention.",
        )
    }
}

private const val MAX_HA_OAUTH_COPY_CHARS = 4 * 1024

/** Process-local presentation and return authority. The closed surface prevents an OAuth callback from
 * becoming an open redirect; [preserveExplicitEnglish] exists only for Setup's explicit `?lang=en`. */
internal data class HaOAuthStartContext(
    val locale: String = AppLocale.ENGLISH,
    val returnSurface: HaOAuthReturnSurface = HaOAuthReturnSurface.CONFIGURE,
    val preserveExplicitEnglish: Boolean = false,
    val copy: HaOAuthCallbackCopy = HaOAuthCallbackCopy.ENGLISH,
    /** Exact languages emitted by [copy], supplied by the catalogue resolver. Per-key fallback means
     * this can be the requested locale, English, or both. */
    val contentLanguages: Set<String> = setOf(AppLocale.ENGLISH),
    val useLegacySuccessReturn: Boolean = false,
) {
    init {
        require(AppLocale.canonical(locale, allowPseudo = true) == locale) { "unsupported OAuth callback locale" }
        require(returnSurface == HaOAuthReturnSurface.SETUP || !preserveExplicitEnglish) {
            "explicit English return preservation is Setup-only"
        }
        require(locale == AppLocale.ENGLISH || !preserveExplicitEnglish) {
            "explicit English return preservation requires English"
        }
        require(contentLanguages.isNotEmpty() && contentLanguages.size <= 2) {
            "OAuth callback must declare one or two content languages"
        }
        require(contentLanguages.all { language ->
            AppLocale.canonical(language, allowPseudo = true) == language &&
                (language == locale || language == AppLocale.ENGLISH)
        }) {
            "OAuth callback content languages must be canonical requested-locale or English values"
        }
        require(
            !useLegacySuccessReturn || (
                locale == AppLocale.ENGLISH && returnSurface == HaOAuthReturnSurface.CONFIGURE &&
                    !preserveExplicitEnglish && copy == HaOAuthCallbackCopy.ENGLISH &&
                    contentLanguages == setOf(AppLocale.ENGLISH)
                ),
        ) {
            "legacy OAuth return authority must be the English Configure default"
        }
    }

    fun returnPath(): String {
        val base = when (returnSurface) {
            HaOAuthReturnSurface.CONFIGURE -> "/configure"
            HaOAuthReturnSurface.SETUP -> "/setup"
        }
        val localized = if (locale != AppLocale.ENGLISH || preserveExplicitEnglish) "$base?lang=$locale" else base
        return if (returnSurface == HaOAuthReturnSurface.CONFIGURE) "$localized#cfg-ha_url" else localized
    }

    companion object {
        val ENGLISH_CONFIGURE = HaOAuthStartContext()
        val LEGACY_ENGLISH_CONFIGURE = HaOAuthStartContext(useLegacySuccessReturn = true)
    }
}

/** A browser login attempt contains authority, not credentials. It is process-local, short-lived and
 * consumed exactly once before an authorization code is sent back to Home Assistant. */
internal data class HaOAuthAttempt(
    val haUrl: String,
    val panelOrigin: String,
    val clientId: String,
    val redirectUri: String,
    val expectedOwner: HaAuthOwner,
    val expectedEpoch: Long,
    val startContext: HaOAuthStartContext,
)

internal data class HaOAuthStart(val authorizationUrl: String)

internal sealed class HaOAuthClaim {
    data class Claimed(val attempt: HaOAuthAttempt) : HaOAuthClaim()
    object Invalid : HaOAuthClaim()
    object WrongOrigin : HaOAuthClaim()
}

internal class HaOAuthFlow(
    private val nowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val stateToken: () -> String = ::secureHaOAuthState,
    private val ttlMillis: Long = 10 * 60 * 1_000L,
) {
    private data class Pending(val attempt: HaOAuthAttempt, val expiresAtMillis: Long)

    private val pending = LinkedHashMap<String, Pending>()

    init {
        require(ttlMillis in 1..(60 * 60 * 1_000L))
    }

    @Synchronized
    fun start(
        haUrl: String,
        panelOrigin: String,
        authority: HaOAuthAttemptAuthority,
        startContext: HaOAuthStartContext = HaOAuthStartContext.LEGACY_ENGLISH_CONFIGURE,
    ): HaOAuthStart {
        purgeExpired()
        // Only the most recently requested login remains usable. The config-owned epoch also blocks
        // an older callback which was claimed before this map could invalidate its state.
        pending.clear()
        val state = generateUniqueState()
        val clientId = "$panelOrigin/"
        val redirectUri = "$panelOrigin$HA_OAUTH_CALLBACK_PATH"
        val attempt = HaOAuthAttempt(
            haUrl,
            panelOrigin,
            clientId,
            redirectUri,
            authority.owner,
            authority.epoch,
            startContext,
        )
        pending[state] = Pending(attempt, nowMillis() + ttlMillis)
        val authorizationUrl = "$haUrl/auth/authorize?" + listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "state" to state,
        ).joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return HaOAuthStart(authorizationUrl)
    }

    /** Consume before token exchange. A wrong-origin callback also burns the disclosed state instead of
     * leaving a valid bearer-like login capability available for a second request. */
    @Synchronized
    fun claim(state: String, panelOrigin: String): HaOAuthClaim {
        if (!validHaOAuthState(state)) return HaOAuthClaim.Invalid
        purgeExpired()
        val found = pending.remove(state) ?: return HaOAuthClaim.Invalid
        return if (found.attempt.panelOrigin == panelOrigin) HaOAuthClaim.Claimed(found.attempt)
        else HaOAuthClaim.WrongOrigin
    }

    @Synchronized
    internal fun pendingCount(): Int {
        purgeExpired()
        return pending.size
    }

    private fun purgeExpired() {
        val now = nowMillis()
        pending.entries.removeAll { it.value.expiresAtMillis <= now }
    }

    private fun generateUniqueState(): String {
        repeat(4) {
            val candidate = stateToken()
            require(validHaOAuthState(candidate)) { "OAuth state generator returned an invalid token" }
            if (candidate !in pending) return candidate
        }
        error("OAuth state generator repeated a live token")
    }
}

internal fun validHaOAuthState(value: String): Boolean =
    value.length in 43..86 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

private fun secureHaOAuthState(): String = ByteArray(32)
    .also(SecureRandom()::nextBytes)
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** Strictly turn the request Host header into this panel's HTTP origin. The OAuth client and callback
 * must share this exact host and port; accepting path/userinfo/query ambiguity would weaken that bind. */
internal fun panelHttpOrigin(hostHeader: String?, panelPort: Int): String? {
    if (panelPort !in 1..65_535) return null
    val raw = hostHeader?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return null
    if (raw.any { it <= ' ' || it == '\u007f' } || raw.any { it in "/?#@" }) return null
    val uri = runCatching { URI("http://$raw") }.getOrNull() ?: return null
    if (uri.rawAuthority != raw || uri.userInfo != null || uri.rawPath.isNotEmpty() ||
        uri.rawQuery != null || uri.rawFragment != null
    ) return null
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        ?.takeIf(String::isNotBlank) ?: return null
    val port = when {
        uri.port == -1 -> panelPort
        uri.port in 1..65_535 -> uri.port
        else -> return null
    }
    val authorityHost = if (':' in host) "[$host]" else host
    val portSuffix = if (port == 80) "" else ":$port"
    return "http://$authorityHost$portSuffix"
}
