package io.panelassistant.android.control

import kotlinx.coroutines.delay

/**
 * The one place outside a root action that may start `su` to learn whether it works, owned by the
 * service and never by a read path. Status reads answer from [SuShell.observedUsable], which only real
 * root outcomes move, so without this a start-up check that lost the race with the root manager left
 * su unproven for the whole process, and a loss stayed recorded until some unrelated root action ran.
 *
 * While a proof is needed ([SuShell.needsRecoveryProof]) it runs one bounded `su true` at each offset
 * after start (about 30 s, 2 min and 10 min), stops at the first success, and then waits. A loss
 * recorded later re-arms the same schedule. So a process spends at most a few su starts here, never one
 * per poll, and a panel without su (latched by the exec-failure cache) starts nothing.
 */
internal class SuRecoveryProof(
    private val needsProof: () -> Boolean,
    private val prove: () -> Boolean,
    private val lossCount: () -> Long,
    private val awaitLossAfter: suspend (Long) -> Unit,
    private val offsetsMs: List<Long> = OFFSETS_MS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Runs until cancelled with the service scope. [prove] blocks, so call this on an IO dispatcher. */
    suspend fun run() {
        while (true) {
            val armedAt = lossCount()
            var elapsed = 0L
            for (offset in offsetsMs) {
                sleep(offset - elapsed)
                elapsed = offset
                if (!needsProof()) break
                if (prove()) break
            }
            awaitLossAfter(armedAt)
        }
    }

    companion object {
        val OFFSETS_MS = listOf(30_000L, 120_000L, 600_000L)

        fun of(shell: SuShell) = SuRecoveryProof(
            needsProof = shell::needsRecoveryProof,
            prove = shell::proveNow,
            lossCount = shell::lossCount,
            awaitLossAfter = shell::awaitLossAfter,
        )
    }
}
