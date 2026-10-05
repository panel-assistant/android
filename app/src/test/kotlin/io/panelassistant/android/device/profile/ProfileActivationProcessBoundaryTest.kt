package io.panelassistant.android.device.profile

import io.panelassistant.android.ProcessBoundaryCommitment
import io.panelassistant.android.ServiceBoundaryProof
import io.panelassistant.android.ServiceGenerationAdmission
import io.panelassistant.android.ServiceTeardownBoundary
import io.panelassistant.android.ServiceTeardownDisposition
import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.EvdevDeviceInspector
import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileIssue
import io.panelassistant.android.device.profile.ProfileIssueSeverity
import io.panelassistant.android.device.profile.ProfileMutation
import io.panelassistant.android.device.profile.ProfilePreferences
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfileYaml
import io.panelassistant.android.device.profile.RuntimeProfileRegistry
import io.panelassistant.android.device.profile.testProfileDocument
import io.panelassistant.android.runServiceBoundary
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Issue #107 activation defect, reproduced without a panel.
 *
 * A reporter on public 0.9.7-rc2 imported a device profile, activated it, and watched the restart put
 * the default profile back. Their logcat says why: the boundary is requested, a *second*
 * `foreground service started` appears in the same pid one second later, the database reports a locked
 * journal-mode change, the process exits twenty seconds after that, and the fresh process opens with
 * `Previous profile activation was rolled back after an unhealthy restart`.
 *
 * [PanelProcess] models one OS process hosting successive service generations against durable panel
 * state, driving the production [ProcessBoundaryCommitment], [ServiceTeardownBoundary],
 * [runServiceBoundary] and [RuntimeProfileRegistry]. Only the ordering the defect turns on is modelled,
 * and it is taken from `PaneldService`: foreground promotion, then generation admission, then profile
 * resolution and exclusive owner acquisition in `onCreate`, then the runtime start that proves the
 * activation healthy from behind `restartLease.awaitPredecessor()`.
 *
 * [theUnfencedAdmissionOfTheReleasedBuildReproducesTheFieldRollback] calibrates the model before
 * anything else relies on it: driven with the released build's admission rule it produces the
 * reporter's exact outcome, including the two concurrent owners behind their locked database. Every
 * other test then drives the production rule.
 */
class ProfileActivationProcessBoundaryTest {
    private lateinit var directory: File
    private lateinit var panel: Panel

    @Before fun setUp() {
        directory = Files.createTempDirectory("activation-boundary-test").toFile()
        panel = Panel(directory)
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun theUnfencedAdmissionOfTheReleasedBuildReproducesTheFieldRollback() {
        val running = PanelProcess(panel, admit = { ServiceGenerationAdmission.START })
        val outgoing = running.deliverStart()
        val staged = running.importAndActivateProfile()

        running.requestSafeProcessBoundary(outgoing)

        // The re-armed start request was served inside the dying process, and the generation it created
        // claimed a staged activation it could never live long enough to prove.
        val doomed = running.generations.last()
        assertTrue(doomed !== outgoing)
        assertFalse(doomed.stoodDown)
        assertEquals(staged, doomed.resolvedRef)
        assertNotNull(doomed.activationGeneration)
        assertFalse(doomed.startupCompleted)
        assertEquals(2, running.peakConcurrentOwners)
        assertTrue(running.exited)

        val fresh = PanelProcess(panel)
        val recovered = fresh.deliverStart()

        assertEquals("generic", recovered.resolvedRef?.id)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, fresh.registry.status().activation.phase)
        assertTrue(
            recovered.issues.any {
                it.severity == ProfileIssueSeverity.WARNING &&
                    it.message == "Previous profile activation was rolled back after an unhealthy restart."
            },
        )
    }

    @Test fun startStickyRecreationAfterTheBoundaryActivatesTheStagedProfile() {
        val running = PanelProcess(panel)
        val outgoing = running.deliverStart()
        val staged = running.importAndActivateProfile()

        running.requestSafeProcessBoundary(outgoing)
        val fresh = PanelProcess(panel)
        val recreated = fresh.deliverStart()

        assertEquals(staged, recreated.resolvedRef)
        assertTrue(recreated.startupCompleted)
        assertEquals(ProfileActivationPhase.ACTIVE, fresh.registry.status().activation.phase)
        assertEquals(staged, fresh.registry.status().active!!.ref)
        assertTrue(recreated.issues.none { it.path == "activation" })
    }

