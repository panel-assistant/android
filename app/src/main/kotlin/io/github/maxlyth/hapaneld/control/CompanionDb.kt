package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.InstallPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads + repairs the HA Companion's connection database — the `servers` table in `HomeAssistantDB`.
 *
 * A Companion `servers` row with an EMPTY `internal_url` (while `internal_ssids` matches the panel's
 * Wi-Fi) makes the Companion request a host-less URL, which HA 2026.7 (new aiohttp)
 * rejects with a full-screen **"Missing 'Host' header in request."** — blanking the dashboard on the
 * restored panels. This detects that state (a panel-health warning) and offers a one-tap repair:
 * copy the row's `external_url` into the empty `internal_url`. Backup restore applies the same policy
 * to its staged database and verifies it before the live transaction begins.
 *
 * Root-only (the DB is app-private); a safe no-op on panels without su or without the Companion. The
 * parse + decision logic is pure ([parseServers] / [needsRepair]) and unit-tested; only the
 * sqlite3-over-su calls touch the device.
 */
object CompanionDb {
    private const val TAG = "ha-paneld/companiondb"

    // ASCII Unit Separator (0x1F) — a field delimiter that can't appear in a URL, so the parse is robust
    // against NULLs and any '|' in a value (unlike sqlite3's default pipe separator). Built with char(31).
    private const val US = '\u001f'

    // Read the servers table as `id US internal US external`, one row per line, NULLs coalesced to "".
    private const val DUMP_SQL =
        "SELECT id||char(31)||coalesce(internal_url,'')||char(31)||coalesce(external_url,'') FROM servers;"

    // Copy external_url into every blank internal_url. Mirrors [needsRepair] so a re-read confirms zero left.
    internal const val INTERNAL_URL_REPAIR_SQL =
        "UPDATE servers SET internal_url=external_url " +
            "WHERE (internal_url IS NULL OR trim(internal_url)='') " +
            "AND external_url IS NOT NULL AND trim(external_url)<>'';"

    internal const val INTERNAL_URL_REPAIR_REMAINING_SQL =
        "SELECT count(*) FROM servers " +
            "WHERE (internal_url IS NULL OR trim(internal_url)='') " +
            "AND external_url IS NOT NULL AND trim(external_url)<>'';"

    data class ServerRow(val id: String, val internalUrl: String, val externalUrl: String)

    /** Result of a health read: whether any row needs repair and how many. */
    data class UrlStatus(val needsRepair: Boolean, val affected: Int)

    /** Typed repair outcome; [message] remains the exact legacy English status. */
    data class CompanionUrlRepairResult(
        val message: String,
        val presentation: InstallPresentation? = null,
    )

    /** One servers-table read projected for both header fallback and repair health. */
    internal data class ServerObservation(
        val probeSucceeded: Boolean,
        val preferredUrl: String?,
        val status: UrlStatus,
    ) {
        companion object {
            /** Companion is absent, or its servers table was read successfully and has no rows. */
            val EMPTY = ServerObservation(
                probeSucceeded = true,
                preferredUrl = null,
                status = UrlStatus(needsRepair = false, affected = 0),
            )

            /** Companion is installed but its servers table could not be read. */
            val UNKNOWN = ServerObservation(
                probeSucceeded = false,
                preferredUrl = null,
                status = UrlStatus(needsRepair = false, affected = 0),
            )
        }
    }

    private fun isBlank(u: String): Boolean = u.isBlank() || u == "null"

    /** A row needs repair when its internal URL is blank but a usable external URL exists to copy. */
    fun needsRepair(r: ServerRow): Boolean = isBlank(r.internalUrl) && !isBlank(r.externalUrl)

    /** Whether the internal-URL health warning applies: only when the Companion is the app that will
     *  actually render the dashboard — an explicitly configured Companion `dashboard_package`. With the
     *  automatic built-in renderer or
     *  another explicit dashboard app a blank `internal_url` can't blank the panel (the built-in
     *  renderer's login borrow already falls back to `external_url`), so the warning stays quiet. */
    fun warningApplies(dashboardPackage: String): Boolean =
        dashboardPackage in CompanionInstaller.SUPPORTED_PACKAGES

    /** Which internal-URL warning (if any) the render surfaces should show, given the active dashboard
     *  package, the last servers observation and whether a privileged re-read is possible. */
    internal sealed interface Warning {
        /** [affected] rows have a blank internal URL — offer the repair. */
        data class NeedsRepair(val affected: Int) : Warning

        /** The servers table couldn't be inspected but a privileged retry exists. */
        object ProbeFailed : Warning
    }

    /** Pure decision shared by the status endpoint and the dashboard/Install banner: the two surfaces
     *  render this with different copy, but they must agree on WHICH warning applies (the selection was
     *  previously copy-pasted into both). Returns null when no companion warning is warranted. */
    internal fun warning(
        dashboardPackage: String,
        observation: ServerObservation?,
        directSuReady: Boolean,
    ): Warning? {
        if (!warningApplies(dashboardPackage)) return null
        val status = observation?.status ?: return null
        return when {
            status.needsRepair -> Warning.NeedsRepair(status.affected)
            !observation.probeSucceeded && directSuReady -> Warning.ProbeFailed
            else -> null
        }
    }

