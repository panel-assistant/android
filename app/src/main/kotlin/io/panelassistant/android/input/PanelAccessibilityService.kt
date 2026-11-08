package io.panelassistant.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.os.Bundle
import io.panelassistant.android.KioskAdminUi
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.panelassistant.android.Config
import io.panelassistant.android.PaneldService
import io.panelassistant.android.R
import io.panelassistant.android.setupEverCompleted
import io.panelassistant.android.builtInRendererReady
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.platform.dreamInFront
import io.panelassistant.android.platform.accessibilityScreenshotGranted
import io.panelassistant.android.platform.AndroidSystemEnv
import io.panelassistant.android.util.ConfirmedSelfInstaller
import io.panelassistant.android.util.GuardDbProcessAdmission
import io.panelassistant.android.persistence.AppState
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional hardware-button capture. Hardware/capacitive buttons that emit standard Android
 * KeyEvents are reported to HA via [ButtonBus] → Home Assistant `event.<panel>_button`. Enabled at
 * provisioning with `settings put secure enabled_accessibility_services <pkg>/<this>`.
 *
 * ⚠️ Per-hardware uncertain: panels whose buttons are GPIO-only (no KeyEvent) won't surface here
 * and would need a vendor GPIO HAL (load-if-present), not this service. Needs on-device validation.
 * Requires `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` in the a11y config XML.
 */
