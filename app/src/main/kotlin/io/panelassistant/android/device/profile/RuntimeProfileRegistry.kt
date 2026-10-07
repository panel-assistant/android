package io.panelassistant.android.device.profile

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.EvdevButton
import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.SuForm
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.persistence.AppState
import io.panelassistant.android.util.SystemProps
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

private const val EMERGENCY_PROFILE_VERSION = "capability-empty-emergency-v1"
private val EMERGENCY_PROFILE_REF = ProfileRef("generic", ProfileYaml.sha256(EMERGENCY_PROFILE_VERSION))

internal fun interface EvdevDeviceInspector {
    /** True/false when capabilities are readable; null when the target cannot be classified safely. */
    fun isTouchscreen(node: String): Boolean?
}

internal object SysfsEvdevDeviceInspector : EvdevDeviceInspector {
    override fun isTouchscreen(node: String): Boolean? {
        val event = node.substringAfterLast('/').takeIf { Regex("event[0-9]{1,3}").matches(it) } ?: return null
        val capabilities = File("/sys/class/input/$event/device/capabilities")
        val abs = runCatching { File(capabilities, "abs").readText() }.getOrNull() ?: return null
        val key = runCatching { File(capabilities, "key").readText() }.getOrNull() ?: return null
        return capabilitiesAreTouchscreen(abs, key)
    }

    internal fun capabilitiesAreTouchscreen(absCapabilities: String, keyCapabilities: String): Boolean? {
        // sysfs input bitmaps use the kernel's unsigned-long width. ha-paneld runs on both 32-bit and
        // 64-bit kernels, and an all-low-bit bitmap does not encode that width. Treat either valid
        // interpretation as touchscreen-class; a conservative rejection is safer than grabbing a panel.
        val classifications = listOf(32, 64).mapNotNull { wordBits ->
            val abs = readBitmap(absCapabilities, wordBits) ?: return@mapNotNull null
            val key = readBitmap(keyCapabilities, wordBits) ?: return@mapNotNull null
            val hasAbsoluteCoordinates = (abs.contains(ABS_X) && abs.contains(ABS_Y)) ||
                (abs.contains(ABS_MT_POSITION_X) && abs.contains(ABS_MT_POSITION_Y))
            hasAbsoluteCoordinates && key.contains(BTN_TOUCH)
        }
        return classifications.takeIf { it.isNotEmpty() }?.any { it }
    }

    private fun readBitmap(value: String, wordBits: Int): Set<Int>? = runCatching {
        val words = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return@runCatching emptySet()
        buildSet {
            words.asReversed().forEachIndexed { wordIndex, word ->
                val bits = word.toULong(16)
                if (wordBits == 32 && bits > UInt.MAX_VALUE.toULong()) error("32-bit bitmap word overflow")
                for (bit in 0 until wordBits) {
                    if ((bits and (1uL shl bit)) != 0uL) add(wordIndex * wordBits + bit)
                }
            }
        }
    }.getOrNull()

    private const val ABS_X = 0
    private const val ABS_Y = 1
    private const val ABS_MT_POSITION_X = 0x35
    private const val ABS_MT_POSITION_Y = 0x36
    private const val BTN_TOUCH = 0x14a
}

/** Last-resort runtime contract when the bundled YAML catalog has no valid fallback. This deliberately
 * declares no privileged or optional hardware capability; it exists only to keep the service usable
 * enough to report and repair the catalog failure. */
private object EmergencyDeviceProfile : DeviceProfile {
    override val id = EMERGENCY_PROFILE_REF.id
    override val revision = EMERGENCY_PROFILE_REF.revision
    override val displayName = "Emergency safe profile"
    override val socClass = "unknown"
    override val suForm = SuForm.NONE
    override val appCanSu = false
    override val usesDaemon = false
    override val hasRecents = false
    override val hasNativeNavbar: Boolean? = null
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer: String? = null
    override val model: String? = null
    override val evdevButtons = emptyList<EvdevButton>()
    override val cpuGovernors: Map<String, String>? = null
}

internal interface ProfileRevisionPersistence {
    fun createDirectory(directory: File): Boolean
    fun writeAndSync(file: File, bytes: ByteArray)
    fun atomicRename(source: File, target: File): Boolean
    fun delete(file: File): Boolean
    fun syncDirectory(directory: File)
}

internal object FileProfileRevisionPersistence : ProfileRevisionPersistence {
    override fun createDirectory(directory: File): Boolean = directory.isDirectory || directory.mkdir()

    override fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    override fun atomicRename(source: File, target: File): Boolean = source.renameTo(target)

    override fun delete(file: File): Boolean = file.delete()

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}

/**
 * Local immutable-revision profile registry. Imports are inert until explicitly selected; selection is
 * staged for the next process start and rolls back if that activation never reports healthy.
 */
