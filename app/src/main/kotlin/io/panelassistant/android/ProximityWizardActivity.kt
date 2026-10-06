package io.panelassistant.android

import android.graphics.Color
import android.content.Intent
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.FrameLayout
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ProximityWizardSpeech(
    val prompt: String,
    val textRes: Int,
    val formatArgs: List<Any> = emptyList(),
)

internal fun proximityWizardSpeech(
    stage: String,
    awaitingReading: Boolean,
    mode: String,
    waveCount: Int,
    capabilities: ProximityWizardCapabilities,
    acceptedGestures: Int,
    requiredGestures: Int,
): ProximityWizardSpeech? = when (stage) {
    "intro" -> when {
        awaitingReading -> ProximityWizardSpeech("intro|waiting", R.string.proximity_wizard_speech_waiting)
        mode == "binary" -> ProximityWizardSpeech("intro|binary", R.string.proximity_wizard_speech_intro_binary)
        else -> ProximityWizardSpeech("intro|ranged", R.string.proximity_wizard_speech_intro)
    }
    "clear" -> ProximityWizardSpeech("clear", R.string.proximity_wizard_speech_clear)
    "near" -> ProximityWizardSpeech("near", R.string.proximity_wizard_speech_near)
    "return_clear" -> ProximityWizardSpeech("return_clear", R.string.proximity_wizard_speech_return_clear)
    "wave_baseline" -> ProximityWizardSpeech("wave_baseline", R.string.proximity_wizard_speech_wave_baseline)
    "wave_capture" -> if (waveCount == 2) {
        ProximityWizardSpeech("wave_capture|double", R.string.proximity_wizard_speech_wave_double)
    } else {
        ProximityWizardSpeech("wave_capture|single", R.string.proximity_wizard_speech_wave_single)
    }
    "waves" -> if (acceptedGestures == 0) {
        ProximityWizardSpeech("waves|start", R.string.proximity_wizard_speech_waves_start)
    } else {
        ProximityWizardSpeech(
            "waves|$acceptedGestures|$requiredGestures",
            R.string.proximity_wizard_speech_wave_count,
            listOf(acceptedGestures, requiredGestures),
        )
    }
    "review" -> ProximityWizardSpeech(
        "review|${capabilities.name}",
        when (capabilities) {
            ProximityWizardCapabilities.BOTH -> R.string.proximity_wizard_speech_review_both
            ProximityWizardCapabilities.PRESENCE_ONLY -> R.string.proximity_wizard_speech_review_presence
            ProximityWizardCapabilities.WAVE_ONLY -> R.string.proximity_wizard_speech_review_wave
            ProximityWizardCapabilities.NEITHER -> R.string.proximity_wizard_speech_review_neither
        },
    )
    "saved" -> when (capabilities) {
        ProximityWizardCapabilities.BOTH -> ProximityWizardSpeech("saved|both", R.string.proximity_wizard_speech_saved_both)
        ProximityWizardCapabilities.PRESENCE_ONLY -> ProximityWizardSpeech("saved|presence", R.string.proximity_wizard_speech_saved_presence)
        ProximityWizardCapabilities.WAVE_ONLY -> ProximityWizardSpeech("saved|wave", R.string.proximity_wizard_speech_saved_wave)
        ProximityWizardCapabilities.NEITHER -> null
    }
    "cancelled" -> ProximityWizardSpeech("cancelled", R.string.proximity_wizard_speech_cancelled)
    "timed_out" -> ProximityWizardSpeech("timed_out", R.string.proximity_wizard_speech_timed_out)
    "failed" -> ProximityWizardSpeech("failed", R.string.proximity_wizard_speech_failed)
    else -> null
}

internal enum class ProximityWizardLayoutMode { COMPACT_SQUARE, PORTRAIT, LANDSCAPE }

internal data class ProximityWizardLayoutSpec(
    val mode: ProximityWizardLayoutMode,
    val rawValueSp: Float,
)

