package io.panelassistant.android.util

import android.net.LocalSocket
import android.net.LocalSocketAddress

/**
 * Connect to a root-owned abstract socket and authenticate the server before any protocol bytes flow.
 * Abstract names have no filesystem owner or mode, so client-side SO_PEERCRED is the only protection
 * against an unprivileged process binding the well-known name first and impersonating the helper.
 */
internal fun openRootAbstractSocket(name: String): LocalSocket {
    val socket = LocalSocket()
    try {
        socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
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
