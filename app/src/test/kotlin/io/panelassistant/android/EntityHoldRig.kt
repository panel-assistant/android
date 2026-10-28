package io.panelassistant.android

import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.AutoScopeVerification
import io.panelassistant.android.dashboard.EntityBootstrapProblem
import io.panelassistant.android.dashboard.RendererEntityHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * A [DashboardEntityGate] on the plain JVM with a virtual clock, for tests that drive an entity hold
 * from the screen being shown to its end and observe what the panel would do.
 *
 * The host does what [DashboardActivity.buildAndLoad] does with the gate: a renderer the gate still holds
 * goes back to the hold screen, and one it releases is admitted. No Robolectric: the activity, Handler and
 * status screens are allocated without constructors, the hold screen is recorded rather than painted, and
 * every timer the gate posts runs on [advance]'s clock.
 */
internal class EntityHoldRig(
    val config: Config,
    var screenAwake: Boolean = true,
    /** Where the virtual clock starts; a recreated activity shares its predecessor's. */
    startAt: Long = 1_000_000L,
) : AutoCloseable {
    val owner = BuiltinDashboard.acquireActivityOwner()
    private val work = Job()

    /** The learner's state, as the service-owned learner would report it. */
    var blockingIssues = 0
    var problem: EntityBootstrapProblem? = null
    var canIgnore = true
    var syncRunning = false
    val retries = mutableListOf<Long>()
    val probes = mutableListOf<Long>()

    /** Every hold screen painted, in order, and every renderer admitted, by its filter signature. */
    val shown = mutableListOf<EntityHoldKind>()
    val admitted = mutableListOf<String>()
    var reconciles = 0

    var now = startAt
        private set
    private val pending = mutableListOf<Pair<Long, Runnable>>()

    private val timers = object : EntityHoldTimers {
        override fun now(): Long = this@EntityHoldRig.now
        override fun post(task: Runnable, delayMs: Long) { synchronized(pending) { pending += (now + delayMs) to task } }
        override fun cancel(task: Runnable) { synchronized(pending) { pending.removeAll { it.second === task } } }
    }

    val gate: DashboardEntityGate = DashboardEntityGate(
        activity = allocate(AppCompatActivity::class.java),
        main = allocate(Handler::class.java),
        scope = CoroutineScope(Dispatchers.Unconfined + work),
        statusScreens = allocate(DashboardStatusScreens::class.java),
        host = object : DashboardEntityGate.Host {
            override val activityOwner get() = owner
            override val destroyed get() = false
            override val screenAwake get() = this@EntityHoldRig.screenAwake
            override fun buildAndLoad(config: Config) {
                if (gate.holdsRenderer(config)) {
                    gate.showWaitingForEntityBootstrap()
                } else {
                    admitted += gate.entityFilterSignature
                    gate.onRendererAdmitted()
                }
            }
            override fun teardownWeb() = Unit
            override fun reconcile() { reconciles++ }
        },
        readConfig = { config },
        timers = timers,
        learner = object : EntityHoldLearner {
            override fun blockingIssueCount() = blockingIssues
            override fun bootstrapProblem() = problem
            override fun canIgnoreBlockingIssues() = canIgnore
            override fun bootstrapMilestoneState() = null
            override fun syncRunning() = this@EntityHoldRig.syncRunning
            override fun retryBootstrap(): Boolean = true.also { retries += now }
            override fun probeDashboardChange(): Boolean = true.also { probes += now }
        },
        holdPainter = { kind, _, _ -> shown += kind; null },
    )

    /** The activity's first build, as onCreate runs it: prepare the allow-list, then build. */
    fun start() {
        runBlocking { gate.configureEntityFilter(config, gate.prepareEntityFilter(config)) }
        gate.holdsRenderer(config).let { held ->
            if (held) gate.showWaitingForEntityBootstrap() else { admitted += gate.entityFilterSignature; gate.onRendererAdmitted() }
        }
        settle()
    }

    /** Run every timer due within [ms], in due order, as the activity's Handler would. */
    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = synchronized(pending) {
                pending.filter { it.first <= until }.minByOrNull { it.first }?.also { pending.remove(it) }
            } ?: break
            now = maxOf(now, next.first)
            next.second.run()
            settle()
        }
        now = until
    }

    private fun settle() = runBlocking { work.children.forEach { it.join() } }

    override fun close() {
        gate.stopLease()
        work.cancel()
        RendererEntityHold.ended()
        AutoScopeVerification.invalidate()
        BuiltinDashboard.releaseActivityOwner(owner)
    }

    companion object {
        const val AUTO = ""

        /** A panel whose learner has not committed a first set: learning on, no filter. */
        fun freshLearner(homeDashboard: String = "lovelace"): Config = Config(fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to homeDashboard,
            "dashboard_entity_learning" to true,
        )).instance)

        /** A panel after an update: a learned allow-list on record, learning on. */
        fun learned(homeDashboard: String = AUTO): Config = Config(fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to homeDashboard,
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to if (homeDashboard == AUTO) "/" else "/$homeDashboard",
            "dashboard_entity_learning" to true,
        )).instance).apply {
            migrateDashboardEntityDefaultResolver()
            check(commitDashboardEntitySubscription(true, listOf("light.office"), applied = true))
            clearDashboardEntityDefaultResolverRebootstrapPending()
            check(dashboardEntityFilterEnabled) { "fixture precondition" }
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
        }
    }
}
