package io.panelassistant.android

/** The running app's version on screens and in human-readable reports. */
internal fun appVersion(
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
): String = "$versionName ($versionCode)"
