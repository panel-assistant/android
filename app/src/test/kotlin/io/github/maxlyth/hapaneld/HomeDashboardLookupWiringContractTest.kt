package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts for every reader of the activity's home dashboard lookup. The lookup is nullable, so
 * the compiler already forces each caller to handle "not resolved yet"; these pin what each one does
 * with it, since the activity cannot run on this gate. The pure halves are executed in
 * `DashboardIdleReturnTickTest`.
 */
class HomeDashboardLookupWiringContractTest {
    private val source = TestSources.kotlin("DashboardActivity.kt").readText()

    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val end = Regex("\n    (?:override |private |internal )?fun ").find(source, start + signature.length)
            ?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test fun `no reader can throw on an unresolved home dashboard`() {
        assertFalse(source.contains("used before authenticated resolution"))
        assertTrue(source.contains("private fun resolvedHomeDashboard(config: Config): String? ="))
    }

    @Test fun `the lookup has exactly the readers pinned here`() {
        val readers = Regex("""(?<!fun )\bresolvedHomeDashboard\(""").findAll(source).map { match ->
            Regex("""fun (\w+)\(""").findAll(source.substring(0, match.range.first)).last().groupValues[1]
        }.toList()
        assertEquals(
            listOf("onNewIntent", "lightRefresh", "onIdleCheck", "reloadTarget", "loadCorrectedHomeDashboard"),
            readers,
        )
        assertEquals(2, Regex("""\bownedHomeDashboardPath\(""").findAll(source).count())
    }

    @Test fun `the idle tick reads the lookup only through the guarded policy and logs an episode once`() {
        val idle = body("private fun onIdleCheck(")
        assertTrue(idle.contains("DashboardIdleReturnPolicy.tick("))
        assertTrue(idle.contains("homeDashboard = { resolvedHomeDashboard(config) }"))
        assertTrue(idle.contains("if (idleUnresolvedNotice.shouldLog(tick))"))
    }

    @Test fun `a reload or relaunch with no resolved home re-enters admission`() {
        assertTrue(
            body("override fun onNewIntent(").contains(
                "val targetPath = nav ?: resolvedHomeDashboard(config)\n" +
                    "            ?: return readmitForHomeDashboard(",
            ),
        )
        val readmit = body("private fun readmitForHomeDashboard(")
        assertTrue(readmit.indexOf("teardownWeb()") in 0 until readmit.indexOf("buildAndLoad(Config(this))"))
    }

    @Test fun `a reload over the reconnecting page re-enters admission before rotating the bus`() {
        val reload = body("private fun reloadTarget(")
        val readmit = reload.indexOf("readmitForHomeDashboard(\"reload over the reconnecting page\")")
        assertTrue(readmit in 0 until reload.indexOf("rotateBusDocument("))
        assertTrue(reload.substring(readmit).startsWith("readmitForHomeDashboard(\"reload over the reconnecting page\")\n                return false"))
    }

    @Test fun `a light refresh with no route and no resolved home clears its spinner and stops`() {
        val refresh = body("private fun lightRefresh(")
        val fallback = refresh.substring(refresh.indexOf("resolvedHomeDashboard(Config(this)) ?: run {"))
        assertTrue(fallback.indexOf("swipe?.isRefreshing = false") in 0 until fallback.indexOf("return"))
        assertTrue(fallback.indexOf("return") < fallback.indexOf("sendBusNavigate(path)"))
    }

    @Test fun `a correction load and every renderer build use a resolved home only`() {
        assertTrue(body("private fun loadCorrectedHomeDashboard(").contains("val home = resolvedHomeDashboard(config) ?: return"))
        assertTrue(source.contains("private fun currentUrl(config: Config, home: String): String"))
        assertTrue(source.contains("private fun buildCompatibleAndLoad(config: Config, homePath: String)"))
        assertTrue(body("private fun buildCompatibleAndLoad(").contains("currentUrl(config, homePath)"))
    }
}
