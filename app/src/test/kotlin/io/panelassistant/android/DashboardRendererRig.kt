package io.panelassistant.android

import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.EntityLearningProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * A [DashboardActivity] on the plain JVM, for tests that drive its own renderer entry points (the
 * watchdog fire, the periodic check, a teardown) and observe what reaches the WebView, the cover and the
 * process-global renderer state.
 *
 * No Robolectric: the activity, its Handler and the cover's surface are allocated without constructors, and
 * only the collaborators those entry points use are filled in, from production classes. Framework calls
 * return mockable-jar defaults; Handler posts run only when a test opts into the virtual clock.
 */
internal class DashboardRendererRig(
    prefs: Map<String, Any?> = SIGNED_IN,
    /** Where the entity gate's hold screens run; a cancelled scope keeps them from painting a status screen. */
    entityGateScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    /** The activity's own scope; by default nothing launched on it ever runs. */
    activityScope: CoroutineScope = CoroutineScope(java.util.concurrent.Executor {}.asCoroutineDispatcher()),
) : AutoCloseable {
    val activity: DashboardActivity = allocate(DashboardActivity::class.java)
    private val main: Handler = allocate(Handler::class.java)
    private val gate = RendererGenerationGate()
    val owner = BuiltinDashboard.acquireActivityOwner()
    /** Clock ticks the status screens and the cover asked to have posted, in order. */
    val posted = mutableListOf<Runnable>()
    val reloadNotices = mutableListOf<String>()
    private val recordPost: (Runnable, Long) -> Unit = { tick, _ -> posted += tick }
    /** The activity's own cover, built by its factory. */
    val cover: DashboardWebCover by lazy { factory("createWebCover") as DashboardWebCover }
    val bridge = DashboardFrontendBridge(main, CoroutineScope(Job()), proxy(DashboardFrontendBridge.Host::class.java))
    /** The settings store behind [config], to read what a write left behind. */
    val store = fakePreferences(prefs)
    val config = Config(store.instance)
    private val entityGate = DashboardEntityGate(
        activity = allocate(AppCompatActivity::class.java),
        main = main,
        scope = entityGateScope,
        statusScreens = allocate(DashboardStatusScreens::class.java),
        host = object : DashboardEntityGate.Host {
            override val activityOwner get() = owner
            override val destroyed get() = false
            override val screenAwake get() = pacer.screenAwake
            override fun buildAndLoad(config: Config) = error("no rebuild expected")
            override fun teardownWeb() = error("no teardown expected")
            override fun reconcile() = error("no reconcile expected")
        },
        readConfig = { config },
    )

    init {
        set("main", main)
        set("activityScope", activityScope)
        set("rendererGate", gate)
        set("rendererGeneration", gate.open())
        set("embodiment", RendererEmbodiment())
        set("bridge", bridge)
        set("shownPage", ShownPageTracker())
        set("webCover\$delegate", lazyOf(cover))
        set("activityOwner", owner)
        set("activityConfig", config)
        set("entityGate", entityGate)
        BuiltinDashboard.consumeNavPath()
        BuiltinDashboard.consumeReloadRequest()
        BuiltinDashboard.consumeReloadReason()
        // The activity's own factory, so the pacer reads this activity back exactly as it does on a panel.
        set("pacer", DashboardActivity::class.java.getDeclaredMethod("createPacer").apply { isAccessible = true }.invoke(activity))
        set("admissionRetryPolicy", AdmissionRetryPolicy())
        // The activity's own status-screen factory, wired as on a panel but posting into [posted].
        set("statusScreens", factory("createStatusScreens"))
        val paintReload: (AppCompatActivity, android.view.View?, String) -> android.view.View = { _, _, reason ->
            reloadNotices += reason
            allocate(android.view.View::class.java)
        }
        DashboardStatusScreens::class.java.getDeclaredField("paintReloadCard").apply { isAccessible = true }
            .set(statusScreens, paintReload)
        set("homeDashboardAttempts", HomeDashboardResolutionAttemptGate())
        set("compatibilityAttempts", DashboardV2AttemptGate())
    }

    private fun factory(name: String): Any? = DashboardActivity::class.java.getDeclaredMethod(name, Function2::class.java)
        .apply { isAccessible = true }.invoke(activity, recordPost)

    /** The activity's status screens, as its own factory built them. */
    val statusScreens: DashboardStatusScreens get() = get("statusScreens") as DashboardStatusScreens

    /** Make [view] the activity's WebView, its listeners already installed (as after its first load). */
    fun show(view: RecordingWebView, committed: String? = view.url) {
        set("web", view)
        DashboardFrontendBridge::class.java.getDeclaredField("v2ListenerView").apply { isAccessible = true }.set(bridge, view)
        committed?.let { (get("shownPage") as ShownPageTracker).onCommitVisible(it) }
    }

    /** The state after admission loaded [home]: compatibility checked for this address, the home dashboard
     *  resolved for this credential and path, and the entity filter and theme baked as configured. */
    fun admitted(home: String = "lovelace") = runBlocking {
        set("compatibilityReadyUrl", HA)
        set("homeDashboardResolution", OwnedHomeDashboardResolution(
            HomeDashboardResolutionOwner(config.haAuthSnapshot().stableOwner(), config.homeDashboard.trim()),
            EntityLearningProtocol.HomeDashboardResolution(home, EntityLearningProtocol.HomeDashboardSource.EXPLICIT),
        ))
        set("dashboardThemeSignature", config.dashboardThemeEffective)
        entityGate.configureEntityFilter(config, entityGate.prepareEntityFilter(config))
    }

    /** Deliver a relaunch to the renderer's own intent handling, as `onNewIntent` does, and wait for it. */
    fun relaunch(intent: Intent?) = runBlocking { suspendCall("handleNewIntent", intent) }

    private suspend fun suspendCall(name: String, vararg args: Any?): Any? {
        val method = DashboardActivity::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn { continuation -> method.invoke(activity, *args, continuation) }
    }

    /** Stand the reconnecting or latch cover over the page, as showReconnecting or the latch page leaves it. */
    fun coverShown() {
        DashboardWebCover::class.java.getDeclaredField("cover").apply { isAccessible = true }
            .set(cover, allocate(StatusSurface::class.java))
    }

    val coverUp: Boolean get() = DashboardWebCover::class.java.getDeclaredField("cover").apply { isAccessible = true }.get(cover) != null

    var phase: RendererPhase
        get() = get("phase") as RendererPhase
        set(value) = set("phase", value)

    fun call(name: String, vararg args: Any?) {
        DashboardActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    }

    /** The activity's renderer pacer: its watchdog, dark settle, screen fan-out and memory ceiling. */
    val pacer: DashboardRendererPacer get() = get("pacer") as DashboardRendererPacer

    /** Call one of the pacer's own entry points, as the activity's Handler, screen listener or loads do. */
    fun pace(name: String, vararg args: Any?) {
        DashboardRendererPacer::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(pacer, *args)
    }

    fun setPaced(name: String, value: Any?) {
        DashboardRendererPacer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(pacer, value)
    }

    fun set(name: String, value: Any?) {
        DashboardActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    fun get(name: String): Any? =
        DashboardActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    override fun close() = BuiltinDashboard.releaseActivityOwner(owner)

    companion object {
        const val HA = "http://ha.local:8123"
        val SIGNED_IN: Map<String, Any?> = mapOf("panel_id" to "renderer_fixture", "ha_url" to HA, "ha_token" to "token")

        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
        }

        @Suppress("UNCHECKED_CAST")
        // The renderer and its route are current; the other host members answer nothing.
        private fun <T> proxy(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                else -> null
            }
        } as T
    }
}

/** A WebView that records what the renderer asks of it. */
internal class RecordingWebView(private val pageUrl: String?) : WebView(ContextWrapper(null)) {
    val calls = mutableListOf<String>()
    override fun getUrl(): String? = pageUrl
    override fun loadUrl(url: String) { calls += "loadUrl $url" }
    override fun reload() { calls += "reload" }
    override fun onPause() { calls += "onPause" }
    override fun onResume() { calls += "onResume" }
    override fun pauseTimers() { calls += "pauseTimers" }
    override fun resumeTimers() { calls += "resumeTimers" }
    override fun destroy() { calls += "destroy" }
    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) { calls += "evaluateJavascript" }
    override fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {
        calls += "loadData"
    }
}
