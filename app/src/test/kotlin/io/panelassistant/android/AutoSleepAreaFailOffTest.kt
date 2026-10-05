package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AutoSleepAreaFailOffTest {
    @Test fun `direct ON commit and discard can overlap replay without deadlock or stale OFF`() {
        val authority = LiveSettingAuthority(setOf("auto_sleep"))
        authority.applyOrQueueIf(
            "auto_sleep", "false", "true", fence = 2L, expected = { true },
        ) { _, _, _ -> LiveSettingApplyResult.DEFERRED }
        val enabled = AtomicBoolean(false)
        val generation = AtomicLong(2L)
        val refreshes = AtomicInteger()
        val staleBridgeCalls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replay = thread {
            authority.replay { _, _, _, fence ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                applyAutoSleepFencedReplay(
                    fence = checkNotNull(fence),
                    currentGeneration = generation.get(),
                    currentEnabled = enabled.get(),
                    persistOff = { error("stale replay attempted persistence") },
                    refreshController = { refreshes.incrementAndGet() },
                    applyBridge = { staleBridgeCalls.incrementAndGet(); LiveSettingApplyResult.APPLIED },
                )
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        enabled.set(true)
        generation.set(3L)
        val directOn = thread {
            acceptCommittedAutoSleepSetting(authority) { refreshes.incrementAndGet() }
        }
        directOn.join(1_000L)
        assertFalse("direct ON callback deadlocked behind replay", directOn.isAlive)
        release.countDown()
        replay.join(1_000L)

        assertFalse(replay.isAlive)
        assertTrue(enabled.get())
        assertEquals(3L, generation.get())
        assertEquals(1, refreshes.get())
        assertEquals(0, staleBridgeCalls.get())
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `durable OFF refreshes once and deferred bridge work replays`() {
        val authority = LiveSettingAuthority(setOf("auto_sleep"))
        var enabled = true
        var refreshes = 0
        var bridgeCalls = 0

        assertTrue(authority.applyOrQueueIf(
            "auto_sleep", "false", "true", expected = { enabled },
        ) { _, _, _ ->
            applyAutoSleepAreaFailOff(
                expectedEpoch = 7L,
                expectedSettingGeneration = 10L,
                epochIsCurrent = { it == 7L },
                persistOff = { generation ->
                    assertEquals(10L, generation)
                    enabled = false
                    AutoSleepWriteResult.COMMITTED
                },
                refreshController = { refreshes++ },
                applyBridge = {
                    bridgeCalls++
                    LiveSettingApplyResult.DEFERRED
                },
            )
        })

        assertFalse(enabled)
        assertEquals(1, refreshes)
        assertEquals(1, bridgeCalls)
        assertEquals(mapOf("auto_sleep" to "false"), authority.pendingSnapshot())

        authority.replay { key, value, previous ->
            assertEquals(Triple("auto_sleep", "false", "true"), Triple(key, value, previous))
            bridgeCalls++
            LiveSettingApplyResult.APPLIED
        }
        assertEquals(2, bridgeCalls)
        assertEquals(1, refreshes)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `stale enable epoch is rejected before it can queue OFF`() {
        val authority = LiveSettingAuthority(setOf("auto_sleep"))
        var applied = false

        assertFalse(authority.applyOrQueueIf(
            "auto_sleep", "false", "true", expected = { false },
        ) { _, _, _ ->
            applied = true
            LiveSettingApplyResult.APPLIED
        })

        assertFalse(applied)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `idempotent durable OFF still converges bridge without another refresh`() {
        var refreshes = 0
        var bridgeCalls = 0
        val result = applyAutoSleepAreaFailOff(
            expectedEpoch = 3L,
            expectedSettingGeneration = 4L,
            epochIsCurrent = { true },
            persistOff = { AutoSleepWriteResult.UNCHANGED },
            refreshController = { refreshes++ },
            applyBridge = { bridgeCalls++; LiveSettingApplyResult.APPLIED },
        )

        assertEquals(LiveSettingApplyResult.APPLIED, result)
        assertEquals(0, refreshes)
        assertEquals(1, bridgeCalls)
    }

    @Test fun `epoch superseded after queue clears stale intent without mutation`() {
        var persisted = false
        var refreshed = false
        var bridged = false

        assertEquals(
            LiveSettingApplyResult.APPLIED,
            applyAutoSleepAreaFailOff(
                expectedEpoch = 9L,
                expectedSettingGeneration = 12L,
                epochIsCurrent = { false },
                persistOff = { persisted = true; AutoSleepWriteResult.COMMITTED },
                refreshController = { refreshed = true },
                applyBridge = { bridged = true; LiveSettingApplyResult.APPLIED },
            ),
        )
        assertFalse(persisted)
        assertFalse(refreshed)
        assertFalse(bridged)
    }

    @Test fun `deferred fail off superseded by direct MQTT ON cannot replay OFF`() {
        val authority = LiveSettingAuthority(setOf("auto_sleep"))
        var generation = 1L
        var enabled = true
        var bridgeOffs = 0
        assertTrue(authority.applyOrQueueIf(
            "auto_sleep", "false", "true", fence = 2L,
            expected = { enabled && generation == 1L },
        ) { _, _, _ ->
            enabled = false
            generation = 2L
            bridgeOffs++
            LiveSettingApplyResult.DEFERRED
        })

        // A direct committed MQTT ON is the newer authority and immediately retires queued OFF.
        enabled = true
        generation = 3L
        var refreshes = 0
        acceptCommittedAutoSleepSetting(authority) { refreshes++ }
        authority.replay { _, _, _, _ ->
            bridgeOffs++
            LiveSettingApplyResult.APPLIED
        }

        assertTrue(enabled)
        assertEquals(3L, generation)
        assertEquals(1, bridgeOffs)
        assertEquals(1, refreshes)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `old fail off cannot cross an interleaved newer OFF then ON generation`() {
        var enabled = true
        var generation = 5L
        var refreshes = 0

        // Newer direct commands complete before the old callback reaches its atomic CAS.
        enabled = false
        generation = 6L
        enabled = true
        generation = 7L
        val result = applyAutoSleepAreaFailOff(
            expectedEpoch = 4L,
            expectedSettingGeneration = 5L,
            epochIsCurrent = { true },
            persistOff = { expected ->
                if (!enabled || generation != expected) null else {
                    enabled = false
                    generation++
                    AutoSleepWriteResult.COMMITTED
                }
            },
            refreshController = { refreshes++ },
            applyBridge = { error("stale OFF reached bridge") },
        )

        assertEquals(LiveSettingApplyResult.APPLIED, result)
        assertTrue(enabled)
        assertEquals(7L, generation)
        assertEquals(0, refreshes)
    }

    @Test fun `fenced replay discards OFF after a newer generation even if direct discard was interrupted`() {
        var persisted = false
        var bridged = false
        val result = applyAutoSleepFencedReplay(
            fence = 2L,
            currentGeneration = 3L,
            currentEnabled = true,
            persistOff = { persisted = true; AutoSleepWriteResult.COMMITTED },
            refreshController = {},
            applyBridge = { bridged = true; LiveSettingApplyResult.APPLIED },
        )

        assertEquals(LiveSettingApplyResult.APPLIED, result)
        assertFalse(persisted)
        assertFalse(bridged)
    }

    @Test fun `fenced replay completes a journaled OFF whose config commit was interrupted`() {
        var enabled = true
        var generation = 1L
        var refreshes = 0
        var bridged = 0
        val result = applyAutoSleepFencedReplay(
            fence = 2L,
            currentGeneration = generation,
            currentEnabled = enabled,
            persistOff = { expected ->
                if (generation != expected || !enabled) null else {
                    enabled = false
                    generation++
                    AutoSleepWriteResult.COMMITTED
                }
            },
            refreshController = { refreshes++ },
            applyBridge = { bridged++; LiveSettingApplyResult.APPLIED },
        )

        assertEquals(LiveSettingApplyResult.APPLIED, result)
        assertFalse(enabled)
        assertEquals(2L, generation)
        assertEquals(1, refreshes)
        assertEquals(1, bridged)
    }
}
