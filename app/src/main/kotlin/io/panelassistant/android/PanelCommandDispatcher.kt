package io.panelassistant.android

import android.util.Log
import io.panelassistant.android.config.ChannelOption
import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.BootChimeController
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.DeviceCredentialRebootRefusalException
import io.panelassistant.android.control.LedCommandPolicy
import io.panelassistant.android.control.NavbarController
import io.panelassistant.android.control.NavbarModeApplyOutcome
import io.panelassistant.android.control.NavbarModeApplyStatus
import io.panelassistant.android.control.NavigateController
import io.panelassistant.android.control.PowerSafetyMutationPolicy
import io.panelassistant.android.control.RelayController
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.TouchSoundController
import io.panelassistant.android.control.VolumeController
import io.panelassistant.android.control.WatchdogController
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.LatestDispatcher
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate
import io.panelassistant.android.util.submit
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Who a command came from (protocol section 20.2). [approvalKey] is the approval principal: `panel_assistant`,
 * `user:<Home Assistant user id>` or `peer:<address>`, so an approval earned by one origin can never be
 * consumed by another. [peerAddress] is the bare address of a `peer` origin, for the loopback exemption.
 * Doors construct origins; a requester never supplies one.
 */
internal data class CommandOrigin private constructor(
    val approvalKey: String,
    val requestId: String? = null,
    val peerAddress: String? = null,
    /** The connection that carries this origin's requests, when a door has one per socket. */
    val connection: String? = null,
    /**
     * Request headers the door read from its own connection (the socket's `Host` and `Accept-Language`), which an
     * operation sees as its request's headers: never `args`, so no handler reads them as a body or parameters.
     */
    val headers: Map<String, String> = emptyMap(),
) {
    val isPanelAssistant: Boolean get() = approvalKey == PANEL_ASSISTANT
    val isUser: Boolean get() = approvalKey.startsWith(USER_PREFIX)

    /**
     * Who owns a subscription (protocol section 20.9 check 4): the connection when there is one, so two tabs
     * from one address hold, cap and close their own; the approval principal otherwise.
     */
    val subscriptionOwner: String get() = connection?.let { "$approvalKey#$it" } ?: approvalKey

    fun withRequestId(requestId: String?): CommandOrigin = copy(requestId = requestId)

    fun withConnection(connection: String): CommandOrigin = copy(connection = connection)

    fun withHeaders(headers: Map<String, String>): CommandOrigin = copy(headers = headers)

    companion object {
        const val PANEL_ASSISTANT = "panel_assistant"
        private const val USER_PREFIX = "user:"
        private const val PEER_PREFIX = "peer:"

        fun panelAssistant(requestId: String? = null) = CommandOrigin(PANEL_ASSISTANT, requestId)

        fun user(userId: String, requestId: String? = null): CommandOrigin {
            require(userId.isNotBlank()) { "a user origin needs the Home Assistant user id" }
            return CommandOrigin(USER_PREFIX + userId, requestId)
        }

        fun peer(address: String, requestId: String? = null): CommandOrigin {
            require(address.isNotBlank()) { "a peer origin needs the peer address" }
            return CommandOrigin(PEER_PREFIX + address, requestId, address)
        }
    }
}

/**
 * The panel's one ordered command authority for a network runtime generation, keyed by channel id.
 * Panel Assistant commands and managed and local settings writes all enter here,
 * run their handlers on one worker in admission order, and report through the [PanelStateCore].
 */
