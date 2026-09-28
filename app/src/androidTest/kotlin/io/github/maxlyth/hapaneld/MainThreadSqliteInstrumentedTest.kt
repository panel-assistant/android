package io.github.maxlyth.hapaneld

import android.app.Activity
import android.os.StrictMode
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run each method in a fresh instrumentation process so AppState's namespace cache starts cold. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28) // penaltyListener supplies test-owned evidence from API 28 onward.
class MainThreadSqliteInstrumentedTest {
    @Test fun coldAdminLauncher() = checkActivity(AdminLauncherActivity::class.java)
    @Test fun coldMain() = checkActivity(MainActivity::class.java)
    @Test fun coldDashboard() = checkActivity(DashboardActivity::class.java)
    @Test fun coldConfigure() = checkActivity(ConfigActivity::class.java)
    @Test fun coldProximityWizard() = checkActivity(ProximityWizardActivity::class.java)

    private fun checkActivity(activityClass: Class<out Activity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = File.createTempFile("strictmode-read-", ".txt", context.cacheDir)
        probe.writeText("probe")
        val violations = CopyOnWriteArrayList<Throwable>()
        var previous: StrictMode.ThreadPolicy? = null
        try {
            instrumentation.runOnMainSync {
                previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(previous)
                        .detectDiskReads()
                        .penaltyListener(Executor { it.run() }) { violations.add(it) }
                        .build(),
                )
                probe.inputStream().use { it.read() }
            }
            for (attempt in 0 until 20) {
                if (violations.isNotEmpty()) break
                Thread.sleep(50)
            }
            assertTrue("StrictMode did not detect the deliberate disk read", violations.isNotEmpty())
            violations.clear()
            ActivityScenario.launch(activityClass).use {
                Thread.sleep(2_000)
                instrumentation.waitForIdleSync()
                val sqliteReads = violations.filter { violation ->
                    violation.stackTrace.any { frame ->
                        frame.className.startsWith("android.database.sqlite.") ||
                            frame.className.contains("SqliteNamespacePersistence") ||
                            frame.className.contains("EntityCatalogStore")
                    }
                }
                assertTrue(
                    "Main-thread SQLite reads in ${activityClass.simpleName}:\n" +
                        sqliteReads.joinToString("\n\n") { it.stackTraceToString() },
                    sqliteReads.isEmpty(),
                )
            }
        } finally {
            instrumentation.runOnMainSync { previous?.let(StrictMode::setThreadPolicy) }
            probe.delete()
        }
    }
}
