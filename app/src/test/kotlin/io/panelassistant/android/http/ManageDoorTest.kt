package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.Config
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.ManageFamily
import io.panelassistant.android.ManageLogSource
import io.panelassistant.android.ManageLogSources
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageTable
import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.TouchSoundHardware
import io.panelassistant.android.control.TouchSoundState
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manage door (protocol section 20.9 checks 1, 2 and 4): named operations registered by the routes,
 * entered through the dispatcher's one entry with each request's origin, on the real dispatcher.
 */
internal class ManageDoorTest : PanelRuntimeRig() {
    private val rigs = mutableListOf<Rig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
        LocalApprovalBroker.instance.clear()
    }

    private fun managedRig(
        table: ManageTable = ManageTable(),
        families: Map<String, ManageFamily> = emptyMap(),
        rebootSucceeds: Boolean = true,
        touchSound: TouchSoundHardware? = null,
        configure: (Config) -> Unit = {},
    ): Rig = rig(
        manageTable = table,
        manageFamilies = families,
        approvalPollMs = 20,
        rebootSucceeds = rebootSucceeds,
        deviceSecure = false,
        touchSoundHardware = touchSound,
        configure = configure,
    ).also { it.start(); rigs += it }

    private fun manage(rig: Rig, commandId: String, op: String, origin: CommandOrigin, args: JSONObject = JSONObject()): ManageReply {
        val answer = LinkedBlockingQueue<ManageReply>()
        rig.commands.manage(commandId, op, args, origin) { answer.put(it) }
        return requireNotNull(answer.poll(20, TimeUnit.SECONDS)) { "$op answered" }
    }

    private fun submit(rig: Rig, channel: String, payload: String, origin: CommandOrigin): PanelAssistantCommandResult {
        val answer = LinkedBlockingQueue<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand(channel, nativeValue(channel, payload), admit = { null }), origin) { answer.put(it) }
        return requireNotNull(answer.poll(20, TimeUnit.SECONDS)) { "$channel answered" }
    }

    @Test fun aServedNameAnswersTheBodyItsRouteReturns() = testApplication {
        val table = ManageTable()
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    discoveryRoutes({ """{"peers":[{"name":"hall"}]}""" }, { """{"apps":[],"renderers":[]}""" })
                }
            }
        }
        val http = client.get("/api/v1/peers").bodyAsText()
        val rig = managedRig(table)

        val reply = manage(rig, "r1", "peers", CommandOrigin.peer("192.0.2.7"))

        assertEquals("applied", reply.outcome)
        assertNull(reply.code)
        assertEquals(JSONObject(http).toString(), (reply.result as JSONObject).toString())
        assertEquals(ContentType.Application.Json.toString(), reply.contentType)
    }

    @Test fun anUnknownNameIsRefused() {
        val rig = managedRig()

        val reply = manage(rig, "r1", "no.such.operation", CommandOrigin.panelAssistant())

        assertEquals("refused", reply.outcome)
        assertEquals("unknown_command", reply.code)
    }

    @Test fun aRepeatedCommandIdReplaysItsAnswerWithoutRunningAgain() = testApplication {
        val table = ManageTable()
        val runs = AtomicInteger()
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops { post("/count") { call.respondText("""{"run":${runs.incrementAndGet()}}""", ContentType.Application.Json) } }
                }
            }
        }
        startApplication()
        val rig = managedRig(table)
        val origin = CommandOrigin.user("a")

        val first = manage(rig, "c1", "count", origin)
        val repeat = manage(rig, "c1", "count", origin)

        assertEquals(1, runs.get())
        assertEquals((first.result as JSONObject).toString(), (repeat.result as JSONObject).toString())
        // The rule is per origin: another origin's identical id is its own command.
        manage(rig, "c1", "count", CommandOrigin.peer("192.0.2.9"))
        assertEquals(2, runs.get())
    }

    @Test fun anApprovalEarnedByOneOriginCannotBeConsumedByAnother() {
        val rig = managedRig { it.setSecurityMode(Config.SecurityMode.HARDENED) }
        val earner = CommandOrigin.user("a")

        val held = submit(rig, "reboot", "PRESS", earner) as PanelAssistantCommandResult.ApprovalPending
        assertTrue(LocalApprovalBroker.instance.approve(held.approvalId))

        assertTrue(submit(rig, "reboot", "PRESS", CommandOrigin.panelAssistant()) is PanelAssistantCommandResult.ApprovalPending)
        assertTrue(submit(rig, "reboot", "PRESS", CommandOrigin.peer("192.0.2.8")) is PanelAssistantCommandResult.ApprovalPending)
        assertEquals(PanelAssistantCommandResult.Applied, submit(rig, "reboot", "PRESS", earner))
    }

    @Test fun aFailedRebootFailsWhateverItsOrigin() {
        val rig = managedRig(rebootSucceeds = false)

        listOf(CommandOrigin.panelAssistant(), CommandOrigin.user("a"), CommandOrigin.peer("192.0.2.7")).forEach { origin ->
            val result = submit(rig, "reboot", "PRESS", origin)
            assertEquals(origin.approvalKey, PanelAssistantCommandResult.Failed("hardware_unavailable"), result)
        }
    }

    @Test fun anHttpOffQueuedBehindAPanelAssistantOnLeavesOnStoredAndActuated() {
        val hardware = RecordingTouchSound()
        val rig = managedRig(touchSound = hardware)
        // Hold the ordered worker so both writes are queued together.
        rig.sysfs.blockNextWrite("$RELAY_PATH/relay1")
        val held = thread { submit(rig, "relay1", "ON", CommandOrigin.panelAssistant()) }
        assertTrue(rig.sysfs.blockEntered.await(10, TimeUnit.SECONDS))

        val http = AtomicReference<LiveSettingApplyResult>()
        val httpWrite = thread { http.set(rig.commands.applySetting("touch_sound", "false")) }
        awaitQueued(rig, 2)
        // Admitted on this thread, so the Panel Assistant ON is queued before the worker is released.
        val native = LinkedBlockingQueue<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand("touch_sound", nativeValue("touch_sound", "ON"), admit = { null }), CommandOrigin.panelAssistant()) { native.put(it) }
        rig.sysfs.blockRelease.countDown()
        listOf(held, httpWrite).forEach { it.join(20_000) }

        assertEquals(PanelAssistantCommandResult.Applied, native.poll(20, TimeUnit.SECONDS))
        // The HTTP value conflated under the same channel key and never ran; nothing is left to replay.
        assertEquals(LiveSettingApplyResult.APPLIED, http.get())
        assertEquals(listOf("on"), hardware.transitions)
        assertEquals(true, rig.config.touchSoundIntent)
    }

    @Test fun aSubscriptionSendsTheCurrentValueThenOneLaterUpdateForChangesInsideTheFloor() = testApplication {
        val table = ManageTable()
        val value = AtomicInteger(1)
        // The read of 4 is held until the subscription has closed, so a read in flight at close is what is tested.
        val readingFour = CountDownLatch(1)
        val closed = CountDownLatch(1)
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops {
                        get("/probe") {
                            val read = value.get()
                            if (read == 4) {
                                readingFour.countDown()
                                closed.await(10, TimeUnit.SECONDS)
                            }
                            call.respondText("""{"v":$read}""", ContentType.Application.Json)
                        }
                    }
                }
            }
        }
        startApplication()
        val rig = managedRig(table, families = mapOf("probe" to ManageFamily("probe", FLOOR_MS, listOf("probe"))))
        val updates = LinkedBlockingQueue<String>()
        val subscription = requireNotNull(
            rig.commands.subscribe("probe", CommandOrigin.user("a")) { update, _, delivered ->
                updates.put(update.toString()); delivered()
            },
        )

        assertEquals("""{"v":1}""", updates.poll(5, TimeUnit.SECONDS))
        value.set(2)
        value.set(3)
        assertEquals("""{"v":3}""", updates.poll(5, TimeUnit.SECONDS))
        assertNull("an unchanged value sends nothing", updates.poll(FLOOR_MS * 3, TimeUnit.MILLISECONDS))

        value.set(4)
        assertTrue(readingFour.await(10, TimeUnit.SECONDS))
        subscription.close()
        closed.countDown()
        assertNull("a closed subscription sends nothing", updates.poll(FLOOR_MS * 3, TimeUnit.MILLISECONDS))
    }

    @Test fun theLogsFamilySendsItsBacklogThenOnlyNewLines() {
        val follower = AtomicReference<(String) -> Unit>()
        val followed = CountDownLatch(1)
        val table = ManageTable().apply {
            logSources = ManageLogSources { source ->
                if (source != "app") null else object : ManageLogSource {
                    override fun backlog() = listOf("one", "two")
                    override fun follow(onLine: (String) -> Unit): AutoCloseable {
                        follower.set(onLine); followed.countDown()
                        return AutoCloseable { follower.set(null) }
                    }
                }
            }
        }
        val rig = managedRig(table, families = mapOf("logs" to ManageFamily("logs", FLOOR_MS, emptyList(), append = true)))
        val updates = LinkedBlockingQueue<String>()
        rig.commands.subscribe("logs", CommandOrigin.peer("192.0.2.7")) { update, _, delivered ->
            updates.put(update.toString()); delivered()
        }

        assertEquals(JSONArray(listOf("one", "two")).toString(), updates.poll(5, TimeUnit.SECONDS))
        assertTrue(followed.await(5, TimeUnit.SECONDS))
        follower.get()("three")
        follower.get()("four")
        assertEquals(JSONArray(listOf("three", "four")).toString(), updates.poll(5, TimeUnit.SECONDS))
    }

    @Test fun aSecondLogViewerOfOneOwnerAtTheViewerLimitStillFollows() {
        // One viewer at a time, and a closing viewer takes a moment to let go of its place.
        val held = java.util.concurrent.atomic.AtomicBoolean(false)
        val table = ManageTable().apply {
            logSources = ManageLogSources { _ ->
                object : ManageLogSource {
                    override fun backlog() = listOf("one")
                    override fun follow(onLine: (String) -> Unit): AutoCloseable? {
                        if (!held.compareAndSet(false, true)) return null
                        return AutoCloseable { Thread.sleep(200); held.set(false) }
                    }
                }
            }
        }
        val rig = managedRig(table, families = mapOf("logs" to ManageFamily("logs", FLOOR_MS, emptyList(), append = true)))
        val origin = CommandOrigin.peer("192.0.2.7")
        val first = LinkedBlockingQueue<String>()
        val second = LinkedBlockingQueue<String>()
        rig.commands.subscribe("logs", origin) { update, known, delivered -> first.put("$known $update"); delivered() }
        assertEquals("true [\"one\"]", first.poll(5, TimeUnit.SECONDS))
        rig.commands.subscribe("logs", origin) { update, known, delivered -> second.put("$known $update"); delivered() }

        assertEquals("the second viewer follows once the first has let go", "true [\"one\"]", second.poll(5, TimeUnit.SECONDS))
    }

    /** The production mount enters every page-called route under its section 20.6 name, each name once. */
    @Test fun theProductionMountRegistersThePageCalledNames() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                assertEquals(PAGE_CALLED_NAMES.sorted(), fixture.manageTable.names().toList())
            }
        }
    }

    private fun awaitQueued(rig: Rig, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val queue = rig.commands.javaClass.getDeclaredField("queue").apply { isAccessible = true }.get(rig.commands)
            val pending = queue.javaClass.getMethod("pendingCount").invoke(queue) as Int
            if (pending >= count - 1) return
            Thread.sleep(5)
        }
        error("commands were not queued")
    }

    private class RecordingTouchSound : TouchSoundHardware {
        val transitions = CopyOnWriteArrayList<String>()
        override fun capture() = TouchSoundState(0)
        override fun enable(): ControlApplyOutcome { transitions += "on"; return ControlApplyOutcome.APPLIED }
        override fun restore(state: TouchSoundState): ControlApplyOutcome { transitions += "off"; return ControlApplyOutcome.APPLIED }
        override fun disableConservatively(): ControlApplyOutcome { transitions += "off"; return ControlApplyOutcome.APPLIED }
    }

    private companion object {
        val PAGE_CALLED_NAMES = listOf(
            "page.info", "page.configure", "page.setup", "page.profiles", "page.install", "page.logs", "page.entities",
            "health", "info", "settings", "diag", "status", "peers", "apps", "radio", "radio.join",
            "setup", "setup.attest", "setup.identity", "setup.home_dashboard", "setup.entity_filter",
            "config", "config.values", "config.schema", "config.discovery", "config.home_dashboards", "config.ha_area",
            "config.export", "config.import", "ha.oauth.status", "ha.oauth.start", "ha.oauth.panel_start",
            "install.component", "install.apk", "install.apk.from_url", "install.apk.fetch.cancel",
            "install.apk.discard", "install.apk.pending", "install.apk.commit", "install.apk.allow",
            "install.versions", "install.status", "backup", "updates.ignore", "packages", "uninstall",
            "dashboard.clear_storage", "companion.repair_url", "tame", "tame.record", "tame.suggest",
            "hand_back_home", "display", "display.density",
            "dashboard.entity_filter", "dashboard.entity_filter.set", "dashboard.entities",
            "dashboard.entities.sync", "dashboard.entities.sync.set", "dashboard.entities.issues",
            "dashboard.entities.issues.set", "dashboard.entities.activate", "dashboard.entities.policy",
            "dashboard.entities.override", "dashboard.entities.overrides", "dashboard.entities.reset",
            "dashboard.entities.export", "power_safety", "power_safety.repair", "power_safety.acknowledge",
            "perf", "sensors", "proximity", "proximity.calibration", "camera.status", "screenshot", "input", "action", "action.dashboard",
            "inspect", "inspect.start", "inspect.stop",
            "auto_brightness", "auto_brightness.history", "auto_brightness.sources", "auto_brightness.source",
            "auto_brightness.reset", "auto_brightness.resume",
            "auto_sleep", "auto_sleep.history", "auto_sleep.prerequisite", "auto_sleep.source",
            "voice.pipelines", "voice.microphone", "voice.wake_words", "voice.wake_words.set", "restore",
        )
        const val FLOOR_MS = 300L
        const val RELAY_PATH = "/sys/class/strelay"
    }
}
