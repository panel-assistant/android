package io.panelassistant.android

import io.panelassistant.android.util.HaTransportEvidence
import io.panelassistant.android.util.HaTransportFault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection is the contract three surfaces share, so it is tested behaviourally rather than by
 * reading the source that renders it: what a soak, a pasted dump and the info card each see for every
 * state the panel can actually be in.
 */
class RendererAdmissionPresentationTest {

    private val url = "https://home-assistant.example.invalid:8123"

    private fun record(
        state: RendererAdmissionState,
        outcome: AdmissionOutcome? = null,
        cached: Boolean = false,
        evidence: HaTransportEvidence = HaTransportEvidence.NONE,
        at: Long = 1_000L,
    ) = RendererAdmissionRuntime.Record(state, outcome, cached, evidence, at)

    // The default anchors describe an ordinary healthy panel some way past an upgrade: the process
    // started at the install, and the admission verdict was formed shortly after it. Tests that care
    // about the anchors override them; the rest inherit a shape that is internally consistent, so a
    // stale-evidence assertion cannot pass by accident on nonsense inputs.
    private fun present(
        record: RendererAdmissionRuntime.Record?,
        connected: Boolean = false,
        mode: RendererMode = RendererMode.BUILTIN,
        haUrl: String = url,
        family: String = "Automatic",
        now: Long = 61_000L,
        processStart: Long = 0L,
        packageUpdatedAt: Long? = 1_000_000L,
        nowWall: Long = 1_100_000L,
    ) = RendererAdmissionPresentation.of(
        mode = mode,
        haUrl = haUrl,
        addressFamilyPolicy = family,
        live = RendererAdmissionRuntime.Live(owner = 7L, record = record, frontendConnected = connected),
        nowElapsedMs = now,
        processStartElapsedMs = processStart,
        packageUpdatedAtMs = packageUpdatedAt,
        nowWallMs = nowWall,
    )

    private fun json(p: RendererAdmissionPresentation) = JSONObject(p.statusJson())

    // --- the healthy cases -------------------------------------------------------------------

    @Test fun aLiveVersionCheckWithAConnectedFrontendIsRendered() {
        val p = present(record(RendererAdmissionState.ADMITTED), connected = true)
        assertEquals(RendererAdmissionState.RENDERED, p.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_OK, p.outcome)
        assertEquals(HaTransportFault.NONE, p.fault)
        assertNull(p.faultDetail)
        assertEquals("", p.action)
        val j = json(p)
        assertEquals("rendered", j.getString("state"))
        assertTrue(j.getBoolean("rendered"))
        assertEquals(60_000L, j.getLong("observed_age_ms"))
    }

    @Test fun admittedOnACachedVersionIsNotRenderedUntilTheFrontendConnects() {
        // The admitted-and-blank shape: the version check could not complete, the previously verified
        // version admitted the renderer, and the page then hit the same wall. Every other check on
        // the panel reads green, because none of them asks whether a dashboard actually appeared.
        val cached = record(
            RendererAdmissionState.ADMITTED,
            cached = true,
            evidence = HaTransportEvidence(HaTransportFault.TLS_TRUST, "CertPathValidatorException"),
        )
        val blank = present(cached, connected = false)
        assertEquals(RendererAdmissionState.ADMITTED, blank.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_OK_CACHED, blank.outcome)
        assertEquals(HaTransportFault.TLS_TRUST, blank.fault)
        assertFalse(json(blank).getBoolean("rendered"))
        assertTrue(blank.summary.contains("previously verified"))

        // The same verdict WITH a live frontend is genuinely up, and still says how it was admitted.
        val up = present(cached, connected = true)
        assertEquals(RendererAdmissionState.RENDERED, up.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_OK_CACHED, up.outcome)
        assertTrue(up.summary.contains("previously verified"))
    }

