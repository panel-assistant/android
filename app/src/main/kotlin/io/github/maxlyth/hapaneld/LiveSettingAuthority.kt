package io.github.maxlyth.hapaneld

import android.content.Context
import io.github.maxlyth.hapaneld.persistence.AppState
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * This boot's kernel identity, the same `/proc` value the guard-DB startup sentinel keys on. It needs no
 * permission, changes on every boot and cannot be reused within one, which is exactly what separates
 * "two independent boots agreed" from "one boot retried twice".
 */
internal fun kernelBootIdentity(): String? =
    runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
        .getOrNull()
        ?.takeIf { BOOT_ID.matches(it) }

private val BOOT_ID =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/**
 * The stored form of one journal entry. This is the only thing that carries an observation across a
 * real reboot — an entry that encodes without its boots can never accumulate the second one — so both
 * directions are tested rather than trusted to the storage layer.
 */
internal fun encodeJournalEntry(value: LiveSettingAuthority.Pending): String =
    JSONObject().put("value", value.value).apply {
        value.previousValue?.let { put("previous", it) }
        value.fence?.let { put("fence", it) }
        put("generation", value.generation)
        if (value.unavailableBoots.isNotEmpty()) {
            put("unavailable_boots", JSONArray(value.unavailableBoots.toList()))
        }
    }.toString()

internal fun decodeJournalEntry(encoded: String): LiveSettingAuthority.Pending {
    val decoded = runCatching { JSONObject(encoded) }.getOrNull()
    // A journal written before desired/previous were separated holds the bare desired value.
    if (decoded?.has("value") != true) return LiveSettingAuthority.Pending(encoded, null)
    return LiveSettingAuthority.Pending(
        decoded.getString("value"),
        decoded.optString("previous").takeIf { decoded.has("previous") },
        decoded.optLong("fence").takeIf { decoded.has("fence") },
        decoded.optString("generation").takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
        decodeUnavailableBoots(decoded),
    )
}

/**
 * Boot identities are validated where they are produced, so this keeps whatever was stored rather than
 * re-imposing a format — an entry recorded under one identity scheme must never be silently emptied by
 * a reader that expects another, which would quietly reset a stall on every reboot. The cap only stops
 * a corrupt file from being unbounded; nothing beyond the threshold is ever recorded in the first place.
 */
private fun decodeUnavailableBoots(decoded: JSONObject): Set<String> {
    val encoded = decoded.optJSONArray("unavailable_boots") ?: return emptySet()
    return (0 until encoded.length())
        .mapNotNull { index -> encoded.optString(index).takeIf(String::isNotBlank) }
        .take(LiveSettingAuthority.STALL_OBSERVATION_BOOTS)
        .toCollection(linkedSetOf())
}

internal enum class LiveSettingApplyResult {
    APPLIED,
    DEFERRED,
    FAILED,

    /**
     * The applier ran and found no path to this hardware at all — not "it failed this time".
     *
     * Only the operation that actually ran may report this, and only when every path it owns reported
     * structural absence (a permission the app does not hold, an executable that is not on the device).
     * A denial, a timeout, a helper that has not started yet and a draining bridge are all [FAILED].
     *
     * The consequence is deliberately small: this never drops the journalled intent and never stops the
     * replay, so a misclassification costs one wrong sentence in the Configure banner and nothing else.
     * That is what makes the ambiguity at the helper and root paths safe to live with.
     */
    UNAVAILABLE,
}

/** Immediate request disposition plus an optional observable terminal result for admitted work that
 * outlived the bounded response wait. */
internal class LiveSettingApplication(
    val initial: LiveSettingApplyResult,
    private val lateCompletion: (((LiveSettingApplyResult) -> Unit) -> Unit)? = null,
) {
    val hasLateCompletion: Boolean get() = lateCompletion != null
    fun observeLateCompletion(observer: (LiveSettingApplyResult) -> Unit) {
        lateCompletion?.invoke(observer)
    }

    companion object {
        fun immediate(result: LiveSettingApplyResult) = LiveSettingApplication(result)
    }
}

/** Request-level disposition after the desired value has (or has not) entered durable ownership. */
internal enum class LiveSettingRequestOutcome {
    APPLIED,
    DEFERRED,
    FAILED_PENDING,
    REJECTED;

