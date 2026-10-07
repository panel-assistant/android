package io.panelassistant.android.http

import io.panelassistant.android.control.PrivilegeRoute
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteInputHttpTest {
    @Test fun `combined tap success returns png and complete correlation headers`() = testApplication {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        application {
            routing {
                post("/result") {
                    respondTapCaptureResult(
                        call = call,
                        requestId = 17,
                        result = TapCaptureResult.Success(
                            PrivilegeRoute.ACCESSIBILITY,
                            PrivilegeRoute.DAEMON,
                            png,
                        ),
                        stopping = false,
                        cacheScreenshot = { "a".repeat(64) },
                    )
                }
            }
        }

        val response = client.post("/result")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.headers[HttpHeaders.ContentType]?.let(ContentType::parse))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("17", response.headers["X-ha-paneld-Input-Id"])
        assertEquals("accessibility", response.headers["X-ha-paneld-Input-Route"])
        assertEquals("daemon", response.headers["X-ha-paneld-Screenshot-Route"])
        assertEquals("a".repeat(64), response.headers["X-ha-paneld-Screenshot-Id"])
        assertArrayEquals(png, response.bodyAsBytes())
    }

    @Test fun `combined tap failures retain precise status error and known route`() = testApplication {
        var result: TapCaptureResult = TapCaptureResult.TapFailed()
        var stopping = false
        application {
            routing {
                post("/result") {
                    respondTapCaptureResult(call, 23, result, stopping) { null }
                }
            }
        }

        suspend fun assertFailure(
            value: TapCaptureResult,
            status: HttpStatusCode,
            error: String,
            inputRoute: String? = null,
            isStopping: Boolean = false,
        ) {
            result = value
            stopping = isStopping
            val response = client.post("/result")
            assertEquals(error, status, response.status)
            assertEquals(error, """{"ok":false,"error":"$error"}""", response.bodyAsText())
            assertEquals(error, "23", response.headers["X-ha-paneld-Input-Id"])
            assertEquals(error, inputRoute, response.headers["X-ha-paneld-Input-Route"])
        }

        assertFailure(TapCaptureResult.TapFailed(), HttpStatusCode.BadGateway, "tap-failed")
        assertFailure(
            TapCaptureResult.ScreenshotFailed(PrivilegeRoute.SU),
            HttpStatusCode.ServiceUnavailable,
            "screenshot-unavailable",
            "su",
        )
        assertFailure(
            TapCaptureResult.CompletionUnknown(PrivilegeRoute.ACCESSIBILITY),
            HttpStatusCode.GatewayTimeout,
            "completion-unknown",
            "accessibility",
        )
        assertFailure(
            TapCaptureResult.HardenedRefusal,
            HttpStatusCode.Forbidden,
            "remote-input-disabled",
        )
        assertFailure(TapCaptureResult.Expired, HttpStatusCode.GatewayTimeout, "tap-expired")
        assertFailure(TapCaptureResult.NotExecuted, HttpStatusCode.Conflict, "tap-superseded")
        assertFailure(
            TapCaptureResult.NotExecuted,
            HttpStatusCode.ServiceUnavailable,
            "control-plane-stopping",
            isStopping = true,
        )
    }
}
