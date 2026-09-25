package io.github.maxlyth.hapaneld.dashboard

/**
 * The additive-only rule, made executable.
 *
 * Every schema change creates a future downgrade event, and a downgrade that an older build cannot
 * tolerate costs the owner their configuration. Additive changes are tolerable: an older build simply
 * ignores a table or column it has never heard of. Two shapes are not, and both are silent — nothing
 * fails at the moment the change is written, only years later on somebody's panel:
 *
 * - **Removing or retyping** anything an older build still reads.
 * - **Adding a `NOT NULL` column without a default**, which leaves an older build's inserts — written
 *   against the columns it knows — failing against a constraint it cannot satisfy.
 *
 * Keeping this as a comment in the reconcile path was not enough; it is a rule about SQL, so it is
 * checked against the SQL. It lives with the tests, which run it over every migration step on every build.
 */
internal object SchemaAdditivePolicy {
    private val FORBIDDEN = listOf(
        Regex("""\bDROP\s+TABLE\b""") to "drops a table",
        Regex("""\bDROP\s+COLUMN\b""") to "drops a column",
        Regex("""\bRENAME\s+TO\b""") to "renames a table",
        Regex("""\bRENAME\s+COLUMN\b""") to "renames a column",
        Regex("""\bALTER\s+COLUMN\b""") to "retypes a column",
    )

    /** Human-readable reasons [sql] is not additive; empty when it is safe for an older build. */
    fun violations(sql: String): List<String> {
        val normalized = sql.uppercase().replace(Regex("""\s+"""), " ")
        return buildList {
            FORBIDDEN.forEach { (pattern, reason) -> if (pattern.containsMatchIn(normalized)) add(reason) }
            if (normalized.contains("ADD COLUMN") &&
                normalized.contains("NOT NULL") &&
                !normalized.contains("DEFAULT")
            ) {
                add("adds a NOT NULL column with no default, so an older build's inserts would fail")
            }
        }
    }

    fun violations(statements: List<String>): List<String> = statements.flatMap { statement ->
        violations(statement).map { "$it: ${statement.trim().take(80)}" }
    }
}
