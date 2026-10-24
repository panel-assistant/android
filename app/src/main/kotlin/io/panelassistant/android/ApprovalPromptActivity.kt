package io.panelassistant.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.PendingApproval

/**
 * Raises each approval the broker starts waiting for on the panel's own screen: wakes the display, then brings
 * [ApprovalPromptActivity] up over whatever is showing. Approvals used to be pull-only (Settings, then the menu,
 * Security mode, Review approvals), so a sidebar restore on a Hardened panel waited unseen until it expired.
 * [post] moves the work off the requester's thread: the broker tells this from inside a request.
 */
internal class ApprovalPrompt(
    private val broker: ApprovalBroker,
    private val wake: () -> Unit,
    private val launch: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val listener: (PendingApproval) -> Unit = { approval -> post { raise(approval) } }

    init {
        broker.onRequested = listener
    }

    private fun raise(approval: PendingApproval) {
        // Decided, withdrawn or expired before this ran: nothing is waiting for the screen.
        if (broker.state(approval.id) != ApprovalBroker.State.PENDING) return
        runCatching(wake).onFailure { Log.w(TAG, "approval prompt: screen wake failed", it) }
        val raised = runCatching(launch).getOrDefault(false)
        Log.i(TAG, "approval prompt ${if (raised) "raised" else "could not be raised"}: ${approval.operation.name} ${approval.id}")
    }

    override fun close() {
        if (broker.onRequested === listener) broker.onRequested = null
    }

    private companion object {
        const val TAG = "ha-paneld/approval"
    }
}

/**
 * The on-screen approval: the oldest pending request as a Notice card with Deny and Approve, then the next, until
 * none waits. A request decided in Settings, withdrawn by its sender or expired leaves the card within a second.
 * Back leaves the request pending, still listed in Settings. Its own task, so finishing returns to whatever was
 * showing rather than to this app's dashboard.
 */
class ApprovalPromptActivity : Activity() {
    private val broker get() = LocalApprovalBroker.instance
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private var shown: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            show()
            if (!isFinishing) handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        host = FrameLayout(this)
        setContentView(host)
    }

    // A locked kiosk pulls the dashboard back over anything but this app's own admin screens.
    override fun onStart() {
        super.onStart()
        KioskAdminUi.setVisible(this, true)
    }

    override fun onStop() {
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun show() {
        val next = broker.pending().firstOrNull()
        if (next == null) {
            finish()
            return
        }
        if (next.id == shown) return
        shown = next.id
        val dark = runCatching { Config(this).dashboardThemeDark }.getOrNull() ?: true
        val notice = Notice(this, dark, NoticeForm.OVERLAY, surfaceAlpha = 0xFF)
        fun decide(approve: Boolean) {
            if (approve) broker.approve(next.id) else broker.deny(next.id)
            show()
        }
        // A tap another app's window obscures never decides: an approval is the one thing worth tapjacking.
        fun Button.guarded() = apply { filterTouchesWhenObscured = true }
        notice.setPill(getString(R.string.pending_approvals))
        notice.setBody(
            notice.sentence(localizedLabel(next.operation)),
            notice.footer(getString(R.string.approval_request_detail, next.summary, next.peer)),
            notice.actions(
                notice.action(getString(R.string.deny)) { decide(false) }.guarded(),
                notice.action(getString(R.string.approve), primary = true) { decide(true) }.guarded(),
            ),
        )
        host.removeAllViews()
        host.addView(notice.view, FrameLayout.LayoutParams(notice.widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private companion object {
        const val REFRESH_MS = 1_000L
    }
}
