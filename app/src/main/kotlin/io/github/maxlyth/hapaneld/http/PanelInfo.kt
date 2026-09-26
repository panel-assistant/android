package io.github.maxlyth.hapaneld.http

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.webkit.WebSettings
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import android.webkit.WebView
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.util.AccessDenialMemo
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gathers the panel facts shown on the info page (`GET /`). Static device/version facts live here;
 * runtime status (MQTT/LED/sensors) is passed in as [extras] by the service, which owns those
 * objects. Returns an ordered map rendered verbatim as a key/value table.
 */
object PanelInfo {
    private const val SYS_BLOCK = "/sys/block"

    fun collect(
        context: Context,
        extras: Map<String, String>,
        profile: DeviceProfile,
    ): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["ha-paneld"] = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        m["Android"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        m["Firmware"] = Build.DISPLAY
        // Model + Platform promoted to lines 4-5 (panel identity up top). putAll below updates the values
        // in place without moving them (LinkedHashMap keeps first-insertion order).
        extras["Model"]?.let { m["Model"] = it }
        extras["Platform"]?.let { m["Platform"] = it }
        m["Device"] = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        m["Device ID"] = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "?"
        } catch (e: Throwable) {
            "?"
        }
        m["CPU"] = cpu()
        m["RAM"] = ram(context)
        m["Storage"] = storage()
        m["Display"] = display(context, profile)
        m["System WebView"] = webViewStatus(context).display
        m["HA Companion"] = companion(context)
        m.putAll(extras)
        return m
    }

    private fun cpu(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val cores = Runtime.getRuntime().availableProcessors()
        val hw = Build.HARDWARE
        return "$cores cores · $abi · $hw"
    }

    private fun ram(context: Context): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        "${formatDisplayBytes(mi.totalMem)} total · ${formatDisplayBytes(mi.availMem)} free"
    } catch (e: Throwable) {
        "?"
    }

    private fun storage(): String = try {
        val fs = StatFs(Environment.getDataDirectory().path)
        val free = fs.availableBlocksLong * fs.blockSizeLong
        // Advertised spec = the whole internal eMMC, so a user can tell e.g. an 8 GB panel from a 32 GB
        // one; /data is just one partition (~3.5 GB) and reads nothing like the box spec. Report eMMC
        // total + usable /data free; fall back to the /data total if the eMMC size can't be read.
        val emmc = emmcTotalBytes()
        if (emmc != null) "${gb(emmc)} eMMC · ${gb(free)} free (data)"
        else "${gb(fs.blockCountLong * fs.blockSizeLong)} (data) · ${gb(free)} free"
    } catch (e: Throwable) {
        "?"
    }

    /** Total internal storage = the largest WHOLE block device in `/sys/block` (its `size` in 512-byte
     *  sectors). Scanning beats deriving from /data's mount, which is a by-name symlink (e.g.
     *  `/dev/block/by-name/userdata`) an app can't traverse. Matches whole eMMC/SD devices only — the
     *  full-string regex excludes partitions and eMMC boot/rpmb areas (`mmcblk1boot0`, `mmcblk1p20`). */
    private fun emmcTotalBytes(): Long? = try {
        AccessDenialMemo.app.read(
            key = "dir:$SYS_BLOCK",
            what = "Block device list $SYS_BLOCK",
            probeDenied = { AccessDenialMemo.listDenied(SYS_BLOCK) },
        ) { File(SYS_BLOCK).listFiles { f -> f.name.matches(Regex("mmcblk\\d+|sd[a-z]")) } }
            ?.mapNotNull { runCatching { File(it, "size").readText().trim().toLong() }.getOrNull() }
            ?.maxOrNull()
            ?.let { it * 512L }
    } catch (e: Throwable) {
        null
    }

    /** Familiar user-facing labels; thresholds retain the existing 1024-based scaling. */
    internal fun formatDisplayBytes(bytes: Long): String {
        val bounded = bytes.coerceAtLeast(0L)
        if (bounded < 1024L) return "$bounded B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
        var value = bounded.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    internal fun databaseSummary(usage: EntityCatalogStore.DatabaseUsage): String = buildList {
        add("${formatDisplayBytes(usage.usedBytes)} used")
        if (usage.diskBytes > 0L) add("${formatDisplayBytes(usage.diskBytes)} on disk")
        if (usage.schemaVersion > 0) add("schema ${usage.schemaVersion}")
    }.joinToString(" · ")

    /** Marketing GB (÷10⁹) so the number reads close to the advertised spec (8 GB / 32 GB …). */
    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1e9)

    /** Physical resolution, current logical density, and independently profiled physical PPI when known. */
    private fun display(context: Context, profile: DeviceProfile): String {
        val observation = DisplayGeometryReport.observe(context) ?: return "?"
        val physical = profile.displayGeometry(observation.physicalWidthPx, observation.physicalHeightPx)?.physical
        return displaySummary(
            observation.viewportWidthPx,
            observation.viewportHeightPx,
            observation.currentDpi ?: 0,
            physical?.ppi?.let { Math.round(it).toInt() },
        )
    }

    /** Pure formatter so a base logical density can never regress into being labelled physical/native. */
    internal fun displaySummary(width: Int, height: Int, logicalDpi: Int, physicalPpi: Int?): String =
        "${width}×${height} px · logical $logicalDpi dpi" +
            (physicalPpi?.let { " · physical ≈$it ppi" } ?: "")

    /** Package + real-engine WebView status for the info row and the health banner. */
    // [engineMajor] = the real Chromium major (from the WebView UA when the package version looked old),
    // or the package major on the fast path, or null. Scheduled WebView updates read the full UA directly.
    class WebViewStatus(
        val display: String,
        val tooOld: Boolean,
        val engineMajor: Int? = null,
        val reportingQuirk: String? = null,
        val playManaged: Boolean = false,
    )

    /**
     * WebView status, disambiguating the Cromite/LineageOS version spoof. The package versionName
     * (`getCurrentWebViewPackage`) is what those SystemWebView builds STAMP to the OEM stock value to
     * pass a signature-locked provider gate — so it lies (e.g. reports 83 while the engine is Chromium
     * 147). We escalate to the real engine version (the WebView UA, which isn't spoofed) ONLY when the
     * package version looks too old, so a modern-package panel never pays the provider-load cost and a
     * Cromite-updated panel is no longer falsely warned.
     */
    fun webViewStatus(context: Context): WebViewStatus {
        val provider = webViewPackage()
        val pkg = provider.display
        val pkgMajor = PanelHealth.chromiumMajor(pkg)
        val playManaged = provider.packageName?.let { isPlayManaged(context, it) } == true
        // Fast path: unknown or already ≥ threshold — trust it, don't load the WebView provider.
        if (pkgMajor == null || pkgMajor >= PanelHealth.MIN_CHROMIUM) {
            return WebViewStatus(pkg, false, pkgMajor, playManaged = playManaged)
        }
        // Package looks old: could be genuinely old, or a Cromite/LineageOS swap stamping the OEM
        // version. The UA carries the true engine version — use it.
        val engine = engineVersion(context)
        val engineMajor = engine?.substringBefore('.')?.toIntOrNull()
        val presentation = webViewPresentation(pkg, pkgMajor, engine, engineMajor)
        return WebViewStatus(
            display = presentation.first,
            tooOld = PanelHealth.webViewTooOld(pkg, engineMajor),
            engineMajor = engineMajor ?: pkgMajor,
            reportingQuirk = presentation.second,
            playManaged = playManaged,
        )
    }

    /** Keep effective rendering capability in Panel information and return any deliberately retained
     *  package-version stamp separately for the diagnostic-context card. */
    internal fun webViewPresentation(
        packageSummary: String,
        packageMajor: Int?,
        engineVersion: String?,
        engineMajor: Int?,
    ): Pair<String, String?> =
        if (engineVersion != null && engineMajor != null && engineMajor != packageMajor) {
            val reportedVersion = packageSummary.substringAfterLast(' ', packageSummary)
            "Chromium $engineVersion rendering engine" to
                "System reports $reportedVersion · provider compatibility quirk"
        } else {
            packageSummary to null
        }

    private data class WebViewPackage(val display: String, val packageName: String?)

    private fun webViewPackage(): WebViewPackage = try {
        WebView.getCurrentWebViewPackage()?.let {
            WebViewPackage("${it.packageName} ${it.versionName}", it.packageName)
        } ?: WebViewPackage("unknown", null)
    } catch (e: Throwable) {
        WebViewPackage("unknown", null)
    }

    @Suppress("DEPRECATION")
    private fun isPlayManaged(context: Context, packageName: String): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(packageName)
        }
        isPlayStoreInstaller(installer)
    }.getOrDefault(false)

    internal fun isPlayStoreInstaller(installerPackage: String?): Boolean =
        installerPackage == "com.android.vending"

    /**
     * Whether a store owns updating this panel's WebView, asked on its own.
     *
     * [webViewStatus] answers the same question, but only as part of a verdict that may escalate to
     * reading the engine's real version — which loads the provider into this process. A caller that
     * only needs to know who owns the updates should not pay for that, so this asks the package
     * manager and stops.
     */
    fun webViewPlayManaged(context: Context): Boolean =
        webViewPackage().packageName?.let { isPlayManaged(context, it) } == true

    // Real engine version from the WebView default UA, computed once and cached: the fetch loads the
    // WebView provider into this process and must run on a Looper thread (see [defaultUserAgent]), and
    // the status page reaches it only via the escalation gate above. Scheduled updates read it directly
    // because a modern package version can still hide an older build within the recommended major.
    @Volatile private var uaComputed = false
    @Volatile private var uaEngineVersion: String? = null

    internal fun engineVersion(context: Context): String? {
        if (uaComputed) return uaEngineVersion
        synchronized(this) {
            if (uaComputed) return uaEngineVersion
            val ua = defaultUserAgent(context)
            uaEngineVersion = ua?.let { PanelHealth.engineVersionFromUa(it) }
            // A timed-out main-thread hop is transient; try again on the next update tick.
            uaComputed = ua != null
            return uaEngineVersion
        }
    }

    /** [WebSettings.getDefaultUserAgent] initialises the WebView provider, which is only safe on a
     *  Looper thread; the info page renders on a Ktor worker, so hop to the main thread (one-shot). */
    private fun defaultUserAgent(context: Context): String? = try {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            WebSettings.getDefaultUserAgent(context)
        } else {
            val ref = AtomicReference<String?>()
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                ref.set(runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull())
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
            ref.get()
        }
    } catch (e: Throwable) {
        null
    }

    private fun companion(context: Context): String {
        for (id in CompanionInstaller.SUPPORTED_PACKAGES) {
            try {
                val pi = context.packageManager.getPackageInfo(id, 0)
                return "${pi.versionName} ($id)"
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
        return "not installed"
    }

    /** Dashboard renderers that will actually draw on this panel: the built-in renderer when selected by
     *  Auto or explicitly, and any explicitly configured dashboard package. Empty ⇒ nothing will draw a
     *  dashboard (ha-paneld itself still runs fine). */
    fun dashboardRenderers(context: Context, dashboardPackage: String, haUrl: String = ""): List<String> {
        val out = mutableListOf<String>()
        if ((dashboardPackage.isBlank() || dashboardPackage == SystemController.BUILTIN_DASHBOARD) && haUrl.isNotBlank()) {
            out += "Built-in renderer"
        }
        if (dashboardPackage.isNotBlank() &&
            dashboardPackage != SystemController.BUILTIN_DASHBOARD &&
            installed(context, dashboardPackage)
        ) out += dashboardPackage
        return out
    }

    private fun installed(context: Context, id: String): Boolean = try {
        context.packageManager.getPackageInfo(id, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
