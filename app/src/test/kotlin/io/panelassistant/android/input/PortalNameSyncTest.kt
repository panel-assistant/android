package io.panelassistant.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalNameSyncTest {
    private val id = PortalNameSync.ID
    private fun node(resource: String = "", kind: String = "android.widget.FrameLayout", text: String? = null,
                     clickable: Boolean = false, children: List<PortalNameSync.Node> = emptyList()) =
        PortalNameSync.Node(if (resource.isEmpty()) "" else id + resource, kind, text, clickable = clickable, children = children)
    // The qualified structure observed in the native editor, with synthetic names.
    private fun editor(name: String, window: Int = 20): PortalNameSync.Window {
        val custom = node(kind = "android.widget.Button", clickable = true, children = listOf(
            node("title", "android.widget.TextView", name), node("endIcon", "android.widget.ImageView"),
        ))
        val preset = node(kind = "android.widget.RadioButton", clickable = true,
            children = listOf(node("title", "android.widget.TextView", "Kitchen")))
        return PortalNameSync.Window(window, PortalNameSync.SETTINGS, false, node("qa_device_setup_name", children = listOf(
            node("names_niu_list", children = listOf(node("niu_list", "androidx.recyclerview.widget.RecyclerView",
                children = listOf(custom, preset)))), node("next", "android.widget.Button", "Done", true),
        )))
    }
    private fun dialog(name: String) = PortalNameSync.Window(21, PortalNameSync.SETTINGS, false,
        node("qa_common_device_setup_name_alert_enter_portal_name", children = listOf(
            node("niu_dialog_input_layout", children = listOf(node("niu_dialog_input", "android.widget.EditText", name))),
            node("niu_dialog_button_positive", "android.widget.Button", "Save", true),
        )))
    private val dashboard = PortalNameSync.Window(10, "io.panelassistant.android", true, node())
    private inner class Session {
        var time = 0L
        var visible: PortalNameSync.Window? = dashboard
        var launches = 0
        var accepted = true
        val actions = mutableListOf<Pair<PortalNameSync.Action, String>>()
        val results = mutableListOf<Pair<Boolean, String>>()
        val pending = mutableMapOf<Runnable, Long>()
        val driver = PortalNameSync({ time }, { visible }, { launches++; accepted }, { _, action, name ->
            actions += action to name
            accepted
        }, { task, delay -> pending[task] = time + delay }, { pending.remove(it) }, { ok, reason -> results += ok to reason })
        fun show(window: PortalNameSync.Window?) { visible = window; driver.onEvent(); advance(300) }
        fun advance(ms: Long) {
            val until = time + ms
            while (pending.values.minOrNull()?.let { it <= until } == true) {
                val task = pending.minBy { it.value }.key
                time = pending.remove(task)!!
                task.run()
            }
            time = until
        }
        fun submit(name: String = "Chosen") {
            driver.request(name)
            show(editor("Original"))
            show(dialog("Original"))
            show(dialog(name))
            show(editor(name))
        }
        fun closedAndReopened(name: String = "Chosen") {
            show(dashboard)
            advance(PortalNameSync.SAVE_SETTLE_MS + PortalNameSync.POLL_MS)
            show(editor(name, 22))
        }
    }

    @Test fun saveAndDoneAreNotSuccessUntilEditorClosesAndPersistedNameIsReopened() {
        val s = Session().apply { submit() }
        assertTrue(s.results.isEmpty())
        assertEquals(1, s.launches)
        s.advance(5_000)
        assertTrue(s.results.isEmpty()) // Even a matching local row after Done cannot prove persistence.
        s.closedAndReopened()
        assertEquals(listOf(true), s.results.map { it.first })
        assertEquals(2, s.launches)
        assertEquals(PortalNameSync.Action.BACK to "Chosen", s.actions.last())
        assertFalse(s.driver.active)
    }

    @Test fun staleReopenedNameIsRetriedAfterAnotherCloseAndSettleWithoutResubmitting() {
        val s = Session().apply { submit(); closedAndReopened("Original") }
        assertTrue(s.results.isEmpty())
        s.show(dashboard)
        s.advance(PortalNameSync.SAVE_SETTLE_MS + PortalNameSync.POLL_MS)
        s.show(editor("Chosen", 23))
        assertEquals(listOf(true), s.results.map { it.first })
        assertEquals(1, s.actions.count { it.first == PortalNameSync.Action.DONE })
        assertEquals(3, s.launches)
    }

    @Test fun alreadyMatchingNativeNameNeedsNoVendorWriteAndReturnsFromOwnedEditor() {
        val s = Session().apply { driver.request("Chosen"); show(editor("Chosen")) }
        assertEquals(listOf(PortalNameSync.Action.BACK to "Chosen"), s.actions)
        assertEquals(listOf(true), s.results.map { it.first })
    }

    @Test fun normalAuthenticationWaitsWithoutTypingClickingOrBackingAndCanContinue() {
        val auth = PortalNameSync.Window(19, PortalNameSync.SETTINGS, false, node("auth_prompt"))
        val s = Session().apply { driver.request("Chosen"); show(auth); advance(20_000) }
        assertTrue(s.actions.isEmpty())
        assertTrue(s.results.isEmpty())
        s.show(editor("Original"))
        assertEquals(listOf(PortalNameSync.Action.CUSTOM to "Chosen"), s.actions)
    }

    @Test fun authenticationAndUnrelatedScreensExpireHonestlyWithoutNavigation() {
        val s = Session().apply {
            driver.request("Chosen")
            show(PortalNameSync.Window(19, "vendor.authentication", false, node("pin_input")))
            advance(PortalNameSync.TIMEOUT_MS)
        }
        assertTrue(s.actions.isEmpty())
        assertEquals(listOf(false), s.results.map { it.first })
        assertFalse(s.driver.active)
        assertTrue(s.pending.isEmpty())
    }

    @Test fun initialUnrelatedWindowDoesNotOpenSettings() {
        val s = Session().apply { visible = editor("Original"); driver.request("Chosen") }
        assertEquals(0, s.launches)
        assertEquals(listOf(false), s.results.map { it.first })
    }

    @Test fun qualifiedCustomRowNeverUsesAnArbitraryDescendantOrPresetRadioButton() {
        val proper = editor("Original")
        val decoy = node("names_niu_list", children = listOf(node("niu_list", "androidx.recyclerview.widget.RecyclerView",
            children = listOf(node(kind = "android.widget.LinearLayout", children = listOf(
                node(kind = "android.widget.Button", clickable = true, children = listOf(node("title", "android.widget.TextView", "Decoy"))),
            ))))))
        val malformed = proper.copy(root = node("qa_device_setup_name", children = listOf(decoy)))
        val s = Session().apply { driver.request("Chosen"); show(malformed); advance(1_000) }
        assertTrue(s.actions.isEmpty())
        s.show(proper)
        assertEquals(listOf(PortalNameSync.Action.CUSTOM to "Chosen"), s.actions)
    }

    @Test fun latestNameReplacesTextBeforeAnyCloudSubmission() {
        val s = Session().apply {
            driver.request("First"); show(editor("Original")); show(dialog("Original"))
            driver.request("Latest"); show(dialog("First")); show(dialog("Latest")); show(editor("Latest"))
            closedAndReopened("Latest")
        }
        assertEquals(listOf("Latest"), s.actions.filter { it.first == PortalNameSync.Action.SAVE }.map { it.second })
        assertEquals(listOf(true), s.results.map { it.first })
    }

    @Test fun latestNameDuringSubmittedSaveWaitsForCloseBeforeStartingAnotherEdit() {
        val s = Session().apply { submit("First"); driver.request("Latest"); advance(2_000) }
        assertEquals(1, s.actions.count { it.first == PortalNameSync.Action.DONE })
        s.closedAndReopened("First")
        s.advance(300)
        s.show(dialog("First")); s.show(dialog("Latest")); s.show(editor("Latest", 22))
        s.closedAndReopened("Latest")
        assertEquals(listOf(true), s.results.map { it.first })
        assertEquals(listOf("First", "Latest"), s.actions.filter { it.first == PortalNameSync.Action.DONE }.map { it.second })
    }

    @Test fun repeatedRequestBetweenDialogSaveAndDoneStillRequiresPersistedReadback() {
        val s = Session().apply {
            driver.request("Chosen"); show(editor("Original")); show(dialog("Original")); show(dialog("Chosen"))
            driver.request("Chosen"); show(editor("Chosen"))
        }
        assertTrue(s.results.isEmpty())
        assertEquals(PortalNameSync.Action.DONE to "Chosen", s.actions.last())
        s.closedAndReopened()
        assertEquals(listOf(true), s.results.map { it.first })
    }

    @Test fun launchRefusalIsReportedWithoutAnyEditorActions() {
        val s = Session().apply { accepted = false; driver.request("Chosen") }
        assertTrue(s.actions.isEmpty())
        assertEquals(listOf(false), s.results.map { it.first })
        assertFalse(s.driver.active)
    }

    @Test fun persistedMismatchExhaustsBoundedReadbacksWithoutClaimingSuccess() {
        val s = Session().apply { submit() }
        repeat(PortalNameSync.MAX_READBACKS) { s.closedAndReopened("Original") }
        assertEquals(listOf(false), s.results.map { it.first })
        assertEquals(1 + PortalNameSync.MAX_READBACKS, s.launches)
        assertEquals(1, s.actions.count { it.first == PortalNameSync.Action.DONE })
        assertTrue(s.pending.isEmpty())
    }

    @Test fun teardownCancelsTimersAndAnAlreadyDequeuedCallbackCannotAct() {
        val s = Session().apply { driver.request("Chosen") }
        val dequeued = s.pending.keys.single()
        s.driver.close()
        s.visible = editor("Original")
        dequeued.run()
        s.driver.onEvent()
        assertTrue(s.actions.isEmpty())
        assertTrue(s.pending.isEmpty())
        assertEquals(listOf(false), s.results.map { it.first })
    }

    @Test fun refusedActionCannotBeReportedAsVendorSuccess() {
        val s = Session().apply { driver.request("Chosen"); accepted = false; show(editor("Original")) }
        assertEquals(listOf(false), s.results.map { it.first })
        assertFalse(s.driver.active)
    }
}
