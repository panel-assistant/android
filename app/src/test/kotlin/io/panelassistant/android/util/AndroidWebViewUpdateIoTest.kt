@file:Suppress("DEPRECATION")

package io.panelassistant.android.util

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.test.mock.MockPackageManager
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.device.WebViewSpec
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.KeyStore
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.SSLContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sun.misc.Unsafe

/** Real adapter and pure-Java Android package/AtomicFile implementations, with scripted OS package facts. */
class AndroidWebViewUpdateIoTest {
    @get:Rule val temporary = TemporaryFolder()
    private val certificate = byteArrayOf(1, 3, 7)
    private val certificateHash = sha256(certificate)

    @Test fun archiveReadsActualSignerSdkAndNativeAbisFromPackageManagerAndZip() = withSdk(28) {
        val fixture = fixture()
        val archive = File(fixture.root, "candidate.apk")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("lib/arm64-v8a/libwebview.so", "lib/arm64-v8a/libsecond.so", "lib/armeabi-v7a/libwebview.so", "assets/lib/x86/libignored.so", "lib/x86/ignored.txt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        val info = packageInfo(archive, modern = true, code = 0x1_0000_002aL).apply {
            // Android 9/10 archive inspection may omit signingInfo; the real AppInstaller fallback must read legacy signatures.
            signingInfo = null
            applicationInfo!!.minSdkVersion = 26
        }
        fixture.pm.archive = info
        val candidate = fixture.io.inspect(archive)
        assertNotNull("A supported archive must be inspected", candidate)
        val parsed = candidate!!
        assertEquals(AppInstaller.WEBVIEW_PKG, parsed.pkg)
        assertEquals("138.0.0.0", parsed.versionName)
        assertEquals(0x1_0000_002aL, parsed.versionCode)
        assertEquals(setOf(certificateHash), parsed.signers)
        assertEquals(26, parsed.minSdk)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), parsed.abis)
        assertEquals(listOf(PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES, 0), fixture.pm.archiveFlags)
        info.signingInfo = signingInfo(arrayOf(Signature(certificate)))
        info.signatures = arrayOf(Signature(byteArrayOf(9)))
        assertEquals(setOf(certificateHash), fixture.io.inspect(archive)!!.signers)
        fixture.pm.archive = null
        assertNull(fixture.io.inspect(archive))
        fixture.pm.archive = info.apply { applicationInfo = null }
        assertNull(fixture.io.inspect(archive))
    }

    @Test fun installedReadbackRequiresVersionSignersSizeAndHashAcrossSupportedApis() {
        for (sdk in listOf(27, 28)) withSdk(sdk) {
            val fixture = fixture()
            val source = File(fixture.root, "installed.apk").apply { writeBytes(byteArrayOf(2, 4, 6, 8)) }
            val code = if (sdk == 27) 42L else 0x1_0000_002aL
            val info = packageInfo(source, modern = sdk >= 28, code = code)
            // Give the unused API's signer path a different signer so a wrong API branch cannot pass.
            if (sdk == 27) info.signingInfo = signingInfo(arrayOf(Signature(byteArrayOf(9))))
            else info.signatures = arrayOf(Signature(byteArrayOf(9)))
            fixture.pm.installed = info
            val spec = spec(source, code)
            assertTrue("SDK $sdk must read the installed package", fixture.io.installedMatches(spec))
            assertEquals(listOf(if (sdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES), fixture.pm.installedFlags)
            assertFalse(fixture.io.installedMatches(spec.copy(version = "wrong")))
            assertFalse(fixture.io.installedMatches(spec.copy(versionCode = code + 1)))
            assertFalse(fixture.io.installedMatches(spec.copy(certSha256 = "f".repeat(64))))
            assertFalse(fixture.io.installedMatches(spec.copy(size = spec.size + 1)))
            source.writeBytes(byteArrayOf(8, 6, 4, 2))
            assertEquals(spec.size, source.length())
            assertFalse("Equal size cannot replace installed SHA-256 readback", fixture.io.installedMatches(spec))
            fixture.pm.installed = null
            assertFalse(fixture.io.installedMatches(spec))
            fixture.pm.installed = info.apply { applicationInfo = null }
            assertFalse(fixture.io.installedMatches(spec))
        }
    }

    @Test fun receiptRecoversAtomicBackupAndRollsBackInterruptedWriteBeforeAnyRead() {
        val fixture = fixture()
        val original = JSONObject().put("request_id", "original").put("state", "submitted_unknown")
        fixture.io.writeReceipt(original)
        val receipt = File(fixture.io.directory, "receipt.json")
        assertEquals(original.toString(), receipt.readText())
        val backup = File(receipt.path + ".bak")
        assertTrue(receipt.renameTo(backup))
        val recovered = fixture.io.readReceipt()
        assertNotNull("A backup-only receipt must recover", recovered)
        assertEquals("original", recovered!!.getString("request_id"))
        assertTrue(receipt.exists())
        assertFalse(backup.exists())
        original.put("state", "installed")
        fixture.io.writeReceipt(original)
        assertEquals("A successful overwrite must commit before readback", original.toString(), receipt.readText())
        assertFalse("A completed overwrite must retire its atomic backup", backup.exists())
        val broken = object : JSONObject() {
            override fun toString(): String = throw IOException("receipt serialization interrupted after startWrite")
        }
        try {
            fixture.io.writeReceipt(broken)
            fail("The write failure must be propagated")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("interrupted"))
        }
        // Read the filesystem first: readReceipt/openRead would itself recover a forgotten .bak.
        assertEquals("Rollback must already have restored the old receipt", original.toString(), receipt.readText())
        assertFalse("Rollback must not leave recovery debt", backup.exists())
        assertEquals("original", fixture.io.readReceipt()!!.getString("request_id"))
    }

    @Test fun providerObservationUsesPlatformPackageAndInstallerAdmission() {
        val factory = Class.forName("android.webkit.WebViewFactory").getDeclaredField("sPackageInfo").apply { isAccessible = true }
        val previous = factory.get(null)
        val abiField = Build::class.java.getDeclaredField("SUPPORTED_ABIS")
        val abiOwner = unsafe.staticFieldBase(abiField)
        val abiOffset = unsafe.staticFieldOffset(abiField)
        val oldAbis = unsafe.getObject(abiOwner, abiOffset)
        unsafe.putObject(abiOwner, abiOffset, arrayOf("arm64-v8a", "armeabi-v7a"))
        try {
            for (sdk in listOf(27, 28, 30)) withSdk(sdk) {
                val fixture = fixture()
                val source = File(fixture.root, "installed.apk").apply { writeBytes(byteArrayOf(1)) }
                val code = if (sdk < 28) 42L else 0x1_0000_002aL
                fixture.pm.installed = packageInfo(source, sdk >= 28, code)
                factory.set(null, packageInfo(source, sdk >= 28, 17).apply { versionName = "bound-version" })
                fixture.pm.installer = "com.android.vending"
                val observed = fixture.io.observe()
                assertEquals(sdk, observed.sdk)
                assertEquals(setOf("arm64-v8a", "armeabi-v7a"), observed.abis)
                assertEquals(AppInstaller.WEBVIEW_PKG, observed.providerPackage)
                assertEquals("138.0.0.0", observed.versionName)
                assertEquals(code, observed.versionCode)
                assertEquals(setOf(certificateHash), observed.signers)
                assertEquals("bound-version", observed.boundVersionName)
                assertEquals(17L, observed.boundVersionCode)
                assertEquals("com.android.vending", observed.installerPackage)
                assertEquals(true, observed.playManaged)
                assertEquals(if (sdk >= 30) 1 else 0, fixture.pm.modernInstallerReads)
                assertEquals(if (sdk < 30) 1 else 0, fixture.pm.legacyInstallerReads)
                fixture.pm.installer = "another.installer"
                assertEquals(false, fixture.io.observe().playManaged)
                fixture.pm.installer = null
                assertEquals(false, fixture.io.observe().playManaged)
                fixture.pm.installerFailure = true
                val failed = fixture.io.observe()
                assertNull(failed.installerPackage)
                assertNull("A failed installer lookup is unknown", failed.playManaged)
            }
        } finally {
            factory.set(null, previous)
            unsafe.putObject(abiOwner, abiOffset, oldAbis)
        }
    }

    @Test fun downloadRefusesInsufficientSpaceAndTransportFailures() {
        val fixture = fixture()
        assertTrue(fixture.io.directory.mkdirs())
        val store = File(fixture.root, "localhost.p12")
        val password = "throwaway-provider-test"
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path,
            "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2",
            "-keystore", store.path, "-storetype", "PKCS12", "-storepass", password)
            .redirectErrorStream(true).start()
        val keytoolOutput = process.inputStream.bufferedReader().readText()
        assertEquals(keytoolOutput, 0, process.waitFor())
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password.toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password.toCharArray()) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(serverContext)
        val hits = AtomicInteger()
        var status = 200
        var bytes = byteArrayOf(2, 4, 6, 8)
        server.createContext("/provider.apk") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.socketFactory)
        server.start()
        try {
            val source = File(fixture.root, "expected.apk").apply { writeBytes(bytes) }
            val expected = spec(source).copy(url = "https://localhost:${server.address.port}/provider.apk")
            val destination = File(fixture.io.directory, "candidate.apk")
            assertTrue(fixture.io.download(expected, destination))
            assertArrayEquals(bytes, destination.readBytes())
            assertEquals(1, hits.get())
            assertFalse(fixture.io.download(expected.copy(size = Long.MAX_VALUE), destination))
            assertEquals("Insufficient space must prevent opening a connection", 1, hits.get())
            status = 503
            assertFalse(fixture.io.download(expected, destination))
            assertEquals(2, hits.get())
            status = 200
            bytes = ByteArray(8) { 1 }
            assertFalse("A response larger than the manifest bound must fail", fixture.io.download(expected, destination))
            assertEquals(3, hits.get())
            server.stop(0)
            assertFalse(fixture.io.download(expected, destination))
        } finally {
            server.stop(0)
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
        }
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val pm = unsafe.allocateInstance(ScriptedPackages::class.java) as ScriptedPackages
        pm.archiveFlags = mutableListOf()
        pm.installedFlags = mutableListOf()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = root
            override fun getPackageManager(): PackageManager = pm
        }
        return Fixture(root, pm, AndroidWebViewUpdateIo(context, fakeProfile()))
    }

    private data class Fixture(val root: File, val pm: ScriptedPackages, val io: AndroidWebViewUpdateIo)

    private class ScriptedPackages : MockPackageManager() {
        var installed: PackageInfo? = null
        var archive: PackageInfo? = null
        var installer: String? = null
        var installerFailure = false
        var modernInstallerReads = 0
        var legacyInstallerReads = 0
        override fun getInstallerPackageName(packageName: String): String? {
            legacyInstallerReads++
            if (installerFailure) throw SecurityException("installer unavailable")
            return installer
        }
        override fun getInstallSourceInfo(packageName: String): InstallSourceInfo {
            modernInstallerReads++
            if (installerFailure) throw SecurityException("installer unavailable")
            return InstallSourceInfo::class.java.getDeclaredConstructor(String::class.java, SigningInfo::class.java,
                String::class.java, String::class.java).apply { isAccessible = true }
                .newInstance("initiator", null, "originator", installer)
        }
        lateinit var installedFlags: MutableList<Int>
        lateinit var archiveFlags: MutableList<Int>
        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
            check(packageName == AppInstaller.WEBVIEW_PKG)
            installedFlags += flags
            return installed ?: throw PackageManager.NameNotFoundException(packageName)
        }
        override fun getPackageArchiveInfo(archiveFilePath: String, flags: Int): PackageInfo? {
            check(File(archiveFilePath).exists())
            archiveFlags += flags
            return archive
        }
    }

    private fun packageInfo(source: File, modern: Boolean, code: Long) = PackageInfo().apply {
        packageName = AppInstaller.WEBVIEW_PKG
        versionName = "138.0.0.0"
        setLongVersionCode(code)
        signatures = arrayOf(Signature(certificate))
        if (modern) signingInfo = signingInfo(arrayOf(Signature(certificate)))
        applicationInfo = ApplicationInfo().apply { sourceDir = source.path; minSdkVersion = 26 }
    }

    private fun signingInfo(signatures: Array<Signature>): SigningInfo {
        val detailsClass = Class.forName("android.content.pm.PackageParser\$SigningDetails")
        val details = unsafe.allocateInstance(detailsClass)
        detailsClass.getDeclaredField("signatures").apply { isAccessible = true }.set(details, signatures)
        return (unsafe.allocateInstance(SigningInfo::class.java) as SigningInfo).also { info ->
            SigningInfo::class.java.getDeclaredField("mSigningDetails").apply { isAccessible = true }.set(info, details)
        }
    }

    private fun spec(file: File, code: Long = 42) = WebViewSpec(
        "https://example.invalid/provider.apk", "138.0.0.0", certificateHash, AppInstaller.sha256(file), code,
        file.length(), setOf("arm64-v8a"), 26, 28,
    )

    private fun <T> withSdk(sdk: Int, block: () -> T): T {
        val field = Build.VERSION::class.java.getDeclaredField("SDK_INT")
        val owner = unsafe.staticFieldBase(field)
        val offset = unsafe.staticFieldOffset(field)
        val before = unsafe.getInt(owner, offset)
        unsafe.putInt(owner, offset, sdk)
        return try { block() } finally { unsafe.putInt(owner, offset, before) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run { isAccessible = true; get(null) as Unsafe }
    }
}
