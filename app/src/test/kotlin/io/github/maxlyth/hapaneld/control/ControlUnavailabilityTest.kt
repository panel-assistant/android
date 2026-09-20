package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.RootRunOutcome
import io.github.maxlyth.hapaneld.platform.RootShell
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a controller may and may not call structurally unavailable.
 *
 * This is the half of the phantom-warning fix that decides whether the Configure page is allowed to stop
 * saying "waiting to apply". An earlier design read the shared root probe and was rejected for it: boot
 * chime has an app path, so root's absence proves nothing on its own, and a root manager that denied one
 * command is not a panel without root. Every case below is the result of an attempt that actually ran.
 */
class ControlUnavailabilityTest {
    private val prior = BootChimeState(2, null, 7, 3, 6)

    @Test fun `a root manager that denied the command keeps the transition retryable`() {
        // su ran and said no. That can succeed on the next boot, so it must never read as a panel that
        // cannot apply the value.
        val hardware = AndroidBootChimeHardware(
            direct = PermissionlessDirect(prior),
            root = FakeRootShell(runResult = false, suLaunchable = true),
            daemon = FakeDaemon(),
        )

        assertEquals(ControlApplyOutcome.FAILED, hardware.silence())
    }

    @Test fun `an su binary this app may not execute is an absent root path`() {
        // The reported panel's actual state, and the case a missing-binary test would miss entirely:
        // /system/xbin/su is present and mode 4750 root:shell, so the app's exec is refused EACCES
        // rather than ENOENT. No root process is ever created either way, which is what NO_LAUNCH
        // means and why the classification is not "is the file there".
        val hardware = AndroidBootChimeHardware(
            direct = PermissionlessDirect(prior),
            root = FakeRootShell(runResult = false, suLaunchable = false),
            daemon = FakeDaemon(),
        )

        assertEquals(ControlApplyOutcome.UNAVAILABLE, hardware.silence())
    }

    @Test fun `a launch that started no process is an absent root path whatever the errno was`() {
        // The production judgement, separated from the exec machinery so it can be scored without a
        // device. A refused launch is not a missing file, and the reported panel is exactly that case:
        // su exists, and the app may not execute it.
        assertEquals(
            "a launch refusal is a property of the device, not of this attempt",
            RootRunOutcome.NO_LAUNCH,
            classifyRootRun(ran = false, launchCreatedNoProcess = true, rootKnownUnusable = false),
        )
        assertEquals(
            RootRunOutcome.NO_LAUNCH,
            classifyRootRun(ran = false, launchCreatedNoProcess = false, rootKnownUnusable = true),
        )
        assertEquals(
            "a root manager that ran the command and refused it can succeed next time",
            RootRunOutcome.RAN_FAILED,
            classifyRootRun(ran = false, launchCreatedNoProcess = false, rootKnownUnusable = false),
        )
        assertEquals(
            RootRunOutcome.RAN_OK,
            classifyRootRun(ran = true, launchCreatedNoProcess = false, rootKnownUnusable = false),
        )
    }

    @Test fun `the default root shell never claims a capability is absent`() {
        // Any RootShell that has not been taught the distinction must fail safe: a fake or an older
        // implementation reporting NO_LAUNCH by accident would stall values on no evidence at all.
        val defaultOnly = object : RootShell {
            override fun available() = true
            override fun run(cmd: String) = false
            override fun runOutput(cmd: String): String? = null
            override fun runBytes(cmd: String): ByteArray? = null
            override fun fireAndForget(cmd: String) = false
        }

        assertEquals(RootRunOutcome.RAN_FAILED, defaultOnly.runClassified("true"))
    }

