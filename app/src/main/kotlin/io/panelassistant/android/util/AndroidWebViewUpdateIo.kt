package io.panelassistant.android.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.util.AtomicFile
import android.webkit.WebView
import io.panelassistant.android.control.Su
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.http.PanelInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject

/** Android adapter for the provider owner; no package removal or system-partition route exists here. */
internal class AndroidWebViewUpdateIo(private val context: Context, private val profile: DeviceProfile) : WebViewUpdateIo {
    override val directory = File(context.filesDir, "webview-install")
    private val receipt = AtomicFile(File(directory, "receipt.json"))

    @Suppress("DEPRECATION")
    override fun observe(): WebViewObservation {
        // This process's default UA binds the provider. Installed package facts are read separately below.
        val engine = PanelInfo.engineVersion(context)
        val bound = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val installed = bound?.packageName?.let { packageInfo(it) }
        val installer = installed?.let { info -> runCatching {
            if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(info.packageName).installingPackageName
            else context.packageManager.getInstallerPackageName(info.packageName)
        } }
        val data = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        return WebViewObservation(
            profile.id, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet(), PROCESS_ID, Process.myPid(), Process.getStartElapsedRealtime(),
            installed?.packageName, installed?.versionName, installed?.let(::versionCode), installed?.let(::signers).orEmpty(),
            installer?.getOrNull(), installer?.takeIf { it.isSuccess }?.let { it.getOrNull() == "com.android.vending" },
            engine, when { Su.observedUsable() -> "su"; HelperClient.available() -> "helper"; else -> "none" },
            bound?.packageName, bound?.versionName, bound?.let(::versionCode),
            data?.availableBytes, data?.totalBytes,
        )
    }

    override fun inspect(file: File): WebViewApk? {
        val info = AppInstaller.inspect(context, file.path) ?: return null
        val archive = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
        val minSdk = archive.applicationInfo?.minSdkVersion ?: return null
        val abis = ZipFile(file).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.substringAfter("lib/").substringBefore('/') }.toSet()
        }
        return WebViewApk(info.pkg, info.version, info.versionCode, info.signerSha256s.toSet(), minSdk, abis)
    }

    override fun readReceipt(): JSONObject? {
        if (!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists()) return null
        val bytes = receipt.openRead().use { BoundedStreams.readBytes(it, 4096L) }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    override fun writeReceipt(receipt: JSONObject) {
        directory.mkdirs()
        val stream = this.receipt.startWrite()
        try {
            stream.write(receipt.toString().toByteArray(Charsets.UTF_8))
            this.receipt.finishWrite(stream)
        } catch (failure: Exception) {
            this.receipt.failWrite(stream)
            throw failure
        }
    }

    override fun deleteReceipt() = receipt.delete()

    override fun download(spec: io.panelassistant.android.device.WebViewSpec, destination: File): String? =
        fetch(spec, destination, directory.usableSpace)

    override fun installedMatches(spec: io.panelassistant.android.device.WebViewSpec): Boolean = runCatching {
        val installed = packageInfo(AppInstaller.WEBVIEW_PKG) ?: return@runCatching false
        if (versionCode(installed) != spec.versionCode || installed.versionName != spec.version || signers(installed) != setOf(spec.certSha256)) {
            return@runCatching false
        }
        val source = installed.applicationInfo?.sourceDir?.let(::File) ?: return@runCatching false
        source.length() == spec.size && AppInstaller.sha256(source) == spec.apkSha256
    }.getOrDefault(false)

    override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome =
        AppInstaller.installStrictUpgrade(context, file, beforeSubmit)

    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(pkg, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().mapTo(linkedSetOf()) {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    companion object {
        private val PROCESS_ID = UUID.randomUUID().toString()

        /** Null when [destination] holds the pinned response; otherwise the transaction's download cause. */
        internal fun fetch(
            spec: io.panelassistant.android.device.WebViewSpec,
            destination: File,
            usableSpace: Long,
            openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        ): String? {
            if (usableSpace < spec.size) return "no_space"
            var cause = "failed"
            val result = AppInstaller.download(spec.url, destination, spec.size, cause = { cause = it }, openConnection = openConnection)
            return if (result == AppInstaller.DownloadResult.Succeeded) null else cause
        }
    }
}
