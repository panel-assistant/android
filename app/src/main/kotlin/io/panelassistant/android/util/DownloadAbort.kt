package io.panelassistant.android.util

import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Stops an [AppInstaller.download] that is blocked inside a socket read.
 *
 * The download's copy loop is synchronous and has no suspension points, so cancelling the surrounding
 * coroutine cannot reach it — only closing the transport can. This is the same mechanism the remote
 * audio transfer uses for its latest-wins replacement.
 *
 * Abort is one-way and safe to call before a connection exists. A download that attaches afterwards
 * observes the abort and refuses to start, so a cancel arriving during the first connect is never
 * lost — which matters because that is exactly when an operator is most likely to press Cancel.
 */
internal class DownloadAbort {
    private val connection = AtomicReference<HttpURLConnection?>(null)
    private val cancelled = AtomicBoolean(false)

    val isAborted: Boolean get() = cancelled.get()

    /** Register the live connection. False means the owner already aborted and the caller must stop. */
    fun attach(conn: HttpURLConnection): Boolean {
        connection.set(conn)
        if (cancelled.get()) {
            connection.set(null)
            runCatching { conn.disconnect() }
            return false
        }
        return true
    }

    /** Release a connection the download is finished with, so a later abort cannot touch a reused one. */
    fun detach() {
        connection.set(null)
    }

    fun abort() {
        cancelled.set(true)
        connection.getAndSet(null)?.let { runCatching { it.disconnect() } }
    }
}
