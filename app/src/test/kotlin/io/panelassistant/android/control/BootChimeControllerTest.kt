package io.panelassistant.android.control

import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.platform.RootRunOutcome
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootChimeControllerTest {
    private val prior = BootChimeState(
        ringSpeakerSetting = 2,
        ringSetting = null,
        notificationSetting = 7,
        ringStream = 3,
        notificationStream = 6,
    )

    @Test fun enablingCapturesDurablyBeforeSilencingAndDisableRestoresExactly() {
        var configured = false
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val hardware = FakeBootHardware(prior, events)
        val controller = BootChimeController(
            configured = { configured },
            setConfigured = { configured = it; events += "configured:$it" },
            stateStore = store,
            hardware = hardware,
        )

        assertTrue(controller.set(true))
        assertTrue(controller.set(false))

        assertEquals(
            listOf("capture", "save", "configured:true", "silence", "restore:$prior", "clear", "configured:false"),
            events,
        )
        assertFalse(configured)
        assertEquals(prior, hardware.restored)
        assertNull(store.state)
    }

    @Test fun failedSnapshotPersistenceRefusesToChangeConfigurationOrAudio() {
        var configured = false
        val events = mutableListOf<String>()
        val store = FakeBootStore(events, saveSucceeds = false)
        val hardware = FakeBootHardware(prior, events)
        val controller = BootChimeController({ configured }, { configured = it }, store, hardware)

        assertFalse(controller.set(true))

        assertEquals(listOf("capture", "save"), events)
        assertFalse(configured)
        assertFalse(hardware.silenced)
    }

    @Test fun persistedSilenceCompletesOnTheLifecycleOwnerBeforeReturning() {
        var configured = true
        val events = mutableListOf<String>()
        val controller = BootChimeController(
            { configured },
            { configured = it },
            FakeBootStore(events),
            FakeBootHardware(prior, events),
        )

        controller.applyPersisted()

        assertEquals(listOf("capture", "save", "silence"), events)
    }

    @Test fun legacyDisableNeverInventsALouderRestore() {
        var configured = true
        val events = mutableListOf<String>()
        val hardware = FakeBootHardware(prior, events)
        val controller = BootChimeController(
            { configured },
            { configured = it; events += "configured:$it" },
            FakeBootStore(events),
            hardware,
        )

        controller.set(false)

        assertEquals(listOf("configured:false"), events)
        assertFalse(configured)
        assertNull(hardware.restored)
    }

    @Test fun rootRestorePreservesUnsetAndIndependentStreamValues() = withShellHardware { hardware, log, _ ->
        assertEquals(ControlApplyOutcome.APPLIED, hardware.restore(prior))
        assertEquals(
            listOf(
                "put system volume_ring_speaker 2",
                "delete system volume_ring",
                "put system volume_notification 7",
                "media_session volume --stream 2 --set 3",
                "media_session volume --stream 5 --set 6",
            ),
            log.readLines(),
        )
    }

    @Test fun rootSilenceReportsAnEarlierPrivilegedWriteFailure() = withShellHardware { hardware, log, fail ->
        fail.writeText("put system volume_ring 0")
        assertEquals(ControlApplyOutcome.FAILED, hardware.silence())
        assertEquals(
            listOf("put system volume_ring_speaker 0", "put system volume_ring 0"),
            log.readLines(),
        )
    }

    /** Execute the hardware owner's command with Android's two CLI boundaries replaced by fixtures. */
    private fun withShellHardware(test: (AndroidBootChimeHardware, File, File) -> Unit) {
        val directory = scratchDirectory("boot-chime-shell").toFile()
        try {
            val log = File(directory, "calls")
            val fail = File(directory, "fail").apply { writeText("") }
            for (command in listOf("settings", "cmd")) File(directory, command).apply {
                writeText("""#!/bin/sh
                    |printf '%s\n' "${'$'}*" >> "${'$'}CALLS"
                    |[ "${'$'}*" != "${'$'}(cat "${'$'}FAIL")" ]
                    |""".trimMargin())
                assertTrue(setExecutable(true))
            }
            val root = object : RootShell by FakeRootShell() {
                override fun runClassified(cmd: String): RootRunOutcome {
                    val process = ProcessBuilder("sh", "-c", cmd).apply {
                        environment()["PATH"] = directory.path + ":" + System.getenv("PATH")
                        environment()["CALLS"] = log.path
                        environment()["FAIL"] = fail.path
                    }.redirectErrorStream(true).start()
                    return try {
                        assertTrue("boot-chime command finished", process.waitFor(5, TimeUnit.SECONDS))
                        if (process.exitValue() == 0) RootRunOutcome.RAN_OK else RootRunOutcome.RAN_FAILED
                    } finally {
                        process.destroyForcibly()
                    }
                }
            }
            test(AndroidBootChimeHardware(FakeBootChimeDirect(prior, applySucceeds = false), root, FakeDaemon()), log, fail)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun directTransitionAttemptsEveryDurableSettingAndLiveStream() {
        val attempted = mutableListOf<String>()

        assertEquals(ControlApplyOutcome.FAILED, applyBootChimeDirect(
            state = prior,
            writeSetting = { key, value ->
                attempted += "setting:$key=${value ?: "-"}"
                false
            },
            writeStream = { stream, value ->
                attempted += "stream:$stream=$value"
                if (stream == 2) error("ring stream denied")
                true
            },
        ))

        assertEquals(
            listOf(
                "setting:volume_ring_speaker=2",
                "setting:volume_ring=-",
                "setting:volume_notification=7",
                "stream:2=3",
                "stream:5=6",
            ),
            attempted,
        )
    }

    @Test fun helperSilenceSucceedsOnSandboxedPanelWithoutSu() {
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "OK"))
        val root = FakeRootShell(available = false, runResult = false)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertEquals(ControlApplyOutcome.APPLIED, hardware.silence())

        // The app route logs a refused private-setting and Do Not Disturb write on every attempt, so a
        // working helper must spare it.
        assertTrue(direct.applied.isEmpty())
        assertEquals(listOf("BOOTCHIME SILENCE"), daemon.sent)
        assertEquals(listOf(30_000L), daemon.longTimeouts)
        assertTrue(root.ran.isEmpty())
    }

    @Test fun helperFailureFallsThroughToExistingSuCommand() {
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "PARTIAL"))
        val root = FakeRootShell(runResult = true)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertEquals(ControlApplyOutcome.APPLIED, hardware.silence())

        assertEquals(listOf("BOOTCHIME SILENCE"), daemon.sent)
        assertEquals(listOf(silenceShellCommand(0)), root.ran)
    }

    @Test fun helperRestoreEncodesExactSnapshotIncludingUnsetValues() {
        val command = "BOOTCHIME RESTORE 2 - 7 3 6"
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf(command to "OK"))
        val root = FakeRootShell(runResult = false)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertEquals(ControlApplyOutcome.APPLIED, hardware.restore(prior))

        assertEquals(command, restoreHelperCommand(prior))
        assertEquals(listOf(command), daemon.sent)
        assertEquals(listOf(30_000L), daemon.longTimeouts)
        assertTrue(root.ran.isEmpty())
        assertTrue(direct.applied.isEmpty())
    }

    @Test fun appPathIsTheLastResortAfterHelperAndSu() {
        val direct = FakeBootChimeDirect(prior, applySucceeds = true)
        val daemon = FakeDaemon()
        val root = FakeRootShell(available = false, runResult = false)
        val hardware = AndroidBootChimeHardware(direct, root, daemon)

        assertEquals(ControlApplyOutcome.APPLIED, hardware.silence())

        assertEquals(listOf("BOOTCHIME SILENCE"), daemon.sent)
        assertEquals(listOf(silenceShellCommand(0)), root.ran)
        assertEquals(listOf(BootChimeState(0, 0, 0, 0, 0)), direct.applied)
    }

    @Test fun allRouteFailureRemainsFalseAndPendingAtControllerLevel() {
        var configured = false
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val direct = FakeBootChimeDirect(prior, applySucceeds = false)
        val daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "ERR"))
        val root = FakeRootShell(available = false, runResult = false)
        val controller = BootChimeController(
            configured = { configured },
            setConfigured = { configured = it },
            stateStore = store,
            hardware = AndroidBootChimeHardware(direct, root, daemon),
        )

        assertFalse(controller.set(true))

        assertTrue(configured)
        assertEquals(prior, store.state)
        assertEquals(listOf("BOOTCHIME SILENCE"), daemon.sent)
        assertEquals(listOf(silenceShellCommand(0)), root.ran)
    }

    @Test fun failedExactRestoreRetainsConfigurationAndSnapshotForRetry() {
        var configured = true
        val events = mutableListOf<String>()
        val store = FakeBootStore(events).also { it.state = prior }
        val hardware = FakeBootHardware(prior, events, restoreSucceeds = false)
        val controller = BootChimeController(
            { configured },
            { configured = it; events += "configured:$it" },
            store,
            hardware,
        )

        assertFalse(controller.set(false))

        assertEquals(listOf("restore:$prior"), events)
        assertTrue(configured)
        assertEquals(prior, store.state)
    }

    @Test fun `an unappliable OFF keeps the divergence visible instead of resolving it`() {
        // Retiring a pending OFF was rejected because it strands divergent hardware, config and published
        // state with nothing left to say so. The controller reports the absence and changes nothing:
        // the snapshot is retained for retry, config stays enabled, and isEnabled() — which is what the
        // published switch state reads — still reads ON while the user's saved desired value is OFF.
        var configured = true
        val events = mutableListOf<String>()
        val store = FakeBootStore(events).apply { state = prior }
        val hardware = FakeBootHardware(prior, events, restoreOutcome = ControlApplyOutcome.UNAVAILABLE)
        val controller = BootChimeController({ configured }, { configured = it }, store, hardware)

        assertEquals(ControlApplyOutcome.UNAVAILABLE, controller.apply(false))

        assertTrue("config must not record a transition that did not happen", configured)
        assertTrue("the published state keeps reporting the panel's actual state", controller.isEnabled())
        assertEquals("the snapshot is retained so a repaired panel can still restore", prior, store.state)
    }

    @Test fun `an unappliable ON reports the absence to a caller that can use it`() {
        var configured = false
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val hardware = FakeBootHardware(prior, events, silenceOutcome = ControlApplyOutcome.UNAVAILABLE)
        val controller = BootChimeController({ configured }, { configured = it }, store, hardware)

        assertEquals(ControlApplyOutcome.UNAVAILABLE, controller.apply(true))
        assertFalse("set() still reports a plain failure to its existing callers", controller.set(true))
    }

    // --- startup writes only until the first silence has succeeded (Android keeps these volumes) ---

    private fun startupController(
        configured: Boolean,
        store: FakeBootStore,
        hardware: FakeBootHardware,
    ): Pair<BootChimeController, () -> Boolean> {
        var on = configured
        return BootChimeController({ on }, { on = it }, store, hardware) to { on }
    }

    /** After a recorded silence the owner raised the ring volume in Android; a restart must leave it. */
    @Test fun `a start with the setting on and a recorded silence writes nothing`() {
        val events = mutableListOf<String>()
        val store = FakeBootStore(events).apply { state = prior; silenced = true }
        val hardware = FakeBootHardware(prior, events)
        val (controller, _) = startupController(true, store, hardware)

        controller.applyPersisted()

        assertEquals(emptyList<String>(), events)
        assertFalse(hardware.silenced)
    }

    @Test fun `turning it on silences once and records it, so the next start writes nothing`() {
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val hardware = FakeBootHardware(prior, events)
        val (controller, configured) = startupController(false, store, hardware)

        assertTrue(controller.set(true))
        assertTrue(configured())
        assertTrue(store.silenced)
        assertEquals(listOf("capture", "save", "silence"), events)

        events.clear()
        controller.applyPersisted()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun `the first run with the default on silences once and records it`() {
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val hardware = FakeBootHardware(prior, events)
        val (controller, _) = startupController(true, store, hardware)

        controller.applyPersisted()
        assertEquals(listOf("capture", "save", "silence"), events)
        assertTrue(store.silenced)

        events.clear()
        controller.applyPersisted()
        assertEquals("the second start leaves the volume alone", emptyList<String>(), events)
    }

    @Test fun `a first silence that failed is tried again at the next start`() {
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val (failing, _) = startupController(
            true, store, FakeBootHardware(prior, events, silenceOutcome = ControlApplyOutcome.FAILED),
        )

        failing.applyPersisted()
        assertFalse(store.silenced)

        events.clear()
        val (next, _) = startupController(true, store, FakeBootHardware(prior, events))
        next.applyPersisted()
        assertEquals(listOf("silence"), events)
        assertTrue(store.silenced)
    }

    @Test fun `turning it off restores the snapshot and clears the record`() {
        val events = mutableListOf<String>()
        val store = FakeBootStore(events)
        val hardware = FakeBootHardware(prior, events)
        val (controller, configured) = startupController(false, store, hardware)

        assertTrue(controller.set(true))
        assertTrue(controller.set(false))

        assertFalse(configured())
        assertEquals(prior, hardware.restored)
        assertFalse(store.silenced)
        assertNull(store.state)

        events.clear()
        assertTrue("turning it on again silences again", controller.set(true))
        assertEquals(listOf("capture", "save", "silence"), events)
    }

    private class FakeBootStore(
        private val events: MutableList<String>,
        private val saveSucceeds: Boolean = true,
    ) : BootChimeStateStore {
        var state: BootChimeState? = null
        override fun load() = state
        override fun save(state: BootChimeState): Boolean {
            events += "save"
            if (saveSucceeds) this.state = state
            return saveSucceeds
        }
        override fun clear(): Boolean {
            events += "clear"
            state = null
            silenced = false
            return true
        }
        var silenced = false
        override fun silenced() = silenced
        override fun markSilenced(): Boolean { silenced = true; return true }
        override fun clearSilenced(): Boolean { silenced = false; return true }
    }

    private class FakeBootHardware(
        private val captured: BootChimeState,
        private val events: MutableList<String>,
        private val restoreSucceeds: Boolean = true,
        /** What the hardware reported, so a test can tell "no path at all" from "failed this time". */
        private val silenceOutcome: ControlApplyOutcome = ControlApplyOutcome.APPLIED,
        private val restoreOutcome: ControlApplyOutcome? = null,
    ) : BootChimeHardware {
        var silenced = false
        var restored: BootChimeState? = null
        override fun capture(): BootChimeState {
            events += "capture"
            return captured
        }
        override fun silence(): ControlApplyOutcome {
            events += "silence"
            silenced = silenceOutcome == ControlApplyOutcome.APPLIED
            return silenceOutcome
        }
        override fun restore(state: BootChimeState): ControlApplyOutcome {
            events += "restore:$state"
            restored = state
            return restoreOutcome
                ?: if (restoreSucceeds) ControlApplyOutcome.APPLIED else ControlApplyOutcome.FAILED
        }
    }

    private class FakeBootChimeDirect(
        private val captured: BootChimeState,
        private val applySucceeds: Boolean,
        private val outcome: ControlApplyOutcome =
            if (applySucceeds) ControlApplyOutcome.APPLIED else ControlApplyOutcome.FAILED,
    ) : BootChimeDirectAccess {
        val applied = mutableListOf<BootChimeState>()
        override fun capture(): BootChimeState = captured
        override fun apply(state: BootChimeState): ControlApplyOutcome {
            applied += state
            return outcome
        }
    }
}
