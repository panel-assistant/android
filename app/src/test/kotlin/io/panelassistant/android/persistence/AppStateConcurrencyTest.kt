package io.panelassistant.android.persistence

import android.content.SharedPreferences
import io.panelassistant.android.Config
import io.panelassistant.android.config.SettingsRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateConcurrencyTest {
    @Test fun concurrentFactoryAccessConstructsOneValue() {
        val cache = AtomicFactoryCache<String, Any>()
        val constructed = AtomicInteger()
        val start = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(12)
        try {
            val results = (1..48).map {
                callers.submit<Any> {
                    start.await()
                    cache.getOrCreate("namespace") {
                        constructed.incrementAndGet()
                        Thread.sleep(5)
                        Any()
                    }
                }
            }
            start.countDown()
            val first = results.first().get(5, TimeUnit.SECONDS)
            results.drop(1).forEach { assertSame(first, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, constructed.get())
        } finally {
            callers.shutdownNow()
        }
    }

    @Test fun applyPublishesMemoryAndListenersBeforeBackgroundPersistenceCompletes() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            },
        )
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )

            preferences.edit().putString("mode", "ready").apply()

            assertEquals("ready", preferences.getString("mode", null))
            assertEquals(listOf("mode"), changed)
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))
            assertTrue(persistence.events.isEmpty())
        } finally {
            releasePersistence.countDown()
            writer.shutdown()
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(listOf("persist:mode"), persistence.events)
    }

    @Test fun commitWaitsBehindEarlierApplyAndPreservesWriteOrder() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = { mutation ->
                if ("first" in mutation.changes) {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
            },
        )
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val secondVisible = CountDownLatch(1)
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "second") secondVisible.countDown()
                },
            )
            preferences.edit().putInt("first", 1).apply()
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            val committed = caller.submit<Boolean> {
                preferences.edit().putInt("second", 2).commit()
            }
            assertTrue(secondVisible.await(5, TimeUnit.SECONDS))
            assertFalse(committed.isDone)
            assertEquals(2, preferences.getInt("second", 0))

            releaseFirst.countDown()
            assertTrue(committed.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("persist:first", "persist:second"), persistence.events)
        } finally {
            releaseFirst.countDown()
            caller.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun durableVisibilityCommitPublishesOnlyAfterPersistenceAndRetainsPriorStateOnFailure() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            },
            failFirstPersist = true,
        )
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )

            val failed = caller.submit<Boolean> {
                preferences.commitWithDurableVisibility { putString("ownership", "allow") }
            }
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))
            assertFalse(failed.isDone)
            assertFalse(preferences.contains("ownership"))
            assertTrue(changed.isEmpty())

            releasePersistence.countDown()
            assertFalse(failed.get(5, TimeUnit.SECONDS))
            assertFalse(preferences.contains("ownership"))
            assertTrue(changed.isEmpty())

            assertTrue(
                preferences.commitWithDurableVisibility { putString("ownership", "allow") },
            )
            assertEquals("allow", preferences.getString("ownership", null))
            assertEquals(listOf("ownership"), changed)
            assertEquals(listOf("persist:ownership", "replace:ownership"), persistence.events)
        } finally {
            releasePersistence.countDown()
            caller.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun failedDurableVisibilityRemovalLeavesOwnershipVisibleForRetry() {
        val persistence = RecordingPersistence(failPersistCall = 2)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            assertTrue(
                preferences.commitWithDurableVisibility { putString("ownership", "foreground") },
            )

            assertFalse(preferences.commitWithDurableVisibility { remove("ownership") })
            assertEquals("foreground", preferences.getString("ownership", null))

            assertTrue(preferences.commitWithDurableVisibility { remove("ownership") })
            assertFalse(preferences.contains("ownership"))
            assertEquals(
                listOf("persist:ownership", "persist:ownership", "replace:"),
                persistence.events,
            )
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun configBatchFailureRetainsPriorValuesAndListenersUntilRetrySucceeds() {
        val persistence = RecordingPersistence(failPersistCall = 2)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            assertTrue(
                preferences.commitWithDurableVisibility { putString("panel_id", "old_panel") },
            )
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )
            val config = Config(preferences)
            val afterCommitCalls = AtomicInteger()

            assertFalse(config.applyBatch({ afterCommitCalls.incrementAndGet() }) {
                config.setPanelId("new_panel")
                config.setHttpAllowedHosts("panel.example")
            })

            assertEquals("old_panel", config.panelId)
            assertEquals("", config.httpAllowedHostsRaw)
            assertTrue(changed.isEmpty())
            assertEquals(0, afterCommitCalls.get())

            assertTrue(config.applyBatch({ afterCommitCalls.incrementAndGet() }) {
                config.setPanelId("new_panel")
                config.setHttpAllowedHosts("panel.example")
            })

            assertEquals("new_panel", config.panelId)
            assertEquals("panel.example", config.httpAllowedHostsRaw)
            assertEquals(setOf("panel_id", "http_allowed_hosts"), changed.toSet())
            assertEquals(1, afterCommitCalls.get())
            assertEquals(
                mapOf("panel_id" to "new_panel", "http_allowed_hosts" to "panel.example"),
                persistence.replacedSnapshot,
            )
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun configBatchPublishesValuesListenersAndCallbackOnlyAfterDurability() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            },
        )
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )
            val config = Config(preferences)
            val afterCommitCalls = AtomicInteger()

            val committed = caller.submit<Boolean> {
                config.applyBatch({ afterCommitCalls.incrementAndGet() }) {
                    config.setPanelId("new_panel")
                    config.setHttpAllowedHosts("panel.example")
                }
            }
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))

            assertFalse(committed.isDone)
            assertFalse(preferences.contains("panel_id"))
            assertEquals("", config.httpAllowedHostsRaw)
            assertTrue(changed.isEmpty())
            assertEquals(0, afterCommitCalls.get())

            releasePersistence.countDown()
            assertTrue(committed.get(5, TimeUnit.SECONDS))
            assertEquals("new_panel", config.panelId)
            assertEquals("panel.example", config.httpAllowedHostsRaw)
            assertEquals(setOf("panel_id", "http_allowed_hosts"), changed.toSet())
            assertEquals(1, afterCommitCalls.get())
        } finally {
            releasePersistence.countDown()
            caller.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun failedRawLiveSettingCommitKeepsOldValueAndIdenticalRetryAdmitted() {
        val persistence = RecordingPersistence(failPersistCall = 2)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val config = Config(preferences)
            val zoom = requireNotNull(SettingsRegistry.spec("dashboard_zoom"))
            assertTrue(config.commitRaw(zoom, "100"))
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )

            assertFalse(config.commitRaw(zoom, "125"))
            assertEquals("100", config.getRaw(zoom))
            assertTrue(changed.isEmpty())

            assertTrue(config.commitRaw(zoom, "125"))
            assertEquals("125", config.getRaw(zoom))
            assertEquals(listOf("dashboard_zoom"), changed)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun failedAreaLiveCommitPublishesNeitherValueNorOverrideAndRetryPublishesBoth() {
        val persistence = RecordingPersistence(failPersistCall = 2)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val config = Config(preferences)
            val area = requireNotNull(SettingsRegistry.spec("ha_area"))
            assertTrue(config.commitRaw(area, "Office"))
            assertEquals("Office", config.haArea)
            assertTrue(config.haAreaUserOverride)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )

            assertFalse(config.commitRaw(area, ""))
            assertEquals("Office", config.haArea)
            assertTrue(config.haAreaUserOverride)
            assertTrue(changed.isEmpty())

            assertTrue(config.commitRaw(area, ""))
            assertEquals("", config.haArea)
            assertFalse(config.haAreaUserOverride)
            assertEquals(setOf("ha_area", "device_local_ha_area_user_override"), changed.toSet())
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun failedBooleanSideEffectAuthorityCommitKeepsOldGetterUntilRetry() {
        val persistence = RecordingPersistence(failPersistCall = 2)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val config = Config(preferences)
            assertTrue(config.commitKioskLock(true))

            assertFalse(config.commitKioskLock(false))
            assertTrue(config.kioskLock)

            assertTrue(config.commitKioskLock(false))
            assertFalse(config.kioskLock)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun callerStagedConfigCommitAlsoDefersPublicationUntilDurable() {
        val persistence = RecordingPersistence(failPersistCall = 2)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val config = Config(preferences)
            val zoom = requireNotNull(SettingsRegistry.spec("dashboard_zoom"))
            config.editor().also { editor ->
                config.stage(editor, zoom, "100")
                assertTrue(config.commit(editor))
            }
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )
            val retryable = config.editor().also { config.stage(it, zoom, "125") }

            assertFalse(config.commit(retryable))
            assertEquals("100", config.getRaw(zoom))
            assertTrue(changed.isEmpty())

            assertTrue(config.commit(retryable))
            assertEquals("125", config.getRaw(zoom))
            assertEquals(listOf("dashboard_zoom"), changed)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun nextWriteReconcilesCompleteSnapshotAfterFailedApply() {
        val persistence = RecordingPersistence(failFirstPersist = true)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            preferences.edit().putString("first", "one").apply()

            assertTrue(preferences.edit().putString("second", "two").commit())

            assertEquals(listOf("persist:first", "replace:first,second"), persistence.events)
            assertEquals(mapOf("first" to "one", "second" to "two"), persistence.replacedSnapshot)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun upgradeMutationAndDowngradeEditSurviveBothDirections() {
        val legacy = RecordingLegacyMirror(
            linkedMapOf(
                "dashboard_url" to "http://old.example",
                "removed_later" to true,
            ),
        )
        val primary = ImportingPersistence(legacy.snapshot())
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )

            assertTrue(
                preferences.edit()
                    .putString("dashboard_url", "https://current.example")
                    .remove("removed_later")
                    .putStringSet("entities", setOf("light.kitchen", "sensor.zone"))
                    .commit(),
            )

            // Simulate the old 0.9.x APK opening and then editing its XML after a deliberate downgrade.
            assertEquals(
                mapOf(
                    "dashboard_url" to "https://current.example",
                    "entities" to setOf("light.kitchen", "sensor.zone"),
                ),
                legacy.snapshot(),
            )
            legacy.persist(
                StateMutation(
                    clear = false,
                    changes = mapOf("dashboard_url" to "http://edited-by-old-build.example"),
                ),
            )

            val returnedPreferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )
            assertEquals(
                "http://edited-by-old-build.example",
                returnedPreferences.getString("dashboard_url", null),
            )
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun compositeWriteDoesNotTouchEitherStoreWhenIntentIsNotDurable() {
        val original = linkedMapOf<String, Any>(
            "keep" to "old",
            "remove" to true,
        )
        val primary = ImportingPersistence(original)
        val legacy = RecordingLegacyMirror(original)
        val metadata = RecordingBridgeMetadata().also {
            it.writeHash(stateSnapshotHash(original))
            it.failNextIntent = true
        }
        val persistence = DowngradeCompatibleStatePersistence(primary, legacy, metadata)
        assertEquals(original, persistence.initialize())

        assertFalse(
            persistence.persist(
                StateMutation(
                    clear = false,
                    changes = mapOf("add" to "new", "remove" to null),
                ),
            ),
        )

        assertEquals(original, primary.snapshot())
        assertEquals(original, legacy.snapshot())
        assertTrue(metadata.readWriteIntent() == null)
    }

    @Test fun failedSqliteCompositeWriteCannotReimportAddedOrRemovedXmlOnRestart() {
        val original = linkedMapOf<String, Any>(
            "keep" to "old",
            "remove" to true,
        )
        val primary = ImportingPersistence(original)
        val legacy = RecordingLegacyMirror(original)
        val metadata = RecordingBridgeMetadata().also {
            it.writeHash(stateSnapshotHash(original))
        }
        val persistence = DowngradeCompatibleStatePersistence(primary, legacy, metadata)
        assertEquals(original, persistence.initialize())
        primary.failNextPersist = true

        assertFalse(
            persistence.persist(
                StateMutation(
                    clear = false,
                    changes = mapOf("add" to "new", "remove" to null),
                ),
            ),
        )
        assertEquals(original, primary.snapshot())
        assertEquals(original, legacy.snapshot())
        assertTrue(metadata.readWriteIntent() != null)

        val restarted = DowngradeCompatibleStatePersistence(primary, legacy, metadata).initialize()
        assertEquals(original, restarted)
        assertEquals(original, primary.snapshot())
        assertEquals(original, legacy.snapshot())
        assertTrue(metadata.readWriteIntent() == null)
    }

    @Test fun legacyFailureAfterSqliteReportsCommittedAndSqliteWinsOnRestart() {
        val original = linkedMapOf<String, Any>(
            "keep" to "old",
            "remove" to true,
        )
        val candidate = mapOf<String, Any>("keep" to "old", "add" to "new")
        val primary = ImportingPersistence(original)
        val legacy = RecordingLegacyMirror(original)
        val metadata = RecordingBridgeMetadata().also {
            it.writeHash(stateSnapshotHash(original))
        }
        val persistence = DowngradeCompatibleStatePersistence(primary, legacy, metadata)
        assertEquals(original, persistence.initialize())
        legacy.failNextPersist = true

        assertTrue(
            persistence.persist(
                StateMutation(
                    clear = false,
                    changes = mapOf("add" to "new", "remove" to null),
                ),
            ),
        )
        assertEquals(candidate, primary.snapshot())
        assertEquals(original, legacy.snapshot())
        assertTrue(metadata.readWriteIntent() != null)

        val restarted = DowngradeCompatibleStatePersistence(primary, legacy, metadata).initialize()
        assertEquals(candidate, restarted)
        assertEquals(candidate, primary.snapshot())
        assertEquals(candidate, legacy.snapshot())
        assertEquals(stateSnapshotHash(candidate), metadata.readHash())
        assertTrue(metadata.readWriteIntent() == null)
    }

    @Test fun completionMarkerFailureDoesNotTurnDurableCompositeIntoFalseFailure() {
        val original = mapOf<String, Any>("mode" to "old")
        val candidate = mapOf<String, Any>("mode" to "new")
        val primary = ImportingPersistence(original)
        val legacy = RecordingLegacyMirror(original)
        val metadata = RecordingBridgeMetadata().also {
            it.writeHash(stateSnapshotHash(original))
        }
        val persistence = DowngradeCompatibleStatePersistence(primary, legacy, metadata)
        assertEquals(original, persistence.initialize())
        metadata.failNextHash = true

        assertTrue(
            persistence.persist(
                StateMutation(clear = false, changes = mapOf("mode" to "new")),
            ),
        )
        assertEquals(candidate, primary.snapshot())
        assertEquals(candidate, legacy.snapshot())
        assertTrue(metadata.readWriteIntent() != null)

        val restarted = DowngradeCompatibleStatePersistence(primary, legacy, metadata).initialize()
        assertEquals(candidate, restarted)
        assertEquals(candidate, legacy.snapshot())
        assertEquals(stateSnapshotHash(candidate), metadata.readHash())
        assertTrue(metadata.readWriteIntent() == null)
    }

    @Test fun nextLiveWriteFullyRepairsJournalAfterLegacyFailure() {
        val original = mapOf<String, Any>("base" to "kept")
        val primary = ImportingPersistence(original)
        val legacy = RecordingLegacyMirror(original)
        val metadata = RecordingBridgeMetadata().also {
            it.writeHash(stateSnapshotHash(original))
        }
        val persistence = DowngradeCompatibleStatePersistence(primary, legacy, metadata)
        assertEquals(original, persistence.initialize())
        legacy.failNextPersist = true

        assertTrue(
            persistence.persist(
                StateMutation(clear = false, changes = mapOf("first" to "durable")),
            ),
        )
        assertEquals(original, legacy.snapshot())
        assertTrue(metadata.readWriteIntent() != null)

        assertTrue(
            persistence.persist(
                StateMutation(clear = false, changes = mapOf("second" to "also-durable")),
            ),
        )
        val expected = mapOf<String, Any>(
            "base" to "kept",
            "first" to "durable",
            "second" to "also-durable",
        )
        assertEquals(expected, primary.snapshot())
        assertEquals(expected, legacy.snapshot())
        assertEquals(stateSnapshotHash(expected), metadata.readHash())
        assertTrue(metadata.readWriteIntent() == null)
    }

    @Test fun markerlessDivergenceKeepsSqliteActiveAndLegacyUntouched() {
        val legacy = RecordingLegacyMirror(
            mapOf("dashboard_url" to "http://stale.example"),
        )
        val primary = ImportingPersistence(
            mapOf("dashboard_url" to "https://vc236-current.example"),
        )
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )

            assertEquals(
                "https://vc236-current.example",
                preferences.getString("dashboard_url", null),
            )
            assertEquals(
                mapOf("dashboard_url" to "http://stale.example"),
                legacy.snapshot(),
            )
            assertTrue(metadata.readHash() == null)
            assertEquals(
                stateSnapshotHash(primary.snapshot()) to stateSnapshotHash(legacy.snapshot()),
                metadata.conflict,
            )
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun driftedMirrorNeverWipesLiveConfig() {
        val fullConfig: Map<String, Any> = linkedMapOf(
            "panel_id" to "alpha",
            "ha_url" to "https://ha.example",
            "dashboard_package" to "builtin",
        )
        val primary = ImportingPersistence(fullConfig)
        val legacy = RecordingLegacyMirror(linkedMapOf("panel_id" to "alpha"))
        val metadata = RecordingBridgeMetadata().also {
            it.writeHash(stateSnapshotHash(fullConfig))
        }

        val snapshot = DowngradeCompatibleStatePersistence(primary, legacy, metadata).initialize()

        assertEquals("https://ha.example", snapshot["ha_url"])
        assertEquals("builtin", snapshot["dashboard_package"])
        assertEquals("alpha", snapshot["panel_id"])
        assertEquals(fullConfig, primary.snapshot())
    }

    @Test fun matchingLegacyMarkerRecoversRicherLegacySnapshot() {
        val legacy: Map<String, Any> = linkedMapOf(
            "panel_id" to "old-panel-id",
            "ha_url" to "https://ha.example",
            "mqtt_broker" to "tcp://mqtt.example:1883",
        )
        val primary = ImportingPersistence(
            mapOf("panel_id" to "current-panel-id", "config_schema" to 3),
        )
        val mirror = RecordingLegacyMirror(legacy)
        val metadata = RecordingBridgeMetadata().also { it.writeHash(stateSnapshotHash(legacy)) }

        val snapshot = DowngradeCompatibleStatePersistence(primary, mirror, metadata).initialize()

        assertEquals("https://ha.example", snapshot["ha_url"])
        assertEquals("tcp://mqtt.example:1883", snapshot["mqtt_broker"])
        assertEquals("current-panel-id", snapshot["panel_id"])
        assertEquals(3, snapshot["config_schema"])
        assertEquals(snapshot, primary.snapshot())
        assertEquals(snapshot, mirror.snapshot())
    }

    @Test fun firstPublicUpgradeWithEqualStatesEstablishesNormalMarker() {
        val state = mapOf("dashboard_url" to "https://public-093.example")
        val legacy = RecordingLegacyMirror(state)
        val primary = ImportingPersistence(state)
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )

            assertEquals(
                "https://public-093.example",
                preferences.getString("dashboard_url", null),
            )
            assertEquals(stateSnapshotHash(legacy.snapshot()), metadata.readHash())
            assertTrue(metadata.conflict == null)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun firstMutationAfterMarkerlessConflictReconcilesFullSqliteCandidate() {
        val legacy = RecordingLegacyMirror(mapOf("url" to "stale"))
        val primary = ImportingPersistence(mapOf("url" to "current", "newKey" to "x"))
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )
            assertTrue(preferences.edit().putString("theme", "dark").commit())

            val expected = mapOf("url" to "current", "newKey" to "x", "theme" to "dark")
            assertEquals(expected, preferences.all)
            assertEquals(expected, primary.snapshot())
            assertEquals(expected, legacy.snapshot())
            assertEquals(stateSnapshotHash(expected), metadata.readHash())

            val reopened = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )
            assertEquals(expected, reopened.all)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun credentialsNeverReachLegacyJournalThroughPersistOrReplace() {
        val original = mapOf<String, Any>("ha_url" to "https://ha.example")
        val primary = ImportingPersistence(original)
        val legacy = RecordingLegacyMirror(original)
        val metadata = RecordingBridgeMetadata().also { it.writeHash(stateSnapshotHash(original)) }
        val persistence = DowngradeCompatibleStatePersistence(
            primary, legacy, metadata, legacyMirrorExclusions("config"),
        )
        assertEquals(original, persistence.initialize())

        assertTrue(
            persistence.persist(
                StateMutation(
                    clear = false,
                    changes = mapOf(
                        "ha_token" to "persisted-access",
                        "ha_refresh_token" to "persisted-refresh",
                        "ha_token_expiry" to 1234L,
                    ),
                ),
            ),
        )
        assertEquals("persisted-access", primary.snapshot()["ha_token"])
        assertEquals("persisted-refresh", primary.snapshot()["ha_refresh_token"])
        assertEquals(
            mapOf("ha_url" to "https://ha.example", "ha_token_expiry" to 1234L),
            legacy.snapshot(),
        )
        assertEquals(stateSnapshotHash(legacy.snapshot()), metadata.readHash())

        val replaced = mapOf<String, Any>(
            "ha_url" to "https://other.example",
            "ha_token" to "replaced-access",
            "ha_refresh_token" to "replaced-refresh",
        )
        assertTrue(persistence.replace(replaced))
        assertEquals(replaced, primary.snapshot())
        assertEquals(mapOf("ha_url" to "https://other.example"), legacy.snapshot())
        assertEquals(stateSnapshotHash(legacy.snapshot()), metadata.readHash())
    }

    @Test fun journalHoldingCredentialsIsScrubbedOnceAndNextStartIsQuiet() {
        val raw = mapOf<String, Any>(
            "panel_id" to "panel-a",
            "ha_url" to "https://ha.example",
            "ha_token" to "live-access",
            "ha_refresh_token" to "live-refresh",
        )
        val primary = ImportingPersistence(raw)
        val legacy = RecordingLegacyMirror(raw)
        // The marker an earlier build recorded hashed the journal with its credentials included.
        val metadata = RecordingBridgeMetadata().also { it.writeHash(stateSnapshotHash(raw)) }
        val excluded = legacyMirrorExclusions("config")

        val first = DowngradeCompatibleStatePersistence(primary, legacy, metadata, excluded).initialize()
        assertEquals(raw, first)
        assertEquals(raw, primary.snapshot())
        assertEquals(
            mapOf("panel_id" to "panel-a", "ha_url" to "https://ha.example"),
            legacy.snapshot(),
        )
        val settledMarker = metadata.readHash()
        assertEquals(stateSnapshotHash(legacy.snapshot()), settledMarker)

        primary.replaceCalls = 0
        primary.persistCalls = 0
        legacy.replaceCalls = 0
        legacy.persistCalls = 0
        val second = DowngradeCompatibleStatePersistence(primary, legacy, metadata, excluded).initialize()
        assertEquals(raw, second)
        assertEquals(0, primary.replaceCalls + primary.persistCalls)
        assertEquals(0, legacy.replaceCalls + legacy.persistCalls)
        assertEquals(settledMarker, metadata.readHash())
    }

    @Test fun downgradeEditOfOrdinaryKeyIsMergedWhileSqliteKeepsCredentials() {
        val primary = ImportingPersistence(emptyMap())
        val legacy = RecordingLegacyMirror(emptyMap())
        val metadata = RecordingBridgeMetadata()
        val excluded = legacyMirrorExclusions("config")
        val persistence = DowngradeCompatibleStatePersistence(primary, legacy, metadata, excluded)
        persistence.initialize()
        assertTrue(
            persistence.persist(
                StateMutation(
                    clear = false,
                    changes = mapOf(
                        "ha_url" to "https://ha.example",
                        "ha_token" to "live-access",
                        "ha_refresh_token" to "live-refresh",
                    ),
                ),
            ),
        )

        // An older build edits an ordinary key in the journal it reads.
        legacy.persist(StateMutation(clear = false, changes = mapOf("ha_url" to "https://edited.example")))

        val returned = DowngradeCompatibleStatePersistence(primary, legacy, metadata, excluded).initialize()
        val expected = mapOf(
            "ha_url" to "https://edited.example",
            "ha_token" to "live-access",
            "ha_refresh_token" to "live-refresh",
        )
        assertEquals(expected, returned)
        assertEquals(expected, primary.snapshot())
        assertEquals(mapOf("ha_url" to "https://edited.example"), legacy.snapshot())
    }

    @Test fun credentialsWrittenIntoJournalByOlderBuildNeverReplaceSqliteCredentials() {
        val primary = ImportingPersistence(emptyMap())
        val legacy = RecordingLegacyMirror(emptyMap())
        val metadata = RecordingBridgeMetadata()
        val excluded = legacyMirrorExclusions("config")
        val persistence = DowngradeCompatibleStatePersistence(primary, legacy, metadata, excluded)
        persistence.initialize()
        val live = mapOf(
            "ha_url" to "https://ha.example",
            "ha_token" to "live-access",
            "ha_refresh_token" to "live-refresh",
        )
        assertTrue(persistence.replace(live))

        legacy.persist(
            StateMutation(
                clear = false,
                changes = mapOf(
                    "ha_token" to "older-build-access",
                    "ha_refresh_token" to "older-build-refresh",
                ),
            ),
        )
        primary.replaceCalls = 0

        val returned = DowngradeCompatibleStatePersistence(primary, legacy, metadata, excluded).initialize()
        assertEquals(live, returned)
        assertEquals(live, primary.snapshot())
        assertEquals(0, primary.replaceCalls)
        assertEquals(mapOf("ha_url" to "https://ha.example"), legacy.snapshot())
    }

    @Test fun shutdownDrainWaitsForBlockedApplyToBecomeDurable() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            },
        )
        val writer = Executors.newSingleThreadExecutor()
        val shutdown = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            preferences.edit().putString("mode", "saved-before-replace").apply()
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))

            val drained = shutdown.submit<Boolean> { preferences.flush(5_000) }
            assertFalse(drained.isDone)

            releasePersistence.countDown()
            assertTrue(drained.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("persist:mode", "replace:mode"), persistence.events)
        } finally {
            releasePersistence.countDown()
            shutdown.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun quiesceDefersRacingApplyUntilFailedInstallReopensAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        val applyStarted = CountDownLatch(1)
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )
            val quiescence = quiesceStateWrites(admission) { true }
            assertTrue(quiescence != null)

            val applying = caller.submit<Unit> {
                applyStarted.countDown()
                preferences.edit().putString("racing_apply", "durable").apply()
            }
            assertTrue(applyStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(applying.isDone)
            assertFalse(preferences.contains("racing_apply"))
            assertTrue(changed.isEmpty())

            quiescence!!.close()
            applying.get(5, TimeUnit.SECONDS)
            assertTrue(preferences.flush(5_000))
            assertEquals("durable", preferences.getString("racing_apply", null))
            assertEquals(listOf("racing_apply"), changed)
            assertEquals(listOf("persist:racing_apply", "replace:racing_apply"), persistence.events)
        } finally {
            caller.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun quiesceFlushExcludesAdmittedWritesUntilTheLeaseCloses() {
        val admission = StateMutationAdmission()
        val flushEntered = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val frozenWriteEntered = CountDownLatch(1)
        val quiescer = Executors.newSingleThreadExecutor()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val quiescence = quiescer.submit<StateQuiescence?> {
                quiesceStateWrites(admission) {
                    flushEntered.countDown()
                    releaseFlush.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(flushEntered.await(5, TimeUnit.SECONDS))

            val racingWrite = writer.submit<Unit> {
                admission.admit {
                    frozenWriteEntered.countDown()
                }
            }
            assertFalse(frozenWriteEntered.await(100, TimeUnit.MILLISECONDS))

            releaseFlush.countDown()
            val activeQuiescence = quiescence.get(5, TimeUnit.SECONDS)
            assertTrue(activeQuiescence != null)
            assertFalse(frozenWriteEntered.await(100, TimeUnit.MILLISECONDS))

            activeQuiescence!!.close()
            racingWrite.get(5, TimeUnit.SECONDS)
            assertTrue(frozenWriteEntered.await(5, TimeUnit.SECONDS))
        } finally {
            releaseFlush.countDown()
            quiescer.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun concurrentAppliesWaitForQuiescenceThenPreserveCandidatePersistenceAndPublication() {
        val persistence = SnapshotPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(2)
        val admission = StateMutationAdmission()
        val start = CountDownLatch(1)
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)
            val quiescence = quiesceStateWrites(admission) { true }
            assertTrue(quiescence != null)

            val first = callers.submit<Unit> {
                start.await()
                preferences.edit().putString("first", "one").apply()
            }
            val second = callers.submit<Unit> {
                start.await()
                preferences.edit().putString("second", "two").apply()
            }
            start.countDown()
            Thread.sleep(100)
            assertFalse(first.isDone)
            assertFalse(second.isDone)
            assertTrue(preferences.all.isEmpty())

            quiescence!!.close()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertTrue(preferences.flush(5_000))

            assertEquals(
                mapOf("first" to "one", "second" to "two"),
                preferences.all,
            )
            assertEquals(
                mapOf("first" to "one", "second" to "two"),
                persistence.snapshot(),
            )
        } finally {
            callers.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun failedInstallCanCloseQuiescenceAndRestoreAsyncAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)
            assertTrue(preferences.edit().putString("before", "durable").commit())

            val quiescence = quiesceStateWrites(admission) { true }
            assertTrue(quiescence != null)
            quiescence!!.close()
            assertTrue(preferences.edit().putString("after", "accepted").commit())
            assertEquals("accepted", preferences.getString("after", null))
            assertEquals(listOf("persist:before", "persist:after"), persistence.events)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun failedQuiesceDrainReopensMutationAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)

            assertTrue(quiesceStateWrites(admission) { false } == null)
            assertTrue(preferences.edit().putString("retry", "accepted").commit())
            assertEquals("accepted", preferences.getString("retry", null))
            assertEquals(listOf("persist:retry"), persistence.events)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun throwingQuiesceDrainReopensMutationAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)

            assertTrue(quiesceStateWrites(admission) { error("unexpected flush failure") } == null)
            assertTrue(preferences.edit().putString("retry", "accepted").commit())
            assertEquals("accepted", preferences.getString("retry", null))
            assertEquals(listOf("persist:retry"), persistence.events)
        } finally {
            writer.shutdownNow()
        }
    }

    private class RecordingPersistence(
        private val persistBlock: (StateMutation) -> Unit = {},
        private val failFirstPersist: Boolean = false,
        private val failPersistCall: Int? = null,
    ) : StateNamespacePersistence {
        val events = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var replacedSnapshot: Map<String, Any>? = null
        private val persistCalls = AtomicInteger()

        override fun initialize(): Map<String, Any> = emptyMap()

        override fun persist(mutation: StateMutation): Boolean {
            persistBlock(mutation)
            events += "persist:${mutation.changes.keys.joinToString(",")}"
            val call = persistCalls.incrementAndGet()
            return !(failFirstPersist && call == 1) && call != failPersistCall
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            replacedSnapshot = snapshot
            events += "replace:${snapshot.keys.joinToString(",")}"
            return true
        }
    }

    private class SnapshotPersistence : StateNamespacePersistence {
        private val values = linkedMapOf<String, Any>()

        override fun initialize(): Map<String, Any> = emptyMap()

        @Synchronized
        override fun persist(mutation: StateMutation): Boolean {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        @Synchronized
        override fun replace(snapshot: Map<String, Any>): Boolean {
            values.clear()
            values.putAll(snapshot)
            return true
        }

        @Synchronized
        fun snapshot(): Map<String, Any> = values.toMap()
    }

    private class ImportingPersistence(
        imported: Map<String, Any>,
    ) : StateNamespacePersistence {
        private val values = imported.toMutableMap()
        var failNextPersist = false
        var failNextReplace = false
        var persistCalls = 0
        var replaceCalls = 0

        override fun initialize(): Map<String, Any> = values.toMap()

        override fun persist(mutation: StateMutation): Boolean {
            persistCalls++
            if (failNextPersist) {
                failNextPersist = false
                return false
            }
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            replaceCalls++
            if (failNextReplace) {
                failNextReplace = false
                return false
            }
            values.clear()
            values.putAll(snapshot)
            return true
        }

        fun snapshot(): Map<String, Any> = values.toMap()
    }

    private class RecordingLegacyMirror(
        initial: Map<String, Any>,
    ) : LegacyStateMirror {
        private val values = initial.toMutableMap()
        var failNextPersist = false
        var failNextReplace = false
        var persistCalls = 0
        var replaceCalls = 0

        override fun snapshot(): Map<String, Any> = values.toMap()

        override fun persist(mutation: StateMutation): Boolean {
            persistCalls++
            if (failNextPersist) {
                failNextPersist = false
                return false
            }
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            replaceCalls++
            if (failNextReplace) {
                failNextReplace = false
                return false
            }
            values.clear()
            values.putAll(snapshot)
            return true
        }
    }

    private class RecordingBridgeMetadata : BridgeMetadata {
        private var hash: String? = null
        private var writeIntent: String? = null
        var failNextIntent = false
        var failNextHash = false
        var conflict: Pair<String, String>? = null
            private set

        override fun readHash(): String? = hash

        override fun readWriteIntent(): String? = writeIntent

        override fun writeIntent(candidateHash: String): Boolean {
            if (failNextIntent) {
                failNextIntent = false
                return false
            }
            writeIntent = candidateHash
            return true
        }

        override fun writeHash(hash: String): Boolean {
            if (failNextHash) {
                failNextHash = false
                return false
            }
            this.hash = hash
            writeIntent = null
            conflict = null
            return true
        }

        override fun writeConflict(sqliteHash: String, legacyHash: String): Boolean {
            conflict = sqliteHash to legacyHash
            return true
        }
    }
}
