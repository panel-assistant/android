package io.panelassistant.android

import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry

/**
 * The HTTP live-setting lane persists a value before the shared command path applies it, so that path's
 * own auto_sleep write then reads UNCHANGED and never refreshes the controller. This lane refreshes it.
 */
internal fun commitHttpLiveSetting(
    config: Config,
    spec: SettingSpec,
    value: String,
    refreshAutoSleep: () -> Unit,
): Boolean {
    val wasAutoSleep = config.autoSleep
    if (!config.commitRaw(spec, value)) return false
    if (spec.key == "auto_sleep" && config.autoSleep != wasAutoSleep) refreshAutoSleep()
    return true
}

/**
 * One live-setting value from the panel's own doors (the HTTP config API, the :8888 form, import and
 * restore, the kiosk escape gesture). [authority] journals it, so a value the draining dispatcher does not
 * admit is replayed against its replacement; [applyLiveSetting] then runs it through the dispatcher's
 * settings entry, in the one order Panel Assistant's commands share. Kiosk lock never enters the journal:
 * see [requestKioskSetting].
 */
internal fun requestLiveSetting(
    authority: LiveSettingAuthority,
    config: Config,
    commands: () -> PanelCommandDispatcher,
    key: String,
    value: String,
    unlockKiosk: () -> Boolean,
    refreshAutoSleep: () -> Unit,
): LiveSettingRequestOutcome {
    if (key == "kiosk_lock") return requestKioskSetting(config, commands, value, unlockKiosk)
    return authority.applyOrQueueOutcomeObserved(
        key = key,
        value = value,
        previousValue = SettingsRegistry.spec(key)?.let(config::getRaw),
    ) { appliedKey, appliedValue, previous ->
        applyLiveSetting(config, commands(), appliedKey, appliedValue, previous, refreshAutoSleep)
    }
}

/**
 * Kiosk lock from the panel's own doors, including the escape gesture, through the dispatcher's settings
 * entry but never the replay journal: a lock that cannot run now is refused rather than replayed later, so
 * no stale lock can come back. An unlock is stored before it queues (an older lock still engaging then
 * yields); when no worker takes it, [unlock] clears the lock here.
 */
internal fun requestKioskSetting(
    config: Config,
    commands: () -> PanelCommandDispatcher,
    value: String,
    unlock: () -> Boolean,
): LiveSettingRequestOutcome {
    val on = SettingValue.parseBool(value) ?: return LiveSettingRequestOutcome.REJECTED
    val application = runCatching { applyLiveSetting(config, commands(), "kiosk_lock", value, null) {} }.getOrNull()
    return when {
        application?.initial == LiveSettingApplyResult.APPLIED -> LiveSettingRequestOutcome.APPLIED
        // Queued behind a running command: it runs in order, and an unlock is already stored.
        application?.initial == LiveSettingApplyResult.DEFERRED && application.hasLateCompletion ->
            LiveSettingRequestOutcome.DEFERRED
        on -> LiveSettingRequestOutcome.REJECTED
        else -> {
            runCatching(unlock)
            if (config.kioskLock) LiveSettingRequestOutcome.REJECTED else LiveSettingRequestOutcome.APPLIED
        }
    }
}

internal fun applyLiveSetting(
    config: Config,
    commands: PanelCommandDispatcher,
    key: String,
    value: String,
    previousValue: String?,
    refreshAutoSleep: () -> Unit,
): LiveSettingApplication {
    val spec = SettingsRegistry.spec(key)
        ?: return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
    val previous = previousValue ?: config.getRaw(spec)
    // Network ADB must retain its durable ownership marker until the controller has cleared every
    // classic/TLS property and authoritatively read them all inactive. Persisting false here first
    // made AdbController.set(false) conclude that the listener was external and skip teardown.
    // voice_enabled must not be persisted ahead of handleVoiceEnabled's live hasMicrophone check —
    // this generic path runs before dispatchSetting, so pre-persisting ON here would durably record
    // the setting even when the bridge refuses to act on it for lack of a microphone. handleVoiceEnabled
    // owns persistence itself (mirrors network_adb, whose actuator is also the sole writer).
    // Kiosk ON is durable only once the lock engaged, so its handler persists it. An OFF is fail-safe and
    // stored before it queues, so an unlock asked for while the worker is busy survives a restart, and an ON
    // still locking cannot store over it. An OFF the store refuses still queues: its handler clears the lock
    // whether or not the write lands.
    val kioskOff = key == "kiosk_lock" && SettingValue.parseBool(value) == false
    val actuationOwnsPersistence = key == "navbar_mode" || key == "network_adb" || key == "voice_enabled" ||
        key == "kiosk_lock"
    if (kioskOff) {
        config.storeKioskOffAhead()
    } else if (!spec.transient && !actuationOwnsPersistence &&
        !commitHttpLiveSetting(config, spec, value, refreshAutoSleep)
    ) {
        return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
    }
    return commands.applySettingObserved(key, value, previous.takeUnless { spec.transient })
}

