package io.panelassistant.android.backup

import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.withStagedFiles
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** File-backed encrypted panel-backup codec and container. */
object PanelBackup {
    private val MAGIC = "HPB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    internal const val SEALED_OVERHEAD_BYTES = 4L + SALT_LEN + IV_LEN + TAG_BYTES
    const val MANIFEST_ENTRY = "manifest.json"
    /** One manifest plus a deliberately small allowance for current and future file-backed payloads:
     * entity filter and overrides, profiles, app state, three Companion files and imported wake words. */
    internal const val MAX_ARCHIVE_ENTRIES = 9

    data class ArchiveSource(val entry: String, val file: File)
    data class ArchiveTarget(
        val entry: String,
        val file: File,
        val maxBytes: Long,
        val allowEmpty: Boolean = false,
    )

    /**
     * A response artifact whose private temporary file must be deleted after the response is consumed.
     *
     * [stateUnavailable] carries the manifest's incomplete marker back out to the response layer: the
     * owner downloading a backup whose `app_state` could not be read has to learn that now, not when a
     * later restore quietly brings nothing back.
     */
    class Artifact(
        val file: File,
        val extension: String = "hpb",
        val stateUnavailable: Boolean = false,
    ) : Closeable {
        override fun close() {
            file.delete()
        }
    }

    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun isSealed(file: File): Boolean = runCatching {
        if (file.length() <= MAGIC.size) return@runCatching false
        file.inputStream().use { input ->
            ByteArray(MAGIC.size).also { header ->
                if (!input.readExactly(header)) return@runCatching false
            }.contentEquals(MAGIC)
        }
    }.getOrDefault(false)

    fun maxSealablePlaintextBytes(maxBundleBytes: Long): Long =
        (maxBundleBytes - SEALED_OVERHEAD_BYTES).coerceAtLeast(0L)

    fun seal(plaintext: ByteArray, passphrase: String): ByteArray {
        val output = java.io.ByteArrayOutputStream(plaintext.size + 64)
        seal(plaintext.inputStream(), output, passphrase)
        return output.toByteArray()
    }

    /** Stream an authenticated envelope without materialising plaintext or ciphertext in one array. */
    fun seal(plaintext: InputStream, output: OutputStream, passphrase: String) {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(rnd::nextBytes)
        val iv = ByteArray(IV_LEN).also(rnd::nextBytes)
        output.write(MAGIC)
        output.write(salt)
        output.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        CipherOutputStream(output, cipher).use { encrypted -> plaintext.copyTo(encrypted) }
    }

    fun open(bundle: ByteArray, passphrase: String): ByteArray? {
        val output = java.io.ByteArrayOutputStream(bundle.size)
        return if (open(bundle.inputStream(), output, passphrase, bundle.size.toLong())) output.toByteArray() else null
    }

