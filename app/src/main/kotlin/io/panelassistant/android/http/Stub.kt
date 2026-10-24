package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.decodeURLPart
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageBlobs
import io.panelassistant.android.ManageDoor
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.isLoopbackPeer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/**
 * The stub's closed endpoint list (protocol section 20.7), defined once. Paths are Ktor route templates: a
 * `{name}` segment is one path segment and a `{name...}` segment is the rest of the path. With the REST routes
 * closed (section 20.8, [io.panelassistant.android.Config.uiRoutesClosed]) every other request answers [GONE_BODY];
 * the step-1 retirements of section 20.8 answer it in every build.
 */
internal object StubEndpoints {
    /** The rendered pages and the `page.<name>` each one answers. */
    val PAGES: Map<String, String> = linkedMapOf(
        "/" to "info", "/configure" to "configure", "/setup" to "setup", "/profiles" to "profiles",
        "/install" to "install", "/logs" to "logs", "/entities" to "entities",
    )
    val HEALTH = listOf("/health", "/api/v1/health")
    val DIAG = listOf("/diag", "/api/v1/diag")
    const val UI = "/ui"
    const val BLOB = "/blob/{token}"
    /**
     * Sign-in navigations, served by their existing handlers until the setup page carries them (protocol section
     * 20.6): the panel's own WebView opens the first on loopback (DashboardRecovery.kt) and Home Assistant
     * redirects the browser to the second. Neither is a page module's REST read, so closing the routes must not
     * end sign-in.
     */
    val SIGN_IN = listOf("/api/v1/ha/oauth/panel-start", "/api/v1/ha/oauth/callback")

    val ENDPOINTS: Set<Pair<HttpMethod, String>> = buildSet {
        // 1. Bundle files, served from the APK.
        listOf("/info.js", "/info.css", "/icon.svg", "/favicon.svg", "/assets/{f...}").forEach { add(HttpMethod.Get to it) }
        // 2. The pages, 3. health, 4. diag, 5. the door, 6. blobs; and the sign-in navigations.
        (PAGES.keys + HEALTH + DIAG + UI + BLOB + SIGN_IN).forEach { add(HttpMethod.Get to it) }
        add(HttpMethod.Put to BLOB)
    }

    /** Step 1 of section 20.8: retired in every build, whatever the method. */
    val RETIRED: Set<String> = setOf(
        "/test",
        "/api/v1/proximity/capture", "/api/v1/proximity/relearn", "/api/v1/proximity/reset",
        "/api/v1/proximity/sensitivity", "/api/v1/proximity/teach", "/api/v1/proximity/test",
        "/api/v1/proximity/threshold",
        "/api/v1/config/probe-log-sink", "/api/v1/logship/status", "/api/v1/perf/costs", "/api/v1/perf/history",
        "/api/v1/power-safety/state", "/api/v1/provisioning/plan", "/api/v1/provisioning/plan.txt",
        "/api/v1/sensortrace", "/api/v1/ui/layout",
    )

    val GONE_BODY: String = JSONObject().put("error", "gone").put("docs", "https://github.com/panel-assistant/android/releases").toString()

    /** Whether the stub serves [method] [path]. HEAD reads as GET. */
    fun admits(method: HttpMethod, path: String): Boolean {
        val asked = if (method == HttpMethod.Head) HttpMethod.Get else method
        return ENDPOINTS.any { (m, template) -> m == asked && matches(template, path) }
    }

    /** [rawPath] as routing reads it: percent-decoded, repeated slashes collapsed, no trailing slash. */
    fun normalize(rawPath: String): String =
        "/" + runCatching { rawPath.decodeURLPart() }.getOrDefault(rawPath).split('/').filter(String::isNotEmpty).joinToString("/")

    /** Whether [rawPath] is gone: a step-1 retirement, or any path the stub does not serve once the routes close. */
    fun gone(method: HttpMethod, rawPath: String, routesClosed: Boolean): Boolean {
        val path = normalize(rawPath)
        return path in RETIRED || (routesClosed && !admits(method, path))
    }

    private fun matches(template: String, path: String): Boolean {
        val want = template.split('/').filter(String::isNotEmpty)
        val got = path.split('/').filter(String::isNotEmpty)
        want.forEachIndexed { i, segment ->
            if (segment.startsWith("{") && segment.endsWith("...}")) return got.size > i
            val actual = got.getOrNull(i) ?: return false
            if (!(segment.startsWith("{") && segment.endsWith("}")) && segment != actual) return false
        }
        return want.size == got.size
    }
}

/** The stub's 410 answers by method and path: the first [limit] distinct, then one overflow count (section 20.7). */
internal class GoneCounts(private val limit: Int = 64) {
    private val counts = LinkedHashMap<String, Long>()
    private var overflow = 0L

