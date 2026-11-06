package io.panelassistant.android.control

import java.io.File

/** Only a successful direct su answer is remembered. A helper answer never reaches this store. */
internal class SuBootHistory(directory: File, bootIdentity: () -> String?) {
    private val boot = runCatching(bootIdentity).getOrNull()?.takeIf { BOOT_ID.matches(it) }
    private val marker = File(directory, "su-answered-boot")
    @Volatile var answeredThisBoot = boot != null && runCatching {
        marker.inputStream().use { input ->
            val bytes = ByteArray(37)
            val count = input.read(bytes)
            count == 36 && input.read() == -1 && String(bytes, 0, count, Charsets.US_ASCII) == boot
        }
    }.getOrDefault(false)
        private set

    private var persisted = answeredThisBoot

    @Synchronized fun recordAnswer() {
        if (persisted) return
        val currentBoot = boot ?: return
        // A write failure must not invent prior proof in a replacement process. The current process
        // still has the real proof; retry the durable write on the next successful root operation.
        answeredThisBoot = true
        val pending = File(marker.parentFile, "${marker.name}.tmp")
        runCatching {
            pending.outputStream().use { output ->
                output.write(currentBoot.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            check(pending.renameTo(marker))
            persisted = true
        }
    }

    private companion object {
        val BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
