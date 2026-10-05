package io.panelassistant.android.util

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server half of the shared route-admission matrix in `tools/test/fixtures/dashboard-path-parity.json`.
 *
 * The browser suite reads the same file and asserts the client never refuses a route this side accepts.
 * Exact parity between a Kotlin canonicalizer and a JavaScript expression is unreachable — the server
 * percent-decodes to reject traversal and a client expression cannot — so the client is a deliberate
 * superset and this side remains the authority. The matrix is shared data precisely because two
 * independently maintained expressions once diverged in behaviour while comparing equal as strings.
 */
class DashboardPathParityTest {
    private val matrix = JSONObject(
        File("../tools/test/fixtures/dashboard-path-parity.json").takeIf { it.isFile }
            ?.readText() ?: File("tools/test/fixtures/dashboard-path-parity.json").readText(),
    )

    private fun cases(key: String): List<String> =
        matrix.getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) } }

    @Test fun everyRouteTheMatrixCallsAdmissibleIsAdmitted() {
        val accepted = cases("serverAccepts")
        assertTrue("the matrix must carry cases", accepted.size >= 10)
        for (route in accepted) {
            val canonical = DashboardPath.canonical(route, preserveRoute = true)
            assertNotNull("server refused $route", canonical)
            assertTrue("canonical form of $route must be rooted: $canonical", canonical!!.startsWith("/"))
        }
    }

    @Test fun everyRouteTheMatrixCallsInadmissibleIsRefused() {
        for (route in cases("serverRejects")) {
            assertNull("server admitted $route", DashboardPath.canonical(route, preserveRoute = true))
        }
    }

    @Test fun everySpellingTheMatrixCallsAccountDefaultNamesNoDashboard() {
        for (route in cases("meansAccountDefault")) {
            assertTrue("$route should mean the account default", DashboardPath.followsAccountDefault(route))
        }
    }
}
