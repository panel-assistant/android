package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import io.github.maxlyth.hapaneld.persistence.StateQuiescence
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantUpdatePolicy
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import io.github.maxlyth.hapaneld.dashboard.DatabaseCompatibilityDecision
import io.github.maxlyth.hapaneld.dashboard.DatabaseCompatibilityRefusal
import io.github.maxlyth.hapaneld.dashboard.RecoveryDatabaseKind
import io.github.maxlyth.hapaneld.dashboard.RecoveryDatabaseObservation

/**
 * [AppInstaller.httpsRedirect] — the download redirect gate: only ever follow a hop to an HTTPS
 * target, and resolve relative `Location` headers against the current URL. `java.net.URL` is a pure
 * JVM class, so this needs no device.
 */
class AppInstallerTest {
    private val boundary = DatabaseCompatibilityApkContract.Boundary(1, "ha-paneld.db", 11, 14)

    private fun selfInfo(
        signer: String? = AppInstaller.HA_PANELD.certSha256,
        contract: DatabaseCompatibilityApkContract.Parsed =
            DatabaseCompatibilityApkContract.Parsed.Valid(boundary),
    ) = AppInstaller.ApkInfo(
        pkg = AppInstaller.HA_PANELD.pkg,
        version = "candidate",
        signerSha256 = signer,
        databaseCompatibility = contract,
    )

    @Test fun panelAppLocalInstallWaitsForLivePanelAssistantPolicy() {
        val refusal = AppInstaller.localInstallCandidateRefusal(
            info = selfInfo(),
            runningPackage = AppInstaller.HA_PANELD.pkg,
            admittedBoundary = null,
        ) { null }
        assertEquals("Panel Assistant must be online with update policy", refusal)
    }

    @Test fun signedPanelCandidatesShareChannelRangeAndVersionAdmission() {
        val stable = PanelAssistantUpdatePolicy(1, 3, false)
        val prerelease = stable.copy(prerelease = true)
        fun refusal(version: String = "1.1.0", range: IntRange? = 3..3,
                    policy: PanelAssistantUpdatePolicy? = stable, code: Long = 110) =
            AppInstaller.localInstallCandidateRefusal(
                selfInfo().copy(version = version, versionCode = code, nativeProtocolRange = range),
                AppInstaller.HA_PANELD.pkg, null, policy, "1.0.0", 100,
            ) { null }
        assertNull(refusal())
        assertNull(refusal("1.1.0-rc1", policy = prerelease))
        assertNull(refusal("1.1.0-rc3.dev.1", policy = prerelease))
        assertEquals("candidate version ordering could not be proven", refusal("1.1.0-rc3..1", policy = prerelease))
        assertEquals("candidate version ordering could not be proven", refusal("1.1.0-01", policy = prerelease))
        assertNull(refusal("1.1.0", policy = prerelease))
        assertEquals("Panel Assistant must be online with update policy", refusal(policy = null))
        assertEquals("candidate Panel Assistant protocol metadata is missing or malformed", refusal(range = null))
        assertEquals("candidate cannot speak to the running Panel Assistant", refusal(range = 4..5))
        assertEquals("Panel Assistant update policy permits stable builds only", refusal("1.1.0-rc1"))
        assertEquals("panel-app downgrades are not permitted", refusal("0.9.9"))
        assertEquals("panel-app downgrades are not permitted", refusal(code = 99))
        assertEquals("candidate version ordering could not be proven", refusal("unknown"))
    }

    @Test fun localPanelCandidatePolicyAppliesToBothIdentitiesAndLeavesOtherAppsAlone() {
        val otherPanel = selfInfo().copy(pkg = io.github.maxlyth.hapaneld.AppIdentity.COUNTERPART)
        assertEquals("candidate must have exactly the pinned panel-app signer",
            AppInstaller.localInstallCandidateRefusal(otherPanel.copy(signerSha256s = setOf("untrusted")),
                AppInstaller.HA_PANELD.pkg, null) { null })
        assertEquals("Panel Assistant must be online with update policy",
            AppInstaller.localInstallCandidateRefusal(otherPanel, AppInstaller.HA_PANELD.pkg, null) { null })
        val companion = otherPanel.copy(pkg = AppInstaller.COMPANION_MINIMAL.pkg)
        assertNull(AppInstaller.localInstallCandidateRefusal(companion, AppInstaller.HA_PANELD.pkg, null) { null })
    }

