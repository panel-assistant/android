package io.panelassistant.android.dashboard

/**
 * Read from a resource and close it afterwards without Kotlin's `use`.
 *
 * `SQLiteOpenHelper` only implements `AutoCloseable` from API 29, so `use { }` on a catalog store
 * compiles against the current compileSdk yet throws `ClassCastException` at runtime on Android 8.1
 * (API 27) — inside this app's supported range, since `minSdk` is 26. An explicit try/finally is
 * correct on every level, and this function is the one place that idiom lives so call sites cannot
 * quietly reintroduce `use`.
 *
 * The close is additionally isolated: a resource that read successfully but failed to close has
 * still produced a valid result, and discarding that result over the close would silently cost the
 * caller its data — for the backup bundle, an entire configuration export.
 */
internal fun <S, T> readThenClose(resource: S, close: (S) -> Unit, read: (S) -> T): T =
    try {
        read(resource)
    } finally {
        // Never let a close failure mask the read's outcome — neither a good result nor the read's
        // own exception.
        runCatching { close(resource) }
    }
