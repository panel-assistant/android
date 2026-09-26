package io.github.maxlyth.hapaneld

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import java.util.Locale

/** One small advisory above either renderer; no focus, dashboard replacement, or background polling. */
internal class MigrationNotice(private val context: Context, private val config: Config) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val windows by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val catalogue = CatalogueLoader { context.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
    private var view: View? = null
    private var started = false
    private val guideQr by lazy { qrBitmap(URL, (56 * context.resources.displayMetrics.density).toInt()) }
    @Volatile private var closed = false
    private val redraw = Runnable { if (!closed) render() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in OBSERVED_KEYS) main.post(redraw)
    }

    /** Called by off-main service startup, like the navbar; never run a permission command on the UI. */
    fun start() {
        if (closed) return
        if (config.migrationNoticeVisible() && !Settings.canDrawOverlays(context)) {
            Su.run("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        }
        main.post {
            if (!closed && !started) {
                started = true
                config.registerChangeListener(listener)
                render()
            }
        }
    }

    private fun render() {
        remove()
        if (!config.migrationNoticeVisible()) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "notice requires overlay permission")
            return
        }
        val strings = catalogue.strings(AppLocale.resolve(
            explicit = null, persisted = config.uiLanguage, acceptLanguage = null,
            deviceLanguageTag = Locale.getDefault().toLanguageTag(), allowPseudo = BuildConfig.DEBUG,
        ))
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 36, 24))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.rgb(210, 149, 31))
            }
        }
        fun text(value: String, bold: Boolean = false) = TextView(context).apply {
            text = value
            setTextColor(Color.rgb(250, 242, 226))
            textSize = if (bold) 15f else 13f
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val message = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(text(strings.get("shell.migration.title"), bold = true).apply {
                contentDescription = "${strings.get("shell.migration.title")}. ${strings.get("shell.migration.body")}"
            })
            addView(text(URL.removePrefix("https://")))
        }
        card.addView(message, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
        guideQr?.let { qr ->
            card.addView(ImageView(context).apply {
                setImageBitmap(qr)
                contentDescription = URL
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) })
        }
        card.addView(Button(context).apply {
            text = "\u00d7"
            contentDescription = strings.get("shell.migration.dismiss")
            isAllCaps = false
            textSize = 24f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                if (config.dismissMigrationNotice()) remove()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val width = minOf(context.resources.displayMetrics.widthPixels - dp(24), dp(420)).coerceAtLeast(1)
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(12)
        }
        runCatching { windows.addView(card, params); view = card }
            .onFailure { Log.w(TAG, "notice could not be displayed", it) }
    }

    private fun remove() {
        view?.let { runCatching { windows.removeViewImmediate(it) }.onFailure { error -> Log.w(TAG, "notice removal failed", error) } }
        view = null
    }

    override fun close() {
        closed = true
        config.unregisterChangeListener(listener)
        main.removeCallbacks(redraw)
        if (Looper.myLooper() == main.looper) remove() else main.post { remove() }
    }

    companion object {
        const val URL = "https://panel-assistant.io/go/migration"
        private const val TAG = "ha-paneld/migration-notice"
        private val OBSERVED_KEYS = setOf(
            "panel_assistant_authority", "panel_assistant_update_owner_seen_ms",
            "migration_notice_connection_seen", "migration_notice_dismissed_version", "ui_language",
        )
    }
}
