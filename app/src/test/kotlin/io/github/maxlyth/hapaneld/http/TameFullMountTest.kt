package io.github.maxlyth.hapaneld.http

import android.content.ContextWrapper
import android.content.pm.PackageManager
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.testApplication
import java.io.File
import org.json.JSONObject
import org.junit.Test
import sun.misc.Unsafe
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TameFullMountTest {
    @Test fun `full mount rejects invalid or unknown packages and enforces root admission`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
            testApplication {
                application { fixture.mount(this) }
                suspend fun post(body: String) = client.post("/api/v1/tame") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Accept, "application/json")
                    setBody(body)
                }
                val invalid = post("pkg=not-a-package")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertEquals("invalid or protected package\n", invalid.bodyAsText())
                val unknown = post("pkg=com.vendor.unknown")
                assertEquals(HttpStatusCode.BadRequest, unknown.status)
                assertEquals("invalid or protected package\n", unknown.bodyAsText())
                val origin = client.post("/api/v1/tame") {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, origin.status)
                val oversized = post("pkg=" + "x".repeat(PaneldServer.MAX_SMALL_FORM_POST_BODY_BYTES.toInt()))
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            }
        }
    }

    @Test fun `full mount requires physical approval before enabling a vendor package`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
            assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            LocalApprovalBroker.instance.clear()
            try {
                testApplication {
                    application {
                        intercept(ApplicationCallPipeline.Setup) {
                            context.mutableOriginConnectionPoint.remoteAddress = "192.168.50.20"
                        }
                        fixture.mount(this)
                    }
                    val pending = client.post("/api/v1/tame") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        setBody("action=untame&pkg=com.vendor.keep")
                    }
                    assertEquals(HttpStatusCode.Accepted, pending.status)
                    assertEquals("approval-required", JSONObject(pending.bodyAsText()).getString("error"))
                    assertEquals(emptyList(), fixture.config.tameVendorPackages)
                }
            } finally {
                LocalApprovalBroker.instance.clear()
            }
        }
    }

    @Test fun `repeated recommended requests retain durable selections and both response formats`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
            fixture.config.setTameVendorPackages("com.vendor.keep")
            testApplication {
                application { fixture.mount(this) }
                suspend fun recommended(accept: String) = client.post("/api/v1/tame") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Accept, accept)
                    setBody("action=recommended")
                }
                val json = recommended("application/json")
                assertEquals(HttpStatusCode.OK, json.status)
                val body = JSONObject(json.bodyAsText())
                assertEquals("started", body.getString("status"))
                assertEquals("install#cfg-tame", body.getString("return_to"))
                assertEquals(listOf("com.vendor.keep"), fixture.config.tameVendorPackages)
                val html = recommended("text/html")
                assertEquals(HttpStatusCode.OK, html.status)
                assertTrue(html.bodyAsText().contains("2;url=install#cfg-tame"))
                assertEquals(listOf("com.vendor.keep"), fixture.config.tameVendorPackages)
            }
        }
    }

    private fun prepare(fixture: PaneldServerHttpFixture) {
        // Source-text reason: load the shipped locale data as runtime input, not a code-shape assertion.
        field(fixture.server, "catalogueLoader\$delegate", lazyOf(CatalogueLoader {
            File("src/main/assets", it).readText()
        }))
        // Android package inventory is unavailable in this JVM fixture. The real controller must
        // classify it as unknown and refuse taming, rather than assuming an unobserved app is safe.
        val tame = unsafe.allocateInstance(TameController::class.java) as TameController
        field(tame, "context", object : ContextWrapper(null) {
            override fun getPackageManager(): PackageManager =
                throw UnsupportedOperationException("Package inventory unavailable in JVM fixture")
        })
        field(fixture.server, "tame", tame)
        field(fixture.server, "tameProfileCandidates", emptyList<Any>())
        val authority = TameReconcileAuthority(
            readDesired = { fixture.config.tameVendorPackages.toSet() },
            reconcile = { _, _ -> error("Closed tame authority must not actuate packages") },
            stopping = { true },
        )
        check(authority.closeAndJoin(1_000))
        field(fixture.server, "tameReconciliation", authority)
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private companion object {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
