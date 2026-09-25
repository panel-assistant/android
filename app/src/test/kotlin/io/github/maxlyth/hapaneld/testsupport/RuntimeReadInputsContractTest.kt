package io.github.maxlyth.hapaneld.testsupport

import java.io.File
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the JVM unit tests' runtime file reads and the Gradle `Test` task's declared inputs in
 * lockstep.
 *
 * Many contract tests read production files straight from the working tree (source slices, shipped
 * web assets, the manifest, resource XML, build scripts, documents, fixtures and test-side scripts)
 * rather than from the test classpath. Gradle fingerprints only what a task declares, so a read the
 * build script does not know about lets an edit to that file leave the test task `UP-TO-DATE`, or lets
 * the build cache replay a green recorded under different bytes as `FROM-CACHE`. `app/build.gradle.kts`
 * therefore declares the runtime-read directories and files as `Test` inputs and hands the same lists
 * to this test through system properties; this test scans every test source for the paths it reads
 * and fails, naming the offender, when one falls outside the declared set.
 *
 * Two shapes of read are recognised:
 *  - path literals whose prefix is one of the families tests read today (`src/main/…`, `src/test/…`,
 *    `docs/`, `scripts/`, `helper/`, `tools/`, `gradle/`, the two build scripts; the browser-harness
 *    fixtures moved under `tools/test/` and are covered by `tools/`, while the legacy `test/fixtures/`
 *    prefix stays recognised so a stray pre-move reference is reported rather than silently ignored) and
 *    the `TestSources.kotlin/asset/assetDir` helpers; an interpolated literal is checked as a prefix;
 *  - constructed reads: a bare name after `parentFile,` or `resolveSibling(`, resolved against the
 *    directory of every path literal in the same file; and a `../`-relative literal given to
 *    `File(...)` or `resolve(...)`, resolved against those directories and every declared directory.
 *    Each is covered only when one of its candidates exists on disk and is declared.
 * A path assembled from variables alone is invisible to both; keep such reads inside a declared
 * directory, or write them in one of the recognised shapes.
 */
class RuntimeReadInputsContractTest {
    // Source-text reason: scans test sources and the build script to keep Gradle Test inputs complete; pins no app code.
    private val declaredDirectories = declared("hapaneld.test.runtimeReadDirectories")
    private val declaredFiles = declared("hapaneld.test.runtimeReadFiles")
    // File-name globs inside the assets directory, not paths, so they bypass the path normalization.
    private val generatedAssetExcludes = property("hapaneld.test.runtimeReadAssetExcludes")
        .map { glob -> Regex("^" + glob.split('*').joinToString(".*") { Regex.escape(it) } + "$") }

    @Test fun `every declared runtime-read input exists in the working tree`() {
        assertTrue("no runtime-read directories were declared", declaredDirectories.isNotEmpty())
        assertTrue("no runtime-read files were declared", declaredFiles.isNotEmpty())
        // The excluded names are build outputs; each glob must still describe a real generated asset
        // name so a renamed binary cannot leave a stale, matching-nothing exclusion behind.
        for (glob in generatedAssetExcludes) {
            assertTrue(
                "exclusion $glob matches no generated asset name",
                listOf("cdprelay-arm", "cdprelay-arm64", "hapaneld-helper-arm", "hapaneld-helper-arm64").any(glob::matches),
            )
        }
        val missing = declaredDirectories.filterNot { repoPath(it).isDirectory } +
            declaredFiles.filterNot { repoPath(it).isFile }
        assertEquals("declared Test inputs that no longer exist: $missing", emptyList<String>(), missing)
    }

    @Test fun `every runtime read in the test sources is a declared Test input`() {
        val scan = scanTestSources()
        // The scanner must be able to go positive on each shape it claims to see, or a broken pattern
        // would pass this contract vacuously.
        assertTrue("scanner missed the OpenAPI asset read", "app/src/main/assets/openapi.json" in scan.reads)
        assertTrue("scanner missed the docs/profiles/unofficial read", "docs/profiles/unofficial" in scan.reads)
        assertTrue("scanner missed a test-side script read", "app/src/test/js/entity-template-advisory-test.mjs" in scan.reads)
        assertTrue(
            "scanner missed the constructed sibling read of the editor lock file",
            "tools/profile-editor/package-lock.json" in scan.reads,
        )

        val uncovered = (scan.reads.filterNot(::covered) + scan.unresolved).sorted()
        assertEquals(
            "runtime reads outside the declared Test inputs (declare them in app/build.gradle.kts " +
                "unitTestRuntimeReadDirectories/Files, or the edited file will not re-run the tests):\n" +
                uncovered.joinToString("\n"),
            emptyList<String>(),
            uncovered,
        )
    }

    @Test fun `the build script wires the declared lists as normalized Test inputs`() {
        val build = TestSources.appFile("build.gradle.kts").readText()
        for (needle in listOf(
            "val unitTestRuntimeReadDirectories = listOf(",
            "val unitTestRuntimeReadFiles = listOf(",
            "val unitTestGeneratedAssetExcludes = listOf(",
            "withPathSensitivity(PathSensitivity.RELATIVE)",
            "systemProperty(\"hapaneld.test.runtimeReadDirectories\"",
            "systemProperty(\"hapaneld.test.runtimeReadFiles\"",
            "systemProperty(\"hapaneld.test.runtimeReadAssetExcludes\"",
        )) {
            assertTrue("build.gradle.kts must contain: $needle", build.contains(needle))
        }
    }

    // ---- scanning ----

    private class Scan(val reads: Set<String>, val unresolved: List<String>)

    private data class Constructed(val relative: String, val sibling: Boolean)