class RuntimeProfileRegistry internal constructor(
    private val filesDir: File,
    private val preferences: ProfilePreferences,
    private val bundledLoader: () -> Map<String, String>,
    private val facts: DeviceFacts,
    private val coreVersion: String,
    private val clock: () -> Long,
    private val revisionPersistence: ProfileRevisionPersistence = FileProfileRevisionPersistence,
    private val evdevInspector: EvdevDeviceInspector = SysfsEvdevDeviceInspector,
    private val catalogFileReader: ((File) -> String)? = null,
) : ProfileAdmin, ProfileResolver {
    private data class StoredProfile(
        val ref: ProfileRef,
        val origin: ProfileOrigin,
        val rawYaml: String,
        val sourceBytes: Int,
        val document: ProfileDocument?,
        val issues: List<ProfileIssue> = emptyList(),
        val rollbackOnly: Boolean = false,
        val importedAtEpochMs: Long? = null,
    ) {
        val compatible: Boolean get() = document != null && issues.none { it.severity == ProfileIssueSeverity.ERROR }
    }

    private val importedDir = File(filesDir, "device-profiles/imported")
    private val rollbackDir = File(filesDir, "device-profiles/rollback")
    private val previewTokens = PreviewTokenStore(clock)
    private var entries: Map<ProfileRef, StoredProfile> = emptyMap()
    private var catalogIssues: List<ProfileIssue> = emptyList()
    private var importedDiskCount: Int = 0
    private var importedDiskBytes: Long = 0
    private var importedDiskCountById: Map<String, Int> = emptyMap()
    private var fullyHydrated = false
    private var resolvedStartupRef: ProfileRef? = null

    init {
        loadStartupCatalog()
    }

    constructor(
        context: Context,
        facts: DeviceFacts = buildFacts(),
        coreVersion: String = BuildConfig.VERSION_NAME,
    ) : this(
        filesDir = context.applicationContext.filesDir,
        preferences = AndroidProfilePreferences(
            AppState.preferences(context, "device-profiles", PREFS_NAME),
        ),
        bundledLoader = { bundledAssets(context) },
        facts = facts,
        coreVersion = coreVersion,
        clock = System::currentTimeMillis,
    )

    override fun schema(): ProfileSchemaDescriptor = ProfileMetadata.schema

    override fun drivers(): List<ProfileDriverDescriptor> = ProfileMetadata.drivers

    @Synchronized
    override fun status(): ProfileStatus {
        ensureHydrated()
        return statusLocked()
    }

    @Synchronized
    override fun list(): List<ProfileSummary> {
        ensureHydrated()
        val state = readActivation()
        val requestedSelection = state.desired ?: readSelection()
        val selectedRef = when (requestedSelection) {
            ProfileSelection.Auto -> resolveSelection(requestedSelection).entry?.ref
            is ProfileSelection.Pinned -> requestedSelection.ref
        }
        val activeSelection = if (state.phase == ProfileActivationPhase.PENDING) state.previous ?: ProfileSelection.Auto else readSelection()
        val activeRef = resolveSelection(activeSelection).entry?.ref
        return entries.values
            .map { summary(it, active = it.ref == activeRef, selected = it.ref == selectedRef) }
            .sortedWith(compareBy<ProfileSummary>({ it.origin }, { it.displayName.lowercase() }, { it.contentVersion }, { it.ref.revision }))
    }

    @Synchronized
    override fun preview(rawYaml: String): ProfilePreview {
        ensureHydrated()
        val parsed = measuredParse(rawYaml)
        val validation = parsed.document?.let { measuredValidate(it, bundled = false) }.orEmpty()
        val issues = parsed.issues + validation
        val compatible = parsed.document != null && issues.none { it.severity == ProfileIssueSeverity.ERROR }
        val token = if (compatible) previewTokens.issue(parsed.contentSha256) else null
        val document = parsed.document
        val ref = document?.let { ProfileRef(it.id, parsed.contentSha256) }
        val stored = if (document != null && ref != null) StoredProfile(
            ref, ProfileOrigin.IMPORTED, rawYaml, parsed.sourceBytes, document, issues,
        ) else null
        return ProfilePreview(
            previewToken = token?.value,
            contentSha256 = parsed.contentSha256,
            expiresAtEpochMs = token?.expiresAt,
            summary = stored?.let { summary(it, active = false, selected = false) },
            issues = issues,
            diffFromActive = document?.let { diffFromActive(it) }.orEmpty(),
            compatible = compatible,
        )
    }

    @Synchronized
    override fun importProfile(rawYaml: String, previewToken: String): ProfileMutation {
        ensureHydrated()
        val hash = ProfileYaml.sha256(rawYaml)
        if (!previewTokens.consume(previewToken, hash)) {
            return rejected("preview_token", "Preview token is invalid, expired, already used, or belongs to different content.", "preview-token-invalid")
        }
        val parsed = measuredParse(rawYaml)
        val document = parsed.document
        val issues = parsed.issues + document?.let { measuredValidate(it, bundled = false) }.orEmpty()
        if (document == null || issues.any { it.severity == ProfileIssueSeverity.ERROR }) return ProfileMutation.Rejected(statusLocked(), issues)
        val ref = ProfileRef(document.id, hash)
        if (entries[ref] == null) {
            val rawBytes = rawYaml.toByteArray(Charsets.UTF_8).size.toLong()
            val quotaIssue = when {
                importedDiskCount >= ProfileMetadata.MAX_IMPORTED_REVISIONS ->
                    "Imported catalog is limited to ${ProfileMetadata.MAX_IMPORTED_REVISIONS} revisions." to ProfilePresentation(
                        "imported-catalog-revision-limit",
                        mapOf("max" to ProfileMetadata.MAX_IMPORTED_REVISIONS.toString()),
                    )
                importedDiskCountById.getOrDefault(document.id, 0) >= ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID ->
                    "Profile '${document.id}' is limited to ${ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID} revisions." to ProfilePresentation(
                        "imported-profile-revision-limit",
                        mapOf(
                            "id" to document.id,
                            "max" to ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID.toString(),
                        ),
                    )
                importedDiskBytes > ProfileMetadata.MAX_IMPORTED_BYTES - rawBytes ->
                    "Imported catalog is limited to ${ProfileMetadata.MAX_IMPORTED_BYTES} bytes." to ProfilePresentation(
                        "imported-catalog-byte-limit",
                        mapOf("max" to ProfileMetadata.MAX_IMPORTED_BYTES.toString()),
                    )
                else -> null
            }
            if (quotaIssue != null) return rejected("catalog.quota", quotaIssue.first, quotaIssue.second)
            // Reserve the optimistic-concurrency revision before touching disk. A crash or write failure
            // may conservatively make clients refresh, but can no longer change the catalog while leaving
            // its revision unchanged.
            if (!bumpCatalogRevision()) return rejected("catalog_revision", "Could not reserve the catalog change.", "catalog-reservation-failed")
            if (!writeImported(ref, rawYaml)) return rejected("$", "Could not store the immutable profile revision.", "profile-store-failed")
            reload()
        }
        return ProfileMutation.Success(
            statusLocked(),
            restartRequired = false,
            message = "Imported ${document.displayName} ${document.version}.",
            presentation = ProfilePresentation(
                "profile-imported",
                mapOf("display_name" to document.displayName, "version" to document.version),
            ),
        )
    }

    @Synchronized
    override fun exportProfile(ref: ProfileRef): String? {
        ensureHydrated()
        return entries[ref]?.rawYaml
    }

    @Synchronized
    override fun exportBackup(): ProfileBackup {
        ensureHydrated()
        val status = statusLocked()
        val revisions = entries.values.asSequence()
            .filter { it.origin == ProfileOrigin.IMPORTED }
            .sortedWith(compareBy<StoredProfile>({ it.ref.id }, { it.ref.revision }))
            .map { ProfileBackupRevision(it.ref, it.rawYaml) }
            .toList()
        return ProfileBackup(
            revisions = revisions,
            selection = status.selection,
            active = status.active?.ref,
            lastKnownGood = status.lastKnownGood,
        )
    }

    @Synchronized
    override fun planBackupRestore(payload: ProfileBackup): ProfileBackupRestorePlan {
        ensureHydrated()
        return planBackupRestoreLocked(payload)
    }

    @Synchronized
    override fun restoreBackup(
        payload: ProfileBackup,
        expectedCatalogRevision: Long,
    ): ProfileBackupRestoreResult {
        ensureHydrated()
        val before = statusLocked()
        if (expectedCatalogRevision != before.catalogRevision) {
            return rejectedBackupRestore(
                before,
                listOf(issue(ProfileIssueSeverity.ERROR, "profiles.catalog_revision", "Catalog changed; revalidate the backup and retry.", "backup-restore-catalog-stale")),
            )
        }
        val plan = planBackupRestoreLocked(payload)
        if (!plan.valid) return rejectedBackupRestore(plan.status, plan.issues, plan.alreadyPresent)

        val candidates = payload.revisions.associateBy { it.ref }
        val imported = mutableListOf<ProfileRef>()
        val writeIssues = mutableListOf<ProfileIssue>()
        if (plan.toImport.isNotEmpty()) {
            // Reserve the CAS token before any file appears. A failed write can leave only inert,
            // immutable revisions behind and forces every concurrent administrator to refresh.
            if (!bumpCatalogRevision()) {
                return rejectedBackupRestore(
                    statusLocked(),
                    listOf(issue(ProfileIssueSeverity.ERROR, "profiles.catalog_revision", "Could not reserve the catalog restore.", "backup-restore-reservation-failed")),
                    plan.alreadyPresent,
                )
            }
            plan.toImport.forEach { ref ->
                val raw = candidates.getValue(ref).rawYaml
                if (writeImported(ref, raw)) {
                    imported += ref
                } else {
                    writeIssues += ProfileIssue(
                        ProfileIssueSeverity.ERROR,
                        "profiles.revisions[${ref.id}@${ref.revision}]",
                        "Could not store the immutable revision.",
                        ProfilePresentation("backup-revision-store-failed"),
                    )
                }
            }
            reload()
            imported.toList().forEach { ref ->
                val expectedRaw = candidates.getValue(ref).rawYaml
                val stored = entries[ref]
                if (stored?.compatible != true || stored.rawYaml != expectedRaw) {
                    imported.remove(ref)
                    writeIssues += ProfileIssue(
                        ProfileIssueSeverity.ERROR,
                        "profiles.revisions[${ref.id}@${ref.revision}]",
                        "Stored revision did not pass the post-write coherency check.",
                        ProfilePresentation("backup-post-write-coherency-failed"),
                    )
                }
            }
        }
        if (writeIssues.isNotEmpty()) {
            return ProfileBackupRestoreResult(
                outcome = ProfileBackupRestoreOutcome.PARTIAL,
                status = statusLocked(),
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues + writeIssues,
                selectionStaged = false,
                restartRequired = false,
                message = "Some profile revisions were imported inertly; selection was not changed.",
                presentation = ProfilePresentation("backup-import-partial-selection-unchanged"),
            )
        }

        val restored = requireNotNull(plan.selection)
        val current = readSelection()
        if (restored == current) {
            return ProfileBackupRestoreResult(
                outcome = ProfileBackupRestoreOutcome.SUCCEEDED,
                status = statusLocked(),
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues,
                selectionStaged = false,
                restartRequired = false,
                message = "Profile catalog restored; selection is unchanged.",
                presentation = ProfilePresentation("backup-restored-selection-unchanged"),
            )
        }

        // Do not copy transient activation state or replace the destination rollback target. select()
        // records the current proven selection as `previous` and health-gates the restored selection.
        return when (val selected = select(restored, catalogRevision())) {
            is ProfileMutation.Success -> ProfileBackupRestoreResult(
                outcome = ProfileBackupRestoreOutcome.SUCCEEDED,
                status = selected.status,
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues,
                selectionStaged = true,
                restartRequired = selected.restartRequired,
                message = "Profile catalog restored; selection staged for a health-gated restart.",
                presentation = ProfilePresentation("backup-restored-selection-staged"),
            )
            is ProfileMutation.Rejected -> ProfileBackupRestoreResult(
                outcome = if (imported.isEmpty()) ProfileBackupRestoreOutcome.REJECTED else ProfileBackupRestoreOutcome.PARTIAL,
                status = selected.status,
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues + selected.issues,
                selectionStaged = false,
                restartRequired = false,
                message = if (imported.isEmpty()) {
                    "Profile selection could not be restored."
                } else {
                    "Profile revisions were imported inertly, but selection could not be staged."
                },
                presentation = ProfilePresentation(
                    if (imported.isEmpty()) "backup-selection-restore-failed" else "backup-import-selection-stage-failed",
                ),
            )
        }
    }

    @Synchronized
    override fun select(selection: ProfileSelection, expectedCatalogRevision: Long): ProfileMutation {
        ensureHydrated()
        if (expectedCatalogRevision != catalogRevision()) return staleCatalog()
        if (selection is ProfileSelection.Pinned) {
            val entry = entries[selection.ref] ?: return rejected("selection", "Profile revision does not exist.", "profile-revision-not-found")
            if (!entry.compatible) return ProfileMutation.Rejected(
                statusLocked(),
                entry.issues.ifEmpty {
                    listOf(
                        ProfileIssue(
                            ProfileIssueSeverity.ERROR,
                            "selection",
                            "Profile revision is incompatible with this core.",
                            ProfilePresentation("profile-incompatible"),
                        ),
                    )
                },
            )
            activationIssues(entry).takeIf { it.isNotEmpty() }?.let {
                return ProfileMutation.Rejected(statusLocked(), it)
            }
        }
        val state = readActivation()
        if (state.phase == ProfileActivationPhase.PENDING || state.phase == ProfileActivationPhase.APPLYING) {
            return rejected("activation", "A profile activation is already in progress.", "activation-in-progress")
        }
        val current = readSelection()
        if (selection == current) return ProfileMutation.Success(
            statusLocked(),
            false,
            "Profile selection is unchanged.",
            ProfilePresentation("profile-selection-unchanged"),
        )
        val generation = maxOf(state.generation, preferences.getLong(KEY_GENERATION, 0L)) + 1
        val next = ProfileActivationState(
            phase = ProfileActivationPhase.PENDING,
            generation = generation,
            previous = current,
            desired = selection,
            message = "Selection staged; restart required.",
            presentation = ProfilePresentation("activation-pending"),
        )
        if (!preferences.put(
                *selectionState(selection),
                KEY_PHASE to next.phase.name,
                KEY_GENERATION to generation,
                KEY_PREVIOUS to encode(current),
                KEY_DESIRED to encode(selection),
                KEY_MESSAGE to next.message,
                KEY_PRESENTATION_CODE to "activation-pending",
                KEY_CATALOG_REVISION to catalogRevision() + 1,
            )) return rejected("activation", "Could not persist the staged selection.", "selection-persist-failed")
        return ProfileMutation.Success(
            statusLocked(),
            true,
            "Profile selection staged for restart.",
            ProfilePresentation("profile-selection-staged"),
        )
    }

    @Synchronized
    override fun rollbackToLastKnownGood(expectedCatalogRevision: Long): ProfileMutation {
        ensureHydrated()
        if (expectedCatalogRevision != catalogRevision()) return staleCatalog()
        val target = readLastKnownGood() ?: return rejected("last_known_good", "No previous proven selection is available.", "rollback-unavailable")
        return select(target, expectedCatalogRevision)
    }

    @Synchronized
    override fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long): ProfileMutation {
        ensureHydrated()
        if (expectedCatalogRevision != catalogRevision()) return staleCatalog()
        val entry = entries[ref] ?: return rejected("profile", "Profile revision does not exist.", "profile-revision-not-found")
        if (entry.origin != ProfileOrigin.IMPORTED) return rejected("profile", "Bundled profile revisions cannot be deleted.", "bundled-profile-delete-forbidden")
        val state = readActivation()
        val referenced = listOfNotNull(readSelection(), state.previous, state.desired, readLastKnownGood())
            .any { selection -> selection is ProfileSelection.Pinned && selection.ref == ref }
        val active = statusLocked().active?.ref == ref
        if (referenced || active) return rejected("profile", "Selected, active, or rollback profile revisions cannot be deleted.", "referenced-profile-delete-forbidden")
        val file = importedFile(ref)
        // As with import, advance the CAS token first. A failed delete can cause only a harmless extra
        // refresh; a successful delete can never be published under the old catalog revision.
        if (!bumpCatalogRevision()) return rejected("catalog_revision", "Could not reserve the catalog change.", "catalog-reservation-failed")
        if (!deleteImmutable(file)) {
            reload()
            return rejected("profile", "Could not durably delete the imported revision.", "profile-delete-failed")
        }
        reload()
        return ProfileMutation.Success(
            statusLocked(),
            false,
            "Deleted imported profile revision.",
            ProfilePresentation("profile-revision-deleted"),
        )
    }

    /** Called once, before controllers are constructed for this process lifetime. */
    @Synchronized
    override fun resolveForStartup(): ResolvedProfile {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_STARTUP_RESOLVE)
        return try {
            val state = readActivation()
            when (state.phase) {
            ProfileActivationPhase.PENDING -> {
                val staged = state.desired ?: readSelection()
                val resolution = resolveSelection(staged)
                val successor = (staged as? ProfileSelection.Pinned)?.let(::retiredBundledSuccessor)
                val desired = successor?.let { ProfileSelection.Pinned(it.ref) } ?: staged
                val repinIssues = if (successor != null) listOf(repinIssue(staged as ProfileSelection.Pinned, successor)) else emptyList()
                // A re-pinned successor must not be able to roll back onto itself, so the staged
                // rollback target is re-chosen to exclude it; an ordinary activation keeps its own.
                val previous = if (successor != null) compatibleRollbackTarget(state.previous, excluding = successor.ref) else state.previous
                if (resolution.entry == null && successor == null) {
                    rollbackInvalidPending(state, resolution.issues)
                } else if (!preferences.put(
                        *selectionState(desired),
                        KEY_DESIRED to encode(desired),
                        KEY_PREVIOUS to previous?.let(::encode),
                        KEY_PHASE to ProfileActivationPhase.APPLYING.name,
                        KEY_MESSAGE to "Applying selected profile.",
                        KEY_PRESENTATION_CODE to "activation-applying-selected",
                        KEY_CATALOG_REVISION to catalogRevision() + 1,
                    )) {
                    rollbackInvalidPending(
                        state,
                        repinIssues + issue(ProfileIssueSeverity.ERROR, "activation", "Could not persist the applying state.", "activation-applying-persist-failed"),
                        preferred = previous,
                        excluding = (desired as? ProfileSelection.Pinned)?.ref,
                    )
                } else {
                    resolved(desired, state.generation, repinIssues)
                }
            }
            ProfileActivationPhase.APPLYING -> {
                // The revision that failed to prove healthy must not be the rollback destination, even
                // through automatic matching on hardware that would pick it again. A failed bundled
                // successor is also remembered so a re-pin does not retry it on the next start.
                val failed = (state.desired as? ProfileSelection.Pinned)?.ref
                val rollback = compatibleRollbackTarget(state.previous, excluding = failed)
                val persisted = persistRollback(
                    rollback,
                    "Previous activation did not report healthy; rolled back to ${describe(rollback)}.",
                    rollbackPresentation("activation-rolled-back-unhealthy", rollback),
                )
                val issue = if (persisted) {
                    issue(ProfileIssueSeverity.WARNING, "activation", "Previous profile activation was rolled back after an unhealthy restart.", "activation-unhealthy-rollback-complete")
                } else {
                    issue(ProfileIssueSeverity.ERROR, "activation", "Using the previous profile for this run, but the rollback could not be persisted and will be retried after restart.", "activation-unhealthy-rollback-persist-failed")
                }
                resolved(rollback, null, listOf(issue))
            }
            ProfileActivationPhase.ACTIVE, ProfileActivationPhase.ROLLED_BACK -> {
                val selection = readSelection()
                val resolution = resolveSelection(selection)
                val successor = (selection as? ProfileSelection.Pinned)?.let(::retiredBundledSuccessor)
                if (selection is ProfileSelection.Pinned && successor != null) {
                    repinRetiredBundled(selection, successor, state)
                } else if (selection is ProfileSelection.Pinned && resolution.entry == null) {
                    recoverInvalidActive(selection, resolution.issues)
                } else if (selection is ProfileSelection.Pinned) {
                    resolved(selection, null, listOfNotNull(rejectedSuccessorIssue(selection)))
                } else if (selection == ProfileSelection.Auto) {
                    val current = resolution.entry
                    val previous = readActiveRef()
                    if (current != null && previous != null && previous != current.ref && entries[previous]?.compatible == true) {
                        val generation = maxOf(state.generation, preferences.getLong(KEY_GENERATION, 0L)) + 1
                        if (preferences.put(
                                KEY_PHASE to ProfileActivationPhase.APPLYING.name,
                                KEY_GENERATION to generation,
                                KEY_PREVIOUS to encode(ProfileSelection.Pinned(previous)),
                                KEY_DESIRED to encode(ProfileSelection.Auto),
                                KEY_MESSAGE to "Applying updated automatically matched profile.",
                                KEY_PRESENTATION_CODE to "activation-applying-auto-update",
                                KEY_CATALOG_REVISION to catalogRevision() + 1,
                            )) {
                            resolved(selection, generation)
                        } else {
                            resolved(ProfileSelection.Pinned(previous), null, listOf(issue(ProfileIssueSeverity.ERROR, "activation", "Could not stage the automatically updated profile; retained the previous revision.", "activation-auto-update-stage-failed")))
                        }
                    } else {
                        resolved(selection, null)
                    }
                } else {
                    resolved(selection, null)
                }
            }
            }.also { resolved ->
                resolvedStartupRef = resolved.summary.ref.takeIf { it in entries }
                // After the activation decision, so it cannot displace a write that decision makes or
                // consume its failure; every path that does write a selection has already recorded the
                // origin through selectionState, which leaves this to the steady-state legacy case.
                backfillLegacySelectionOrigin()
            }
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.work(units = 1L).close()
        }
    }

    @Synchronized
    override fun markActivationHealthy(generation: Long): Boolean {
        val state = readActivation()
        if (state.phase != ProfileActivationPhase.APPLYING || state.generation != generation) return false
        val activeRef = resolveSelection(readSelection()).entry?.ref ?: return false
        if (!writeRollbackSnapshot(activeRef)) return false
        val persisted = preferences.put(
            *selectionState(readSelection()),
            KEY_PHASE to ProfileActivationPhase.ACTIVE.name,
            KEY_ACTIVE_REF to encode(ProfileSelection.Pinned(activeRef)),
            KEY_LAST_KNOWN_GOOD to (state.previous ?: readLastKnownGood())?.let(::encode),
            KEY_PREVIOUS to null,
            KEY_DESIRED to null,
            KEY_MESSAGE to null,
            KEY_PRESENTATION_CODE to null,
            KEY_CATALOG_REVISION to catalogRevision() + 1,
        )
        if (persisted) pruneRollbackSnapshots(activeRef)
        return persisted
    }

    /** Records the proven runtime revision after an ordinary (non-activation) startup. */
    @Synchronized
    fun markStartupHealthy(ref: ProfileRef): Boolean {
        val state = readActivation()
        if (state.phase == ProfileActivationPhase.PENDING || state.phase == ProfileActivationPhase.APPLYING) return false
        if (!writeRollbackSnapshot(ref)) return false
        val persisted = preferences.put(KEY_ACTIVE_REF to encode(ProfileSelection.Pinned(ref)))
        if (persisted) pruneRollbackSnapshots(ref)
        return persisted
    }

    /** Records the revision returned by [resolveForStartup] without hydrating the admin catalog. */
    @Synchronized
    fun markResolvedStartupHealthy(): Boolean {
        val ref = resolvedStartupRef ?: return false
        return markStartupHealthy(ref)
    }

    /** Cancels a staged restart when teardown could not release every restart-critical owner. */
    @Synchronized
    fun abortPendingActivation(message: String): Boolean {
        val state = readActivation()
        if (state.phase != ProfileActivationPhase.PENDING) return false
        val rollback = state.previous ?: readLastKnownGood() ?: ProfileSelection.Auto
        return persistRollback(
            rollback,
            // Caller-owned teardown evidence is compatibility prose, not a stable semantic code.
            message.trim().take(240).ifBlank { "Profile restart was cancelled during teardown." },
            null,
        )
    }

    private data class SelectionResolution(val entry: StoredProfile?, val issues: List<ProfileIssue>)

    private fun planBackupRestoreLocked(payload: ProfileBackup): ProfileBackupRestorePlan {
        val status = statusLocked()
        val issues = mutableListOf<ProfileIssue>()
        if (payload.schema != ProfileBackup.SCHEMA) {
            issues += issue(
                ProfileIssueSeverity.ERROR,
                "profiles.schema",
                "Unsupported profile backup schema '${payload.schema}'.",
                "backup-schema-version-unsupported",
                mapOf("actual" to payload.schema.toString()),
            )
        }
        if (payload.revisions.size > ProfileMetadata.MAX_IMPORTED_REVISIONS) {
            issues += issue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Profile backup exceeds the revision limit.", "backup-revision-limit")
        }
        val seen = mutableSetOf<ProfileRef>()
        val validated = linkedMapOf<ProfileRef, ProfileBackupRevision>()
        val alreadyPresent = mutableListOf<ProfileRef>()
        var payloadBytes = 0L
        var newBytes = 0L
        val payloadById = mutableMapOf<String, Int>()
        val newById = mutableMapOf<String, Int>()
        payload.revisions.sortedWith(compareBy<ProfileBackupRevision>({ it.ref.id }, { it.ref.revision }))
            .forEachIndexed { index, candidate ->
                val path = "profiles.revisions[$index]"
                if (!seen.add(candidate.ref)) {
                    issues += issue(ProfileIssueSeverity.ERROR, path, "Duplicate immutable revision.", "backup-duplicate-revision")
                    return@forEachIndexed
                }
                val parsed = measuredParse(candidate.rawYaml)
                val document = parsed.document
                val candidateIssues = parsed.issues + document?.let { measuredValidate(it, bundled = false) }.orEmpty()
                issues += candidateIssues.map { it.copy(path = "$path.${it.path}") }
                val sourceBytes = parsed.sourceBytes.toLong()
                payloadBytes = if (Long.MAX_VALUE - payloadBytes < sourceBytes) Long.MAX_VALUE else payloadBytes + sourceBytes
                val payloadIdCount = payloadById.getOrDefault(candidate.ref.id, 0) + 1
                payloadById[candidate.ref.id] = payloadIdCount
                if (payloadIdCount > ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID) {
                    issues += issue(
                        ProfileIssueSeverity.ERROR,
                        path,
                        "Profile '${candidate.ref.id}' exceeds the backup revision limit.",
                        "backup-profile-revision-limit-plan",
                        mapOf("id" to candidate.ref.id),
                    )
                }
                if (parsed.contentSha256 != candidate.ref.revision) {
                    issues += issue(ProfileIssueSeverity.ERROR, "$path.revision", "Revision does not match the YAML SHA-256.", "backup-revision-hash-mismatch")
                }
                if (document != null && document.id != candidate.ref.id) {
                    issues += issue(ProfileIssueSeverity.ERROR, "$path.id", "Revision id does not match the YAML document id.", "backup-revision-id-mismatch")
                }
                if (parsed.sourceBytes > ProfileMetadata.MAX_BYTES) {
                    issues += issue(ProfileIssueSeverity.ERROR, "$path.yaml", "Revision exceeds the per-file size limit.", "backup-revision-file-size-limit")
                }
                if (document == null || candidateIssues.any { it.severity == ProfileIssueSeverity.ERROR } ||
                    parsed.contentSha256 != candidate.ref.revision || document.id != candidate.ref.id
                ) return@forEachIndexed

                val existing = entries[candidate.ref]
                if (existing != null) {
                    if (!existing.compatible || existing.rawYaml != candidate.rawYaml) {
                        issues += issue(ProfileIssueSeverity.ERROR, path, "Existing immutable revision conflicts with the backup.", "backup-existing-revision-conflict")
                    } else {
                        alreadyPresent += candidate.ref
                        validated[candidate.ref] = candidate
                    }
                    return@forEachIndexed
                }
                val bytes = sourceBytes
                newBytes = if (Long.MAX_VALUE - newBytes < bytes) Long.MAX_VALUE else newBytes + bytes
                newById[candidate.ref.id] = newById.getOrDefault(candidate.ref.id, 0) + 1
                validated[candidate.ref] = candidate
            }

        if (payloadBytes > ProfileMetadata.MAX_IMPORTED_BYTES) {
            issues += issue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Profile backup exceeds the aggregate byte limit.", "backup-aggregate-byte-limit")
        }

        val toImport = validated.keys.filterNot { it in alreadyPresent }.sortedRefs()
        if (importedDiskCount > ProfileMetadata.MAX_IMPORTED_REVISIONS - toImport.size) {
            issues += issue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Restored catalog would exceed the revision limit.", "backup-restored-catalog-revision-limit")
        }
        newById.toSortedMap().forEach { (id, count) ->
            if (importedDiskCountById.getOrDefault(id, 0) > ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID - count) {
                issues += issue(
                    ProfileIssueSeverity.ERROR,
                    "profiles.revisions",
                    "Restored profile '$id' would exceed its revision limit.",
                    "backup-restored-profile-revision-limit",
                    mapOf("id" to id),
                )
            }
        }
        if (importedDiskBytes > ProfileMetadata.MAX_IMPORTED_BYTES - newBytes) {
            issues += issue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Restored catalog would exceed the aggregate byte limit.", "backup-restored-catalog-byte-limit")
        }

        val available = entries.filterValues { it.compatible }.keys + validated.keys
        val selection = restorableSelection(payload, available)
        if (selection == null) {
            issues += issue(ProfileIssueSeverity.ERROR, "profiles.selection", "Referenced immutable revision is missing or incompatible.", "backup-referenced-revision-unavailable")
        } else if (selection != payload.selection) {
            issues += repinIssue(payload.selection as ProfileSelection.Pinned, entries.getValue((selection as ProfileSelection.Pinned).ref))
                .copy(path = "profiles.selection")
        }
        (payload.lastKnownGood as? ProfileSelection.Pinned)?.ref?.takeIf { it !in available }?.let {
            issues += ProfileIssue(
                ProfileIssueSeverity.WARNING,
                "profiles.last_known_good",
                "The source rollback revision is not present in this core; the destination's current selection remains the rollback target.",
                ProfilePresentation("backup-source-rollback-unavailable"),
            )
        }
        payload.active?.takeIf { it !in available }?.let {
            issues += ProfileIssue(
                ProfileIssueSeverity.WARNING,
                "profiles.active",
                "The source active revision is not present in this core; it will not be activated.",
                ProfilePresentation("backup-source-active-unavailable"),
            )
        }
        val activation = readActivation()
        if (activation.phase == ProfileActivationPhase.PENDING || activation.phase == ProfileActivationPhase.APPLYING) {
            issues += issue(ProfileIssueSeverity.ERROR, "profiles.activation", "A profile activation is already in progress.", "activation-in-progress")
        }
        return ProfileBackupRestorePlan(
            status = status,
            expectedCatalogRevision = status.catalogRevision,
            valid = issues.none { it.severity == ProfileIssueSeverity.ERROR },
            toImport = toImport,
            alreadyPresent = alreadyPresent.sortedRefs(),
            issues = issues,
            restartRequired = selection != null && selection != readSelection(),
            selection = selection,
        )
    }

    /**
     * The selection a restore stages, or null when the backup's selection cannot be restored here.
     *
     * A pin this core carries, or that the backup carries, is restored exactly. A pin of a bundled
     * revision this release does not ship follows the id's current bundled revision through the health
     * gate, the same rule [retiredBundledSuccessor] applies at startup: the source pinned the profile,
     * and a backup cannot carry a bundled revision, because only imported revisions are exported. The
     * usual way to hold such a pin is an activation that rolled back onto a retained snapshot.
     *
     * The source's hold on a rejected successor is not in the backup, so it cannot be honoured; the
     * destination's own previous selection stays the rollback target, and a second unhealthy start
     * rolls back away from the successor. A pin whose id the backup carries imported revisions of
     * belongs to an imported lineage and is never swapped for the stock profile.
     */
    private fun restorableSelection(payload: ProfileBackup, available: Set<ProfileRef>): ProfileSelection? {
        val pinned = payload.selection as? ProfileSelection.Pinned ?: return payload.selection
        if (pinned.ref in available) return pinned
        if (payload.revisions.any { it.ref.id == pinned.ref.id }) return null
        return currentBundledAsset(pinned)?.let { ProfileSelection.Pinned(it.ref) }
    }

    private fun rejectedBackupRestore(
        status: ProfileStatus,
        issues: List<ProfileIssue>,
        alreadyPresent: List<ProfileRef> = emptyList(),
    ) = ProfileBackupRestoreResult(
        outcome = ProfileBackupRestoreOutcome.REJECTED,
        status = status,
        imported = emptyList(),
        alreadyPresent = alreadyPresent,
        issues = issues,
        selectionStaged = false,
        restartRequired = false,
        message = "Profile catalog restore was rejected before mutation.",
        presentation = ProfilePresentation("backup-restore-rejected-before-mutation"),
    )

    private fun Iterable<ProfileRef>.sortedRefs(): List<ProfileRef> =
        sortedWith(compareBy<ProfileRef>({ it.id }, { it.revision }))

    private fun resolveSelection(selection: ProfileSelection): SelectionResolution = when (selection) {
        ProfileSelection.Auto -> autoResolve()
        is ProfileSelection.Pinned -> {
            val entry = entries[selection.ref]
            val activation = entry?.let(::activationIssues).orEmpty()
            when {
                entry == null -> SelectionResolution(null, listOf(issue(ProfileIssueSeverity.ERROR, "selection", "Pinned revision is missing.", "pinned-revision-missing")))
                !entry.compatible -> SelectionResolution(
                    null,
                    entry.issues.ifEmpty { listOf(issue(ProfileIssueSeverity.ERROR, "selection", "Pinned revision is incompatible with this core.", "pinned-revision-incompatible")) },
                )
                activation.isNotEmpty() -> SelectionResolution(null, activation)
                else -> SelectionResolution(entry, emptyList())
            }
        }
    }

    private fun autoResolve(): SelectionResolution {
        val bundled = entries.values.filter { it.origin == ProfileOrigin.BUNDLED && !it.rollbackOnly }
        val generic = bundled.singleOrNull { it.isBundledFallback() }
        val bundledMatches = bundled.filter { it.compatible && it.document?.matches(facts) == true }
        val chosenBundled = highestUnique(bundledMatches)
        if (chosenBundled.entry != null) return chosenBundled
        if (bundledMatches.isNotEmpty()) return SelectionResolution(generic, chosenBundled.issues)
        // Local/community revisions are never activated from a fingerprint alone. Their match block is
        // preview information; an administrator must pin the immutable revision explicitly.
        return if (generic != null) SelectionResolution(generic, emptyList())
        else SelectionResolution(null, listOf(issue(ProfileIssueSeverity.ERROR, "catalog", "Bundled generic fallback is missing.", "bundled-generic-fallback-missing")))
    }

    private fun highestUnique(candidates: List<StoredProfile>): SelectionResolution {
        if (candidates.isEmpty()) return SelectionResolution(null, emptyList())
        val topGroupPriority = candidates.maxOf { it.document?.matchedGroupPriority(facts) ?: -1 }
        val groupHighest = candidates.filter { (it.document?.matchedGroupPriority(facts) ?: -1) == topGroupPriority }
        val topProfilePriority = groupHighest.maxOf { it.document?.match?.priority ?: -1 }
        val highest = groupHighest.filter { it.document?.match?.priority == topProfilePriority }
        return if (highest.size == 1) SelectionResolution(highest.single(), emptyList())
        else {
            val ids = highest.joinToString { it.ref.id }
            SelectionResolution(
                null,
                listOf(
                    issue(
                        ProfileIssueSeverity.ERROR,
                        "match",
                        "Ambiguous automatic match at branch priority $topGroupPriority: $ids.",
                        "ambiguous-automatic-match",
                        mapOf("priority" to topGroupPriority.toString(), "ids" to ids),
                    ),
                ),
            )
        }
    }

    private fun resolved(selection: ProfileSelection, generation: Long?, extraIssues: List<ProfileIssue> = emptyList()): ResolvedProfile {
        val resolution = resolveSelection(selection)
        val entry = resolution.entry ?: entries.values.firstOrNull { it.isBundledFallback() }
        if (entry == null) {
            return ResolvedProfile(
                profile = EmergencyDeviceProfile,
                summary = emergencySummary(active = true),
                activationGeneration = null,
                issues = catalogIssues + resolution.issues + extraIssues + ProfileIssue(
                    ProfileIssueSeverity.ERROR,
                    "catalog",
                    "Using the capability-empty emergency profile because the bundled fallback is unavailable.",
                    ProfilePresentation("emergency-profile-in-use"),
                ),
            )
        }
        return ResolvedProfile(
            profile = DataDeviceProfile(
                requireNotNull(entry.document),
                facts.productVersion,
                revision = entry.ref.revision,
                trustedBundledContent = entry.origin == ProfileOrigin.BUNDLED,
            ),
            summary = summary(entry, active = true, selected = true),
            activationGeneration = generation,
            issues = catalogIssues + resolution.issues + extraIssues,
        )
    }

    /**
     * [excluding] is the revision this start was about to apply. When the applying state could not be
     * persisted there is no activation generation and therefore no health gate, so the recovery must not
     * land on that revision by another route: on hardware whose automatic match *is* that revision,
     * an unexcluded rollback resolves straight back to it and runs it unproven.
     */
    private fun rollbackInvalidPending(
        state: ProfileActivationState,
        problems: List<ProfileIssue>,
        preferred: ProfileSelection? = state.previous,
        excluding: ProfileRef? = null,
    ): ResolvedProfile {
        val rollback = compatibleRollbackTarget(preferred, excluding = excluding)
        val persisted = persistRollback(
            rollback,
            "Selected profile could not be resolved; rolled back.",
            ProfilePresentation("activation-rolled-back-unresolved"),
        )
        return resolved(
            rollback,
            generation = null,
            extraIssues = problems + issue(
                ProfileIssueSeverity.ERROR,
                "activation",
                if (persisted) "Selected profile could not be activated; the previous selection was restored."
                else "Selected profile could not be activated; using the previous selection for this run, but the rollback could not be persisted.",
                if (persisted) "activation-unresolved-selection-restored" else "activation-unresolved-rollback-persist-failed",
            ),
        )
    }

    private fun recoverInvalidActive(
        invalid: ProfileSelection.Pinned,
        problems: List<ProfileIssue>,
    ): ResolvedProfile {
        val rollback = compatibleRollbackTarget(readLastKnownGood(), excluding = invalid.ref)
        val lkg = readLastKnownGood()
        val keepLkg = lkg?.takeIf { resolveSelection(it).entry != null }
        val persisted = persistRollback(
            rollback,
            "Active profile became incompatible after a core change; restored the last known good selection.",
            ProfilePresentation("activation-rolled-back-incompatible"),
            LastKnownGoodRollbackMutation.Set(keepLkg),
        )
        return resolved(
            rollback,
            generation = null,
            extraIssues = problems + issue(
                ProfileIssueSeverity.ERROR,
                "activation",
                if (persisted) {
                    "Profile ${invalid.ref.id}@${invalid.ref.revision.take(12)} is incompatible; last known good selection restored."
                } else {
                    "Profile ${invalid.ref.id}@${invalid.ref.revision.take(12)} is incompatible; using the rollback selection for this run, but recovery could not be persisted."
                },
                if (persisted) "activation-incompatible-selection-restored" else "activation-incompatible-recovery-persist-failed",
                mapOf("id" to invalid.ref.id, "revision" to invalid.ref.revision.take(12)),
            ),
        )
    }

    /**
     * The origin of a pinned selection. The preference store records it whenever a selection is staged,
     * proven healthy or rolled back, and that record wins in both directions. State written by an older
     * build has no record: a stored entry then answers for itself (a retained rollback snapshot is
     * bundled, an imported file is imported), and a revision stored nowhere counts as imported when an
     * imported store for its id exists, so a lost imported override of a bundled id is never mistaken
     * for a bundled pin.
     */
    /**
     * Writes the inferred origin of a pin carried over from a build that did not record one, once, at
     * the first startup that can still see the evidence. The imported store or the retained snapshot the
     * inference reads can disappear later, and an inference that changes answer between two starts would
     * let a lost imported override become the stock profile. This is a deliberate one-time migration
     * write on the startup path, not a read-path side effect: [pinnedOrigin] itself stays pure. A failed
     * write is harmless and simply retried next start, because the inference still answers meanwhile.
     *
     * The already-recorded check saves a preference write on every subsequent boot; it is not a
     * correctness barrier, because [pinnedOrigin] returns the record when there is one and rewriting it
     * would store the same value. A mutation battery removing the check leaves every test green, which
     * is the evidence for that claim rather than an untested assumption.
     */
    private fun backfillLegacySelectionOrigin() {
        if (readSelectionOrigin() != null) return
        val pinned = readSelection() as? ProfileSelection.Pinned ?: return
        preferences.put(KEY_SELECTION_ORIGIN to encodeOrigin(pinnedOrigin(pinned)))
    }

    private fun pinnedOrigin(pinned: ProfileSelection.Pinned): ProfileOrigin =
        readSelectionOrigin()
            ?: entries[pinned.ref]?.origin
            ?: if (File(importedDir, pinned.ref.id).exists()) ProfileOrigin.IMPORTED else ProfileOrigin.BUNDLED

    /** The current bundled asset for a pinned profile's id when it is not the pinned revision itself. */
    private fun currentBundledAsset(pinned: ProfileSelection.Pinned): StoredProfile? =
        entries.values
            .singleOrNull { it.origin == ProfileOrigin.BUNDLED && !it.rollbackOnly && it.ref.id == pinned.ref.id }
            ?.takeIf { it.ref != pinned.ref }

    /**
     * The current bundled revision of a pinned profile whose pinned revision this release retired.
     *
     * A bundled revision is the SHA-256 of its YAML, so a release that edits a bundled profile mints a
     * new revision and ships without the old one. The administrator pinned the *profile*; the retired
     * revision is not something they could have kept. Only a pin of bundled origin qualifies, and the
     * current asset for its id must differ from the pin: a pin whose revision is still the shipped
     * asset is left alone, while a pin that now resolves only through a retained rollback snapshot, or
     * not at all, follows the asset. Retained snapshots of older revisions are never candidates. The
     * current asset is withheld while it is the recorded rejected successor: the last APPLYING rollback
     * found it unhealthy, or the administrator explicitly selected a retained revision instead of it.
     * A newer asset is offered as soon as it ships; [select] rewrites the record with every explicit
     * choice, and a re-pin to a newer asset clears it.
     */
    private fun retiredBundledSuccessor(pinned: ProfileSelection.Pinned): StoredProfile? {
        if (pinnedOrigin(pinned) != ProfileOrigin.BUNDLED) return null
        return currentBundledAsset(pinned)?.takeIf { it.ref != readRejectedSuccessor() }
    }

    /**
     * The warning that explains why a pinned profile is not following its current asset: either that
     * asset was rolled back as unhealthy, or the administrator explicitly selected the retained revision.
     */
    private fun rejectedSuccessorIssue(pinned: ProfileSelection.Pinned): ProfileIssue? {
        val current = currentBundledAsset(pinned)?.takeIf { it.ref == readRejectedSuccessor() } ?: return null
        return ProfileIssue(
            ProfileIssueSeverity.WARNING,
            "selection",
            "Pinned profile '${pinned.ref.id}' is held at revision ${pinned.ref.revision.take(12)}; " +
                "the current bundled revision ${current.ref.revision.take(12)} is not applied automatically. Select it to adopt it.",
            ProfilePresentation(
                "pinned-successor-held",
                mapOf(
                    "id" to pinned.ref.id,
                    "retired_revision" to pinned.ref.revision.take(12),
                    "current_revision" to current.ref.revision.take(12),
                ),
            ),
        )
    }

    /**
     * The current bundled asset a selection is deliberately held away from.
     *
     * A retained rollback snapshot is only ever the selection because somebody put the panel there: the
     * administrator chose that revision, or an activation of the current asset failed and the panel was
     * rolled back onto it. Either way the re-pin path must not move it forward again until a newer
     * revision ships. Deriving the hold from the selection, rather than recording it at each site that
     * causes one, is what keeps it correct through rollback, abort and staged-activation recovery.
     */
    private fun holdFor(selection: ProfileSelection): StoredProfile? {
        val pinned = selection as? ProfileSelection.Pinned ?: return null
        val entry = entries[pinned.ref] ?: return null
        if (entry.origin != ProfileOrigin.BUNDLED || !entry.rollbackOnly) return null
        return currentBundledAsset(pinned)
    }

    /**
     * One selection's complete persisted identity: the selection, the origin that decides whether it may
     * follow a bundled successor, and the asset it is held away from. Every write of the selection writes
     * all three together, so no path can leave one of them describing a selection that is no longer there.
     */
    private fun selectionState(selection: ProfileSelection): Array<Pair<String, Any?>> = arrayOf(
        KEY_SELECTION to encode(selection),
        KEY_SELECTION_ORIGIN to encodeOrigin(originOf(selection)),
        KEY_REJECTED_SUCCESSOR to holdFor(selection)?.let { encode(ProfileSelection.Pinned(it.ref)) },
    )

    private fun repinIssue(retired: ProfileSelection.Pinned, successor: StoredProfile) = ProfileIssue(
        ProfileIssueSeverity.WARNING,
        "selection",
        "Pinned profile '${retired.ref.id}' revision ${retired.ref.revision.take(12)} was retired by this release; " +
            "following its current bundled revision ${successor.ref.revision.take(12)}.",
        ProfilePresentation(
            "pinned-revision-retired",
            mapOf(
                "id" to retired.ref.id,
                "retired_revision" to retired.ref.revision.take(12),
                "current_revision" to successor.ref.revision.take(12),
            ),
        ),
    )

    /**
     * Re-pins an active or rolled-back selection to the bundled successor through the same health gate
     * as an automatic bundled revision change. The rollback target is the retired revision itself when
     * its rollback snapshot survived, otherwise the first of last known good, automatic matching and
     * the bundled generic profile that does not resolve to the successor; last known good is left for
     * [markActivationHealthy] to prove.
     */
    private fun repinRetiredBundled(
        retired: ProfileSelection.Pinned,
        successor: StoredProfile,
        state: ProfileActivationState,
    ): ResolvedProfile {
        val target = ProfileSelection.Pinned(successor.ref)
        val issue = repinIssue(retired, successor)
        val rollback = compatibleRollbackTarget(retired, excluding = successor.ref)
        val generation = maxOf(state.generation, preferences.getLong(KEY_GENERATION, 0L)) + 1
        val persisted = preferences.put(
            *selectionState(target),
            KEY_PHASE to ProfileActivationPhase.APPLYING.name,
            KEY_GENERATION to generation,
            KEY_PREVIOUS to encode(rollback),
            KEY_DESIRED to encode(target),
            KEY_MESSAGE to "Applying the current bundled revision of the pinned profile.",
            KEY_PRESENTATION_CODE to "activation-applying-bundled-revision",
            KEY_CATALOG_REVISION to catalogRevision() + 1,
        )
        return if (persisted) {
            resolved(target, generation, listOf(issue))
        } else {
            // Nothing recorded that this successor is being tried, so nothing would roll it back if it
            // failed. Keep running what the panel already had and retry the re-pin after a restart.
            resolved(
                rollback,
                null,
                listOf(
                    issue,
                    ProfileIssue(
                        ProfileIssueSeverity.ERROR,
                        "activation",
                        "Could not persist the re-pinned bundled revision; kept ${describe(rollback)} for this run and will retry after restart.",
                        rollbackPresentation("repin-persist-failed", rollback),
                    ),
                ),
            )
        }
    }

    /**
     * The first rollback candidate that resolves to a revision other than [excluding]: the preferred
     * selection, then last known good, then automatic matching, then an explicit pin of the bundled
     * generic profile. Automatic matching is judged by what it resolves to, so hardware that would match
     * the excluded revision again cannot use it as a way back to that revision.
     */
    private fun compatibleRollbackTarget(
        preferred: ProfileSelection?,
        excluding: ProfileRef? = null,
    ): ProfileSelection {
        val generic = entries.values.firstOrNull { it.isBundledFallback() }?.let { ProfileSelection.Pinned(it.ref) }
        val candidates = listOfNotNull(preferred, readLastKnownGood(), ProfileSelection.Auto, generic).distinct()
        return candidates.firstOrNull { candidate ->
            val resolvedRef = resolveSelection(candidate).entry?.ref
            resolvedRef != null && resolvedRef != excluding
        } ?: ProfileSelection.Auto
    }

    private fun describe(selection: ProfileSelection): String = when (selection) {
        ProfileSelection.Auto -> "automatic matching"
        is ProfileSelection.Pinned -> "${selection.ref.id}@${selection.ref.revision.take(12)}"
    }

    private fun rollbackPresentation(code: String, selection: ProfileSelection): ProfilePresentation = when (selection) {
        ProfileSelection.Auto -> ProfilePresentation("$code-auto")
        is ProfileSelection.Pinned -> ProfilePresentation(
            "$code-pinned",
            mapOf("id" to selection.ref.id, "revision" to selection.ref.revision.take(12)),
        )
    }

    private fun presentationState(presentation: ProfilePresentation?): Array<Pair<String, Any?>> = arrayOf(
        KEY_PRESENTATION_CODE to presentation?.code,
        KEY_PRESENTATION_PARAM_ID to presentation?.params?.get("id"),
        KEY_PRESENTATION_PARAM_REVISION to presentation?.params?.get("revision"),
    )

    /** The rollback transition is one atomic preference commit; callers retain recovery policy and diagnostics. */
    private fun persistRollback(
        rollback: ProfileSelection,
        message: String,
        presentation: ProfilePresentation?,
        lastKnownGood: LastKnownGoodRollbackMutation = LastKnownGoodRollbackMutation.Preserve,
    ): Boolean {
        val values = mutableListOf<Pair<String, Any?>>(
            *selectionState(rollback),
            KEY_PHASE to ProfileActivationPhase.ROLLED_BACK.name,
            KEY_PREVIOUS to null,
            KEY_DESIRED to null,
            KEY_MESSAGE to message,
            *presentationState(presentation),
        )
        if (lastKnownGood is LastKnownGoodRollbackMutation.Set) {
            values += KEY_LAST_KNOWN_GOOD to lastKnownGood.selection?.let(::encode)
        }
        values += KEY_CATALOG_REVISION to catalogRevision() + 1
        return preferences.put(*values.toTypedArray())
    }

    private sealed class LastKnownGoodRollbackMutation {
        data object Preserve : LastKnownGoodRollbackMutation()
        data class Set(val selection: ProfileSelection?) : LastKnownGoodRollbackMutation()
    }

    private fun summary(entry: StoredProfile, active: Boolean, selected: Boolean): ProfileSummary = ProfileSummary(
        ref = entry.ref,
        displayName = entry.document?.displayName ?: entry.ref.id,
        origin = entry.origin,
        schema = entry.document?.schema ?: 0,
        minCoreVersion = entry.document?.requires?.minCoreVersion,
        matchesThisDevice = entry.document?.matches(facts) == true,
        active = active,
        selected = selected,
        risks = entry.document?.let { document ->
            ProfileValidator.risks(document, entry.origin == ProfileOrigin.IMPORTED && entries.values.any { it.origin == ProfileOrigin.BUNDLED && it.ref.id == entry.ref.id })
        }.orEmpty(),
        contentVersion = entry.document?.version.orEmpty(),
        author = entry.document?.metadata?.author,
        maturity = entry.document?.metadata?.maturity ?: ProfileMaturity.DRAFT,
        trustedProvenance = entry.origin == ProfileOrigin.BUNDLED,
        compatible = entry.compatible,
        issues = entry.issues,
        soc = entry.document?.soc,
        links = entry.document?.takeIf { entry.compatible }?.let { document ->
            buildList {
                document.metadata.source?.let { add(ProfileLink("Panel details", it)) }
                addAll(document.metadata.links)
            }.distinctBy { it.url }
        }.orEmpty(),
        importedAtEpochMs = entry.importedAtEpochMs.takeIf { entry.origin == ProfileOrigin.IMPORTED },
    )

    private fun statusLocked(): ProfileStatus {
        val activation = readActivation()
        val selection = readSelection()
        val activeSelection = if (activation.phase == ProfileActivationPhase.PENDING) activation.previous ?: ProfileSelection.Auto else selection
        val resolution = resolveSelection(activeSelection)
        val activeEntry = resolution.entry ?: entries.values.firstOrNull { it.isBundledFallback() }
        val requestedRef = resolveSelection(selection).entry?.ref
        val active = activeEntry?.let { summary(it, active = true, selected = it.ref == requestedRef) }
            ?: emergencySummary(active = true)
        return ProfileStatus(
            catalogRevision = catalogRevision(),
            selection = selection,
            active = active,
            activation = activation,
            issues = catalogIssues + resolution.issues,
            lastKnownGood = readLastKnownGood(),
        )
    }

    private fun diffFromActive(candidate: ProfileDocument): List<ProfileDiff> {
        val activeDocument = statusLocked().active?.ref?.let { entries[it]?.document } ?: return emptyList()
        val before = flatten(activeDocument.toYamlMap())
        val after = flatten(candidate.toYamlMap())
        return (before.keys + after.keys).toSortedSet().mapNotNull { path ->
            if (before[path] == after[path]) null else ProfileDiff(path, before[path], after[path])
        }.take(MAX_DIFFS)
    }

    private fun flatten(value: Any?, path: String = "", output: MutableMap<String, String?> = linkedMapOf()): Map<String, String?> {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) -> flatten(child, if (path.isEmpty()) key.toString() else "$path.$key", output) }
            is List<*> -> value.forEachIndexed { index, child -> flatten(child, "$path[$index]", output) }
            else -> output[path] = value?.toString()
        }
        return output
    }

    private fun ensureHydrated() {
        if (!fullyHydrated) reload()
    }

    /**
     * Shared catalog-load scaffold: bounded cost accounting, the bundled-asset pass, the missing-generic
     * fallback guard, and the atomic `entries`/`catalogIssues` publish. [loadExtras] contributes the
     * store-specific revisions (a startup subset or the full imported/rollback catalog) into the same
     * working map before it is published; [markHydrated] flags a full reload so [ensureHydrated] no
     * longer re-reads the imported catalog.
     */
    private fun loadCatalog(
        markHydrated: Boolean,
        loadExtras: (loaded: MutableMap<ProfileRef, StoredProfile>, issues: MutableList<ProfileIssue>) -> Unit,
    ) {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_CATALOG_LOAD)
        val loaded = linkedMapOf<ProfileRef, StoredProfile>()
        val issues = mutableListOf<ProfileIssue>()
        try {
            bundledLoader().forEach { (name, raw) ->
                loadEntry(raw, ProfileOrigin.BUNDLED, "assets/$BUNDLED_DIR/$name", loaded, issues)
            }
            loadExtras(loaded, issues)
            entries = loaded
            if (markHydrated) fullyHydrated = true
            if (loaded.values.none { it.isBundledFallback() }) {
                issues += ProfileIssue(
                    ProfileIssueSeverity.ERROR,
                    "catalog",
                    "Bundled generic fallback is missing or invalid; the capability-empty emergency profile will be used.",
                    ProfilePresentation("catalog-fallback-invalid-emergency-used"),
                )
            }
            catalogIssues = issues
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            val bytes = loaded.values.fold(0L) { total, entry ->
                val size = entry.sourceBytes.toLong()
                if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
            }
            cost.work(units = loaded.size.toLong(), bytes = bytes).close()
        }
    }

    private fun StoredProfile.isBundledFallback(): Boolean =
        origin == ProfileOrigin.BUNDLED && !rollbackOnly && compatible && document?.match?.fallback == true

    /**
     * Foreground startup needs every bundled asset for automatic matching, but imported revisions are
     * inert unless an administrator pins them. Read only pinned revisions that can participate in the
     * current activation or its recovery; the rest of the imported catalog is hydrated on first admin use.
     */
    private fun loadStartupCatalog() = loadCatalog(markHydrated = false) { loaded, issues ->
        val activation = readActivation()
        val required = listOfNotNull(
            readSelection(),
            activation.previous,
            activation.desired,
            readLastKnownGood(),
            readActiveRef()?.let { ProfileSelection.Pinned(it) },
        ).filterIsInstance<ProfileSelection.Pinned>()
            .mapTo(linkedSetOf()) { it.ref }
        required.forEach { ref ->
            if (ref in loaded) return@forEach
            val imported = importedFile(ref)
            val rollback = rollbackFile(ref)
            when {
                // Full hydration admits retained bundled rollback snapshots before imports. Preserve
                // that precedence if an unexpected duplicate exists in both stores.
                rollback.isFile -> loadStartupFile(
                    rollback,
                    ref,
                    ProfileOrigin.BUNDLED,
                    rollbackOnly = true,
                    loaded = loaded,
                    issues = issues,
                )
                imported.isFile -> loadStartupFile(
                    imported,
                    ref,
                    ProfileOrigin.IMPORTED,
                    rollbackOnly = false,
                    loaded = loaded,
                    issues = issues,
                )
            }
        }
    }

    private fun loadStartupFile(
        file: File,
        expected: ProfileRef,
        origin: ProfileOrigin,
        rollbackOnly: Boolean,
        loaded: MutableMap<ProfileRef, StoredProfile>,
        issues: MutableList<ProfileIssue>,
    ) {
        val raw = runCatching { readCatalogFile(file) }.getOrElse {
            issues += ProfileIssue(
                ProfileIssueSeverity.ERROR,
                "catalog[${file.path}]",
                "Could not read required profile revision.",
                ProfilePresentation("required-profile-read-failed"),
            )
            return
        }
        loadEntry(
            raw,
            origin,
            file.path,
            loaded,
            issues,
            expectedRef = expected,
            rollbackOnly = rollbackOnly,
            importedAtEpochMs = if (origin == ProfileOrigin.IMPORTED) importedAt(file) else null,
        )
    }

    private fun reload() = loadCatalog(markHydrated = true) { loaded, issues ->
        rollbackDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { directory ->
            directory.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }
        }.forEach { file ->
            val expected = expectedRef(file) ?: return@forEach
            if (expected in loaded) return@forEach
            val raw = runCatching { readCatalogFile(file) }.getOrNull() ?: return@forEach
            loadEntry(raw, ProfileOrigin.BUNDLED, file.path, loaded, issues, expectedRef = expected, rollbackOnly = true)
        }
        val importedFiles = importedDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { idDir ->
            idDir.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }
        }
        importedDiskCount = importedFiles.size
        importedDiskBytes = importedFiles.fold(0L) { total, file ->
            val length = file.length().coerceAtLeast(0)
            if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
        }
        importedDiskCountById = importedFiles.groupingBy { it.parentFile?.name.orEmpty() }.eachCount()
        val activation = readActivation()
        val protected = listOfNotNull(readSelection(), activation.previous, activation.desired, readLastKnownGood())
            .filterIsInstance<ProfileSelection.Pinned>().mapTo(mutableSetOf()) { it.ref }
        val orderedFiles = importedFiles.sortedWith(
            compareBy<File> { file -> expectedRef(file) !in protected }
                .thenBy { it.parentFile?.name.orEmpty() }
                .thenBy { it.name },
        )
        var admittedCount = 0
        var admittedBytes = 0L
        val admittedById = mutableMapOf<String, Int>()
        orderedFiles.forEach { file ->
            val expected = expectedRef(file)
            if (expected == null) {
                issues += issue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", "Imported revision path is not canonical <id>/<sha256>.yaml.", "imported-path-noncanonical")
                return@forEach
            }
            val length = file.length()
            val quotaIssue = when {
                length < 0 || length > ProfileMetadata.MAX_BYTES -> "Imported revision exceeds the per-file size limit." to "imported-file-size-limit"
                admittedCount >= ProfileMetadata.MAX_IMPORTED_REVISIONS -> "Imported revision skipped: catalog count quota exceeded." to "imported-catalog-count-quota"
                admittedById.getOrDefault(expected.id, 0) >= ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID -> "Imported revision skipped: per-profile revision quota exceeded." to "imported-profile-count-quota"
                admittedBytes + length > ProfileMetadata.MAX_IMPORTED_BYTES -> "Imported revision skipped: aggregate byte quota exceeded." to "imported-catalog-byte-quota"
                else -> null
            }
            if (quotaIssue != null) {
                issues += issue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", quotaIssue.first, quotaIssue.second)
                return@forEach
            }
            admittedCount++
            admittedBytes += length
            admittedById[expected.id] = admittedById.getOrDefault(expected.id, 0) + 1
            val raw = runCatching { readCatalogFile(file) }.getOrElse {
                issues += issue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", "Could not read imported profile.", "imported-profile-read-failed")
                return@forEach
            }
            loadEntry(raw, ProfileOrigin.IMPORTED, file.path, loaded, issues, expectedRef = expected, importedAtEpochMs = importedAt(file))
        }
    }

    private fun emergencySummary(active: Boolean) = ProfileSummary(
        ref = EMERGENCY_PROFILE_REF,
        displayName = EmergencyDeviceProfile.displayName,
        origin = ProfileOrigin.BUNDLED,
        schema = 0,
        minCoreVersion = null,
        matchesThisDevice = true,
        active = active,
        selected = true,
        risks = emptySet(),
        contentVersion = EMERGENCY_PROFILE_VERSION,
        maturity = ProfileMaturity.DRAFT,
        trustedProvenance = false,
    )

    private fun activationIssues(entry: StoredProfile): List<ProfileIssue> {
        if (entry.origin != ProfileOrigin.IMPORTED) return emptyList()
        val document = entry.document ?: return emptyList()
        if (!document.matches(facts)) {
            return listOf(ProfileIssue(
                ProfileIssueSeverity.ERROR,
                "selection.match",
                "Imported profile does not match this device's immutable build identity.",
                ProfilePresentation("activation-device-mismatch"),
            ))
        }
        return document.input.evdevButtons.mapIndexedNotNull { index, button ->
            if (button.grab && evdevInspector.isTouchscreen(button.node) == true) {
                ProfileIssue(
                    ProfileIssueSeverity.ERROR,
                    "input.evdev_buttons[$index].grab",
                    "Imported profiles cannot exclusively grab a touchscreen input device.",
                    ProfilePresentation("activation-touchscreen-grab-forbidden"),
                )
            } else {
                null
            }
        }
    }

    private fun loadEntry(
        raw: String,
        origin: ProfileOrigin,
        source: String,
        loaded: MutableMap<ProfileRef, StoredProfile>,
        issues: MutableList<ProfileIssue>,
        expectedRef: ProfileRef? = null,
        rollbackOnly: Boolean = false,
        importedAtEpochMs: Long? = null,
    ) {
        val parsed = measuredParse(raw)
        val document = parsed.document
        val validation = document?.let { measuredValidate(it, bundled = origin == ProfileOrigin.BUNDLED) }.orEmpty()
        val allIssues = parsed.issues + validation
        val computedRef = document?.let { ProfileRef(it.id, parsed.contentSha256) }
        if (origin == ProfileOrigin.BUNDLED && (document == null || allIssues.any { it.severity == ProfileIssueSeverity.ERROR })) {
            issues += allIssues.map { it.copy(path = "catalog[$source].${it.path}") }
            return
        }
        val ref = expectedRef ?: computedRef ?: return
        if (expectedRef != null && parsed.contentSha256 != expectedRef.revision) {
            issues += issue(ProfileIssueSeverity.ERROR, "catalog[$source]", "Imported filename does not match the content SHA-256; revision ignored.", "imported-filename-hash-mismatch")
            return
        }
        val identityIssue = if (expectedRef != null && document != null && document.id != expectedRef.id) {
            issue(
                ProfileIssueSeverity.ERROR,
                "id",
                "Document id '${document.id}' does not match storage id '${expectedRef.id}'.",
                "imported-document-id-mismatch",
                mapOf("document_id" to document.id, "storage_id" to expectedRef.id),
            )
        } else null
        val storedIssues = allIssues + listOfNotNull(identityIssue)
        if (storedIssues.any { it.severity == ProfileIssueSeverity.ERROR }) {
            issues += storedIssues.map { it.copy(path = "catalog[$source].${it.path}") }
        }
        val storedDocument = document?.takeIf { identityIssue == null }
        val previous = loaded.putIfAbsent(
            ref,
            StoredProfile(ref, origin, raw, parsed.sourceBytes, storedDocument, storedIssues, rollbackOnly, importedAtEpochMs),
        )
        if (previous != null) issues += issue(ProfileIssueSeverity.WARNING, "catalog[$source]", "Duplicate immutable revision ignored.", "duplicate-revision-ignored")
    }

    private fun measuredParse(raw: String): ProfileParseResult {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_YAML_PARSE)
        return try {
            ProfileYaml.parse(raw).also { parsed ->
                cost.work(units = 1L, bytes = parsed.sourceBytes.toLong())
            }
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.close()
        }
    }

    private fun measuredValidate(document: ProfileDocument, bundled: Boolean): List<ProfileIssue> {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_VALIDATE).work(units = 1L)
        return try {
            ProfileValidator.validate(document, coreVersion, bundled)
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.close()
        }
    }

    private fun writeImported(ref: ProfileRef, raw: String): Boolean = writeImmutable(importedFile(ref), raw)

    /**
     * The import event's time, read from the immutable revision file rather than stored beside it,
     * because the file is written once at import and never rewritten. A filesystem that reports no
     * modification time answers 0, which is indistinguishable from the epoch, so both become null
     * and the caller falls back to its stable tiebreak rather than inventing an order.
     */
    private fun importedAt(file: File): Long? = runCatching { file.lastModified() }.getOrNull()?.takeIf { it > 0L }

    private fun readBounded(file: File): String {
        if (file.length() > ProfileMetadata.MAX_BYTES) error("profile is too large")
        val bytes = ByteArrayOutputStream(minOf(file.length().coerceAtLeast(0).toInt(), ProfileMetadata.MAX_BYTES))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > ProfileMetadata.MAX_BYTES) error("profile is too large")
                bytes.write(buffer, 0, read)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun readCatalogFile(file: File): String = catalogFileReader?.invoke(file) ?: readBounded(file)

    private fun importedFile(ref: ProfileRef) = File(File(importedDir, ref.id), "${ref.revision}.yaml")

    private fun rollbackFile(ref: ProfileRef) = File(File(rollbackDir, ref.id), "${ref.revision}.yaml")

    private fun writeRollbackSnapshot(ref: ProfileRef): Boolean {
        val entry = entries[ref] ?: return false
        // Imported revisions already have durable immutable storage and must retain their untrusted
        // origin on reload. Only bundled assets need a private snapshot before an app update removes them.
        if (entry.origin == ProfileOrigin.IMPORTED) return true
        return writeImmutable(rollbackFile(ref), entry.rawYaml)
    }

    private fun pruneRollbackSnapshots(activeRef: ProfileRef) {
        val state = readActivation()
        val keep = listOfNotNull(
            activeRef,
            readActiveRef(),
            (readLastKnownGood() as? ProfileSelection.Pinned)?.ref,
            (state.previous as? ProfileSelection.Pinned)?.ref,
            (state.desired as? ProfileSelection.Pinned)?.ref,
        ).toSet()
        rollbackDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { directory ->
            directory.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }.forEach { file ->
                if (expectedRef(file) !in keep) deleteImmutable(file)
            }
        }
    }

    private fun writeImmutable(target: File, raw: String): Boolean = runCatching {
        val parent = target.parentFile ?: error("revision has no parent directory")
        if (!createDirectoriesDurably(parent)) error("could not create revision directory")
        if (target.exists()) {
            if (readBounded(target) != raw) return@runCatching false
            revisionPersistence.syncDirectory(parent)
            return@runCatching true
        }
        val staging = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            require(bytes.size <= ProfileMetadata.MAX_BYTES)
            revisionPersistence.writeAndSync(staging, bytes)
            if (!revisionPersistence.atomicRename(staging, target)) error("atomic rename failed")
            revisionPersistence.syncDirectory(parent)
        } finally {
            if (staging.exists()) revisionPersistence.delete(staging)
        }
        true
    }.getOrDefault(false)

    private fun createDirectoriesDurably(directory: File): Boolean {
        if (directory.isDirectory) return true
        val missing = generateSequence(directory) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList()
        missing.asReversed().forEach { candidate ->
            val parent = candidate.parentFile ?: return false
            if (!revisionPersistence.createDirectory(candidate)) return false
            revisionPersistence.syncDirectory(parent)
        }
        return directory.isDirectory
    }

    private fun deleteImmutable(file: File): Boolean = runCatching {
        val parent = file.parentFile ?: error("revision has no parent directory")
        if (!file.isFile || !revisionPersistence.delete(file)) error("revision unlink failed")
        revisionPersistence.syncDirectory(parent)
        if (parent.list().isNullOrEmpty() && revisionPersistence.delete(parent)) {
            parent.parentFile?.let(revisionPersistence::syncDirectory)
        }
        true
    }.getOrDefault(false)

    private fun expectedRef(file: File): ProfileRef? {
        val id = file.parentFile?.name ?: return null
        val revision = file.name.removeSuffix(".yaml")
        if (!Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$").matches(id) || ".." in id) return null
        if (!Regex("^[0-9a-f]{64}$").matches(revision)) return null
        return ProfileRef(id, revision)
    }

    private fun readSelection(): ProfileSelection = decode(preferences.getString(KEY_SELECTION, AUTO)) ?: ProfileSelection.Auto

    private fun readLastKnownGood(): ProfileSelection? = decode(preferences.getString(KEY_LAST_KNOWN_GOOD, ""))

    private fun readActiveRef(): ProfileRef? =
        (decode(preferences.getString(KEY_ACTIVE_REF, "")) as? ProfileSelection.Pinned)?.ref

    private fun readRejectedSuccessor(): ProfileRef? =
        (decode(preferences.getString(KEY_REJECTED_SUCCESSOR, "")) as? ProfileSelection.Pinned)?.ref

    private fun readSelectionOrigin(): ProfileOrigin? = when (preferences.getString(KEY_SELECTION_ORIGIN, "")) {
        ORIGIN_BUNDLED -> ProfileOrigin.BUNDLED
        ORIGIN_IMPORTED -> ProfileOrigin.IMPORTED
        else -> null
    }

    private fun originOf(selection: ProfileSelection): ProfileOrigin? =
        (selection as? ProfileSelection.Pinned)?.let { entries[it.ref]?.origin }

    private fun encodeOrigin(origin: ProfileOrigin?): String? = when (origin) {
        ProfileOrigin.BUNDLED -> ORIGIN_BUNDLED
        ProfileOrigin.IMPORTED -> ORIGIN_IMPORTED
        null -> null
    }

    private fun readActivation(): ProfileActivationState {
        val phase = runCatching { ProfileActivationPhase.valueOf(preferences.getString(KEY_PHASE, ProfileActivationPhase.ACTIVE.name)) }
            .getOrDefault(ProfileActivationPhase.ACTIVE)
        val message = preferences.getString(KEY_MESSAGE, "").ifBlank { null }
        val presentation = preferences.getString(KEY_PRESENTATION_CODE, "").ifBlank { null }
            ?.let { code ->
                val params = when {
                    code.endsWith("-pinned") -> mapOf(
                        "id" to preferences.getString(KEY_PRESENTATION_PARAM_ID, ""),
                        "revision" to preferences.getString(KEY_PRESENTATION_PARAM_REVISION, ""),
                    )
                    else -> emptyMap()
                }
                runCatching { ProfilePresentation(code, params) }.getOrNull()
            }
        return ProfileActivationState(
            phase = phase,
            generation = preferences.getLong(KEY_GENERATION, 0L),
            previous = decode(preferences.getString(KEY_PREVIOUS, "")),
            desired = decode(preferences.getString(KEY_DESIRED, "")),
            message = message,
            presentation = presentation.takeIf { message != null },
        )
    }

    private fun encode(selection: ProfileSelection): String = when (selection) {
        ProfileSelection.Auto -> AUTO
        is ProfileSelection.Pinned -> "${selection.ref.id}@${selection.ref.revision}"
    }

    private fun decode(encoded: String): ProfileSelection? {
        if (encoded == AUTO) return ProfileSelection.Auto
        val at = encoded.lastIndexOf('@')
        if (at <= 0) return null
        val id = encoded.substring(0, at)
        val revision = encoded.substring(at + 1)
        if (!Regex("^[a-z0-9][a-z0-9.-]{0,127}$").matches(id) || !Regex("^[0-9a-f]{64}$").matches(revision)) return null
        return ProfileSelection.Pinned(ProfileRef(id, revision))
    }

    private fun catalogRevision() = preferences.getLong(KEY_CATALOG_REVISION, 0L)

    private fun bumpCatalogRevision(): Boolean = preferences.put(KEY_CATALOG_REVISION to catalogRevision() + 1)

    private fun staleCatalog() = rejected("catalog_revision", "Catalog changed; reload and retry with the current revision.", "catalog-stale")

    /** Presentation is additive: oversized opaque evidence must never turn a compatibility issue into a throw. */
    private fun issue(
        severity: ProfileIssueSeverity,
        path: String,
        message: String,
        presentationCode: String,
        presentationParams: Map<String, String> = emptyMap(),
    ) = ProfileIssue(
        severity,
        path,
        message,
        runCatching { ProfilePresentation(presentationCode, presentationParams) }.getOrNull(),
    )

    private fun rejected(path: String, message: String, presentationCode: String) =
        rejected(path, message, ProfilePresentation(presentationCode))

    private fun rejected(path: String, message: String, presentation: ProfilePresentation) = ProfileMutation.Rejected(
        statusLocked(),
        listOf(ProfileIssue(ProfileIssueSeverity.ERROR, path, message, presentation)),
    )

    companion object {
        /**
         * A registry over this build's bundled catalogue alone: no stored revision, no snapshot and no
         * persisted selection. That is the catalogue of a successor that has not restored yet, so it plans
         * a restore exactly as that successor's first restore will plan it, and it writes nothing: the
         * store it names does not exist and its preferences live only in memory.
         */
        internal fun bundledOnly(context: Context): RuntimeProfileRegistry = RuntimeProfileRegistry(
            filesDir = File(context.applicationContext.cacheDir, "profile-restore-plan-${System.nanoTime()}"),
            preferences = TransientProfilePreferences(),
            bundledLoader = { bundledAssets(context) },
            facts = buildFacts(),
            coreVersion = BuildConfig.VERSION_NAME,
            clock = System::currentTimeMillis,
        )

        private fun buildFacts() = DeviceFacts(
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty(),
            SystemProps.get("ro.product.version"),
        )

        private fun bundledAssets(context: Context): Map<String, String> =
            context.applicationContext.assets.list(BUNDLED_DIR).orEmpty()
                .filter { it.endsWith(".yaml") || it.endsWith(".yml") }
                .sorted()
                .associateWith { name ->
                    context.applicationContext.assets.open("$BUNDLED_DIR/$name").bufferedReader().use { it.readText() }
                }

        private const val PREFS_NAME = "ha-paneld-device-profiles"
        private const val BUNDLED_DIR = "device-profiles"
        private const val AUTO = "auto"
        private const val KEY_SELECTION = "selection"
        private const val KEY_PHASE = "activation_phase"
        private const val KEY_GENERATION = "activation_generation"
        private const val KEY_PREVIOUS = "activation_previous"
        private const val KEY_DESIRED = "activation_desired"
        private const val KEY_MESSAGE = "activation_message"
        private const val KEY_PRESENTATION_CODE = "activation_presentation_code"
        private const val KEY_PRESENTATION_PARAM_ID = "activation_presentation_param_id"
        private const val KEY_PRESENTATION_PARAM_REVISION = "activation_presentation_param_revision"
        private const val KEY_CATALOG_REVISION = "catalog_revision"
        private const val KEY_LAST_KNOWN_GOOD = "last_known_good"
        private const val KEY_ACTIVE_REF = "active_ref"
        private const val KEY_SELECTION_ORIGIN = "selection_origin"
        private const val KEY_REJECTED_SUCCESSOR = "rejected_successor"
        private const val ORIGIN_BUNDLED = "bundled"
        private const val ORIGIN_IMPORTED = "imported"
        private const val MAX_DIFFS = 256
    }
}

