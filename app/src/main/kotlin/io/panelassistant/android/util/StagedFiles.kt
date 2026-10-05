package io.panelassistant.android.util

import java.io.File

/**
 * Cleanup scope for temporary files staged during a guarded operation. Every temp file created inside
 * the guarded block is registered with [stage]; when the block finishes — by returning **or** by
 * throwing — any staged file is deleted **unless** the block first called [commit] to hand ownership
 * off to a longer-lived owner.
 *
 * This folds the hand-rolled `try { … } finally { file.delete() }` / `catch { files.forEach delete }` /
 * `if (!ownershipTransferred) files.forEach delete` idioms onto one primitive:
 *   - a temp that is *never* kept (e.g. a scratch file whose extracted value is returned by copy) simply
 *     never [commit]s, so it is always deleted — identical to a `finally { delete() }`;
 *   - a temp that is kept only on success [commit]s on the success path, so failure/throw deletes it;
 *   - a list of temps whose ownership transfers to a caller [commit]s once the handoff is certain.
 *
 * The delete condition (when to [commit]) stays at the call site, so the observable "kept vs deleted on
 * each success/failure path" behaviour is preserved exactly.
 */
class StagedFiles @PublishedApi internal constructor() {
    private val files = ArrayList<File>()
    private var committed = false

    /** Register [file] for delete-unless-committed cleanup and return it for fluent use. */
    fun <T : File> stage(file: T): T {
        files.add(file)
        return file
    }

    /** Hand the staged files off to a longer-lived owner; cleanup will then keep them. */
    fun commit() {
        committed = true
    }

    @PublishedApi internal fun cleanup() {
        if (!committed) files.forEach { it.delete() }
    }
}

/**
 * Run [block] within a [StagedFiles] cleanup scope. Any file [StagedFiles.stage]d but not
 * [StagedFiles.commit]ted is deleted when [block] returns or throws.
 */
inline fun <T> withStagedFiles(block: (StagedFiles) -> T): T {
    val staged = StagedFiles()
    try {
        return block(staged)
    } finally {
        staged.cleanup()
    }
}
