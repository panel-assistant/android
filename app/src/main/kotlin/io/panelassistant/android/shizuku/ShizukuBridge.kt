package io.panelassistant.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.platform.ShellPrivilege
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Official Shizuku client adapter. No Shizuku type escapes this package. */
object ShizukuBridge : ShellPrivilege {
    private const val TAG = "ha-paneld/shizuku"
    private const val REQUEST_CODE = 41_907
    private const val SHORT_TIMEOUT_MS = 10_000L
    private const val MAX_CLIENT_WORKERS = 2

    @Volatile var state: ShizukuState = ShizukuState.DISABLED
        private set
    @Volatile private var remote: IShizukuShellService? = null
    private val startup = ShizukuStartupGate()
    @Volatile private var bindingGeneration = 0L
    @Volatile private var activeConnection: ServiceConnection? = null
    private lateinit var appContext: Context
    private val executor = BoundedCallExecutor(MAX_CLIENT_WORKERS) { runnable ->
        Thread(runnable, "hapaneld-shizuku-client").apply { isDaemon = true }
    }
    private val reconnectScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hapaneld-shizuku-reconnect").apply { isDaemon = true }
    }
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val rejectedBindingDispatcher = ShizukuCallbackMutationDispatcher(
        postBarrier = callbackHandler::post,
        dispatchOffMain = { action -> runCatching { reconnectScheduler.execute(action) }.isSuccess },
        onOffMainRejected = { Log.w(TAG, "failed to dispatch rejected Shizuku binding off main") },
    )
    private val scheduler = ShizukuScheduler { delayMs, action ->
        val future = reconnectScheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        ShizukuScheduledHandle { future.cancel(false) }
    }
    private val reconnects = ShizukuReconnectCoordinator(scheduler)

    private val args: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuShellService::class.java))
            // Shizuku retains UserService processes by component + tag. A protocol-derived tag
            // forces a fresh bind after an AIDL change instead of reusing an older service whose
            // Binder transaction table cannot implement the new method.
            .tag(ShizukuPolicy.USER_SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("shell")
            .daemon(false)

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { clearBinding(managerIdleState()) }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        refresh()
    }

    /**
     * Install the Binder lifecycle listeners, and nothing else.
     *
     * This runs from `Application.onCreate`, inside the foreground-start deadline, so it must reach no
     * durable state: consent lives in `ha-paneld.db`, and opening that here is what killed Android 8.1
     * panels with `RemoteServiceException`. `addBinderReceivedListenerSticky` can call back
     * synchronously from this very frame when Shizuku's ContentProvider already holds the binder — that
     * callback is the reachable path, and [ShizukuStartupGate] is what stops it. [activateAfterPromotion]
     * re-derives the state once `PaneldService` has promoted.
     */
    @Synchronized fun initialize(context: Context) {
        if (!startup.admitRegistration()) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    /**
     * Open durable reads and derive the state the listeners were not allowed to derive.
     *
     * Called by `PaneldService.onCreate` after it has promoted, beside the other database-authoritative
     * corrections. The service's stand-down branches deliberately never reach it: a retired bridge, a
     * passive successor and the guard-database redirect run no controller that could use shell privilege.
     */
    fun activateAfterPromotion() {
        if (admitDurableState()) refresh()
    }

    @Synchronized private fun admitDurableState(): Boolean = startup.admitActivation()

    fun enable(context: Context) {
        initialize(context)
        admitDurableState()
        ShizukuConsent.enable(appContext)
        refresh(requestPermission = true)
    }

    fun disable() {
        if (!startup.registered) return
        ShizukuConsent.disable(appContext)
        clearBinding(managerIdleState())
    }

    fun refresh(requestPermission: Boolean = false) {
        if (!startup.activated) return
        val managerStatus = ShizukuManagerIdentity.status(appContext)
        if (managerStatus != ShizukuManagerIdentity.Status.TRUSTED) {
            clearBinding(if (managerStatus == ShizukuManagerIdentity.Status.UNTRUSTED) {
                ShizukuState.MANAGER_UNTRUSTED
            } else {
                ShizukuState.MANAGER_MISSING
            })
            return
        }
        if (!ShizukuConsent.enabled(appContext)) {
            clearBinding(ShizukuState.DISABLED)
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            clearBinding(ShizukuState.STOPPED)
            return
        }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        if (!granted) {
            val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true)
            clearBinding(
                if (rationale) ShizukuState.MANUAL_GRANT_REQUIRED else ShizukuState.PERMISSION_REQUIRED,
            )
            if (ShizukuPolicy.shouldRequestPermission(requestPermission, rationale)) {
                runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                    .onFailure { Log.w(TAG, "permission request failed", it) }
            }
            return
        }
        bind()
    }

    private fun bind() {
        val generation: Long
        val connection: ServiceConnection
        synchronized(this) {
            if (remote != null || activeConnection != null) return
            reconnects.cancel(resetBackoff = false)
            generation = ++bindingGeneration
            connection = connectionFor(generation)
            activeConnection = connection
            state = ShizukuState.BINDING
        }
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                Log.w(TAG, "bind failed", it)
                deferRejectedBinding(connection, generation, ShizukuState.ERROR)
            }
    }

    private fun connectionFor(generation: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val candidate = IShizukuShellService.Stub.asInterface(service)
            val cost = FeatureCosts.registry.span(FeatureCostOperation.SHIZUKU_BIND)
            val admitted = executor.execute {
                try {
                    val identityUsable = runCatching {
                        ShizukuPolicy.usable(candidate.identityUid(), candidate.protocolVersion())
                    }.getOrDefault(false)
                    val managerTrusted = ShizukuManagerIdentity.status(appContext) ==
                        ShizukuManagerIdentity.Status.TRUSTED
                    val accepted = synchronized(this@ShizukuBridge) {
                        ShizukuPolicy.canAcceptBinding(
                            callbackGeneration = generation,
                            currentGeneration = bindingGeneration,
                            connectionIsCurrent = activeConnection === this,
                            consentEnabled = ShizukuConsent.enabled(appContext),
                            managerTrusted = managerTrusted,
                            identityUsable = identityUsable,
                        ).also { allowed ->
                            if (allowed) {
                                remote = candidate
                                state = ShizukuState.READY
                                scheduleStableBackoffReset(generation)
                            }
                        }
                    }
                    if (!accepted) {
                        cost.outcome(FeatureCostOutcome.FAILURE)
                        deferRejectedBinding(
                            this,
                            generation,
                            if (identityUsable) managerIdleState() else ShizukuState.INCOMPATIBLE,
                        )
                    }
                } catch (error: Throwable) {
                    cost.outcome(FeatureCostOutcome.FAILURE)
                    Log.w(TAG, "binding identity check failed", error)
                    deferRejectedBinding(
                        this,
                        generation,
                        ShizukuState.ERROR,
                    )
                } finally {
                    cost.close()
                }
            }
            if (!admitted) {
                cost.outcome(FeatureCostOutcome.REJECTED).close()
                Log.w(TAG, "binding identity check rejected: client executor saturated")
                deferRejectedBinding(this, generation, ShizukuState.ERROR)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val nextState = disconnectedState()
            val reconnectGeneration = synchronized(this@ShizukuBridge) {
                if (generation != bindingGeneration || activeConnection !== this) return
                bindingGeneration++
                reconnects.cancelBackoffReset()
                activeConnection = null
                remote = null
                state = nextState
                bindingGeneration.takeIf { nextState == ShizukuState.ERROR }
            }
            if (reconnectGeneration != null) scheduleReconnect(reconnectGeneration)
        }
    }

    private fun clearBinding(nextState: ShizukuState) {
        val connection = synchronized(this) {
            bindingGeneration++
            remote = null
            state = nextState
            reconnects.cancelBackoffReset()
            reconnects.cancel(resetBackoff = true)
            activeConnection.also { activeConnection = null }
        }
        if (connection != null) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    private fun deferRejectedBinding(
        connection: ServiceConnection,
        generation: Long,
        nextState: ShizukuState,
    ) {
        // Shizuku 13.1.5 invokes this callback while its main-handler runnable iterates the cached
        // connection set. Posting to the same handler guarantees unbind runs on the next loop turn,
        // after that iteration has returned. The second stage keeps synchronous Binder removal off
        // main even when the identity worker finishes immediately.
        if (!rejectedBindingDispatcher.dispatch(
                Runnable { rejectBindingNow(connection, generation, nextState) },
            )) {
            Log.w(TAG, "failed to defer rejected Shizuku binding on the main looper")
        }
    }

    private fun rejectBindingNow(connection: ServiceConnection, generation: Long, nextState: ShizukuState) {
        var reconnectGeneration: Long? = null
        val removeService = synchronized(this) {
            when (ShizukuPolicy.rejectedBindingDisposition(
                callbackGeneration = generation,
                currentGeneration = bindingGeneration,
                connectionIsCurrent = activeConnection === connection,
                nextState = nextState,
            )) {
                ShizukuPolicy.RejectedBindingDisposition.IGNORE_STALE -> false
                ShizukuPolicy.RejectedBindingDisposition.REMOVE_CURRENT -> {
                    bindingGeneration++
                    activeConnection = null
                    remote = null
                    state = nextState
                    true
                }
                ShizukuPolicy.RejectedBindingDisposition.REMOVE_CURRENT_AND_RECONNECT -> {
                    bindingGeneration++
                    activeConnection = null
                    remote = null
                    state = nextState
                    reconnectGeneration = bindingGeneration
                    true
                }
            }
        }
        // Shizuku 13.1.5 aggregates connections by args tag. Even remove=false clears the tag-wide
        // connection set, so a stale callback must be ignored rather than "selectively" unbound.
        if (!removeService) return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        reconnectGeneration?.let(::scheduleReconnect)
    }

    /** Defer until after Shizuku has returned from its connection-set callback, with one bounded retry. */
    private fun scheduleReconnect(generation: Long) {
        val scheduled = synchronized(this) {
            if (generation != bindingGeneration || activeConnection != null || state != ShizukuState.ERROR) {
                return
            }
            reconnects.schedule(generation) {
                val reconnect = synchronized(this) {
                    generation == bindingGeneration && activeConnection == null &&
                        state == ShizukuState.ERROR
                }
                if (reconnect) refresh()
            }
        }
        if (!scheduled) Log.w(TAG, "Shizuku reconnect already pending or could not be scheduled")
    }

    private fun scheduleStableBackoffReset(generation: Long) {
        val scheduled = reconnects.scheduleBackoffReset(RECONNECT_STABLE_RESET_MS) {
            synchronized(this) {
                if (generation == bindingGeneration && state == ShizukuState.READY) {
                    reconnects.resetBackoff()
                }
            }
        }
        if (!scheduled) Log.w(TAG, "failed to schedule Shizuku reconnect backoff reset")
    }

    data class Snapshot(val state: ShizukuState, val ready: Boolean)

    @Synchronized fun snapshot(): Snapshot = Snapshot(state, remote != null && state == ShizukuState.READY)

    override fun available(): Boolean = snapshot().ready
    fun managerRunning(): Boolean = startup.activated &&
        ShizukuManagerIdentity.status(appContext) == ShizukuManagerIdentity.Status.TRUSTED &&
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    override fun uid(): Int? = call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.identityUid() }
    override fun screenshot(): ByteArray? {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.SHIZUKU_SCREENSHOT)
        val fd = call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.captureScreenshot() } ?: run {
            cost.outcome(FeatureCostOutcome.FAILURE).close()
            return null
        }
        val future = executor.submit {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > ShizukuPolicy.MAX_SCREENSHOT_BYTES) return@submit null
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray().takeUnless { it.isEmpty() }
                }
            }.getOrNull()
        } ?: run {
            runCatching { fd.close() }
            cost.outcome(FeatureCostOutcome.REJECTED).close()
            Log.w(TAG, "screenshot read rejected: client executor saturated")
            return null
        }
        return try {
            future.get(ShizukuPolicy.clientDeadline(SHORT_TIMEOUT_MS), TimeUnit.MILLISECONDS).also { bytes ->
                if (bytes == null) cost.outcome(FeatureCostOutcome.FAILURE)
                else cost.work(units = 1L, bytes = bytes.size.toLong())
            }
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            runCatching { fd.close() }
            future.cancel(true)
            Log.w(TAG, "screenshot read failed", e)
            null
        } finally {
            cost.close()
        }
    }

    override fun inputKey(keyCode: Int): Boolean =
        ShizukuPolicy.validKeyCode(keyCode) && call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.inputKey(keyCode) } == true
    override fun tap(x: Int, y: Int): Boolean =
        ShizukuPolicy.validCoordinate(x) && ShizukuPolicy.validCoordinate(y) &&
            call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.inputTap(x, y) } == true
    override fun density(): String? = call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.readDensity() }
    override fun setDensity(dpi: Int): Boolean =
        ShizukuPolicy.validDensity(dpi) && call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.setDensity(dpi) } == true
    override fun resetDensity(): Boolean = call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.resetDensity() } == true
    override fun fontScale(): String? = call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.readFontScale() }
    override fun setFontScale(scale: Float): Boolean =
        ShizukuPolicy.validFontScale(scale) && call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.setFontScale(scale) } == true
    override fun resetFontScale(): Boolean = call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.resetFontScale() } == true
    fun roomClimate(): String? =
        call(FeatureCostOperation.SHIZUKU_CALL, SHORT_TIMEOUT_MS) { it.readRoomClimate() }

    override fun installApk(apk: File, allowDowngrade: Boolean, timeoutMs: Long): String? {
        if (!ShizukuPolicy.validApkLength(apk.length())) return null
        val serviceDeadline = ShizukuPolicy.installServiceDeadline(timeoutMs) ?: return null
        return call(FeatureCostOperation.SHIZUKU_INSTALL, serviceDeadline, workBytes = apk.length()) { service ->
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                service.installApk(fd, apk.length(), allowDowngrade, serviceDeadline)
            }
        }
    }

    private fun managerIdleState(): ShizukuState {
        if (!startup.activated) return ShizukuState.DISABLED
        return ShizukuPolicy.idleState(
            manager = ShizukuManagerIdentity.status(appContext),
            consentEnabled = ShizukuConsent.enabled(appContext),
        )
    }

    private fun disconnectedState(): ShizukuState {
        if (!startup.activated) return ShizukuState.DISABLED
        val manager = ShizukuManagerIdentity.status(appContext)
        val consentEnabled = ShizukuConsent.enabled(appContext)
        val managerRunning = manager == ShizukuManagerIdentity.Status.TRUSTED && consentEnabled &&
            runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        return ShizukuPolicy.disconnectedState(manager, consentEnabled, managerRunning)
    }

    private const val RECONNECT_STABLE_RESET_MS = 30_000L

    private fun <T> call(
        operation: FeatureCostOperation,
        timeoutMs: Long,
        workBytes: Long = 0L,
        block: (IShizukuShellService) -> T,
    ): T? {
        val service = remote ?: run {
            FeatureCosts.registry.span(operation).work(bytes = workBytes)
                .outcome(FeatureCostOutcome.REJECTED).close()
            return null
        }
        val callGeneration = bindingGeneration
        // Measure inside the bounded worker. Caller-side timing mostly records a blocked Future wait
        // and cannot observe Binder worker CPU; worker ownership captures actual active time/CPU and
        // remains open until a timed-out Binder transaction really returns.
        val future = executor.submit {
            val cost = FeatureCosts.registry.span(operation).work(bytes = workBytes)
            try {
                block(service)
            } catch (error: Throwable) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                throw error
            } finally {
                cost.close()
            }
        } ?: run {
            FeatureCosts.registry.span(operation).work(bytes = workBytes)
                .outcome(FeatureCostOutcome.REJECTED).close()
            Log.w(TAG, "operation rejected: client executor saturated")
            return null
        }
        return try {
            val result = future.get(ShizukuPolicy.clientDeadline(timeoutMs), TimeUnit.MILLISECONDS)
            if (bindingGeneration == callGeneration && remote === service) result else null
        } catch (e: Exception) {
            future.cancel(true)
            Log.w(TAG, "operation failed", e)
            null
        }
    }
}