private data class IssuedPreviewToken(val value: String, val expiresAt: Long)

private class PreviewTokenStore(
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Entry(val hash: String, val expiresAt: Long)
    private val entries = linkedMapOf<String, Entry>()

    fun issue(hash: String): IssuedPreviewToken {
        purge()
        while (entries.size >= MAX_TOKENS) entries.remove(entries.keys.first())
        val bytes = ByteArray(24).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val expires = clock() + TTL_MS
        entries[token] = Entry(hash, expires)
        return IssuedPreviewToken(token, expires)
    }

    fun consume(token: String, hash: String): Boolean {
        purge()
        val entry = entries.remove(token) ?: return false
        return entry.hash == hash && entry.expiresAt >= clock()
    }

    private fun purge() {
        val now = clock()
        entries.entries.removeAll { it.value.expiresAt < now }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_TOKENS = 32
    }
}

internal interface ProfilePreferences {
    fun getString(key: String, default: String): String
    fun getLong(key: String, default: Long): Long
    /** Null values remove keys; all changes are committed atomically. */
    fun put(vararg values: Pair<String, Any?>): Boolean
}

/** Preferences that exist only for the life of one registry; see [RuntimeProfileRegistry.bundledOnly]. */
internal class TransientProfilePreferences : ProfilePreferences {
    private val values = HashMap<String, Any>()
    override fun getString(key: String, default: String): String = values[key] as? String ?: default
    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun put(vararg values: Pair<String, Any?>): Boolean {
        values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
        return true
    }
}

private class AndroidProfilePreferences(private val preferences: SharedPreferences) : ProfilePreferences {
    override fun getString(key: String, default: String): String = preferences.getString(key, default) ?: default
    override fun getLong(key: String, default: Long): Long = preferences.getLong(key, default)
    override fun put(vararg values: Pair<String, Any?>): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                else -> error("Unsupported preference value")
            }
        }
        return editor.commit()
    }
}
