package io.github.maxlyth.hapaneld.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class InstallProgressTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledBeforeLaunchCannotStrandOrOverwriteProgress() = runTest {
        val first = InstallProgress.start("first")
        assertNotNull(first)
        assertTrue(InstallProgress.running)
        assertNull(InstallProgress.start("overlap"))

        val job = launch { error("must not run") }
        InstallProgress.finishOnFailure(first!!, job)
        job.cancel()
        testScheduler.runCurrent()
        assertFalse(InstallProgress.running)
        assertEquals("cancelled", InstallProgress.message)
        assertEquals(
            "operation-cancelled",
            JSONObject(InstallProgress.json()).getJSONObject("presentation").getString("code"),
        )

        val second = InstallProgress.start("second")!!
        InstallProgress.finish(first, "stale")
        assertTrue(InstallProgress.running)
        assertEquals("Working…", InstallProgress.message)
        InstallProgress.finish(second, "done")
        assertFalse(InstallProgress.running)
        assertEquals("done", InstallProgress.message)
    }

    @Test fun structuredResultIsFixedShapeBoundedAndClearedByNextOwner() {
        val ticket = InstallProgress.start("restore")!!
        InstallProgress.finish(
            ticket,
            "Restore partially completed",
            InstallProgress.OperationResult(
                status = InstallProgress.Outcome.PARTIAL,
                config = InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED, 7),
                companion = InstallProgress.ComponentResult(
                    InstallProgress.Outcome.FAILED,
                    0,
                    "x".repeat(400),
                ),
            ),
        )

        val status = JSONObject(InstallProgress.json())
        assertEquals("partial", status.getJSONObject("result").getString("status"))
        assertEquals(7, status.getJSONObject("result").getJSONObject("config").getInt("items"))
        assertEquals(
            256,
            status.getJSONObject("result").getJSONObject("companion").getString("detail").length,
        )

        val next = InstallProgress.start("next")!!
        assertFalse(JSONObject(InstallProgress.json()).has("result"))
        InstallProgress.finish(next, "done")
    }

    @Test fun presentationIsAdditiveNestedBoundedAndClearedWithTheLegacyState() {
        val working = InstallPresentation("operation-working", mapOf("owner" to "restore"))
        val ticket = InstallProgress.start("Restore", working)!!
        var status = JSONObject(InstallProgress.json())
        assertEquals("Restore", status.getString("component"))
        assertEquals("Working…", status.getString("message"))
        assertEquals("operation-working", status.getJSONObject("presentation").getString("code"))
        val workingSnapshot = InstallProgress.presentationSnapshot()
        assertTrue(workingSnapshot.generation > 0L)
        assertTrue(workingSnapshot.running)
        assertEquals("Restore", workingSnapshot.component)
        assertEquals("Working…", workingSnapshot.message)
        assertEquals(working, workingSnapshot.presentation)

        val terminal = InstallPresentation("restore-completed-with-state", mapOf("count" to "2"))
        val nested = InstallPresentation("companion-urls-repaired", mapOf("count" to "1"))
        InstallProgress.finish(
            ticket,
            "Restore completed (2 panel-state values restored)",
            InstallProgress.OperationResult(
                status = InstallProgress.Outcome.SUCCEEDED,
                companion = InstallProgress.ComponentResult(
                    InstallProgress.Outcome.SUCCEEDED,
                    items = 1,
                    detail = "repaired 1 server",
                    presentation = nested,
                ),
            ),
            terminal,
        )
        status = JSONObject(InstallProgress.json())
        assertEquals("Restore completed (2 panel-state values restored)", status.getString("message"))
        assertEquals("restore-completed-with-state", status.getJSONObject("presentation").getString("code"))
        val terminalSnapshot = InstallProgress.presentationSnapshot()
        assertEquals(workingSnapshot.generation, terminalSnapshot.generation)
        assertFalse(terminalSnapshot.running)
        assertEquals("Restore", terminalSnapshot.component)
        assertEquals("Restore completed (2 panel-state values restored)", terminalSnapshot.message)
        assertEquals(terminal, terminalSnapshot.presentation)
        assertEquals(
            "companion-urls-repaired",
            status.getJSONObject("result").getJSONObject("companion")
                .getJSONObject("presentation").getString("code"),
        )

        val next = InstallProgress.start("next")!!
        status = JSONObject(InstallProgress.json())
        assertFalse(status.has("presentation"))
        assertNull(InstallProgress.presentationSnapshot().presentation)
        assertFalse(status.has("result"))
        InstallProgress.finish(next, "done")
        assertFalse(JSONObject(InstallProgress.json()).has("presentation"))
    }

    @Test fun destructiveOperationAndConfigureMutationCannotOverlap() {
        val restore = InstallProgress.start("Restore")!!
        try {
            assertNull(InstallProgress.startConfigMutation())
        } finally {
            InstallProgress.finish(restore, "done")
        }

        val configure = InstallProgress.startConfigMutation()!!
        try {
            assertFalse(InstallProgress.running)
            assertNull(InstallProgress.start("Restore"))
            assertNull(InstallProgress.startConfigMutation())
        } finally {
            InstallProgress.finishConfigMutation(configure)
        }

        val next = InstallProgress.start("Restore")!!
        InstallProgress.finish(next, "done")
    }

    @Test fun staleConfigureReleaseCannotClearNewerOwner() {
        val first = InstallProgress.startConfigMutation()!!
        InstallProgress.finishConfigMutation(first)
        val second = InstallProgress.startConfigMutation()!!

        InstallProgress.finishConfigMutation(first)
        assertNull(InstallProgress.start("Restore"))

        InstallProgress.finishConfigMutation(second)
        val restore = InstallProgress.start("Restore")!!
        InstallProgress.finish(restore, "done")
    }

}
