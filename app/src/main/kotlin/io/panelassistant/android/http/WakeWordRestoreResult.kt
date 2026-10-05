package io.panelassistant.android.http

import io.panelassistant.android.util.InstallProgress

/**
 * The restore result's `wake_words` component: how many carried wake words were imported again, and a
 * short warning naming each one the engine or the catalogue refused. Ids are catalogue ids (lower case
 * letters, digits and underscores) and the reasons are the catalogue's own fixed texts, never bundle data.
 */
internal fun wakeWordRestoreComponent(
    outcome: io.panelassistant.android.backup.WakeWordBackup.Outcome,
): InstallProgress.ComponentResult = InstallProgress.ComponentResult(
    status = if (outcome.refused.isEmpty()) InstallProgress.Outcome.SUCCEEDED else InstallProgress.Outcome.PARTIAL,
    items = outcome.restored.size,
    detail = outcome.refused.takeIf { it.isNotEmpty() }?.let { "not restored: ${outcome.warning()}" }.orEmpty(),
)

/**
 * A restore that could not put back an imported wake word is not a success: the settings may select that
 * word, and a migration must not retire the old install while its model is missing here.
 */
internal fun restoreOverallStatus(
    wakeWords: io.panelassistant.android.backup.WakeWordBackup.Outcome?,
): InstallProgress.Outcome =
    if (wakeWords?.refused?.isNotEmpty() == true) InstallProgress.Outcome.PARTIAL else InstallProgress.Outcome.SUCCEEDED

/** The completion text's note about wake words a restore could not put back, or empty. */
internal fun wakeWordRestoreNote(outcome: io.panelassistant.android.backup.WakeWordBackup.Outcome?): String =
    outcome?.refused?.takeIf { it.isNotEmpty() }
        ?.let { "; ${it.size} wake word${if (it.size == 1) "" else "s"} not restored (${it.joinToString(", ") { (id, _) -> id }})" }
        .orEmpty()
