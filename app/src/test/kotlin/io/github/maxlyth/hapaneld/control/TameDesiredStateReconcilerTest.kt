package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TameDesiredStateReconcilerTest {
    @Test fun `already stopping skips observation and all package work`() {
        val reconciler = TameDesiredStateReconciler(
            readOwned = { throw AssertionError("stopping owner must not read ownership") },
            observePackages = { throw AssertionError("stopping owner must not observe packages") },
            reassert = { throw AssertionError("stopping owner must not tame") },
            restore = { throw AssertionError("stopping owner must not restore") },
            clearAbsent = { throw AssertionError("stopping owner must not clear markers") },
        )
        assertEquals(TameReconcileResult(0, false), reconciler.reconcile(setOf("vendor.one")) { true })
    }

    private class Fixture(initial: Map<String, String?> = emptyMap()) {
        val markers = initial.toMutableMap()
        val presence = mutableMapOf<String, TamePackagePresence>()
        val safety = mutableMapOf<String, TamePackageSafety>()
        val reasserted = mutableListOf<String>()
        val restored = mutableListOf<TameOwnedMarker>()
        val cleared = mutableListOf<TameOwnedMarker>()
        var snapshot: TameOwnedMarkers? = null
        var reassertSucceeds = true
        var restoreSucceeds = true
        var clearSucceeds = true

        val reconciler = TameDesiredStateReconciler(
            readOwned = {
                snapshot ?: TameOwnedMarkers.Ready(
                    markers.mapValues { (pkg, mode) -> TameOwnedMarker(pkg, mode) },
                )
            },
            observePackages = { packages ->
                packages.associateWith {
                    TamePackageObservation(
                        presence[it] ?: TamePackagePresence.UNKNOWN,
                        safety[it] ?: TamePackageSafety.SAFE,
                    )
                }
            },
            reassert = { pkg ->
                reasserted += pkg
                reassertSucceeds.also { if (it) markers.putIfAbsent(pkg, "allow") }
            },
            restore = { marker ->
                restored += marker
                restoreSucceeds.also { if (it) markers.remove(marker.pkg) }
            },
            clearAbsent = { marker ->
                cleared += marker
                clearSucceeds.also { if (it) markers.remove(marker.pkg) }
            },
        )
    }

    @Test fun `legacy overlay marker is the sole rollback ownership record`() {
        val f = Fixture(mapOf("vendor.old" to "foreground")).apply {
            presence["vendor.old"] = TamePackagePresence.PRESENT
            safety["vendor.old"] = TamePackageSafety.PROTECTED
        }

        val result = f.reconciler.reconcile(emptySet())

        assertFalse(result.retryableFailure)
        assertEquals(listOf(TameOwnedMarker("vendor.old", "foreground")), f.restored)
        assertTrue(f.markers.isEmpty())
    }

    @Test fun `rollback restores removed ownership and reasserts every current desired package`() {
        val f = Fixture(mapOf("vendor.keep" to "allow", "vendor.remove" to "deny")).apply {
            listOf("vendor.keep", "vendor.remove", "vendor.add").forEach {
                presence[it] = TamePackagePresence.PRESENT
            }
        }

        assertFalse(f.reconciler.reconcile(setOf("vendor.keep", "vendor.add")).retryableFailure)
        assertEquals(listOf("vendor.remove"), f.restored.map(TameOwnedMarker::pkg))
        assertEquals(listOf("vendor.add", "vendor.keep"), f.reasserted)

        f.reasserted.clear()
        f.restored.clear()
        assertFalse(f.reconciler.reconcile(setOf("vendor.keep", "vendor.remove")).retryableFailure)
        assertEquals(listOf("vendor.add"), f.restored.map(TameOwnedMarker::pkg))
        assertEquals(listOf("vendor.keep", "vendor.remove"), f.reasserted)
    }

    @Test fun `owned desired package is reasserted on every wake after reinstall or firmware reset`() {
        val f = Fixture(mapOf("vendor.one" to "ignore")).apply {
            presence["vendor.one"] = TamePackagePresence.PRESENT
        }

        f.reconciler.reconcile(setOf("vendor.one"))
        f.reconciler.reconcile(setOf("vendor.one"))

        assertEquals(listOf("vendor.one", "vendor.one"), f.reasserted)
        assertTrue(f.restored.isEmpty())
    }

    @Test fun `positive absence clears ownership but unknown observation retains and retries`() {
        val f = Fixture(mapOf("vendor.absent" to "allow", "vendor.unknown" to "deny")).apply {
            presence["vendor.absent"] = TamePackagePresence.ABSENT
            presence["vendor.unknown"] = TamePackagePresence.UNKNOWN
        }

        val result = f.reconciler.reconcile(emptySet())

        assertTrue(result.retryableFailure)
        assertEquals(listOf("vendor.absent"), f.cleared.map(TameOwnedMarker::pkg))
        assertEquals(mapOf("vendor.unknown" to "deny"), f.markers)
        assertTrue(f.restored.isEmpty())
    }

    @Test fun `desired absent package is passive and clears a stale marker only when owned`() {
        val unowned = Fixture().apply { presence["vendor.absent"] = TamePackagePresence.ABSENT }
        val owned = Fixture(mapOf("vendor.absent" to "allow")).apply {
            presence["vendor.absent"] = TamePackagePresence.ABSENT
        }

        assertFalse(unowned.reconciler.reconcile(setOf("vendor.absent")).retryableFailure)
        assertEquals(0, unowned.reconciler.reconcile(setOf("vendor.absent")).attempted)
        assertFalse(owned.reconciler.reconcile(setOf("vendor.absent")).retryableFailure)
        assertEquals(listOf("vendor.absent"), owned.cleared.map(TameOwnedMarker::pkg))
    }

    @Test fun `unknown safety is retryable while deliberate dynamic protection is passive`() {
        val unknown = Fixture().apply {
            presence["vendor.one"] = TamePackagePresence.PRESENT
            safety["vendor.one"] = TamePackageSafety.UNKNOWN
        }
        val protected = Fixture().apply {
            presence["vendor.one"] = TamePackagePresence.PRESENT
            safety["vendor.one"] = TamePackageSafety.PROTECTED
        }

        assertTrue(unknown.reconciler.reconcile(setOf("vendor.one")).retryableFailure)
        assertFalse(protected.reconciler.reconcile(setOf("vendor.one")).retryableFailure)
        assertTrue(unknown.reasserted.isEmpty())
        assertTrue(protected.reasserted.isEmpty())
    }

    @Test fun `marker enumeration overflow and failure are hard fail closed`() {
        listOf<TameOwnedMarkers>(
            TameOwnedMarkers.Overflow(TameStatePolicy.MAX_MARKERS + 1),
            TameOwnedMarkers.Invalid(1),
            TameOwnedMarkers.Unavailable,
        ).forEach { badSnapshot ->
            val f = Fixture().apply { snapshot = badSnapshot }
            val result = f.reconciler.reconcile(setOf("vendor.one"))
            assertTrue(result.retryableFailure)
            assertEquals(0, result.attempted)
            assertTrue(f.reasserted.isEmpty())
            assertTrue(f.restored.isEmpty())
            assertTrue(f.cleared.isEmpty())
        }
    }

    @Test fun `marker parser accepts only a complete bounded valid legacy snapshot`() {
        val old = TameStatePolicy.parseOwnedMarkers(
            mapOf(
                "tame_overlay.vendor.one" to "allow",
                "unrelated" to "allow",
            ),
        ) as TameOwnedMarkers.Ready
        assertEquals("allow", old.byPackage["vendor.one"]?.overlayMode)

        listOf(
            mapOf("tame_overlay.not a package" to "deny"),
            mapOf("tame_overlay.${"com." + "a".repeat(252)}" to "deny"),
            mapOf("tame_overlay.vendor.two" to 7),
            mapOf("tame_overlay.vendor.three" to "unsupported"),
        ).forEach { malformed ->
            assertTrue(TameStatePolicy.parseOwnedMarkers(malformed) is TameOwnedMarkers.Invalid)
        }

        val overflow = TameStatePolicy.parseOwnedMarkers(
            buildMap {
                repeat(TameStatePolicy.MAX_MARKERS) {
                    put("tame_overlay.com.vendor.pkg$it", "default")
                }
                // Invalid records count as prefixed records too; overflow wins without a truncated view.
                put("tame_overlay.not a package", "default")
            },
        )
        assertTrue(overflow is TameOwnedMarkers.Overflow)
        assertEquals(
            TameStatePolicy.MAX_MARKERS + 1,
            (overflow as TameOwnedMarkers.Overflow).count,
        )
    }
}