internal fun proximityWizardLayoutSpec(widthDp: Int, heightDp: Int): ProximityWizardLayoutSpec {
    val ratio = if (heightDp > 0) widthDp.toFloat() / heightDp else 1f
    return when {
        ratio >= 1.2f -> ProximityWizardLayoutSpec(ProximityWizardLayoutMode.LANDSCAPE, 96f)
        widthDp <= 520 && heightDp <= 520 ->
            ProximityWizardLayoutSpec(ProximityWizardLayoutMode.COMPACT_SQUARE, 84f)
        else -> ProximityWizardLayoutSpec(ProximityWizardLayoutMode.PORTRAIT, 96f)
    }
}

/** The physical calibration journey stays on the panel, independently of its dashboard renderer. */
class ProximityWizardActivity : AppCompatActivity() {
    private val maintenanceFence = GuardDbActivityMaintenanceFence()
    private val handler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionId: String? = null
    private var visible = false
    private var stage = ""
    private var lastPresentation = ""
    private lateinit var instruction: TextView
    private lateinit var detail: TextView
    private lateinit var rawLabel: TextView
    private lateinit var rawValue: TextView
    private lateinit var rawWaiting: TextView
    private lateinit var mode: TextView
    private lateinit var progress: TextView
    private lateinit var indicator: ProgressBar
    private lateinit var primary: Button
    private lateinit var cancel: Button
    private lateinit var pictogram: ProximityPictogramView
    private lateinit var cadence: TextView
    private lateinit var cadenceLabel: TextView
    private lateinit var visualRow: LinearLayout
    private lateinit var countdownRing: ProximityCountdownView
    private lateinit var rawNumberFormat: NumberFormat
    private lateinit var layoutSpec: ProximityWizardLayoutSpec
    private val backgroundColor = Color.rgb(14, 23, 37)
    private val bodyColor = Color.rgb(243, 247, 255)
    private val accentColor = Color.rgb(133, 186, 255)

