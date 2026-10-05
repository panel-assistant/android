package io.github.maxlyth.hapaneld

import io.panelassistant.android.R
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * On-demand **admin launcher** for the panel — a slim app drawer reachable from the navbar Launcher
 * button (see [io.github.maxlyth.hapaneld.control.NavbarController]).
 *
 * **Why this exists:** the HA Companion is the panel's *home* app (it boots straight to the dashboard),
 * which is what you want 99% of the time. But administering a panel — opening Settings, another app,
 * or ha-paneld's own config — needs an app drawer, and the vendor's pseudo-launcher (when one is even
 * installed) fights HA/kiosk operation. Users previously sideloaded a third-party launcher (`l.l`) as a
 * "launcher of last resort" just for this. This Activity replaces that: a built-in, on-demand drawer
 * that never competes for HOME.
 *
 * It is a foreground Activity, so it can `startActivity` directly — no root / daemon needed to launch
 * an app from here (unlike [io.github.maxlyth.hapaneld.control.SystemController], which runs from the
 * background service and is blocked by Android 10+ background-activity-start).
 */
class AdminLauncherActivity : AppCompatActivity() {

    private val maintenanceFence = GuardDbActivityMaintenanceFence()
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * The same light/dark decision every other ha-paneld screen makes — the panel's configured
     * dashboard theme first, then the system on the Android versions that reliably have one. Reading
     * only the system setting, as this screen used to, meant a panel pinned to one theme could show
     * this screen in the other.
     */
    private val dark: Boolean get() = StatusSurface.darkFor(this, Config(this))

