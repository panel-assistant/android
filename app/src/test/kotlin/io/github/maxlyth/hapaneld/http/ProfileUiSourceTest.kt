package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileUiSourceTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val script = File("src/main/assets/profiles.js").readText()
    private val css = File("src/main/assets/profiles.css").readText()

    @Test
    fun profileTabIsPrimaryFullWidthLocalAndRestartExplicit() {
        assertTrue("tab(\"profiles\", \"profiles\", strings.get(\"shell.nav.profile\"))" in server)
        assertTrue("get(\"/profiles\")" in server)
        assertTrue("profile-workspace" in server)
        assertTrue("t(\"profiles.action.confirm_restart\", \"Confirm and restart\")" in script)
        assertTrue("t(\"profiles.modal.restart_warning\", \"The panel service will restart. If the revision does not become healthy, ha-paneld will return to the last-known-good profile.\")" in script)
        assertTrue("grid-template-columns:minmax(0,1fr);grid-template-rows:minmax(272px,1fr) auto" in css)
        assertTrue("vendor/profile-editor/codemirror.js" in server)
        assertFalse("https://" in server.substringAfter("private fun profilesBody(strings: AppStrings)").substringBefore("private fun installBody"))
    }

    @Test
    fun profileWorkspaceUsesTheViewportWithoutCrushingTheEditor() {
        assertTrue(".profile-inspector-body{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))" in css)
        assertTrue("padding:10px;overflow:visible;max-height:none" in css)
        assertTrue(".profile-inspector section:nth-of-type(1),.profile-inspector section:nth-of-type(4){grid-column:1/-1}" in css)
        assertTrue("#profile-catalog-issues{display:grid;grid-template-columns:repeat(3,minmax(0,1fr))" in css)
        assertTrue(".profile-report{display:grid;grid-template-columns:repeat(4,minmax(0,1fr))" in css)
        assertTrue(".profile-guidance{grid-column:1/-1" in css)
        assertTrue(".profile-draft{grid-column:1/-1" in css)
        assertTrue("var PROFILE_EDITOR_MIN_LINES = 12" in script)
        assertTrue("function fitProfileWorkspace()" in script)
        assertTrue("editor.querySelector(\".cm-content\") || byId(\"profile-source-fallback\") || editor" in script)
        assertTrue("PROFILE_EDITOR_MIN_LINES * lineHeight" in script)
        assertTrue("Math.ceil(inspectorBody.scrollHeight) + 2" in script)
        assertTrue("minInspectorHeight + rowGap" in script)
        assertTrue("workspace.closest(\".wrap\")" in script)
        assertTrue("window.getComputedStyle(wrap).paddingBottom" in script)
        assertTrue("window.innerHeight - documentTop - Math.max(12, bottomInset)" in script)
        assertTrue("Math.max(minWorkspaceHeight, availableHeight)" in script)
        assertTrue("rawStatus.toLowerCase() === \"observed\" ? string(fact.value) : status + \" · \" + string(fact.value)" in script)
        assertTrue("window.ResizeObserver" in script)
        assertTrue("bindProfileLayout();" in script.substringAfter("initEditor(); bind();"))
        assertTrue("#profile-catalog-issues,.profile-report{grid-template-columns:repeat(2,minmax(0,1fr))" in css)
        assertTrue("<span class=\"profile-action-break\" aria-hidden=\"true\"></span>" in server)
        assertTrue(".profile-actions{display:contents}.profile-action-break{display:block;flex:0 0 100%;height:0}" in css)
        assertTrue("@media(max-width:857px){" in css)
        assertTrue(".profile-workspace{grid-template-rows:auto auto;height:auto;min-height:0" in css)
        assertTrue("height:52vh;min-height:226px" in css)
        assertFalse("Review content must not create nested scroll areas", css.contains("overscroll-behavior:contain"))
    }

    @Test
    fun shieldedProfileActionsExplainPhysicalApprovalInTheirConfirmation() {
        assertTrue("t(\"profiles.modal.hardened_body\", \"Shielded action: when Hardened mode is enabled, approve this request on the physical panel.\")" in script)
        assertTrue("openModal(action === \"rollback\" ? t(\"profiles.modal.rollback_title\", \"Roll back profile?\") : t(\"profiles.modal.activate_title\", \"Activate profile?\")" in script)
    }

    @Test
    fun diagnosticsUseSafeDomAndUnsavedEditsDriveSharedDirtySentinel() {
        assertTrue("message.textContent =" in script)
        assertFalse("innerHTML" in script)
        assertTrue("id=\"savebtn\"" in server)
        assertTrue("model.preview.source === model.source" in script)
        assertTrue("expected_catalog_revision" in script)
        assertTrue("X-Profile-Preview-Token" in script)
        assertTrue(script.indexOf("file.size > model.maxBytes") < script.indexOf("file.text()"))
        assertTrue("id=\"profile-generic-draft\" hidden" in server)
        assertTrue("active.ref.id === \"generic\"" in script)
        assertTrue("shizuku.toLowerCase() === \"recommended\"" in script)
        assertFalse("shizuku.toLowerCase() === \"optional\" ||" in script)
        assertTrue("id=\"profile-shizuku-guidance\" hidden" in server)
        assertTrue("strings.get(\"profiles.shizuku.body\")" in server)
        assertTrue("review.content_version" in script)
        assertTrue("review.author" in script)
        assertTrue(script.substringAfter("function importFile").substringBefore("function exportSource").contains("model.originalSource = \"\""))
        assertTrue(script.substringAfter("function loadTemplate").substringBefore("function loadDeviceDraft").contains("model.originalSource = \"\""))
        assertTrue("jsonFetch(API + \"/report\")" in script)
        assertTrue("model.editor.setSchema(localizedSchemaFields(schema.fields))" in script)
        assertTrue("setSchema: function () {}" in script)
        assertTrue("postYaml(\"/probe\", source)" in script)
        assertTrue("X-Profile-Presentation-Code" in script)
        assertTrue("X-Profile-Presentation-Params" in script)
        assertTrue("application/yaml, text/yaml, text/plain" in script)
        assertTrue("typeof owner.presentation_code !== \"string\"" in script)
        assertTrue("generation !== model.viewGeneration || source !== model.source" in script)
        assertTrue("refKey(model.selected) !== refKey(ref)" in script)
        assertTrue(script.substringAfter("function saveProfile").substringBefore("function beginEdit").contains("model.preview = null"))
        assertTrue("function reviewedSummary()" in script)
        assertTrue("model.preview.summary" in script)
        assertTrue("applying: function () { return t(\"profiles.activation.applying\", \"applying\"); }" in script)
        assertTrue("activation.state === \"active\"" in script)
        assertTrue(script.substringAfter("function setEditor").substringBefore("function isDirty").contains("useDraft.hidden = true"))
        assertTrue("id=\"profile-auto\"" in server)
        assertTrue("activate(null, \"activate\")" in script)
        assertTrue("function confirmDiscard()" in script)
        assertTrue("window.addEventListener(\"beforeunload\"" in script)
        assertTrue("automatic.disabled = dirty" in script)
        assertTrue("rollback.disabled = !(model.status.rollback_ref || model.status.rollback_auto) || dirty" in script)
        assertTrue("id=\"profile-catalog-issues\"" in server)
        assertTrue("renderCatalogIssues(model.status.issues || [])" in script)
        assertTrue("summary.compatible === false" in script)
        assertTrue("summary && summary.issues || []" in script)
        assertFalse("matches_this_device=false" in script)
        assertTrue("t(\"profiles.status.valid_wrong_hardware\", \"Valid profile, but it is intended for different hardware. You can save it here, but you cannot activate it on this panel.\")" in script)
    }

    @Test
    fun profileReferencesAreSeparateVisibleDestinationSafeLinks() {
        assertTrue("dashboard.profile_note.prefix" in server)
        assertFalse("if one looks wrong, that's where to correct it" in server)
        assertTrue("id=\"profile-links\"" in server)
        assertTrue("aria-label=\"${'$'}{esc(strings.get(\"profiles.references.label\"))}\"" in server)
        assertTrue("function renderProfileLinks(summary)" in script)
        assertTrue("new URL(raw)" in script)
        assertTrue("parsed.protocol !== \"https:\"" in script)
        assertTrue("parsed.username || parsed.password" in script)
        assertTrue("summary.compatible === false" in script)
        assertTrue("raw.length > 500" in script)
        assertTrue("profileLinkLabelSafe(label)" in script)
        assertTrue("noopener noreferrer" in script)
        assertTrue("no-referrer" in script)
        assertTrue("parsed.hostname" in script)
        assertTrue("document.createElement(\"bdi\")" in script)
        assertTrue("hostNode.dir = \"ltr\"" in script)
        assertTrue("document.createTextNode(\" · \")" in script)
        assertFalse("link.innerHTML" in script)
        assertTrue("<bdi class=\"profile-reference-label\" dir=\"auto\">" in server)
        assertTrue("<bdi class=\"profile-reference-host\" dir=\"ltr\">" in server)
        assertTrue(".profile-links[hidden]{display:none}" in css)
    }

    @Test
    fun codeMirrorBundleIsPinnedReproducibleAndNotRequiredByGradle() {
        val packageJson = File("../tools/profile-editor/package.json").takeIf { it.isFile }
            ?: File("tools/profile-editor/package.json")
        val lock = File(packageJson.parentFile, "package-lock.json")
        val build = File(packageJson.parentFile, "build.mjs")
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