internal class PanelCommandDispatcher(
    private val config: Config,
    private val core: PanelStateCore,
    private val brightness: BrightnessController,
    private val screen: ScreenController,
    private val navigate: NavigateController,
    private val volume: VolumeController,
    private val system: SystemController,
    // Soft on-screen navbar overlay (select: Off / Always on / Swipe reveal).
    private val navbar: NavbarController,
    // App watchdog (switch): self-heals a dead/abandoned dashboard. Toggling restarts its poll loop.
    private val watchdog: WatchdogController,
    private val touchSound: TouchSoundController,
    private val bootChime: BootChimeController,
    private val zigbee: ZigbeeController,
    private val relay: RelayController,
    private val cpu: CpuController,
    private val adb: AdbController,
    private val hasProximity: Boolean,
    private val hasButtonBacklight: Boolean,
    /** The profile's key-backlight curve; the reported state stays the Home Assistant level. */
    private val buttonBacklightTransfer: io.panelassistant.android.hardware.TransferCurve =
        io.panelassistant.android.hardware.TransferCurve.Identity,
    // The profile-authoritative microphone capability, captured per runtime generation: a profile switch
    // already forces a fresh one. Gates the voice_enabled live-setting handler, which refuses ON without it.
    private val hasMicrophone: Boolean = false,
    // The panel's media player, or null when the profile declares no speaker.
    private val media: io.panelassistant.android.media.PanelMediaPlayer? = null,
    // Live profile/probe capability; null while enumeration is unsettled.
    private val hasCamera: () -> Boolean? = { false },
    // Optional service-owned adaptive-brightness engine.
    private val autoBright: AutoBrightnessController,
    private val onAutoBrightnessConfigChanged: () -> Unit = {},
    // A camera switch commanded remotely performs the same actuation the HTTP reconfigure path performs,
    // so both routes converge on one owner rather than two.
    private val onCameraEnabledChanged: () -> Unit = {},
    // Home-dashboard commands change the automatic entity-learning ownership target. Notify only after
    // the normalized path is durably visible so the manager can hide/rebuild the correct subscription.
    private val onDashboardTargetChanged: () -> Unit = {},
    // The service-owned kiosk controller. Every kiosk change and its start-up recovery runs on this
    // dispatcher's worker, whichever door it came through, so they apply in the order they were asked for.
    private val kioskApply: (Boolean) -> Boolean,
    // Android's Back through the panel's live input route (InteractiveController.back); false when none acted.
    private val goBack: () -> Boolean = { false },
    // Android's Recents through the same routes; false when none acted.
    private val goRecents: () -> Boolean = { false },
    private val kioskRecover: (legacyMayBeActive: Boolean) -> Boolean,
    // A successfully executed remote setting is newer truth than any HTTP generation which timed out
    // ahead of it, even though their dispatcher conflation keys intentionally differ.
    private val onExternalSettingApplied: (String) -> Boolean = { true },
    private val onZigbeeExplicitRetry: () -> Unit = {},
    // The root helper's command line, for the button backlight.
    private val helperSend: (String) -> String? = HelperClient::send,
    private val onAutoSleepConfigChanged: (Boolean) -> Unit = {},
    // The named operations the HTTP routes register (protocol section 20.3), served by [manage].
    manageTable: ManageTable = ManageTable(),
    manageFamilies: Map<String, ManageFamily> = ManageFamilies.DEFAULT,
    approvalPollMs: Long = 1_000,
) : LiveSettingHandlers, ManageDoor {
    enum class Kind { LATEST, ACTION }

    private val lifecycle = RetirableMutationGate()
    private val operations = ManageOperations(
        manageTable, { LocalApprovalBroker.instance }, manageFamilies, approvalPollMs = approvalPollMs,
    )

    /**
     * The one entry every door calls for a named read or command (protocol section 20.2): see
     * [ManageOperations.manage]. Channel commands keep [submit].
     */
    override fun manage(
        commandId: String,
        op: String,
        args: org.json.JSONObject,
        origin: CommandOrigin,
        blobs: ManageBlobs?,
        onPending: () -> Unit,
        done: (ManageReply) -> Unit,
    ) = operations.manage(commandId, op, args, origin, blobs, onPending, done)

    /** Follow a subscription family (protocol section 20.4); see [ManageOperations.subscribe]. */
    override fun subscribe(family: String, origin: CommandOrigin, args: org.json.JSONObject, sink: ManageSink): ManageSubscription? =
        operations.subscribe(family, origin, args, sink)

    /** End every subscription [origin] holds, as its door does when the connection closes. */
    override fun onStopped(action: () -> Unit): AutoCloseable = operations.onStopped(action)

    override fun closeOrigin(origin: CommandOrigin) = operations.closeOrigin(origin)
    private val queue = CommandQueue(
        onQueueWait = { waitNanos ->
            FeatureCosts.registry.recordSynchronousElapsed(FeatureCostOperation.COMMAND_QUEUE_WAIT, waitNanos)
        },
    )
    // Relay/button-LED read-backs leave the command worker and coalesce latest-generation-wins, so a
    // burst costs one privileged read, and a late read-back can never report behind a newer command:
    // the generation predicate travels into the converger and is re-evaluated under its admission
    // lock, so an observation overtaken mid-flight is withdrawn instead of reported.
    // The scheduler is the RAW convergence pump, deliberately not a lifecycle-wrapped one: the wrapper
    // DROPS tasks after retirement, which would strand the gate's open feature-cost span. Every task
    // runs; cancellation lives inside reconcile, whose own gate no-ops after close().
    private val relayReadbackGate = CommandReadbackGate(
        schedule = { task -> StateConverger.dispatch(task) },
        readback = { key, stillCurrent -> core.reconcile(key, admit = stillCurrent) },
    )
    private val zigbeeActuation = ZigbeeActuationCoordinators.forController(zigbee)
    private val zigbeeLease = zigbeeActuation.activate()
    private val zigbeeWorker = LatestDispatcher.singleSlot<Boolean>("zigbee-state", ::reconcileZigbeeDesired)

    init {
        // A Zigbee gateway first seen live by a local pass gets the boot reconcile a connect once gave it.
        core.onCapabilitiesObserved = { observation ->
            if (!observation.previousLive.zigbee && observation.live.zigbee) reconcileZigbeeBoot()
        }
    }

    /**
     * The registry key whose newer external intent became durable during the dispatch running on this
     * thread, set by a handler that persists before it actuates.
     *
     * Thread-scoped rather than a plain field because HTTP live settings reach the same handlers through
     * the same queue: a shared field could be set by one path and read by another. [consume] clears it
     * on entry and exit, so a value can only ever describe the dispatch that is running.
     */
    private val externalIntentCommitted = ThreadLocal<String?>()

    /** The origin of the dispatch running on this thread; its approval key is the approval principal. */
    private val currentOrigin = ThreadLocal<CommandOrigin?>()

    /** Whether [channel] takes commands here, and how they queue: state conflates by channel, actions never. */
    fun kind(channel: String): Kind? = when {
        channel in STATE_CHANNELS -> Kind.LATEST
        channel in ACTION_CHANNELS -> Kind.ACTION
        indexedChannel(channel, "relay") -> Kind.LATEST
        indexedChannel(channel, "button_led") -> Kind.LATEST
        else -> null
    }

    private fun indexedChannel(channel: String, prefix: String): Boolean =
        channel.startsWith(prefix) && channel.substring(prefix.length).toIntOrNull() in 1..MAX_DYNAMIC_COMMAND_INDEX

    /**
     * Run one channel command from [origin]. Stateful commands conflate by channel; reload and reboot use the
     * action queue. [done] receives exactly one result. Approvals a sensitive command needs bind to
     * [origin]'s approval key.
     */
    override fun submit(
        command: PanelAssistantCommand,
        origin: CommandOrigin,
        done: (PanelAssistantCommandResult) -> Unit,
    ) {
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { result: PanelAssistantCommandResult -> if (answered.compareAndSet(false, true)) done(result) }
        if (!lifecycle.isOpen()) {
            finish(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED))
            return
        }
        if (command.channel == PanelStateCore.MEDIA_CHANNEL) return submitMediaCommand(command, finish)
        when (kind(command.channel)) {
            null -> return finish(PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_CHANNEL))
            Kind.ACTION, Kind.LATEST -> Unit
        }
        val run = {
            val refusal = command.admit()
            if (refusal != null) {
                finish(refusal)
            } else if (command.channel == "reboot" && !system.canReboot() ||
                command.channel == "reload" && !system.canReloadDashboard(config.dashboardPackage)
            ) {
                finish(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_HARDWARE_UNAVAILABLE))
            } else {
                finish(panelAssistantCommandResult(consume(command.channel, command.value, origin.withRequestId(command.requestId ?: origin.requestId))))
            }
        }
        val onSkipped = { execution: CommandQueue.Execution ->
            finish(if (execution == CommandQueue.Execution.SUPERSEDED) {
                PanelAssistantCommandResult.Superseded
            } else {
                PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED)
            })
        }
        val admission = if (command.channel in ACTION_CHANNELS) {
            queue.submitAction(onSkipped, run)
        } else {
            queue.submitLatest(key = command.channel, onSkipped = onSkipped, command = run)
        }
        recordCommandAdmission(admission)
    }

    /**
     * The native-only `media` channel. Media commands run in order on the action queue rather than
     * conflating by channel: a mute must not replace a play still waiting to run.
     */
    private fun submitMediaCommand(command: PanelAssistantCommand, finish: (PanelAssistantCommandResult) -> Unit) {
        val player = media
            ?: return finish(PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_CHANNEL))
        val parsed = (command.value as? CommandValue.Media)?.command
            ?: return finish(PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE))
        val admission = queue.submitAction(
            onSkipped = { finish(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED)) },
        ) {
            finish(
                command.admit() ?: if (player.command(parsed)) PanelAssistantCommandResult.Applied
                else PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED),
            )
        }
        recordCommandAdmission(admission)
    }

    private fun recordCommandAdmission(admission: CommandQueue.Admission) {
        when (admission) {
            CommandQueue.Admission.ACCEPTED -> Unit
            CommandQueue.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.COMMAND_DISPATCH)
            CommandQueue.Admission.REJECTED,
            CommandQueue.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.COMMAND_DISPATCH)
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.COMMAND_DISPATCH,
            queue.pendingCount(),
        )
    }

    /** Returns the failure the command handler raised, already logged, or null when it applied. */
    private fun consume(channel: String, value: CommandValue, origin: CommandOrigin): Exception? {
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.COMMAND_DISPATCH,
            queue.pendingCount(),
        )
        val cost = FeatureCosts.registry.span(FeatureCostOperation.COMMAND_DISPATCH)
            .work(units = 1)
        externalIntentCommitted.remove()
        currentOrigin.set(origin)
        try {
            dispatch(channel, value)
            externalLiveSettingKey(channel)?.let { key ->
                check(onExternalSettingApplied(key)) { "failed to supersede pending HTTP setting $key" }
            }
            return null
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "command ${origin.requestId.orEmpty()} from ${origin.approvalKey} failed on $channel", e)
            // The handler may have made newer intent durable before failing to actuate it. Retire the
            // older queued HTTP value anyway — leaving it lets startup replay commit the stale value
            // over the newer one. Best-effort and logged: this is already the failure path, and a
            // second throw here would only replace one diagnosis with another.
            supersededKeyAfterFailedDispatch(externalLiveSettingKey(channel), externalIntentCommitted.get())
                ?.let { key ->
                    if (!onExternalSettingApplied(key)) {
                        Log.w(TAG, "failed to supersede pending HTTP setting $key after a failed command")
                    }
                }
            return e
        } finally {
            currentOrigin.remove()
            externalIntentCommitted.remove()
            cost.close()
        }
    }

    private fun dispatch(channel: String, value: CommandValue) {
        // Relay + button-LED channels are dynamic (relay1/…, button_led1/…) — match before the fixed set.
        if (channel.startsWith("relay")) {
            handleRelay(channel, value.expect<CommandValue.Switch>(channel).on); return
        }
        if (channel.startsWith("button_led")) {
            handleButtonLed(channel, value.expect<CommandValue.Switch>(channel).on); return
        }
        // Every registry-declared live setting, whether it arrived remotely or over HTTP, reaches the same
        // exhaustive owner mapping. Action commands and non-live camera control remain below.
        externalLiveSettingKey(channel)?.let { key ->
            dispatchLiveSetting(key = key, value = value, handlers = this)
            return
        }
        when (channel) {
            "screen" -> handleScreen(value.expect(channel))
            "led" -> handleLed(value.expect(channel))
            "navigate" -> handleNavigate(value.expect<CommandValue.Text>(channel).text)
            "volume" -> handleVolume(value.expect<CommandValue.Quantity>(channel).value.toInt())
            "reload" -> {
                authorizeRemoteSensitive(
                    SensitiveOperation.DASHBOARD_RELOAD,
                    PRESS_BINDING,
                    "Reload the dashboard renderer from Home Assistant",
                )
                system.endDream()
                if (!system.reloadDashboard(config.dashboardPackage)) throw LiveSettingUnavailableException("reload")
            }
            // Kiosk lock keeps the panel on its dashboard, so neither leaves it while the lock is on.
            "android_settings" -> if (config.kioskLock || !system.launchAndroidSettings()) throw LiveSettingUnavailableException(channel)
            "launcher" -> if (config.kioskLock || !system.launchLauncher(config.launcherPackage)) throw LiveSettingUnavailableException(channel)
            "recents" -> if (config.kioskLock || !goRecents()) throw LiveSettingUnavailableException(channel)
            // Both keep the panel on its dashboard, so kiosk lock allows them.
            "dashboard" -> { system.endDream(); system.launchHome(config.dashboardPackage) }
            // The dashboard swallows Android's Back, so in front it goes to its own previous page or says it has none.
            "back" -> if (!((if (BuiltinDashboard.foreground) BuiltinDashboard.back() else null) ?: goBack())) throw LiveSettingUnavailableException(channel)
            "reboot" -> {
                system.requireRebootAllowed()
                authorizeRemoteSensitive(
                    SensitiveOperation.DEVICE_REBOOT,
                    PRESS_BINDING,
                    "Reboot this panel from Home Assistant",
                )
                if (!system.reboot()) throw LiveSettingUnavailableException("reboot")
            }
            "buttons" -> if (hasButtonBacklight) handleButtons(value.expect(channel))
            "camera_enabled" -> handleCameraEnabled(value.expect<CommandValue.Switch>(channel).on)
            "screen_off_timeout" -> handleScreenOffTimeout(value.expect<CommandValue.Quantity>(channel).value.toInt())
            else -> Log.d(TAG, "unhandled command channel $channel")
        }
    }

    private fun handleScreen(light: CommandValue.Light) {
        val command = io.panelassistant.android.control.ScreenCommandPolicy.parse(light)
        if (!command.on) {
            screen.sleep()
            core.publishScreenOff()
            return
        }
        // Capture explicit intent before wake completion can reapply automatic control.
        command.brightness?.let {
            autoBright.noteExternalBrightness(it, io.panelassistant.android.control.BrightnessPreferenceOrigin.HOME_ASSISTANT)
        }
        // A bare ON on a lit panel changes nothing: ensureOn skips the wake path, whose brightness
        // fallback would otherwise restore a stale saved level on the brightness-zero route.
        val delivered = io.panelassistant.android.control.ScreenCommandPolicy.executeOn(
            command,
            ensureOn = screen::ensureOn,
            setBrightness = brightness::trySetBrightness,
            commandedLevel = brightness::getCommanded,
            noteLevel = screen::noteLevel,
            publish = core::publishScreenBrightness,
        )
        check(delivered) { "screen actuation failed; requested brightness not published" }
    }

    private fun handleLed(light: CommandValue.Light) {
        val desired = LedCommandPolicy.command(
            light,
            LedCommandPolicy.stored(config.lastLed, config.lastLedEffect),
        )
        // Persist user intent even when hardware is temporarily unreachable so reconnect can retry it,
        // but clear observation authority before exposing the new desired state.
        val accepted = core.actuateLed {
            config.lastLed = desired.storedColor()
            config.lastLedEffect = desired.storedEffect()
            desired
        }
        if (!accepted) throw LiveSettingUnavailableException("led")
    }

    override fun handleWakeOnWave(on: Boolean) {
        if (!hasProximity) {
            Log.w(TAG, "ignoring wake_on_wave command without a proximity source")
            throw LiveSettingUnavailableException("wake_on_wave")
        }
        config.setWakeOnWave(on)
        core.reconcile("wake_on_wave")
    }

    override fun handleAutoSleep(on: Boolean) {
        applyAutoSleepSetting(on, config::setAutoSleep, onAutoSleepConfigChanged)
        core.reconcile("auto_sleep")
    }

    override fun handleAutoSleepSource(source: String) {
        config.setRaw(requireNotNull(SettingsRegistry.spec("auto_sleep_source")), source)
        onAutoSleepConfigChanged(config.autoSleep)
        core.reconcile("auto_sleep")
    }

    override fun handlePreventIdleDim(on: Boolean, approvalRequired: Boolean) {
        if (approvalRequired && PowerSafetyMutationPolicy.requestsSafetyReduction(
                keepAwake = config.keepAwake,
                requestedKeepAwake = null,
                preventIdleDim = config.preventIdleDim,
                requestedPreventIdleDim = on,
            )
        ) {
            authorizeRemoteSensitive(
                SensitiveOperation.POWER_CONFIGURATION,
                "prevent_idle_dim\u0000${if (on) "on" else "off"}",
                "Disable the panel's native screen-timeout guard from Home Assistant",
            )
        }
        check(config.commitRaw(config.specOf("prevent_idle_dim"), on.toString())) {
            "prevent_idle_dim intent could not be persisted"
        }
        // Durable from here: a failed actuation below must still retire an older queued HTTP value (as touch_sound).
        externalIntentCommitted.set("prevent_idle_dim")
        val timeout = brightness.applyPreventIdleDim(on, config)
        core.reconcile("prevent_idle_dim")
        core.reconcile("screen_off_timeout")
        if (!timeout.effective) {
            // observedMs < 0: WRITE_SETTINGS denied or the setting is unreadable, so there is no path.
            if (timeout.observedMs < 0) throw LiveSettingUnavailableException("prevent_idle_dim")
            error("screen timeout is ${timeout.observedMs}, not ${timeout.targetMs}")
        }
    }

    override fun handleTouchSound(on: Boolean) {
        // Persist the intent before actuating it, like every other live setting. The state channel now
        // reports the persisted value, so a change that only reached the controller would publish the old
        // value straight back and flip the switch in Home Assistant. The HTTP path has already committed
        // the same value through applyLiveSettingObserved, which makes this idempotent rather than
        // redundant: a command from Home Assistant reaches this handler without it.
        check(config.commitTouchSound(on)) { "touch sound intent could not be persisted" }
        // Durable from here, so a failed actuation below must not leave an older queued HTTP value alive
        // to be replayed over this one. The actuation throws past consumeCommand's ordinary supersession.
        externalIntentCommitted.set("touch_sound")
        requireControlApplied("touch_sound", touchSound.apply(on)) { "touch sound transition failed" }
        core.reconcile("touch_sound")
    }

    override fun handleWatchdog(on: Boolean) {
        check(config.commitWatchdogEnabled(on)) { "watchdog intent could not be persisted" }
        watchdog.apply(on)
        core.reconcile("watchdog")
    }

    // The voice assistant is a Home Assistant satellite through Panel Assistant, never a separate entity:
    // this handler is only the live-setting path the panel's own Configure page uses, and an ON on a
    // runtime generation without a microphone is refused.
    //
    // A refusal or a failed durable commit THROWS rather than returning silently. dispatchSetting runs
    // inside the HTTP live-setting path's command-dispatcher lane, whose exception handling is what
    // converts a thrown failure into LiveSettingApplyResult.FAILED — a silent return here previously let
    // both cases report execution SUCCEEDED, so a refused or unpersisted ON came back to the caller as
    // APPLIED. The decision itself is the pure, directly-testable applyAcknowledgedVoiceEnabled (mirrors
    // applyAcknowledgedKioskSetting), which always reconciles before reporting failure.
    override fun handleVoiceEnabled(on: Boolean) {
        if (on && !hasMicrophone) Log.w(TAG, "ignoring voice_enabled ON command without a microphone")
        val accepted = applyAcknowledgedVoiceEnabled(
            on = on,
            hasMicrophone = hasMicrophone,
            commit = config::commitVoiceEnabled,
            reconcile = {},
        )
        check(accepted) {
            if (on && !hasMicrophone) "this panel has no microphone capability"
            else "voice_enabled setting commit failed"
        }
    }

    override fun handleKiosk(on: Boolean) {
        // An ON stores itself only if no OFF was stored ahead of its command while it locked; else it rolls back.
        val offs = config.kioskOffsStoredAhead()
        val persist = { value: Boolean ->
            if (value) config.commitKioskOnUnlessOffStoredSince(offs) else config.commitKioskLock(false)
        }
        check(applyAcknowledgedKioskSetting(on, kioskApply, persist) { core.reconcile("kiosk_lock") }) {
            "kiosk setting was not durably acknowledged"
        }
    }

    /** The camera master switch, commanded from Home Assistant. Enabling arms the camera and nothing more:
     *  Android still withholds the camera until somebody grants the permission at the panel, and every
     *  enable direction waits for a local approval first, so no remote message alone can put this panel's
     *  camera into service. Disabling ends a live session immediately. */
    private fun handleCameraEnabled(on: Boolean) {
        requireCameraEnableAdmission(on, hasCamera() == true) {
            authorizeRemoteSensitive(
                SensitiveOperation.CAMERA_ENABLE,
                "camera_enabled\u0000enable",
                "Serve this panel's camera to Home Assistant",
                always = true,
            )
        }
        check(config.commitCameraEnabled(on)) { "camera switch could not be persisted" }
        // The same actuation the HTTP reconfigure path performs, so both routes converge on one owner.
        onCameraEnabledChanged()
        core.reconcile("camera_enabled")
    }

    override fun handleSilenceBootChime(on: Boolean) {
        val outcome = bootChime.apply(on)
        // ON is persisted before the hardware write, so once durable it is newer truth than any queued HTTP
        // value, even when the write fails (as touch_sound).
        if (bootChime.isEnabled() == on) externalIntentCommitted.set("silence_boot_chime")
        requireControlApplied("silence_boot_chime", outcome) { "boot chime transition failed" }
        core.reconcile("silence_boot_chime")
    }

    override fun handleAutoBright(on: Boolean) {
        config.setAutoBrightness(on)
        autoBright.reapplyLatest()
        onAutoBrightnessConfigChanged()
        core.reconcile("auto_brightness")
    }

    override fun handleAutoBrightnessSensitivity(percent: Int) {
        config.setAutoBrightnessResponsePercent(percent)
        autoBright.reapplyLatest()
    }

    override fun handleAutoBrightnessMaximum(percent: Int) {
        config.setAutoBrightnessMaximumPercent(percent)
        autoBright.reapplyLatest()
    }

    override fun handleAutoBrightnessMinimum(percent: Int) {
        config.setAutoBrightnessMinimumPercent(percent)
        autoBright.reapplyLatest()
    }

    override fun handleAutoBrightnessHaEntity(entityId: String) {
        config.setAutoBrightnessHaEntity(entityId.trim().trim('"'))
        onAutoBrightnessConfigChanged()
        autoBright.reapplyLatest()
    }

    // Button backlight (e.g. TPA10): a brightness-only light, driven via the root daemon's BTN command
    // (same daemon that owns the sysfs LED). Daemon calls are short blocking I/O — fine on this thread.
    private fun handleButtons(light: CommandValue.Light) {
        val level = if (!light.on) 0 else light.brightness ?: 255
        val applied = helperSend(buttonBacklightCommand(level, buttonBacklightTransfer)) == "OK"
        if (applied) config.lastButtonBacklight = level
        core.reconcile("buttons")
        if (!applied) throw LiveSettingUnavailableException("buttons")
    }

    // Zigbee router toggle (Sonoff NSPanel Pro). ON starts the guard supervisor (→ mosquitto +
    // zgateway, ensures Repeater role); OFF stops the guard + zstack, freeing the radio.
    //
    // The vendor lifecycle is slow — OFF blocks ~8s in the stop script, and ON's gateway spawns on the
    // guard's ~30s timer (tens of seconds before it answers) — so it must NOT run on the command worker
    // thread. We publish the commanded state optimistically, then reconcile to the real running state
    // on a background thread (polling for the slow ON) so HA ends up correct without stalling the command worker.
    override fun handleZigbee(on: Boolean) {
        if (on) onZigbeeExplicitRetry()
        config.setZigbeeRouterEnabled(on) // persist desired state so it survives a reboot (boot-restore)
        admitZigbee(on)
    }

    /** User-initiated join retry from the local HTML UI. The persisted switch must already be ON;
     *  this reasserts Repeater mode through the same serialized actuator without spawning another guard. */
    fun requestZigbeeJoin(): Boolean {
        if (!lifecycle.isOpen() || !config.zigbeeRouterConfigured || !config.zigbeeRouterEnabled) return false
        onZigbeeExplicitRetry()
        return admitZigbee(true) != LatestDispatcher.Admission.CLOSED
    }

    // Boot/connect RECONCILE for the Zigbee router. Vendor firmware boot-starts the NSPanel Pro gateway
    // independently of us (and on 120P/3.7.1 the vendor guard CPU-spins), so we drive it to the user's
    // explicit choice on every connect: start it if they left it ON and nothing has; STOP it if they
    // turned it OFF (otherwise the vendor-started gateway returns each reboot). Gated on the switch having
    // been CONFIGURED — we never disable a stock vendor gateway by our default. Slow lifecycle off-thread.
    fun reconcileZigbeeBoot() {
        if (!core.capabilityShape.current().live.zigbee || !config.zigbeeRouterConfigured) return
        val want = config.zigbeeRouterEnabled
        if (want == zigbee.running()) return // already in the desired state
        admitZigbee(want)
    }

    private fun admitZigbee(desired: Boolean): LatestDispatcher.Admission {
        val admission = zigbeeWorker.submit(desired)
        when (admission) {
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.ZIGBEE_RECONCILE)
            LatestDispatcher.Admission.REJECTED, // unreachable for a single slot
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.ZIGBEE_RECONCILE)
            LatestDispatcher.Admission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, zigbeeWorker.pendingCount())
        return admission
    }

    private fun reconcileZigbeeDesired(desired: Boolean) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, 0)
        val cost = FeatureCosts.registry.span(FeatureCostOperation.ZIGBEE_RECONCILE).work(units = 1)
        try {
            val execution = zigbeeActuation.executeIfCurrent(zigbeeLease) {
                val reconciled = zigbee.reconcile(desired)
                if (desired && reconciled) for (i in 0 until 18) {
                    // A newer runtime generation or command is already authoritative. Stop waiting and
                    // release the shared actuator so its latest desired state can run after this one.
                    if (!lifecycle.isOpen() || !zigbeeActuation.isCurrent(zigbeeLease) ||
                        config.zigbeeRouterEnabled != desired || zigbee.running()
                    ) break
                    Thread.sleep(5_000)
                }
                reconciled to zigbee.running()
            }
            if (!execution.executed || !execution.currentAfter) {
                cost.outcome(FeatureCostOutcome.CANCELLED)
                return
            }
            val (reconciled, running) = requireNotNull(execution.value)
            if (!reconciled) cost.outcome(FeatureCostOutcome.FAILURE)
            if (lifecycle.isOpen()) core.reconcile("zigbee_router")
            Log.i(TAG, "zigbee reconcile -> ${if (desired) "on" else "off"}; running=$running")
        } catch (e: InterruptedException) {
            cost.outcome(FeatureCostOutcome.CANCELLED)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "zigbee reconcile failed", e)
        } finally {
            cost.close()
        }
    }

    // On-board relay (Smatek S9E). Channel relay<N>.
    // The write runs here on the ordered command worker; the physical read-back goes through the
    // generation-guarded gate so bursts coalesce and a late read-back never publishes a superseded state.
    private fun handleRelay(channel: String, on: Boolean) {
        val n = channel.removePrefix("relay").toIntOrNull() ?: return
        if (!relayReadbackGate.command("relay$n") { relay.set(n, on) }) {
            throw LiveSettingUnavailableException(channel)
        }
    }

    // S9E button LED. Channel button_led<N> (N 1-based).
    private fun handleButtonLed(channel: String, on: Boolean) {
        val n = channel.removePrefix("button_led").toIntOrNull() ?: return
        if (!relayReadbackGate.command("button_led$n") { relay.ledSet(n - 1, on) }) {
            throw LiveSettingUnavailableException(channel)
        }
    }

    // CPU scaling governor (select). Quick su write; publishes the read-back governor.
    override fun handleCpuGov(option: ChannelOption) {
        check(cpu.setTier(option.label)) { "CPU governor transition failed" }
        core.reconcile("cpu_governor")
    }

    // Persistent network adb (switch). Restarts adbd to apply; that only affects adb, not the Panel Assistant session.
    override fun handleNetAdb(on: Boolean) {
        if (on && config.hardenedSecurityEnabled) {
            throw HardenedModeRefusalException("network ADB cannot be enabled while Hardened mode is active")
        }
        requireControlApplied("network_adb", adb.set(on)) { "network adb transition failed" }
        core.reconcile("network_adb")
    }

    /** A sensitive command's one-shot panel approval binds to the approval key of the origin that sent it;
     * service-side replays of already-approved HTTP writes bypass this helper. A dispatch with no origin is
     * a programming error, never a reason to borrow another principal. */
    private fun authorizeRemoteSensitive(
        operation: SensitiveOperation,
        payload: String,
        summary: String,
        always: Boolean = false,
    ) {
        if (!always && !config.hardenedSecurityEnabled) return
        val origin = checkNotNull(currentOrigin.get()) { "sensitive ${operation.name} dispatched without an origin" }
        // Protocol section 20.2: a loopback peer (the stub in hardened mode) is exempt, as it is over HTTP.
        if (!always && origin.peerAddress?.let { io.panelassistant.android.util.isLoopbackPeer(it) } == true) return
        // A relayed Home Assistant administrator is exempt for the same operations as on the manage path.
        if (!always && io.panelassistant.android.http.relayedAdministratorExempt(origin, operation)) return
        val (decision, approvalId) = LocalApprovalBroker.instance.request(operation, origin.approvalKey, payload, summary)
        if (decision != ApprovalBroker.Decision.APPROVED) {
            throw SensitiveApprovalPendingException(
                approvalId,
                "approval required on the panel before ${operation.label.lowercase()}",
            )
        }
    }

    // Soft navbar mode (select). Persist and publish only after the asynchronous platform actuation
    // acknowledges both privileged display state and the main-thread overlay mutation.
    override fun handleNavbar(option: ChannelOption) {
        val mode = option.label
        val previous = config.navbarMode
        // "Native" is a valid option everywhere, so without this guard a command aimed at the wrong panel
        // would take away its drawn bar and leave no navigation at all. Keep the panel where it is and let
        // the reconcile republish the canonical value, which snaps the Home Assistant select back.
        if (!navbarModePermitted(mode, config.hasNativeNavbar)) {
            Log.w(TAG, "navbar: refusing $mode — this panel has no native navigation bar")
            core.reconcile("navbar")
            throw LiveSettingUnavailableException("navbar_mode")
        }
        applyAcknowledgedNavbarMode(
            payload = mode,
            previousMode = previous,
            actuate = navbar::applyAsync,
            rollback = navbar::rollbackAsync,
            persist = { applied ->
                config.commitRaw(requireNotNull(SettingsRegistry.spec("navbar_mode")), applied)
            },
            reconcile = { core.reconcile("navbar") },
        )
    }

    override fun handleHomeDashboard(payload: String, previousValue: String?) {
        // One authority decides what a home dashboard may be: the setting's own validator, which the
        // config API and both pickers already use. This function serves two callers with different
        // histories — a Home Assistant command arrives unvalidated, live apply arrives already canonical — and the
        // former URL-to-local-path conversion was wrong for both. It let a command retain a route the API
        // would have refused, and it rewrote an accepted route whose query or fragment merely CONTAINED
        // "://" into a different dashboard. Neither caller wants a second, weaker opinion here.
        val spec = SettingsRegistry.spec("home_dashboard")
        val target = when (val validated = spec?.let { SettingValue.validate(it, payload) }) {
            is Validation.Ok -> validated.normalized
            else -> {
                // Refuse rather than coerce, then republish so the entity cannot go on showing a value
                // the panel did not accept. The refusal answers `refused`/`invalid_value`, so Home Assistant
                // raises an error and restores its field instead of keeping the typed text. The route itself
                // is not logged; it names the owner's rooms.
                Log.w(TAG, "home_dashboard command refused: not a well-formed dashboard route")
                core.reconcile("home_dashboard")
                throw IllegalArgumentException("home_dashboard: not a well-formed dashboard route")
            }
        }
        val changed = target != (previousValue ?: config.homeDashboard)
        config.setHomeDashboard(target)
        if (changed) onDashboardTargetChanged()
        core.reconcile("home_dashboard")
    }

    // Android's own screen-off timeout, the screensaver delay on a TV. Prevent idle dim holds it at "never",
    // so a value is taken only while that is off: turning it off stays the approval-gated step.
    private fun handleScreenOffTimeout(seconds: Int) {
        if (config.preventIdleDim || !screen.updateScreenOffTimeout { held -> brightness.setScreenOffTimeout(seconds * 1_000, held) }) {
            core.reconcile("screen_off_timeout")
            throw LiveSettingUnavailableException("screen_off_timeout")
        }
        core.reconcile("screen_off_timeout")
    }

    /** Nothing to actuate: the server owns HA registry write-back. */
    override fun handleHaAreaPublishOnly() = Unit

    // Both take effect from the stored value alone: the renderer rebuilds for a new theme from its own
    // preference listener, and the idle check reads the minutes on every tick.
    override fun handleDashboardTheme(option: ChannelOption) {
        check(config.commitRaw(requireNotNull(SettingsRegistry.spec("dashboard_theme")), option.label)) {
            "dashboard theme could not be persisted"
        }
        core.reconcile("dashboard_theme")
    }

    override fun handleIdleReturn(minutes: Int) {
        check(config.commitRaw(requireNotNull(SettingsRegistry.spec("dashboard_idle_return_min")), minutes.toString())) {
            "idle return could not be persisted"
        }
        core.reconcile("dashboard_idle_return")
    }

    private fun handleNavigate(target: String) {
        // Local navigation only: strip any scheme + host so an external URL can't be pushed (the HA
        // Companion opens a disorienting in-app WebView for those). We keep just the path.
        val path = toLocalPath(target)
        if (path.isEmpty()) return
        system.endDream()
        if (config.dashboardPackage.isBlank() || config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) {
            // Built-in renderer: set the target path and (re)launch DashboardActivity, which loads it —
            // the deep link targets the Companion and would no-op on a builtin-only panel. A repeated path
            // navigates again, because the page may have moved since by touch.
            BuiltinDashboard.navPath = path
            system.launchHome(config.dashboardPackage)
            config.lastNavigate = path
            core.reconcile("navigate")
            return
        }
        if (path == config.lastNavigate) {
            // Already on this path — the deeplink is a no-op, so reload the dashboard instead.
            system.reloadDashboard(config.dashboardPackage)
        } else {
            val companion = system.resolveDashboard(config.dashboardPackage)
                .takeIf { RendererResolver.companionHomeRoute(it, config.homeDashboard) != null }
            if (companion != null) navigate.navigate("homeassistant://navigate$path", companion)
            else navigate.navigate("homeassistant://navigate$path")
            config.lastNavigate = path
            core.reconcile("navigate")
        }
    }

    /** Reduce any posted value to a leading-slash local path: drop `scheme://` and the `host:port`
     *  authority, keep the path (+ query/fragment). `http://ha.local:8123/lovelace/0` → `/lovelace/0`;
     *  `lovelace/0` → `/lovelace/0`; `/lovelace/0` unchanged. */
    private fun toLocalPath(raw: String): String {
        var s = raw.trim().trim('"')
        if (s.isEmpty()) return ""
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            s = s.substring(scheme + 3)            // strip scheme://
            val slash = s.indexOf('/')             // drop the host[:port] authority
            s = if (slash >= 0) s.substring(slash) else "/"
        }
        if (!s.startsWith("/")) s = "/$s"
        return s
    }

    private fun handleVolume(pct: Int) {
        volume.setPercent(pct)
        core.noteVolumeCommanded()
    }

    /**
     * Apply a setting from a NON-command source (the HTTP `/api/v1/config` API) through the SAME
     * side-effect + state-publish path a Home Assistant command takes, so a value set over HTTP behaves
     * identically to one set from Home Assistant (persist → drive hardware → publish state).
     * [value] is the registry-normalized string ("true"/"false", a number, or an enum label).
     * Reports whether the setting applied, was not admitted because this dispatcher is draining, or failed
     * after admission. Only the admission failure is safe for the service to acknowledge as deferred.
     */
    internal fun applySetting(key: String, value: String, previousValue: String? = null): LiveSettingApplyResult {
        return applySettingObserved(key, value, previousValue).initial
    }

    internal fun applySettingObserved(
        key: String,
        value: String,
        previousValue: String? = null,
    ): LiveSettingApplication {
        if (key !in APPLY_SETTING_KEYS) return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
        if (!lifecycle.isOpen()) return LiveSettingApplication.immediate(LiveSettingApplyResult.DEFERRED)
        var started = false
        // Written on the dispatcher thread by the handler below, read by this caller and by the late
        // completion observer, so it must be published across both.
        val unavailable = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = queue.runLatestResult(
            key = settingConflationKey(key),
            onAdmission = ::recordCommandAdmission,
        ) {
            started = true
            val cost = FeatureCosts.registry.span(FeatureCostOperation.COMMAND_DISPATCH)
                .work(units = 1, bytes = value.toByteArray().size.toLong())
            try {
                // This path is used only by the HTTP/service live-setting authority. Its owning HTTP
                // request was already approved against the real peer before it became durable or queued.
                dispatchLiveSetting(
                    key = key,
                    value = liveSettingValue(requireNotNull(SettingsRegistry.spec(key)), value),
                    previousValue = previousValue,
                    sensitiveApprovalRequired = false,
                    handlers = this,
                )
            } catch (e: LiveSettingUnavailableException) {
                unavailable.set(true)
                cost.outcome(FeatureCostOutcome.FAILURE)
                throw e
            } catch (e: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                throw e
            } finally {
                cost.close()
            }
        }
        if (!result.executed && !started &&
            (result.admission == CommandQueue.Admission.REJECTED ||
                result.admission == CommandQueue.Admission.CLOSED)
        ) {
            FeatureCosts.registry.span(FeatureCostOperation.COMMAND_DISPATCH)
                .outcome(FeatureCostOutcome.REJECTED)
                .work(bytes = value.toByteArray().size.toLong())
                .close()
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.COMMAND_DISPATCH,
            queue.pendingCount(),
        )
        return liveSettingApplication(result, unavailable::get)
    }

    /** Publish the already-durable auto-sleep value without running its mutating command handler.
     * The generation check happens inside the dispatcher lane, after any concurrent direct
     * command, so stale queued OFF work can never overwrite a newer ON. It is a reconcile, not a write,
     * so it conflates only with other converges and can never displace a queued auto_sleep value. */
    internal fun convergeAutoSleep(expectedGeneration: Long, expectedValue: Boolean): LiveSettingApplyResult {
        if (!lifecycle.isOpen()) return LiveSettingApplyResult.DEFERRED
        val result = queue.runLatestResult(
            key = "auto_sleep:converge",
            onAdmission = ::recordCommandAdmission,
        ) {
            if (config.autoSleepGeneration == expectedGeneration && config.autoSleep == expectedValue) {
                core.reconcile("auto_sleep")
            }
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.COMMAND_DISPATCH,
            queue.pendingCount(),
        )
        return liveSettingApplyResult(result)
    }

    /**
     * Kiosk platform recovery after start-up, on the command worker so it orders with every kiosk change:
     * [enable] false completes or clears a platform transition a prior process left for the stored setting;
     * true re-locks if the setting is still ON when it runs, so a queued OFF ahead of it wins. Waits for the
     * worker. Null when this dispatcher did not run it (draining or stopped): the caller asks the current one.
     */
    internal fun recoverKiosk(enable: Boolean): Boolean? {
        if (!lifecycle.isOpen()) return null
        val outcome = java.util.concurrent.CompletableFuture<Boolean?>()
        // A recovery that ended without completing its own outcome failed, or did not run.
        val notRun = { execution: CommandQueue.Execution -> if (execution == CommandQueue.Execution.FAILED) false else null }
        val result = queue.runLatestResult(key = "kiosk_lock:recover", onAdmission = ::recordCommandAdmission) {
            outcome.complete(if (enable) config.kioskLock && kioskApply(true) else kioskRecover(config.kioskLock))
        }
        if (!result.observeLateCompletion { terminal -> outcome.complete(notRun(terminal)) }) {
            outcome.complete(notRun(result.execution))
        }
        return outcome.get()
    }

    /** Close admission, cancel queued commands, then drain the running handler and Zigbee worker. */
    fun stop(deadline: MonotonicDeadline): Boolean {
        lifecycle.closeAdmission()
        operations.stop()
        zigbeeActuation.retire(zigbeeLease)
        val cancelledCommands = queue.close()
        val queuedZigbee = zigbeeWorker.pendingCount()
        zigbeeWorker.closeAndJoin(0L)
        if (cancelledCommands > 0) {
            FeatureCosts.registry.recordDropped(
                FeatureCostOperation.COMMAND_DISPATCH,
                cancelledCommands.toLong(),
            )
        }
        if (queuedZigbee > 0) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.ZIGBEE_RECONCILE, queuedZigbee.toLong())
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.COMMAND_DISPATCH, 0)
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, 0)
        val lifecycleDrained = lifecycle.awaitDrained(deadline)
        val commandsDrained = queue.awaitDrained(deadline)
        val zigbeeDrained = zigbeeWorker.closeAndJoin(deadline.remainingMs())
        val drained = lifecycleDrained && commandsDrained && zigbeeDrained
        if (!drained) {
            Log.w(TAG, "command dispatcher did not drain (lifecycle=$lifecycleDrained commands=$commandsDrained zigbee=$zigbeeDrained)")
        }
        return drained
    }

    companion object {
        private const val TAG = "ha-paneld/commands"
        /** The approval binding of a press, which carries no value of its own. */
        private const val PRESS_BINDING = "press"
        private const val MAX_DYNAMIC_COMMAND_INDEX = 64
        /** Channels whose commands conflate: the newest value for a channel is the one that runs. */
        private val STATE_CHANNELS = setOf(
            "cpu_governor", "network_adb", "screen", "led", "navigate", "volume", "home_dashboard",
            "buttons", "navbar", "wake_on_wave", "auto_sleep", "touch_sound", "watchdog", "kiosk_lock",
            "silence_boot_chime", "prevent_idle_dim", "zigbee_router", "auto_brightness",
            "camera_enabled", "dashboard_theme", "dashboard_idle_return", "screen_off_timeout",
        )
        /** Channels whose commands never conflate. */
        private val ACTION_CHANNELS = setOf("reload", "reboot", "android_settings", "launcher", "dashboard", "back", "recents")

        /** Keys accepted by [applySetting], declared once by [SettingsRegistry]. */
        internal val APPLY_SETTING_KEYS = SettingsRegistry.liveApplyKeys().toSet()
    }
}

