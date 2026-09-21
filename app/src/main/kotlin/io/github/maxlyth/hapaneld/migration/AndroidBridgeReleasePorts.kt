package io.github.maxlyth.hapaneld.migration

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.PaneldService
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import io.github.maxlyth.hapaneld.platform.AndroidSystemEnv
import io.github.maxlyth.hapaneld.upgrade.UpgradeRequestCompletion
import io.github.maxlyth.hapaneld.upgrade.UpgradeShutdownCoordinator
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.dualUidHelperRefusal
import java.security.SecureRandom

/**
 * The bridge's real [BridgeRelease.Ports]. Quiescing reuses the upgrade shutdown exactly as an
 * installer-driven update does: arm the coordinator, stop the service, and act only when it reports
 * that the service tore down cleanly with state flushed and frozen. That teardown is also what stops
 * the kiosk return loop and the HTTP server, so nothing here stops them a second way.
 */
internal class AndroidBridgeReleasePorts(context: Context) : BridgeRelease.Ports {
    private val context = context.applicationContext
    @Volatile private var armedNonce: String? = null

    override fun isBridge(): Boolean = AppIdentity.IS_BRIDGE
    override fun retired(): Boolean = BridgeRetirement.isRetired(context)
    override fun tokenMatches(presented: String?): Boolean = ReleaseToken.of(context).matches(presented)

    override fun successorTrusted(): Boolean {
        val signers = AppInstaller.installedSigners(context, AppIdentity.SUCCESSOR) ?: return false
        return signers.size == 1 && signers.single().equals(AppInstaller.MIGRATION_SIGNER, ignoreCase = true)
    }

    override fun helperRefusal(): String? =
        dualUidHelperRefusal(HelperClient.helperStatus(), BuildConfig.HELPER_BUILD_ID, context.packageName)

    override fun beginQuiesce(onQuiesced: () -> Unit): Boolean {
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val completion = object : UpgradeRequestCompletion {
            override fun ready(nonce: String, proof: CleanDatabaseProof) {
                Log.i(TAG, "bridge quiesced; retiring")
                onQuiesced()
            }

            override fun failed(reason: String) {
                // The coordinator resumes the service itself on every failure path.
                Log.w(TAG, "bridge release abandoned: $reason")
            }

            // The teardown is ending in a process exit rather than a same-process handoff. That is
            // still a retirement in substance — the service stops and the port frees — so complete
            // it here instead of abandoning a release the successor is already waiting on.
            override fun exitingProcess(reason: String) {
                Log.i(TAG, "bridge retiring on process exit: $reason")
                onQuiesced()
            }
        }
        if (!UpgradeShutdownCoordinator.arm(context, nonce, completion)) return false
        armedNonce = nonce
        // The reply to the release request has to leave before the server it travels through stops.
        Handler(Looper.getMainLooper()).postDelayed({
            val stopped = runCatching {
                context.stopService(Intent(context, PaneldService::class.java))
            }.getOrDefault(false)
            if (!stopped) UpgradeShutdownCoordinator.cancelAndResume(context, nonce, "service_not_running")
        }, REPLY_GRACE_MS)
        return true
    }

    override fun writeRetiredMarker(): Boolean = BridgeRetirement.marker(context).arm()

    override fun setHomeToSuccessor(): Boolean {
        // HOME is handed over only when this app holds it. A panel whose owner chose another launcher
        // keeps that choice; the successor applies the restored launcher policy like any other start.
        val current = AndroidSystemEnv(context).defaultHome()?.pkg
        if (current != null && current != AppIdentity.LEGACY && current != "android") {
            Log.i(TAG, "HOME belongs to $current; nothing to hand over")
            return true
        }
        val component = AppIdentity.component(AppIdentity.SUCCESSOR, ".DashboardActivity")
        val set = HelperClient.send("SETHOME $component") == "OK" ||
            Su.run("cmd package set-home-activity $component")
        val confirmed = AndroidSystemEnv(context).defaultHome()?.pkg == AppIdentity.SUCCESSOR
        Log.i(TAG, "HOME handed to the successor: set=$set confirmed=$confirmed")
        return set && confirmed
    }

    override fun endProcess() {
        Log.i(TAG, "bridge retired; ending the process")
        kotlin.system.exitProcess(0)
    }

    override fun resumeBridge() {
        armedNonce?.let { UpgradeShutdownCoordinator.cancelAndResume(context, it, "retire_marker_not_durable") }
    }

    private companion object {
        const val TAG = "ha-paneld/migration"
        const val REPLY_GRACE_MS = 750L
    }
}
