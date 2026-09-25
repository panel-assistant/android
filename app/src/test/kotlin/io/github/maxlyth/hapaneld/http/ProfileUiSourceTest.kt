package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileUiSourceTest {
    @Test
    fun codeMirrorBundleIsPinnedReproducibleAndNotRequiredByGradle() {
        val packageJson = File("../tools/profile-editor/package.json").takeIf { it.isFile }
            ?: File("tools/profile-editor/package.json")
        val lock = File(packageJson.parentFile, "package-lock.json")
        val build = File(packageJson.parentFile, "build.mjs")
        // Source-text reason: pins the shipped third-party bundle's licence notice and reproducible build inputs.
        val bundle = File("src/main/assets/vendor/profile-editor/codemirror.js")
        val license = File(bundle.parentFile, "LICENSE.txt")
        val notice = File(bundle.parentFile, "NOTICE.txt")
        assertTrue(packageJson.isFile)
        assertTrue(lock.isFile)
        assertTrue(build.isFile)
        assertTrue(bundle.length() > 100_000)
        assertTrue(license.readText().contains("Permission is hereby granted"))
        assertTrue(notice.readText().contains("@codemirror/view 6.43.11"))
        assertTrue(bundle.readText().startsWith("/*! @license CodeMirror 6"))
        assertFalse(packageJson.readText().contains("\"latest\""))
        // Gradle never builds the bundle: the three editor files this test reads are declared inputs of
        // the unit-test task and nothing else — never the subject of a task, an exec or a dependency.
        val editorReferences = Regex("profile-editor[^\"\\s]*").findAll(File("build.gradle.kts").readText())
            .map { it.value }.toSet()
        assertEquals(
            setOf("profile-editor/package.json", "profile-editor/package-lock.json", "profile-editor/build.mjs"),
            editorReferences,
        )
    }
}
