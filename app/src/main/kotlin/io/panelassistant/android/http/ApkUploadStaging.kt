package io.panelassistant.android.http

import java.io.File
import java.util.UUID

internal object ApkUploadStaging {
    private const val PREFIX = "apk-upload-"
    private val processPrefix = "$PREFIX${UUID.randomUUID().toString().replace("-", "")}-"

    // Older builds use only the decimal suffix from createTempFile.
    val fileName = Regex("""$PREFIX(?:[0-9a-f]{32}-)?[0-9]+\.apk""")

    fun create(cacheDir: File): File = File.createTempFile(processPrefix, ".apk", cacheDir)

    // Staging and async install share this path. Ownership must survive wall-clock changes.
    fun belongsToCurrentProcess(file: File): Boolean = file.name.startsWith(processPrefix)
}
