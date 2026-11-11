package io.panelassistant.android

import io.panelassistant.android.util.isLoopbackPeer

/**
 * Who a command came from (protocol section 20.2). [approvalKey] is the approval principal: `panel_assistant`,
 * `user:<Home Assistant user id>` or `peer:<address>`, so an approval earned by one origin can never be
 * consumed by another. [peerAddress] is the bare address of a `peer` origin, for the loopback exemption.
 * Doors construct origins; a requester never supplies one.
 */
internal data class CommandOrigin private constructor(
    val approvalKey: String,
    val requestId: String? = null,
    val peerAddress: String? = null,
    /** The connection that carries this origin's requests, when a door has one per socket. */
    val connection: String? = null,
    /**
     * Request headers the door read from its own connection (the socket's `Host` and `Accept-Language`), which an
     * operation sees as its request's headers: never `args`, so no handler reads them as a body or parameters.
     */
    val headers: Map<String, String> = emptyMap(),
) {
    val isPanelAssistant: Boolean get() = approvalKey == PANEL_ASSISTANT
    val isUser: Boolean get() = approvalKey.startsWith(USER_PREFIX)

    /**
     * Whether the request came from software on the panel itself: a `peer` whose address is loopback. Panel
     * Assistant and a relayed Home Assistant user have no peer address and are remote, whatever else they may do.
     */
    val isLoopback: Boolean get() = peerAddress?.let(::isLoopbackPeer) == true

    /**
     * Who owns a subscription (protocol section 20.9 check 4): the connection when there is one, so two tabs
     * from one address hold, cap and close their own; the approval principal otherwise.
     */
    val subscriptionOwner: String get() = connection?.let { "$approvalKey#$it" } ?: approvalKey

    fun withRequestId(requestId: String?): CommandOrigin = copy(requestId = requestId)

    fun withConnection(connection: String): CommandOrigin = copy(connection = connection)

    fun withHeaders(headers: Map<String, String>): CommandOrigin = copy(headers = headers)

    companion object {
        const val PANEL_ASSISTANT = "panel_assistant"
        private const val USER_PREFIX = "user:"
        private const val PEER_PREFIX = "peer:"

        fun panelAssistant(requestId: String? = null) = CommandOrigin(PANEL_ASSISTANT, requestId)

        fun user(userId: String, requestId: String? = null): CommandOrigin {
            require(userId.isNotBlank()) { "a user origin needs the Home Assistant user id" }
            return CommandOrigin(USER_PREFIX + userId, requestId)
        }

        fun peer(address: String, requestId: String? = null): CommandOrigin {
            require(address.isNotBlank()) { "a peer origin needs the peer address" }
            return CommandOrigin(PEER_PREFIX + address, requestId, address)
        }
    }
}