    /** Parse the `id US internal US external` lines emitted by [DUMP_SQL]. Tolerant of blank lines and
     *  short rows (a missing trailing field → ""). A line with no id is skipped. */
    fun parseServers(output: String): List<ServerRow> =
        output.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val f = line.split(US)
            if (f[0].isBlank()) null else ServerRow(f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" })
        }.toList()

    private fun dbPath(pkg: String) = "/data/data/$pkg/databases/HomeAssistantDB"

    // ---- login borrow: reuse the Companion's sign-in for the built-in renderer ----
    // Same panel, same HA user; HA does not rotate refresh tokens on use, so both apps refresh
    // independently off the same token and the Companion keeps working as a fallback (mechanism in
    // deployed installations; the DB columns are plain text).

    /** The client_id the Companion's refresh token was issued to — a refresh only works with it. */
    const val COMPANION_CLIENT_ID = "https://home-assistant.io/android"

    private const val LOGIN_SQL =
        "SELECT id||char(31)||coalesce(internal_url,'')||char(31)||coalesce(external_url,'')||char(31)||" +
            "coalesce(refresh_token,'')||char(31)||coalesce(access_token,'')||char(31)||coalesce(token_expiration,'') FROM servers;"

    data class LoginRow(
        val id: String,
        val internalUrl: String,
        val externalUrl: String,
        val refreshToken: String,
        val accessToken: String,
        val expirySec: Long,           // epoch SECONDS (the Companion's unit; DashboardAuth's too)
    ) {
        val url: String get() = (internalUrl.takeUnless(::isBlank) ?: externalUrl).trimEnd('/')
    }