    private val poll = object : Runnable {
        override fun run() {
            if (!visible) return
            refresh()
            if (visible) handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maintenanceFence.stop(this)) return
        sessionId = savedInstanceState?.getString(SESSION)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = getString(applicationInfo.labelRes)
            gravity = Gravity.CENTER
        })
        activityScope.launch {
            val language = readActivityStateOffMain { Config(applicationContext).uiLanguage }
            if (!isActive) return@launch
            NativeLocale.apply(language)
            supportActionBar?.hide()
            layoutSpec = proximityWizardLayoutSpec(
                resources.configuration.screenWidthDp,
                resources.configuration.screenHeightDp,
            )
            rawNumberFormat = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
                isGroupingUsed = false
                maximumFractionDigits = 4
            }
            buildUi()
            if (visible) startPresentation()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val landscape = layoutSpec.mode == ProximityWizardLayoutMode.LANDSCAPE
        val compactSquare = layoutSpec.mode == ProximityWizardLayoutMode.COMPACT_SQUARE
        val compact = compactSquare || resources.configuration.screenHeightDp < 520
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(if (compact) 16 else 28), dp(24), dp(20))
            setBackgroundColor(backgroundColor)
        }
        fun label(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            gravity = Gravity.START
            setTextColor(bodyColor)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(6))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        root.addView(label(20f, true).apply {
            setText(R.string.proximity_wizard_title)
            setTextColor(Color.rgb(161, 187, 220))
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(if (compact) 10 else 20) })
        instruction = label(if (compact) 34f else 40f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            letterSpacing = -0.025f
        }
        detail = label(26f)
        rawLabel = label(if (compactSquare) 18f else 22f, true).apply {
            setText(R.string.proximity_wizard_raw_label)
            setTextColor(Color.rgb(169, 190, 219))
            letterSpacing = 0.04f
        }
        rawValue = label(layoutSpec.rawValueSp, true).apply {
            setTextColor(accentColor)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = if (compactSquare) Gravity.START else Gravity.CENTER_HORIZONTAL
            maxLines = 1
        }
        rawWaiting = label(if (compactSquare) 22f else 26f).apply {
            setTextColor(accentColor)
            setText(R.string.proximity_wizard_raw_waiting)
            gravity = if (compactSquare) Gravity.START else Gravity.CENTER_HORIZONTAL
        }
        mode = label(24f).apply { setTextColor(Color.rgb(179, 200, 228)) }
        progress = label(24f, true).apply { setTextColor(accentColor) }
        indicator = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(accentColor)
            indeterminateTintList = ColorStateList.valueOf(accentColor)
        }
        pictogram = ProximityPictogramView(this)
        cadence = label(56f, true).apply { gravity = Gravity.CENTER }
        cadenceLabel = label(24f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(169, 190, 219))
        }
        countdownRing = ProximityCountdownView(this)
        val clock = FrameLayout(this).apply {
            addView(countdownRing, FrameLayout.LayoutParams(-1, -1))
            addView(cadence, FrameLayout.LayoutParams(-1, -1))
        }
        val clockScale = if (compactSquare) resources.configuration.fontScale.coerceAtLeast(1f) else 1f
        val clockSize = ((if (compact) 116 else 144) * clockScale).toInt()
        val cadenceColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(clock, LinearLayout.LayoutParams(dp(clockSize), dp(clockSize)))
            addView(cadenceLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        visualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(if (compact) 12 else 20), 0, dp(if (compact) 12 else 20))
            addView(pictogram, LinearLayout.LayoutParams(0, -1, 1.5f))
            addView(cadenceColumn, if (compactSquare) {
                LinearLayout.LayoutParams(dp(clockSize), -2)
            } else {
                LinearLayout.LayoutParams(0, -2, 1f)
            })
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(instruction)
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(mode)
            addView(progress)
        }
        val rawBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (compactSquare) Gravity.START else Gravity.CENTER_HORIZONTAL
            addView(rawLabel, LinearLayout.LayoutParams(-1, -2))
            addView(rawValue, LinearLayout.LayoutParams(-1, -2))
            addView(rawWaiting, LinearLayout.LayoutParams(-1, -2))
        }
        if (compactSquare) {
            content.addView(rawBlock, LinearLayout.LayoutParams(-1, -2))
            content.addView(copy)
            content.addView(visualRow, LinearLayout.LayoutParams(-1, -2))
        } else if (landscape) {
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(copy, LinearLayout.LayoutParams(0, -2, 1.1f))
                addView(visualRow, LinearLayout.LayoutParams(0, dp(216), 1f).apply { leftMargin = dp(20) })
            }
            content.addView(top)
            content.addView(rawBlock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        } else {
            content.addView(copy)
            content.addView(rawBlock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            content.addView(visualRow, LinearLayout.LayoutParams(-1, dp(if (compact) 188 else 248)))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(indicator, LinearLayout.LayoutParams(-1, dp(4)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        fun button(filled: Boolean) = Button(this).apply {
            isAllCaps = false
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = dp(60)
            minHeight = dp(60)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            stateListAnimator = null
            elevation = 0f
            val shape = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (filled) accentColor else Color.TRANSPARENT)
                setStroke(dp(1), if (filled) accentColor else Color.rgb(97, 120, 147))
            }
            backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 243, 247, 255)), shape, null)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.rgb(104, 124, 151), if (filled) Color.rgb(8, 33, 67) else bodyColor),
            ))
        }
        cancel = button(false).apply {
            setText(R.string.proximity_wizard_cancel)
            setOnClickListener { cancelAndFinish() }
        }
        primary = button(true).apply {
            setOnClickListener {
                when (stage) {
                    "intro" -> perform("begin")
                    "failed", "timed_out" -> perform("retry")
                    "review" -> perform("save")
                    else -> finish()
                }
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(primary, LinearLayout.LayoutParams(0, -2, 1.8f).apply { leftMargin = dp(12) })
        root.addView(actions)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (maintenanceFence.stop(this)) return
        visible = true
        if (::instruction.isInitialized) startPresentation()
    }

    private fun startPresentation() {
        pictogram.setPresenting(true)
        KioskAdminUi.setVisible(this, true)
        handler.post(poll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A later explicit launch can replace a completed screen, never an in-progress journey.
        if (::instruction.isInitialized && proximityWizardMayRebind(stage)) {
            sessionId = null
            lastPresentation = ""
            refresh()
        }
    }

    override fun onStop() {
        visible = false
        sessionId?.let(ProximityWizardHost::stopNarration)
        if (::pictogram.isInitialized) pictogram.setPresenting(false)
        handler.removeCallbacks(poll)
        // Rotation may reconnect to the same session. Leaving the wizard must not keep collecting.
        if (proximityWizardMustCancelOnStop(stage, isChangingConfigurations)) {
            sessionId?.let { ProximityWizardHost.action(it, "cancel") }
        }
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        activityScope.cancel()
        KioskAdminUi.setVisible(this, false)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SESSION, sessionId)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        val snapshot = ProximityWizardHost.status()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val currentId = snapshot?.optString("sessionId").orEmpty()
        if (currentId.isBlank() || (sessionId != null && sessionId != currentId)) {
            render(JSONObject().put("stage", "unavailable"))
            return
        }
        sessionId = currentId
        if (snapshot!!.optString("stage") !in TERMINAL) ProximityWizardHost.action(currentId, "visible")
        render(snapshot)
    }

    private fun render(snapshot: JSONObject) {
        stage = snapshot.optString("stage", "unavailable")
        val awaitingReading = stage == "intro" && snapshot.optString("health") != "healthy"
        val cue = ProximityWizardCue.fromWire(snapshot.optString("cue"))
        val usesHand = proximityWizardUsesHand(stage, cue)
        val waveCount = proximityWizardWaveCount(snapshot.optString("wavePattern"))
        val capabilities = proximityWizardCapabilities(
            snapshot.optBoolean("presenceSupported"), snapshot.optBoolean("waveSupported"),
        )
        val collecting = !proximityWizardHasLocalStepAction(stage) && stage != "saving"
        val (title, hint) = if (collecting && cue != ProximityWizardCue.NONE) when (cue) {
            ProximityWizardCue.PREPARE -> R.string.proximity_wizard_prepare to R.string.proximity_wizard_prepare_hint
            ProximityWizardCue.APPROACH -> if (stage == "wave_baseline") {
                R.string.proximity_wizard_normal_position to R.string.proximity_wizard_hands_down
            } else if (usesHand) {
                R.string.proximity_wizard_hand_near to R.string.proximity_wizard_hand_near_hint
            } else R.string.proximity_wizard_approach to R.string.proximity_wizard_approach_hint
            ProximityWizardCue.HOLD -> R.string.proximity_wizard_hold to if (stage == "wave_baseline") {
                R.string.proximity_wizard_hands_down
            } else R.string.proximity_wizard_hold_hint
            ProximityWizardCue.MOVE_AWAY -> if (usesHand) {
                R.string.proximity_wizard_clear to R.string.proximity_wizard_clear_hint
            } else R.string.proximity_wizard_step_away to R.string.proximity_wizard_step_away_hint
            ProximityWizardCue.WAIT_CLEAR -> R.string.proximity_wizard_stay_clear to R.string.proximity_wizard_stay_clear_hint
            ProximityWizardCue.WAVE -> if (waveCount == 2) {
                R.string.proximity_wizard_wave_twice to R.string.proximity_wizard_wave_twice_hint
            } else R.string.proximity_wizard_wave_now to R.string.proximity_wizard_wave_now_hint
            ProximityWizardCue.NONE -> R.string.proximity_wizard_prepare to R.string.proximity_wizard_prepare_hint
        } else when (stage) {
            "intro" -> if (awaitingReading) {
                R.string.proximity_wizard_waiting_reading to R.string.proximity_wizard_waiting_reading_hint
            } else R.string.proximity_wizard_intro to R.string.proximity_wizard_intro_hint
            "clear" -> R.string.proximity_wizard_clear to R.string.proximity_wizard_clear_hint
            "near" -> R.string.proximity_wizard_near to R.string.proximity_wizard_near_hint
            "return_clear" -> R.string.proximity_wizard_return_clear to R.string.proximity_wizard_return_clear_hint
            "waves" -> R.string.proximity_wizard_waves to R.string.proximity_wizard_waves_hint
            "review" -> R.string.proximity_wizard_review to when (capabilities) {
                ProximityWizardCapabilities.BOTH -> R.string.proximity_wizard_review_verified
                ProximityWizardCapabilities.PRESENCE_ONLY -> R.string.proximity_wizard_review_presence_only
                ProximityWizardCapabilities.WAVE_ONLY -> R.string.proximity_wizard_review_wave_only
                ProximityWizardCapabilities.NEITHER -> R.string.proximity_wizard_review_neither
            }
            "saving" -> R.string.proximity_wizard_saving to R.string.proximity_wizard_saving_hint
            "saved" -> R.string.proximity_wizard_saved to when (capabilities) {
                ProximityWizardCapabilities.BOTH -> R.string.proximity_wizard_saved_hint
                ProximityWizardCapabilities.PRESENCE_ONLY -> R.string.proximity_wizard_saved_presence_only
                ProximityWizardCapabilities.WAVE_ONLY -> R.string.proximity_wizard_saved_wave_only
                ProximityWizardCapabilities.NEITHER -> R.string.proximity_wizard_unchanged
            }
            "cancelled" -> R.string.proximity_wizard_cancelled to R.string.proximity_wizard_unchanged
            "timed_out" -> R.string.proximity_wizard_timed_out to R.string.proximity_wizard_unchanged
            "failed" -> R.string.proximity_wizard_failed to R.string.proximity_wizard_unchanged
            else -> R.string.proximity_wizard_unavailable to R.string.proximity_wizard_unavailable_hint
        }
        val failureDetail = snapshot.optString("message").takeIf {
            it.isNotBlank() && stage in setOf("failed", "cancelled", "timed_out")
        }
        // Do not re-announce unchanged instructions four times per second to accessibility services.
        val presentation = "$stage|$cue|$waveCount|$capabilities|${snapshot.optBoolean("canSave")}|${snapshot.optString("health")}|${snapshot.optString("message")}|${snapshot.optString("mode")}|${snapshot.optInt("acceptedGestures")}|${snapshot.optInt("requiredGestures", 3)}"
        if (presentation != lastPresentation) {
            lastPresentation = presentation
            instruction.setText(title)
            detail.text = failureDetail ?: getString(hint)
            detail.textLocale = if (failureDetail != null) java.util.Locale.ENGLISH else resources.configuration.locales[0]
            mode.setText(R.string.proximity_wizard_binary)
            mode.visibility = if (snapshot.optString("mode") == "binary" && !awaitingReading && stage == "intro") View.VISIBLE else View.GONE
            val required = snapshot.optInt("requiredGestures", 3).coerceIn(1, 20)
            val accepted = snapshot.optInt("acceptedGestures", 0).coerceIn(0, required)
            progress.text = getString(R.string.proximity_wizard_progress, accepted, required)
            progress.visibility = if (stage == "waves" || (stage == "review" && snapshot.optBoolean("waveSupported"))) View.VISIBLE else View.GONE
            detail.visibility = View.VISIBLE
            visualRow.visibility = if (collecting || stage == "intro") View.VISIBLE else View.GONE
            visualRow.getChildAt(1).visibility = if (stage == "intro" && !awaitingReading) View.GONE else View.VISIBLE
            pictogram.present(
                if (awaitingReading) ProximityWizardCue.WAVE else cue,
                usesHand || awaitingReading, bodyColor, accentColor,
                requestedWaveCount = waveCount,
                holdNearPanel = proximityWizardHoldsNearPanel(stage, snapshot.optString("wavePattern")),
            )
            pictogram.setPresenting(visible && visualRow.visibility == View.VISIBLE)
            indicator.visibility = if (stage == "saving") View.VISIBLE else View.GONE
            indicator.isIndeterminate = stage != "waves"
            indicator.max = required
            indicator.progress = accepted
            cancel.visibility = if (stage in TERMINAL) View.GONE else View.VISIBLE
            cancel.isEnabled = stage != "saving"
            primary.isEnabled = stage != "saving" && !awaitingReading &&
                (stage != "review" || (snapshot.optBoolean("canSave") && capabilities != ProximityWizardCapabilities.NEITHER))
            primary.visibility = if (!proximityWizardHasLocalStepAction(stage)) View.GONE else View.VISIBLE
            primary.setText(when (stage) {
                "intro" -> R.string.proximity_wizard_start
                "failed", "timed_out" -> R.string.proximity_wizard_retry
                "review" -> R.string.proximity_wizard_save
                else -> R.string.proximity_wizard_done
            })
            // Failed sessions offer a local exit as well as Retry.
            if (stage == "failed" || stage == "timed_out") cancel.visibility = View.VISIBLE
            sessionId?.let { currentId ->
                proximityWizardSpeech(
                    stage = stage,
                    awaitingReading = awaitingReading,
                    mode = snapshot.optString("mode"),
                    waveCount = waveCount,
                    capabilities = capabilities,
                    acceptedGestures = accepted,
                    requiredGestures = required,
                )?.let { speech ->
                    ProximityWizardHost.narrate(
                        currentId,
                        speech.prompt,
                        getString(speech.textRes, *speech.formatArgs.toTypedArray()),
                        resources.configuration.locales[0].toLanguageTag(),
                    )
                }
            }
        }
        val countdown = proximityWizardCountdownSeconds(
            snapshot.optLong("cueRemainingMs"), snapshot.optLong("cueDurationMs"),
        )
        countdownRing.present(
            snapshot.optLong("cueRemainingMs"), snapshot.optLong("cueDurationMs"),
            if (cue == ProximityWizardCue.WAVE) Color.rgb(200, 173, 255) else accentColor,
        )
        cadence.setTextColor(if (cue == ProximityWizardCue.WAVE) Color.rgb(227, 213, 255) else bodyColor)
        cadence.text = countdown?.toString() ?: getString(R.string.proximity_wizard_waiting_symbol)
        cadenceLabel.setText(when {
            countdown == null -> R.string.proximity_wizard_observing
            cue in setOf(ProximityWizardCue.HOLD, ProximityWizardCue.WAIT_CLEAR) -> R.string.proximity_wizard_capturing
            cue == ProximityWizardCue.WAVE && waveCount == 2 -> R.string.proximity_wizard_wave_twice
            cue == ProximityWizardCue.WAVE -> R.string.proximity_wizard_wave_window
            else -> R.string.proximity_wizard_preparing
        })
        val raw = if (snapshot.optString("health") == "healthy") {
            runCatching { snapshot.getDouble("raw") }.getOrNull()?.takeIf { it.isFinite() }
        } else null
        val showRaw = proximityWizardShowsRawValue(stage)
        rawLabel.visibility = if (showRaw) View.VISIBLE else View.GONE
        rawValue.text = raw?.let(rawNumberFormat::format).orEmpty()
        rawValue.visibility = if (showRaw && raw != null) View.VISIBLE else View.GONE
        rawWaiting.visibility = if (showRaw && raw == null) View.VISIBLE else View.GONE
    }

    private fun perform(action: String) {
        if (action == "retry") sessionId?.let { ProximityWizardHost.action(it, "visible") }
        val accepted = sessionId?.let { ProximityWizardHost.action(it, action) } == true
        refresh()
        if (!accepted && stage != "unavailable") detail.setText(R.string.proximity_wizard_action_failed)
    }

    private fun cancelAndFinish() {
        if (stage == "saving") return
        sessionId?.let { ProximityWizardHost.action(it, "cancel") }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stage == "saving") return
        sessionId?.let { ProximityWizardHost.action(it, "cancel") }
        super.onBackPressed()
    }

    companion object {
        private const val SESSION = "proximity_wizard_session"
        private val TERMINAL = setOf("saved", "cancelled", "timed_out", "failed", "unavailable")
    }
}

/** Raw readings are learning aids during setup, rather than a claim about saved calibration. */
internal fun proximityWizardShowsRawValue(stage: String): Boolean = stage in setOf(
    "intro", "clear", "near", "return_clear", "wave_baseline", "wave_capture", "waves", "review",
)
