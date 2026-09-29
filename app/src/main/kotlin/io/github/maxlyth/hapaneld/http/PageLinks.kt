package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

    /** The shared tab bar; [active] highlights the current page. */
    internal fun localizedHref(path: String, strings: AppStrings): String {
        if (strings.requestedLocale == AppLocale.ENGLISH) return path
        val fragmentAt = path.indexOf('#')
        val address = if (fragmentAt < 0) path else path.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else path.substring(fragmentAt)
        val separator = if ('?' in address) '&' else '?'
        return "$address${separator}lang=${esc(strings.requestedLocale)}$fragment"
    }

    /** Setup is the only server page whose browser code carries an explicit locale between journey
     * steps. Keep an explicit English override in its server-rendered links too, while naturally
     * negotiated English remains URL-clean everywhere. */
    internal fun setupHref(path: String, strings: AppStrings, preserveExplicitEnglish: Boolean): String {
        if (!preserveExplicitEnglish || strings.requestedLocale != AppLocale.ENGLISH) {
            return localizedHref(path, strings)
        }
        val fragmentAt = path.indexOf('#')
        val address = if (fragmentAt < 0) path else path.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else path.substring(fragmentAt)
        val separator = if ('?' in address) '&' else '?'
        return "$address${separator}lang=${esc(AppLocale.ENGLISH)}$fragment"
    }

    internal fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
