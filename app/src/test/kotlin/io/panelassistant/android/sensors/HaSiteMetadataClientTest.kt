package io.panelassistant.android.sensors

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaSiteMetadataClientTest {
    @Test fun `site projection rounds coordinates and elevation without retaining exact location`() {
        val result = HaSiteMetadataProtocol.parse(
            JSONObject()
                .put("latitude", 51.507351)
                .put("longitude", -0.127758)
                .put("elevation", 34.6)
                .put("time_zone", "Europe/London")
                .put("location_name", "Exact private home name"),
            fetchedAtEpochMs = 1234,
        )!!

        assertEquals(51.51, result.latitude, 0.0)
        assertEquals(-0.13, result.longitude, 0.0)
        assertEquals(35, result.elevationMeters)
        assertEquals("Europe/London", result.timeZone)
        assertEquals(1234, result.fetchedAtEpochMs)
        assertEquals(setOf("latitude", "longitude", "elevationMeters", "timeZone", "fetchedAtEpochMs"),
            result::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet())
    }

    @Test fun `invalid coordinates fail closed while optional elevation may be omitted`() {
        assertNull(HaSiteMetadataProtocol.parse(JSONObject().put("latitude", 91).put("longitude", 0), 0))
        val noElevation = HaSiteMetadataProtocol.parse(
            JSONObject().put("latitude", -0.001).put("longitude", 0.001).put("elevation", "unknown"),
            0,
        )!!
        assertEquals(0.0, noElevation.latitude, 0.0)
        assertEquals(0.0, noElevation.longitude, 0.0)
        assertNull(noElevation.elevationMeters)
    }

    @Test fun `REST auth rejection forces one DashboardAuth refresh and retries`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val forces = mutableListOf<Boolean>()
        val transport = MetadataTransport(rejectOnce = true)
        val client = HaSiteMetadataClient(
            auth = HaApiSessionProvider { force ->
                forces += force
                HaApiSession("https://ha.example", "token-${forces.size}")
            },
            transport = transport,
            workerDispatcher = dispatcher,
            epochMillis = { 999 },
        )

        val result = client.fetch()

        assertEquals(HaSiteMetadataPhase.AVAILABLE, result.phase)
        assertEquals(listOf(false, true), forces)
        assertEquals(2, transport.calls)
        assertEquals(999L, result.metadata?.fetchedAtEpochMs)
    }

    @Test fun `unconfigured renderer does not attempt network`() = runTest {
        val transport = MetadataTransport()
        val client = HaSiteMetadataClient(
            auth = HaApiSessionProvider { HaApiSession("", null) },
            transport = transport,
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(HaSiteMetadataPhase.UNCONFIGURED, client.fetch().phase)
        assertEquals(0, transport.calls)
    }

    private class MetadataTransport(private val rejectOnce: Boolean = false) : HaAmbientTransport {
        var calls = 0
        override suspend fun config(baseUrl: String, accessToken: String): JSONObject {
            calls++
            if (rejectOnce && calls == 1) throw HaAuthenticationException("expired")
            return JSONObject().put("latitude", 10.123).put("longitude", 20.456).put("time_zone", "UTC")
        }
        override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? = error("unused")
        override suspend fun states(baseUrl: String, accessToken: String): JSONArray = error("unused")
    }
}