    /** Parse the `LOGIN_SQL` lines. Same tolerance rules as [parseServers]. */
    fun parseLogins(output: String): List<LoginRow> =
        output.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val f = line.split(US)
            if (f[0].isBlank()) null else LoginRow(
                f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" },
                f.getOrElse(3) { "" }, f.getOrElse(4) { "" },
                f.getOrElse(5) { "" }.toLongOrNull() ?: 0L,
            )
        }.toList()

    /** The Companion's active server id from `session_0.xml` (`<int name="active_server" value="N"/>`),
     *  or null when unreadable — multi-server installs must borrow the server the panel actually shows. */
    fun parseActiveServer(xml: String?): String? =
        xml?.let { Regex("\"active_server\"\\s+value=\"(\\d+)\"").find(it)?.groupValues?.get(1) }

    /** The Companion's "Page zoom" App-Settings value from `themes_0.xml`
     *  (`<int name="page_zoom_level" value="N"/>`), or null when unset — the Companion's own default is
     *  100. This is the direct analog of ha-paneld's `dashboard_zoom`, so a panel switched from the
     *  Companion can keep the zoom the user chose there. */
    fun parsePageZoom(xml: String?): Int? =
        xml?.let { Regex("\"page_zoom_level\"\\s+value=\"(\\d+)\"").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Read the Companion's chosen page-zoom % via root, or null (no root / no Companion / unset).
     *  Call OFF the main thread. */
    fun readPageZoom(context: Context, root: RootShell): Int? {
        val pkg = CompanionInstaller.installedPkg(context) ?: return null
        return parsePageZoom(root.runOutput("""cat "/data/data/$pkg/shared_prefs/themes_0.xml" 2>/dev/null"""))
    }

    /** Pick the row to borrow: the active server when known, else the first signed-in row (has a
     *  refresh token and a URL). Null = nothing borrowable. */
    fun pickLogin(rows: List<LoginRow>, activeId: String?): LoginRow? {
        val signed = rows.filter { it.refreshToken.isNotBlank() && !isBlank(it.url) }
        return signed.firstOrNull { it.id == activeId } ?: signed.firstOrNull()
    }

    /** Read the borrowable login via root, or null (no root / no Companion / no signed-in server).
     *  Read-only immutable open — never leaves root-owned WAL files. Call OFF the main thread. */
    fun readLogin(context: Context, root: RootShell): LoginRow? {
        val pkg = CompanionInstaller.installedPkg(context) ?: return null
        val db = dbPath(pkg)
        val out = root.runOutput("""sqlite3 "file:$db?immutable=1" "$LOGIN_SQL" 2>/dev/null""") ?: return null
        val active = parseActiveServer(root.runOutput("""cat "/data/data/$pkg/shared_prefs/session_0.xml" 2>/dev/null"""))
        return pickLogin(parseLogins(out), active)
    }

    /** Read the Companion `servers` rows via root sqlite3, or null when unavailable (no root / no
     *  Companion / no DB / no sqlite3). Opens the file as an **immutable** URI: read-only, WAL ignored,
     *  and crucially it never creates the `-wal`/`-shm` files — so a root read can't leave root-owned
     *  sidecar files the app then can't open. Safe while the Companion is running. (Android's bundled
     *  sqlite3 — 3.19 on PX30 — lacks the `-readonly` flag but supports `file:…?immutable=1`.) */
    private fun readServers(context: Context, root: RootShell): List<ServerRow>? {
        val pkg = CompanionInstaller.installedPkg(context) ?: return null
        return readServers(pkg, root)
    }

    private fun readServers(pkg: String, root: RootShell): List<ServerRow>? {
        val db = dbPath(pkg)
        val out = root.runOutput("""sqlite3 "file:$db?immutable=1" "$DUMP_SQL" 2>/dev/null""") ?: return null
        return parseServers(out)
    }

    internal fun observeServers(rows: List<ServerRow>?): ServerObservation {
        rows ?: return ServerObservation.UNKNOWN
        val preferredUrl = rows.firstNotNullOfOrNull { row ->
            (row.internalUrl.takeUnless(::isBlank) ?: row.externalUrl.takeUnless(::isBlank))
                ?.trimEnd('/')
        }
        val affected = rows.count(::needsRepair)
        return ServerObservation(
            probeSucceeded = true,
            preferredUrl = preferredUrl,
            status = UrlStatus(needsRepair = affected > 0, affected = affected),
        )
    }

    /** Keep the last truthful payload when a refresh is unreadable, without claiming it was fresh. */
    internal fun retainLastKnownServerObservation(
        previous: ServerObservation?,
        observed: ServerObservation,
    ): ServerObservation = when {
        observed.probeSucceeded -> observed
        previous == null -> observed
        else -> previous.copy(probeSucceeded = false)
    }

    /**
     * One root SQLite read for every read-only servers-table projection. Call off the main thread.
     * Absence is confirmed empty; an installed Companion whose table is unreadable remains unknown.
     */
    internal fun observeServers(context: Context, root: RootShell): ServerObservation {
        val pkg = CompanionInstaller.installedPkg(context) ?: return ServerObservation.EMPTY
        return observeServers(readServers(pkg, root))
    }

    /**
     * Repair every blank `internal_url` by copying the row's `external_url`. Force-stops the Companion
     * first (so Room isn't mid-write), checkpoints + drops the WAL so sqlite doesn't leave root-owned
     * `-wal`/`-shm` the app can't read, restores the DB owner + SELinux context, then relaunches the
     * Companion. Returns a short human status. Root-only; call OFF the main thread.
     */
    suspend fun repairInternalUrl(context: Context, root: RootShell): String =
        repairInternalUrlResult(context, root).message

    /** Locale-neutral companion to [repairInternalUrl]; finite success metadata is selected at source. */
    suspend fun repairInternalUrlResult(
        context: Context,
        root: RootShell,
    ): CompanionUrlRepairResult = withContext(Dispatchers.IO) {
        val pkg = CompanionInstaller.installedPkg(context)
            ?: return@withContext CompanionUrlRepairResult("no HA Companion app installed")
        val before = readServers(context, root)
            ?: return@withContext CompanionUrlRepairResult("cannot read the Companion database (needs root)")
        val bad = before.count(::needsRepair)
        if (bad == 0) {
            return@withContext CompanionUrlRepairResult("nothing to repair — every server already has an internal URL")
        }

        val db = dbPath(pkg)
        // One privileged operation: stop → repair → checkpoint+drop WAL → restore owner/context → relaunch.
        // Running under su means the relaunch (monkey) is in the root domain, so BAL doesn't gate it.
        val script = buildString {
            append("am force-stop $pkg; ")
            append("""U=$(stat -c %u "/data/data/$pkg" 2>/dev/null); """)
            append("""sqlite3 "$db" "$INTERNAL_URL_REPAIR_SQL PRAGMA wal_checkpoint(TRUNCATE);" 2>&1; """)
            append("""rm -f "$db-wal" "$db-shm" 2>/dev/null; """)
            append("""[ -n "${'$'}U" ] && { chown "${'$'}U:${'$'}U" "$db" 2>/dev/null; restorecon "$db" 2>/dev/null; }; """)
            append("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo ok")
        }
        val out = root.runOutput(script)
            ?: return@withContext CompanionUrlRepairResult("repair command failed (needs root)")
        if (!out.contains("ok")) Log.w(TAG, "repair script output: ${out.take(200)}")

        val remaining = readServers(context, root)?.count(::needsRepair) ?: -1
        Log.i(TAG, "internal_url repair: $bad row(s) fixed, $remaining remaining")
        when {
            remaining == 0 -> CompanionUrlRepairResult(
                "repaired $bad server${if (bad == 1) "" else "s"} — internal URL set to the external URL; Companion relaunched",
                InstallPresentation("companion-urls-repaired", mapOf("count" to bad.toString())),
            )
            remaining < 0 -> CompanionUrlRepairResult("repair ran ($bad row(s)); could not re-verify the database")
            else -> CompanionUrlRepairResult("repair incomplete — $remaining server(s) still have no internal URL")
        }
    }
}
