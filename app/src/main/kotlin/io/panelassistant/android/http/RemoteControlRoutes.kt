package io.panelassistant.android.http

import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.isLoopbackPeer
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.LatestDispatcher
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** One lifecycle-owned dispatcher for navigation, tap admission and bounded tap/capture work. */
internal class RemoteControlRoutes(
    private val config: Config,
    private val interactive: InteractiveController,
    private val system: SystemController,
    private val stepVolume: (Boolean) -> Unit,
    private val stopping: () -> Boolean,
    private val cacheScreenshot: (ByteArray) -> String?,
) {
    private sealed class RemoteControl(val key: String) {
        class Tap(
            val x: Float,
            val y: Float,
            val loopback: Boolean = false,
            val capture: Boolean = false,
            val requestId: Long? = null,
            val executeBeforeElapsedMs: Long? = null,
            val completeBeforeElapsedMs: Long? = null,
            val completion: CompletableDeferred<TapCaptureResult>? = null,
        ) : RemoteControl("tap")
        class Action(val name: String) : RemoteControl(name)
    }
    private val remoteInputSequence = AtomicLong()
    private val remoteControls: LatestDispatcher<String, RemoteControl> = LatestDispatcher(
        threadName = "ha-paneld-remote-control",
        maxPendingKeys = 8,
        consume = { _, command -> executeRemoteControl(command) },
        onDiscard = { _, command ->
            (command as? RemoteControl.Tap)?.completion?.complete(TapCaptureResult.NotExecuted)
        },
    )

    private fun executeRemoteControl(command: RemoteControl) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.REMOTE_INPUT, remoteControls.pendingCount())
        val cost = FeatureCosts.registry.span(FeatureCostOperation.REMOTE_INPUT).work(units = 1)
        val startedAt = SystemClock.elapsedRealtime()
        val ok = try {
            when (command) {
                is RemoteControl.Tap -> executeRemoteTap(command)
                is RemoteControl.Action -> if (executeRemoteDashboardAction(
                        command.name,
                        config.dashboardPackage,
                        launch = { system.launchHome(it) },
                        reload = { system.reloadDashboard(it) },
                    )
                ) true else when (command.name) {
                    "back" -> interactive.back()
                    "recents" -> interactive.recents()
                    "launcher" -> { system.launchLauncher(config.launcherPackage); true }
                    "admin_launcher" -> { system.launchAdminLauncher(); true }
                    "reboot" -> { system.reboot(); true }
                    "volup" -> { stepVolume(true); true }
                    "voldn" -> { stepVolume(false); true }
                    else -> false
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            (command as? RemoteControl.Tap)?.completion?.complete(TapCaptureResult.CompletionUnknown())
            Log.w(TAG, "remote control execution failed", error)
            false
        }
        if (!ok) cost.outcome(FeatureCostOutcome.FAILURE)
        cost.close()
        (command as? RemoteControl.Tap)?.requestId?.let { requestId ->
            Log.i(TAG, "remote input id=$requestId complete ok=$ok elapsed_ms=" +
                (SystemClock.elapsedRealtime() - startedAt))
        }
    }

    private fun executeRemoteTap(command: RemoteControl.Tap): Boolean {
        if (!command.capture) {
            if (config.hardenedSecurityEnabled && !command.loopback) return false
            return interactive.tapWithRoute(command.x, command.y) != null
        }
        val executeBefore = command.executeBeforeElapsedMs
        val completeBefore = command.completeBeforeElapsedMs
        if (executeBefore == null || completeBefore == null) {
            command.completion?.complete(TapCaptureResult.CompletionUnknown())
            return false
        }
        val result = performTapCapture(
            execution = TapCaptureExecution(
                command.x,
                command.y,
                command.loopback,
                executeBefore,
                completeBefore,
                REMOTE_TAP_CAPTURE_SETTLE_MS,
                REMOTE_SCREENSHOT_WAIT_MS,
            ),
            hardened = { config.hardenedSecurityEnabled },
            nowElapsedMs = SystemClock::elapsedRealtime,
            tap = interactive::tapOnceWithRoute,
            settle = { waitMs ->
                try {
                    Thread.sleep(waitMs)
                    true
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            },
            screenshot = interactive::screenshotOnceWithRoute,
        )
        command.completion?.complete(result)
        if (result is TapCaptureResult.Success) {
            Log.i(TAG, "remote input id=${command.requestId} routes=" +
                "${result.inputRoute.name.lowercase()}/${result.screenshotRoute.name.lowercase()}")
        }
        return result is TapCaptureResult.Success
    }
    private suspend fun respondRemoteAdmission(call: ApplicationCall, command: RemoteControl) {
        // Coordinate injection cannot be made self-approving: an approved tap could target the next
        // approval dialog. Hardened mode therefore trusts taps only from loopback software already on
        // the panel. Non-coordinate navigation remains routine; reboot has its own explicit gate below.
        val loopback = isLoopbackPeer(call.request.origin.remoteAddress)
        if (command is RemoteControl.Tap && config.hardenedSecurityEnabled && !loopback) {
            call.respondText(
                """{"ok":false,"error":"remote-input-disabled"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
            return
        }
        val admittedCommand = if (command is RemoteControl.Tap) {
            val requestId = if (command.capture) remoteInputSequence.incrementAndGet() else null
            RemoteControl.Tap(
                x = command.x,
                y = command.y,
                loopback = loopback,
                capture = command.capture,
                requestId = requestId,
                executeBeforeElapsedMs = requestId?.let {
                    SystemClock.elapsedRealtime() + REMOTE_TAP_QUEUE_DEADLINE_MS
                },
                completeBeforeElapsedMs = requestId?.let {
                    SystemClock.elapsedRealtime() + REMOTE_TAP_CAPTURE_TIMEOUT_MS
                },
                completion = requestId?.let { CompletableDeferred() },
            )
        } else command
        val admission = remoteControls.submit(admittedCommand.key, admittedCommand)
        when (admission) {
            LatestDispatcher.Admission.ACCEPTED -> Unit
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.REMOTE_INPUT)
            LatestDispatcher.Admission.REJECTED,
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.REMOTE_INPUT)
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.REMOTE_INPUT, remoteControls.pendingCount())
        (admittedCommand as? RemoteControl.Tap)?.requestId?.let { requestId ->
            Log.i(TAG, "remote input id=$requestId admission=${admission.name.lowercase()} " +
                "backlog=${remoteControls.pendingCount()}")
        }
        if (admission == LatestDispatcher.Admission.ACCEPTED ||
            admission == LatestDispatcher.Admission.COALESCED
        ) {
            val tap = admittedCommand as? RemoteControl.Tap
            if (tap?.completion == null || tap.requestId == null) {
                call.respondText("accepted\n", status = HttpStatusCode.Accepted)
            } else {
                // The worker owns the semantic deadline. A larger response grace prevents an ambiguous
                // HTTP timeout from racing a still-running, exactly-once input/capture operation.
                val result = withTimeoutOrNull(REMOTE_TAP_CAPTURE_RESPONSE_TIMEOUT_MS) {
                    tap.completion.await()
                }
                    ?: TapCaptureResult.CompletionUnknown()
                respondTapCapture(call, tap.requestId, result)
            }
        } else {
            call.respondText(
                "control queue busy\n",
                status = if (stopping()) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
            )
        }
    }

    private suspend fun respondTapCapture(
        call: ApplicationCall,
        requestId: Long,
        result: TapCaptureResult,
    ) {
        val outcome = when (result) {
            is TapCaptureResult.Success -> "success"
            is TapCaptureResult.TapFailed -> "tap-failed"
            is TapCaptureResult.ScreenshotFailed -> "screenshot-unavailable"
            is TapCaptureResult.CompletionUnknown -> "completion-unknown"
            TapCaptureResult.HardenedRefusal -> "remote-input-disabled"
            TapCaptureResult.Expired -> "tap-expired"
            TapCaptureResult.NotExecuted -> if (stopping()) "control-plane-stopping" else "tap-superseded"
        }
        Log.i(TAG, "remote input id=$requestId response=$outcome")
        respondTapCaptureResult(call, requestId, result, stopping()) { png ->
            withContext(Dispatchers.IO) { cacheScreenshot(png) }
        }
    }

    suspend fun admitTap(call: ApplicationCall, x: Float, y: Float, capture: Boolean) =
        respondRemoteAdmission(call, RemoteControl.Tap(x, y, capture = capture))

    suspend fun admitAction(call: ApplicationCall, action: String) =
        respondRemoteAdmission(call, RemoteControl.Action(action))

    fun closeAndJoin(timeoutMs: Long): Boolean = remoteControls.closeAndJoin(timeoutMs)

    private companion object {
        const val TAG = "ha-paneld/http"
        private const val REMOTE_TAP_QUEUE_DEADLINE_MS = 5_000L
        // The panel renderer can blank its surface briefly after input. Give it a bounded redraw
        // window before the one-shot capture; the response still has a hard overall deadline.
        private const val REMOTE_TAP_CAPTURE_SETTLE_MS = 1000L
        private const val REMOTE_SCREENSHOT_WAIT_MS = 25_000L
        private const val REMOTE_TAP_CAPTURE_TIMEOUT_MS = 45_000L
        private const val REMOTE_TAP_CAPTURE_RESPONSE_TIMEOUT_MS = 60_000L
    }
}

internal fun Route.remoteControlRoutes(
    owner: () -> RemoteControlRoutes,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
) {
    // Inject a tap at device pixel (x,y). capture=1 is the Dashboard overlay PoC's
    // combined one-tap/settled-screenshot operation; omission preserves legacy 202 admission.
    post("/input") {
        val q = receiveBoundedFormParameters(call) ?: return@post
        val x = q["x"]?.trim()?.toFloatOrNull()
        val y = q["y"]?.trim()?.toFloatOrNull()
        val capture = q["capture"]?.let(SettingValue::parseBool) ?: false
        if (x == null || y == null || !x.isFinite() || !y.isFinite() ||
            x < 0f || y < 0f || x > Int.MAX_VALUE || y > Int.MAX_VALUE
        ) {
            call.respondText("bad-coords\n", status = HttpStatusCode.BadRequest)
        } else if (q["capture"] != null && SettingValue.parseBool(q["capture"].orEmpty()) == null) {
            call.respondText("bad-capture\n", status = HttpStatusCode.BadRequest)
        } else {
            owner().admitTap(call, x, y, capture)
        }
    }
    // On-screen Controls card (software navbar) for panels with no physical nav bar.
    post("/action") {
        handleRemoteAction(
            call,
            RemoteActionRouteDependencies(
                authorizeSensitive = authorizeSensitive,
                admit = { request, action ->
                    owner().admitAction(request, action)
                },
            ),
        )
    }
}