    /**
     * Authenticate and decrypt an envelope into [output]. The caller must treat output as untrusted and
     * delete it unless this method returns true: GCM can emit plaintext before its final tag check.
     */
    fun open(bundle: InputStream, output: OutputStream, passphrase: String, maxPlaintextBytes: Long): Boolean {
        val magic = ByteArray(MAGIC.size)
        val salt = ByteArray(SALT_LEN)
        val iv = ByteArray(IV_LEN)
        if (!bundle.readExactly(magic) || !magic.contentEquals(MAGIC) ||
            !bundle.readExactly(salt) || !bundle.readExactly(iv)
        ) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            }
            require(maxPlaintextBytes in 0 until Long.MAX_VALUE - TAG_BYTES)
            val ciphertextLimit = maxPlaintextBytes + TAG_BYTES
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var ciphertextBytes = 0L
            var plaintextBytes = 0L
            while (true) {
                val remainingWithProbe = (ciphertextLimit - ciphertextBytes) + 1L
                val wanted = minOf(buffer.size.toLong(), remainingWithProbe).toInt()
                val read = bundle.read(buffer, 0, wanted)
                if (read < 0) break
                if (read == 0) continue
                if (ciphertextBytes + read > ciphertextLimit) throw ByteLimitExceeded(maxPlaintextBytes)
                ciphertextBytes += read
                plaintextBytes = writeBounded(
                    output,
                    cipher.update(buffer, 0, read),
                    plaintextBytes,
                    maxPlaintextBytes,
                )
            }
            writeBounded(output, cipher.doFinal(), plaintextBytes, maxPlaintextBytes)
            true
        } catch (error: ByteLimitExceeded) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /** Write the v2 file container. Entry names are fixed metadata, never filesystem paths. */
    fun writeArchive(
        output: OutputStream,
        manifest: String,
        sources: List<ArchiveSource>,
        maxManifestBytes: Long = Long.MAX_VALUE,
    ) {
        require(sources.size < MAX_ARCHIVE_ENTRIES)
        require(sources.map { it.entry }.toSet().size == sources.size)
        require(sources.all { validArchiveEntry(it.entry) && it.entry != MANIFEST_ENTRY })
        val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
        if (manifestBytes.size.toLong() > maxManifestBytes) throw ByteLimitExceeded(maxManifestBytes)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestBytes)
            zip.closeEntry()
            for (source in sources) {
                zip.putNextEntry(ZipEntry(source.entry))
                source.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Read only the bounded manifest; Companion entries remain compressed and file-backed. */
    fun readManifest(archive: File, maxBytes: Long): String? = runCatching {
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            val seen = HashSet<String>(MAX_ARCHIVE_ENTRIES)
            var manifest: ZipEntry? = null
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count += 1
                if (count > MAX_ARCHIVE_ENTRIES ||
                    entry.isDirectory ||
                    !validArchiveEntry(entry.name) ||
                    !seen.add(entry.name)
                ) return@runCatching null
                if (entry.name == MANIFEST_ENTRY) manifest = entry
            }
            manifest ?: return@runCatching null
            if (manifest.isDirectory || manifest.size > maxBytes) return@runCatching null
            val bytes = zip.getInputStream(manifest).use { BoundedStreams.readBytes(it, maxBytes) }
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * Extract exactly [targets]. Duplicate, directory, unexpected or oversized entries fail closed.
     * Every target is removed on failure, including a partially written current entry.
     */
    fun extractArchive(
        archive: File,
        targets: List<ArchiveTarget>,
        allowedEntries: Set<String> = targets.map { it.entry }.toSet(),
    ): Boolean {
        val targetEntries = targets.map { it.entry }.toSet()
        if (targetEntries.size != targets.size ||
            allowedEntries.size >= MAX_ARCHIVE_ENTRIES ||
            targetEntries.any { it !in allowedEntries } ||
            allowedEntries.any { !validArchiveEntry(it) || it == MANIFEST_ENTRY } ||
            targets.any { it.maxBytes <= 0L }
        ) return false
        return withStagedFiles { staged ->
            targets.forEach { staged.stage(it.file) }
            val ok = try {
                ZipFile(archive).use { zip ->
                    val expected = allowedEntries + MANIFEST_ENTRY
                    val entries = zip.entries()
                    val found = HashMap<String, ZipEntry>(expected.size)
                    var count = 0
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        count += 1
                        if (count > expected.size ||
                            count > MAX_ARCHIVE_ENTRIES ||
                            entry.isDirectory ||
                            entry.name !in expected ||
                            found.put(entry.name, entry) != null
                        ) return@use false
                    }
                    if (found.keys != expected) return@use false
                    for (target in targets) {
                        val entry = found.getValue(target.entry)
                        val minimum = if (target.allowEmpty) 0L else 1L
                        if (entry.size !in minimum..target.maxBytes) return@use false
                        target.file.outputStream().use { output ->
                            zip.getInputStream(entry).use { input -> BoundedStreams.copy(input, output, target.maxBytes) }
                        }
                        if (target.file.length() !in minimum..target.maxBytes) return@use false
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
            if (ok) staged.commit()
            ok
        }
    }

    private fun validArchiveEntry(name: String): Boolean =
        name.matches(Regex("^[a-z0-9][a-z0-9._/-]{0,127}$")) &&
            !name.startsWith('/') && ".." !in name.split('/')

    private fun InputStream.readExactly(destination: ByteArray): Boolean {
        var offset = 0
        while (offset < destination.size) {
            val read = read(destination, offset, destination.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun writeBounded(
        output: OutputStream,
        bytes: ByteArray?,
        written: Long,
        maxBytes: Long,
    ): Long {
        if (bytes == null || bytes.isEmpty()) return written
        if (bytes.size.toLong() > maxBytes - written) throw ByteLimitExceeded(maxBytes)
        output.write(bytes)
        return written + bytes.size
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
