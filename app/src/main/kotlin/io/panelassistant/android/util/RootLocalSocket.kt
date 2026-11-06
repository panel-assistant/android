package io.panelassistant.android.util

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.net.SocketTimeoutException

/**
 * Connect to a root-owned abstract socket and authenticate the server before any protocol bytes flow.
 * Abstract names have no filesystem owner or mode, so client-side SO_PEERCRED is the only protection
 * against an unprivileged process binding the well-known name first and impersonating the helper.
 */
internal fun openRootAbstractSocket(
    name: String,
    deadline: MonotonicDeadline? = null,
    createSocket: () -> LocalSocket = { LocalSocket() },
): LocalSocket {
    val socket = createSocket()
    try {
        deadline?.let {
            // getInputStream creates the fd without connecting; setSoTimeout alone does not.
            socket.inputStream
            val remaining = it.remainingMs()
            if (remaining == 0L) throw SocketTimeoutException("helper connect deadline expired")
            // LocalSocket applies SO_SNDTIMEO as well as SO_RCVTIMEO.
            // AF_UNIX backlog waits consume that send timeout; the public connect timeout overload is unsupported.
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
        if (deadline?.remainingMs() == 0L) throw SocketTimeoutException("helper connect deadline expired")
        val peerUid = socket.peerCredentials.uid
        if (!trustedRootSocketPeer(peerUid)) {
            throw SecurityException("refusing non-root local-socket peer uid=$peerUid")
        }
        return socket
    } catch (failure: Exception) {
        runCatching { socket.close() }
        throw failure
    }
}

internal fun trustedRootSocketPeer(uid: Int): Boolean = uid == ROOT_UID

private const val ROOT_UID = 0