    @Test fun panelVersionOrderingMatchesPanelAssistantForGenericPrereleaseIdentifiers() {
        assertTrue(AppInstaller.comparePanelVersions("1.1.0-rc10", "1.1.0-rc9")!! > 0)
        assertTrue(AppInstaller.comparePanelVersions("1.1.0", "1.1.0-rc10.dev.2")!! > 0)
        assertTrue(AppInstaller.comparePanelVersions("1.1.0-beta.2", "1.1.0-beta.10")!! < 0)
        assertNull(AppInstaller.comparePanelVersions("1.1.0-01", "1.0.0"))
        assertNull(AppInstaller.comparePanelVersions("1.1.0+build", "1.0.0"))
    }

    @Test fun malformedProtocolMetadataNeverManufacturesCompatibility() {
        assertEquals(1..3, AppInstaller.parseNativeProtocolMetadata("hapaneld-native:v1:1:3"))
        assertEquals(3..Int.MAX_VALUE, AppInstaller.parseNativeProtocolMetadata("hapaneld-native:v1:3:2147483647"))
        listOf(null, 3, "hapaneld-native:v1:0:3", "hapaneld-native:v1:4:3",
            "hapaneld-native:v1:3:2147483648", "hapaneld-native:v2:1:3", "hapaneld-native:v1:01:3")
            .forEach { assertNull("$it", AppInstaller.parseNativeProtocolMetadata(it)) }
    }

    @Test fun selfCandidateSignerIsAuthenticatedBeforeDatabaseMetadataIsTrusted() {
        var compatibilityConsulted = false

        val refusal = AppInstaller.selfReplacementRefusal(selfInfo(signer = "untrusted")) {
            compatibilityConsulted = true
            null
        }

        assertEquals("candidate must have exactly the pinned running-package signer", refusal)
        assertFalse("untrusted metadata must not reach the compatibility authority", compatibilityConsulted)
    }

    @Test fun selfCandidateWithPinnedAndExtraSignerRefusesBeforeCompatibilityAuthority() {
        var compatibilityConsulted = false
        val info = AppInstaller.ApkInfo(
            pkg = AppInstaller.HA_PANELD.pkg,
            version = "candidate",
            signerSha256 = AppInstaller.HA_PANELD.certSha256,
            databaseCompatibility = DatabaseCompatibilityApkContract.Parsed.Valid(boundary),
            signerSha256s = setOf(AppInstaller.HA_PANELD.certSha256, "unexpected-extra-signer"),
        )

        val refusal = AppInstaller.selfReplacementRefusal(info) {
            compatibilityConsulted = true
            null
        }

        assertEquals("candidate must have exactly the pinned running-package signer", refusal)
        assertFalse(compatibilityConsulted)
    }

    @Test fun missingOrMalformedCandidateBoundaryRefusesBeforeCompatibilityDecision() {
        listOf(
            DatabaseCompatibilityApkContract.Parsed.Missing,
            DatabaseCompatibilityApkContract.Parsed.Malformed("bad contract"),
        ).forEach { contract ->
            var compatibilityConsulted = false
            val refusal = AppInstaller.selfReplacementRefusal(selfInfo(contract = contract)) {
                compatibilityConsulted = true
                null
            }
            assertTrue(refusal?.isNotBlank() == true)
            assertFalse(compatibilityConsulted)
        }
    }

    @Test fun authenticatedExactBoundaryReachesCompatibilityAuthorityOnce() {
        val seen = mutableListOf<DatabaseCompatibilityApkContract.Boundary>()

        val refusal = AppInstaller.selfReplacementRefusal(selfInfo()) {
            seen += it
            "primary database is unreadable"
        }

        assertEquals(listOf(boundary), seen)
        assertEquals("primary database is unreadable", refusal)
    }

    @Test fun preparedAdmissionReobservesChangedDatabaseBeforeFirstMutation() {
        val events = mutableListOf<String>()
        val initial = AppInstaller.selfReplacementRefusal(selfInfo()) {
            events += "prepare-allow"
            null
        }
        assertNull(initial)

        val final = AppInstaller.preparedSelfReplacementRefusal(selfInfo(), boundary) {
            events += "install-reobserve-refuse"
            "database compatibility primary unreadable"
        }
        if (final == null) {
            events += "snapshot"
            events += "quiesce"
            events += "package-install"
        }

        assertEquals("database compatibility primary unreadable", final)
        assertEquals(listOf("prepare-allow", "install-reobserve-refuse"), events)
    }

