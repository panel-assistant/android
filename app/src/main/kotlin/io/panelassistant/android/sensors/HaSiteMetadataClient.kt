package io.panelassistant.android.sensors

import io.panelassistant.android.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.round
import kotlin.math.roundToInt

data class HaSiteMetadata(
    /** Rounded to about 1.1 km at the equator; exact home coordinates never leave this client. */
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Int?,
    val timeZone: String,
    val fetchedAtEpochMs: Long,
)

enum class HaSiteMetadataPhase { AVAILABLE, UNCONFIGURED, AUTH_FAILED, UNAVAILABLE, INVALID }

data class HaSiteMetadataResult(
    val phase: HaSiteMetadataPhase,
    val metadata: HaSiteMetadata? = null,
    val detail: String = "",
)

/** Reads the HA site's solar inputs through the renderer's existing DashboardAuth credentials. */
class HaSiteMetadataClient internal constructor(
    private val auth: HaApiSessionProvider,
    private val transport: HaAmbientTransport,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val epochMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(config: Config) : this(DashboardHaApiSessionProvider(config), KtorHaAmbientTransport())

    /** Safe to invoke from the main thread: authentication and network I/O are dispatched internally. */
    suspend fun fetch(): HaSiteMetadataResult = withContext(workerDispatcher) {
        try {
            var session = auth.resolve(force = false)
            if (session.baseUrl.isBlank()) return@withContext HaSiteMetadataResult(HaSiteMetadataPhase.UNCONFIGURED)
            if (session.rejected || session.accessToken.isNullOrBlank()) {
                return@withContext HaSiteMetadataResult(HaSiteMetadataPhase.AUTH_FAILED, detail = "Home Assistant credentials are unavailable")
            }
            val config = try {
                transport.config(session.baseUrl, session.accessToken)
            } catch (_: HaAuthenticationException) {
                session = auth.resolve(force = true)
                if (session.rejected || session.accessToken.isNullOrBlank()) {
                    return@withContext HaSiteMetadataResult(HaSiteMetadataPhase.AUTH_FAILED, detail = "Home Assistant rejected the configured credentials")
                }
                transport.config(session.baseUrl, session.accessToken)
            }
            val metadata = HaSiteMetadataProtocol.parse(config, epochMillis())
                ?: return@withContext HaSiteMetadataResult(HaSiteMetadataPhase.INVALID, detail = "Home Assistant returned invalid site coordinates")
            HaSiteMetadataResult(HaSiteMetadataPhase.AVAILABLE, metadata)
        } catch (rejected: HaAuthenticationException) {
            HaSiteMetadataResult(HaSiteMetadataPhase.AUTH_FAILED, detail = "Home Assistant rejected the configured credentials")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            HaSiteMetadataResult(
                HaSiteMetadataPhase.UNAVAILABLE,
                detail = error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(240)
                    ?.takeIf(String::isNotBlank) ?: "Home Assistant site metadata is unavailable",
            )
        }
    }
}

internal object HaSiteMetadataProtocol {
    fun parse(config: JSONObject, fetchedAtEpochMs: Long): HaSiteMetadata? {
        val latitude = config.optDouble("latitude", Double.NaN)
        val longitude = config.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) return null

        val elevation = config.optDouble("elevation", Double.NaN)
            .takeIf { it.isFinite() && it in -1_000.0..10_000.0 }
            ?.roundToInt()
        return HaSiteMetadata(
            latitude = roundCoordinate(latitude),
            longitude = roundCoordinate(longitude),
            elevationMeters = elevation,
            timeZone = config.optString("time_zone").trim().take(MAX_TIME_ZONE_CHARS),
            fetchedAtEpochMs = fetchedAtEpochMs,
        )
    }

    private fun roundCoordinate(value: Double): Double {
        val rounded = round(value * COORDINATE_SCALE) / COORDINATE_SCALE
        return if (rounded == -0.0) 0.0 else rounded
    }

    private const val COORDINATE_SCALE = 100.0
    private const val MAX_TIME_ZONE_CHARS = 100
}
