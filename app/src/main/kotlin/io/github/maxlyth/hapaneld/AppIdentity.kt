package io.github.maxlyth.hapaneld

import io.panelassistant.android.BuildConfig

/**
 * The two installable identities one source tree builds. A new applicationId is a new app to Android,
 * so a panel moves from [LEGACY] to [SUCCESSOR] by running both for one handover: the `bridge` build
 * keeps the legacy id and installs the successor, and the successor pulls state, takes over and removes
 * the bridge. Only the applicationId differs; the Kotlin package, manifest component classes and the
 * release signer are shared, so a component name is always `<applicationId>/<fully.qualified.Class>`.
 */
object AppIdentity {
    const val LEGACY: String = BuildConfig.LEGACY_APPLICATION_ID
    const val SUCCESSOR: String = BuildConfig.SUCCESSOR_APPLICATION_ID

    /** This build's own applicationId. */
    const val OWN: String = BuildConfig.APPLICATION_ID

    /** True for the build that keeps the legacy id and hands over to the successor. */
    const val IS_BRIDGE: Boolean = BuildConfig.SUCCESSOR_BRIDGE

    /** Both ids, legacy first. Either one may be the installed panel app during the migration. */
    val ALL: Set<String> = linkedSetOf(LEGACY, SUCCESSOR)

    /** The other identity: the package this build hands over to, or takes over from. */
    val COUNTERPART: String = counterpartOf(OWN)

    fun isPanelApp(pkg: String?): Boolean = pkg != null && pkg in ALL

    /**
     * The flattened component for one of this app's own classes, given as the manifest's relative name
     * (`.DashboardActivity`). Android resolves the `/.Class` shorthand against the applicationId, while
     * the classes live in [CODE_PACKAGE] whichever id the build carries, so the shorthand is only valid
     * for the legacy id. That build keeps it, so every command it issues is unchanged.
     */
    fun component(applicationId: String, relativeClass: String): String =
        "$applicationId/${className(applicationId, relativeClass)}"

    /** The class half of [component]: relative for the legacy id, fully qualified for any other. */
    fun className(applicationId: String, relativeClass: String): String {
        require(relativeClass.startsWith(".")) { "$relativeClass is not a relative class name" }
        return if (applicationId == CODE_PACKAGE) relativeClass else "$CODE_PACKAGE$relativeClass"
    }

    /** The Gradle namespace and Kotlin package, which do not move with the applicationId. */
    const val CODE_PACKAGE: String = "io.github.maxlyth.hapaneld"

    internal fun counterpartOf(pkg: String): String = when (pkg) {
        LEGACY -> SUCCESSOR
        SUCCESSOR -> LEGACY
        else -> error("$pkg is not a panel application id")
    }
}