    @Test fun preparedFinalUnreadableOrWrongPackageCannotFallThroughAsNonSelf() {
        val wrongPackage = AppInstaller.ApkInfo(
            pkg = "example.not.the.running.package",
            version = "candidate",
            signerSha256 = AppInstaller.HA_PANELD.certSha256,
            databaseCompatibility = DatabaseCompatibilityApkContract.Parsed.Valid(boundary),
        )
        var compatibilityConsulted = false

        val unreadable = AppInstaller.localInstallCandidateRefusal(
            info = null,
            runningPackage = AppInstaller.HA_PANELD.pkg,
            admittedBoundary = boundary,
        ) {
            compatibilityConsulted = true
            null
        }
        val wrong = AppInstaller.localInstallCandidateRefusal(
            info = wrongPackage,
            runningPackage = AppInstaller.HA_PANELD.pkg,
            admittedBoundary = boundary,
        ) {
            compatibilityConsulted = true
            null
        }

        assertEquals("unreadable candidate APK", unreadable)
        assertEquals("prepared candidate is not the running package", wrong)
        assertFalse("neither invalid identity may reach database admission", compatibilityConsulted)
    }

    @Test fun unpreparedLocalInstallRequiresReadableApkButStillAllowsReadableNonSelfPackage() {
        var compatibilityConsulted = false
        val unreadable = AppInstaller.localInstallCandidateRefusal(
            info = null,
            runningPackage = AppInstaller.HA_PANELD.pkg,
            admittedBoundary = null,
        ) {
            compatibilityConsulted = true
            null
        }
        val readableNonSelf = AppInstaller.localInstallCandidateRefusal(
            info = AppInstaller.ApkInfo(
                pkg = "example.curated.package",
                version = "candidate",
                signerSha256 = null,
                databaseCompatibility = DatabaseCompatibilityApkContract.Parsed.Missing,
            ),
            runningPackage = AppInstaller.HA_PANELD.pkg,
            admittedBoundary = null,
        ) {
            compatibilityConsulted = true
            null
        }

        assertEquals("unreadable candidate APK", unreadable)
        assertNull("a readable non-self upload retains its existing confirmation policy", readableNonSelf)
        assertFalse("non-self packages have no ha-paneld database contract to consult", compatibilityConsulted)
    }

    @Test fun onlyDirectOrExactValidatedRecoveryDecisionsAdmitSelfReplacement() {
        assertNull(AppInstaller.compatibilityDecisionRefusal(DatabaseCompatibilityDecision.Direct(14)))
        val recoveryFile = File.createTempFile("database-recovery-", ".premigrate").also { it.deleteOnExit() }
        assertNull(
            AppInstaller.compatibilityDecisionRefusal(
                DatabaseCompatibilityDecision.Recover(
                    RecoveryDatabaseObservation(
                        file = recoveryFile,
                        kind = RecoveryDatabaseKind.PREMIGRATE,
                        namedSchema = 14,
                        actualSchema = 14,
                        integrityValid = true,
                        regularFile = true,
                    ),
                ),
            ),
        )
        assertEquals(
            "installed package database is not proven present",
            AppInstaller.compatibilityDecisionRefusal(DatabaseCompatibilityDecision.Fresh),
        )
        assertEquals(
            "database compatibility primary unreadable",
            AppInstaller.compatibilityDecisionRefusal(
                DatabaseCompatibilityDecision.Refuse(DatabaseCompatibilityRefusal.PRIMARY_UNREADABLE),
            ),
        )
        assertEquals(
            "database compatibility primary missing not proven fresh",
            AppInstaller.compatibilityDecisionRefusal(
                DatabaseCompatibilityDecision.Refuse(
                    DatabaseCompatibilityRefusal.PRIMARY_MISSING_NOT_PROVEN_FRESH,
                ),
            ),
        )
    }

    @Test fun preparedCapabilityIsBoundToExactBytesAndDeletesWhenDiscarded() {
        val apk = File.createTempFile("prepared-self-install-", ".apk").apply { writeText("original") }
        val prepared = AppInstaller.PreparedSelfInstall(
            apk = apk,
            expectedSha256 = AppInstaller.sha256(apk),
            version = "candidate",
            boundary = boundary,
            allowShizuku = true,
        )

        assertTrue(prepared.bytesUnchanged())
        apk.writeText("changed")
        assertFalse(prepared.bytesUnchanged())
        prepared.close()
        assertFalse(apk.exists())
        assertNull(prepared.consume())
    }

    @Test fun successfulSelfInstallKeepsStateQuiescedWhileFailedInstallReopensIt() {
        val successReopened = AtomicBoolean()
        val successful = StateQuiescence { successReopened.set(true) }
        AppInstaller.finishSelfReplaceQuiescence(successful, installSucceeded = true)
        assertFalse(successReopened.get())

        val failureReopened = AtomicBoolean()
        val failed = StateQuiescence { failureReopened.set(true) }
        AppInstaller.finishSelfReplaceQuiescence(failed, installSucceeded = false)
        assertTrue(failureReopened.get())
    }

