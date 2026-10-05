package io.panelassistant.android.util

/**
 * What went wrong on the wire between this panel and Home Assistant, as a CLOSED vocabulary.
 *
 * It exists because the useful half of a transport failure and the unpublishable half are the same
 * string. `java.security.cert.CertPathValidatorException: Trust anchor for certification path not
 * found` is the one sentence that turns a stuck panel into an immediate diagnosis — but
 * `UnknownHostException` carries the configured hostname, `ConnectException` carries `ip:port`, and
 * a proxy can put anything at all in a message. A diagnostic surface that is meant to be pasted
 * into a public issue cannot forward any of them.
 *
 * So the classification is derived from the exception TYPE, never from its text, and the only free
 * text that escapes is [HaTransportEvidence.token] — a sanitized class name. Types are matched by
 * their fully-qualified names rather than by `is` checks so this file stays pure Kotlin with no
 * platform imports, and so an engine-specific exception (Ktor's, OkHttp's) can be recognised without
 * depending on that engine.
 */
enum class HaTransportFault(val wire: String) {
    /** No transport fault was involved in this observation. */
    NONE("none"),

    /** The server's certificate chain could not be validated — an expired, missing or untrusted
     *  anchor. A whole site of panels can stop rendering on this alone, and it is the one fault an
     *  operator can act on immediately. */
    TLS_TRUST("tls_trust"),

    /** TLS failed for a reason other than trust: a protocol/cipher mismatch, or a truncated session. */
    TLS_OTHER("tls_other"),

    /** The Home Assistant hostname did not resolve. */
    DNS("dns"),

    /** The connection or read ran out of time. A black-holed address family looks like this. */
    TIMEOUT("timeout"),

    /** Something answered and refused the connection — the port is closed, or a proxy said no. */
    REFUSED("refused"),

    /** No route to the host: the address family or the segment itself is unusable. */
    UNREACHABLE("unreachable"),

    /** The server answered with an HTTP status that blocked the check; the code is in the token. */
    HTTP_STATUS("http_status"),

    /** A reply arrived but was not what the endpoint promised — malformed JSON, a captive portal. */
    PROTOCOL("protocol"),

    /** Reached the wire and failed in a way this vocabulary does not name. */
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Cause chains are walked, but never further than this: a self-referential chain would hang
         *  a diagnostics read, and no real chain carries its verdict this deep. */
        private const val MAX_CAUSE_DEPTH = 8

        /** Longest sanitized class name emitted as a token. Long enough for the real names that
         *  matter (`CertPathValidatorException` is 26), short enough that nothing narrative fits. */
        internal const val MAX_TOKEN_CHARS = 40

        /**
         * TLS trust is checked FIRST and over the whole chain, because it is the one fault that
         * arrives wrapped: the platform reports `SSLHandshakeException` caused by
         * `CertPathValidatorException`, and matching the outer type alone would classify a broken
         * certificate chain as a generic TLS fault and lose the entire point of this enum.
         */
        fun classify(error: Throwable?): HaTransportFault {
            val chain = chain(error)
            if (chain.isEmpty()) return UNKNOWN
            if (chain.any { it.isCertificateType() }) return TLS_TRUST
            chain.forEach { name ->
                named(name)?.let { return it }
            }
            return UNKNOWN
        }

        /**
         * A sanitized simple class name for the fault, or null when there is nothing to name. Only
         * `[A-Za-z0-9]` survives, so a class name that somehow embedded a host or a path could not
         * carry it through. The name identifies the FIRST link in the chain that this vocabulary
         * recognises, falling back to the outermost, so the token agrees with [classify] rather than
         * naming a wrapper the verdict ignored.
         */
        fun token(error: Throwable?): String? {
            val chain = chain(error)
            if (chain.isEmpty()) return null
            val chosen = chain.firstOrNull { it.isCertificateType() }
                ?: chain.firstOrNull { named(it) != null }
                ?: chain.first()
            return sanitize(chosen.substringAfterLast('.'))
        }

        /** Fault and token together, so a caller cannot pair one exception's class with another's. */
        fun evidenceOf(error: Throwable?): HaTransportEvidence =
            HaTransportEvidence(classify(error), token(error))

        /** An HTTP status that blocked the check. The code IS the useful detail and carries nothing
         *  private, so it becomes the token verbatim. */
        fun evidenceOfHttpStatus(code: Int): HaTransportEvidence =
            HaTransportEvidence(HTTP_STATUS, "http_$code")

        fun sanitize(raw: String?): String? =
            raw?.filter { it.isLetterOrDigit() || it == '_' }?.take(MAX_TOKEN_CHARS)?.takeIf { it.isNotBlank() }

        /** Fully-qualified class names of the throwable and its causes, outermost first. */
        private fun chain(error: Throwable?): List<String> {
            val names = mutableListOf<String>()
            val seen = mutableSetOf<Throwable>()
            var current = error
            while (current != null && names.size < MAX_CAUSE_DEPTH && seen.add(current)) {
                names += current.javaClass.name
                current = current.cause
            }
            return names
        }

        /** Certificate validation lives in several classes across platforms; all of them mean trust. */
        private fun String.isCertificateType(): Boolean =
            substringAfterLast('.').let {
                it.startsWith("CertPath") || it.startsWith("Certificate") ||
                    it == "CertificateExpiredException" || it == "CertificateNotYetValidException"
            }

        private fun named(qualified: String): HaTransportFault? = when (qualified.substringAfterLast('.')) {
            "UnknownHostException" -> DNS
            "SocketTimeoutException", "ConnectTimeoutException", "HttpRequestTimeoutException",
            "TimeoutCancellationException", "InterruptedIOException",
            -> TIMEOUT
            "ConnectException" -> REFUSED
            "NoRouteToHostException", "PortUnreachableException", "BindException" -> UNREACHABLE
            "SSLHandshakeException", "SSLPeerUnverifiedException" -> TLS_OTHER
            "SSLProtocolException", "SSLKeyException", "SSLException" -> TLS_OTHER
            "JSONException", "SerializationException", "ProtocolException", "MalformedURLException",
            "URISyntaxException",
            -> PROTOCOL
            else -> null
        }
    }
}

/**
 * One transport failure, as the pair a consumer must read together: the classified [fault] and the
 * sanitized [token] naming it. They travel as one value so a surface cannot render a fault from one
 * failure beside the class name of another.
 */
data class HaTransportEvidence(val fault: HaTransportFault, val token: String? = null) {

    /**
     * The evidence to publish for a failure that is KNOWN to have happened. [NONE] means "no
     * transport failure was involved", so a producer that reports a failure without a classification
     * must not be allowed to answer with it — that collapse is how a surface starts saying a panel
     * is fine because nobody wrote down why it is not. Every carrier of a transient detail passes its
     * evidence through here, so an unclassified failure degrades to [UNKNOWN] rather than to health.
     */
    fun orUnclassified(): HaTransportEvidence = if (fault == HaTransportFault.NONE) UNKNOWN else this

    companion object {
        /** No transport failure was involved — the default for every non-transport outcome. */
        val NONE = HaTransportEvidence(HaTransportFault.NONE)

        /** A transport failure whose cause was not carried to this point. Deliberately distinct from
         *  [NONE]: "we did not classify it" and "there was nothing to classify" are different facts,
         *  and collapsing them is how a surface starts claiming health it never observed. */
        val UNKNOWN = HaTransportEvidence(HaTransportFault.UNKNOWN)
    }
}
