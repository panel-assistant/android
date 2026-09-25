package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogSchemaTest {
    /** Version-agnostic: a bump should not need this test edited, only a new step added. */
    @Test fun schemaUpgradePlanIsSequentialAndCompleteFromTheSupportedFloor() {
        val floor = EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION
        val current = EntityCatalogSchema.CURRENT_VERSION
        val plan = EntityCatalogSchema.plan(floor, current)

        assertEquals("every version from the floor must have a step", (floor until current).toList(), plan.map { it.from })
        assertEquals(current, plan.last().to)
        assertTrue("steps must be contiguous", plan.zipWithNext().all { (a, b) -> a.to == b.from })
        assertTrue("every step must do something", plan.all { it.sql.isNotEmpty() || it.transform != null })
    }

    @Test fun sameVersionIsANoOpAndDowngradeIsRejected() {
        val current = EntityCatalogSchema.CURRENT_VERSION
        assertTrue(EntityCatalogSchema.plan(current, current).isEmpty())
        assertTrue("a downgrade has no plan", runCatching { EntityCatalogSchema.plan(current, current - 1) }.isFailure)
        assertTrue(
            "a target beyond the newest step has no plan",
            runCatching { EntityCatalogSchema.plan(EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION, current + 1) }.isFailure,
        )
    }

    /**
     * A step may break compatibility — the forward chain handles renames and drops on upgrade, which is
     * the common direction — but it may not do so *silently*, because the chain cannot run backwards.
     * So a non-additive step must say it is one.
     */
    @Test fun noMigrationStepBreaksCompatibilityWithoutDeclaringIt() {
        val plan = EntityCatalogSchema.plan(EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION, EntityCatalogSchema.CURRENT_VERSION)
        val undeclared = plan.filterNot { it.breaksCompatibility }.flatMap { step ->
            SchemaAdditivePolicy.violations(step.sql).map { "step ${step.from}->${step.to} $it" }
        }
        assertEquals("a non-additive step must set breaksCompatibility", emptyList<String>(), undeclared)
    }

    @Test fun theAuthoritativeBoundaryIsFiniteAtTheCandidateCurrentSchema() {
        val boundary = EntityCatalogSchema.DATABASE_COMPATIBILITY
        assertEquals(EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION, boundary.minimumSchema)
        assertEquals(EntityCatalogSchema.CURRENT_VERSION, boundary.maximumSchema)
        assertFalse(boundary.contains(EntityCatalogSchema.CURRENT_VERSION + 1))
    }

    /** A step that claims a break must actually contain one, or the declaration is noise. */
    @Test fun aDeclaredBreakIsAnActualBreak() {
        EntityCatalogSchema.plan(EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION, EntityCatalogSchema.CURRENT_VERSION)
            .filter { it.breaksCompatibility }
            .forEach { step ->
                assertTrue(
                    "step ${step.from}->${step.to} declares a break but is additive",
                    SchemaAdditivePolicy.violations(step.sql).isNotEmpty(),
                )
            }
    }

    @Test fun theAdditivePolicyRejectsEachUnsafeShape() {
        // Removals and retypes break an older build that still reads them.
        assertTrue(SchemaAdditivePolicy.violations("DROP TABLE dashboard_entity_traffic_minute").isNotEmpty())
        assertTrue(SchemaAdditivePolicy.violations("ALTER TABLE dashboard DROP COLUMN issues_json").isNotEmpty())
        assertTrue(SchemaAdditivePolicy.violations("ALTER TABLE dashboard RENAME TO board").isNotEmpty())
        assertTrue(SchemaAdditivePolicy.violations("ALTER TABLE dashboard RENAME COLUMN path TO route").isNotEmpty())
        // The silent one: an older build's inserts omit the column and cannot satisfy the constraint.
        assertTrue(
            SchemaAdditivePolicy.violations("ALTER TABLE dashboard ADD COLUMN owner TEXT NOT NULL").isNotEmpty(),
        )
        // Case and whitespace must not be an escape hatch.
        assertTrue(SchemaAdditivePolicy.violations("drop   table   entity").isNotEmpty())
    }

    @Test fun theAdditivePolicyAcceptsSafeChanges() {
        assertTrue(
            SchemaAdditivePolicy.violations("ALTER TABLE dashboard ADD COLUMN owner TEXT NOT NULL DEFAULT ''").isEmpty(),
        )
        assertTrue(SchemaAdditivePolicy.violations("ALTER TABLE dashboard ADD COLUMN owner TEXT").isEmpty())
        assertTrue(SchemaAdditivePolicy.violations("CREATE TABLE metric_meta(metadata_id INTEGER PRIMARY KEY)").isEmpty())
        assertTrue(SchemaAdditivePolicy.violations("CREATE INDEX ix_metric_meta_id ON metric_meta(metadata_id)").isEmpty())
    }

    /**
     * Structures older than public v0.9.5 must be rejected here rather than half-migrated. They are
     * handled before the database is opened, because a throw inside onUpgrade aborts the open and takes
     * configuration down with it.
     */
    @Test fun versionsBelowTheSupportedFloorAreRejected() {
        assertEquals(11, EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION)
        for (stale in 1 until EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION) {
            assertTrue(
                "schema $stale is below the floor and must not produce a plan",
                runCatching { EntityCatalogSchema.plan(stale, EntityCatalogSchema.CURRENT_VERSION) }.isFailure,
            )
        }
    }

    @Test fun footprintCountsOnlyTheDatabaseAndKnownSqliteSidecars() {
        val directory = File.createTempFile("catalog-footprint", "").let { probe ->
            probe.delete()
            probe.also { it.mkdirs() }
        }
        try {
            val database = File(directory, EntityCatalogStore.DATABASE_NAME).apply { writeBytes(ByteArray(11)) }
            File(database.path + "-wal").writeBytes(ByteArray(13))
            File(database.path + "-shm").writeBytes(ByteArray(17))
            File(database.path + "-journal").writeBytes(ByteArray(19))
            File(database.path + "-backup").writeBytes(ByteArray(23))

            assertEquals(60L, EntityCatalogStore.knownDatabaseFootprint(database))
        } finally {
            directory.deleteRecursively()
        }
    }
}
