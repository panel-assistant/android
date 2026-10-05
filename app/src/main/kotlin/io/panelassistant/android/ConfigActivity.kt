package io.panelassistant.android

import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.control.HardenedNetworkAdbAdmission
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.control.VerifiedRelayTransition
import io.panelassistant.android.shizuku.ShizukuConsent
import io.panelassistant.android.shizuku.ShizukuManagerIdentity
import io.panelassistant.android.shizuku.ShizukuSetupDialog
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.util.LocalAdminEndpoint
import io.panelassistant.android.util.LocalAdminReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read SQLite-backed Activity state without running the read on Android's looper. */
internal suspend fun <T> readActivityStateOffMain(read: () -> T): T =
    withContext(Dispatchers.IO) { read() }

/**
 * In-app config screen: a WebView onto the local config page (127.0.0.1:8888). Panels are usually
 * kiosks with no browser installed, so an `ACTION_VIEW http://…` intent finds no handler and silently
 * does nothing — this guarantees the config page opens on the panel itself. Cleartext to localhost is
 * permitted by `usesCleartextTraffic` in the manifest.
 *
 * Opened from the built-in dashboard's "App Configuration" sidebar entry, so it carries a top bar with
 * a back control that returns to the dashboard, and it pins the web UI to the panel's own light/dark.
 */
class ConfigActivity : AppCompatActivity() {

    private val maintenanceFence = GuardDbActivityMaintenanceFence()
    private lateinit var web: WebView
    private lateinit var readinessPanel: LinearLayout
    private lateinit var readinessMessage: TextView
    private lateinit var readinessProgress: ProgressBar
    private lateinit var retryButton: Button
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val readiness = LocalAdminReadiness(attempts = 10, retryDelayMs = 250L)
    private var readinessJob: Job? = null
    private var pageUrl: String = ""
    private var healthUrl: String = ""

    override fun onStart() {
        super.onStart()
        if (maintenanceFence.stop(this)) return
        KioskAdminUi.setVisible(this, true)
    }

