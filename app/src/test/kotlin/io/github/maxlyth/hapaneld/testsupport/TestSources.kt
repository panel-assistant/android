package io.github.maxlyth.hapaneld.testsupport

import java.io.File

/**
 * One shared locator for reading shipped production source, assets and catalog files from JVM unit
 * tests, regardless of whether the test process runs with its working directory at the repo root or
 * the app module. It ONLY locates files — no parsing, assertion or fixture logic lives here — so the
 * source-text-coupled tests that remain stay enumerable by a single grep on this object, and the
 * working-directory fallback convention lives in one place instead of being re-derived per test.
 *
 * Path families (first existing match wins):
 *  - app module:  `<rel>`, `app/<rel>`, `../app/<rel>`  (repo-root and subdir runs both resolve)
 *  - repo root:   `<rel>`, `../<rel>`
 *
 * Required variants error with the cwd if nothing matches; `…OrNull` variants return null for callers
 * that tolerate absence.
 */
object TestSources {
    // Source-text reason: the one locator for reads that state their own reason; it asserts nothing.
    private const val PKG = "io/github/maxlyth/hapaneld"

    /** A production `.kt` under the app package root, e.g. `kotlin("http/PaneldServer.kt")`. Required. */
    fun kotlin(relativeUnderPackage: String): File = appFile("src/main/kotlin/$PKG/$relativeUnderPackage")

    /** A shipped web asset by file name, e.g. `asset("configure.js")`. Required. */
    fun asset(name: String): File = appFile("src/main/assets/$name")

    /** The shipped web-asset directory. Required. */
    fun assetDir(): File = appDir("src/main/assets")

    /** A file relative to the app module root (the dir containing `src/main/…`). Required. */
    fun appFile(relativeToAppModule: String): File =
        firstFile(appCandidates(relativeToAppModule)) ?: missing(relativeToAppModule)

    /** A directory relative to the app module root. Required. */
    fun appDir(relativeToAppModule: String): File =
        firstDir(appCandidates(relativeToAppModule)) ?: missing(relativeToAppModule)

    /** A file relative to the repository root (the dir containing `app/`, `docs/`, `scripts/`). Required. */
    fun repoFile(relativeToRepoRoot: String): File =
        firstFile(repoCandidates(relativeToRepoRoot)) ?: missing(relativeToRepoRoot)

    /** A directory relative to the repository root. Required. */
    fun repoDir(relativeToRepoRoot: String): File =
        firstDir(repoCandidates(relativeToRepoRoot)) ?: missing(relativeToRepoRoot)

    fun appFileOrNull(relativeToAppModule: String): File? = firstFile(appCandidates(relativeToAppModule))

    fun repoFileOrNull(relativeToRepoRoot: String): File? = firstFile(repoCandidates(relativeToRepoRoot))

    private fun appCandidates(rel: String) = listOf(File(rel), File("app/$rel"), File("../app/$rel"))

    private fun repoCandidates(rel: String) = listOf(File(rel), File("../$rel"))

    private fun firstFile(candidates: List<File>) = candidates.firstOrNull(File::isFile)

    private fun firstDir(candidates: List<File>) = candidates.firstOrNull(File::isDirectory)

    private fun missing(rel: String): Nothing =
        error("TestSources: no file found for '$rel' (cwd=${File(".").absoluteFile.parent})")
}
