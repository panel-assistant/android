package io.panelassistant.android.device.profile

import io.panelassistant.android.BuildConfig
import java.io.File

/**
 * Test authority for repository profile data. Tests consume the same bytes that are packaged in the
 * APK; no Kotlin device singleton is allowed to act as an oracle for those bytes.
 */
internal object BundledProfileFixtures {
    data class Loaded(
        val file: File,
        val rawYaml: String,
        val rawSha256: String,
        val document: ProfileDocument,
    ) {
        fun profile(productVersion: String = "", trustedBundledContent: Boolean = true): DataDeviceProfile =
            DataDeviceProfile(
                document = document,
                productVersion = productVersion,
                revision = rawSha256,
                trustedBundledContent = trustedBundledContent,
            )
    }

    // Source-text reason: loads the shipped device profiles as input data.
    val assetsDirectory: File by lazy {
        requiredDirectory(
            "src/main/assets/device-profiles",
            "app/src/main/assets/device-profiles",
            "../app/src/main/assets/device-profiles",
        )
    }

    val unofficialDirectory: File? by lazy {
        optionalDirectory(
            "docs/profiles/unofficial",
            "../docs/profiles/unofficial",
        )
    }

    // Source-text reason: other test files still resolve paths from here; drop once they no longer do.
    val mainKotlinDirectory: File by lazy {
        requiredDirectory(
            "src/main/kotlin",
            "app/src/main/kotlin",
            "../app/src/main/kotlin",
        )
    }

    val bundled: List<Loaded> by lazy { load(assetsDirectory, bundled = true) }

    val bundledById: Map<String, Loaded> by lazy { uniqueById(bundled, "bundled") }

    val unofficial: List<Loaded> by lazy {
        unofficialDirectory?.let { load(it, bundled = false) }.orEmpty()
    }

    val unofficialById: Map<String, Loaded> by lazy { uniqueById(unofficial, "unofficial") }

    fun profile(id: String, productVersion: String = ""): DataDeviceProfile =
        bundledById.getValue(id).profile(productVersion)

    fun fallback(productVersion: String = ""): DataDeviceProfile =
        bundled.single { it.document.match.fallback }.profile(productVersion)

    fun rawByName(): Map<String, String> = bundled.associate { it.file.name to it.rawYaml }

    private fun load(directory: File, bundled: Boolean): List<Loaded> = yamlFiles(directory).map { file ->
        val raw = file.readText()
        val parsed = ProfileYaml.parse(raw)
        val document = requireNotNull(parsed.document) { "${file.path} did not parse: ${parsed.issues}" }
        val issues = parsed.issues + ProfileValidator.validate(
            document,
            BuildConfig.VERSION_NAME,
            bundled = bundled,
        )
        check(issues.isEmpty()) {
            "${file.path} has profile validation issues: $issues"
        }
        Loaded(
            file = file,
            rawYaml = raw,
            rawSha256 = ProfileYaml.sha256(raw),
            document = document,
        )
    }

    private fun yamlFiles(directory: File): List<File> = directory.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("yaml", "yml") }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .toList()

    private fun uniqueById(profiles: List<Loaded>, label: String): Map<String, Loaded> {
        val duplicates = profiles.groupBy { it.document.id }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "duplicate $label profile ids: $duplicates" }
        return profiles.associateBy { it.document.id }
    }

    private fun requiredDirectory(vararg candidates: String): File =
        optionalDirectory(*candidates) ?: error("Directory not found; tried ${candidates.toList()}")

    private fun optionalDirectory(vararg candidates: String): File? =
        candidates.asSequence().map(::File).firstOrNull(File::isDirectory)
}