/** Independent, production-used map from registry live keys to their concrete dispatcher effect owner.
 * Keeping dispatch exhaustive over this enum means a newly declared live setting cannot acquire HTTP
 * and remote-command classification merely because both classifications derive from the same registry flag. */
internal enum class LiveSettingEffectOwner(val settingKey: String) {
    WAKE_ON_WAVE("wake_on_wave"),
    AUTO_SLEEP("auto_sleep"),
    AUTO_SLEEP_SOURCE("auto_sleep_source"),
    PREVENT_IDLE_DIM("prevent_idle_dim"),
    WATCHDOG("watchdog_enabled"),
    KIOSK("kiosk_lock"),
    BOOT_CHIME("silence_boot_chime"),
    AUTO_BRIGHTNESS("auto_brightness"),
    TOUCH_SOUND("touch_sound"),
    VOICE("voice_enabled"),
    NETWORK_ADB("network_adb"),
    ZIGBEE("zigbee_router"),
    AUTO_BRIGHTNESS_MINIMUM("auto_brightness_minimum_percent"),
    AUTO_BRIGHTNESS_MAXIMUM("auto_brightness_maximum_percent"),
    AUTO_BRIGHTNESS_RESPONSE("auto_brightness_response_percent"),
    AUTO_BRIGHTNESS_HA_ENTITY("auto_brightness_ha_entity"),
    CPU_GOVERNOR("cpu_governor"),
    NAVBAR("navbar_mode"),
    HOME_DASHBOARD("home_dashboard"),
    HA_AREA_PUBLISH_ONLY("ha_area"),
    DASHBOARD_THEME("dashboard_theme"),
    IDLE_RETURN("dashboard_idle_return_min"),
    ;

