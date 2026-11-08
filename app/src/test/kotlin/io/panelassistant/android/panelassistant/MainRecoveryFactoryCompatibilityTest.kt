package io.panelassistant.android.panelassistant

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Uses the factory signature shared with the preceding release to pin the endpoint migration. */
internal class MainRecoveryFactoryCompatibilityTest : PanelRuntimeRig() {
    @Test fun factoryReadinessUsesTheAdmittedMainTransport() = runTest {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val daemon = Proxy.newProxyInstance(Daemon::class.java.classLoader, arrayOf(Daemon::class.java)) { _, method, args ->
            when (method.name) {
                "adbRecoveryExchange" -> {
                    requests += args!![0] as String
                    "READY 1 ${BuildConfig.HELPER_BUILD_ID} 0 0 0\n".toByteArray()
                }
                "supportsAdbRecovery" -> true
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as Daemon
        val context = object : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val runtime = LocalAdbRuntime.create(context, scope, newConfig(MemoryPreferences(), object : ContentResolver(null) {}), daemon)
            runtime.session("session", true)
            val ready = runtime.request("adb.ready", JSONObject(), { true }).result as JSONObject
            assertTrue("an admitted main helper must be visible through the actual runtime factory", ready.getBoolean("ready"))
            assertTrue(requests.contains("STATUS"))
        } finally { scope.cancel() }
    }
}
