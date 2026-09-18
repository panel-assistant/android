package io.github.maxlyth.hapaneld.migration

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.maxlyth.hapaneld.AppIdentity

/**
 * Successor side of [ReleaseToken] delivery. The manifest guards this receiver with the
 * signature-level migration permission, so Android only delivers a broadcast whose sender shares this
 * app's signer; the bridge build ignores it outright.
 */
class MigrationTokenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppIdentity.IS_BRIDGE || intent.action != ACTION) return
        ReleaseToken.of(context).accept(intent.getStringExtra(EXTRA_TOKEN))
    }

    companion object {
        const val ACTION = "io.github.maxlyth.hapaneld.action.MIGRATION_TOKEN"
        const val EXTRA_TOKEN = "token"
        const val PERMISSION = "io.github.maxlyth.hapaneld.permission.IDENTITY_MIGRATION"

        /** Bridge: address the token to the successor package alone, never as an implicit broadcast. */
        fun deliver(context: Context, token: String): Boolean = runCatching {
            context.sendBroadcast(
                Intent(ACTION)
                    .setComponent(
                        ComponentName(
                            AppIdentity.SUCCESSOR,
                            MigrationTokenReceiver::class.java.name,
                        ),
                    )
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(EXTRA_TOKEN, token),
            )
            true
        }.getOrDefault(false)
    }
}
