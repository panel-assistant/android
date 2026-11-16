package io.panelassistant.android.control

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files

/**
 * The panel's own network hardware addresses, for Panel Assistant to put on its Home Assistant device
 * so the card lists other integrations' devices for the same hardware (the Shelly integration's, say)
 * as linked devices.
 *
 * Read from sysfs on every status, because an interface can appear after start-up. Only Wi-Fi and
 * Ethernet interfaces count, and a randomised, placeholder (`02:00:00:00:00:00`) or multicast address
 * is left out: it names no hardware another integration could share. A panel whose Android denies the
 * read sends none. Stop retrying a permission denial for this process to avoid repeated audit logs.
 * Other listing failures can be temporary, so a later status still retries those.
 */
internal class NetworkMacs(
    private val netRoot: File,
    private val listInterfaces: (File) -> List<File> = { root ->
        try {
            Files.newDirectoryStream(root.toPath()).use { entries -> entries.map { it.toFile() } }
        } catch (failure: DirectoryIteratorException) {
            throw failure.cause ?: failure
        }
    },
) {
    private var permissionDenied = false

    @Synchronized fun read(): List<String> {
        if (permissionDenied) return emptyList()
        val interfaces = try {
            listInterfaces(netRoot)
        } catch (_: AccessDeniedException) {
            permissionDenied = true
            return emptyList()
        } catch (_: IOException) {
            return emptyList()
        }
        return interfaces
            .filter { iface -> PREFIXES.any { iface.name.startsWith(it) } }
            .mapNotNull { iface -> runCatching { File(iface, "address").readText() }.getOrNull() }
            .mapNotNull(::hardwareAddress)
            .distinct()
            .sorted()
            .take(MAX_ADDRESSES)
    }

    companion object {
        private val PREFIXES = listOf("wlan", "eth", "en")
        private const val MAX_ADDRESSES = 4
        private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        /** A globally administered unicast address in lower-case colon form, or null. */
        internal fun hardwareAddress(raw: String): String? {
            val mac = raw.trim().lowercase()
            if (!MAC.matches(mac)) return null
            val first = mac.substring(0, 2).toInt(16)
            // Bit 1: locally administered (randomised or placeholder); bit 0: multicast.
            if (first and 0x03 != 0) return null
            if (mac == "00:00:00:00:00:00") return null
            return mac
        }

        val shared = NetworkMacs(File("/sys/class/net"))
    }
}
