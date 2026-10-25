package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.panelassistant.android.PanelFont
import io.panelassistant.android.util.isLoopbackPeer

/**
 * The bundled Roboto as the pages' text face. Appended to the stylesheet only for the panel's own WebView on a panel
 * whose system font replaces Roboto ([PanelFont.bundled]): every other panel, and every browser and sidebar showing the
 * pages, keeps the stylesheet's platform face.
 */
private const val PANEL_FONT_CSS = """
@font-face{font-family:"Panel Roboto";src:url(assets/${PanelFont.ASSET}) format("truetype");font-weight:100 900;font-stretch:75% 100%}
body{font-family:"Panel Roboto",system-ui,-apple-system,sans-serif}
button,input,select,textarea{font-family:inherit}
"""

/** Bundled front-end assets, mounted beneath the shared production root guards. */
internal fun Route.assetRoutes(asset: (String) -> String, bytes: (String) -> ByteArray, panelFont: () -> Boolean) {
    // Static front-end assets (externalised from the Kotlin string so CI can lint them).
    get("/info.js") {
        call.response.headers.append("Cache-Control", "no-cache")  // assets iterate; always serve fresh
        call.respondText(asset("info.js"), ContentType.Application.JavaScript)
    }
    get("/info.css") {
        call.response.headers.append("Cache-Control", "no-cache")
        val own = panelFont() && isLoopbackPeer(call.request.origin.remoteAddress)
        call.respondText(asset("info.css") + (if (own) PANEL_FONT_CSS else ""), ContentType.Text.CSS)
    }
    // The mark from the brand asset set; browsers ask for /favicon.svg too, so both names serve it.
    for (path in BUNDLE_ROOT_FILES.keys.filter { it.endsWith(".svg") }) get("/$path") {
        call.respondText(asset(BUNDLE_ROOT_FILES.getValue(path)), ContentType.Image.SVG)
    }
    // Generic bundled-asset server for the redesigned UI (page scripts + vendored libs).
    get("/assets/{f...}") {
        val rel = call.parameters.getAll("f")?.joinToString("/").orEmpty()
        if (rel == PanelFont.ASSET) {
            call.response.headers.append("Cache-Control", "no-cache")
            return@get call.respondBytes(bytes(rel), ContentType("font", "ttf"))
        }
        val body = if (rel.isEmpty() || rel.contains("..")) null else runCatching { asset(rel) }.getOrNull()
        if (body == null) {
            call.respondText("not found\n", status = HttpStatusCode.NotFound)
        } else {
            val ct = when {
                rel.endsWith(".js") -> ContentType.Application.JavaScript
                rel.endsWith(".css") -> ContentType.Text.CSS
                rel.endsWith(".svg") -> ContentType.Image.SVG
                rel.endsWith(".json") -> ContentType.Application.Json
                else -> ContentType.Text.Plain
            }
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondText(body, ct)
        }
    }
}

/**
 * The panel's bundle (protocol section 20.5): every file under the APK's `assets/`, by its path there, and its
 * bytes. The root names the routes above serve ([BUNDLE_ROOT_FILES]) are assets too.
 */
internal interface UiBundleAssets {
    fun names(): List<String>
    fun bytes(name: String): ByteArray

    companion object {
        /**
         * The `assets/` entries of the installed APK at [apk], read from the APK itself: the asset manager's root
         * listing also names the framework's own asset directories, which the panel never serves.
         */
        fun ofApk(apk: () -> String): UiBundleAssets = object : UiBundleAssets {
            override fun names(): List<String> = java.util.zip.ZipFile(apk()).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(PREFIX) }.map { it.name.removePrefix(PREFIX) }.toList()
            }

            override fun bytes(name: String): ByteArray = java.util.zip.ZipFile(apk()).use { zip ->
                val entry = zip.getEntry(PREFIX + name) ?: throw java.io.FileNotFoundException(name)
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }

        private const val PREFIX = "assets/"
    }
}

/** The root paths the routes above serve, by the asset each serves. */
internal val BUNDLE_ROOT_FILES: Map<String, String> =
    linkedMapOf("info.js" to "info.js", "info.css" to "info.css", "icon.svg" to "icon.svg", "favicon.svg" to "icon.svg")

/**
 * The asset types a page loads: its scripts, styles and images. The rest of `assets/` (translation catalogues,
 * which the page shell inlines, wake-word models, helper binaries, profile sources, the OpenAPI file) never
 * reaches a page and would multiply the bundle a slow panel must upload before the sidebar can show it.
 */
internal val PAGE_ASSET_TYPES: Set<String> = setOf("js", "css", "svg")

/**
 * Write the bundle as one zip: the page files the stub's bundle routes serve ([PAGE_ASSET_TYPES]), at the paths
 * the page base sees them (`assets/<path>` and the root names), so Panel Assistant can serve the sidebar frame
 * without the panel.
 */
internal fun writeUiBundle(assets: UiBundleAssets, out: java.io.OutputStream) {
    java.util.zip.ZipOutputStream(out).use { zip ->
        fun entry(path: String, bytes: ByteArray) {
            zip.putNextEntry(java.util.zip.ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
        BUNDLE_ROOT_FILES.forEach { (path, name) -> entry(path, assets.bytes(name)) }
        assets.names().filter { it.substringAfterLast('.') in PAGE_ASSET_TYPES }.sorted()
            .forEach { name -> entry("assets/$name", assets.bytes(name)) }
    }
}

/**
 * `bundle` (protocol section 20.5): one zip of the files the stub's bundle routes serve, uploaded through
 * `args.blob_token`, answered with its reference and the [build] token the health line reports, which keys it.
 */
internal suspend fun uploadUiBundle(
    args: org.json.JSONObject,
    blobs: io.panelassistant.android.panelassistant.PanelAssistantBlobs,
    assets: UiBundleAssets,
    build: String,
    dir: java.io.File,
): io.panelassistant.android.panelassistant.PanelAssistantManageReply {
    val protocol = io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
    val token = io.panelassistant.android.panelassistant.PanelAssistantBlobs.validToken(args.opt("blob_token"))
        ?: return io.panelassistant.android.panelassistant.PanelAssistantManageReply(
            protocol.OUTCOME_REFUSED, io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor.CODE_INVALID_VALUE, null,
        )
    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        java.io.File.createTempFile("bundle", ".zip", dir).also { zip -> zip.outputStream().use { writeUiBundle(assets, it) } }
    }
    val reference = try {
        blobs.upload(token, file, "application/zip")
    } finally {
        file.delete()
    } ?: return io.panelassistant.android.panelassistant.PanelAssistantManageReply(
        protocol.OUTCOME_FAILED, io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor.CODE_FAILED,
        org.json.JSONObject().put("error", "blob-unavailable"),
    )
    return io.panelassistant.android.panelassistant.PanelAssistantManageReply(
        protocol.OUTCOME_APPLIED, null, org.json.JSONObject().put("blob", reference.json()).put("build", build),
    )
}