    @Test fun aGenerationCreatedInsideACommittedBoundaryOwnsNothingAndKeepsItsStickyRecord() {
        val running = PanelProcess(panel)
        val outgoing = running.deliverStart()
        running.importAndActivateProfile()

        running.requestSafeProcessBoundary(outgoing)

        val doomed = running.generations.last()
        assertTrue(doomed !== outgoing)
        assertTrue(doomed.foregroundPromoted)
        assertTrue(doomed.stoodDown)
        assertEquals(START_STICKY_RESULT, doomed.startCommandResult)
        assertFalse(doomed.stoppedSelf)
        assertNull(doomed.resolvedRef)
        assertNull(doomed.activationGeneration)
        // One runtime held the database and the hardware owners for the whole boundary.
        assertEquals(1, running.peakConcurrentOwners)
        // The staged activation is untouched, so the fresh process still has one to claim.
        assertEquals(ProfileActivationPhase.PENDING, running.registry.status().activation.phase)
    }

    @Test fun anOrdinaryCleanStopNeverCommitsTheProcessAndReleasesItsSameProcessSuccessor() {
        val process = PanelProcess(panel)
        val first = process.deliverStart()
        val staged = process.importAndActivateProfile()

        process.stopCleanly(first)

        assertFalse(process.exited)
        val successor = process.deliverStart()
        assertFalse(successor.stoodDown)
        assertEquals(staged, successor.resolvedRef)
        assertTrue(successor.startupCompleted)
        assertEquals(ProfileActivationPhase.ACTIVE, process.registry.status().activation.phase)
        assertEquals(1, process.peakConcurrentOwners)
    }

    @Test fun aGenuinelyUnhealthyActivationStillRollsBackToTheLastKnownGoodProfile() {
        val running = PanelProcess(panel)
        val outgoing = running.deliverStart()
        val staged = running.importAndActivateProfile()
        running.requestSafeProcessBoundary(outgoing)

        val unhealthy = PanelProcess(panel, runtimeStartSucceeds = false)
        val claimed = unhealthy.deliverStart()
        assertEquals(staged, claimed.resolvedRef)
        assertNotNull(claimed.activationGeneration)
        assertFalse(claimed.startupCompleted)

        val recovery = PanelProcess(panel)
        val rolledBack = recovery.deliverStart()

        assertEquals("generic", rolledBack.resolvedRef?.id)
        assertNull(rolledBack.activationGeneration)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, recovery.registry.status().activation.phase)
        assertEquals(ProfileSelection.Auto, recovery.registry.status().selection)
    }

    @Test fun aProvenActivationSurvivesEveryLaterProcessStart() {
        val running = PanelProcess(panel)
        val outgoing = running.deliverStart()
        val staged = running.importAndActivateProfile()
        running.requestSafeProcessBoundary(outgoing)
        PanelProcess(panel).deliverStart()

        val later = PanelProcess(panel)
        val restarted = later.deliverStart()

        assertEquals(staged, restarted.resolvedRef)
        assertNull(restarted.activationGeneration)
        assertEquals(ProfileActivationPhase.ACTIVE, later.registry.status().activation.phase)
        assertEquals(staged, later.registry.status().active!!.ref)
    }
}

private const val START_STICKY_RESULT = 1

/** Durable panel state: the profile catalog and its preference store survive every process boundary. */
private class Panel(private val directory: File) {
    private val preferences = InMemoryProfilePreferences()
    val facts = DeviceFacts("test-panel", "test-device", "fw-1")
    private val bundled = mapOf(
        "generic.yaml" to ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true)),
    )

    fun registry() = RuntimeProfileRegistry(
        filesDir = directory,
        preferences = preferences,
        bundledLoader = { bundled },
        facts = facts,
        coreVersion = "1.0.0",
        clock = { 1000L },
        evdevInspector = EvdevDeviceInspector { null },
    )
}

/**
 * One OS process hosting successive `PaneldService` generations.
 *
 * `admit` is the generation-admission rule under test and defaults to the production one.
 * [deliverStart] is Android delivering a start request: `onCreate` followed by `onStartCommand`. The
 * runtime start that proves an activation healthy is queued rather than run inline, because in the
 * service it sits behind `restartLease.awaitPredecessor()`; a process exit discards that queue, which
 * is what the field log shows happening to the doomed generation.
 */