    @Test fun anUnwritableConfigRevisionWarnsAndTheUpgradeContinues() {
        // The private revision is defense in depth, not a precondition — losing it must not strand
        // the panel on the old build, so quiescence still runs and its lease is still returned.
        val warnings = mutableListOf<String>()
        val lease = StateQuiescence { }

        val prepared = AppInstaller.prepareSelfReplace(
            snapshot = { false },
            quiesce = { lease },
            warn = warnings::add,
        )

        assertSame("the upgrade must continue into quiescence", lease, prepared)
        assertEquals(listOf(AppInstaller.SNAPSHOT_UNWRITABLE_WARNING), warnings)
    }

    @Test fun aWrittenConfigRevisionIsTakenBeforeStateIsQuiesced() {
        val order = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        AppInstaller.prepareSelfReplace(
            snapshot = { order.add("snapshot"); true },
            quiesce = { order.add("quiesce"); StateQuiescence { } },
            warn = warnings::add,
        )

        assertEquals(listOf("snapshot", "quiesce"), order)
        assertTrue("a written revision must not warn", warnings.isEmpty())
    }

    @Test fun storageTooSmallForTheActualApkIsRefused() {
        assertEquals("a 100 MB APK cannot be staged in 50 MB", 0L, AppInstaller.downloadCeiling(100 * MIB, 50 * MIB))
        assertEquals("an undeclared length on a full filesystem is refused", 0L, AppInstaller.downloadCeiling(-1L, 0L))
    }

    @Test fun noFixedSurplusIsReservedAboveTheApkItself() {
        // 110 MB free for a 100 MB APK is viable, and was refused only by the retired 64 MB reserve.
        val ceiling = AppInstaller.downloadCeiling(100 * MIB, 110 * MIB)

        assertTrue("an otherwise viable update must be admitted", ceiling > 0L)
        assertTrue("the download must not be bounded below the APK itself", ceiling >= 100 * MIB)
    }

    private val github = URL("https://github.com/panel-assistant/android/releases/download/v1/app.apk")

    @Test fun followsAbsoluteHttpsRedirect() {
        val next = AppInstaller.httpsRedirect(github, "https://objects.githubusercontent.com/x/app.apk")
        assertEquals("https", next?.protocol)
        assertEquals("objects.githubusercontent.com", next?.host)
    }

    @Test fun refusesHttpsToHttpDowngrade() =
        assertNull("http redirect target must be refused", AppInstaller.httpsRedirect(github, "http://cdn.example/app.apk"))

    @Test fun resolvesRelativeRedirectAgainstBaseAndKeepsHttps() {
        // A relative Location inherits the (https) base scheme — previously URL("/path") threw and failed the download.
        val next = AppInstaller.httpsRedirect(github, "/redirected/app.apk")
        assertEquals("https", next?.protocol)
        assertEquals("github.com", next?.host)
        assertEquals("/redirected/app.apk", next?.path)
    }

