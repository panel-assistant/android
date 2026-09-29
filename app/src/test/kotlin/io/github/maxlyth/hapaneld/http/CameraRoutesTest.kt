package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.camera.CameraPresentation
import io.github.maxlyth.hapaneld.camera.CameraRefusal
import io.github.maxlyth.hapaneld.camera.CameraResolution
import io.github.maxlyth.hapaneld.camera.CameraSurface
import io.github.maxlyth.hapaneld.camera.SnapshotResult
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRoutesTest {
    @Test fun `full mount preserves passive status active snapshot and refusal mapping`() {
        val requests = mutableListOf<CameraResolution?>()
        var result: SnapshotResult = SnapshotResult.Jpeg(byteArrayOf(1, 2, 3))
        val camera = object : CameraSurface {
            override fun presentation() = CameraPresentation.absent()
            override fun snapshot(requested: CameraResolution?): SnapshotResult {
                requests += requested
                return result
            }
        }
        PaneldServerHttpFixture(camera = camera).use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val status = client.get("/api/v1/camera/status") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.OK, status.status)
                assertEquals(camera.presentation().statusJson(), status.bodyAsText())
                assertEquals("no-store", status.headers[HttpHeaders.CacheControl])
                assertEquals(emptyList<CameraResolution?>(), requests)
                val refused = client.get("/api/v1/camera/snapshot.jpg") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                val invalid = client.get("/api/v1/camera/snapshot.jpg?res=bad")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertEquals("unknown res 'bad' (480p|720p|1080p)\n", invalid.bodyAsText())
                assertNull(invalid.headers[HttpHeaders.CacheControl])
                assertEquals(emptyList<CameraResolution?>(), requests)
                val jpeg = client.get("/api/v1/camera/snapshot.jpg?res=720p")
                assertEquals(HttpStatusCode.OK, jpeg.status)
                assertEquals("image/jpeg", jpeg.headers[HttpHeaders.ContentType])
                assertEquals("no-store", jpeg.headers[HttpHeaders.CacheControl])
                assertArrayEquals(byteArrayOf(1, 2, 3), jpeg.bodyAsBytes())
                assertEquals(listOf(CameraResolution.P720), requests)
                for (reason in CameraRefusal.entries) {
                    result = SnapshotResult.Refused(reason)
                    val response = client.get("/api/v1/camera/snapshot.jpg")
                    assertEquals(if (reason == CameraRefusal.ABSENT) HttpStatusCode.NotFound else HttpStatusCode.ServiceUnavailable, response.status)
                    assertEquals(reason.token + "\n", response.bodyAsText())
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                }
                assertEquals(1 + CameraRefusal.entries.size, requests.size)
                assertNull(requests.last())
            }
        }
    }
}
