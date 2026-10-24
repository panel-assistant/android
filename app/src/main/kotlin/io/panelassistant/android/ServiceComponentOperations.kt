package io.panelassistant.android

import android.content.Context
import android.util.Log
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.state.SoftwareTarget
import io.panelassistant.android.state.SoftwareUpdateSources
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.InstallOperationResult
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The service's software operations: the one destructive-operation lane (Companion URL repair and
 * component installs from the Install tab, each under an [InstallProgress] ticket), the snapshot the
 * update entities report, and this service generation's [InstallProgress] and [UpdateChecker]
 * observers. The observers are this instance's own, so a detach clears only what it attached.
 *
 * [config], [core] and [profile] are read when used, never captured: the service creates this unit
 * before any of them exists.
 */
internal class ServiceComponentOperations(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: () -> Config,
    private val core: () -> PanelStateCore,
    private val profile: () -> DeviceProfile,
) {
    // Observer identities for InstallProgress and UpdateChecker, so detach clears only this service's.
    // Progress only schedules a republish: lane claims include database maintenance, which must not
    // gain a configuration write from here.
    private val softwareProgressObserver: () -> Unit = {
        runCatching { core().publishSoftwareUpdates() }
    }
    private val softwareCatalogObserver: () -> Unit = {
        runCatching { config().setSoftwareUpdateTargets(UpdateChecker.persistableTargets()) }
        runCatching { core().publishSoftwareUpdates() }
    }

    /** Run one destructive operation under the same process-wide ticket used by HTTP restore/APK routes. */
    private suspend fun completeOperation(
        progress: InstallProgress.Ticket,
        logLabel: String,
        operation: suspend () -> InstallOperationResult,
        after: suspend (String) -> Unit = {},
    ): String {
        var result = "cancelled"
        var resultPresentation: InstallPresentation? = InstallPresentation("operation-cancelled")
        try {
            val completed = try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$logLabel failed", e)
                InstallOperationResult("error: ${e.message}")
            }
            result = completed.message
            resultPresentation = completed.presentation
            Log.i(TAG, "$logLabel: $result")
            after(result)
            // Keep the destructive-operation lane until post-install activation is complete. WebView
            // activation deliberately waits before requesting a process boundary; releasing the lane
            // first lets the blocked screen start a second install that the pending restart kills.
            InstallProgress.finish(progress, result, presentation = resultPresentation)
            return result
        } finally {
            // Covers cancellation, fatal errors, and direct callers; stale tickets cannot clear a successor.
            InstallProgress.finish(progress, result, presentation = resultPresentation)
        }
    }

    /** Start an asynchronous destructive operation only when the shared lane is free. */
    private fun launchOperation(
        component: String,
        owner: String,
        logLabel: String,
        operation: suspend () -> InstallOperationResult,
        after: suspend (String) -> Unit = {},
        onStarted: (InstallProgress.Ticket) -> Unit = {},
    ): Boolean {
        val progress = InstallProgress.start(
            component,
            InstallPresentation("operation-working", mapOf("owner" to owner)),
        ) ?: return false
        onStarted(progress)
        val job = scope.launch { completeOperation(progress, logLabel, operation, after) }
        // A cancelled job can complete before its body starts, so the body's finally is not sufficient.
        InstallProgress.finishOnFailure(progress, job)
        return true
    }

    /** Companion internal_url repair (Install-tab button): copy each server's external_url into a blank
     *  internal_url so HA 2026.7 stops rejecting the dashboard with "Missing 'Host' header". [repair]
     *  owns the Companion data custody; this lane only admits it and reports its result. Off-thread
     *  (the helper force-stops and relaunches the Companion). */
    fun repairCompanionUrl(repair: () -> InstallOperationResult): Boolean {
        return launchOperation(
            component = "Companion URL repair",
            owner = "companion-url-repair",
            logLabel = "Companion internal_url repair",
            operation = { withContext(Dispatchers.IO) { repair() } },
        )
    }

    fun attachSoftwareUpdateObservers() {
        // Targets persisted by an earlier process: a restart keeps reporting the last known release.
        UpdateChecker.restoreTargets(config().softwareUpdateTargets.second)
        InstallProgress.observer = softwareProgressObserver
        UpdateChecker.onChecked = softwareCatalogObserver
    }

    fun detachSoftwareUpdateObservers() {
        if (InstallProgress.observer === softwareProgressObserver) InstallProgress.observer = null
        if (UpdateChecker.onChecked === softwareCatalogObserver) UpdateChecker.onChecked = null
    }

    /** One coherent read of everything the update channels report. */
    fun softwareUpdateSources(): SoftwareUpdateSources {
        val paneldChannel = "stable"
        val companionChannel = "stable"
        val cap = profile().companionMaxVersion
        val progress = InstallProgress.presentationSnapshot()
        // Only "not installed" is an answer; any other failure leaves presence unknown rather than absent.
        var lookupFailed = false
        fun installed(pkg: String): String? = try {
            context.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            lookupFailed = true
            null
        }
        val companionMinimal = installed(CompanionInstaller.MINIMAL_PKG)
        val companionFull = installed(CompanionInstaller.FULL_PKG)
        return SoftwareUpdateSources(
            paneldVersion = BuildConfig.VERSION_NAME,
            paneldChannel = paneldChannel,
            paneldTarget = null,
            companionMinimalVersion = companionMinimal,
            companionFullVersion = companionFull,
            companionChannel = companionChannel,
            companionCap = cap,
            companionTarget = UpdateChecker.companionTarget(companionChannel, cap)?.let {
                SoftwareTarget(it.version, it.tag, it.releaseUrl, it.capped, it.newestVersion)
            },
            runningOperation = progress.component.takeIf { progress.running },
            companionPresenceUnknown = lookupFailed,
        )
    }

    /** Install/update a managed component from the Install tab (POST /api/v1/install/component). Runs
     *  off-thread; progress is reported via InstallProgress so the web UI can poll. action="reinstall"
     *  forces even when the installed build is already current. Single-slot (InstallProgress.start gates). */
    fun installComponent(
        name: String,
        action: String,
        version: String,
        onStarted: (InstallProgress.Ticket) -> Unit = {},
    ): Boolean {
        if (name != "companion") return false
        val label = "HA Companion"
        val force = action == "reinstall"
        val tag = version.takeIf { it.isNotBlank() }
        return launchOperation(
            component = label,
            owner = "companion",
            logLabel = "install $name",
            operation = {
                // A specific picked version installs that exact tag; otherwise the channel's newest.
                if (tag != null) CompanionInstaller.installVersionResult(
                    context,
                    tag,
                    profile().companionMaxVersion,
                ) else CompanionInstaller.installOrUpdateResult(
                    context,
                    force = force,
                    channel = "stable",
                    maxVersion = profile().companionMaxVersion,
                )
            },
            after = {
                // Refresh the available-update list so the banner + Install tab reflect the new state.
                runCatching {
                    UpdateChecker.check(
                        context,
                        "stable",
                        profile().companionMaxVersion,
                    )
                }
            },
            onStarted = onStarted,
        )
    }

    private companion object {
        const val TAG = PaneldService.TAG
    }
}
