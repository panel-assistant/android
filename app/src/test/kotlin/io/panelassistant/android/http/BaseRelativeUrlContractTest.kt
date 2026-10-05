package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every URL the web interface emits resolves against `<base href="/">`, so Panel Assistant's proxy can mount
 * the interface under its own path by rewriting that one element. A root-relative literal would escape the
 * proxy, so a new one fails here unless it is listed below with its reason.
 */
class BaseRelativeUrlContractTest {
    // Source-text reason: lints every shipped page script and every http markup file for proxy-escaping URLs; no single name is pinned.
    private val assets = File("src/main/assets")
    private val http = File("src/main/kotlin/io/panelassistant/android/http")

    /** Quoted literals in page scripts that start with `/` and are not URLs the page loads or links. */
    private val scriptExceptions = mapOf(
        // OpenAPI operation paths, matched against the specification's own keys (the API tab is hidden when embedded).
        "api.js" to setOf("/api/v1/config", "/api/v1/config/export", "/api/v1/config/import", "/api/v1/restore", "/api/v1/action"),
        // Rate units shown as text.
        "info.js" to setOf("/hr", "/s"),
        // Suffixes appended to the base-relative profiles API root.
        "profiles.js" to setOf("/", "/revisions/", "/report", "/probe", "/import", "/template", "/device-draft", "/rollback", "/select", "/delete", "/schema"),
        // Home Assistant dashboard routes and their placeholder, not panel URLs.
        "setup.js" to setOf("/", "/dashboard-name/tab-name"),
        "configure-state.js" to setOf("/"),
        "configure-controls.js" to setOf("/dashboard-name/tab-name"),
        // RTSP stream path appended to the page host, not a panel HTTP URL.
        "configure-help.js" to setOf("/live"),
    )

    private val scriptLiteral = Regex("""(?<![\w\\])(["'`])(/(?:[A-Za-z_?#][^"'`\s]*)?)\1""")

    @Test fun `page scripts emit no root-relative URL literal`() {
        val offenders = pageSources().flatMap { file ->
            val allowed = scriptExceptions[file.name].orEmpty()
            file.readLines().withIndex()
                .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .flatMap { (index, line) ->
                    scriptLiteral.findAll(line.replace("<base href=\"/\">", "")).map { it.groupValues[2] }
                        .filterNot { it in allowed }
                        .map { "${file.name}:${index + 1}: $it" }
                        .toList()
                }
        }
        assertEquals("root-relative literals escape the proxy base", emptyList<String>(), offenders)
    }

    @Test fun `the scanner finds the literal shapes it guards`() {
        listOf("fetch(\"/api/v1/status\")", "el.src = '/assets/x.js'", "location.href = `/configure`", "a.href = \"/\"")
            .forEach { assertTrue(it, scriptLiteral.containsMatchIn(it)) }
        listOf("fetch(\"api/v1/status\")", "x.split(\"/\")[0] + '/hr'.length", "/^\\/foo/.test(v)")
            .forEach { sample -> assertTrue(sample, scriptLiteral.findAll(sample).all { it.groupValues[2] in setOf("/", "/hr") }) }
    }

    // The base element itself is the one root-relative URL, and the proxy rewrites it.
    private val markupLiteral = Regex("""(?:(?<!<base )(?:href|src|action)=\\?["']|url=|(?:localizedHref|setupHref)\(")/(?!/)""")

    @Test fun `server markup emits no root-relative URL`() {
        val offenders = http.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
            // The OAuth callback page is Home Assistant's redirect target on the LAN, never proxied.
            .filterNot { it.name == "HaOAuthRoutes.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> markupLiteral.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim().take(120)}" }
            }
        assertEquals("root-relative markup escapes the proxy base", emptyList<String>(), offenders)
        listOf("""<a href="/configure">""", """"<a href=\"/install\">"""", "content='2;url=/configure'", """localizedHref("/api", strings)""")
            .forEach { assertTrue(it, markupLiteral.containsMatchIn(it)) }
    }

    /** Under the base element a bare `#fragment` resolves against the base, so a fragment link must cancel its own navigation. */
    @Test fun `every fragment link cancels its navigation`() {
        val fragmentScript = Regex("""href\s*[:=]\s*["']#""")
        val handled = Regex("""onclick|addEventListener\(\s*["']click["']|preventDefault""")
        val scriptOffenders = pageSources().filter { it.extension == "js" }.flatMap { file ->
            val lines = file.readLines()
            lines.indices.filter { fragmentScript.containsMatchIn(lines[it]) }
                .filterNot { i -> (i until minOf(lines.size, i + 7)).any { handled.containsMatchIn(lines[it]) } }
                .map { "${file.name}:${it + 1}" }
        }
        val fragmentMarkup = Regex("""href=\\?["']#""")
        val markupOffenders = http.listFiles { f -> f.extension == "kt" }!!.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> fragmentMarkup.containsMatchIn(line) && !line.contains("onclick=") }
                .map { (index, _) -> "${file.name}:${index + 1}" }
        }
        assertEquals("fragment links leave the page under <base href=\"/\">", emptyList<String>(), scriptOffenders + markupOffenders)
        assertTrue(fragmentScript.containsMatchIn("""el("a", { href: "#" + HASH_OF_DOT[i], text: label })"""))
        assertTrue(!handled.containsMatchIn("""el("a", { href: "#cfg-proximity-learning", class: "pbtn" })"""))
    }

    private fun pageSources(): List<File> =
        assets.listFiles { f -> f.isFile && (f.extension == "js" || f.extension == "html") }!!.sortedBy { it.name }
}
