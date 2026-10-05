package io.panelassistant.android.control

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AutoSleepLearningTest {
    @Test fun `empty local evidence respects the ten minute safety floor`() {
        val store = AutoSleepLearningStore(fakePreferences(), { 5L * DAY_MS }, Unit)

        assertEquals(10L * 60_000L, store.learnedLease("area-a", 1L).leaseMs)
    }

    @Test fun `touch percentile aggregates matching buckets across retained days`() {
        var now = 10L * DAY_MS
        val store = AutoSleepLearningStore(fakePreferences(), { now }, Unit)
        store.recordGap("area-a", AutoSleepLocalEvidence.TOUCH, 16L * 60_000L)
        now += DAY_MS
        store.recordGap("area-a", AutoSleepLocalEvidence.TOUCH, 16L * 60_000L)
        now += DAY_MS
        store.recordGap("area-a", AutoSleepLocalEvidence.TOUCH, 16L * 60_000L)

        assertEquals(22L * 60_000L, store.learnedLease("area-a", 15L * 60_000L).leaseMs)
    }

    @Test fun `proximity learning is capped and partition changes discard previous evidence`() {
        val store = AutoSleepLearningStore(fakePreferences(), { 20L * DAY_MS }, Unit)
        repeat(3) { store.recordGap("area-a", AutoSleepLocalEvidence.PROXIMITY, 55L * 60_000L) }
        assertEquals(30L * 60_000L, store.learnedLease("area-a", 15L * 60_000L).leaseMs)
        assertEquals(15L * 60_000L, store.learnedLease("area-b", 15L * 60_000L).leaseMs)
    }

    @Test fun `premature sleep correction decays after its protected day`() {
        var now = 30L * DAY_MS
        val store = AutoSleepLearningStore(fakePreferences(), { now }, Unit)
        store.recordCorrection("area-a", 40L * 60_000L)
        assertEquals(40L * 60_000L, store.learnedLease("area-a", 15L * 60_000L).leaseMs)
        now += 3L * DAY_MS
        assertEquals(30L * 60_000L, store.learnedLease("area-a", 15L * 60_000L).leaseMs)
        now += 5L * DAY_MS
        assertEquals(15L * 60_000L, store.learnedLease("area-a", 15L * 60_000L).leaseMs)
    }

    @Test fun `learning reads do not rewrite unchanged preferences`() {
        var writes = 0
        val preferences = fakePreferences { writes += 1 }
        val store = AutoSleepLearningStore(preferences, { 40L * DAY_MS }, Unit)

        repeat(3) { store.learnedLease("area-a", 15L * 60_000L) }

        assertEquals(1, writes)
    }

    @Test fun `event bursts are persisted once per interval and flushed at shutdown`() {
        var now = 50L * DAY_MS
        var writes = 0
        val preferences = fakePreferences { writes += 1 }
        val store = AutoSleepLearningStore(preferences, { now }, Unit)

        repeat(3) {
            store.recordGap("area-a", AutoSleepLocalEvidence.TOUCH, 16L * 60_000L)
            now += 1_000L
        }
        assertEquals(1, writes)
        assertEquals(22L * 60_000L, store.learnedLease("area-a", 15L * 60_000L).leaseMs)
        assertEquals(1, writes)

        store.flush()
        assertEquals(2, writes)
        val restarted = AutoSleepLearningStore(preferences, { now }, Unit)
        assertEquals(22L * 60_000L, restarted.learnedLease("area-a", 15L * 60_000L).leaseMs)
        assertEquals(2, writes)
    }

    @Test fun `partition activation durably discards evidence owned by the old source set`() {
        var writes = 0
        val preferences = fakePreferences { writes += 1 }
        val store = AutoSleepLearningStore(preferences, { 60L * DAY_MS }, Unit, minimumWriteIntervalMs = 0L)
        repeat(3) { store.recordGap("area-a", AutoSleepLocalEvidence.TOUCH, 16L * 60_000L) }
        assertEquals(22L * 60_000L, store.learnedLease("area-a", 15L * 60_000L).leaseMs)

        assertEquals(15L * 60_000L, store.learnedLease("area-b", 15L * 60_000L).leaseMs)
        val restarted = AutoSleepLearningStore(preferences, { 60L * DAY_MS }, Unit)
        assertEquals(15L * 60_000L, restarted.learnedLease("area-a", 15L * 60_000L).leaseMs)
    }

    @Test fun `premature sleep correction bypasses the routine evidence write interval`() {
        var writes = 0
        val preferences = fakePreferences { writes += 1 }
        val store = AutoSleepLearningStore(preferences, { 70L * DAY_MS }, Unit)
        store.recordGap("area-a", AutoSleepLocalEvidence.TOUCH, 16L * 60_000L)
        store.recordGap("area-a", AutoSleepLocalEvidence.TOUCH, 16L * 60_000L)
        assertEquals(1, writes)

        store.recordCorrection("area-a", 40L * 60_000L)

        assertEquals(2, writes)
        val restarted = AutoSleepLearningStore(preferences, { 70L * DAY_MS }, Unit)
        assertEquals(40L * 60_000L, restarted.learnedLease("area-a", 15L * 60_000L).leaseMs)
    }

    @Test fun `corrupt persisted aggregate is replaced with a valid empty state`() {
        var writes = 0
        val preferences = fakePreferences { writes += 1 }
        AutoSleepLearningStore(preferences, { 80L * DAY_MS }, Unit)
            .learnedLease("area-a", 15L * 60_000L)
        preferences.edit().putString("state", "{").apply()
        val beforeRecovery = writes

        val recovered = AutoSleepLearningStore(preferences, { 80L * DAY_MS }, Unit)
        assertEquals(15L * 60_000L, recovered.learnedLease("area-a", 15L * 60_000L).leaseMs)

        assertEquals(beforeRecovery + 1, writes)
        val raw = preferences.getString("state", "")
        org.json.JSONObject(raw.orEmpty())
    }

    private fun fakePreferences(onApply: () -> Unit = {}): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1]; editor }
                "clear" -> { values.clear(); editor }
                "apply" -> { onApply(); null }
                "commit" -> true
                "toString" -> "AutoSleepLearningEditor"
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] as? String ?: args[1]
                "edit" -> editor
                "contains" -> values.containsKey(args!![0] as String)
                "getAll" -> values.toMap()
                "toString" -> "AutoSleepLearningPreferences"
                else -> null
            }
        } as SharedPreferences
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60_000L
    }
}