    companion object {
        private val bySettingKey = entries.associateBy(LiveSettingEffectOwner::settingKey)
        internal val settingKeys: Set<String> = bySettingKey.keys

        fun requireFor(key: String): LiveSettingEffectOwner =
            bySettingKey[key] ?: error("live setting declared without a dispatcher: $key")
    }
}

/**
 * The complete remote command surface (Panel Assistant) for registry-backed live
 * settings, keyed by command channel.
 *
 * This is deliberately narrower than [LiveSettingEffectOwner]: HTTP can apply adaptive-brightness
 * tuning/source fields, while `ha_area` is followed by server-owned HA registry work and its dispatcher
 * owner is intentionally publish-only. The wildcard subscription must not turn those HTTP-only/no-op
 * owners into undocumented remote writers.
 */
internal val externalLiveSettingOwners: Map<String, String> = linkedMapOf(
    "cpu_governor" to "cpu_governor",
    "network_adb" to "network_adb",
    "home_dashboard" to "home_dashboard",
    "navbar" to "navbar_mode",
    "wake_on_wave" to "wake_on_wave",
    "auto_sleep" to "auto_sleep",
    "touch_sound" to "touch_sound",
    "watchdog" to "watchdog_enabled",
    "kiosk_lock" to "kiosk_lock",
    "silence_boot_chime" to "silence_boot_chime",
    "prevent_idle_dim" to "prevent_idle_dim",
    "zigbee_router" to "zigbee_router",
    "auto_brightness" to "auto_brightness",
    "dashboard_theme" to "dashboard_theme",
    "dashboard_idle_return" to "dashboard_idle_return_min",
)