    @Synchronized fun count(method: HttpMethod, path: String) {
        val key = "${method.value} $path"
        when {
            key in counts -> counts[key] = counts.getValue(key) + 1
            counts.size < limit -> counts[key] = 1
            else -> overflow++
        }
    }

    /** The lines `/diag` prints. */
    @Synchronized fun text(): String = buildString {
        append("gone (410) answers: ").append(counts.values.sum() + overflow).append('\n')
        counts.forEach { (key, n) -> append("  ").append(key).append(' ').append(n).append('\n') }
        if (overflow > 0) append("  other paths ").append(overflow).append('\n')
    }
}

/**
 * One-use blob transfers on the stub (protocol section 20.5): a token lives ten minutes and is spent by its
 * first use. [put] holds a reply's bytes for one `GET /blob/<token>`; [mintUpload] answers `blob.upload` with a
 * token one `PUT /blob/<token>` fills; [take] hands those bytes to the request whose `args.blob` names them,
 * only when the reference's size and SHA-256 match. An upload is written to a file in [dir] as it arrives, never
 * held in memory, and the file is deleted once read, refused or expired.
 */
internal class StubBlobs(
    private val dir: java.io.File? = null,
    private val maxBytes: Long = MAX_BYTES,
    private val ttlMs: Long = 10 * 60_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : ManageBlobs {
    private class Slot(
        val upload: Boolean,
        val expiresAt: Long,
        /** A reply's bytes, for a download. */
        val bytes: ByteArray?,
        val type: String,
        /** For an upload: the most bytes its PUT may carry (the size `blob.upload` declared, within the cap). */
        val limit: Long = 0,
    ) {
        /** For an upload, once its PUT completed: the file holding the body, its size and SHA-256. */
        @Volatile var file: java.io.File? = null
        @Volatile var size: Long = -1
        @Volatile var sha256: String = ""

        @Synchronized fun discard() { file?.delete() }
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val random = SecureRandom()
    /** Uploads whose bodies are arriving now, an evicted one included until it ends; never more than the slots. */
    private val filling = java.util.concurrent.atomic.AtomicInteger()

    init {
        // Uploads a previous process left behind are never readable again.
        dir?.listFiles()?.forEach { it.delete() }
    }

    private fun mint(slot: Slot): String {
        purge()
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        slots[token] = slot
        // Bound what a stream of unread transfers can hold: the oldest is dropped first.
        while (slots.size > MAX_SLOTS) slots.entries.minByOrNull { it.value.expiresAt }?.let { slots.remove(it.key)?.discard() }
        return token
    }

    private fun purge() {
        val now = nowMs()
        slots.entries.filter { it.value.expiresAt <= now }.forEach { slots.remove(it.key)?.discard() }
    }

    override suspend fun put(bytes: ByteArray, type: String): JSONObject {
        val token = mint(Slot(upload = false, expiresAt = nowMs() + ttlMs, bytes = bytes, type = type))
        return reference(token, bytes.size.toLong(), sha256Hex(bytes), type)
    }

    /**
     * Answer `blob.upload`: where the reader PUTs the bytes it then names in `args.blob`. A declared [size] bounds
     * that PUT; without one the cap does. Null when the declared size is over the cap: the upload is refused, never
     * quietly lowered.
     */
    fun mintUpload(type: String, size: Long? = null): JSONObject? {
        if (size != null && size > maxBytes) return null
        val limit = size ?: maxBytes
        val token = mint(Slot(upload = true, expiresAt = nowMs() + ttlMs, bytes = null, type = type, limit = limit))
        return JSONObject().put("url", "/blob/$token").put("max_size", limit)
    }

    /** The most bytes a PUT to [token] may carry, or null when [token] names no open upload. */
    fun uploadLimit(token: String): Long? =
        slots[token]?.takeIf { it.upload && it.file == null && it.expiresAt > nowMs() }?.limit

    override suspend fun take(ref: JSONObject): java.io.InputStream? {
        purge()
        val token = ref.optString("url").substringAfterLast('/')
        val slot = slots.remove(token) ?: return null
        if (!slot.upload || slot.expiresAt <= nowMs()) {
            slot.discard()
            return null
        }
        val file = slot.file ?: return null
        if (ref.optLong("size", -1) != slot.size || !ref.optString("sha256").equals(slot.sha256, ignoreCase = true)) {
            slot.discard()
            return null
        }
        return object : java.io.FilterInputStream(java.io.FileInputStream(file)) {
            override fun close() {
                try { super.close() } finally { file.delete() }
            }
        }
    }

    /** The download [token] names, spent by this read; null when there is none. */
    fun download(token: String): Pair<ByteArray, String>? {
        purge()
        val slot = slots.remove(token)?.takeIf { !it.upload && it.expiresAt > nowMs() } ?: return null
        return (slot.bytes ?: return null) to slot.type
    }

    /**
     * Fill the upload [token] once from [body], at most [limit] bytes, into a file; answers the reference, or null
     * when there is no such open upload. Throws [ByteLimitExceeded] past the limit, leaving nothing behind, and
     * [Busy] when as many uploads are already arriving as there are slots.
     */
    fun fill(token: String, limit: Long, body: java.io.InputStream): JSONObject? {
        purge()
        val slot = slots[token]?.takeIf { it.upload && it.file == null && it.expiresAt > nowMs() } ?: return null
        if (filling.incrementAndGet() > MAX_SLOTS) {
            filling.decrementAndGet()
            throw Busy()
        }
        try {
            return fillSlot(token, slot, limit, body)
        } finally {
            filling.decrementAndGet()
        }
    }

    private fun fillSlot(token: String, slot: Slot, limit: Long, body: java.io.InputStream): JSONObject? {
        dir?.mkdirs()
        val file = java.io.File.createTempFile("blob", ".part", dir)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val size = try {
            java.security.DigestOutputStream(java.io.FileOutputStream(file), digest).use { BoundedStreams.copy(body, it, limit) }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        synchronized(slot) {
            if (slot.file != null || slots[token] !== slot) {
                file.delete()
                return null
            }
            slot.size = size
            slot.sha256 = sha
            slot.file = file
        }
        return reference(token, size, sha, "application/octet-stream")
    }

    /** As many uploads are arriving as there are slots; another is refused until one ends. */
    class Busy : IllegalStateException("too many uploads in progress")

    companion object {
        /** The largest body an HTTP route accepts (an APK); each route still bounds its own body when it reads it. */
        const val MAX_BYTES = PaneldServer.MAX_APK_UPLOAD_BYTES
        private const val MAX_SLOTS = 4

        fun reference(token: String, size: Long, sha256: String, type: String): JSONObject = JSONObject()
            .put("url", "/blob/$token").put("size", size).put("sha256", sha256).put("type", type)
    }
}

/**
 * The stub of protocol section 20.7, mounted beneath the production root guards: pages answered by
 * `page.<name>` through [door], the health line, the diag text, the `/ui` socket and blob transfers. Bundle
 * files stay with [assetRoutes]. It holds no operation handlers of its own.
 */
internal fun Route.stubRoutes(
    door: () -> ManageDoor?,
    blobs: StubBlobs,
    health: suspend () -> String,
    diag: suspend () -> String,
    /** Hardened mode: the door and blobs, which did not exist before, never answer a LAN peer (section 20.7). */
    hardened: () -> Boolean = { false },
) {
    StubEndpoints.PAGES.forEach { (path, name) -> get(path) { call.answerPage(door, "page.$name") } }
    StubEndpoints.HEALTH.forEach { path -> get(path) { call.respondText(health()) } }
    StubEndpoints.DIAG.forEach { path -> get(path) { call.respondText(diag(), ContentType.Text.Plain) } }
    // Checked after routing, on the routes themselves, so every spelling of a path that reaches them is covered.
    val loopbackOnly = createRouteScopedPlugin("StubLoopbackOnly") {
        onCall { call ->
            if (hardened() && !isLoopbackPeer(call.request.origin.remoteAddress)) {
                call.respondText("loopback only in hardened mode\n", status = HttpStatusCode.Forbidden)
            }
        }
    }
    route(StubEndpoints.BLOB) {
    install(loopbackOnly)
    get {
        val found = blobs.download(call.parameters["token"].orEmpty())
            ?: return@get call.respondText("no such blob\n", status = HttpStatusCode.NotFound)
        call.respondBytes(found.first, ContentType.parse(found.second))
    }
    put {
        val token = call.parameters["token"].orEmpty()
        // The token first, then the declared length, so nothing is read for a request that cannot be accepted.
        val limit = blobs.uploadLimit(token)
            ?: return@put call.respondText("no such upload\n", status = HttpStatusCode.NotFound)
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > limit) return@put call.respondText("too large\n", status = HttpStatusCode.PayloadTooLarge)
        val reference = try {
            call.receiveChannel().toInputStream().use { blobs.fill(token, limit, it) }
        } catch (_: ByteLimitExceeded) {
            return@put call.respondText("too large\n", status = HttpStatusCode.PayloadTooLarge)
        } catch (_: StubBlobs.Busy) {
            call.response.headers.append(HttpHeaders.RetryAfter, "5")
            return@put call.respondText("too many uploads in progress\n", status = HttpStatusCode.ServiceUnavailable)
        } ?: return@put call.respondText("no such upload\n", status = HttpStatusCode.NotFound)
        call.respondText(reference.toString(), ContentType.Application.Json)
    }
    }
    route(StubEndpoints.UI, HttpMethod.Get) {
        install(loopbackOnly)
        uiSocket(door, blobs, hardened)
    }
}

/**
 * A page through the dispatcher. Its `args` are the locale signals of the query (`lang`, and `ha_lang`, which the
 * Setup redirect carries); its origin carries the request's `Host` and `Accept-Language` as headers. Only the
 * sidebar relay embeds a page (`args.embed`), so the query never switches presentation.
 */
private suspend fun ApplicationCall.answerPage(door: () -> ManageDoor?, op: String) {
    val dispatcher = door() ?: return respondText("starting\n", status = HttpStatusCode.ServiceUnavailable)
    val args = JSONObject()
    PAGE_QUERY_ARGS.forEach { name -> request.queryParameters[name]?.let { args.put(name, it) } }
    val origin = CommandOrigin.peer(request.origin.remoteAddress).withHeaders(doorHeaders(request.headers))
    val reply = suspendCancellableCoroutine { waiting ->
        dispatcher.manage(UUID.randomUUID().toString(), op, args, origin) {
            if (waiting.isActive) waiting.resume(it)
        }
    }
    reply.headers.forEach { (name, value) -> response.headers.append(name, value) }
    val type = reply.contentType?.let(ContentType::parse) ?: ContentType.Text.Plain
    respondText(reply.result as? String ?: reply.result?.toString().orEmpty(), type, HttpStatusCode.fromValue(reply.status))
}

/** The query parameters a page reads: its locale signals. */
private val PAGE_QUERY_ARGS = listOf("lang", "ha_lang")

/**
 * The headers of a door's own HTTP request that every operation it carries reads as its own: the validated `Host`
 * (OAuth binds the panel's origin to it) and the browser's `Accept-Language`.
 */
private fun doorHeaders(headers: io.ktor.http.Headers): Map<String, String> = buildMap {
    headers[HttpHeaders.Host]?.let { put(HttpHeaders.Host, it) }
    headers[HttpHeaders.AcceptLanguage]?.let { put(HttpHeaders.AcceptLanguage, it) }
}

/**
 * `GET /ui`: the stub's door (protocol section 20.2). One text frame per request object, handled by [UiFrames];
 * the origin is the peer's address whatever the frame says. Closing the socket ends every subscription its origin
 * holds; turning hardened mode on closes a LAN peer's socket.
 */
private fun Route.uiSocket(door: () -> ManageDoor?, blobs: StubBlobs, hardened: () -> Boolean) {
    application.pluginOrNull(WebSockets) ?: application.install(WebSockets) { maxFrameSize = MAX_FRAME_BYTES }
    webSocket {
        // A cross-site page opening the socket is the WebSocket form of the cross-site write OriginGuard refuses.
        if (!OriginGuard.allowed(HttpMethod.Post.value, call.request.headers[HttpHeaders.Origin], null, call.request.headers[HttpHeaders.Host])) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "cross-origin refused"))
        }
        val dispatcher = door() ?: return@webSocket close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "starting"))
        // The socket belongs to this dispatcher's generation: when a runtime replacement stops it, the socket
        // closes and the page reconnects (and resubscribes) to the new one; requests in flight answer failed.
        val generationEnded = dispatcher.onStopped {
            launch { close(CloseReason(CloseReason.Codes.GOING_AWAY, "panel runtime restarted")) }
        }
        val peerAddress = call.request.origin.remoteAddress
        // Approvals bind to the peer; subscriptions belong to this socket alone; every operation reads the upgrade
        // request's headers as its own.
        val origin = CommandOrigin.peer(peerAddress).withConnection(UUID.randomUUID().toString())
            .withHeaders(doorHeaders(call.request.headers))
        // Hardened mode keeps the door to loopback: a LAN socket open when it turns on is closed at once.
        if (!isLoopbackPeer(peerAddress)) launch {
            while (isActive) {
                if (hardened()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "loopback only in hardened mode"))
                    break
                }
                delay(HARDENED_CHECK_MS)
            }
        }
        // One sender, so frames leave in the order they were queued.
        val queued = Channel<Pair<JSONObject, () -> Unit>>(Channel.UNLIMITED)
        launch {
            for ((frame, after) in queued) {
                try {
                    outgoing.send(Frame.Text(frame.toString()))
                    after()
                } catch (_: ClosedSendChannelException) {
                }
            }
        }
        val frames = UiFrames(dispatcher, origin, this, uploads = blobs) { frame, after -> queued.trySend(frame to after) }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                frames.handle(runCatching { JSONObject(frame.readText()) }.getOrNull(), blobs)
            }
        } finally {
            generationEnded.close()
            queued.close()
            frames.close()
        }
    }
}

private const val HARDENED_CHECK_MS = 500L

/** Frames into the panel are capped at 2 MiB (section 20.1); larger bodies move as blobs. */
private const val MAX_FRAME_BYTES = 2L * 1024 * 1024
