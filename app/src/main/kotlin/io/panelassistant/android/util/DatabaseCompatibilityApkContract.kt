package io.panelassistant.android.util

/**
 * Strict parser for the database boundary signed into a candidate APK's application metadata.
 *
 * This format is intentionally finite and boring so Android and the host provisioner can parse the
 * same authenticated value without JSON-library or locale differences. A missing or malformed value
 * remains distinguishable from a valid boundary: only the compatibility authority may decide that a
 * proven fresh install can proceed without metadata from a legacy APK.
 */
internal object DatabaseCompatibilityApkContract {
    // DB_COMPAT_MUTATION_ANCHOR: CANDIDATE_METADATA
    const val METADATA_NAME = "io.panelassistant.android.DATABASE_COMPATIBILITY"
    private const val FORMAT_NAME = "hapaneld-db"
    private const val FORMAT_VERSION = "v1"
    private const val FIELD_COUNT = 5

    data class Boundary(
        val formatVersion: Int,
        val databaseName: String,
        val minimumSchema: Int,
        val maximumSchema: Int,
    )

    sealed interface Parsed {
        data object Missing : Parsed
        data class Malformed(val reason: String) : Parsed
        data class Valid(val boundary: Boundary) : Parsed
    }

    fun parse(raw: String?): Parsed {
        if (raw == null) return Parsed.Missing
        if (raw.isEmpty()) return Parsed.Malformed("empty database compatibility metadata")
        val fields = raw.split(':')
        if (fields.size != FIELD_COUNT) return Parsed.Malformed("database compatibility field count")
        if (fields[0] != FORMAT_NAME || fields[1] != FORMAT_VERSION) {
            return Parsed.Malformed("unsupported database compatibility format")
        }
        val databaseName = fields[2]
        if (databaseName != "ha-paneld.db") return Parsed.Malformed("unexpected database name")
        val minimum = fields[3].strictPositiveInt()
            ?: return Parsed.Malformed("invalid minimum database schema")
        val maximum = fields[4].strictPositiveInt()
            ?: return Parsed.Malformed("invalid maximum database schema")
        if (minimum > maximum) return Parsed.Malformed("incoherent database schema boundary")
        return Parsed.Valid(Boundary(1, databaseName, minimum, maximum))
    }

    fun encode(boundary: Boundary): String {
        require(boundary.formatVersion == 1) { "unsupported database compatibility format" }
        require(boundary.databaseName == "ha-paneld.db") { "unexpected database name" }
        require(boundary.minimumSchema > 0) { "invalid minimum database schema" }
        require(boundary.maximumSchema >= boundary.minimumSchema) { "incoherent database schema boundary" }
        return "$FORMAT_NAME:$FORMAT_VERSION:${boundary.databaseName}:${boundary.minimumSchema}:${boundary.maximumSchema}"
    }

    private fun String.strictPositiveInt(): Int? {
        if (isEmpty() || any { it !in '0'..'9' } || (length > 1 && first() == '0')) return null
        return toIntOrNull()?.takeIf { it > 0 }
    }
}