    /**
     * Repo-root-relative paths the test sources read. Interpolated literals are cut at the first `$`
     * and carry a trailing `/` marker so they are checked as prefixes under a declared directory.
     * Constructed reads that resolve to no existing declared candidate are reported in `unresolved`.
     */
    private fun scanTestSources(): Scan {
        val root = TestSources.appDir("src/test/kotlin")
        val self = File(root, "io/github/maxlyth/hapaneld/testsupport/RuntimeReadInputsContractTest.kt")
        val reads = sortedSetOf<String>()
        val unresolved = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && it != self }.forEach { file ->
            val fileReads = sortedSetOf<String>()
            val constructed = mutableListOf<Constructed>()
            file.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEach
                PATH_LITERAL.findAll(line).forEach { fileReads += normalize(it.groupValues[1]) }
                TEST_SOURCES_KOTLIN.findAll(line).forEach {
                    fileReads += normalize("src/main/kotlin/io/github/maxlyth/hapaneld/" + it.groupValues[1])
                }
                TEST_SOURCES_ASSET.findAll(line).forEach { fileReads += normalize("src/main/assets/" + it.groupValues[1]) }
                if (line.contains("TestSources.assetDir()")) fileReads += "app/src/main/assets"
                SIBLING_NAME.findAll(line).forEach { constructed += Constructed(it.groupValues[1], sibling = true) }
                RELATIVE_CONSTRUCTION.findAll(line).forEach { constructed += Constructed(it.groupValues[1], sibling = false) }
            }
            reads += fileReads
            // A constructed read is anchored on the directory of a path literal in the same file; a
            // `../` climb may also start from a declared directory (its anchor can live in another
            // file). A bare sibling name deliberately gets no declared-directory anchors, or any
            // same-named file anywhere in the declared set would cover it. It counts only when a
            // candidate really exists there.
            val literalBases = fileReads.filterNot { it.endsWith("/") }.map { it.substringBeforeLast('/', "") }
            constructed.forEach { (relative, sibling) ->
                val bases = if (sibling) literalBases else literalBases + declaredDirectories
                val candidate = bases.asSequence()
                    .map { base -> Paths.get(base).resolve(relative).normalize().toString() }
                    .filter { !it.startsWith("..") && repoPath(it).exists() }
                    .firstOrNull { covered(it) } ?: bases.asSequence()
                    .map { base -> Paths.get(base).resolve(relative).normalize().toString() }
                    .firstOrNull { !it.startsWith("..") && repoPath(it).exists() }
                if (candidate == null) {
                    unresolved += "${file.name}: constructed read '$relative' resolves to no existing declared file"
                } else {
                    reads += candidate
                }
            }
        }
        return Scan(reads, unresolved)
    }

    private fun normalize(literal: String): String {
        val interpolated = literal.indexOf('$')
        val fixed = if (interpolated >= 0) literal.substring(0, interpolated) else literal
        var path = fixed
        while (path.startsWith("../")) path = path.removePrefix("../")
        path = path.removePrefix("app/")
        val repoRelative = if (path.startsWith("src/") || path == "build.gradle.kts") "app/$path" else path
        return if (interpolated >= 0) repoRelative.substringBeforeLast('/') + "/" else repoRelative
    }

    private fun covered(read: String): Boolean {
        val prefixOnly = read.endsWith("/")
        val path = read.trimEnd('/')
        if (!prefixOnly && path in declaredFiles) return true
        val directory = declaredDirectories.firstOrNull { path == it || path.startsWith("$it/") }
            ?: return false
        if (directory == "app/src/main/assets" && !prefixOnly) {
            val name = path.substringAfterLast('/')
            if (generatedAssetExcludes.any { it.matches(name) }) return false
        }
        return true
    }

    // ---- declared set ----

    /** Declared entries, normalized to repo-root-relative form (`app/...` for app-module paths). */
    private fun declared(property: String): List<String> = property(property).map { entry ->
        if (entry.startsWith("../")) entry.removePrefix("../") else "app/$entry"
    }

    private fun property(name: String): List<String> {
        val value = System.getProperty(name)
        assertNotNull("system property $name must be set by app/build.gradle.kts", value)
        return value!!.split(File.pathSeparatorChar).filter { it.isNotBlank() }
    }

    private fun repoPath(repoRelative: String): File =
        TestSources.repoFile("settings.gradle.kts").absoluteFile.parentFile.resolve(repoRelative)

    private companion object {
        val PATH_LITERAL = Regex(
            "\"((?:\\.\\./)*(?:app/)?(?:src/(?:main|test)/[^\"\\s]*|docs/[^\"\\s]+|scripts/[^\"\\s]+|helper/[^\"\\s]+|" +
                "test/fixtures/[^\"\\s]+|tools/[^\"\\s]+|gradle/[^\"\\s]+|build\\.gradle\\.kts|settings\\.gradle\\.kts))\"",
        )
        val TEST_SOURCES_KOTLIN = Regex("TestSources\\.kotlin\\(\"([^\"]*)\"")
        val TEST_SOURCES_ASSET = Regex("TestSources\\.asset\\(\"([^\"]*)\"")
        /** `File(x.parentFile, "name")` and `x.resolveSibling("name")`: a bare name beside a known file. */
        val SIBLING_NAME = Regex("(?:parentFile\\s*,\\s*|resolveSibling\\()\"([^\"/$]+)\"")
        /** `File(dir, "../rel")` and `dir.resolve("../rel")`: a climb out of a known directory. */
        val RELATIVE_CONSTRUCTION = Regex("(?:File\\([^\"()]*,\\s*|resolve\\()\"((?:\\.\\./)+[^\"$]*)\"")
    }
}