    @Test fun `a reachable helper that refused keeps the transition retryable`() {
        val hardware = AndroidBootChimeHardware(
            direct = PermissionlessDirect(prior),
            root = FakeRootShell(runResult = false, suLaunchable = false),
            daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "PARTIAL")),
        )

        assertEquals(ControlApplyOutcome.FAILED, hardware.silence())
    }

    @Test fun `an app path that merely failed keeps the transition retryable`() {
        // No permission problem: the writes returned false. Only a refused permission is a property of
        // the panel rather than of this attempt.
        val hardware = AndroidBootChimeHardware(
            direct = FailingDirect(prior),
            root = FakeRootShell(runResult = false, suLaunchable = false),
            daemon = FakeDaemon(),
        )

        assertEquals(ControlApplyOutcome.FAILED, hardware.silence())
    }

    @Test fun `any working path still applies`() {
        val viaHelper = AndroidBootChimeHardware(
            direct = PermissionlessDirect(prior),
            root = FakeRootShell(runResult = false, suLaunchable = false),
            daemon = FakeDaemon(mapOf("BOOTCHIME SILENCE" to "OK")),
        )
        assertEquals(ControlApplyOutcome.APPLIED, viaHelper.silence())

        val viaRoot = AndroidBootChimeHardware(
            direct = PermissionlessDirect(prior),
            root = FakeRootShell(runResult = true),
            daemon = FakeDaemon(),
        )
        assertEquals(ControlApplyOutcome.APPLIED, viaRoot.silence())
    }

    @Test fun `the direct path reports absence only when every failed write was refused`() {
        assertEquals(
            ControlApplyOutcome.UNAVAILABLE,
            applyBootChimeDirect(
                state = prior,
                writeSetting = { _, _ -> throw SecurityException("WRITE_SETTINGS") },
                writeStream = { _, _ -> true },
            ),
        )
        assertEquals(
            "one ordinary failure among refusals is still a retryable transition",
            ControlApplyOutcome.FAILED,
            applyBootChimeDirect(
                state = prior,
                writeSetting = { _, _ -> throw SecurityException("WRITE_SETTINGS") },
                writeStream = { _, _ -> false },
            ),
        )
        assertEquals(
            ControlApplyOutcome.APPLIED,
            applyBootChimeDirect(prior, writeSetting = { _, _ -> true }, writeStream = { _, _ -> true }),
        )
    }

    @Test fun `touch sound is unavailable when its only permission is refused`() {
        // WRITE_SETTINGS is the whole of this controller's path, so its refusal will be raised again on
        // the next boot. A capture or persistence failure says nothing about the hardware.
        val refusing = TouchSoundStatePolicy(RecordingStore(), RefusedTouchSoundHardware())
        assertEquals(ControlApplyOutcome.UNAVAILABLE, refusing.enable())
        assertEquals(ControlApplyOutcome.UNAVAILABLE, refusing.disable())

        val uncapturable = TouchSoundStatePolicy(RecordingStore(), UncapturableTouchSoundHardware())
        assertEquals(
            "a capture failure is this attempt's problem, not the panel's",
            ControlApplyOutcome.FAILED,
            uncapturable.enable(),
        )
    }

    /** Writes refused for a permission this app does not hold; streams are unrestricted. */
    private class PermissionlessDirect(private val captured: BootChimeState) : BootChimeDirectAccess {
        override fun capture(): BootChimeState = captured
        override fun apply(state: BootChimeState): ControlApplyOutcome = applyBootChimeDirect(
            state = state,
            writeSetting = { _, _ -> throw SecurityException("WRITE_SETTINGS not held") },
            writeStream = { _, _ -> true },
        )
    }

    private class FailingDirect(private val captured: BootChimeState) : BootChimeDirectAccess {
        override fun capture(): BootChimeState = captured
        override fun apply(state: BootChimeState): ControlApplyOutcome = applyBootChimeDirect(
            state = state,
            writeSetting = { _, _ -> false },
            writeStream = { _, _ -> false },
        )
    }

    private class RefusedTouchSoundHardware : TouchSoundHardware {
        override fun capture(): TouchSoundState = TouchSoundState(1)
        override fun enable(): ControlApplyOutcome = ControlApplyOutcome.UNAVAILABLE
        override fun restore(state: TouchSoundState): ControlApplyOutcome = ControlApplyOutcome.UNAVAILABLE
        override fun disableConservatively(): ControlApplyOutcome = ControlApplyOutcome.UNAVAILABLE
    }

    private class UncapturableTouchSoundHardware : TouchSoundHardware {
        override fun capture(): TouchSoundState? = null
        override fun enable(): ControlApplyOutcome = ControlApplyOutcome.APPLIED
        override fun restore(state: TouchSoundState): ControlApplyOutcome = ControlApplyOutcome.APPLIED
        override fun disableConservatively(): ControlApplyOutcome = ControlApplyOutcome.APPLIED
    }

    private class RecordingStore : TouchSoundStateStore {
        private var activeState: Boolean? = null
        private var priorState: TouchSoundState? = null
        override fun active(): Boolean? = activeState
        override fun prior(): TouchSoundState? = priorState
        override fun saveEnabled(prior: TouchSoundState): Boolean {
            activeState = true
            priorState = prior
            return true
        }
        override fun saveDisabledAndClearPrior(): Boolean {
            activeState = false
            priorState = null
            return true
        }
        override fun retireLegacyStreamState(): Boolean = true
    }
}
