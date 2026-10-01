package io.github.maxlyth.hapaneld.util

import java.net.HttpURLConnection

/**
 * Closes the body of a response the server has already answered. `disconnect()` alone does not
 * release it: the platform HTTP stack keeps the pooled connection allocated until the body is closed,
 * and logs `A connection to <url> was leaked` when the connection is garbage-collected first. Call it
 * on every non-2xx or early-return path of a request that read the status line; an error status carries
 * its body on `errorStream`, which `inputStream` refuses to open.
 *
 * Only call it once the status is known: before then it would dial the server a second time.
 */
internal fun HttpURLConnection.closeBody() {
    runCatching { (errorStream ?: inputStream).close() }
}