/**
 * The key a *failed* external command must still supersede, or null.
 *
 * A handler that persists intent before actuating leaves newer durable truth behind even when the
 * actuation then fails — and that failure throws straight past the supersession step that normally
 * retires the older queued HTTP value. Left alone, startup replay commits the stale HTTP value over the
 * newer external one, inverting last-write-wins on the durable setting.
 *
 * Both arguments are required to agree. [committedKey] alone would let a key committed by an earlier
 * dispatch retire a pending value the current command never touched, and [commandKey] alone would retire
 * one for a command that threw before committing anything — an unauthorized or refused command must
 * leave HTTP intent exactly where it was.
 */
internal fun supersededKeyAfterFailedDispatch(commandKey: String?, committedKey: String?): String? =
    commandKey?.takeIf { it == committedKey }

/** Resolve a remote command channel to the same registry key used by HTTP live application. */
internal fun externalLiveSettingKey(channel: String): String? = externalLiveSettingOwners[channel]

private val settingChannels: Map<String, String> = externalLiveSettingOwners.entries.associate { (channel, key) -> key to channel }

/**
 * The command-queue key a setting conflates under, whichever door it arrived through: its channel when Panel
 * Assistant can command it, else its own registry key, which no channel shares (protocol section 20.2,
 * "Conflation"). One key per setting makes queue order the order its values are stored in.
 */