    @Test fun aBlockedVerdictIsNeverUpgradedByAStaleConnection() {
        // A blocked screen tears the page down without ending the renderer generation, so a
        // connection flag that had not yet been cleared must not resurrect "rendered".
        val p = present(
            record(RendererAdmissionState.BLOCKED, AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED),
            connected = true,
        )
        assertEquals(RendererAdmissionState.BLOCKED, p.state)
        assertFalse(json(p).getBoolean("rendered"))
    }

    // --- the blocked matrix ------------------------------------------------------------------

    @Test fun aTransportFailureNamesTheClassifiedFaultAndRetriesOnItsOwn() {
        val p = present(
            record(
                RendererAdmissionState.BLOCKED,
                AdmissionOutcome.TRANSPORT_FAILED,
                evidence = HaTransportEvidence(HaTransportFault.TLS_TRUST, "CertPathValidatorException"),
            ),
        )
        assertEquals(RendererAdmissionState.BLOCKED, p.state)
        assertEquals("transport_failed", p.outcome)
        assertEquals(HaTransportFault.TLS_TRUST, p.fault)
        assertEquals("CertPathValidatorException", p.faultDetail)
        assertEquals("from_base", p.recovery)
        assertTrue(p.action.contains("recovers unattended"))
        assertTrue(p.summary.contains("tls_trust"))
    }

