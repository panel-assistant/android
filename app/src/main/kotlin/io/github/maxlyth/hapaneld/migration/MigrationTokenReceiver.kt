package io.github.maxlyth.hapaneld.migration

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.PaneldService
import io.github.maxlyth.hapaneld.upgrade.UpgradeShutdownCoordinator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The private link between the two installed identities of this app. The manifest guards this receiver
 * with the signature-level migration permission, so Android delivers only broadcasts whose sender
 * shares this app's signer, and both directions address the other package by explicit component.
 *
 * Bridge to successor: the one-time [ReleaseToken] and the port the bridge serves on. Successor to
 * bridge: an ordered status query, answered from the durable retired marker alone. That query exists
 * because the release request cannot
 * be answered after the handover it starts (the HTTP server is part of what is given up), and because
 * a successor that merely finds the port free cannot tell a retired bridge from a crashed one.
 */
class MigrationTokenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOKEN -> if (!AppIdentity.IS_BRIDGE) {
                ReleaseToken.of(context).accept(intent.getStringExtra(EXTRA_TOKEN))
                LegacyPort.of(context).accept(intent.getIntExtra(EXTRA_PORT, 0))
            }
            ACTION_STATUS -> if (AppIdentity.IS_BRIDGE && isOrderedBroadcast) {
                val retired = BridgeRetirement.isRetired(context)
                resultCode = if (retired) STATUS_RETIRED else STATUS_ACTIVE
                // A bridge killed after its service stopped but before it retired has nothing left to
                // restart it, and the successor can only ask an HTTP server that is running. Never while
                // a shutdown is armed, though: the successor polls this during the handover itself, and
                // a service generation started then would wait behind the hold and run after retirement.
                if (!retired && !UpgradeShutdownCoordinator.isArmed()) runCatching { PaneldService.start(context) }
            }
        }
    }

    companion object {
        const val ACTION_TOKEN = "io.github.maxlyth.hapaneld.action.MIGRATION_TOKEN"
        const val ACTION_STATUS = "io.github.maxlyth.hapaneld.action.MIGRATION_STATUS"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PORT = "port"
        const val PERMISSION = "io.github.maxlyth.hapaneld.permission.IDENTITY_MIGRATION"
        const val STATUS_ACTIVE = 1
        const val STATUS_RETIRED = 2
        private const val STATUS_TIMEOUT_MS = 10_000L

        private fun addressed(action: String, applicationId: String): Intent = Intent(action)
            .setComponent(ComponentName(applicationId, MigrationTokenReceiver::class.java.name))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

        /** Bridge: address the token to the successor package alone, never as an implicit broadcast. */
        fun deliver(context: Context, token: String, httpPort: Int): Boolean = runCatching {
            context.sendBroadcast(
                addressed(ACTION_TOKEN, AppIdentity.SUCCESSOR)
                    .putExtra(EXTRA_TOKEN, token)
                    .putExtra(EXTRA_PORT, httpPort),
            )
            true
        }.getOrDefault(false)

        /** Successor: true only when the legacy app answers that its retired marker is durable. */
        suspend fun legacyRetired(context: Context): Boolean = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val answer = object : BroadcastReceiver() {
                    override fun onReceive(receiving: Context, intent: Intent) {
                        if (continuation.isActive) continuation.resume(resultCode == STATUS_RETIRED)
                    }
                }
                runCatching {
                    context.sendOrderedBroadcast(
                        addressed(ACTION_STATUS, AppIdentity.LEGACY),
                        null,
                        answer,
                        null,
                        0,
                        null,
                        null,
                    )
                }.onFailure { if (continuation.isActive) continuation.resume(false) }
            }
        } ?: false
    }
}
