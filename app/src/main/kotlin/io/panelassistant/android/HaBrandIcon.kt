package io.panelassistant.android

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.closeBody
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supplies the Home Assistant mark for the lifecycle banner, so an outage reads as Home Assistant's
 * doing rather than ha-paneld's.
 *
 * No brand asset ships in this repository. The Home Assistant logo is distributed under CC BY-NC-SA
 * 4.0 and is an Open Home Foundation trademark, and this project is Apache-2.0 — redistributing the
 * mark from here would need its own licence carve-out and trademark permission this project does not
 * have, so it is not shipped.
 *
 * What is rendered instead is the copy already present on the user's own device: their Home Assistant's
 * own icon, or the installed Companion app's. Finally nothing at all — the banner is legible without
 * it, so a missing icon is a cosmetic degrade and never a failure.
 *
 * The brand guidelines' layout requirements (clear space, no coloured enclosure) are followed where
 * this bar renders the mark.
 */
internal object HaBrandIcon {
    private const val TAG = "ha-paneld/ha"
    private const val ICON_PATH = "/static/icons/favicon-192x192.png"
    private const val CACHE_NAME = "ha-brand-icon.png"
    private const val MAX_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val REFRESH_AFTER_MS = 30L * 24L * 60L * 60L * 1_000L

    /** The mark renders at 96dp; this cap is generous headroom, not a render size. */
    private const val MAX_DECODED_DIMENSION_PX = 1024

    private fun cacheFile(context: Context) = File(context.cacheDir, CACHE_NAME)

    /**
     * Fetch and cache the mark if it is missing or stale. Blocking — call from a worker. Every failure is
     * swallowed on purpose: this is decoration, and it must never affect whether the outage is reported.
     */
    fun prefetch(context: Context, haUrl: String) {
        val base = haUrl.trim().trimEnd('/')
        if (base.isEmpty()) return
        val target = cacheFile(context)
        val fresh = target.isFile && target.length() > 0L &&
            System.currentTimeMillis() - target.lastModified() < REFRESH_AFTER_MS
        if (fresh) return
        runCatching {
            val connection = (URL(base + ICON_PATH).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.closeBody()
                    return
                }
                // Read with a hard cap so a wrong URL cannot stream an unbounded body into the cache.
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(MAX_BYTES + 1)
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read > MAX_BYTES) null else buffer.copyOf(read)
                } ?: return
                // Bounds only, NO allocation: the byte cap bounds the compressed size, not the decoded
                // one, and a sub-512 KiB image from a plain-HTTP endpoint can decode to an arbitrarily
                // large bitmap on a constrained panel. This also rejects a captive-portal HTML page,
                // which would otherwise be cached as a "logo" and render as nothing.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (iconSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODED_DIMENSION_PX) == null) return
                val staging = File(context.cacheDir, "$CACHE_NAME.part")
                staging.writeBytes(bytes)
                if (!staging.renameTo(target)) staging.delete()
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.i(TAG, "Home Assistant icon unavailable: ${it.javaClass.simpleName}") }
    }

    /** The cached mark, the Companion app's icon, or null. Never throws. */
    fun drawable(context: Context): Drawable? {
        cacheFile(context).takeIf { it.isFile && it.length() > 0L }?.let { file ->
            runCatching {
                // Inspect, cap, then decode ONCE with the computed sampling — never an unbounded decode,
                // even of our own cache, which an older version may have written without the bounds check.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val sample = iconSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODED_DIMENSION_PX)
                    ?: return@runCatching null
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.let {
                return BitmapDrawable(context.resources, it)
            }
        }
        // Both Companion variants, not just `.minimal`: a panel may carry either, and checking one
        // would silently skip the fallback on the other.
        return CompanionInstaller.SUPPORTED_PACKAGES.firstNotNullOfOrNull { pkg ->
            runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
    }
}

/**
 * The power-of-two sampling that brings a decode within [maxDimension], or null when the reported
 * bounds are not a decodable image. Pure — unit-tested in `HaBrandIconPolicyTest`, because the decode
 * itself is Android and the arithmetic is the part that must not be wrong.
 */
internal fun iconSampleSize(width: Int, height: Int, maxDimension: Int): Int? {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return null
    var sample = 1
    while (width / sample > maxDimension || height / sample > maxDimension) sample *= 2
    return sample
}
