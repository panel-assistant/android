#!/usr/bin/env python3
"""Mutation definitions for the Issue #96 staged-upload discard battery.

Each mutation is one exact-text replacement in one file, applied to a throwaway checkout by
mutation-battery.sh. A mutation that does not match exactly once (after its anchor, when one is
given) is reported as absent and must be re-anchored — a mutation that cannot apply is not evidence.

Usage:
    mutations.py list                 -> one "name<TAB>suite<TAB>named_test" line per mutation
    mutations.py apply <name> <root>  -> apply in checkout <root>; exit 3 if absent/ambiguous
"""

import sys
from pathlib import Path

STORE = "app/src/main/kotlin/io/panelassistant/android/http/PendingUploadStore.kt"
ROUTES = "app/src/main/kotlin/io/panelassistant/android/http/ControlPlaneRoutes.kt"
INSTALL_JS = "app/src/main/assets/install.js"

# name -> (file, old, new, anchor, suite, named_test)
# suite is "kotlin" (focused Gradle unit tests) or "browser" (the Issue #96 browser tests).
# named_test is the single assertion-bearing test that must go red for the kill to be credited.
MUTATIONS = {
    "discarded-file-survives": (
        STORE,
        "        active = null\n        entry.file.delete()\n        return DiscardResult.DISCARDED",
        "        active = null\n        return DiscardResult.DISCARDED",
        None,
        "kotlin",
        "discardRetiresTheInspectedEntryAndDeletesItsBytes",
    ),
    "mismatch-still-discards": (
        STORE,
        "        if (ref != entry.token && ref != entry.discardId) return DiscardResult.DIFFERENT_PENDING",
        "        if (ref != entry.token && ref != entry.discardId) {\n"
        "            active = null\n"
        "            entry.file.delete()\n"
        "            return DiscardResult.DIFFERENT_PENDING\n"
        "        }",
        None,
        "kotlin",
        "aTokenScopedDiscardNeverRemovesAnEntryItDidNotInspect",
    ),
    "probe-reference-is-the-commit-token": (
        STORE,
        "?.let { PendingSummary(it.identity, it.discardId) }",
        "?.let { PendingSummary(it.identity, it.token) }",
        None,
        "kotlin",
        "theProbeReferenceCarriesNoInstallAuthority",
    ),
    "any-reference-discards": (
        STORE,
        "        if (ref != entry.token && ref != entry.discardId) return DiscardResult.DIFFERENT_PENDING\n",
        "",
        None,
        "kotlin",
        "aStaleRecoveryReferenceCannotRemoveAReplacementUpload",
    ),
    # The design under review: the operation lane is taken BEFORE the entry is claimed, so a busy
    # commit never removes the entry and there is no claim window for a discard to be lied to in.
    # This mutation swaps the order back — the entry is taken first and silently lost on busy — and
    # the held-lane route sequence (pending stays probeable and discardable through a busy answer)
    # must go red.
    "claim-before-ticket": (
        ROUTES,
        "    val progress = InstallProgress.start(\"APK\")\n"
        "    if (progress == null) {\n"
        "        call.respondText(\"\"\"{\"status\":\"busy\"}\"\"\", ContentType.Application.Json)\n"
        "        return\n"
        "    }\n"
        "    val claimed = dependencies.pending.claim(token)\n"
        "    if (claimed == null) {",
        "    val claimed = dependencies.pending.claim(token)\n"
        "    val progress = InstallProgress.start(\"APK\")\n"
        "    if (progress == null) {\n"
        "        call.respondText(\"\"\"{\"status\":\"busy\"}\"\"\", ContentType.Application.Json)\n"
        "        return\n"
        "    }\n"
        "    if (claimed == null) {",
        None,
        "kotlin",
        "apkDiscardRetiresOnlyThePendingEntryAndThePendingProbeNeverLeaksTheToken",
    ),
    "busy-lane-leaks-on-stale-claim": (
        ROUTES,
        "        InstallProgress.finish(progress, \"Nothing installed: the pending APK was discarded, replaced or expired.\")\n",
        "",
        None,
        "kotlin",
        "apkDiscardRetiresOnlyThePendingEntryAndThePendingProbeNeverLeaksTheToken",
    ),
    "discard-aborts-panel-work": (
        STORE,
        "    fun discard(ref: String): DiscardResult {\n        expireActive()",
        "    fun discard(ref: String): DiscardResult {\n        expireActive()\n        stopPanelWork()",
        None,
        "kotlin",
        "discardNeverTouchesAnArrivingBodyOrInFlightPanelWork",
    ),
    "discard-clears-receiving": (
        STORE,
        "    fun discard(ref: String): DiscardResult {\n        expireActive()",
        "    fun discard(ref: String): DiscardResult {\n        expireActive()\n        receiving = null",
        None,
        "kotlin",
        "discardNeverTouchesAnArrivingBodyOrInFlightPanelWork",
    ),
    "lease-identity-ignores-id": (
        STORE,
        "        this != null && epoch == other.epoch && id == other.id",
        "        this != null && epoch == other.epoch",
        None,
        "kotlin",
        "aReleasedLeaseCannotStageOverItsReplacement",
    ),
    "pending-summary-ignores-expiry": (
        STORE,
        "    fun pendingSummary(): PendingSummary? {\n        expireActive()\n        return active",
        "    fun pendingSummary(): PendingSummary? {\n        return active",
        None,
        "kotlin",
        "pendingSummaryGoesQuietOnExpiry",
    ),
    "probe-invents-a-pending-entry": (
        ROUTES,
        '        call.respondText("""{"pending":false}""", ContentType.Application.Json)',
        '        call.respondText("""{"pending":true}""", ContentType.Application.Json)',
        None,
        "kotlin",
        "apkDiscardRetiresOnlyThePendingEntryAndThePendingProbeNeverLeaksTheToken",
    ),
    "nothing-pending-claims-a-removal": (
        ROUTES,
        "        PendingUploadStore.DiscardResult.NOTHING_PENDING ->\n"
        '            call.respondText("""{"ok":true,"discarded":false}""", ContentType.Application.Json)',
        "        PendingUploadStore.DiscardResult.NOTHING_PENDING ->\n"
        '            call.respondText("""{"ok":true,"discarded":true}""", ContentType.Application.Json)',
        None,
        "kotlin",
        "apkDiscardRetiresOnlyThePendingEntryAndThePendingProbeNeverLeaksTheToken",
    ),
    "conflict-hidden-as-success": (
        ROUTES,
        '                """{"ok":false,"error":"different-pending"}""",\n'
        "                ContentType.Application.Json,\n"
        "                HttpStatusCode.Conflict,",
        '                """{"ok":false,"error":"different-pending"}""",\n'
        "                ContentType.Application.Json,",
        None,
        "kotlin",
        "apkDiscardRetiresOnlyThePendingEntryAndThePendingProbeNeverLeaksTheToken",
    ),
    "busy-recovery-ignores-the-probe": (
        INSTALL_JS,
        "      if (mine !== apkPreviewGeneration || !d.pending) return;",
        "      if (mine !== apkPreviewGeneration) return;",
        "function renderApkBusyRecovery",
        "browser",
        "upload-busy offers Discard only when the panel actually holds a pending entry",
    ),
    "preview-cancel-drops-its-token": (
        INSTALL_JS,
        "      body: 'token=' + encodeURIComponent(token)",
        "      body: 'token='",
        None,
        "browser",
        "APK preview offers Install and Cancel, and Cancel discards the exact token",
    ),
    "reload-recovery-never-probes": (
        INSTALL_JS,
        "  // preview token but not the panel-side file. (Issue #96)\n  apkProbePending();",
        "  // preview token but not the panel-side file. (Issue #96)",
        None,
        "browser",
        "A reload surfaces the panel-held pending upload with a probe-scoped Discard action",
    ),
    "recovery-card-drops-its-reference": (
        INSTALL_JS,
        "      '<button class=\"pbtn\" data-token=\"' + esc(d.discard) + '\" onclick=\"apkDiscard(this)\">✕ Discard pending upload</button>'",
        "      '<button class=\"pbtn\" onclick=\"apkDiscard(this)\">✕ Discard pending upload</button>'",
        None,
        "browser",
        "A reload surfaces the panel-held pending upload with a probe-scoped Discard action",
    ),
    "stale-refusal-never-repaints": (
        INSTALL_JS,
        "      apkProbePending();\n    }).catch(function (error) {\n      btn.disabled = false;",
        "    }).catch(function (error) {\n      btn.disabled = false;",
        None,
        "browser",
        "A stale recovery card cannot delete a replacement upload and repaints the truth",
    ),
    "cancel-gains-a-shield": (
        INSTALL_JS,
        "      '<button class=\"pbtn\" data-token=\"' + esc(d.token) + '\" onclick=\"apkDiscard(this)\">✕ Cancel</button>'",
        "      '<button class=\"pbtn\"' + hardenedApprovalAttrs + ' data-token=\"' + esc(d.token) + '\" onclick=\"apkDiscard(this)\">✕ Cancel</button>'",
        None,
        "kotlin",
        "protectedHtmlActionsCarryAnAlwaysVisibleAccessibleMarker",
    ),
    # The no-op control proves the battery can tell a mutation from noise: every suite must stay
    # green, and the driver reports CONTROL-OK rather than crediting a kill.
    "control-no-op": (
        STORE,
        "/** Owns the one inspected-but-uncommitted upload",
        "/** Control mutation: comment text only. Owns the one inspected-but-uncommitted upload",
        None,
        "kotlin",
        "",
    ),
}


def main() -> int:
    if len(sys.argv) >= 2 and sys.argv[1] == "list":
        for name, (_, _, _, _, suite, named) in MUTATIONS.items():
            print(f"{name}\t{suite}\t{named}")
        return 0
    if len(sys.argv) != 4 or sys.argv[1] != "apply":
        print(__doc__, file=sys.stderr)
        return 2
    name, root = sys.argv[2], Path(sys.argv[3])
    if name not in MUTATIONS:
        print(f"unknown mutation: {name}", file=sys.stderr)
        return 2
    rel, old, new, anchor, _, _ = MUTATIONS[name]
    path = root / rel
    text = path.read_text()
    start = 0
    if anchor is not None:
        start = text.find(anchor)
        if start < 0:
            print(f"MUTATION-ABSENT: anchor not found for {name}", file=sys.stderr)
            return 3
    region = text[start:]
    if region.count(old) < 1 or (anchor is None and text.count(old) != 1):
        print(f"MUTATION-ABSENT: pattern matches {text.count(old)} times for {name}", file=sys.stderr)
        return 3
    path.write_text(text[:start] + region.replace(old, new, 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