    @Test fun anAbsentCredentialIsBlockedAndSaysItNeedsAPerson() {
        val p = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.SIGN_IN_REQUIRED))
        assertEquals("sign_in_required", p.outcome)
        assertEquals("manual_only", p.recovery)
        // No transport failure was involved, so no fault is invented and none is appended to the copy.
        assertEquals(HaTransportFault.NONE, p.fault)
        assertFalse(p.summary.contains("("))
        assertTrue(p.action.contains("will not clear on its own"))
    }

    @Test fun aRefusedCredentialIsBlockedUntilAnExplicitRetry() {
        val p = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.CREDENTIAL_REFUSED))
        assertEquals("credential_refused", p.outcome)
        assertEquals("manual_only", p.recovery)
        assertEquals(HaTransportFault.NONE, p.fault)
        assertTrue(p.action.contains("will not clear on its own"))
    }

    @Test fun anUnsupportedServerIsBlockedAndTerminalUntilSomeoneUpgradesIt() {
        val p = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.UNSUPPORTED_HA))
        assertEquals("unsupported_ha", p.outcome)
        assertEquals("manual_only", p.recovery)
        assertTrue(p.summary.contains("older than the supported floor"))
    }

    @Test fun everyAdmissionOutcomeProducesItsOwnBlockedSummaryAndAKnownRecovery() {
        // Enumerated from the enum rather than listed by hand: a new outcome that nobody described
        // would otherwise ship with whatever copy the fallback happened to give it.
        val summaries = AdmissionOutcome.entries.associateWith { outcome ->
            present(record(RendererAdmissionState.BLOCKED, outcome))
        }
        summaries.forEach { (outcome, p) ->
            assertEquals(outcome.name.lowercase(), p.outcome)
            assertTrue("$outcome has no recovery class", p.recovery in setOf("from_base", "at_ceiling", "manual_only"))
            assertTrue("$outcome blocked copy is empty", p.summary.startsWith("blocked — "))
            assertTrue("$outcome has no action", p.action.isNotBlank())
        }
        assertEquals(
            "two outcomes share one summary, so a report cannot tell them apart",
            AdmissionOutcome.entries.size,
            summaries.values.map { it.summary }.toSet().size,
        )
    }

    // --- the uninformative cases -------------------------------------------------------------

    @Test fun nothingObservedIsNeverAStatementOfHealth() {
        val p = present(record = null)
        assertEquals(RendererAdmissionState.UNOBSERVED, p.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_UNOBSERVED, p.outcome)
        assertNull(p.observedAgeMs)
        assertFalse(json(p).getBoolean("rendered"))
        assertTrue(json(p).isNull("observed_age_ms"))
        assertTrue(p.diagnosticLine().contains("observed_age=never"))
        assertNotEquals("rendered", p.state.wire)
    }

    @Test fun aCheckInFlightIsNotAnOutcome() {
        val p = present(record(RendererAdmissionState.CHECKING))
        assertEquals(RendererAdmissionState.CHECKING, p.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_UNOBSERVED, p.outcome)
        assertEquals("none", p.recovery)
    }

    @Test fun anExternalRendererSaysItIsNotObservedRatherThanOmittingTheObject() {
        val p = present(record = null, mode = RendererMode.EXTERNAL)
        assertEquals("external", json(p).getString("mode"))
        assertEquals("unobserved", json(p).getString("state"))
        assertTrue(p.statusText()!!.contains("not observed by ha-paneld"))
        // The battery caught this test passing while an external renderer was routed through the
        // built-in branch: `statusText` switches on the mode by itself, so the row read correctly
        // while the machine-readable summary and action silently described a built-in panel. The
        // JSON body is what a fleet check reads, so it is what must be asserted.
        assertTrue(json(p).getString("summary").contains("external renderer is configured"))
        assertTrue(json(p).getString("action").contains("cannot verify a foreign renderer"))
        assertEquals("none", json(p).getString("recovery"))
        // A consumer must be able to tell "not applicable" from "applicable and silent" without
        // inferring anything from an absent field.
        assertNotEquals(present(record = null).statusText(), p.statusText())
        assertNotEquals(present(record = null).summary, p.summary)
    }

    @Test fun noConfiguredRendererEarnsNoInfoRowButStillReportsItsMode() {
        val p = present(record = null, mode = RendererMode.NONE)
        assertEquals("none", json(p).getString("mode"))
        assertNull(p.statusText())
    }

    // --- redaction ---------------------------------------------------------------------------

    @Test fun noSurfaceCarriesTheHostTheUrlOrRawExceptionText() {
        val p = present(
            record(
                RendererAdmissionState.BLOCKED,
                AdmissionOutcome.TRANSPORT_FAILED,
                evidence = HaTransportEvidence(HaTransportFault.DNS, "UnknownHostException"),
            ),
        )
        listOf(p.statusJson(), p.diagnosticLine(), p.statusText().orEmpty()).forEach { rendered ->
            assertFalse(rendered, rendered.contains("example.invalid"))
            assertFalse(rendered, rendered.contains("8123"))
            assertFalse(rendered, rendered.contains(url))
        }
        // The scheme alone survives, because "is this panel talking TLS at all?" is the first
        // question a certificate failure raises and a scheme identifies nobody.
        assertEquals("https", p.transport)
        assertEquals("https", json(p).getString("transport"))
    }

    @Test fun anUnparseableEndpointReportsAnUnknownTransportRatherThanGuessing() {
        assertEquals("unknown", present(record = null, haUrl = "").transport)
        assertEquals("unknown", present(record = null, haUrl = "hass.example.net").transport)
        assertEquals("http", present(record = null, haUrl = "HTTP://hass.example.net").transport)
    }

    @Test fun theAddressFamilyPolicyIsNormalisedToAWireToken() {
        assertEquals("prefer_ipv4", present(record = null, family = "Prefer IPv4").addressFamilyPolicy)
        assertEquals("force_ipv4", present(record = null, family = "Force IPv4").addressFamilyPolicy)
        assertEquals("automatic", present(record = null, family = "  ").addressFamilyPolicy)
    }

    // --- shape -------------------------------------------------------------------------------

    @Test fun theStatusObjectCarriesEveryFieldASoakReads() {
        val j = json(present(record(RendererAdmissionState.ADMITTED), connected = true))
        listOf(
            "mode", "state", "outcome", "fault", "fault_detail", "recovery", "transport",
            "address_family_policy", "observed_age_ms", "process_age_ms", "package_updated_age_ms",
            "rendered", "theme_policy", "theme_effective", "theme_overridden", "summary", "action",
        ).forEach { assertTrue("missing $it", j.has(it)) }
    }

    @Test fun theDiagnosticLineIsOneLineAndSelfLabelling() {
        val line = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.NO_LEGAL_DASHBOARD)).diagnosticLine()
        assertTrue(line.startsWith("[renderer] "))
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("outcome=no_legal_dashboard"))
        assertTrue(line.contains("recovery=at_ceiling"))
        assertTrue(line.contains("detail=none"))
    }

    @Test fun theInfoRowSaysTheAgeIsTheAdmissionsRatherThanASighting() {
        val text = present(record(RendererAdmissionState.ADMITTED, at = 1_000L), connected = true, now = 601_000L)
            .statusText()!!
        assertTrue(text.startsWith("built-in · "))
        assertTrue(text.contains("admitted 10m ago"))
        // The row used to say "seen 10m ago" for exactly this panel — one that is rendering right now
        // and was admitted ten minutes ago. On a panel up for a day that reads as a dashboard nobody
        // has seen working since yesterday, which is the opposite of what the state says.
        assertFalse(text, text.contains("seen "))
        assertTrue(text.contains("dashboard rendered"))
    }

    @Test fun aClockThatWentBackwardsReportsZeroRatherThanANegativeAge() {
        val p = present(record(RendererAdmissionState.ADMITTED, at = 5_000L), now = 1_000L)
        assertEquals(0L, p.observedAgeMs)
    }

    // --- the upgrade anchors -----------------------------------------------------------------
    //
    // These exist because a deployment check cannot tell post-upgrade evidence from pre-upgrade
    // evidence by age alone. A rollout updates its panels minutes or hours apart — one first, as a
    // trial, and the rest afterwards — so a single absolute window sized to the last update calls the
    // earliest panel stale while it is rendering perfectly. That has happened: the panel had been
    // updated about 50 minutes before the sweep, its verdict was formed two seconds after that
    // update, and the window allowed 30. One shared window cannot judge panels updated at different
    // times; each panel's own package-update anchor can.

    @Test fun healthyPostUpgradeEvidenceIsYoungerThanThePackageUpdate() {
        // That shape, to scale: installed 3044s ago, process started at the install, admission
        // recorded two seconds later, still rendering.
        val p = present(
            record(RendererAdmissionState.ADMITTED, at = 2_000L),
            connected = true,
            now = 3_046_000L,
            processStart = 0L,
            packageUpdatedAt = 5_000_000L,
            nowWall = 8_044_000L,
        )
        assertEquals(RendererAdmissionState.RENDERED, p.state)
        assertEquals(3_044_000L, p.observedAgeMs)
        assertEquals(3_044_000L, p.packageUpdatedAgeMs)
        assertEquals(3_046_000L, p.processAgeMs)
        // The property a wave gate needs, and the one an absolute window got wrong here: this
        // evidence postdates its own install, however old the wall clock says it is.
        assertTrue("evidence must postdate the package update", p.observedAgeMs!! <= p.packageUpdatedAgeMs!!)
        val j = json(p)
        // Read by presence-then-value rather than `getLong`, which throws a JSONException on a missing
        // key. A renamed or dropped anchor is a defect this test must REPORT — naming the field — not
        // one it blows up on, and a mutation battery cannot credit a kill it cannot classify.
        assertTrue("package_updated_age_ms is missing", j.has("package_updated_age_ms"))
        assertTrue("process_age_ms is missing", j.has("process_age_ms"))
        assertEquals(3_044_000L, j.optLong("package_updated_age_ms", -1L))
        assertEquals(3_046_000L, j.optLong("process_age_ms", -1L))
    }

    @Test fun evidenceOlderThanThePackageUpdateStaysVisiblyOlder() {
        // The failure the gate must keep catching: a verdict formed before the app was replaced. The
        // runtime cannot actually produce it — the record is process-local and a replacement kills the
        // process — but the projection must still make it detectable rather than smoothing it away,
        // because the gate's whole job is to refuse evidence it cannot attribute to this build.
        val p = present(
            record(RendererAdmissionState.ADMITTED, at = 1_000L),
            connected = true,
            now = 4_000_000L,
            processStart = 0L,
            packageUpdatedAt = 8_000_000L,
            nowWall = 9_000_000L,
        )
        assertEquals(3_999_000L, p.observedAgeMs)
        assertEquals(1_000_000L, p.packageUpdatedAgeMs)
        assertTrue("stale evidence must not look post-upgrade", p.observedAgeMs!! > p.packageUpdatedAgeMs!!)
    }

    @Test fun anObservationCanNeverBeOlderThanTheProcessHoldingIt() {
        val p = present(record(RendererAdmissionState.ADMITTED, at = 40_000L), connected = true, now = 100_000L, processStart = 10_000L)
        assertEquals(60_000L, p.observedAgeMs)
        assertEquals(90_000L, p.processAgeMs)
        assertTrue(p.observedAgeMs!! <= p.processAgeMs)
    }

    @Test fun anUnreadablePackageUpdateTimeIsNullRatherThanAnAncientInstall() {
        // 0 is the package manager declining to answer. Reported as an age it would be decades, and
        // every observation on the panel would look comfortably post-upgrade — a gate that passes
        // precisely when it knows least.
        assertNull(present(record(RendererAdmissionState.ADMITTED), packageUpdatedAt = 0L).packageUpdatedAgeMs)
        assertNull(present(record(RendererAdmissionState.ADMITTED), packageUpdatedAt = null).packageUpdatedAgeMs)
        assertTrue(json(present(record(RendererAdmissionState.ADMITTED), packageUpdatedAt = null)).isNull("package_updated_age_ms"))
        assertTrue(present(record(RendererAdmissionState.ADMITTED), packageUpdatedAt = null).diagnosticLine().contains("package_updated=unknown"))
    }

    @Test fun aClockStandingBeforeItsAnchorReportsZeroRatherThanANegativeAge() {
        // A negative age is worse than an unknown one: it sorts younger than every real observation,
        // so it would satisfy any "this evidence postdates X" rule it was fed to.
        val p = present(
            record(RendererAdmissionState.ADMITTED),
            now = 1_000L,
            processStart = 500_000L,
            packageUpdatedAt = 9_000_000L,
            nowWall = 1_000_000L,
        )
        assertEquals(0L, p.packageUpdatedAgeMs)
        assertEquals(0L, p.processAgeMs)
    }

    @Test fun theAnchorsAreReportedForAForeignRendererToo() {
        // Applicability is stated, never inferred from an absent field: "ha-paneld does not observe
        // this renderer" and "this panel cannot say when it was upgraded" are different answers.
        val p = present(record = null, mode = RendererMode.EXTERNAL, processStart = 1_000L, now = 61_000L)
        val j = json(p)
        assertTrue(j.isNull("observed_age_ms"))
        assertTrue("process_age_ms is missing", j.has("process_age_ms"))
        assertTrue("package_updated_age_ms is missing", j.has("package_updated_age_ms"))
        assertEquals(60_000L, j.optLong("process_age_ms", -1L))
        assertEquals(100_000L, j.optLong("package_updated_age_ms", -1L))
    }

    @Test fun ageFormattingCoversEachMagnitude() {
        assertEquals("23s", RendererAdmissionPresentation.fmtAge(23_000L))
        assertEquals("47m", RendererAdmissionPresentation.fmtAge(47 * 60_000L))
        assertEquals("5h12m", RendererAdmissionPresentation.fmtAge((5 * 3600 + 12 * 60) * 1000L))
        assertEquals("3d2h", RendererAdmissionPresentation.fmtAge((3 * 86400 + 2 * 3600) * 1000L))
    }
}
