package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileFrameI18nSourceTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val body = server.substringAfter("private fun profilesBody(strings: AppStrings)")
        .substringBefore("private fun installBody")

    @Test fun `profile route uses one request catalogue for body projection and language evidence`() {
        val route = server.substringAfter("get(\"/profiles\")").substringBefore("get(\"/test\")")

        assertTrue(route.contains("val strings = requestStrings(call)"))
        assertTrue(route.contains("strings.languages(setOf(\"shell.\", \"configure.hardened.\", \"profiles.\")).joinToString(\", \")"))
        assertTrue(route.contains("profilesBody(strings)"))
        assertFalse(route.contains("+ AppLocale.ENGLISH"))
        assertTrue(
            server.substringAfter("private fun page(").substringBefore("private fun setupBody")
                .contains("translationPrefixes = setOf(\"shell.\", \"\$active.\", \"runtime.\")"),
        )
    }

    @Test fun `every server frame record is escaped at its markup sink`() {
        val keys = listOf(
            "profiles.toolbar.actions_label",
            "profiles.toolbar.revision",
            "profiles.toolbar.revision_label",
            "profiles.status.loading_catalog",
            "profiles.toolbar.editing_label",
            "profiles.action.new",
            "profiles.action.edit",
            "profiles.action.fork",
            "profiles.action.import",
            "profiles.action.export",
            "profiles.toolbar.review_label",
            "profiles.action.validate_yaml",
            "profiles.action.compare",
            "profiles.toolbar.activation_label",
            "profiles.action.save_revision",
            "profiles.action.activate",
            "profiles.action.use_automatic",
            "profiles.action.rollback",
            "profiles.action.delete",
            "profiles.state.label",
            "profiles.references.label",
            "profiles.editor.title",
            "profiles.inspector.title",
            "profiles.section.catalog_runtime",
            "profiles.section.validation",
            "profiles.shizuku.title",
            "profiles.shizuku.body",
            "profiles.shizuku.guide",
            "profiles.section.compared_active",
            "profiles.section.observed",
            "profiles.observed.note",
            "profiles.generic.title",
            "profiles.generic.body",
            "profiles.action.generate_draft",
            "profiles.action.copy_draft",
            "profiles.modal.default_title",
            "profiles.action.cancel",
            "profiles.action.confirm",
        )

        assertEquals(38, keys.size)
        keys.forEach { key ->
            assertTrue("missing escaped frame key $key", body.contains("\${esc(strings.get(\"$key\"))}"))
        }
        assertEquals(40, Regex("""\$\{esc\(strings\.get\(\"profiles\.[^\"]+\"\)\)}""").findAll(body).count())
    }

    @Test fun `protected profile actions use request-local approval descriptions`() {
        listOf("profile-activate", "profile-auto", "profile-rollback").forEach { id ->
            val control = body.lineSequence().single { it.contains("id=\"$id\"") }
            assertTrue(control.contains("\${hardenedApprovalAttrs(strings = strings)}"))
        }
        assertFalse(body.contains("hardenedApprovalAttrs()"))
    }
}