class TameOwnershipPolicyTest {
    @Test fun `filtered package inventory cannot prove absence`() {
        assertFalse(TameStatePolicy.packageInventoryCredible(emptySet(), "io.github.hapaneld"))
        assertFalse(
            TameStatePolicy.packageInventoryCredible(
                setOf("com.vendor.one"),
                "io.github.hapaneld",
            ),
        )
        assertTrue(
            TameStatePolicy.packageInventoryCredible(
                setOf("com.vendor.one", "io.github.hapaneld"),
                "io.github.hapaneld",
            ),
        )
    }

    @Test fun `privileged route admits one helper command and preserves su fallback`() {
        var helperCalls = 0
        var suCalls = 0
        assertTrue(
            TameStatePolicy.privileged(
                daemonCmd = "DISABLE com.vendor.one",
                suCmd = "pm disable-user --user 0 com.vendor.one",
                send = { helperCalls++; "OK" },
                runSu = { suCalls++; true },
            ),
        )
        assertEquals(1, helperCalls)
        assertEquals(0, suCalls)

        helperCalls = 0
        assertTrue(
            TameStatePolicy.privileged(
                daemonCmd = "ENABLE com.vendor.one",
                suCmd = "pm enable com.vendor.one",
                send = { helperCalls++; null },
                runSu = { suCalls++; true },
            ),
        )
        assertEquals(1, helperCalls)
        assertEquals(1, suCalls)
    }

