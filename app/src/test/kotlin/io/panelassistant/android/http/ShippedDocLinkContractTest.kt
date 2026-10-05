package io.panelassistant.android.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every repository-documentation URL shipped in app sources must resolve. The Shizuku guide
 * consolidation deleted docs/shizuku.md while the Profile UI still linked to it, which no test
 * caught because the documentation link sweep read only Markdown and shell sources. This contract
 * reads the shipped Kotlin and web-asset sources and checks two link shapes:
 *
 * - a `blob/main/...` GitHub URL: the referenced file must exist in the tree, and where the URL
 *   carries a fragment, a heading that slugifies to it must exist in the referenced Markdown, so a
 *   retargeted link cannot point at a heading that was since reworded;
 * - a `panel-assistant.io/go/docs?page=<key>` redirect (the docs hub migration, 2026-09-14 — every
 *   page under docs/ is now a one-line stub pointing there instead of carrying its own content): the
 *   `page` key must resolve to a real stub file under `docs/`, so a renamed or deleted stub cannot
 *   silently 302 a shipped link to the site's homepage. This does not verify the *website's* topic
 *   map — that lives in a different repository — only that the shipped key still names a real page
 *   on this side of the contract.
 */
class ShippedDocLinkContractTest {
    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main").isDirectory }

    // Source-text reason: shipped doc URLs are user-visible links; a deleted doc page broke one (Shizuku guide).
    private fun shippedSources(): List<File> =
        listOf("app/src/main/kotlin", "app/src/main/assets")
            .map { File(repoRoot, it) }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "js" || it.extension == "html") }.toList() }

    private fun headingSlugs(markdown: File): Set<String> =
        markdown.readLines()
            .filter { it.startsWith("#") }
            .map { heading ->
                heading.trimStart('#').trim()
                    .lowercase()
                    .replace(Regex("[`*_\\[\\]()]"), "")
                    .replace(Regex("[^a-z0-9\\- ]"), "")
                    .trim()
                    .replace(Regex(" +"), "-")
            }
            .toSet()

    private fun stubRelativePathForPageKey(key: String): String =
        if (key == "readme" || key.endsWith("/readme")) {
            key.removeSuffix("readme") + "README.md"
        } else {
            "$key.md"
        }

    @Test
    fun everyShippedRepositoryDocLinkResolvesToARealFileAndHeading() {
        val blobUrl = Regex("blob/main/((?:docs/)?[A-Za-z0-9_/.-]+\\.md)(#([A-Za-z0-9-]+))?")
        val goDocsUrl = Regex("panel-assistant\\.io/go/docs\\?page=([A-Za-z0-9_/-]+)")
        val problems = mutableListOf<String>()
        var found = 0
        for (source in shippedSources()) {
            val text = source.readText()
            for (match in blobUrl.findAll(text)) {
                found++
                val path = match.groupValues[1]
                val anchor = match.groupValues[3]
                val target = File(repoRoot, path)
                if (!target.isFile) {
                    problems += "${source.name}: $path does not exist in the repository"
                } else if (anchor.isNotEmpty() && anchor !in headingSlugs(target)) {
                    problems += "${source.name}: $path has no heading matching #$anchor"
                }
            }
            for (match in goDocsUrl.findAll(text)) {
                found++
                val key = match.groupValues[1].lowercase()
                val relative = stubRelativePathForPageKey(key)
                val docsRoot = File(repoRoot, "docs")
                if (!File(docsRoot, relative).isFile) {
                    problems += "${source.name}: /go/docs?page=$key has no stub at docs/$relative"
                }
            }
        }
        assertTrue("no shipped repository doc links were found; the extractor is broken", found > 0)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
