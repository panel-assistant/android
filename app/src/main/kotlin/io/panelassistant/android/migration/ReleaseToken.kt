package io.panelassistant.android.migration

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The one-time proof that a release request comes from the successor this bridge installed.
 *
 * Localhost HTTP carries no caller identity, and every app on the panel can reach port 8888. The
 * bridge therefore mints a random token, keeps it in its own private storage, and delivers it only to
 * the successor package through a signature-protected explicit broadcast, after checking that the
 * installed successor carries the pinned signer. The release endpoint accepts nothing else. The same
 * class is the successor's store for the token it was handed.
 */
internal class ReleaseToken(noBackupFilesDir: File) {
    private val record = DurableTextFile(noBackupFilesDir.resolve(DIRECTORY).resolve(FILE), maxChars = TOKEN_CHARS)

    /** The stored token, or null when none has been minted or received. */
    fun current(): String? = record.read()?.takeIf(::wellFormed)

    /** Bridge: the stable token for this migration, minted durably on first use. */
    fun ensure(random: (ByteArray) -> Unit = SecureRandom()::nextBytes): String? {
        current()?.let { return it }
        val minted = ByteArray(TOKEN_CHARS / 2).also(random).joinToString("") { "%02x".format(it) }
        return if (record.write(minted)) minted else null
    }

    /** Successor: keep the token the bridge delivered. A malformed value is never stored. */
    fun accept(token: String?): Boolean = token != null && wellFormed(token) && record.write(token)

    /** Bridge: constant-time comparison of a presented token with the minted one. */
    fun matches(presented: String?): Boolean {
        val expected = current() ?: return false
        if (presented == null || !wellFormed(presented)) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), presented.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        const val TOKEN_CHARS = 64
        const val HEADER = "X-Panel-Migration-Token"
        private const val DIRECTORY = "identity-migration"
        private const val FILE = "release-token.v1"
        private val TOKEN_RE = Regex("[0-9a-f]{$TOKEN_CHARS}")

        fun wellFormed(token: String): Boolean = TOKEN_RE.matches(token)

        fun of(context: Context): ReleaseToken = ReleaseToken(context.noBackupFilesDir)
    }
}