private class PanelProcess(
    panel: Panel,
    private val admit: (ProcessBoundaryCommitment) -> ServiceGenerationAdmission =
        ProcessBoundaryCommitment::admitServiceGeneration,
    private val runtimeStartSucceeds: Boolean = true,
) {
    val registry = panel.registry()
    val generations = mutableListOf<Generation>()
    var exited = false
        private set
    var peakConcurrentOwners = 0
        private set

    private val facts = panel.facts
    private val commitment = ProcessBoundaryCommitment()
    private val boundary = ServiceTeardownBoundary()
    private val liveOwners = mutableSetOf<Generation>()
    private val fencedRuntimeStarts = ArrayDeque<Generation>()
    private var restartAfterInternalBoundary = false

    class Generation {
        var foregroundPromoted = false
            internal set
        var stoodDown = false
            internal set
        var startCommandResult: Int? = null
            internal set
        var stoppedSelf = false
            internal set
        var resolvedRef: ProfileRef? = null
            internal set
        var activationGeneration: Long? = null
            internal set

        /** The fenced runtime start ran to completion, proving any activation this generation owned. */
        var startupCompleted = false
            internal set
        var issues: List<ProfileIssue> = emptyList()
            internal set
    }

    fun deliverStart(): Generation {
        check(!exited) { "the process had already exited" }
        val generation = Generation()
        generations += generation
        // onCreate promotes to the foreground before anything heavyweight, then decides admission.
        generation.foregroundPromoted = true
        generation.startCommandResult = START_STICKY_RESULT
        if (admit(commitment) == ServiceGenerationAdmission.STAND_DOWN) {
            generation.stoodDown = true
            return generation
        }
        val resolved = registry.resolveForStartup()
        generation.resolvedRef = resolved.summary.ref
        generation.activationGeneration = resolved.activationGeneration
        generation.issues = resolved.issues
        liveOwners += generation
        peakConcurrentOwners = maxOf(peakConcurrentOwners, liveOwners.size)
        // onStartCommand hands the runtime start to a lane that waits for the predecessor generation.
        fencedRuntimeStarts += generation
        if (liveOwners.size == 1) drainFencedRuntimeStarts()
        return generation
    }

    /** The reporter's own HTTP sequence: import the community profile, then activate it. */
    fun importAndActivateProfile(): ProfileRef {
        val raw = ProfileYaml.serialize(testProfileDocument(facts = facts))
        val preview = registry.preview(raw)
        check(registry.importProfile(raw, preview.previewToken!!) is ProfileMutation.Success) { "import rejected" }
        val ref = preview.summary!!.ref
        val selected = registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        check(selected is ProfileMutation.Success && selected.restartRequired) { "activation was not staged" }
        return ref
    }

    fun requestSafeProcessBoundary(generation: Generation) {
        if (!boundary.requestExplicitBoundary()) return
        commitment.commit()
        restartAfterInternalBoundary = true
        destroy(generation)
    }

    fun stopCleanly(generation: Generation) = destroy(generation)

    /**
     * `onDestroy` re-arms the start request before the finalizer proves teardown, and in the field its
     * bounded main-thread budget expired first, so the outgoing owners were still live when the
     * successor was created.
     */
    private fun destroy(generation: Generation) {
        if (restartAfterInternalBoundary) deliverStart()
        finishBoundary(generation)
    }

    private fun finishBoundary(generation: Generation) {
        runServiceBoundary(
            boundary = boundary,
            completed = true,
            prepare = {},
            prove = { ServiceBoundaryProof(externalStateSafe = true, forceFreshProcess = false) },
            pauseBeforeRetry = { error("external state was proved safe on the first attempt") },
            finish = { disposition ->
                liveOwners -= generation
                if (disposition == ServiceTeardownDisposition.EXIT) {
                    fencedRuntimeStarts.clear()
                    exited = true
                } else {
                    drainFencedRuntimeStarts()
                }
            },
        )
    }

    private fun drainFencedRuntimeStarts() {
        while (fencedRuntimeStarts.isNotEmpty()) {
            val generation = fencedRuntimeStarts.removeFirst()
            if (!runtimeStartSucceeds) continue
            generation.activationGeneration?.let {
                check(registry.markActivationHealthy(it)) { "the activation could not be proved healthy" }
            }
            generation.startupCompleted = true
        }
    }
}

private class InMemoryProfilePreferences : ProfilePreferences {
    private val values = linkedMapOf<String, Any>()

    override fun getString(key: String, default: String) = values[key] as? String ?: default

    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default

    override fun put(vararg values: Pair<String, Any?>): Boolean {
        val next = LinkedHashMap(this.values)
        values.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        this.values.clear()
        this.values.putAll(next)
        return true
    }
}