internal fun settingConflationKey(key: String): String = settingChannels[key] ?: key

/**
 * The concrete live-setting owners. [PanelCommandDispatcher] implements these methods itself, so production has no
 * second key-to-method adapter that can be swapped independently of the behaviour tested by the router.
 */
internal interface LiveSettingHandlers {
    fun handleWakeOnWave(on: Boolean)
    fun handleAutoSleep(on: Boolean)
    fun handleAutoSleepSource(source: String)
    fun handlePreventIdleDim(on: Boolean, approvalRequired: Boolean = true)
    fun handleWatchdog(on: Boolean)
    fun handleKiosk(on: Boolean)
    fun handleSilenceBootChime(on: Boolean)
    fun handleAutoBright(on: Boolean)
    fun handleTouchSound(on: Boolean)
    fun handleVoiceEnabled(on: Boolean)
    fun handleNetAdb(on: Boolean)
    fun handleZigbee(on: Boolean)
    fun handleAutoBrightnessMaximum(percent: Int)
    fun handleAutoBrightnessMinimum(percent: Int)
    fun handleAutoBrightnessSensitivity(percent: Int)
    fun handleAutoBrightnessHaEntity(entityId: String)
    fun handleCpuGov(option: ChannelOption)
    fun handleNavbar(option: ChannelOption)
    fun handleHomeDashboard(payload: String, previousValue: String? = null)
    fun handleHaAreaPublishOnly()
    fun handleDashboardTheme(option: ChannelOption)
    fun handleIdleReturn(minutes: Int)
}

