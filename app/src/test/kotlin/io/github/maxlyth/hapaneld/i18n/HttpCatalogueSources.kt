package io.github.maxlyth.hapaneld.i18n

import java.io.File

// Source-text reason: catalogue coverage compares shipped literal translation keys with their consumers.
// Scan the HTTP package rather than pinning a consumer's current source-file location.
internal fun httpCatalogueSources(): String =
    File("src/main/kotlin/io/github/maxlyth/hapaneld/http").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .joinToString("\n") { it.readText() }