    override fun onStop() {
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maintenanceFence.stop(this)) return
        KioskAdminUi.setVisible(this, true)
        supportActionBar?.hide()
        web = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    NativeLocale.apply(Config(this@ConfigActivity).uiLanguage)
                }
            } // keep links + the config-form POST inside this WebView
        }
        readinessMessage = TextView(this).apply {
            setText(R.string.config_service_starting)
            gravity = Gravity.CENTER
        }
        readinessProgress = ProgressBar(this)
        retryButton = Button(this).apply {
            setText(R.string.retry)
            isAllCaps = false
            isEnabled = false
            setOnClickListener { waitForAdminServer(restartService = true) }
        }
        readinessPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(readinessProgress)
            addView(
                readinessMessage,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 20
                },
            )
            addView(
                retryButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 20
                },
            )
        }
        // A top bar with a back arrow → return to the dashboard (finish this activity). Without it there
        // is no obvious way off the config page on a kiosk panel with no visible system nav.
        lateinit var enhancedAccessItem: android.view.MenuItem
        lateinit var securityModeItem: android.view.MenuItem
        val bar = Toolbar(this).apply {
            title = getString(applicationInfo.labelRes).ifBlank { "ha-paneld" }
            subtitle = getString(R.string.settings)
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@ConfigActivity, R.drawable.ic_toolbar_back,
            )
            navigationContentDescription = getString(R.string.back_to_dashboard)
            setNavigationOnClickListener { finish() }
            enhancedAccessItem = menu.add(R.string.enhanced_access).apply {
                isVisible = false
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    ShizukuSetupDialog.show(this@ConfigActivity)
                    true
                }
            }
            securityModeItem = menu.add(R.string.security_mode).apply {
                isEnabled = false
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    showSecurityModeDialog()
                    true
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(readinessPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // Optional path (e.g. the sidebar "App Configuration" tap opens /configure); default = the info page.
        val path = intent?.getStringExtra("path")?.takeIf { it.startsWith("/") } ?: "/"
        // Pin the web UI to the panel's own light/dark (the app applies DayNight at process start, so the
        // activity's uiMode reflects the panel's dark_mode/system setting). The :8888 UI honours ?theme=.
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val sep = if (path.contains('?')) "&" else "?"
        // ConfigActivity can be entered from the launcher or dashboard during process recovery. Ensure the
        // service is requested, then wait for its actual liveness endpoint instead of racing WebView load.
        activityScope.launch {
            val (port, enhancedAccessVisible) = readActivityStateOffMain {
                val port = Config(applicationContext).httpPort
                val visible = ShizukuSetupDialog.entryVisible(
                    consented = ShizukuConsent.enabled(applicationContext),
                    managerStatus = ShizukuManagerIdentity.status(applicationContext),
                )
                port to visible
            }
            if (!isActive) return@launch
            securityModeItem.isEnabled = true
            enhancedAccessItem.isVisible = enhancedAccessVisible
            pageUrl = LocalAdminEndpoint.loopbackUrl(port, "$path${sep}theme=${if (dark) "dark" else "light"}")
            healthUrl = LocalAdminEndpoint.loopbackUrl(port, "/health")
            PaneldService.start(this@ConfigActivity, fromVisibleActivity = true)
            waitForAdminServer()
        }
    }

    private fun showSecurityModeDialog() {
        val config = Config(this)
        val hardened = config.hardenedSecurityEnabled
        val pending = LocalApprovalBroker.instance.pending().size
        AlertDialog.Builder(this)
            .setTitle(R.string.security_mode)
            .setMessage(
                if (hardened) {
                    getString(R.string.security_mode_hardened_summary, pending)
                } else {
                    getString(R.string.security_mode_relaxed_summary)
                },
            )
            .setPositiveButton(if (hardened) R.string.use_relaxed_mode else R.string.enable_hardened_mode) { _, _ ->
                if (hardened) {
                    activityScope.launch {
                        withContext(Dispatchers.IO) {
                            RemoteDebugSecurityTransitionGate.mutate {
                                config.setSecurityMode(Config.SecurityMode.RELAXED)
                            }
                            // Keep the durable mode change and revocation together even if this
                            // Activity is destroyed before its coroutine returns to main.
                            LocalApprovalBroker.instance.clear()
                        }
                    }
                } else {
                    enableHardenedMode(config)
                }
            }
            .setNeutralButton(R.string.review_approvals) { _, _ -> showPendingApprovals() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun enableHardenedMode(config: Config) {
        activityScope.launch {
            var networkAdbAdmission = HardenedNetworkAdbAdmission.UNVERIFIED
            // Root process/listener termination and property reads are bounded but blocking. Keep the
            // complete relay-stop -> ADB-proof -> durable-mode transition off the activity thread and
            // under the relay lifecycle lock so an inspect/start request cannot cross the commit.
            val transition = withContext(Dispatchers.IO) {
                RemoteDebugSecurityTransitionGate.mutate {
                    val result = CdpRelay.stopAndVerifyThen(this@ConfigActivity) {
                        networkAdbAdmission = AdbController(this@ConfigActivity, config)
                            .commitHardenedWhenRemoteAdbInactive {
                                config.setSecurityMode(Config.SecurityMode.HARDENED)
                            }
                        networkAdbAdmission == HardenedNetworkAdbAdmission.APPLIED
                    }
                    if (result == VerifiedRelayTransition.APPLIED &&
                        !RemoteDebugSecurityTransitionGate.sealHardened()
                    ) VerifiedRelayTransition.ACTION_FAILED else result
                }
            }
            if (transition == VerifiedRelayTransition.VERIFICATION_FAILED) {
                AlertDialog.Builder(this@ConfigActivity)
                    .setTitle(R.string.unable_stop_remote_debugging)
                    .setMessage(R.string.unable_stop_remote_debugging_detail)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } else when (networkAdbAdmission) {
                HardenedNetworkAdbAdmission.DISABLE_FAILED -> {
                    AlertDialog.Builder(this@ConfigActivity)
                        .setTitle(R.string.unable_stop_remote_adb)
                        .setMessage(R.string.unable_stop_remote_adb_detail)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
                HardenedNetworkAdbAdmission.UNVERIFIED -> {
                    AlertDialog.Builder(this@ConfigActivity)
                        .setTitle(R.string.unable_verify_remote_adb)
                        .setMessage(R.string.unable_verify_remote_adb_detail)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
                HardenedNetworkAdbAdmission.ACTIVE -> {
                    AlertDialog.Builder(this@ConfigActivity)
                        .setTitle(R.string.turn_off_remote_adb_first)
                        .setMessage(R.string.turn_off_remote_adb_first_detail)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
                HardenedNetworkAdbAdmission.COMMIT_FAILED -> {
                    AlertDialog.Builder(this@ConfigActivity)
                        .setTitle(R.string.unable_enable_hardened_mode)
                        .setMessage(R.string.unable_enable_hardened_mode_detail)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
                HardenedNetworkAdbAdmission.APPLIED -> Unit
            }
        }
    }

    private fun showPendingApprovals() {
        val pending = LocalApprovalBroker.instance.pending()
        if (pending.isEmpty()) {
            AlertDialog.Builder(this).setTitle(R.string.pending_approvals).setMessage(R.string.no_pending_requests)
                .setPositiveButton(R.string.ok, null).show()
            return
        }
        val labels = pending.map {
            getString(R.string.approval_list_item, localizedLabel(it.operation), it.summary, it.peer)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.approve_one_request)
            .setItems(labels) { _, which -> confirmApproval(pending[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmApproval(pending: io.panelassistant.android.security.PendingApproval) {
        AlertDialog.Builder(this)
            .setTitle(localizedLabel(pending.operation))
            .setMessage(getString(R.string.approval_request_detail, pending.summary, pending.peer))
            .setPositiveButton(R.string.approve) { _, _ -> LocalApprovalBroker.instance.approve(pending.id) }
            .setNegativeButton(R.string.deny) { _, _ -> LocalApprovalBroker.instance.deny(pending.id) }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun waitForAdminServer(restartService: Boolean = false) {
        readinessJob?.cancel()
        web.visibility = View.GONE
        readinessPanel.visibility = View.VISIBLE
        readinessProgress.visibility = View.VISIBLE
        readinessMessage.setText(R.string.config_service_starting)
        retryButton.isEnabled = false
        retryButton.visibility = View.GONE
        if (restartService) {
            // Register the fresh START_STICKY request synchronously while this Activity still owns the
            // main thread. Service teardown deliberately exits the process after cleanup, so an
            // in-process delayed restart could die before it reached ActivityManager.
            stopService(Intent(this, PaneldService::class.java))
            PaneldService.start(this, fromVisibleActivity = true)
        }
        readinessJob = activityScope.launch {
            val ready = withContext(Dispatchers.IO) {
                readiness.await(probe = { LocalAdminReadiness.healthProbe(healthUrl, timeoutMs = 350) })
            }
            if (!isActive) return@launch
            if (ready) {
                readinessPanel.visibility = View.GONE
                web.visibility = View.VISIBLE
                web.loadUrl(pageUrl)
            } else {
                readinessProgress.visibility = View.GONE
                readinessMessage.setText(R.string.config_service_not_ready)
                retryButton.visibility = View.VISIBLE
                retryButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        KioskAdminUi.setVisible(this, false)
        readinessJob?.cancel()
        activityScope.cancel()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (maintenanceFence.stop(this)) return
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

}
