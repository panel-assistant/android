package io.panelassistant.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.panelassistant.android.storage.ProcessStartWallClock
import androidx.appcompat.app.AppCompatDelegate
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.util.GuardDbProcessAdmission
import io.panelassistant.android.util.GuardDbSentinelLoad

/**
 * On Android 9- panels (no system dark/light setting — the NSPanel Pro fleet) the DayNight default is
 * set from the panel's `dark_mode` config before any activity is created, so ha-paneld's own screens
 * (admin launcher, dialogs, on-device config) follow the Display-card toggle. Android 10+ panels have
 * a real OS control and are left on FOLLOW_SYSTEM — ha-paneld never fights the system setting there.
 * Runtime changes are applied by the config POST handler; this covers process start.
 */
class HaPaneldApp : Application() {
    /** Only a process born with a valid Guard sentinel owes the one startup acknowledgment. */
    internal var guardDbFreshStartupPending = false
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // The earliest point this process runs; storage remediation proves files orphaned against it.
        val startElapsed = android.os.Process.getStartElapsedRealtime()
        ProcessStartWallClock.capture(
            System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - startElapsed),
            startElapsed,
        )
        // Installed before providers/components: every security/debug mutation publishes a durable
        // TRANSITION epoch, and maintenance successors can authenticate HARDENED without opening DB.
        RemoteDebugSecurityTransitionGate.install(base.noBackupFilesDir)
        // This is earlier than ContentProvider creation. Merely finding a valid or corrupt marker
        // closes ordinary DB/service admission until onCreate reconciles the root journal.
        GuardDbProcessAdmission.prime(base)
    }

    override fun onCreate() {
        super.onCreate()
        observeWallClockSteps()
        // Guard DB recovery returns before Config may open the protected database. Its UI and foreground
        // notification still need the last selected language, so restore it from Config's read-only 0.9.x
        // compatibility mirror before taking that early return.
        NativeLocale.applyBeforeDatabase(this)
        // A valid helper-owned transaction takes the maintenance foreground-service route before
        // status reacquisition or the one controlled pending-health database open. Ordinary owners
        // remain fenced by the sentinel until that service settles the exact helper generation.
        val guardDbAdmission = GuardDbProcessAdmission.current()
        if (guardDbAdmission !is GuardDbSentinelLoad.Absent) {
            if (guardDbAdmission is GuardDbSentinelLoad.Valid) {
                guardDbFreshStartupPending = true
                GuardDbMaintenanceService.start(this)
            }
            return
        }
        // NOTHING BELOW HERE MAY OPEN THE DATABASE, INCLUDING THROUGH A CALLEE. On an out-of-process
        // cold start — the provisioner's `am start-foreground-service` after `adb install -r`, a
        // START_STICKY re-create, or the process
        // boundary PaneldService.onDestroy re-arms — Android has already armed the
        // startForegroundService deadline before this method runs, so every millisecond spent here is
        // taken out of PaneldService's budget to reach startForeground.
        //
        // Constructing Config here used to open ha-paneld.db, which runs two full `PRAGMA quick_check`
        // scans over a database that reaches tens of megabytes, plus the namespace load. That is what
        // made Android 8.1 panels die with `RemoteServiceException: startForegroundService() did not
        // then call Service.startForeground()`. Measured on such a panel on 2026-09-18, on a *warm*
        // start with page cache hot: 2.31 s from am_proc_start to the promote. A cold post-install
        // start, where the dex is still interpreted, clears the deadline outright.
        //
        // `ui_language` is already applied above from the XML mirror, and `dark_mode` is read from that
        // same mirror. PaneldService.onCreate re-asserts both from the authoritative database, after it
        // has promoted — see reconcileNativePresentationAfterPromotion there.
        if (Build.VERSION.SDK_INT < 29) {
            AppCompatDelegate.setDefaultNightMode(
                if (darkModeBeforeDatabase(this)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
            )
        }
    }

    /**
     * A backward clock step that is undone again (a manual set, then NTP) leaves no trace for the
     * next remediation run to see, so every step is observed as it happens. ACTION_TIME_CHANGED is a
     * protected system broadcast, still delivered to a NOT_EXPORTED receiver.
     */
    private fun observeWallClockSteps() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ProcessStartWallClock.observe(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
            }
        }
        val filter = IntentFilter(Intent.ACTION_TIME_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }
    }
}
