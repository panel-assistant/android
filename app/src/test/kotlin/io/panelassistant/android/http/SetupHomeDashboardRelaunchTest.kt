package io.panelassistant.android.http

import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.panelassistant.android.Config
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.setupEntityFilterAnswered
import io.panelassistant.android.setupEverCompleted
import io.panelassistant.android.haUrl
import io.panelassistant.android.setHaConnection
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Use this dashboard" in the panel's own setup wizard saves the home dashboard through `POST /config`, then
 * records the answer and, on a panel Panel Assistant handed over, turns filtering on and answers the
 * entity-filter question, each as a further request from the wizard's page. The built-in renderer is
 * singleTask, so a relaunch of it clears the wizard above it. On the bench Shelly X2i (2026-11-09 and
 * 2026-11-11) the home save relaunched the renderer, the wizard was destroyed half-way through that chain,
 * and setup stopped with filtering on, the question unanswered and no way back on screen.
 *
 * While that question holds the renderer before its first load, the home save brings nothing forward: the
 * answer releases the renderer itself. Once setup has finished, a new home still brings the dashboard up.
 *
 * Driven through the production `POST /api/v1/config` route; the dashboard starts are what Android was asked.
 */
class SetupHomeDashboardRelaunchTest {
    private fun postHome(prepare: (Config) -> Unit): FakeSystemEnv {
        val env = FakeSystemEnv()
        withRouteConfig { config, _, server, _ ->
            val home = requireNotNull(SettingsRegistry.spec("home_dashboard"))
            config.applyBatch {
                config.setDashboardPackage(SystemController.BUILTIN_DASHBOARD)
                config.setHaConnection("http://ha.test", "signed-in-token")
            }
            prepare(config)
            setField(server, "system", SystemController(env))
            setField(server, "rendererPreparation", RendererPreparationCoordinator(
                builtinPackage = SystemController.BUILTIN_DASHBOARD,
                state = { RendererPreparationState(SystemController.BUILTIN_DASHBOARD, config.haUrl) },
                borrow = { null },
                persist = { true },
            ))
            // The live home handler stores the route, as on a panel.
            setField(server, "applySetting", { key: String, value: String ->
                if (key == home.key) config.setRaw(home, value)
                LiveSettingRequestOutcome.APPLIED
            })
            testApplication {
                application {
                    routing { route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } } }
                }
                val saved = client.submitForm("/api/v1/config", Parameters.build {
                    append(home.key, "/home")
                }) { accept(ContentType.Application.Json) }
                assertEquals(saved.bodyAsText(), HttpStatusCode.OK, saved.status)
                assertEquals("/home", config.getRaw(home))
            }
        }
        return env
    }

    @Test fun `a home dashboard chosen while the setup question is open leaves the wizard on screen`() {
        val env = postHome { }
        assertEquals(
            "nothing renders before the answer, so nothing may come over the wizard",
            emptyList<String>(), env.directStarts,
        )
        assertEquals(0, env.homeStarts)
    }

    @Test fun `a home dashboard changed after setup still brings the dashboard up`() {
        val env = postHome { config ->
            config.setupEntityFilterAnswered = true
            config.setupEverCompleted = true
        }
        assertEquals("the new home is shown now", 1, env.directStarts.size + env.homeStarts)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
