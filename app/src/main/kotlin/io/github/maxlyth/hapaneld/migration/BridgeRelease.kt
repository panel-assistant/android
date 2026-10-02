package io.github.maxlyth.hapaneld.migration

/**
 * Bridge side of the retired on-panel handover: the successor has pulled and verified a backup and
 * asks for the panel. Panel Assistant now moves panels itself, so a request that passes the identity
 * checks is refused with [Refusal.MOVED_BY_PANEL_ASSISTANT]; only an already retired bridge answers
 * [Outcome.AlreadyReleased].
 *
 * A refusal has no side effect at all, so a refused successor stays passive and the panel keeps
 * running the bridge. Once admitted the order is fixed, because each step is what makes the next one
 * safe: quiesce through the ordinary upgrade shutdown (state flushed and frozen, kiosk return loop
 * stopped, HTTP server stopped and port 8888 free), then the durable retired marker, then HOME. The
 * marker precedes HOME so that no instant exists in which the successor owns HOME while a restarted
 * bridge would still run; a death before the marker leaves an ordinary bridge that can be asked again.
 */
internal class BridgeRelease(private val ports: Ports) {
    interface Ports {
        fun isBridge(): Boolean
        fun retired(): Boolean
        fun tokenMatches(presented: String?): Boolean

        /** The installed successor is signed by exactly the pinned signer. */
        fun successorTrusted(): Boolean

        /** Why the running helper cannot carry the handover, or null when it is confirmed dual-uid. */
        fun helperRefusal(): String?

        /**
         * Arm the upgrade shutdown and stop the service. [onQuiesced] runs once the service has torn
         * down and state is frozen. False when the shutdown could not be armed; a shutdown that fails
         * later resumes the service by itself and never calls [onQuiesced].
         */
        fun beginQuiesce(onQuiesced: () -> Unit): Boolean

        fun writeRetiredMarker(): Boolean

        /** Set HOME to the successor through the helper and confirm it by a fresh HOME query. */
        fun setHomeToSuccessor(): Boolean

        /** The freeze is released and the ordinary service restarted: the handover did not happen. */
        fun resumeBridge()

        /** The handover is durable; end this process so nothing armed in it can run again. */
        fun endProcess()
    }

    enum class Refusal(val code: String) {
        NOT_A_BRIDGE("not-a-bridge"),
        NOT_LOOPBACK("not-loopback"),
        BAD_TOKEN("bad-token"),
        UNTRUSTED_SUCCESSOR("untrusted-successor"),
        HELPER_NOT_CONFIRMED("helper-not-confirmed"),
        QUIESCE_UNAVAILABLE("quiesce-unavailable"),

        /** The on-panel handover is retired: Panel Assistant moves the panel through its Repair. */
        MOVED_BY_PANEL_ASSISTANT("moved-by-panel-assistant"),
    }

    sealed interface Outcome {
        /** Admitted; the handover completes after the HTTP reply, as the server is part of it. */
        data object Releasing : Outcome

        /** This bridge already retired. The successor may proceed. */
        data object AlreadyReleased : Outcome
        data class Refused(val refusal: Refusal, val detail: String? = null) : Outcome
    }

    fun request(token: String?, loopback: Boolean): Outcome {
        if (!ports.isBridge()) return Outcome.Refused(Refusal.NOT_A_BRIDGE)
        if (!loopback) return Outcome.Refused(Refusal.NOT_LOOPBACK)
        if (!ports.tokenMatches(token)) return Outcome.Refused(Refusal.BAD_TOKEN)
        if (!ports.successorTrusted()) return Outcome.Refused(Refusal.UNTRUSTED_SUCCESSOR)
        if (ports.retired()) return Outcome.AlreadyReleased
        // A bridge never gives up the panel to a successor running beside it. Panel Assistant moves
        // the panel through its Repair, which refuses a successor that has already run.
        return Outcome.Refused(Refusal.MOVED_BY_PANEL_ASSISTANT)
    }

    /**
     * Runs after the service has torn down. If the marker cannot be made durable the handover is
     * abandoned and the bridge resumes, since a bridge that is not provably retired must keep working.
     * A HOME that could not be confirmed does not undo the retirement: the successor re-asserts HOME
     * itself and never uninstalls this package until a HOME query names it.
     */
    internal fun completeAfterQuiesce() {
        if (!ports.writeRetiredMarker()) {
            ports.resumeBridge()
            return
        }
        ports.setHomeToSuccessor()
        // The process that armed the shutdown still holds its freeze, a watchdog that would release it,
        // and possibly an activity drawing beneath the successor. None of that may outlive retirement;
        // a fresh process, if Android ever starts one, is idle on every route.
        ports.endProcess()
    }
}
