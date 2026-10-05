package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.panelassistant.android.util.ProfileRestartCoordinator
import org.junit.Assume.assumeTrue
import org.junit.Test

class DashboardRecoveryTest {
    @Test fun `registration callback is ignored when activity started online`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = true)
        assertFalse(gate.onAvailable())
    }

    @Test fun `first available reloads when activity started offline`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = false)
        assertTrue(gate.onAvailable())
        assertFalse(gate.onAvailable())
    }

    @Test fun `real network loss makes the next available recover exactly once`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = true)
        gate.onLost()
        assertTrue(gate.onAvailable())
        assertFalse(gate.onAvailable())
    }

    @Test fun `startup connection failures back off without waiting for HA countdown`() {
        val policy = DashboardRetryPolicy()
        assertEquals(5_000L, policy.connectionFailureDelay(wasConnected = false))
        assertEquals(10_000L, policy.afterRetry())
        assertEquals(10_000L, policy.connectionFailureDelay(wasConnected = false))
        assertEquals(20_000L, policy.afterRetry())
        assertEquals(20_000L, policy.connectionFailureDelay(wasConnected = false))
    }

    @Test fun `established dashboard keeps long websocket reconnect grace`() {
        val policy = DashboardRetryPolicy()
        assertEquals(90_000L, policy.connectionFailureDelay(wasConnected = true))
    }

    @Test fun `a retry over the committed reconnecting page loads the dashboard afresh`() {
        val ha = "http://192.0.2.10:8123"
        // The committed document DevTools reported on the trapped panel (2026-09-25), with HA serving.
        assertTrue(retryNeedsFreshLoad("data:text/html;charset=utf-8;base64,", ha, interstitialShown = false, dashboardRenderer = true))
        assertTrue(retryNeedsFreshLoad("about:blank", ha, interstitialShown = false, dashboardRenderer = true))
        assertTrue(retryNeedsFreshLoad(null, ha, interstitialShown = false, dashboardRenderer = true))
        assertTrue(retryNeedsFreshLoad("$ha/lovelace/0?external_auth=1", ha, interstitialShown = true, dashboardRenderer = true))
    }

    @Test fun `a retry over a committed dashboard page reloads it as before`() {
        val ha = "http://192.0.2.10:8123"
        assertFalse(retryNeedsFreshLoad("$ha/lovelace/0?external_auth=1", ha, interstitialShown = false, dashboardRenderer = true))
        // The on-panel sign-in view has no dashboard target; it keeps reload().
        assertFalse(retryNeedsFreshLoad(null, ha, interstitialShown = false, dashboardRenderer = false))
    }

    @Test fun `a load that started but never drew does not make the retry reload the reconnecting page`() {
        val ha = "http://192.0.2.10:8123"
        val page = ShownPageTracker()
        page.onCommitVisible("data:text/html;charset=utf-8;base64,")
        // The retry's load of Home Assistant starts, then stalls before anything is drawn.
        page.onLoadStarted("$ha/lovelace/0?external_auth=1")
        assertTrue(page.needsFreshLoad(ha, interstitialShown = false, dashboardRenderer = true))
        // Home Assistant is back: the next plain retry still loads it afresh, and once drawn a retry reloads it.
        page.onCommitVisible("$ha/lovelace/0?external_auth=1")
        assertFalse(page.needsFreshLoad(ha, interstitialShown = false, dashboardRenderer = true))
    }

    @Test fun `showing the reconnecting page forgets the Home Assistant page that was drawn`() {
        val ha = "http://192.0.2.10:8123"
        val page = ShownPageTracker()
        page.onCommitVisible("$ha/lovelace/0?external_auth=1")
        page.forget()
        assertNull(page.shown)
        assertTrue(page.needsFreshLoad(ha, interstitialShown = false, dashboardRenderer = true))
    }

    /** Fire [count] awake misses and return the 1-based miss numbers that did something other than reload. */
    private fun escalationsWithin(policy: DashboardRetryPolicy, count: Int): List<Pair<Int, HandshakeRecoveryStep>> =
        (1..count).mapNotNull { miss ->
            val step = policy.onWatchdogFired(screenAwake = true)
            if (step == HandshakeRecoveryStep.RELOAD) null else miss to step
        }

    @Test fun `a single missed handshake reloads on the existing cadence`() {
        val policy = DashboardRetryPolicy()
        assertEquals(HandshakeRecoveryStep.RELOAD, policy.onWatchdogFired(screenAwake = true))
        assertEquals(1, policy.consecutiveMisses)
        assertNull(policy.escalatedTo)
        assertEquals(10_000L, policy.afterRetry())
        assertEquals(HandshakeRecoveryStep.RELOAD, policy.onWatchdogFired(screenAwake = true))
        assertNull(policy.escalatedTo)
    }

    @Test fun `consecutive misses escalate to a fresh load then WebView recreation with backoff`() {
        val policy = DashboardRetryPolicy()
        val escalations = escalationsWithin(policy, 300)
        assertEquals(
            listOf(
                3 to HandshakeRecoveryStep.FRESH_LOAD,
                6 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
                12 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
                24 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
                48 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
                96 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
                // Capped: never sparser than one recreation per 60 misses.
                156 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
                216 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
                276 to HandshakeRecoveryStep.RECREATE_WEBVIEW,
            ),
            escalations,
        )
        assertEquals(300, policy.consecutiveMisses)
        assertEquals(HandshakeRecoveryStep.RECREATE_WEBVIEW, policy.escalatedTo)
    }

    @Test fun `a completed handshake resets the miss count and the escalation ladder`() {
        val policy = DashboardRetryPolicy()
        escalationsWithin(policy, 7)
        assertEquals(HandshakeRecoveryStep.RECREATE_WEBVIEW, policy.escalatedTo)
        policy.reset()
        assertEquals(0, policy.consecutiveMisses)
        assertNull(policy.escalatedTo)
        assertEquals(listOf(3 to HandshakeRecoveryStep.FRESH_LOAD, 6 to HandshakeRecoveryStep.RECREATE_WEBVIEW), escalationsWithin(policy, 6))
    }

    @Test fun `a screen-off fire is not a miss and does not reset the count`() {
        val policy = DashboardRetryPolicy()
        escalationsWithin(policy, 2)
        repeat(50) { assertEquals(HandshakeRecoveryStep.NONE, policy.onWatchdogFired(screenAwake = false)) }
        assertEquals(2, policy.consecutiveMisses)
        assertNull(policy.escalatedTo)
        assertEquals(HandshakeRecoveryStep.FRESH_LOAD, policy.onWatchdogFired(screenAwake = true))
    }

    @Test fun `successful connection resets startup backoff`() {
        val policy = DashboardRetryPolicy()
        policy.afterRetry()
        policy.afterRetry()
        policy.reset()
        assertEquals(5_000L, policy.connectionFailureDelay(wasConnected = false))
    }

    @Test fun `learned network wait drives progress without claiming completion`() {
        assertEquals(0, networkWaitProgress(elapsedMs = 10_000L, estimateMs = 0L))
        assertEquals(500, networkWaitProgress(elapsedMs = 30_000L, estimateMs = 60_000L))
        assertEquals(950, networkWaitProgress(elapsedMs = 60_000L, estimateMs = 60_000L))
        assertEquals(950, networkWaitProgress(elapsedMs = 90_000L, estimateMs = 60_000L))
    }

    @Test fun `renderer generation rejects replaced and closed callbacks`() {
        val gate = RendererGenerationGate()
        val first = gate.open()
        assertTrue(gate.owns(first))

        val second = gate.open()
        assertFalse(gate.owns(first))
        assertTrue(gate.owns(second))

        gate.invalidate()
        assertFalse(gate.owns(second))
        val third = gate.open()
        gate.close()
        assertFalse(gate.owns(third))
        assertTrue(runCatching { gate.open() }.isFailure)
    }

    @Test fun `wake media recovery is generation checked and at most once per wake`() {
        val gate = WakeMediaRecoveryGate()
        val first = gate.begin(rendererGeneration = 4)
        assertEquals(first, gate.begin(rendererGeneration = 4))
        assertTrue(gate.owns(first))
        assertEquals(WakeMediaRecoveryAction.INSPECT, gate.onArmResult(first, candidates = 2))
        assertEquals(WakeMediaRecoveryAction.RELOAD, gate.onInspectResult(first, stalled = true))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onInspectResult(first, stalled = true))

        gate.invalidate()
        assertFalse(gate.owns(first))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onArmResult(first, candidates = 1))
        val second = gate.begin(rendererGeneration = 4)
        assertTrue(second.cycle > first.cycle)
        assertEquals(WakeMediaRecoveryAction.INSPECT, gate.onArmResult(second, candidates = 1))

        val replacement = gate.begin(rendererGeneration = 5)
        assertFalse(gate.owns(second))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onInspectResult(second, stalled = true))
        assertTrue(gate.owns(replacement))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onArmResult(replacement, candidates = 0))
        assertFalse(gate.owns(replacement))
        val healthy = gate.begin(rendererGeneration = 5)
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onInspectResult(healthy, stalled = false))
        assertFalse(gate.owns(healthy))
        gate.close()
        assertTrue(runCatching { gate.begin(6) }.isFailure)
    }

    @Test fun `disconnected wake is retained only for its renderer generation`() {
        val gate = WakeMediaRecoveryGate()
        val deferred = gate.defer(rendererGeneration = 7)

        assertTrue(gate.owns(deferred))
        assertEquals(null, gate.activateDeferred(rendererGeneration = 8))
        assertEquals(deferred, gate.activateDeferred(rendererGeneration = 7))
        assertEquals(null, gate.activateDeferred(rendererGeneration = 7))

        val stale = gate.defer(rendererGeneration = 7)
        val replacement = gate.defer(rendererGeneration = 8)
        assertFalse(gate.owns(stale))
        assertEquals(null, gate.activateDeferred(rendererGeneration = 7))
        assertEquals(replacement, gate.activateDeferred(rendererGeneration = 8))

        gate.defer(rendererGeneration = 8)
        gate.invalidate()
        assertEquals(null, gate.activateDeferred(rendererGeneration = 8))

        gate.defer(rendererGeneration = 9)
        gate.close()
        assertEquals(null, gate.activateDeferred(rendererGeneration = 9))
    }

    @Test fun `javascript integer results reject malformed callbacks`() {
        assertEquals(1, javascriptIntResult("1"))
        assertEquals(-1, javascriptIntResult("\"-1\""))
        assertEquals(null, javascriptIntResult("null"))
        assertEquals(null, javascriptIntResult(null))
    }

    @Test fun `exact wake media scripts classify and sample nested dashboard video`() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val arm = WakeMediaRecoveryScript.arm(7)
        val inspect = WakeMediaRecoveryScript.inspect(7)
        val harness =
            """
            global.window=globalThis;
            global.innerWidth=800;
            global.innerHeight=600;
            global.getComputedStyle=video=>video.style;
            const makeRoot=(videos=[],elements=[])=>({querySelectorAll(selector){return selector==='video'?videos:elements;}});
            const makeVideo=(values={})=>Object.assign({
              currentTime:0,webkitDecodedFrameCount:0,isConnected:true,ended:false,paused:false,autoplay:false,
              style:{display:'block',visibility:'visible',opacity:'1'},srcObject:null,playCalls:0,
              getBoundingClientRect(){return {width:320,height:180,top:0,left:0,bottom:180,right:320};},
              getVideoPlaybackQuality(){return {totalVideoFrames:this.webkitDecodedFrameCount};},
              play(){this.playCalls++;return {catch(){}};}
            },values);
            const arm=()=>($arm);
            const inspect=()=>($inspect);
            const expect=(actual,want,label)=>{if(actual!==want)throw Error(label+': got '+actual+', want '+want);};
            document=makeRoot();
            expect(arm(),0,'no video');
            const hidden=makeVideo({getBoundingClientRect(){return {width:0,height:0,top:0,left:0,bottom:0,right:0};}});
            document=makeRoot([hidden]);
            expect(arm(),0,'hidden video');
            const manualPause=makeVideo({paused:true});
            document=makeRoot([manualPause]);
            expect(arm(),0,'paused non-autoplay video');
            const ended=makeVideo({ended:true});
            document=makeRoot([ended]);
            expect(arm(),0,'ended video');
            const stalled=makeVideo();
            document=makeRoot([stalled]);
            expect(arm(),1,'visible stalled arm');
            expect(stalled.playCalls,1,'resume play attempt');
            expect(inspect(),1,'visible stalled inspect');
            const progressing=makeVideo();
            document=makeRoot([progressing]);
            expect(arm(),1,'progressing arm');
            progressing.currentTime=1;
            expect(inspect(),1,'time cannot mask flat supported frame counter');
            const timeOnly=makeVideo({webkitDecodedFrameCount:undefined,getVideoPlaybackQuality:null});
            document=makeRoot([timeOnly]);
            expect(arm(),1,'time-only arm');
            timeOnly.currentTime=1;
            expect(inspect(),0,'current time fallback without frame counters');
            const frameProgress=makeVideo();
            document=makeRoot([frameProgress]);
            expect(arm(),1,'frame arm');
            frameProgress.webkitDecodedFrameCount=1;
            expect(inspect(),0,'frame progress');
            const counterLost=makeVideo();
            document=makeRoot([counterLost]);
            expect(arm(),1,'counter-loss arm');
            counterLost.getVideoPlaybackQuality=null;
            counterLost.webkitDecodedFrameCount=undefined;
            counterLost.currentTime=1;
            expect(inspect(),-1,'lost supported counter is inconclusive, not time fallback');
            const reconnecting=makeVideo({paused:true,autoplay:true});
            document=makeRoot([reconnecting]);
            expect(arm(),1,'autoplay without initial track');
            reconnecting.currentTime=0.5;
            expect(inspect(),1,'late clock movement without decoded frames is stalled');
            const healthy=makeVideo();
            const otherStalled=makeVideo();
            document=makeRoot([healthy,otherStalled]);
            expect(arm(),2,'two videos');
            healthy.webkitDecodedFrameCount=1;
            expect(inspect(),1,'one healthy video cannot mask another stalled video');
            const healthyOne=makeVideo();
            const healthyTwo=makeVideo();
            document=makeRoot([healthyOne,healthyTwo]);
            expect(arm(),2,'two healthy videos arm');
            healthyOne.webkitDecodedFrameCount=1;
            healthyTwo.webkitDecodedFrameCount=1;
            expect(inspect(),0,'all visible videos progressing');
            const shadowVideo=makeVideo();
            const shadowRoot=makeRoot([shadowVideo]);
            document=makeRoot([], [{shadowRoot}]);
            expect(arm(),1,'open shadow root video');
            expect(inspect(),1,'shadow video stalled');
            const deepVideo=makeVideo();
            const deepRoot=makeRoot([deepVideo]);
            const middleRoot=makeRoot([], [{shadowRoot:deepRoot}]);
            document=makeRoot([], [{shadowRoot:middleRoot}]);
            expect(arm(),1,'recursive shadow root video');
            expect(inspect(),1,'recursive shadow video stalled');
            const replaced=makeVideo();
            document=makeRoot([replaced]);
            expect(arm(),1,'replace arm');
            replaced.isConnected=false;
            expect(inspect(),-1,'replaced node is inconclusive');
            const hiddenDuringSample=makeVideo();
            document=makeRoot([hiddenDuringSample]);
            expect(arm(),1,'hide arm');
            hiddenDuringSample.style.display='none';
            expect(inspect(),-1,'hidden during sample is inconclusive');
            const pausedDuringSample=makeVideo();
            document=makeRoot([pausedDuringSample]);
            expect(arm(),1,'pause arm');
            pausedDuringSample.paused=true;
            expect(inspect(),-1,'manual pause during sample is inconclusive');
            const liveTrack=makeVideo({paused:true,srcObject:{getVideoTracks(){return [{readyState:'live'}];}}});
            document=makeRoot([liveTrack]);
            expect(arm(),1,'live track candidate');
            expect(inspect(),1,'live track without progress');
            const audioOnly=makeVideo({paused:true,srcObject:{getVideoTracks(){return [];},getTracks(){return [{readyState:'live'}];}}});
            document=makeRoot([audioOnly]);
            expect(arm(),0,'audio-only stream is not video playback');
            if(window.__haPanelWakeMedia===undefined)throw Error('arm state missing before inspect');
            expect(inspect(),-1,'empty sample is inconclusive');
            if(window.__haPanelWakeMedia!==undefined)throw Error('inspect retained sampled nodes');
            """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test fun `dashboard navigation stays on the configured authority`() {
        assertTrue(dashboardNavigationAllowed("https://ha.example", "https://HA.EXAMPLE/lovelace/0"))
        assertTrue(dashboardNavigationAllowed("http://ha.example", "https://ha.example/lovelace/0"))
        assertTrue(dashboardNavigationAllowed("http://ha.example:8123", "https://ha.example:8123/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "http://ha.example/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example:8123", "http://ha.example:8123/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "https://ha.example:8443/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "file://ha.example/data/local/tmp/page"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "https://other.example/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("not a url", "https://ha.example/lovelace/0"))
    }

    @Test fun `physical panel oauth navigation admits only HA and local callback`() {
        assertTrue(panelHaOAuthNavigationAllowed("https://ha.example", "https://ha.example/auth/authorize?state=s"))
        assertTrue(panelHaOAuthNavigationAllowed("https://ha.example", "http://127.0.0.1:8888/api/v1/ha/oauth/callback?state=s&code=c"))
        assertTrue(panelHaOAuthNavigationAllowed("https://ha.example", "http://localhost:8888/api/v1/ha/oauth/callback?state=s&code=c"))
        assertFalse(panelHaOAuthNavigationAllowed("https://ha.example", "http://ha.example/lovelace/0"))
        assertFalse(panelHaOAuthNavigationAllowed("https://ha.example", "http://127.0.0.1:8888/configure"))
        assertFalse(panelHaOAuthNavigationAllowed("https://ha.example", "http://other.example:8888/api/v1/ha/oauth/callback?state=s"))
    }

    @Test fun `physical panel oauth start url targets local control surface`() {
        assertEquals(
            "http://127.0.0.1:8888/api/v1/ha/oauth/panel-start?ha_url=https%3A%2F%2Fha.example%3A8123",
            panelHaOAuthStartUrl("https://ha.example:8123/"),
        )
    }

    @Test fun `an incomplete admission check recovers on its own`() {
        // The panel never got an answer it can act on, and every one of these clears server-side with
        // nobody at the panel — so each must arm the fast ladder.
        listOf(
            AdmissionOutcome.TRANSPORT_FAILED,
            AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
            AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE,
            AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
            AdmissionOutcome.BRIDGE_ATTACH_FAILED,
        ).forEach { assertEquals(it.name, AdmissionRetryClass.FROM_BASE, admissionRetryClass(it)) }
    }

    @Test fun `a server-repairable answer nobody will announce is probed slowly`() {
        // Creating a dashboard, or a proxy that stops mangling the version, happens entirely
        // server-side and nothing tells the panel — so asking again slowly is the only way it finds
        // out. NO_LEGAL_DASHBOARD was latched and its resolution
        // cached, so restoring access left the panel blocked until somebody touched it.
        listOf(
            AdmissionOutcome.VERSION_UNVERIFIABLE,
            AdmissionOutcome.NO_LEGAL_DASHBOARD,
        ).forEach { assertEquals(it.name, AdmissionRetryClass.AT_CEILING, admissionRetryClass(it)) }
    }

    @Test fun `a failed bridge attachment on a capable WebView is retried, unlike a missing capability`() {
        // The capability check and the attachment attempt are different evidence: a provider update or
        // process death mid-session fails attachment on a WebView that genuinely supports the bridge,
        // and a fresh WebView can succeed. Only the absent capability is terminal.
        assertEquals(AdmissionRetryClass.FROM_BASE, admissionRetryClass(AdmissionOutcome.BRIDGE_ATTACH_FAILED))
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.BRIDGE_UNAVAILABLE))
    }

    @Test fun `resume owns countdown visibility only where the top-resumed callback does not exist`() {
        // onTopResumedActivityChanged arrives from API 29. Below that it is never delivered, so without
        // a resume-owned path the countdown would stay blank forever while its retry fired invisibly.
        assertTrue("API 26 has no top-resumed callback", resumeOwnsAdmissionVisibility(26))
        assertTrue("API 28 has no top-resumed callback", resumeOwnsAdmissionVisibility(28))
        assertFalse("API 29 delivers the precise signal", resumeOwnsAdmissionVisibility(29))
        assertFalse(resumeOwnsAdmissionVisibility(34))
    }

    @Test fun `an answer carried by an event or owned by a person never runs a timer`() {
        // An ABSENT credential is repaired by connecting the panel, which relaunches admission
        // immediately, and there is nothing to re-ask with meanwhile. A refused credential must not
        // be replayed unattended because HA can ban repeated login failures. The other two are
        // maintainer-designated terminal outcomes.
        listOf(
            AdmissionOutcome.SIGN_IN_REQUIRED,
            AdmissionOutcome.CREDENTIAL_REFUSED,
            AdmissionOutcome.UNSUPPORTED_HA,
            AdmissionOutcome.BRIDGE_UNAVAILABLE,
        ).forEach { assertEquals(it.name, AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(it)) }
    }

    @Test fun `a refused credential waits for an explicit retry instead of risking a login ban`() {
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.CREDENTIAL_REFUSED))
        assertNotEquals(AdmissionRetryClass.FROM_BASE, admissionRetryClass(AdmissionOutcome.CREDENTIAL_REFUSED))
    }

    @Test fun `a missed handshake and an absent credential are classified oppositely`() {
        // The two boundaries that were previously inverted, pinned against each other: a capable
        // WebView that simply missed the exchange recovers, while a panel holding no credential does
        // not, because there is nothing to re-ask with.
        assertEquals(AdmissionRetryClass.FROM_BASE, admissionRetryClass(AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED))
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.SIGN_IN_REQUIRED))
        // ...and a missed handshake is not the same evidence as a WebView that cannot bridge at all.
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.BRIDGE_UNAVAILABLE))
        // No blocked outcome may be left without any route back: the explicit retry action owns
        // credential refusal, while the other outcomes below need configuration or platform repair.
        val latched = AdmissionOutcome.entries.filter { admissionRetryClass(it) == AdmissionRetryClass.MANUAL_ONLY }
        assertEquals(
            setOf(
                AdmissionOutcome.SIGN_IN_REQUIRED,
                AdmissionOutcome.CREDENTIAL_REFUSED,
                AdmissionOutcome.UNSUPPORTED_HA,
                AdmissionOutcome.BRIDGE_UNAVAILABLE,
            ),
            latched.toSet(),
        )
    }

    @Test fun `an unusable answer is retried slowly, not on the fast ladder`() {
        assertEquals(AdmissionRetryClass.AT_CEILING, admissionRetryClass(AdmissionOutcome.VERSION_UNVERIFIABLE))
    }

    @Test fun `every admission outcome is classified deliberately`() {
        // A new outcome must be given a class here rather than inheriting one, so the exhaustive set
        // is asserted by size and by every value resolving.
        assertEquals(11, AdmissionOutcome.entries.size)
        AdmissionOutcome.entries.forEach { assertNotNull(it.name, admissionRetryClass(it)) }
    }

    // --- a WebView provider install as the one event that can clear a terminal bridge screen ---

    private companion object {
        const val ADDED = "android.intent.action.PACKAGE_ADDED"
        const val REPLACED = "android.intent.action.PACKAGE_REPLACED"
        val PROVIDER = WebViewProviderIdentity("com.android.webview", 4110, "41.1.0")
    }

    /** Counts provider lookups so a decision can be proved to have declined BEFORE querying one. */
    private class Resolver(private val identity: WebViewProviderIdentity?) : () -> WebViewProviderIdentity? {
        var calls = 0
        override fun invoke(): WebViewProviderIdentity? {
            calls++
            return identity
        }
    }

    private fun decide(
        action: String? = REPLACED,
        changed: String? = PROVIDER.packageName,
        replacing: Boolean = false,
        blocked: AdmissionOutcome? = AdmissionOutcome.BRIDGE_UNAVAILABLE,
        resolver: Resolver = Resolver(PROVIDER),
    ): WebViewRebindDecision = webViewRebindDecision(action, changed, replacing, blocked, resolver)

    @Test fun `the actions observed are exactly the platform's package install broadcasts`() {
        // Compile-time constants, so a JVM test can hold the framework to its own spelling. A silent
        // divergence here would register a filter that never matches and fail as permanent silence.
        assertEquals(android.content.Intent.ACTION_PACKAGE_ADDED, ADDED)
        assertEquals(android.content.Intent.ACTION_PACKAGE_REPLACED, REPLACED)
    }

    @Test fun `replacing the panel's WebView provider rebinds it`() {
        assertEquals(WebViewRebindDecision.REBIND, decide())
    }

    @Test fun `a provider arriving for the first time rebinds it too`() {
        // The added-package route is not decoration: a provider that never loaded leaves nothing bound,
        // so the panel resolves whichever package the system has just adopted.
        assertEquals(WebViewRebindDecision.REBIND, decide(action = ADDED))
    }

    @Test fun `a repair that reinstalls the same build still rebinds`() {
        // The screen tells the person to update OR repair. A same-version reinstall is the repair, and a
        // rule keyed on a version difference would sit out the remedy it just recommended.
        val resolver = Resolver(PROVIDER.copy())
        assertEquals(WebViewRebindDecision.REBIND, decide(resolver = resolver))
    }

    @Test fun `a stale pinned version does not hide a replacement of the same package`() {
        // Once an engine is bound, the resolvable version keeps naming the build that was bound, however
        // many times the APK is replaced underneath. Keying on the package name is what survives that.
        val stale = Resolver(PROVIDER.copy(versionCode = 1, versionName = "1.0.0"))
        assertEquals(WebViewRebindDecision.REBIND, decide(resolver = stale))
    }

    @Test fun `an unrelated app updating is ignored without even asking who the provider is`() {
        val resolver = Resolver(PROVIDER)
        assertEquals(WebViewRebindDecision.OTHER_PACKAGE, decide(changed = "com.example.thermostat", resolver = resolver))
        // It had to ask once to know the package was unrelated...
        assertEquals(1, resolver.calls)

        // ...but a panel that is not blocked never pays for the lookup at all.
        val quiet = Resolver(PROVIDER)
        assertEquals(WebViewRebindDecision.NOT_BLOCKED, decide(changed = "com.example.thermostat", blocked = null, resolver = quiet))
        assertEquals(0, quiet.calls)
    }

    @Test fun `a package name that never arrived is not treated as the provider`() {
        assertEquals(WebViewRebindDecision.OTHER_PACKAGE, decide(changed = null))
        assertEquals(WebViewRebindDecision.OTHER_PACKAGE, decide(changed = "  "))
        // The case the emptiness guard actually defends, which plain inequality would wave through:
        // a degenerate resolution answering with a blank package would otherwise MATCH a broadcast
        // that named nothing, and restart the panel over an install it never identified.
        assertEquals(
            WebViewRebindDecision.OTHER_PACKAGE,
            decide(changed = "", resolver = Resolver(WebViewProviderIdentity("", 0L, null))),
        )
    }

    @Test fun `the added half of a replace is dropped, so one install decides once`() {
        // Android announces a replace as an add carrying EXTRA_REPLACING as well as its own broadcast.
        assertEquals(WebViewRebindDecision.DUPLICATE_INSTALL, decide(action = ADDED, replacing = true))
        // The replaced broadcast is the one that is sent exactly once, and it still decides.
        assertEquals(WebViewRebindDecision.REBIND, decide(action = REPLACED, replacing = true))
    }

    @Test fun `broadcasts this rule does not observe decide nothing`() {
        assertEquals(WebViewRebindDecision.UNRELATED_ACTION, decide(action = "android.intent.action.PACKAGE_REMOVED"))
        assertEquals(WebViewRebindDecision.UNRELATED_ACTION, decide(action = null))
    }

    @Test fun `a dashboard that is not parked on a provider verdict is left alone`() {
        // No renderer generation is blocked at all — a healthy panel, or one whose activity was destroyed
        // or replaced, which is the same answer because the runtime stops reporting a retired generation.
        assertEquals(WebViewRebindDecision.NOT_BLOCKED, decide(blocked = null))

        // Blocked, but on verdicts a new engine cannot repair. These keep the recovery they already have.
        listOf(
            AdmissionOutcome.TRANSPORT_FAILED,
            AdmissionOutcome.UNSUPPORTED_HA,
            AdmissionOutcome.CREDENTIAL_REFUSED,
            AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
            AdmissionOutcome.BRIDGE_ATTACH_FAILED,
        ).forEach {
            assertEquals(it.name, WebViewRebindDecision.NOT_BLOCKED, decide(blocked = it))
        }
    }

    @Test fun `an install with no resolvable provider has nothing to rebind to`() {
        assertEquals(WebViewRebindDecision.NO_PROVIDER, decide(resolver = Resolver(null)))
    }

    @Test fun `a replacement that is still incapable decides again only on the next real install`() {
        // The rebind happens, the panel comes back on a fresh engine, and the engine is STILL incapable —
        // so the same screen returns. Nothing here re-decides on its own: the identical inputs are only
        // re-examined because another install event arrived, which is the whole point of an event trigger.
        assertEquals(WebViewRebindDecision.REBIND, decide())
        assertEquals(WebViewRebindDecision.REBIND, decide())
        // Between those installs the panel asks nothing at all: with no broadcast there is no decision,
        // which is what "no polling" means here rather than a cadence set to a long interval.
        assertEquals(WebViewRebindDecision.UNRELATED_ACTION, decide(action = null))
    }

    @Test fun `only a missing WebView capability is treated as provider-repairable`() {
        // Exhaustive by size, like the retry classification above: a new outcome must answer here rather
        // than inherit `false`, because inheriting silently re-makes the terminal-screen decision.
        assertEquals(11, AdmissionOutcome.entries.size)
        AdmissionOutcome.entries.forEach {
            assertEquals(it.name, it == AdmissionOutcome.BRIDGE_UNAVAILABLE, providerRepairableAdmission(it))
        }
        // And it is precisely the verdict that no timer will ever re-ask, which is why it needs an event.
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.BRIDGE_UNAVAILABLE))
    }

    @Test fun `a provider description carries the build without carrying a path or a host`() {
        assertEquals("com.android.webview 41.1.0 (4110)", PROVIDER.describe())
        assertEquals("com.android.webview ? (4110)", PROVIDER.copy(versionName = null).describe())
    }

    // --- the pending rebind, driven across the wait rather than asserted from source ---

    /** Hand-driven scheduler: nothing runs until the test fires it, so the interleaving is exact. */
    private class Pending {
        val queue = mutableListOf<Pair<Long, () -> Unit>>()
        fun accept(delayMs: Long, action: () -> Unit): Boolean { queue += delayMs to action; return true }
        fun fire(): Long { val (delay, action) = queue.removeAt(0); action(); return delay }
        val idle: Boolean get() = queue.isEmpty()
    }

    @Test fun `a rebind deferred by a busy panel is still taken when the panel settles`() {
        // THE REGRESSION. The rule acts on ONE broadcast and no second one is coming, so a wait must
        // never be spelled as an abandon: the deadline lands mid-install, the panel stays busy across
        // several rounds — long enough for Android to replace the dashboard activity and for the
        // renderer's verdict to be invisible throughout — and the restart must still happen afterwards.
        // The revision this replaces discarded the request at the first deadline in exactly that state,
        // losing the panel's only route back permanently.
        val pending = Pending()
        var installing = true
        var walking = false
        var restarts = 0
        val coordinator = webViewRebindRestartCoordinator(
            schedule = pending::accept,
            restartProcess = { restarts++ },
            destructiveOperationRunning = { installing },
            guidedSetupBeingWalked = { walking },
            serviceStopping = { false },
        )

        assertTrue(coordinator.request())

        // Every wait must leave the request alive AND re-armed. Asserting survival explicitly is what
        // makes a discarded request fail as an assertion rather than as an empty-queue crash later.
        fun waitOneRound(expectedDelayMs: Long, note: String) {
            assertEquals(note, expectedDelayMs, pending.fire())
            assertEquals("the boundary must not be taken yet: $note", 0, restarts)
            assertFalse("the pending rebind must survive: $note", pending.idle)
        }

        waitOneRound(ProfileRestartCoordinator.RESPONSE_GRACE_MS, "the deadline lands mid-install")
        // Round after round of ordinary panel busyness — long enough for Android to have replaced the
        // dashboard activity, which is exactly when the renderer's verdict is invisible.
        repeat(4) { round -> waitOneRound(ProfileRestartCoordinator.BUSY_RETRY_MS, "install running, round $round") }

        // Guided setup takes over from the install; still a wait, still not a discard.
        installing = false
        walking = true
        waitOneRound(ProfileRestartCoordinator.BUSY_RETRY_MS, "guided setup took over")

        walking = false
        pending.fire()
        assertEquals("the panel settled, so the boundary is finally taken", 1, restarts)
        assertTrue(pending.idle)
    }

    @Test fun `only the service going away discards a pending rebind`() {
        val pending = Pending()
        var stopping = false
        var restarts = 0
        fun build() = webViewRebindRestartCoordinator(
            schedule = pending::accept,
            restartProcess = { restarts++ },
            destructiveOperationRunning = { false },
            guidedSetupBeingWalked = { false },
            serviceStopping = { stopping },
        )

        val coordinator = build()
        assertTrue(coordinator.request())
        stopping = true
        pending.fire()
        assertEquals("a process that is already leaving does not need a boundary", 0, restarts)

        // And the admission is released rather than burnt, so a later healthy owner may ask again.
        stopping = false
        assertTrue(coordinator.request())
        pending.fire()
        assertEquals(1, restarts)
    }

    @Test fun `one provider install asks for exactly one boundary`() {
        val pending = Pending()
        var restarts = 0
        val coordinator = webViewRebindRestartCoordinator(
            schedule = pending::accept,
            restartProcess = { restarts++ },
            destructiveOperationRunning = { false },
            guidedSetupBeingWalked = { false },
            serviceStopping = { false },
        )

        assertTrue(coordinator.request())
        assertFalse("a duplicate broadcast cannot queue a second restart", coordinator.request())
        pending.fire()
        assertEquals(1, restarts)
    }

    // --- the visible countdown, driven as a lifecycle rather than asserted from source ---

    private class Clock(var now: Long = 0L) : () -> Long { override fun invoke() = now }

    @Test fun `an armed countdown paints and reschedules only while it is visible`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)

        // Armed while nothing is visible yet: no repaint, nothing scheduled — but it IS armed.
        val armed = owner.arm(30_000L)
        assertTrue(owner.armed)
        assertNull(armed.text)
        assertNull(armed.scheduleNextTickMs)

        val shown = owner.onVisibilityChanged(true)
        assertEquals("Retrying automatically in 30s", shown.text)
        assertEquals(1_000L, shown.scheduleNextTickMs)

        clock.now += 10_000L
        val tick = owner.onTick()
        assertEquals("Retrying automatically in 20s", tick.text)
        assertEquals(1_000L, tick.scheduleNextTickMs)
    }

    @Test fun `losing top visibility stops the repaint without disarming the retry`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(60_000L)
        owner.onVisibilityChanged(true)

        val hidden = owner.onVisibilityChanged(false)
        assertNull("a hidden countdown must not repaint", hidden.text)
        assertNull("a hidden countdown must not reschedule per-second work", hidden.scheduleNextTickMs)
        assertTrue("the retry it describes must survive being hidden", owner.armed)
        // Ticks that were already in flight when visibility was lost do nothing either.
        clock.now += 5_000L
        assertNull(owner.onTick().text)
        assertNull(owner.onTick().scheduleNextTickMs)
        assertTrue(owner.armed)
    }

    @Test fun `returning to visibility reconciles to the true remaining time immediately`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(120_000L)
        owner.onVisibilityChanged(true)
        owner.onVisibilityChanged(false)

        clock.now += 45_000L                       // hidden for 45s
        val back = owner.onVisibilityChanged(true)
        assertEquals("Retrying automatically in 1m 15s", back.text)
        assertEquals(1_000L, back.scheduleNextTickMs)
    }

    @Test fun `an elapsed countdown paints its final figure and stops rescheduling`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(5_000L)
        owner.onVisibilityChanged(true)

        clock.now += 5_000L
        val done = owner.onTick()
        assertEquals("Retrying automatically in 0s", done.text)
        assertNull("nothing more to count", done.scheduleNextTickMs)
    }

    @Test fun `a disarmed countdown never paints even while visible`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(30_000L)
        owner.onVisibilityChanged(true)
        owner.disarm()

        assertFalse(owner.armed)
        assertNull(owner.onTick().text)
        assertNull(owner.onVisibilityChanged(true).text)
    }

    @Test fun `a redraw carries the deadline itself, so redraw time cannot move it`() {
        val clock = Clock(now = 100_000L)
        val owner = AdmissionCountdownOwner(clock)
        assertNull("nothing pending means nothing to carry", owner.deadlineAtMs)

        owner.arm(30_000L)
        assertEquals(130_000L, owner.deadlineAtMs)

        // Exactly what a redraw does, and the reason this carries an INSTANT: the read happens before
        // the installer disarms, the re-arm after it has built and installed a new view tree, and real
        // time passes in between. Carrying a remaining DURATION would re-add that 900ms here, and
        // again on the next repaint, and again — walking recovery away from a panel being rotated.
        val carried = owner.deadlineAtMs!!
        owner.disarm()
        assertNull(owner.deadlineAtMs)
        clock.now += 900L
        owner.rearmAt(carried)

        assertEquals("the redraw moved the deadline", 130_000L, owner.deadlineAtMs)
        owner.onVisibilityChanged(true)
        assertEquals("Retrying automatically in 30s", owner.onTick().text)   // 29.1s, ceiled

        // Repeating the cycle must stay put rather than accumulate.
        repeat(10) {
            val d = owner.deadlineAtMs!!
            owner.disarm()
            clock.now += 900L
            owner.rearmAt(d)
        }
        assertEquals("ten redraws drifted the deadline", 130_000L, owner.deadlineAtMs)

        // A deadline that expired mid-redraw is still restored as itself; the caller derives a
        // non-negative delay from it rather than posting into the past.
        clock.now = 200_000L
        assertEquals(130_000L, owner.deadlineAtMs)
        assertTrue("an expired deadline is in the past", owner.deadlineAtMs!! < clock.now)
    }

    @Test fun `admission retries back off from the base and stop growing at the ceiling`() {
        val policy = AdmissionRetryPolicy(jitterSource = { 0.5 })   // 0.5 → zero jitter offset
        val ladder = generateSequence { policy.nextDelayMs(AdmissionRetryClass.FROM_BASE) }.take(8).toList()
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), ladder)
    }

    @Test fun `jitter spreads the armed delay symmetrically and is bounded`() {
        val low = AdmissionRetryPolicy(jitterSource = { 0.0 }).nextDelayMs(AdmissionRetryClass.AT_CEILING)
        val high = AdmissionRetryPolicy(jitterSource = { 1.0 }).nextDelayMs(AdmissionRetryClass.AT_CEILING)
        assertEquals(240_000L, low)      // 300s − 20%
        assertEquals(360_000L, high)     // 300s + 20%
        // The jittered floor keeps a pathological small base from arming a sub-second hammer.
        assertEquals(1_000L, AdmissionRetryPolicy(baseMs = 1_000L, jitterSource = { 0.0 }).nextDelayMs(AdmissionRetryClass.FROM_BASE))
    }

    @Test fun `ceiling-cadence and manual-only arms never advance the transport ladder`() {
        val policy = AdmissionRetryPolicy(jitterSource = { 0.5 })
        assertEquals(300_000L, policy.nextDelayMs(AdmissionRetryClass.AT_CEILING))
        assertEquals(null, policy.nextDelayMs(AdmissionRetryClass.MANUAL_ONLY))
        assertEquals("a ceiling probe must not inflate the next transport retry", 5_000L, policy.nextDelayMs(AdmissionRetryClass.FROM_BASE))
    }

    @Test fun `manual retry resets the admission backoff`() {
        val policy = AdmissionRetryPolicy(jitterSource = { 0.5 })
        policy.nextDelayMs(AdmissionRetryClass.FROM_BASE)
        policy.nextDelayMs(AdmissionRetryClass.FROM_BASE)
        policy.reset()
        assertEquals(5_000L, policy.nextDelayMs(AdmissionRetryClass.FROM_BASE))
    }

    @Test fun `admission countdown is a ceiled real number, never zero while pending`() {
        assertEquals("Retrying automatically in 47s", admissionRetryCountdown(47_000L))
        assertEquals("Retrying automatically in 1s", admissionRetryCountdown(1L))
        assertEquals("Retrying automatically in 0s", admissionRetryCountdown(0L))
        assertEquals("Retrying automatically in 5m 0s", admissionRetryCountdown(300_000L))
        assertEquals("Retrying automatically in 4m 1s", admissionRetryCountdown(240_001L))
    }

    @Test fun `document start origins mirror allowed scheme upgrades without broadening authority`() {
        assertEquals(setOf("https://ha.example"), dashboardDocumentStartOrigins("https://HA.EXAMPLE/lovelace"))
        assertEquals(
            linkedSetOf("http://ha.example", "https://ha.example"),
            dashboardDocumentStartOrigins("http://HA.EXAMPLE/lovelace"),
        )
        assertEquals(
            linkedSetOf("http://ha.example:8123", "https://ha.example:8123"),
            dashboardDocumentStartOrigins("http://ha.example:8123/lovelace"),
        )
        assertEquals(
            linkedSetOf("http://ha.example", "https://ha.example:80"),
            dashboardDocumentStartOrigins("http://ha.example:80/lovelace"),
        )
        assertEquals(
            setOf("https://ha.example:8443"),
            dashboardDocumentStartOrigins("https://ha.example:8443/lovelace"),
        )
    }
}
