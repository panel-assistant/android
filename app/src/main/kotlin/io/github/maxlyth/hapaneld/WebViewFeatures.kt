package io.github.maxlyth.hapaneld

import androidx.webkit.WebViewFeature

/**
 * The one place in the app that asks androidx.webkit whether a WebView capability exists.
 *
 * `WebViewFeature.isFeatureSupported` reads `ApiFeature.LAZY_HOLDER.WEBVIEW_APK_FEATURES`, a static
 * set built once per process from the bound provider's support-library glue. When **no** provider
 * resolves at all, `WebViewGlueCommunicator.getWebViewClassLoader()` raises while that holder is
 * initialising, and every later access throws `NoClassDefFoundError` for the life of the process —
 * an `Error`, not an `Exception`, so an ordinary `catch (e: Exception)` would not stop it.
 *
 * `DashboardActivity` is a HOME activity in a process that is meant to outlive every dashboard, so an
 * escaping throw there is not one crash: Android relaunches the home activity, the next probe throws
 * from the same poisoned holder, and the panel crash-loops with nothing on screen. Absorbing the throw
 * as "the capability is absent" is exactly what the caller already handles — a provider that loads
 * without glue resolves to `IncompatibleApkWebViewProviderFactory` and reports an empty feature array,
 * which is the same `false` by a different route.
 *
 * This deliberately introduces no new classification. It answers the capability question and nothing
 * else; [secureBridgeAdmission] is what turns a missing capability into an admission verdict.
 */
internal fun interface WebViewFeatureProbe {
    fun supports(feature: String): Boolean
}

/** The real androidx.webkit probe. The only `isFeatureSupported` call in the app. */
internal val SystemWebViewFeatureProbe = WebViewFeatureProbe { WebViewFeature.isFeatureSupported(it) }

/**
 * True when [feature] is available. A throwing probe — the no-provider case above — reads as absent.
 *
 * `runCatching` is deliberate over `catch (e: Exception)`: the failure this exists for is an `Error`.
 */
internal fun webViewFeatureSupported(
    feature: String,
    probe: WebViewFeatureProbe = SystemWebViewFeatureProbe,
): Boolean = runCatching { probe.supports(feature) }.getOrDefault(false)

/**
 * The secure dashboard bridge needs both halves: a message listener to carry the external-app protocol
 * and a document-start script to install it before Home Assistant's own code runs. Either one missing
 * is the same permanent incapability, so this returns the single existing
 * [AdmissionOutcome.BRIDGE_UNAVAILABLE] verdict rather than splitting it.
 *
 * Null means admission may proceed. Pure and probe-injectable so the no-provider case is a unit test
 * rather than a device with a deliberately broken WebView.
 */
internal fun secureBridgeAdmission(
    probe: WebViewFeatureProbe = SystemWebViewFeatureProbe,
): AdmissionOutcome? =
    if (webViewFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER, probe) &&
        webViewFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT, probe)
    ) {
        null
    } else {
        AdmissionOutcome.BRIDGE_UNAVAILABLE
    }
