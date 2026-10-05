package io.panelassistant.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.control.NavigateController
import io.panelassistant.android.util.CompanionOperationStatus
import io.panelassistant.android.util.HelperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A privileged component start makes this Activity foreground before it sends the Companion route. */
class CompanionHomeReturnActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scope.launch {
            // The helper's durable transaction guard is authoritative; the app-local lease alone
            // cannot see a pending restore after process recovery. Keep socket and marker I/O off main.
            val safe = withContext(Dispatchers.IO) { runCatching {
                CompanionHomeReturn.mayDispatch(
                    HelperClient.companionOperationStatus(),
                    helperAvailable = HelperClient::available,
                    markerPending = { CompanionDataOperationState.from(this@CompanionHomeReturnActivity).isPending() },
                )
            }.getOrDefault(false) }
            if (resumed && safe) CompanionHomeReturn.deliver(SystemClock.elapsedRealtime()) { pkg, path ->
                if (CompanionDataOperationGate.blocks(pkg)) return@deliver false
                val route = Intent(Intent.ACTION_VIEW, Uri.parse(NavigateController.homeUrl(path)))
                    .setPackage(pkg)
                if (route.resolveActivity(packageManager) == null) return@deliver false
                runCatching { startActivity(route) }
                    .onFailure { Log.w("ha-paneld/home", "Companion home launch failed", it) }
                    .isSuccess
            }
            finish()
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Process-local, one-use payload: no dashboard route is passed through the privileged command line. */
internal object CompanionHomeReturn {
    fun mayDispatch(
        status: CompanionOperationStatus,
        helperAvailable: () -> Boolean,
        markerPending: () -> Boolean,
    ): Boolean = status == CompanionOperationStatus.IDLE ||
        (status == CompanionOperationStatus.UNAVAILABLE && !helperAvailable() && !markerPending())

    private data class Pending(
        val id: Long,
        val pkg: String,
        val path: String,
        val expiresAt: Long,
        val valid: () -> Boolean,
        val delivered: () -> Unit,
    )

    private var pending: Pending? = null
    private var nextId = 0L

    @Synchronized
    fun arm(pkg: String, path: String, now: Long, valid: () -> Boolean, delivered: () -> Unit): Long {
        val id = ++nextId
        pending = Pending(id, pkg, path, now + 10_000L, valid, delivered)
        return id
    }

    @Synchronized
    fun clear(id: Long) { if (pending?.id == id) pending = null }

    fun deliver(now: Long, launch: (String, String) -> Boolean): Boolean {
        val request = synchronized(this) { pending.also { pending = null } } ?: return false
        if (now > request.expiresAt || !request.valid() ||
            RendererResolver.companionHomeRoute(request.pkg, request.path) == null
        ) return false
        if (!launch(request.pkg, request.path)) return false
        request.delivered()
        return true
    }
}
