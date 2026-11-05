package io.panelassistant.android.http

import android.util.Log
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.InstallProgress
import java.io.File

/**
 * A durable note that a restore is under way, so one cut short by the end of the app process is reported
 * rather than lost. Android ends the process whenever one of the app's app-ops or permissions changes, and
 * a restore can then stop after some of its writes and before the rest; the in-memory result goes with it.
 *
 * The restore writes the note before its first durable write and marks it once the configuration is saved;
 * it removes the note once every write is done, while it still holds the lane, so the next restore's
 * note is never removed by this one. A later service start that finds the note reports the
 * restore as interrupted through /api/v1/install/status, and keeps reporting it across further restarts
 * until the next operation claims the lane. The restore is not resumed: the owner restores the backup again.
 */
internal class RestoreJournal(private val directory: () -> File) {
    private fun file() = File(directory(), FILE)

    fun begin() = write(STAGE_STARTED)

    fun configSaved() = write(STAGE_CONFIG_SAVED)

    fun end() {
        runCatching { file().delete() }.onFailure { Log.w(TAG, "restore journal not removed", it) }
    }

    /** Report a restore an earlier process left unfinished; false when there is none. */
    fun reportInterrupted(): Boolean {
        val stage = runCatching { file().takeIf(File::isFile)?.readText()?.trim() }.getOrNull() ?: return false
        val configSaved = stage == STAGE_CONFIG_SAVED
        val reported = InstallProgress.reportFinished(
            component = "Restore",
            result = if (configSaved) {
                "Restore interrupted after the configuration was saved; restore the backup again"
            } else {
                "Restore interrupted before the configuration was saved; restore the backup again"
            },
            structured = InstallProgress.OperationResult(
                status = if (configSaved) InstallProgress.Outcome.PARTIAL else InstallProgress.Outcome.FAILED,
                config = InstallProgress.ComponentResult(
                    if (configSaved) InstallProgress.Outcome.PARTIAL else InstallProgress.Outcome.FAILED,
                    detail = if (configSaved) "interrupted after the configuration was saved"
                        else "interrupted before the configuration was saved",
                ),
                interrupted = true,
            ),
            presentation = InstallPresentation(if (configSaved) "restore-partial" else "restore-failed"),
            onSuperseded = ::end,
        )
        if (reported) Log.w(TAG, "restore: an earlier restore was interrupted ($stage)")
        return reported
    }

    private fun write(stage: String) {
        runCatching {
            val target = file()
            val staged = File(target.parentFile, "$FILE.tmp")
            staged.writeText(stage)
            check(staged.renameTo(target)) { "rename failed" }
        }.onFailure { Log.w(TAG, "restore journal not written", it) }
    }

    private companion object {
        const val FILE = "restore-in-progress"
        const val STAGE_STARTED = "started"
        const val STAGE_CONFIG_SAVED = "config-saved"
    }
}

private const val TAG = "ha-paneld/http"
