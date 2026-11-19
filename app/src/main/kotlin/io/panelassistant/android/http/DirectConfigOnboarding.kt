package io.panelassistant.android.http

import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.haUrl
import io.panelassistant.android.util.closeBody
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves a handed-over HA address before the ordinary Configure transaction. */
internal class DirectConfigOnboarding(private val config: Config) {

    /**
     * Verify a handed-over Home Assistant URL from this panel's own network before accepting it.
     *
     * Rewrites the posted parameters rather than persisting separately, so an accepted address takes
     * exactly the same commit and live-apply path as one a person typed — the renderer reconfigure, the
     * read-back check and the mutation plan all behave identically.
     *
     * Three outcomes:
     *  - **already configured** — the panel has a URL, so the handover is dropped untouched. Adoption is
     *    re-runnable, and a retry must converge rather than overwrite a working panel.
     *  - **verified** — promoted to `ha_url`, and the handover field and its reason are cleared.
     *  - **failed** — the address is kept, with the reason, so the wizard can offer a correction that
     *    shows what was tried instead of an empty field.
     *
     * The provenance marker `ha_setup_handover` is NOT touched here. It rides the POST as an ordinary
     * key and is stored whatever this decides, because a handed-over address that did not answer still
     * means Home Assistant deployed this panel.
     */
    suspend fun augmentPostWithVerifiedHandover(posted: Parameters): HandoverConfigPost =
        rewritePostForHandover(posted, config.haUrl) { offered ->
            (withTimeoutOrNull(HaUrlHandover.PROBE_DEADLINE_MS) { verifyHandedOverHaUrl(offered) }
                ?: HaUrlHandover.Outcome.TIMEOUT)
                .also { Log.i(TAG, "config: handed-over Home Assistant URL verification ${it.name.lowercase()}") }
        }

    /**
     * One bounded unauthenticated `GET {url}/auth/providers` from the panel itself.
     *
     * Home Assistant answers that with its login provider list and never with 401, so the check raises
     * no "Login attempt failed" notification. See [HaUrlHandover] for why anything else answering,
     * including a bare 200, is a failure.
     */
    private suspend fun verifyHandedOverHaUrl(url: String): HaUrlHandover.Outcome {
        val probe = HaUrlHandover.probeUrl(url) ?: return HaUrlHandover.Outcome.INVALID
        val connection = runCatching { java.net.URL(probe).openConnection() as java.net.HttpURLConnection }
            .getOrElse { return HaUrlHandover.classifyFailure(it) }
        // Not a child of the caller: blocking socket I/O ignores cancellation, and closing a connection
        // mid-body can itself block while the JDK drains it, so a deadline or a preferred address
        // answering first returns at once and the connection is closed off the caller's path.
        val io = ioScope.async { readProbe(connection) }
        return try {
            io.await()
        } catch (cancelled: CancellationException) {
            ioScope.launch { connection.disconnect() }
            throw cancelled
        }
    }

    private fun readProbe(connection: java.net.HttpURLConnection): HaUrlHandover.Outcome =
        runCatching {
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = HaUrlHandover.CONNECT_TIMEOUT_MS
                connection.readTimeout = HaUrlHandover.READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                val contentType = connection.contentType
                if (HaUrlHandover.mayBeHomeAssistant(status, contentType)) {
                    val body = connection.inputStream.use { readBoundedBody(it) }
                    HaUrlHandover.classifyResponse(status, contentType, body)
                } else {
                    connection.closeBody()
                    HaUrlHandover.classifyResponse(status, contentType, null)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse(HaUrlHandover::classifyFailure)

    /** The body as UTF-8, or null when it exceeds [HaUrlHandover.MAX_BODY_BYTES]. */
    private fun readBoundedBody(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > HaUrlHandover.MAX_BODY_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val TAG = "ha-paneld/http"
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