    @Test fun `write ahead marker commit precedes every external action`() {
        val events = mutableListOf<String>()
        val result = TameStatePolicy.reassertOwnership(
            markerExists = false,
            markerMode = null,
            captureMode = { events += "capture"; "foreground" },
            persistMarker = { events += "persist:$it"; true },
            mutate = { events += "mutate"; true },
        )

        assertTrue(result)
        assertEquals(listOf("capture", "persist:foreground", "mutate"), events)
    }

    @Test fun `marker commit failure or capture failure prevents mutation`() {
        var mutations = 0
        val commitFailed = TameStatePolicy.reassertOwnership(
            markerExists = false,
            markerMode = null,
            captureMode = { "allow" },
            persistMarker = { false },
            mutate = { mutations++; true },
        )
        val captureFailed = TameStatePolicy.reassertOwnership(
            markerExists = false,
            markerMode = null,
            captureMode = { null },
            persistMarker = { true },
            mutate = { mutations++; true },
        )

        assertFalse(commitFailed)
        assertFalse(captureFailed)
        assertEquals(0, mutations)
    }

    @Test fun `crash after marker commit retains ownership and retry does not overwrite original mode`() {
        var marker: String? = null
        val crashed = runCatching {
            TameStatePolicy.reassertOwnership(
                markerExists = false,
                markerMode = null,
                captureMode = { "allow" },
                persistMarker = { marker = it; true },
                mutate = { error("simulated process death") },
            )
        }
        assertTrue(crashed.isFailure)
        assertEquals("allow", marker)

        var recaptured = false
        assertTrue(
            TameStatePolicy.reassertOwnership(
                markerExists = true,
                markerMode = marker,
                captureMode = { recaptured = true; "deny" },
                persistMarker = { false },
                mutate = { true },
            ),
        )
        assertFalse(recaptured)
        assertEquals("allow", marker)
    }

    @Test fun `exact overlay mode and marker survive every incomplete restore stage`() {
        val modes = mutableListOf<String>()
        var removeCalls = 0
        var enable = false
        var overlay = true
        var remove = true
        fun restore() = TameStatePolicy.restoreOwnership(
            markerMode = "foreground",
            enable = { enable },
            restoreOverlay = { modes += it; overlay },
            removeMarker = { removeCalls++; remove },
        )

        assertFalse(restore())
        assertEquals(0, removeCalls)
        enable = true
        overlay = false
        assertFalse(restore())
        assertEquals(0, removeCalls)
        overlay = true
        remove = false
        assertFalse(restore())
        assertEquals(1, removeCalls)
        remove = true
        assertTrue(restore())
        assertEquals(2, removeCalls)
        assertEquals(listOf("foreground", "foreground", "foreground", "foreground"), modes)
    }

    @Test fun `invalid legacy mode retains marker without enable or overlay side effects`() {
        var actions = 0
        val result = TameStatePolicy.restoreOwnership(
            markerMode = "unknown",
            enable = { actions++; true },
            restoreOverlay = { actions++; true },
            removeMarker = { actions++; true },
        )

        assertFalse(result)
        assertEquals(0, actions)
    }
}
