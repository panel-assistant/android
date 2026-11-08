package io.panelassistant.android.control

import android.content.Context
import android.util.Log
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.CompanionInstaller

/**
 * Reads the HA Companion's connection database — the `servers` table in `HomeAssistantDB`.
 *
 * A Companion `servers` row with an EMPTY `internal_url` (while `internal_ssids` matches the panel's
 * Wi-Fi) makes the Companion request a host-less URL, which HA 2026.7 (new aiohttp)
 * rejects with a full-screen **"Missing 'Host' header in request."** — blanking the dashboard on the
 * restored panels. This detects that state (a panel-health warning); the one-tap repair, which copies
 * the row's `external_url` into the empty `internal_url`, and backup restore both apply
 * [INTERNAL_URL_REPAIR_SQL] to a private copy and return it through the helper's restore transaction
 * (`http/CompanionBackupOperations`).
 *
 * Root-only (the DB is app-private); a safe no-op on panels without su or without the Companion. The
 * parse + decision logic is pure ([parseServers] / [needsRepair]) and unit-tested; only the read-only
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

    /** What the last servers-table read found. [TOOL_ABSENT] is a device property (the firmware ships
     *  no `sqlite3`), not a failed check: the table cannot be inspected, so its state stays unknown. */
    internal enum class Probe { SUCCEEDED, FAILED, TOOL_ABSENT }

    /** One servers-table read projected for both header fallback and repair health. */
    internal data class ServerObservation(
        val probe: Probe,
        val preferredUrl: String?,
        val status: UrlStatus,
        /** Why `sqlite3` failed (exit code, database path, first stderr line), when it ran and failed. */
        val failure: String? = null,
    ) {
        val probeSucceeded: Boolean get() = probe == Probe.SUCCEEDED

        companion object {
            /** Companion is absent, or its servers table was read successfully and has no rows. */
            val EMPTY = ServerObservation(
                probe = Probe.SUCCEEDED,
                preferredUrl = null,
                status = UrlStatus(needsRepair = false, affected = 0),
            )

            /** Companion is installed but its servers table could not be read. */
            val UNKNOWN = ServerObservation(
                probe = Probe.FAILED,
                preferredUrl = null,
                status = UrlStatus(needsRepair = false, affected = 0),
            )

            /** Companion is installed but the device has no `sqlite3` to read its servers table with. */
            val TOOL_ABSENT = UNKNOWN.copy(probe = Probe.TOOL_ABSENT)
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

        /** The servers table couldn't be read but a privileged retry exists. Not raised when the device
         *  has no `sqlite3`: a retry cannot succeed, and that is not a failed check. */
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
            observation.probe == Probe.FAILED && directSuReady -> Warning.ProbeFailed
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

    /** Where the Companion's data directory is looked for, in order. Android 11 and later isolate app
     *  data: an app's mount namespace shows only its own folder under `/data/data`, and a su that keeps
     *  the caller's namespace (a setuid su, or a root manager's default) inherits that view, so the
     *  Companion folder is not there for this app's root shell even though `adb shell su` sees it
     *  (Issue #24). `/data_mirror/data_ce/null/0` is the internal-storage source the isolated view is
     *  bound from, and it stays visible inside the app's namespace. */
    internal fun dataDirCandidates(pkg: String): List<String> =
        listOf("/data/data/$pkg", "/data_mirror/data_ce/null/0/$pkg")

    // Shell that sets `hapaneld_cdir` to the first candidate directory this root shell can see, or to the
    // first candidate when none is visible, so a failure names the ordinary path.
    private fun resolveDataDir(pkg: String): String {
        val (primary, mirror) = dataDirCandidates(pkg)
        return """hapaneld_cdir="$primary"; [ -d "${'$'}hapaneld_cdir" ] || { [ -d "$mirror" ] && hapaneld_cdir="$mirror"; }; """
    }

    private const val DB_REL = "databases/HomeAssistantDB"

    /** Run [body] with `hapaneld_cdir` naming the Companion data directory this root shell can see. A
     *  subshell, because this runs in Su's persistent shell: the variable never outlives the command and
     *  the body's exit status is the command's. */
    private fun inDataDir(pkg: String, body: String): String = "( " + resolveDataDir(pkg) + body + " )"

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
        return parsePageZoom(root.runOutput(inDataDir(pkg, """cat "${'$'}hapaneld_cdir/shared_prefs/themes_0.xml" 2>/dev/null""")))
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
        val out = root.runOutput(
            inDataDir(pkg, """sqlite3 "file:${'$'}hapaneld_cdir/$DB_REL?immutable=1" "$LOGIN_SQL" 2>/dev/null"""),
        ) ?: return null
        val active = parseActiveServer(
            root.runOutput(inDataDir(pkg, """cat "${'$'}hapaneld_cdir/shared_prefs/session_0.xml" 2>/dev/null""")),
        )
        return pickLogin(parseLogins(out), active)
    }

    // Printed instead of rows when `sqlite3` is not on the root PATH, so a missing tool (exit 127) is not
    // confused with a read that ran and failed (non-zero, runOutput null). `echo` is a shell builtin; the
    // toybox `printf` is not, and must not be what reports a missing binary.
    internal const val SQLITE3_ABSENT = "ha-paneld:sqlite3-absent"

    // Printed instead of rows when `sqlite3` ran and failed, with its exit code, the database and its
    // stderr, which is what tells a missing database, a hidden data directory and an unreadable schema apart.
    private const val SQLITE3_FAILED = "ha-paneld:sqlite3-failed"
    private const val FAILURE_MAX_CHARS = 300

    private fun sqliteFailure(out: String): String? =
        out.lineSequence().firstOrNull { it.startsWith(SQLITE3_FAILED) }
            ?.removePrefix(SQLITE3_FAILED)?.trim()?.take(FAILURE_MAX_CHARS)

    // A nonempty WAL can hold the whole servers schema. Read a private DB+WAL copy in that case;
    // opening the live file normally could create root-owned WAL/SHM files in the Companion's data.
    // Compare both copies before querying: a concurrent change fails this best-effort observation,
    // rather than falling back to stale main-file rows. This is not an atomic backup of a live writer.
    // The no-WAL path stays immutable for older SQLite versions without a -readonly flag.
    private fun readServersOutput(pkg: String, root: RootShell): String? {
        val db = "${'$'}hapaneld_cdir/$DB_REL"
        val read = """if [ -s "$db-wal" ]; then ( """ +
            """umask 077; hapaneld_cdb_dir=$(mktemp -d /data/local/tmp/ha-paneld-companion.XXXXXX) || exit ${'$'}?; """ +
            """trap 'hapaneld_cdb_status=${'$'}?; rm -rf "${'$'}hapaneld_cdb_dir" || hapaneld_cdb_status=${'$'}?; exit "${'$'}hapaneld_cdb_status"' 0; """ +
            """trap 'exit 1' 1 2 15; """ +
            """cp "$db" "${'$'}hapaneld_cdb_dir/HomeAssistantDB" && """ +
            """cp "$db-wal" "${'$'}hapaneld_cdb_dir/HomeAssistantDB-wal" && """ +
            """cmp "$db" "${'$'}hapaneld_cdb_dir/HomeAssistantDB" >&2 && """ +
            """cmp "$db-wal" "${'$'}hapaneld_cdb_dir/HomeAssistantDB-wal" >&2 && """ +
            """sqlite3 "${'$'}hapaneld_cdb_dir/HomeAssistantDB" "$DUMP_SQL"; """ +
            """) else sqlite3 "file:$db?immutable=1" "$DUMP_SQL"; fi"""
        return root.runOutput(
            inDataDir(
                pkg,
                """if command -v sqlite3 >/dev/null 2>&1; then """ +
                    """{ hapaneld_cdb_err=$({ $read; } 2>&1 1>&3 3>&-); hapaneld_cdb_rc=$?; } 3>&1; """ +
                    """[ "${'$'}hapaneld_cdb_rc" -eq 0 ] || echo "$SQLITE3_FAILED rc=${'$'}hapaneld_cdb_rc db=$db: ${'$'}hapaneld_cdb_err"; """ +
                    """unset hapaneld_cdb_err hapaneld_cdb_rc; """ +
                    """else echo $SQLITE3_ABSENT; fi""",
            ),
        )
    }

    @Volatile private var toolAbsentLogged = false

    /** Observe [pkg]'s servers table through [root]. Tool absence is reported as its own state. */
    internal fun observeServers(pkg: String, root: RootShell): ServerObservation {
        val out = readServersOutput(pkg, root)
        if (out?.trim() == SQLITE3_ABSENT) {
            if (!toolAbsentLogged) {
                toolAbsentLogged = true
                Log.i(TAG, "no sqlite3 on this device; Companion servers table cannot be checked")
            }
            return ServerObservation.TOOL_ABSENT
        }
        val failure = out?.let(::sqliteFailure)
        if (out == null || failure != null) {
            Log.w(TAG, "Companion servers table read failed" + (failure?.let { ": $it" } ?: ""))
            return ServerObservation.UNKNOWN.copy(failure = failure)
        }
        return observeServers(parseServers(out))
    }

    internal fun observeServers(rows: List<ServerRow>?): ServerObservation {
        rows ?: return ServerObservation.UNKNOWN
        val preferredUrl = rows.firstNotNullOfOrNull { row ->
            (row.internalUrl.takeUnless(::isBlank) ?: row.externalUrl.takeUnless(::isBlank))
                ?.trimEnd('/')
        }
        val affected = rows.count(::needsRepair)
        return ServerObservation(
            probe = Probe.SUCCEEDED,
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
        else -> previous.copy(probe = observed.probe, failure = observed.failure)
    }

    /**
     * One root SQLite read for every read-only servers-table projection. Call off the main thread.
     * Absence is confirmed empty; an installed Companion whose table is unreadable remains unknown, and
     * one on a device without `sqlite3` is [Probe.TOOL_ABSENT].
     */
    internal fun observeServers(context: Context, root: RootShell): ServerObservation {
        val pkg = CompanionInstaller.installedPkg(context) ?: return ServerObservation.EMPTY
        return observeServers(pkg, root)
    }
}
