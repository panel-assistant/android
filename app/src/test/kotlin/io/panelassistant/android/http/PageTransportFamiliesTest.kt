package io.panelassistant.android.http

import io.panelassistant.android.ManageFamilies
import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription families exist twice: the panel's registry (ManageFamilies), which the socket door and the
 * session serve, and the page transport's table (assets/transport.js), which its HTTP implementation polls
 * with. A page reads the same value on both transports only while the two agree on names, reads and floors.
 */
class PageTransportFamiliesTest {
    @Test fun `the page transport's families are the panel's families`() {
        Node.assumeAvailable()
        // Source-text reason: execute the shipped script and inspect the API its subscription consumers use.
        val script = """
            const fs = require('node:fs');
            const vm = require('node:vm');
            global.window = {};
            global.document = {};
            vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
            process.stdout.write(JSON.stringify(window.PaneldTransport.families));
        """.trimIndent()
        val (code, output) = Node.run("-e", script, TestSources.asset("transport.js").absolutePath)
        assertEquals(output, 0, code)
        val families = JSONObject(output)
        val page = families.keys().asSequence().associateWith { key ->
            val family = families.getJSONObject(key)
            val reads = family.getJSONArray("reads")
            Triple(family.getLong("floor"), (0 until reads.length()).map(reads::getString), family.optBoolean("append"))
        }
        assertTrue("the family API is populated", page.isNotEmpty())
        val panel = ManageFamilies.DEFAULT.mapValues { (_, family) -> Triple(family.floorMs, family.reads, family.append) }

        assertEquals(panel, page)
    }
}