    /** The scheme gate refuses before any socket is opened, so these are deterministic and offline.
     *  They pin that a refusal is reported as [AppInstaller.DownloadResult.Failed] rather than as a
     *  size or deadline outcome — the Install page words those three differently for the operator. */
    @Test fun refusesUnusableDownloadUrlsWithoutTouchingTheNetwork() {
        val destination = File.createTempFile("download-refusal-", ".apk").also { it.deleteOnExit() }

        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.download("http://cdn.example/app.apk", destination, 1024L),
        )
        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.download("ftp://cdn.example/app.apk", destination, 1024L),
        )
        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.download("not a url at all", destination, 1024L),
        )
        assertEquals(0L, destination.length())
    }

    /** A size breach, a stall and everything else must stay distinguishable: the Install page turns
     *  these into three different sentences, and an operator who is not at the panel has nothing else
     *  to go on. Collapsing any two of them would leave the page telling them the wrong thing. */
    @Test fun classifiesDownloadFailuresByWhatTheOperatorMustDoNext() {
        assertEquals(
            AppInstaller.DownloadResult.TooLarge,
            AppInstaller.downloadFailure(ByteLimitExceeded(4L)),
        )
        assertEquals(
            AppInstaller.DownloadResult.TimedOut,
            AppInstaller.downloadFailure(java.net.SocketTimeoutException("read timed out")),
        )
        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.downloadFailure(java.io.IOException("connection reset")),
        )
    }

    /** A download whose owner cancelled before it began must not reach the network. This runs offline:
     *  opening a URL connection performs no I/O, and the abort is observed before the first request. */
    @Test fun downloadRefusesToStartWhenItsOwnerAlreadyCancelled() {
        val destination = File.createTempFile("download-cancelled-", ".apk").also { it.deleteOnExit() }
        val abort = DownloadAbort().apply { abort() }
        var requested = false
        var disconnected = false
        val connection = object : java.net.HttpURLConnection(java.net.URL("https://cdn.example/app.apk")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
            override fun getResponseCode(): Int {
                requested = true
                return 200
            }
        }

        assertEquals(
            AppInstaller.DownloadResult.Aborted,
AppInstaller.download("https://cdn.example/app.apk", destination, 1024L, abort) { connection },
        )
        // The outcome alone cannot prove this: a failed request would also be reported as aborted once
        // the owner has cancelled. What must hold is that no request was ever issued.
        assertFalse("a cancelled download must not issue a request at all", requested)
        assertTrue("the connection opened before the abort was seen must be closed", disconnected)
        assertEquals(0L, destination.length())
    }

    @Test fun refusesNonHttpScheme() {
        assertNull("file:// target must be refused", AppInstaller.httpsRedirect(github, "file:///etc/passwd"))
        assertNull("ftp:// target must be refused", AppInstaller.httpsRedirect(github, "ftp://host/app.apk"))
    }

    @Test fun hashesDownloadedBlobForExactReleasePins() {
        val file = File.createTempFile("installer-hash-", ".bin")
        try {
            file.writeText("ha-paneld")
            assertEquals("9e3e7fce3ad3280fc638bb3c9dd1b8a5ea8b84ecd716129b6ce18afd946e3309", AppInstaller.sha256(file))
        } finally {
            file.delete()
        }
    }

    @Test fun shizukuRequiresExplicitCuratedInstallOptIn() {
        assertEquals(
            AppInstaller.InstallRoute.NONE,
            AppInstaller.selectInstallRoute(false, false, true, allowShizuku = false),
        )
        assertEquals(
            AppInstaller.InstallRoute.SHIZUKU,
            AppInstaller.selectInstallRoute(false, false, true, allowShizuku = true),
        )
    }

    @Test fun installRouteIsSelectedOnceInEstablishedPrecedenceOrder() {
        assertEquals(
            AppInstaller.InstallRoute.SU,
            AppInstaller.selectInstallRoute(true, true, true, allowShizuku = true),
        )
        assertEquals(
            AppInstaller.InstallRoute.DAEMON,
            AppInstaller.selectInstallRoute(false, true, true, allowShizuku = true),
        )
    }

    @Test fun packageManagerRejectionIsDurableWhileOtherOutputStaysRetryable() {
        // A `pm install` `Failure [...]` line is a durable rejection of THIS artifact — retrying the same
        // pin cannot help — so it is Rejected; anything else is a transient Retryable. The message
        // preserves the historical "install failed: <output>" text exactly.
        assertEquals(
            InstallOutcome.Rejected("install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
            AppInstaller.installFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
        )
        assertEquals(
            InstallOutcome.Retryable("install failed: Shizuku installer unavailable"),
            AppInstaller.installFailure("Shizuku installer unavailable"),
        )
        assertEquals(
            InstallOutcome.Retryable("install failed: "),
            AppInstaller.installFailure(""),
        )
    }

    @Test fun aMissingPackageManagerReplyDoesNotCertifyThatTheInstallFailed() {
        val uncertain = InstallOutcome.Retryable(
            "install outcome unknown: pm reply lost", mayHaveCommitted = true,
        )
        assertEquals(uncertain, AppInstaller.classifyPmInstallReply(null))
        assertEquals(uncertain, AppInstaller.classifyPmInstallReply(""))
        assertEquals(InstallOutcome.Succeeded, AppInstaller.classifyPmInstallReply("Success"))
        assertEquals(
            InstallOutcome.Rejected("install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
            AppInstaller.classifyPmInstallReply("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
        )
    }

    @Test fun slowProgressCannotExtendTheWholeDownloadDeadline() {
        val remaining = ArrayDeque(listOf(10L, 0L))
        val output = ByteArrayOutputStream()

        val failure = runCatching {
            AppInstaller.copyBeforeDeadline(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                output,
                maxBytes = 10L,
                remainingMs = { remaining.removeFirstOrNull() ?: 0L },
            )
        }.exceptionOrNull()

        assertTrue(failure is java.net.SocketTimeoutException)
        assertEquals(0, output.size())
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