/** [this] as the kind [T] the [target] takes; any other kind is refused as an invalid value. */
internal inline fun <reified T : CommandValue> CommandValue.expect(target: String): T =
    requireNotNull(this as? T) { "$target does not take ${this::class.simpleName}" }

/**
 * The typed value of a registry-normalized [normalized] string, as the HTTP config route, the :8888 form and
 * the live-setting journal carry it: a switch, an integer, the option whose label it names, or text. Throws
 * when the string does not fit [spec].
 */
internal fun liveSettingValue(spec: SettingSpec, normalized: String): CommandValue {
    val options = spec.ha?.facts?.options
    return when {
        spec.type == SettingType.BOOL -> CommandValue.Switch(
            requireNotNull(SettingValue.parseBool(normalized)) { "${spec.key} requires a normalized boolean" },
        )
        spec.type == SettingType.INT -> CommandValue.Quantity(normalized.toInt().toDouble())
        spec.type == SettingType.ENUM && options != null -> CommandValue.Option(
            requireNotNull(options.firstOrNull { it.label.equals(normalized, ignoreCase = true) }) {
                "${spec.key} has no option $normalized"
            },
        )
        else -> CommandValue.Text(normalized)
    }
}

/** Route one typed setting value to exactly one typed effect handler. */
internal fun dispatchLiveSetting(
    key: String,
    value: CommandValue,
    previousValue: String? = null,
    sensitiveApprovalRequired: Boolean = true,
    handlers: LiveSettingHandlers,
) {
    fun on() = value.expect<CommandValue.Switch>(key).on
    fun integer(): Int {
        val number = value.expect<CommandValue.Quantity>(key).value
        val spec = requireNotNull(SettingsRegistry.spec(key))
        require(spec.type == SettingType.INT && number.isFinite()) { "$key requires a finite integer setting" }
        // Match Configure's whole-number rounding, then use the setting's ordinary validation.
        val validated = SettingValue.validate(spec, number.roundToInt().toString())
        require(validated is Validation.Ok) { (validated as Validation.Bad).reason }
        return validated.normalized.toInt()
    }
    fun text() = value.expect<CommandValue.Text>(key).text
    fun option() = value.expect<CommandValue.Option>(key).option
    when (LiveSettingEffectOwner.requireFor(key)) {
        LiveSettingEffectOwner.WAKE_ON_WAVE -> handlers.handleWakeOnWave(on())
        LiveSettingEffectOwner.AUTO_SLEEP -> handlers.handleAutoSleep(on())
        LiveSettingEffectOwner.AUTO_SLEEP_SOURCE -> handlers.handleAutoSleepSource(text())
        LiveSettingEffectOwner.PREVENT_IDLE_DIM -> handlers.handlePreventIdleDim(on(), sensitiveApprovalRequired)
        LiveSettingEffectOwner.WATCHDOG -> handlers.handleWatchdog(on())
        LiveSettingEffectOwner.KIOSK -> handlers.handleKiosk(on())
        LiveSettingEffectOwner.BOOT_CHIME -> handlers.handleSilenceBootChime(on())
        LiveSettingEffectOwner.AUTO_BRIGHTNESS -> handlers.handleAutoBright(on())
        LiveSettingEffectOwner.TOUCH_SOUND -> handlers.handleTouchSound(on())
        LiveSettingEffectOwner.VOICE -> handlers.handleVoiceEnabled(on())
        LiveSettingEffectOwner.NETWORK_ADB -> handlers.handleNetAdb(on())
        LiveSettingEffectOwner.ZIGBEE -> handlers.handleZigbee(on())
        LiveSettingEffectOwner.AUTO_BRIGHTNESS_MINIMUM -> handlers.handleAutoBrightnessMinimum(integer())
        LiveSettingEffectOwner.AUTO_BRIGHTNESS_MAXIMUM -> handlers.handleAutoBrightnessMaximum(integer())
        LiveSettingEffectOwner.AUTO_BRIGHTNESS_RESPONSE -> handlers.handleAutoBrightnessSensitivity(integer())
        LiveSettingEffectOwner.AUTO_BRIGHTNESS_HA_ENTITY -> handlers.handleAutoBrightnessHaEntity(text())
        LiveSettingEffectOwner.CPU_GOVERNOR -> handlers.handleCpuGov(option())
        LiveSettingEffectOwner.NAVBAR -> handlers.handleNavbar(option())
        LiveSettingEffectOwner.HOME_DASHBOARD -> handlers.handleHomeDashboard(text(), previousValue)
        LiveSettingEffectOwner.HA_AREA_PUBLISH_ONLY -> handlers.handleHaAreaPublishOnly()
        LiveSettingEffectOwner.DASHBOARD_THEME -> handlers.handleDashboardTheme(option())
        LiveSettingEffectOwner.IDLE_RETURN -> handlers.handleIdleReturn(integer())
    }
}

/**
 * A live-setting handler failed because this panel has no path to the hardware at all.
 *
 * The classification is thrown rather than returned because the command dispatcher between the handler
 * and the live-setting journal reports one boolean per command. Throwing carries the distinction across
 * that boundary without giving every command a typed result it has no use for; on the plain
 * command path, where nothing is journalled, it is an ordinary failure.
 */
internal class LiveSettingUnavailableException(val key: String) :
    IllegalStateException("$key has no apply path on this hardware")

/** Fail a handler while preserving whether the attempt found no path at all. */
internal fun requireControlApplied(
    key: String,
    outcome: io.panelassistant.android.control.ControlApplyOutcome,
    message: () -> String,
) {
    when (outcome) {
        io.panelassistant.android.control.ControlApplyOutcome.APPLIED -> Unit
        io.panelassistant.android.control.ControlApplyOutcome.UNAVAILABLE ->
            throw LiveSettingUnavailableException(key)
        io.panelassistant.android.control.ControlApplyOutcome.FAILED -> error(message())
    }
}

