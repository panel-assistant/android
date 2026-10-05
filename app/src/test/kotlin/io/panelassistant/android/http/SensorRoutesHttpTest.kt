package io.panelassistant.android.http

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import io.panelassistant.android.sensors.EnvironmentalSensorUse
import io.panelassistant.android.sensors.SensorReporter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SensorRoutesHttpTest {
    @Test fun `passive sensor reads report live brightness and unavailable fallback values`() {
        PaneldServerHttpFixture().use { fixture ->
            val contextField = PaneldServer::class.java.getDeclaredField("appContext").apply { isAccessible = true }
            val context = contextField.get(fixture.server) as Context
            contextField.set(fixture.server, object : ContextWrapper(context) {
                override fun getFilesDir() = context.filesDir
                override fun getCacheDir() = context.cacheDir
                override fun getNoBackupFilesDir() = context.noBackupFilesDir
                override fun getPackageName() = context.packageName
                override fun getContentResolver(): ContentResolver = error("System settings unavailable")
            })
            val sensors = PaneldServer::class.java.getDeclaredField("sensors").run {
                isAccessible = true
                get(fixture.server) as SensorReporter
            }
            for (name in listOf("tempUse", "humidityUse")) {
                SensorReporter::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .set(sensors, EnvironmentalSensorUse.ABSENT)
            }
            var brightness = 123
            PaneldServer::class.java.getDeclaredField("effectiveBrightness").apply { isAccessible = true }
                .set(fixture.server, { brightness })
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/sensors") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                val body = JSONObject(response.bodyAsText())
                assertEquals(123, body.getInt("brightness"))
                assertEquals(-1, body.getInt("volume_pct"))
                for (key in listOf("light", "proximity", "temperature", "humidity")) {
                    assertFalse(body.getJSONObject(key).getBoolean("present"))
                }
                brightness = -1
                val unavailable = client.get("/api/v1/sensors")
                assertEquals(HttpStatusCode.OK, unavailable.status)
                assertEquals(-1, JSONObject(unavailable.bodyAsText()).getInt("brightness"))
                val refused = client.get("/api/v1/sensors") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
            }
        }
    }
}
