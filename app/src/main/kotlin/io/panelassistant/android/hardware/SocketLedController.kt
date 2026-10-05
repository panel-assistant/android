package io.panelassistant.android.hardware

import android.util.Log
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.util.HelperClient

/**
 * LED adapter for panels whose LED the app can't drive directly — either a root-only sysfs node
 * (e.g. Tuya TPA10) or a `/dev/ledjni` ioctl the app is SELinux-denied (e.g. ZHICAI SMT1019). A
 * sandboxed app (`untrusted_app`) can't write the sysfs node nor issue that ioctl nor exec `su`, so
 * the root helper daemon ([helper/hapaneld-helper](../../../../../../../helper/README.md)) does it; this
 * controller talks to it via [HelperClient]. The daemon auto-detects which backend the panel has, so
 * the `RGB`/`OFF` commands are identical either way. Full RGB.
 *
 * [available] asks the daemon which backend it found (`LEDPROBE`) so the LED entity is published only
 * when a *reachable* LED node exists — not merely because a daemon is running. Calls are blocking
 * socket I/O — the bridge invokes them off the main thread.
 */
class SocketLedController(
    private val daemon: Daemon = HelperClient,
    private val transfer: LedTransfer = LedTransfer.Identity,
) : LedController {

    // Gate on a reachable LED node, not just "daemon up". An OLD daemon doesn't know LEDPROBE and
    // replies "ERR" → fall back to PING so already-deployed panels keep their LED (backward-compatible);
    // a new daemon that reports "none" (installed for control only, no LED) correctly yields no entity.
    override fun available(): Boolean = when (daemon.send("LEDPROBE")) {
        "ledjni", "sysfs" -> true
        "none" -> false
        else -> daemon.available()
    }

    override fun colorCapable(): Boolean = true

    override fun setRgb(r: Int, g: Int, b: Int): Boolean {
        val ok = daemon.send("RGB ${clamp(transfer.red(r))} ${clamp(transfer.green(g))} ${clamp(transfer.blue(b))}") == "OK"
        if (!ok) Log.w(TAG, "setRgb failed")
        return ok
    }

    override fun off(): Boolean {
        val ok = daemon.send("OFF") == "OK"
        if (!ok) Log.w(TAG, "off failed")
        return ok
    }

    private fun clamp(v: Int) = v.coerceIn(0, 255)

    companion object {
        private const val TAG = "ha-paneld/led-socket"
    }
}
