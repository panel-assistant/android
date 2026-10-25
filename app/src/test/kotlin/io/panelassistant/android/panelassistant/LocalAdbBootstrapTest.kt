package io.panelassistant.android.panelassistant

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LocalAdbBootstrapTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun run(root: File, bytes: ByteArray): Pair<Int, File> {
        val digest = hash(bytes)
        val source = File(root, "data/user/0/io.panelassistant.android/cache/adb-recovery/helper-$digest")
        requireNotNull(source.parentFile).mkdirs(); source.writeBytes(bytes)
        File(root, "data/local").mkdirs()
        val process = ProcessBuilder("/bin/sh", "-c", localAdbBootstrapProgram(source.absolutePath, digest, root.absolutePath)).redirectErrorStream(true).start()
        assertTrue("bootstrap is bounded", process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertFalse("bootstrap must not report shell parser failure: $output", output.contains("Syntax error"))
        return process.exitValue() to File(root, "data/local/panel-assistant-adb-recovery/helper")
    }
    @Test fun verifiedSeparateExecutableReplacesAStaleBuildAfterRebootWithoutTouchingTheIncumbent() {
        val root = temporary.newFolder("bootstrap")
        val old = File(root, "data/local/hapaneld-helper"); requireNotNull(old.parentFile).mkdirs(); old.writeText("incumbent")
        val first = "#!/bin/sh\nexit 0\n".toByteArray()
        val (result, target) = run(root, first)
        assertEquals(0, result); assertArrayEquals(first, target.readBytes())
        val next = "#!/bin/sh\n# next build\nexit 0\n".toByteArray()
        assertEquals(0, run(root, next).first)
        assertArrayEquals(next, target.readBytes())
        assertEquals("incumbent", old.readText())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(target.toPath()))
    }
    @Test fun aPreplantedDirectorySymlinkCannotPublishOutsideTheOwnedDirectory() {
        val root = temporary.newFolder("symlink")
        val parent = File(root, "data/local"); parent.mkdirs()
        val unrelated = File(root, "unrelated"); unrelated.mkdirs()
        Files.setPosixFilePermissions(unrelated.toPath(), setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE))
        Files.createSymbolicLink(File(parent, "panel-assistant-adb-recovery").toPath(), unrelated.toPath())
        assertNotEquals(0, run(root, "#!/bin/sh\nexit 0\n".toByteArray()).first)
        assertFalse(File(unrelated, "helper").exists())
        assertTrue(Files.isSymbolicLink(File(parent, "panel-assistant-adb-recovery").toPath()))
    }
}