/** Kiosk OFFs stored ahead of their queued command in this process, under [Config.CONFIG_LOCK]. */
private var kioskOffsStoredAhead = 0L

/** Store a kiosk OFF before its command queues, counted so an older ON still locking cannot store over it. */
internal fun Config.storeKioskOffAhead(): Boolean = synchronized(Config.CONFIG_LOCK) {
    kioskOffsStoredAhead++
    commitKioskLock(false)
}

/** The count a kiosk ON reads before it starts locking. */
internal fun Config.kioskOffsStoredAhead(): Long = synchronized(Config.CONFIG_LOCK) { kioskOffsStoredAhead }

/** Store a kiosk ON only if no OFF was stored ahead since [count]: the newer OFF stays the stored truth. */
internal fun Config.commitKioskOnUnlessOffStoredSince(count: Long): Boolean = synchronized(Config.CONFIG_LOCK) {
    kioskOffsStoredAhead == count && commitKioskLock(true)
}

/**
 * The settings page's Network ADB ON, made exactly as Home Assistant's switch makes it: one live command on the
 * dispatcher, whose handler gives a held lease back and applies the ON in a single turn on the lease's queue, and
 * which is never journalled. APPLIED only when it took effect. Anything else is REJECTED and has changed
 * nothing: a refusal, a later command on the channel that replaced it before it ran, or a worker too busy to
 * start it within [startWaitMs], when it is withdrawn before it can run. Once started it is answered for what
 * it did.
 */
internal fun pressNetworkAdbOn(
    commands: PanelCommandDispatcher,
    origin: CommandOrigin,
    startWaitMs: Long = PAGE_NETWORK_ADB_ON_START_WAIT_MS,
): LiveSettingRequestOutcome {
    // 0 waiting for the worker, 1 started, 2 withdrawn
    val state = java.util.concurrent.atomic.AtomicInteger()
    val done = java.util.concurrent.CompletableFuture<io.panelassistant.android.panelassistant.PanelAssistantCommandResult>()
    commands.submit(
        io.panelassistant.android.panelassistant.PanelAssistantCommand(
            "network_adb",
            io.panelassistant.android.panelassistant.CommandValue.Switch(true),
            admit = {
                if (state.compareAndSet(0, 1)) null
                else io.panelassistant.android.panelassistant.PanelAssistantCommandResult.Failed(
                    io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor.CODE_FAILED,
                )
            },
        ),
        origin,
    ) { done.complete(it) }
    val result = try {
        done.get(startWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: java.util.concurrent.TimeoutException) {
        if (state.compareAndSet(0, 2)) return LiveSettingRequestOutcome.REJECTED
        try {
            done.get(PAGE_NETWORK_ADB_ON_RUNNING_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            // Still running past every budget it has: say it is on its way rather than that nothing happened.
            return LiveSettingRequestOutcome.DEFERRED
        }
    }
    return if (result == io.panelassistant.android.panelassistant.PanelAssistantCommandResult.Applied) {
        LiveSettingRequestOutcome.APPLIED
    } else {
        LiveSettingRequestOutcome.REJECTED
    }
}

/** How long the settings page's ON waits for the command worker to start it before withdrawing it. */
internal const val PAGE_NETWORK_ADB_ON_START_WAIT_MS = 10_000L
/** Once started, it ends within its own budgets: the lease queue's wait, the helper's exchange and the
 *  controller's settle deadline. */
private const val PAGE_NETWORK_ADB_ON_RUNNING_WAIT_MS = 60_000L