class PanelAccessibilityService : AccessibilityService() {
    // It also presses Install on Android's confirmation of this app's own update ([InstallConfirmation]),
    // so a rootless panel nobody stands at can take the update Panel Assistant sends it.

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return
        instance = this
        revivePaneldService()
        homeChooser.connected()
        if (rebindForWindowContent()) return
        // After an in-place update the chooser is already showing when Android binds the service again.
        scheduleHomeChooserAnswer()
    }

    /**
     * Read setup authority and configured HOME off the main thread before each answer. Settings may
     * still be migrating after an update; never use a stale first-read setup bit or confirm a selection
     * after the configured activity changed. An older in-flight read cannot replace a newer answer.
     */
    @Volatile private var setupEverCompleted: Boolean? = null
    @Volatile private var intendedChooserHome: ChooserHome? = null

    private var homeAnswerReadGeneration = 0L

    private fun answerWithConfiguredHome() {
        val generation = ++homeAnswerReadGeneration
        Thread {
            val done = runCatching {
                val config = Config(this)
                val homes = packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
                ).map { result ->
                    val info = result.activityInfo
                    ChooserHome(
                        io.panelassistant.android.platform.ActivityRef(info.packageName, info.name),
                        info.applicationInfo.loadLabel(packageManager).toString(), info.loadLabel(packageManager).toString(),
                    )
                }
                config.setupEverCompleted to configuredChooserHome(
                    packageName, config.launcherPackage, config.dashboardPackage, config.builtInRendererReady(), homes,
                )
            }
                .onFailure { Log.w(TAG, "could not read configured HOME and setup authority", it) }
                .getOrNull() ?: return@Thread
            mainHandler.post {
                if (instance !== this || generation != homeAnswerReadGeneration ||
                    !GuardDbProcessAdmission.ordinaryMutationsAllowed()) return@post
                setupEverCompleted = done.first
                intendedChooserHome = done.second
                Log.i(TAG, "HOME chooser answer: setup ever completed=${done.first}; intended=${done.second?.activity?.component}")
                runCatching { homeChooser.answer() }.onFailure { Log.w(TAG, "could not read the HOME chooser", it) }
            }
        }.start()
    }

    /**
     * Re-enable this service once per process when its connection predates window-content retrieval
     * ([HomeChooserPolicy.rebindEntries]), so the very update that adds it already answers the HOME
     * chooser. Needs WRITE_SECURE_SETTINGS (granted at install); without it nothing is written.
     */
    private fun rebindForWindowContent(): Boolean {
        if (rebindTried) return false
        rebindTried = true
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val enabled = Settings.Secure.getString(contentResolver, key)
        val others = HomeChooserPolicy.rebindEntries(
            enabled, packageName, javaClass.name, serviceInfo?.capabilities ?: return false,
        ) ?: return false
        Log.i(TAG, "re-enabling the accessibility service to gain window content")
        Thread {
            runCatching {
                Settings.Secure.putString(contentResolver, key, others)
                Thread.sleep(REBIND_GAP_MS)
            }
            runCatching { Settings.Secure.putString(contentResolver, key, enabled) }
                .onFailure { Log.w(TAG, "could not re-enable the accessibility service", it) }
        }.start()
        return true
    }

    /**
     * Bring [PaneldService] up when the process was revived only for this accessibility bind.
     *
     * A foreground-start timeout kills the process *and* drops the started-service record, so there is
     * no START_STICKY re-create waiting to happen. The accessibility service is bound by the system
     * independently of that record, so the process can come back for it alone — no `:8888`, no Home Assistant link and
     * no log shipping. Observed lasting over eight hours, until `MainActivity` was started by hand.
     * This is the route back.
     *
     * Three reasons this cannot start a second instance or fight a deliberate stop:
     * - `PaneldService.start` issues `startForegroundService`, and Android delivers that to the single
     *   existing instance when one is running; `onStartCommand`'s `started` guard makes the redelivery
     *   inert, so `:8888` is never bound twice.
     * - `onServiceConnected` fires on a bind, not on a timer, so there is no loop to run. A deliberate
     *   stop does not unbind accessibility and therefore does not come back through here.
     * - `PaneldService.start` itself refuses while an upgrade holds the service down, and while the
     *   bridge is retired or Guard DB maintenance is required.
     *
     * Never let this throw: `onServiceConnected` failing takes the accessibility bind down with it, and
     * on Android 12+ a foreground start from a background process can be refused outright.
     */
    private fun revivePaneldService() {
        runCatching { PaneldService.start(this) }
            .onFailure { Log.w(TAG, "could not revive PaneldService from the accessibility bind", it) }
    }

    /**
     * Answers Android's HOME chooser; see [HomeChooserAnswer].
     *
     * Always is tapped, not clicked: on Meta's Portal an `ACTION_CLICK` only recorded HOME as chosen once
     * (`mAlways=false`), while a tap on the button's centre, Panel Assistant's ADB method, makes it stick.
     */
    private val homeChooser: HomeChooserAnswer by lazy { HomeChooserAnswer(
        appName = getString(R.string.app_name),
        defaultHome = { AndroidSystemEnv(this).defaultHome() },
        setupEverCompleted = { setupEverCompleted },
        activeWindow = {
            rootInActiveWindow?.let { root ->
                if (root.packageName != "android") return@let ChooserWindow(root.packageName, null, null, null)
                var count = 0
                fun read(node: AccessibilityNodeInfo, depth: Int): ChooserNode {
                    check(depth < 64 && ++count <= 2000) { "HOME chooser tree exceeds inspection bound" }
                    val box = Rect().also(node::getBoundsInScreen)
                    return ChooserNode(
                        node.viewIdResourceName, node.text?.toString(), node.isEnabled,
                        box.takeUnless { it.isEmpty }?.let { it.centerX() to it.centerY() },
                        (0 until node.childCount).mapNotNull { node.getChild(it) }.map { read(it, depth + 1) },
                    )
                }
                readChooserWindow(root.packageName, root.windowId, read(root, 0))
            }
        },
        showChooser = { performGlobalAction(GLOBAL_ACTION_HOME) },
        dismiss = { performGlobalAction(GLOBAL_ACTION_BACK) },
        tap = { x, y -> tap(x, y) },
        answerAgainIn = {
            mainHandler.removeCallbacks(answerHomeChooser)
            mainHandler.postDelayed(answerHomeChooser, it)
        },
        log = { Log.i(TAG, it) },
        intendedHome = { intendedChooserHome },
    ) }
    private var lastWindowState: WindowState? = null
    private data class WindowState(val packageName: String?, val className: String?, val id: Int)

    override fun onDestroy() {
        if (instance === this) {
            AccessibilityKeyRouter.disconnected()
            cancelPortalRename()
            startupOverlayDismissal?.close()
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName == "android") {
            scheduleHomeChooserAnswer()
        }
        pressOwnUpdateInstall(event)
        if (event != null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastWindowState = WindowState(event.packageName?.toString(), event.className?.toString(), event.windowId)
            }
            if (GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
                startupOverlayDismissal?.onEvent(event.eventType, event.packageName, event.className, event.windowId)
                portalNameSync?.onEvent()
            }
        }
    }

    private fun overlayWindow(): PortalStartupOverlayDismissal.Window? = runCatching {
        val root = rootInActiveWindow ?: return@runCatching null
        PortalStartupOverlayDismissal.Window(
            root.windowId, root.packageName?.toString(),
            root.findAccessibilityNodeInfosByViewId(PortalStartupOverlayDismissal.HEADER_ID).map {
                it.text?.toString().takeIf { _ -> it.isVisibleToUser && it.isEnabled }
            },
            root.findAccessibilityNodeInfosByViewId(PortalStartupOverlayDismissal.SWITCH_ID).map {
                PortalStartupOverlayDismissal.Toggle(
                    it.isVisibleToUser, it.isEnabled,
                    it.isCheckable && it.className?.toString() == "android.widget.Switch", it.isChecked,
                )
            },
        )
    }.getOrNull()

    /** Read a fresh native tree for every operation; node handles never survive an event/timer turn. */
    private fun portalNameWindow(): Pair<PortalNameSync.Window, IdentityHashMap<PortalNameSync.Node, AccessibilityNodeInfo>>? {
        val root = rootInActiveWindow ?: return null
        val nodes = IdentityHashMap<PortalNameSync.Node, AccessibilityNodeInfo>()
        var count = 0
        fun read(node: AccessibilityNodeInfo, depth: Int): PortalNameSync.Node {
            check(depth < 64 && ++count <= 2000) { "Portal editor tree exceeds inspection bound" }
            val result = PortalNameSync.Node(
                node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(), node.text?.toString(),
                node.isEnabled, node.isVisibleToUser, node.isClickable,
                (0 until node.childCount).mapNotNull { node.getChild(it) }.map { read(it, depth + 1) },
            )
            nodes[result] = node
            return result
        }
        val state = lastWindowState
        return PortalNameSync.Window(
            root.windowId, root.packageName?.toString(),
            root.packageName?.toString() == packageName && state?.id == root.windowId &&
                state.className == "io.panelassistant.android.DashboardActivity",
            if (root.packageName?.toString() == PortalNameSync.SETTINGS) read(root, 0) else PortalNameSync.Node(),
        ) to nodes
    }

    private fun operatePortalName(expected: PortalNameSync.Window, action: PortalNameSync.Action, name: String): Boolean {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed() || instance !== this || !portalRenamePending ||
            portalRenameOwnerGeneration != portalRenameGeneration.get()) return false
        val (current, nodes) = portalNameWindow() ?: return false
        if (current != expected || current.packageName != PortalNameSync.SETTINGS) return false
        val target = current.target(action) ?: return false
        val node = nodes[target] ?: return false
        return when (action) {
            PortalNameSync.Action.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            PortalNameSync.Action.TEXT -> node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            })
            else -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    /** Put back the dashboard a failed Portal rename covered with Meta's editor. */
    private fun returnToDashboard() {
        runCatching {
            startActivity(Intent(this, io.panelassistant.android.DashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "Portal rename could not return to the dashboard", it) }
    }

    /** Press Install on Android's confirmation of this app's own update, and on nothing else. */
    private fun pressOwnUpdateInstall(event: AccessibilityEvent?) {
        val installer = ConfirmedSelfInstaller.awaitingConfirmationIn() ?: return
        if (event?.packageName?.toString() != installer) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        // The label Android's installer shows for this app, read the way the installer reads it.
        val label = runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrDefault("")
        val texts = runCatching { root.findAccessibilityNodeInfosByText(label).map { it.text } }.getOrDefault(emptyList())
        for (id in InstallConfirmation.buttonIds(installer)) {
            val button = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull() ?: continue
            if (!InstallConfirmation.shouldPress(installer, root.packageName?.toString(), button.viewIdResourceName, button.isEnabled, texts, label)) {
                Log.i(TAG, "not pressing $id in $installer: names this app=${texts.any { it?.toString()?.trim() == label }} enabled=${button.isEnabled}")
                continue
            }
            // Android keeps the button disabled for a moment after the window opens; a later event retries.
            if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                ConfirmedSelfInstaller.confirmationPressed(installer)
                Log.i(TAG, "pressed Install on the confirmation of this app's own update in $installer")
            }
            return
        }
    }

    /** Read the chooser once it has settled; a window that has gone by then is simply not answered. */
    private fun scheduleHomeChooserAnswer() {
        mainHandler.removeCallbacks(answerHomeChooser)
        mainHandler.postDelayed(answerHomeChooser, HOME_CHOOSER_SETTLE_MS)
    }

    private val answerHomeChooser: Runnable = Runnable {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return@Runnable
        answerWithConfiguredHome()
    }

    /**
     * One PNG of the default display through Android 11's accessibility capture, the route a panel
     * without root or the helper has. A capture that finishes after [timeoutMs] releases its buffer.
     * Android refuses captures closer together than about a third of a second; that is a null here,
     * and the panel page keeps showing its last good image.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun capturePng(timeoutMs: Long): ByteArray? {
        val result = AtomicReference<ScreenshotResult?>()
        val abandoned = AtomicBoolean(false)
        val done = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                result.set(screenshot)
                // Whichever side takes the result second finds null, so the buffer is closed exactly once.
                if (abandoned.get()) result.getAndSet(null)?.hardwareBuffer?.close()
                done.countDown()
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "accessibility screenshot failed: $errorCode")
                done.countDown()
            }
        })
        val finished = runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            abandoned.set(true)
            result.getAndSet(null)?.hardwareBuffer?.close()
            return null
        }
        val shot = result.get() ?: return null
        return shot.hardwareBuffer.use { buffer ->
            val hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace) ?: return@use null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            ByteArrayOutputStream().use { out ->
                software.compress(Bitmap.CompressFormat.PNG, 100, out)
                software.recycle()
                out.toByteArray()
            }
        }
    }

    override fun onInterrupt() { cancelPortalRename() }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            AccessibilityKeyRouter.disconnected()
            cancelPortalRename()
            startupOverlayDismissal?.close()
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return super.onKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.onKeyEvent(event)
        val name = KeyEvent.keyCodeToString(event.keyCode)
        Log.d(TAG, "key: $name device=${event.device?.name} scan=${event.scanCode} action=${event.action} repeat=${event.repeatCount}")
        val consumed = AccessibilityKeyRouter.dispatch(
            deviceName = event.device?.name,
            scanCode = event.scanCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            eventType = name,
        )
        return consumed || super.onKeyEvent(event)
    }

    companion object : AccessibilityActions {
        private const val TAG = "ha-paneld/buttons"

        // Set while the a11y service is connected; lets the command path perform global nav
        // actions (back/recents/home) with no root — performGlobalAction is an a11y capability.
        @Volatile
        private var instance: PanelAccessibilityService? = null
        private var startupOverlayDismissal: PortalStartupOverlayDismissal? = null

        /** Called first on the service's IO startup lane, before settings migrations and predecessor teardown. */
        internal fun preparePortalStartupOverlayDismissal(context: Context, profileId: String): PortalStartupOverlayDismissal? {
            if (!PortalStartupOverlayDismissal.supportsProfile(profileId)) return null
            val vendor = runCatching { context.packageManager.getApplicationInfo(PortalStartupOverlayDismissal.PACKAGE, 0) }
                .getOrNull() ?: return null
            if (vendor.flags and ApplicationInfo.FLAG_SYSTEM == 0) return null
            val label = runCatching { vendor.loadLabel(context.packageManager).toString() }.getOrNull() ?: return null
            val preferences = AppState.preferences(context, "controller-state")
            val key = PortalStartupOverlayDismissal.BOOT_KEY
            return PortalStartupOverlayDismissal(
                profileId = profileId,
                bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
                readBoot = { if (preferences.contains(key)) preferences.getInt(key, -1) else null },
                writeBoot = { preferences.edit().putInt(key, it).commit() && preferences.getInt(key, -1) == it },
                label = label,
                now = android.os.SystemClock::elapsedRealtime,
                activeWindow = { instance?.overlayWindow() },
                back = { runCatching {
                    instance?.takeIf { GuardDbProcessAdmission.ordinaryMutationsAllowed() }
                        ?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
                }.getOrDefault(false).also { Log.i(TAG, "Portal startup overlay request: Back=$it") } },
                post = { mainHandler.postDelayed(it, 100L) },
                cancel = { mainHandler.removeCallbacks(it) },
            )
        }

        /** Publish on main only after PaneldService owns the candidate and has checked its stop boundary. */
        internal fun installPortalStartupOverlayDismissal(
            dismissal: PortalStartupOverlayDismissal,
            post: (Runnable) -> Unit = { mainHandler.post(it) },
        ) {
            post(Runnable {
                startupOverlayDismissal?.close()
                startupOverlayDismissal = dismissal
                instance?.lastWindowState?.let {
                    dismissal.onEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, it.packageName, it.className, it.id)
                }
            })
        }

        private val portalRenameGeneration = AtomicLong()
        @Volatile private var portalRenamePending = false
        private val portalRenameOwner = Any()
        private var portalNameSync: PortalNameSync? = null
        private var portalRenameCallback: ((Boolean, String) -> Unit)? = null
        private var portalRenameOwnerGeneration = 0L

        override fun portalRenameActive(): Boolean = portalRenamePending

        override fun renamePortal(name: String, onFinished: (Boolean, String) -> Unit): Boolean {
            val service = instance
            if (service == null || !GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
                onFinished(false, "Portal rename requires connected accessibility")
                return false
            }
            val generation = portalRenameGeneration.incrementAndGet()
            // Publish ownership before posting: the kiosk must not reclaim the editor in the gap.
            portalRenamePending = true
            KioskAdminUi.setVisible(portalRenameOwner, true)
            val queued = mainHandler.post {
                if (generation != portalRenameGeneration.get()) {
                    onFinished(false, "Portal rename superseded or cancelled")
                    return@post
                }
                if (instance !== service || !GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
                    portalRenamePending = false
                    KioskAdminUi.setVisible(portalRenameOwner, false)
                    onFinished(false, "Portal accessibility disconnected before rename")
                    return@post
                }
                portalRenameCallback?.invoke(false, "Portal rename superseded")
                portalRenameCallback = onFinished
                portalRenameOwnerGeneration = generation
                if (portalNameSync?.active != true) {
                    portalNameSync = PortalNameSync(
                        now = android.os.SystemClock::elapsedRealtime,
                        window = { service.portalNameWindow()?.first },
                        launch = { runCatching {
                            check(portalRenamePending && portalRenameOwnerGeneration == portalRenameGeneration.get())
                            service.startActivity(Intent(PortalNameSync.ACTION).setPackage(PortalNameSync.SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            true
                        }.getOrDefault(false) },
                        act = service::operatePortalName,
                        post = { task, delay -> mainHandler.postDelayed(task, delay) },
                        cancel = { mainHandler.removeCallbacks(it) },
                        returnToDashboard = service::returnToDashboard,
                        finished = { success, reason ->
                            if (portalRenameOwnerGeneration == portalRenameGeneration.get()) {
                                portalRenamePending = false
                                KioskAdminUi.setVisible(portalRenameOwner, false)
                            }
                            val callback = portalRenameCallback
                            portalRenameCallback = null
                            Log.i(TAG, "Portal name sync: success=$success $reason")
                            callback?.invoke(success, reason)
                        },
                    )
                }
                runCatching { portalNameSync?.request(name) }.onFailure {
                    portalNameSync?.close()
                    Log.w(TAG, "Portal name sync failed", it)
                }
            }
            if (!queued) {
                if (generation == portalRenameGeneration.get()) {
                    portalRenamePending = false
                    KioskAdminUi.setVisible(portalRenameOwner, false)
                }
                onFinished(false, "Portal rename could not be scheduled")
            }
            return queued
        }

        override fun cancelPortalRename() {
            val cancelledGeneration = portalRenameGeneration.incrementAndGet()
            portalRenamePending = false
            KioskAdminUi.setVisible(portalRenameOwner, false)
            mainHandler.post {
                if (portalRenameOwnerGeneration < cancelledGeneration) {
                    portalNameSync?.close()
                    portalNameSync = null
                }
            }
        }

        override fun connected(): Boolean = instance != null
        override fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        override fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        /** See [dreamInFront]. Both settings keys are readable by apps (`@Readable` in AOSP). */
        override fun dreamShowing(): Boolean = runCatching {
            val service = instance ?: return false
            val active = service.rootInActiveWindow?.packageName?.toString()
            if (active == null || active == service.packageName) return false
            val screensavers = listOf("screensaver_components", "screensaver_default_component")
                .map { Settings.Secure.getString(service.contentResolver, it) }
            dreamInFront(active, screensavers, service.packageName).also {
                Log.i(TAG, "front window $active, screensavers $screensavers -> screensaver=$it")
            }
        }.getOrDefault(false)


        /**
         * Dispatch a short accessibility gesture and wait for its completion when called off-main, as
         * all production callers are. Completion matters to the navbar overlay: it stays non-touchable
         * until the injected tap has landed, avoiding a feedback loop into its own edge strip.
         */
        override fun tap(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build()
            val completed = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }
            val handler = Handler(Looper.getMainLooper())
            val accepted = runCatching { service.dispatchGesture(gesture, callback, handler) }
                .getOrDefault(false)
            if (!accepted) return false
            if (Looper.myLooper() == Looper.getMainLooper()) return true
            return runCatching { done.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed.get() }
                .getOrDefault(false)
        }

        override fun canScreenshot(): Boolean =
            accessibilityScreenshotGranted(Build.VERSION.SDK_INT, instance?.serviceInfo?.capabilities)

        override fun screenshot(timeoutMs: Long): ByteArray? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            if (Looper.myLooper() == Looper.getMainLooper()) return null
            val service = instance ?: return null
            return runCatching { service.capturePng(timeoutMs) }
                .onFailure { Log.w(TAG, "accessibility screenshot threw", it) }
                .getOrNull()
        }

        private const val GESTURE_TIMEOUT_MS = 1000L
        private const val HOME_CHOOSER_SETTLE_MS = 600L
        private const val REBIND_GAP_MS = 1500L
        @Volatile private var rebindTried = false
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