internal fun liveSettingApplyResult(
    result: CommandQueue.RunResult,
    unavailable: () -> Boolean = { false },
): LiveSettingApplyResult = when {
    result.admission == CommandQueue.Admission.CLOSED ||
        result.admission == CommandQueue.Admission.REJECTED ->
        LiveSettingApplyResult.DEFERRED
    result.execution == CommandQueue.Execution.PENDING ->
        LiveSettingApplyResult.DEFERRED
    result.execution == CommandQueue.Execution.SUCCEEDED ->
        LiveSettingApplyResult.APPLIED
    // A newer value for the same setting, from any origin, was admitted under the same conflation key and
    // ran in this one's place: nothing of this value remains to apply or replay.
    result.execution == CommandQueue.Execution.SUPERSEDED ->
        LiveSettingApplyResult.APPLIED
    // Only a handler that ran and failed can report unavailability. Superseded and never-admitted work
    // observed no hardware at all, so it stays an ordinary retryable failure whatever the flag says.
    result.execution == CommandQueue.Execution.FAILED && unavailable() ->
        LiveSettingApplyResult.UNAVAILABLE
    else -> LiveSettingApplyResult.FAILED
}

internal fun liveSettingApplication(
    result: CommandQueue.RunResult,
    unavailable: () -> Boolean = { false },
): LiveSettingApplication {
    val initial = liveSettingApplyResult(result, unavailable)
    if (result.execution != CommandQueue.Execution.PENDING) {
        return LiveSettingApplication.immediate(initial)
    }
    return LiveSettingApplication(initial) { observer ->
        result.observeLateCompletion { terminal ->
            observer(
                liveSettingApplyResult(
                    CommandQueue.RunResult(result.admission, terminal),
                    unavailable,
                ),
            )
        }
    }
}

internal fun applyAutoSleepSetting(
    desired: Boolean,
    write: (Boolean) -> AutoSleepWriteResult,
    onCommitted: (Boolean) -> Unit,
) {
    when (write(desired)) {
        AutoSleepWriteResult.UNCHANGED -> Unit
        AutoSleepWriteResult.COMMITTED -> onCommitted(desired)
        AutoSleepWriteResult.FAILED -> error("auto_sleep setting commit failed")
    }
}

/** Kiosk ON is durable only after privileged actuation accepts it; OFF remains fail-safe and retryable. */
internal fun applyAcknowledgedKioskSetting(
    on: Boolean,
    actuate: (Boolean) -> Boolean,
    persist: (Boolean) -> Boolean,
    reconcile: () -> Unit,
): Boolean {
    if (on) {
        if (!runCatching { actuate(true) }.getOrDefault(false)) return false
        if (!runCatching { persist(true) }.getOrDefault(false)) {
            // Actuation owns a durable recovery marker. Roll back local enforcement and retry
            // uncertain platform cleanup without ever acknowledging an ON that config did not commit.
            // SharedPreferences may update its in-memory map even when commit() reports disk failure,
            // so explicitly restore OFF before cleanup as well as returning a failed acknowledgement.
            runCatching { persist(false) }
            runCatching { actuate(false) }
            return false
        }
    } else {
        // OFF is fail-safe: persistence failure must never prevent local/persistent cleanup.
        val persisted = runCatching { persist(false) }.getOrDefault(false)
        runCatching { actuate(false) }
        // Report whatever is stored now: an OFF stored ahead of this command may have landed even if this write did not.
        runCatching(reconcile)
        return persisted
    }
    runCatching(reconcile)
    return true
}

/**
 * voice_enabled ON is durable only once [commit] (the durable [io.panelassistant.android.Config.
 * commitVoiceEnabled], never the fire-and-forget async write) accepts it AND the live runtime generation
 * has a microphone; [reconcile] runs on every path — success, capability refusal, or commit failure —
 * so the voice_enabled channel is never left echoing a value nothing actually took effect for, whether
 * that is a stale reported ON from before a profile switch removed the capability, or an in-memory
 * SharedPreferences value that changed even though its commit() reported failure (the same hazard
 * [applyAcknowledgedKioskSetting] guards against). A `false` return means the caller must report the
 * request as failed/refused rather than applied — see handleVoiceEnabled.
 */
internal fun applyAcknowledgedVoiceEnabled(
    on: Boolean,
    hasMicrophone: Boolean,
    commit: (Boolean) -> Boolean,
    reconcile: () -> Unit,
): Boolean {
    if (on && !hasMicrophone) {
        runCatching(reconcile)
        return false
    }
    if (!runCatching { commit(on) }.getOrDefault(false)) {
        runCatching(reconcile)
        return false
    }
    runCatching(reconcile)
    return true
}

/** Camera OFF is always immediate. Camera ON is admitted only on a camera-capable profile and only
 * after the local approval broker accepts the exact request. The caller performs no state mutation
 * until this function returns, so a missing capability or pending/refused approval cannot pre-arm a
 * hidden camera setting. */
internal fun requireCameraEnableAdmission(
    on: Boolean,
    hasCamera: Boolean,
    authorize: () -> Unit,
) {
    if (!on) return
    check(hasCamera) { "this panel has no camera capability" }
    authorize()
}

internal fun applyAcknowledgedNavbarMode(
    payload: String,
    previousMode: String,
    actuate: (String) -> CompletableFuture<NavbarModeApplyOutcome>,
    rollback: (expectedMode: String, previousMode: String) -> CompletableFuture<NavbarModeApplyOutcome>,
    persist: (String) -> Boolean,
    reconcile: () -> Unit,
    timeoutMs: Long = NAVBAR_ACK_TIMEOUT_MS,
) {
    fun await(
        future: CompletableFuture<NavbarModeApplyOutcome>,
        label: String,
    ): NavbarModeApplyOutcome = try {
        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (failure: Exception) {
        throw IllegalStateException("navbar $label acknowledgement failed", failure)
    }

    val outcome = try {
        await(actuate(payload), "actuation")
    } catch (failure: IllegalStateException) {
        // Conditional rollback supersedes this request only if it is still the controller's latest
        // desired mode. A newer HTTP or Home Assistant request remains authoritative and returns SUPERSEDED here.
        runCatching { await(rollback(payload, previousMode), "timeout rollback") }
        throw failure
    }
    check(outcome.status == NavbarModeApplyStatus.APPLIED) {
        "navbar transition ${outcome.status.name.lowercase()}"
    }
    val persisted = runCatching { persist(outcome.mode) }.getOrDefault(false)
    if (!persisted) {
        val restored = await(rollback(outcome.mode, previousMode), "persistence rollback")
        check(restored.status == NavbarModeApplyStatus.APPLIED ||
            restored.status == NavbarModeApplyStatus.SUPERSEDED
        ) { "navbar persistence failed and rollback ${restored.status.name.lowercase()}" }
        error("navbar persistence failed")
    }
    reconcile()
}

private const val NAVBAR_ACK_TIMEOUT_MS = 20_000L

/** Serializes Zigbee actuation across replaceable runtime generations. Invalidation is non-blocking;
 * a newer lease waits behind any old uninterruptible call and therefore always owns the final state. */
internal class ZigbeeActuationCoordinator {
    class Lease internal constructor()
    data class Execution<T>(val executed: Boolean, val currentAfter: Boolean, val value: T?)

    private val current = AtomicReference<Lease?>()
    private val actuationLock = Any()

    fun activate(): Lease = Lease().also { replacement -> current.getAndSet(replacement) }

    fun retire(lease: Lease) {
        current.compareAndSet(lease, null)
    }

    fun isCurrent(lease: Lease): Boolean = current.get() === lease

    fun <T> executeIfCurrent(lease: Lease, action: () -> T): Execution<T> = synchronized(actuationLock) {
        if (!isCurrent(lease)) return@synchronized Execution(false, false, null)
        val value = action()
        Execution(true, isCurrent(lease), value)
    }
}

private object ZigbeeActuationCoordinators {
    private val controllers = WeakHashMap<ZigbeeController, ZigbeeActuationCoordinator>()

    @Synchronized
    fun forController(controller: ZigbeeController): ZigbeeActuationCoordinator =
        controllers.getOrPut(controller) { ZigbeeActuationCoordinator() }
}

/** A sensitive remote command is waiting for [approvalId] to be approved on the panel's own screen. */
internal class SensitiveApprovalPendingException(val approvalId: String, message: String) : IllegalStateException(message)

/** A remote command Hardened mode refuses outright, with no approval that could allow it. */
internal class HardenedModeRefusalException(message: String) : IllegalStateException(message)

/** How a command handler's outcome reads on the native transport. */
internal fun panelAssistantCommandResult(failure: Exception?): PanelAssistantCommandResult = when (failure) {
    null -> PanelAssistantCommandResult.Applied
    is SensitiveApprovalPendingException -> PanelAssistantCommandResult.ApprovalPending(failure.approvalId)
    is HardenedModeRefusalException ->
        PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_REFUSED_HARDENED)
    is DeviceCredentialRebootRefusalException ->
        PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE)
    is LiveSettingUnavailableException ->
        PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_HARDWARE_UNAVAILABLE)
    is IllegalArgumentException -> PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
    else -> PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED)
}
