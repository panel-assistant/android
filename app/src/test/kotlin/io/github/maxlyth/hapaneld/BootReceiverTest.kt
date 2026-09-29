package io.github.maxlyth.hapaneld

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class BootReceiverTest {
    @Test fun packageReplacementWarmsOreoBeforeRequestingTheForegroundService() {
        for (sdk in 26..27) {
            val effects = mutableListOf<String>()
            dispatchStartup(
                Intent.ACTION_MY_PACKAGE_REPLACED, sdk,
                startService = { effects += "service" },
                openLauncher = { effects += "launcher" },
            )
            assertEquals("API $sdk", listOf("launcher"), effects)
        }
    }

    @Test fun bootAndNewerPackageReplacementKeepTheirServiceRoute() {
        for ((action, sdk) in listOf(Intent.ACTION_BOOT_COMPLETED to 27, Intent.ACTION_MY_PACKAGE_REPLACED to 28)) {
            val effects = mutableListOf<String>()
            dispatchStartup(action, sdk, { effects += "service" }, { effects += "launcher" })
            assertEquals(listOf("service"), effects)
        }
    }
}
