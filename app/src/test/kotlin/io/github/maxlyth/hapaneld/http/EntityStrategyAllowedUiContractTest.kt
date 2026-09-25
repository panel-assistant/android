package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the configure pages say when a dashboard strategy runs under an allowed entity-discovery check
 * (issue #133 follow-up): the strategy builds cards only for subscribed entities, so any other entity
 * silently has no card and is never learned, and nothing the panel observes can list it.
 */
class EntityStrategyAllowedUiContractTest {
    private fun source(vararg candidates: String): String =
        candidates.map(::File).first(File::isFile).readText()

    private val server = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
    )

    private val manager = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt",
    )

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        return text.substring(start, text.indexOf("\n    }\n", start))
    }

    @Test fun `the status JSON and the banner read one definition over the live stream`() {
        // No JVM harness constructs the manager, so its two call sites are pinned here; the predicate
        // itself is behaviour-tested in EntityLearningIssueVisibilityTest.
        val status = body(manager, "fun statusJson(): String")
        assertTrue(status.contains("""val streamMode = entityStreamMode(held, filtered)"""))
        assertTrue(status.contains(""".put("stream_mode", streamMode)"""))
        assertTrue(status.contains(""".put("strategy_selector_ignored", strategySelectorAllowed(streamMode, visibleIssues))"""))
        val member = body(manager, "fun strategySelectorAllowed(): Boolean")
        assertTrue(member, member.contains("entityStreamMode(held, filtered)"))
        assertTrue(member, member.contains("shouldHoldRendererForEntityBootstrap(config.dashboardEntityLearningEnabled, filtered)"))
        assertTrue(member, member.contains("val filtered = config.dashboardEntityFilterEnabled"))
        assertTrue(member, member.contains("visibleDashboardIssues(store.issuesJson(instance(), dashboardPath())"))
    }

    @Test fun `the configure page carries the strategy banner only while the manager reports it`() {
        val banners = server.substring(
            server.indexOf("private fun configureSetupBanners(strings: AppStrings)"),
            server.indexOf("private fun profilesBody(strings: AppStrings)"),
        )
        assertEquals(1, Regex("""strategySelectorAllowedBanner\(strings\)""").findAll(banners).count())
        val start = server.indexOf("private fun strategySelectorAllowedBanner(strings: AppStrings)")
        assertTrue("the banner builder exists", start >= 0)
        val builder = server.substring(start, server.indexOf('\n', server.indexOf("</div>", start)))
        assertTrue(builder, builder.contains("entityLearning.strategySelectorAllowed()"))
        assertTrue(builder, builder.contains("""<div class="setup info">"""))
        for (key in listOf("title", "body", "link")) {
            assertTrue(key, builder.contains("""strings.get("configure.setup.strategy_allowed.$key")"""))
        }
        assertTrue(builder, builder.contains("""localizedHref("entities", strings)"""))
    }

    @Test fun `the entities page states the strategy consequence and the pin route`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-strategy-allowed-test.mjs"),
            File(working, "src/test/js/entity-strategy-allowed-test.mjs"),
        ).first(File::isFile)
        val asset = listOf(
            File(working, "app/src/main/assets/entities.js"),
            File(working, "src/main/assets/entities.js"),
        ).first(File::isFile)
        val process = ProcessBuilder("node", fixture.absolutePath, asset.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("entity strategy allowed cases passed"))
    }
}