    val legacyAcknowledged: Boolean get() = this == APPLIED || this == DEFERRED
    val pending: Boolean get() = this == DEFERRED || this == FAILED_PENDING
    val durablyAccepted: Boolean get() = this != REJECTED
}

/**
 * Keeps HTTP-originated live settings authoritative while the replaceable MQTT runtime is draining.
 *
 * A bridge can reject an otherwise valid setting because reconfiguration has closed its command
 * admission. Journal the latest accepted value synchronously before trying the bridge, then remove it
 * only after the side effect succeeds. A process death or failed startup therefore replays acknowledged
 * work instead of losing it with the retiring bridge.
 */
internal class LiveSettingAuthority(
    private val supportedKeys: Set<String>,
    private val journal: Journal = MemoryJournal(),
    bootIdentity: () -> String? = ::kernelBootIdentity,
) {
    internal data class Pending(
        val value: String,
        val previousValue: String?,
        val fence: Long? = null,
        val generation: String = UUID.randomUUID().toString(),
        /**
         * The distinct boots in which an applier reported [LiveSettingApplyResult.UNAVAILABLE] for this
         * value. Repeated attempts within one boot are one observation, because they are one reading of
         * one machine state — a helper that started late looks structurally absent for that whole boot.
         */
        val unavailableBoots: Set<String> = emptySet(),
    ) {
        /**
         * Enough independent boots have found no path to this hardware that "waiting to apply" is no
         * longer the honest thing to say. Presentation only: a stalled entry stays journalled and keeps
         * replaying, so repairing the panel still applies it.
         */
        val stalled: Boolean get() = unavailableBoots.size >= STALL_OBSERVATION_BOOTS
    }

    /** Read once, off every lock, so no apply path ever performs I/O while holding the journal. */
    private val currentBoot: String? = runCatching(bootIdentity).getOrNull()

    internal interface Journal {
        fun load(): Map<String, Pending>
        fun put(key: String, value: Pending): Boolean
        fun remove(key: String): Boolean
    }

    private class MemoryJournal : Journal {
        private val values = linkedMapOf<String, Pending>()
        override fun load(): Map<String, Pending> = values.toMap()
        override fun put(key: String, value: Pending): Boolean { values[key] = value; return true }
        override fun remove(key: String): Boolean { values.remove(key); return true }
    }

    private val pending = journal.load().filterKeys(supportedKeys::contains).toMutableMap()
    /** Pending generations already admitted to a dispatcher. Replays must not enqueue the same desired
     * value behind its original execution, because a newer external command may already follow it. */
    private val inFlight = mutableMapOf<String, Pending>()
    /** Serializes runtime application of one setting without blocking journal inspection or discard. */
    private val applyLocks = supportedKeys.associateWith { Any() }

    fun applyOrQueue(
        key: String,
        value: String,
        apply: (String, String) -> LiveSettingApplyResult,
    ): Boolean = applyOrQueueOutcome(key, value, null) { appliedKey, appliedValue, _ ->
        apply(appliedKey, appliedValue)
    }.legacyAcknowledged

    fun applyOrQueue(
        key: String,
        value: String,
        previousValue: String?,
        apply: (String, String, String?) -> LiveSettingApplyResult,
    ): Boolean = applyOrQueueOutcome(key, value, previousValue, apply).legacyAcknowledged

    fun applyOrQueueOutcome(
        key: String,
        value: String,
        previousValue: String?,
        apply: (String, String, String?) -> LiveSettingApplyResult,
    ): LiveSettingRequestOutcome = applyOrQueueOutcomeIf(
        key, value, previousValue, expected = { true },
        apply = { appliedKey, appliedValue, previous ->
            LiveSettingApplication.immediate(apply(appliedKey, appliedValue, previous))
        },
    )

    fun applyOrQueueOutcomeObserved(
        key: String,
        value: String,
        previousValue: String?,
        apply: (String, String, String?) -> LiveSettingApplication,
    ): LiveSettingRequestOutcome = applyOrQueueOutcomeIf(
        key, value, previousValue, expected = { true }, apply = apply,
    )

    /** Atomically rejects a stale safety intent before it can supersede a newer queued value. */
    fun applyOrQueueIf(
        key: String,
        value: String,
        previousValue: String?,
        fence: Long? = null,
        expected: () -> Boolean,
        apply: (String, String, String?) -> LiveSettingApplyResult,
    ): Boolean = applyOrQueueOutcomeIf(
        key, value, previousValue, fence, expected,
    ) { appliedKey, appliedValue, previous ->
        LiveSettingApplication.immediate(apply(appliedKey, appliedValue, previous))
    }.legacyAcknowledged

    internal fun applyOrQueueOutcomeIf(
        key: String,
        value: String,
        previousValue: String?,
        fence: Long? = null,
        expected: () -> Boolean,
        apply: (String, String, String?) -> LiveSettingApplication,
    ): LiveSettingRequestOutcome {
        val applyLock = applyLocks[key] ?: return LiveSettingRequestOutcome.REJECTED
        return synchronized(applyLock) {
            val queued = synchronized(this) {
                if (!expected()) return LiveSettingRequestOutcome.REJECTED
                // A latest-wins update retains provenance from before the first unapplied desired value.
                val superseded = pending[key]
                // Restating the same desired value is not new evidence about this hardware — the form
                // posts the durable desired value back on every unrelated save, and resetting here would
                // flip a stalled entry to "waiting to apply" and re-stall it two boots later, forever.
                // A genuinely different value is new intent and starts with no observations.
                val carried = if (superseded?.value == value) superseded.unavailableBoots else emptySet()
                Pending(value, superseded?.previousValue ?: previousValue, fence, unavailableBoots = carried).also {
                    if (!journal.put(key, it)) return LiveSettingRequestOutcome.REJECTED
                    pending[key] = it
                }
            }
            val application = runCatching { apply(key, value, queued.previousValue) }
                .getOrDefault(LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED))
            if (application.hasLateCompletion) {
                synchronized(this) {
                    if (pending[key] == queued) inFlight[key] = queued
                }
                application.observeLateCompletion { terminal ->
                    settleLateCompletion(key, queued, terminal)
                }
            }
            when (application.initial) {
                LiveSettingApplyResult.APPLIED -> {
                    val cleared = synchronized(this) {
                        // Discard may establish a newer external truth while application is blocked.
                        if (pending[key] != queued) true else if (journal.remove(key)) {
                            pending.remove(key)
                            true
                        } else false
                    }
                    if (cleared) LiveSettingRequestOutcome.APPLIED else LiveSettingRequestOutcome.FAILED_PENDING
                }
                LiveSettingApplyResult.DEFERRED -> LiveSettingRequestOutcome.DEFERRED
                LiveSettingApplyResult.FAILED -> LiveSettingRequestOutcome.FAILED_PENDING
                LiveSettingApplyResult.UNAVAILABLE -> {
                    recordUnavailable(key, queued)
                    // One save is never a stall. The request is pending exactly like any other failure;
                    // only observations from separate boots change how it is presented.
                    LiveSettingRequestOutcome.FAILED_PENDING
                }
            }
        }
    }

    /**
     * Note that this boot found no path for [queued]. The entry is never removed and never stops
     * replaying — this only records what was observed, so the banner can stop claiming the value is
     * about to apply. A boot already counted adds nothing, and an unreadable boot identity records
     * nothing at all rather than attributing the observation to the wrong boot.
     */
    private fun recordUnavailable(key: String, queued: Pending) {
        val boot = currentBoot ?: return
        synchronized(this) {
            val current = pending[key] ?: return
            if (current.generation != queued.generation) return
            // Already stalled: there is nothing further to learn, and recording every later boot would
            // grow the stored entry without bound on a panel that can never apply the value.
            if (current.stalled) return
            if (boot in current.unavailableBoots) return
            val observed = current.copy(unavailableBoots = current.unavailableBoots + boot)
            if (journal.put(key, observed)) pending[key] = observed
        }
    }

    private fun settleLateCompletion(key: String, queued: Pending, terminal: LiveSettingApplyResult) {
        val applyLock = applyLocks[key] ?: return
        synchronized(applyLock) {
            synchronized(this) {
                if (inFlight[key] == queued) inFlight.remove(key)
                if (terminal == LiveSettingApplyResult.APPLIED && pending[key] == queued && journal.remove(key)) {
                    pending.remove(key)
                }
            }
            if (terminal == LiveSettingApplyResult.UNAVAILABLE) recordUnavailable(key, queued)
        }
    }

    /** Supersede any queued HTTP intent without replaying it. Safety-sensitive settings use this when
     * another authority (for example MQTT or the boot escape window) establishes a newer truth. */
    @Synchronized
    fun discard(key: String): Boolean {
        if (key !in supportedKeys) return false
        if (pending.remove(key) == null) return true
        return journal.remove(key)
    }

    fun replay(apply: (String, String) -> LiveSettingApplyResult) {
        replay { key, value, _ -> apply(key, value) }
    }

    fun replay(apply: (String, String, String?) -> LiveSettingApplyResult) {
        replay { key, value, previous, _ -> apply(key, value, previous) }
    }

    fun replay(apply: (String, String, String?, Long?) -> LiveSettingApplyResult) {
        replayKeys(supportedKeys, apply)
    }

    fun replayKeys(keys: Set<String>, apply: (String, String, String?, Long?) -> LiveSettingApplyResult) {
        replayKeysObserved(keys) { key, value, previous, fence ->
            LiveSettingApplication.immediate(apply(key, value, previous, fence))
        }
    }

    fun replayKeysObserved(keys: Set<String>, apply: (String, String, String?, Long?) -> LiveSettingApplication) {
        val snapshot = synchronized(this) { pending.filterKeys(keys::contains).toList() }
        snapshot.forEach { (key, queued) ->
            synchronized(checkNotNull(applyLocks[key])) {
                // A newer apply may have superseded this snapshot while replay waited for the key.
                if (synchronized(this) { pending[key] == queued && inFlight[key] != queued }) {
                    val application = runCatching {
                        apply(key, queued.value, queued.previousValue, queued.fence)
                    }.getOrDefault(LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED))
                    if (application.hasLateCompletion) {
                        synchronized(this) {
                            if (pending[key] == queued) inFlight[key] = queued
                        }
                        application.observeLateCompletion { terminal ->
                            settleLateCompletion(key, queued, terminal)
                        }
                    }
                    if (application.initial == LiveSettingApplyResult.APPLIED) synchronized(this) {
                        if (pending[key] == queued && journal.remove(key)) {
                            pending.remove(key)
                        }
                    } else if (application.initial == LiveSettingApplyResult.UNAVAILABLE) {
                        recordUnavailable(key, queued)
                    }
                }
            }
        }
    }

    @Synchronized
    internal fun pendingSnapshot(): Map<String, String> = pending.mapValues { it.value.value }
    @Synchronized
    internal fun pendingGenerationSnapshot(): Map<String, String> = pending.mapValues { it.value.generation }
    internal fun pendingPreviousSnapshot(): Map<String, String?> = pending.mapValues { it.value.previousValue }

    /** The pending keys whose apply path independent boots have found absent. A subset of
     * [pendingSnapshot]; these entries are still journalled and still replayed. */
    @Synchronized
    internal fun pendingStalledSnapshot(): Set<String> =
        pending.filterValues { it.stalled }.keys.toSet()

    companion object {
        /**
         * Distinct boots that must find no path before an entry is presented as stalled rather than
         * waiting. Two, not one: a single boot where the helper socket was not up yet, or where the root
         * manager had not finished starting, is exactly the transient case that must keep saying
         * "waiting to apply".
         */
        internal const val STALL_OBSERVATION_BOOTS = 2
        private const val JOURNAL = "ha-paneld-live-setting-journal"

        fun persistent(context: Context, supportedKeys: Set<String>): LiveSettingAuthority {
            val preferences = AppState.preferences(context, "live-setting-journal", JOURNAL)
            val store = object : Journal {
                override fun load(): Map<String, Pending> = preferences.all.mapNotNull { (key, raw) ->
                    (raw as? String)?.let { encoded -> key to decodeJournalEntry(encoded) }
                }.toMap()

                override fun put(key: String, value: Pending): Boolean =
                    preferences.edit().putString(key, encodeJournalEntry(value)).commit()

                override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()
            }
            return LiveSettingAuthority(supportedKeys, store)
        }
    }
}
