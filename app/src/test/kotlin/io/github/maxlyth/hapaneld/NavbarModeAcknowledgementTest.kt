package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.NavbarModeApplyOutcome
import io.github.maxlyth.hapaneld.control.NavbarModeApplyStatus
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.transactNavbarMode
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavbarModeAcknowledgementTest {
    @Test fun `unacknowledged restored mode stays pending until a successful replay`() {
        val authority = LiveSettingAuthority(setOf("navbar_mode"))
        var attached = false
        var persisted = "Swipe reveal"
        val published = mutableListOf<String>()
        fun apply(value: String): LiveSettingApplication {
            applyAcknowledgedNavbarMode(
                payload = value,
                previousMode = persisted,
                actuate = {
                    CompletableFuture.completedFuture(NavbarModeApplyOutcome(
                        it,
                        if (attached) NavbarModeApplyStatus.APPLIED else NavbarModeApplyStatus.FAILED,
                    ))
                },
                rollback = { _, previous ->
                    CompletableFuture.completedFuture(NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED))
                },
                persist = { persisted = it; true },
                reconcile = { published += persisted },
            )
            return LiveSettingApplication.immediate(LiveSettingApplyResult.APPLIED)
        }

        assertEquals(
            LiveSettingRequestOutcome.FAILED_PENDING,
            authority.applyOrQueueOutcomeObserved("navbar_mode", persisted, persisted) { _, value, _ -> apply(value) },
        )
        assertEquals(mapOf("navbar_mode" to "Swipe reveal"), authority.pendingSnapshot())
        assertEquals("Swipe reveal", persisted)
        assertTrue(published.isEmpty())

        attached = true
        authority.replayKeysObserved(setOf("navbar_mode")) { _, value, _, _ -> apply(value) }
        assertTrue(authority.pendingSnapshot().isEmpty())
        assertEquals(listOf("Swipe reveal"), published)
    }

    @Test fun `Home Assistant select options match navbar actuation modes`() {
        assertEquals(NavbarController.MODES, SettingsRegistry.spec("navbar_mode")!!.options)
    }

    @Test fun `missing overlay permission performs no physical mutation`() {
        val events = mutableListOf<String>()

        val result = transactNavbarMode(
            previousMode = "Off",
            targetMode = "Always on",
            permissionReady = false,
            applyOverscanFor = { events += "overscan:$it"; true },
            applyOverlayFor = { events += "overlay:$it"; true },
        )

        assertFalse(result.applied)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun `always overlay failure clears crop and restores prior overlay`() {
        val events = mutableListOf<String>()

        val result = transactNavbarMode(
            previousMode = "Off",
            targetMode = "Always on",
            permissionReady = true,
            applyOverscanFor = { events += "overscan:$it"; true },
            applyOverlayFor = {
                events += "overlay:$it"
                it != "Always on"
            },
        )

        assertFalse(result.applied)
        assertEquals(true, result.rollbackSucceeded)
        assertEquals(
            listOf("overscan:Always on", "overlay:Always on", "overscan:Off", "overlay:Off"),
            events,
        )
    }

    @Test fun `always to off clear failure leaves prior overlay and restores prior platform mode`() {
        val events = mutableListOf<String>()
        var firstClear = true

        val result = transactNavbarMode(
            previousMode = "Always on",
            targetMode = "Off",
            permissionReady = true,
            applyOverscanFor = {
                events += "overscan:$it"
                if (it == "Off" && firstClear) {
                    firstClear = false
                    false
                } else true
            },
            applyOverlayFor = { events += "overlay:$it"; true },
        )

        assertFalse(result.applied)
        assertEquals(true, result.rollbackSucceeded)
        assertEquals(listOf("overscan:Off", "overscan:Always on", "overlay:Always on"), events)
    }

    @Test fun `successful transaction confirms platform before replacing overlay`() {
        val events = mutableListOf<String>()

        val result = transactNavbarMode(
            previousMode = "Off",
            targetMode = "Swipe reveal",
            permissionReady = true,
            applyOverscanFor = { events += "overscan:$it"; true },
            applyOverlayFor = { events += "overlay:$it"; true },
        )

        assertEquals(true, result.applied)
        assertEquals(listOf("overscan:Swipe reveal", "overlay:Swipe reveal"), events)
    }

    @Test fun `successful actuation persists normalized mode before reconciliation`() {
        val events = mutableListOf<String>()

        applyAcknowledgedNavbarMode(
            payload = "swipe reveal",
            previousMode = "Off",
            actuate = {
                events += "actuate:$it"
                CompletableFuture.completedFuture(
                    NavbarModeApplyOutcome("Swipe reveal", NavbarModeApplyStatus.APPLIED),
                )
            },
            rollback = { _, previous ->
                events += "rollback:$previous"
                CompletableFuture.completedFuture(
                    NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED),
                )
            },
            persist = { events += "persist:$it"; true },
            reconcile = { events += "reconcile" },
        )

        assertEquals(
            listOf("actuate:swipe reveal", "persist:Swipe reveal", "reconcile"),
            events,
        )
    }

    @Test fun `failed actuation neither persists nor reconciles`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            applyAcknowledgedNavbarMode(
                payload = "Always on",
                previousMode = "Off",
                actuate = {
                    events += "actuate"
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome("Always on", NavbarModeApplyStatus.FAILED),
                    )
                },
                rollback = { _, previous ->
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED),
                    )
                },
                persist = { events += "persist"; true },
                reconcile = { events += "reconcile" },
            )
        }

        assertEquals(listOf("actuate"), events)
    }

    @Test fun `superseded or closed actuation remains unapplied`() {
        listOf(
            NavbarModeApplyStatus.SUPERSEDED,
            NavbarModeApplyStatus.CLOSED,
        ).forEach { status ->
            var persisted = false
            assertThrows(IllegalStateException::class.java) {
                applyAcknowledgedNavbarMode(
                    payload = "Off",
                    previousMode = "Always on",
                    actuate = {
                        CompletableFuture.completedFuture(NavbarModeApplyOutcome("Off", status))
                    },
                    rollback = { _, previous ->
                        CompletableFuture.completedFuture(
                            NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED),
                        )
                    },
                    persist = { persisted = true; true },
                    reconcile = {},
                )
            }
            assertEquals(false, persisted)
        }
    }

    @Test fun `persistence stays pending until asynchronous actuation acknowledges`() {
        val actuation = CompletableFuture<NavbarModeApplyOutcome>()
        val admitted = CompletableFuture<Unit>()
        val events = mutableListOf<String>()
        val command = CompletableFuture.runAsync {
            applyAcknowledgedNavbarMode(
                payload = "Always on",
                previousMode = "Off",
                actuate = {
                    admitted.complete(Unit)
                    actuation
                },
                rollback = { _, previous ->
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED),
                    )
                },
                persist = { events += "persist:$it"; true },
                reconcile = { events += "reconcile" },
            )
        }

        admitted.join()
        assertFalse(command.isDone)
        assertEquals(emptyList<String>(), events)

        actuation.complete(NavbarModeApplyOutcome("Always on", NavbarModeApplyStatus.APPLIED))
        command.join()
        assertEquals(listOf("persist:Always on", "reconcile"), events)
    }

    @Test fun `persistence failure prevents successful reconciliation`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            applyAcknowledgedNavbarMode(
                payload = "Off",
                previousMode = "Always on",
                actuate = {
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome("Off", NavbarModeApplyStatus.APPLIED),
                    )
                },
                rollback = { expected, previous ->
                    events += "rollback:$expected->$previous"
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED),
                    )
                },
                persist = { events += "persist"; false },
                reconcile = { events += "reconcile" },
            )
        }

        assertEquals(listOf("persist", "rollback:Off->Always on"), events)
    }

    @Test fun `actuation acknowledgement timeout conditionally queues prior mode and returns`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            applyAcknowledgedNavbarMode(
                payload = "Always on",
                previousMode = "Off",
                actuate = { CompletableFuture() },
                rollback = { expected, previous ->
                    events += "rollback:$expected->$previous"
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.APPLIED),
                    )
                },
                persist = { events += "persist"; true },
                reconcile = { events += "reconcile" },
                timeoutMs = 20L,
            )
        }

        assertEquals(listOf("rollback:Always on->Off"), events)
    }

    @Test fun `newer desired mode makes persistence compensation a harmless supersession`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            applyAcknowledgedNavbarMode(
                payload = "Always on",
                previousMode = "Off",
                actuate = {
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome("Always on", NavbarModeApplyStatus.APPLIED),
                    )
                },
                rollback = { _, previous ->
                    events += "newer-owned"
                    CompletableFuture.completedFuture(
                        NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.SUPERSEDED),
                    )
                },
                persist = { false },
                reconcile = { events += "reconcile" },
            )
        }

        assertEquals(listOf("newer-owned"), events)
    }
}
