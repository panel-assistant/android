package io.panelassistant.android.migration

import android.content.Context
import io.panelassistant.android.util.DurableRecoveryMarker
import java.io.File

/**
 * The bridge's durable "I have handed over" record.
 *
 * It is written before the bridge gives up HOME or port 8888 and is never cleared: from that instant
 * the successor owns the panel, and a bridge process that Android restarts for any reason (a crash, a
 * broadcast, low memory) must stay idle rather than rebind the port or resume a kiosk loop beside it.
 * Only removing the package ends the record. It lives in `noBackupFilesDir`, so it is never part of a
 * settings backup and cannot retire a panel that merely restored one.
 */
internal object BridgeRetirement {
    private const val DIRECTORY = "identity-migration"
    private const val MARKER = "bridge-retired.v1"

    fun marker(context: Context): DurableRecoveryMarker = marker(context.noBackupFilesDir)

    internal fun marker(noBackupFilesDir: File): DurableRecoveryMarker =
        DurableRecoveryMarker(noBackupFilesDir.resolve(DIRECTORY).resolve(MARKER))

    fun isRetired(context: Context): Boolean = marker(context).isArmed()
}
