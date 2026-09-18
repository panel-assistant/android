package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.util.AndroidInput

/** Canonical, Android-free policy for the durable vendor-package selection. */
internal object TamePackagePolicy {
    const val MAX_BYTES = 8_192
    const val MAX_PACKAGES = 128

    private val critical = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        // Both installed identities of this app. During the application-id migration either one may
        // be the panel's app, and the other must never be offered for taming.
    ) + AppIdentity.ALL

    fun isCritical(pkg: String): Boolean = pkg in critical

    /** Validate first, then remove untouchable names so preview and persisted state are identical. */
    fun normalize(raw: String): Validation {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return Validation.Bad("tame_vendor_packages: must be at most 8192 bytes")
        }
        val packages = raw.split(Regex("[\\s,]+"))
            .filter(String::isNotEmpty)
        if (packages.size > MAX_PACKAGES ||
            packages.any { it.length > 255 || !AndroidInput.isPackage(it) }
        ) {
            return Validation.Bad("tame_vendor_packages: expected at most 128 Android package names")
        }
        return Validation.Ok(packages.distinct().filterNot(::isCritical).joinToString(" "))
    }
}