    /**
     * Card colours, deliberately NOT the shared status palette.
     *
     * This screen is a tile grid, not a status surface: its tiles are drawn in the status palette's
     * background colour, so the page behind them has to be a shade off it or the cards vanish. The
     * shared palette is used for everything that is not that relationship.
     */
    private val paletteFor get() = statusPalette(dark)
    private val bg get() = if (dark) paletteFor.background else "#f3f4f6"
    private val cardBg get() = if (dark) "#1d1f24" else paletteFor.background
    private val body get() = paletteFor.body
    private val subtle get() = paletteFor.subtle
    private val accent get() = if (dark) paletteFor.actionBackground else paletteFor.accent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maintenanceFence.stop(this)) return
        KioskAdminUi.setVisible(this, true)
        supportActionBar?.hide()
        setContentView(TextView(this).apply {
            setText(R.string.panel_admin)
            gravity = Gravity.CENTER
        })
    }

    override fun onStart() {
        super.onStart()
        if (maintenanceFence.stop(this)) return
        KioskAdminUi.setVisible(this, true)
    }

    override fun onStop() {
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        KioskAdminUi.setVisible(this, false)
        refreshJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }

    // Rebuild the drawer each time it's shown — the installed-app set and night mode can change while
    // ha-paneld stays resident.
    override fun onResume() {
        super.onResume()
        if (maintenanceFence.stop(this)) return
        refreshJob?.cancel()
        refreshJob = activityScope.launch {
            // Config can open SQLite namespaces on a cold HOME launch.
            val language = readActivityStateOffMain { Config(applicationContext).uiLanguage }
            if (!isActive) return@launch
            NativeLocale.apply(language)
            setContentView(buildUi())
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        super.onPause()
    }

    private fun buildUi(): View {
        val dashboardTarget = dashboardTarget()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Named, not just titled. This screen is also where the dashboard lands when the panel has no
        // usable System WebView — a terminal failure whose replacement screen must still say whose it is.
        root.addView(
            statusBrandMark(this, dark),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(StatusSurface.specFor(this).brandHeightDp),
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            },
        )
        root.addView(text(getString(R.string.panel_admin), 20f, body, bold = true, padBottom = 2))
        root.addView(text(
            getString(R.string.panel_admin_summary),
            12.5f, subtle, padBottom = 14,
        ))

        // Admin shortcuts — the things you open a drawer to reach but that aren't ordinary app icons.
        root.addView(grid(adminTiles(dashboardTarget)))

        root.addView(text(getString(R.string.apps), 14f, subtle, bold = true, padTop = 18, padBottom = 8))
        val apps = installedApps(dashboardTarget)
        if (apps.isEmpty()) {
            root.addView(text(getString(R.string.no_launchable_apps), 13f, subtle))
        } else {
            root.addView(grid(apps.map { ri ->
                Tile(
                    label = ri.loadLabel(packageManager).toString(),
                    icon = runCatching { ri.loadIcon(packageManager) }.getOrNull(),
                ) { launch(ri) }
            }))
        }

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            isFillViewport = true
            addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // --- tiles ---

    private data class Tile(
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val badge: String? = null,
        val onClick: () -> Unit,
    )

    private fun adminTiles(dashboardTarget: RendererTarget?): List<Tile> = buildList {
        when (dashboardTarget) {
            RendererTarget.Builtin -> add(Tile(getString(R.string.dashboard), appIcon(packageName), "HA") {
                startSafely(Intent(this@AdminLauncherActivity, DashboardActivity::class.java))
            })
            is RendererTarget.Foreign -> add(Tile(getString(R.string.dashboard), appIcon(dashboardTarget.packageName), "HA") {
                packageManager.getLaunchIntentForPackage(dashboardTarget.packageName)?.let(::startSafely)
            })
            null -> Unit
        }
        add(Tile(getString(R.string.settings), appIcon("com.android.settings"), "⚙") {
            startSafely(Intent(Settings.ACTION_SETTINGS))
        })
        // ha-paneld's own front-door screen (wordmark + config URL/QR + dashboard/config buttons).
        add(Tile("ha-paneld", appIcon(packageName), "cfg") {
            startSafely(Intent(this@AdminLauncherActivity, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_EXPLICIT_ADMIN_ENTRY, true)
            })
        })
    }

    /** Lay tiles out in a column-count grid sized to the panel width (square 480 → 4 cols). */
    private fun grid(tiles: List<Tile>): GridLayout {
        val cellW = dp(84)
        val cols = (resources.displayMetrics.widthPixels / cellW).coerceIn(3, 6)
        return GridLayout(this).apply {
            columnCount = cols
            tiles.forEach { addView(tileView(it, cellW)) }
        }
    }

    private fun tileView(t: Tile, cellW: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.parseColor(cardBg))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { t.onClick() }
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellW; height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
        val iconSize = dp(44)
        if (t.icon != null) {
            cell.addView(ImageView(this).apply {
                setImageDrawable(t.icon)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
        } else {
            // Fallback glyph chip for admin tiles whose package has no icon (e.g. Settings on some ROMs).
            cell.addView(TextView(this).apply {
                gravity = Gravity.CENTER
                text = t.badge ?: t.label.take(1).uppercase()
                setTextColor(Color.WHITE)
                textSize = 16f
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(accent)) }
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
        }
        cell.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            text = t.label
            setTextColor(Color.parseColor(body))
            textSize = 11.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        return cell
    }

    // --- data + launching ---

    /**
     * Launchable apps, sorted by label, minus the packages already surfaced as curated admin tiles
     * (the selected dashboard renderer, Settings, ourselves) so the grid doesn't duplicate them. The drawer itself has no
     * LAUNCHER filter, so it's absent regardless.
     */
    private fun installedApps(dashboardTarget: RendererTarget?): List<ResolveInfo> {
        val curated = setOfNotNull(
            packageName,
            "com.android.settings",
            (dashboardTarget as? RendererTarget.Foreign)?.packageName,
        )
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            packageManager.queryIntentActivities(main, 0)
                .filterNot { it.activityInfo.packageName in curated }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun launch(ri: ResolveInfo) {
        val ai = ri.activityInfo
        startSafely(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(ai.packageName, ai.name)
        })
    }

    private fun startSafely(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "launch failed: ${intent.component ?: intent.action}", it) }
    }

    private fun appIcon(pkg: String): android.graphics.drawable.Drawable? =
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    private fun dashboardTarget(): RendererTarget? {
        val config = Config(this)
        return RendererResolver.resolveLaunchable(
            configuredPackage = config.dashboardPackage,
            builtinReady = config.haUrl.isNotBlank(),
            isLaunchable = { packageManager.getLaunchIntentForPackage(it) != null },
        )
    }

    private fun text(
        s: String, size: Float, color: String,
        bold: Boolean = false, padTop: Int = 0, padBottom: Int = 0,
    ): TextView = TextView(this).apply {
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(padTop), 0, dp(padBottom))
        text = s
    }

    companion object {
        private const val TAG = "ha-paneld/admin-launcher"
    }
}
