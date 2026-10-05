package io.panelassistant.android.control

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteDebugSecurityAuthorityStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `transition and Hardened authority retain one epoch across atomic process reload`() {
        val directory = temporary.newFolder("authority")
        val epochs = ArrayDeque(listOf(41L, 42L))
        val first = store(directory, nextEpoch = { epochs.removeFirst() })

        assertEquals(RemoteDebugSecurityAuthorityLoad.Absent, first.load())
        val transition = RemoteDebugSecurityAuthority(RemoteDebugSecurityState.TRANSITION, 41L)
        assertEquals(transition, first.publishTransition())
        assertEquals(
            RemoteDebugSecurityAuthorityLoad.Valid(transition),
            store(directory, nextEpoch = { 99L }).load(),
        )
        assertFalse(first.publishHardened(40L))
        assertFalse(first.publishHardened(42L))
        assertEquals(RemoteDebugSecurityAuthorityLoad.Valid(transition), first.load())

        assertTrue(first.publishHardened(41L))
        val hardened = RemoteDebugSecurityAuthority(RemoteDebugSecurityState.HARDENED, 41L)
        val reopened = store(directory, nextEpoch = { epochs.removeFirst() })
        assertEquals(RemoteDebugSecurityAuthorityLoad.Valid(hardened), reopened.load())
        assertFalse(reopened.publishHardened(41L))

        val next = RemoteDebugSecurityAuthority(RemoteDebugSecurityState.TRANSITION, 42L)
        assertEquals(next, reopened.publishTransition())
        assertFalse(reopened.publishHardened(41L))
        assertTrue(reopened.publishHardened(42L))
        assertEquals(
            RemoteDebugSecurityAuthorityLoad.Valid(
                RemoteDebugSecurityAuthority(RemoteDebugSecurityState.HARDENED, 42L),
            ),
            store(directory, nextEpoch = { 100L }).load(),
        )
    }

    @Test fun `record inventory fails closed for corrupt mode links directories and owner drift`() {
        val directory = temporary.newFolder("inventory")
        val record = File(directory, RECORD)
        val target = File(directory, "target")
        val store = store(directory, nextEpoch = { 7L })

        assertEquals(RemoteDebugSecurityAuthorityLoad.Absent, store.load())

        write0600(record, "corrupt".toByteArray())
        assertEquals(RemoteDebugSecurityAuthorityLoad.Corrupt, store.load())
        assertTrue(record.delete())

        assertTrue(record.mkdir())
        assertEquals(RemoteDebugSecurityAuthorityLoad.Corrupt, store.load())
        assertTrue(record.delete())

        write0600(target, canonicalRecord())
        Files.createSymbolicLink(record.toPath(), target.toPath())
        assertEquals(RemoteDebugSecurityAuthorityLoad.Corrupt, store.load())
        Files.delete(record.toPath())
        Files.delete(target.toPath())

        Files.createSymbolicLink(record.toPath(), target.toPath())
        assertEquals(RemoteDebugSecurityAuthorityLoad.Corrupt, store.load())
        Files.delete(record.toPath())

        write0600(record, canonicalRecord())
        Files.setPosixFilePermissions(
            record.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ),
        )
        assertEquals(RemoteDebugSecurityAuthorityLoad.Corrupt, store.load())
        Files.delete(record.toPath())

        write0600(record, canonicalRecord())
        Files.createLink(target.toPath(), record.toPath())
        assertEquals(RemoteDebugSecurityAuthorityLoad.Corrupt, store.load())
        Files.delete(target.toPath())
        Files.delete(record.toPath())

        write0600(record, canonicalRecord())
        val ownerChanged = changeOwnerWhenPermitted(record)
        if (ownerChanged) assertEquals(RemoteDebugSecurityAuthorityLoad.Corrupt, store.load())
        Files.delete(record.toPath())
        assertEquals(RemoteDebugSecurityAuthorityLoad.Absent, store.load())
    }

    @Test fun `failed Hardened target directory sync leaves a durable fence for fresh reload`() {
        val directory = temporary.newFolder("unsynced-hardened")
        val syncResults = ArrayDeque(listOf(true, true, false))
        val writer = store(
            directory,
            nextEpoch = { 77L },
            syncDirectory = { syncResults.removeFirst() },
        )

        assertEquals(
            RemoteDebugSecurityAuthority(RemoteDebugSecurityState.TRANSITION, 77L),
            writer.publishTransition(),
        )
        assertFalse(writer.publishHardened(77L))
        assertTrue(File(directory, PUBLICATION).exists())
        assertEquals(
            RemoteDebugSecurityAuthorityLoad.Corrupt,
            store(directory, nextEpoch = { 78L }).load(),
        )
    }

    private fun store(
        directory: File,
        nextEpoch: () -> Long,
        syncDirectory: (File) -> Boolean = ::hostSyncDirectory,
    ): RemoteDebugSecurityAuthorityStore {
        val parent = Files.readAttributes(
            directory.toPath(), PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        return RemoteDebugSecurityAuthorityStore(
            noBackupFilesDir = directory,
            nextEpoch = nextEpoch,
            validRegularFile = { hostValidRegular(it, parent.owner(), parent.group()) },
            chmod0600 = ::hostChmod0600,
            syncDirectory = syncDirectory,
        )
    }

    private fun canonicalRecord(): ByteArray = encodeRemoteDebugSecurityAuthority(
        RemoteDebugSecurityAuthority(RemoteDebugSecurityState.HARDENED, 7L),
    )

    private fun write0600(file: File, bytes: ByteArray) {
        Files.write(file.toPath(), bytes)
        assertTrue(hostChmod0600(file))
    }

    private fun changeOwnerWhenPermitted(file: File): Boolean = runCatching {
        val original = Files.getOwner(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        val lookup = file.toPath().fileSystem.userPrincipalLookupService
        val replacement = lookup.lookupPrincipalByName("nobody")
        if (replacement == original) return@runCatching false
        Files.setOwner(file.toPath(), replacement)
        Files.getOwner(file.toPath(), LinkOption.NOFOLLOW_LINKS) != original
    }.getOrDefault(false)

    private companion object {
        const val RECORD = "remote-debug-security-authority.v1"
        const val PUBLICATION = ".remote-debug-security-authority.v1.publish"

        val MODE_0600 = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        fun hostChmod0600(file: File): Boolean = runCatching {
            Files.setPosixFilePermissions(file.toPath(), MODE_0600)
            true
        }.getOrDefault(false)

        fun hostSyncDirectory(directory: File): Boolean = runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            true
        }.getOrDefault(false)

        fun hostValidRegular(file: File, owner: UserPrincipal, group: UserPrincipal): Boolean = runCatching {
            val attributes = Files.readAttributes(
                file.toPath(), PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
            )
            val nlink = Files.getAttribute(file.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number
            attributes.isRegularFile && nlink.toLong() == 1L && attributes.owner() == owner &&
                attributes.group() == group && attributes.permissions() == MODE_0600 &&
                Files.getFileAttributeView(
                    file.toPath(), PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                ) != null
        }.getOrDefault(false)
    }
}
