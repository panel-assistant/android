package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zigbee metadata, role, and effect-path contracts over the root seam, with no device. */
class ZigbeeControllerTest {

    private val dir = "/vendor/bin/siliconlabs_host"

    // Launcher-relevant entries captured read-only from three live NSPanel Pro gateway directories.
    private val managedFiles = setOf("run_guard_process.sh", "guard_process.sh", "run.sh", "zgateway", "mosquitto", "package_version")
    private val nativeFiles = setOf("guard_process.sh", "run.sh", "zgateway", "mosquitto")
    private val fourXFiles = setOf("run.sh", "zgateway", "mosquitto")

    private fun capturedRoot(files: Set<String>, running: Boolean = false, role: String? = null): FakeRootShell {
        val outputs = files.associate { "$dir/$it" to it } + mapOf(
            "for f in run_guard_process.sh" to files.joinToString("\n", postfix = "\n"),
            "pidof zgateway" to if (running) "1234" else "",
        ) + (role?.let { mapOf("-t zigbee/system/network-role/information -C 1" to it) } ?: emptyMap())
        return FakeRootShell(outputs)
    }

    private fun metadata(
        managed: Boolean = false,
        guard: Boolean = false,
        run: Boolean = managed || guard,
        binary: Boolean = false,
        broker: Boolean = managed || guard || run,
        running: Boolean = false,
        packageVersion: String = "",
        packageMarker: Boolean = managed,
    ): String = buildString {
        appendLine("HAPANELD_ZIGBEE_V1")
        appendLine("managed=${managed.bit()}")
        appendLine("guard=${guard.bit()}")
        appendLine("run=${run.bit()}")
        appendLine("binary=${binary.bit()}")
        appendLine("broker=${broker.bit()}")
        appendLine("package_marker=${packageMarker.bit()}")
        appendLine("running=${running.bit()}")
        appendLine("package=$packageVersion")
        appendLine("HAPANELD_ZIGBEE_END")
    }

    private fun Boolean.bit() = if (this) 1 else 0

    private fun zb(
        metadata: String? = metadata(),
        role: String? = null,
        outputs: Map<String, String> = emptyMap(),
    ): Pair<ZigbeeController, FakeRootShell> {
        val framedOutputs = buildMap {
            putAll(outputs)
            metadata?.let { put("HAPANELD_ZIGBEE_V1", it) }
            role?.let { put("network-role/information", it) }
        }
        val root = FakeRootShell(framedOutputs)
        return ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root) to root
    }

    @Test fun noProfileDirIsInertWithoutRootCommands() {
        val root = FakeRootShell()
        val z = ZigbeeController(fakeProfile(zigbeeGatewayDir = null), root)

        assertFalse(z.present())
        assertEquals("none", z.observe(includeRole = true).status)
        assertTrue(root.outputRan.isEmpty())
        assertTrue(root.isolatedOutputRan.isEmpty())
    }

    @Test fun capturedUnavailableSuRouteLeavesProfiledGatewayUnknownWithoutRootCommands() {
        val (z, root) = zb(metadata = metadata(binary = true, running = true))

        val observed = z.observe(includeRole = true, directSuReady = false)

        assertFalse(observed.probeSucceeded)
        assertFalse(observed.present)
        assertEquals("gateway · status unavailable", observed.status)
        assertTrue(root.outputRan.isEmpty())
        assertTrue(root.isolatedOutputRan.isEmpty())
    }

    @Test fun absentObservationUsesOneMetadataCommandAndSkipsRole() {
        val (z, root) = zb()

        val observed = z.observe(includeRole = true)

        assertTrue(observed.probeSucceeded)
        assertFalse(observed.present)
        assertEquals(ZigbeeGatewayLayout.UNKNOWN, observed.layout)
        assertFalse(observed.running)
        assertNull(observed.driver)
        assertNull(observed.role)
        assertEquals("none", observed.status)
        assertEquals(1, root.isolatedOutputRan.size)
        val command = root.isolatedOutputRan.single()
        assertTrue(command.contains("HAPANELD_ZIGBEE_V1"))
        assertTrue(command.contains("run_guard_process.sh"))
        assertTrue(command.contains("guard_process.sh"))
        assertTrue(command.contains("zgateway"))
        assertTrue(command.contains("pidof zgateway"))
        assertTrue(command.contains("head -c 121"))
        assertTrue(command.contains("HAPANELD_ZIGBEE_END"))
        assertTrue(root.isolatedOutputRan.none { it.contains("network-role/information") })
        assertTrue(root.outputRan.isEmpty())
    }

    @Test fun binaryOnlyIncompleteLayoutIsReportedWithoutRoleWait() {
        val (z, root) = zb(metadata = metadata(binary = true))

        val observed = z.observe(includeRole = true)

        assertTrue(observed.present)
        assertTrue(observed.probeSucceeded)
        assertEquals(ZigbeeGatewayLayout.UNKNOWN, observed.layout)
        assertFalse(observed.running)
        assertEquals("zgateway", observed.driver)
        assertNull(observed.role)
        assertEquals("gateway · unknown layout: zgateway", observed.status)
        assertEquals(1, root.isolatedOutputRan.size)
    }

    @Test fun orphanedBrokerAndPackageMarkerAreReportedAsUnknown() {
        val (z, root) = zb(metadata = metadata(broker = true, packageMarker = true))

        assertEquals("gateway · unknown layout: mosquitto, package_version", z.observe(includeRole = true).status)
        assertEquals(1, root.isolatedOutputRan.size)
    }

    @Test fun orphanedPackageMarkerDoesNotCreatePresenceOrDriver() {
        val (z, _) = zb(metadata = metadata(packageVersion = "sonoff-v3.5.4:sonoff-3.5.0"))

        val observed = z.observe(includeRole = true)

        assertFalse(observed.present)
        assertNull(observed.driver)
        assertEquals("none", observed.status)
    }

    @Test fun managedRunningLayoutUsesOneMetadataCommandAndOneRoleRead() {
        val (z, root) = zb(
            metadata = metadata(
                managed = true,
                guard = true,
                binary = true,
                running = true,
                packageVersion = "sonoff-v3.5.4:sonoff-3.5.0",
            ),
            role = """{"role":"Repeater"}""",
        )

        val observed = z.observe(includeRole = true)

        assertTrue(observed.present)
        assertEquals(ZigbeeGatewayLayout.MANAGED, observed.layout)
        assertTrue(observed.running)
        assertEquals("sonoff 3.5.0", observed.driver)
        assertEquals("Repeater", observed.role)
        assertEquals("sonoff 3.5.0 · running · Repeater", observed.status)
        assertEquals(2, root.isolatedOutputRan.size)
        assertEquals(1, root.isolatedOutputRan.count { it.contains("HAPANELD_ZIGBEE_V1") })
        assertEquals(1, root.isolatedOutputRan.count { it.contains("network-role/information") })
    }

    @Test fun vendorNativeRunningLayoutHasParityWithLegacyStatus() {
        val (z, root) = zb(
            metadata = metadata(guard = true, binary = true, running = true),
            role = """{"role":"Coordinator"}""",
        )

        assertEquals("vendor-native · running · Coordinator", z.observe(includeRole = true).status)
        assertEquals(2, root.isolatedOutputRan.size)
    }

    @Test fun vendorNativeStoppedLayoutNeverAttemptsRole() {
        val (z, root) = zb(
            metadata = metadata(guard = true, binary = true),
            role = """{"role":"Coordinator"}""",
        )

        assertEquals("vendor-native · stopped", z.observe(includeRole = true).status)
        assertEquals(1, root.isolatedOutputRan.size)
        assertTrue(root.isolatedOutputRan.none { it.contains("network-role/information") })
    }

    @Test fun capturedFourXMetadataReportsTheKnownLayout() {
        val (z, root) = zb(metadata = metadata(run = true, binary = true))

        assertEquals("vendor-native 4.x · stopped", z.observe(includeRole = true).status)
        assertEquals(1, root.isolatedOutputRan.size)
    }

    @Test fun runningProcessAloneIsAValidPresenceMarker() {
        val (z, root) = zb(
            metadata = metadata(running = true),
            role = """{"role":"Repeater"}""",
        )

        val observed = z.observe(includeRole = false)

        assertTrue(observed.present)
        assertTrue(observed.running)
        assertEquals("running zgateway", observed.driver)
        assertNull(observed.role)
        assertEquals(1, root.isolatedOutputRan.size)
    }

    @Test fun malformedOrTruncatedMetadataFailsClosedAndNeverReadsRole() {
        val malformedFrames = listOf(
            "HAPANELD_ZIGBEE_V1\nmanaged=1\n",
            metadata(binary = true).replace("managed=0", "managed=yes"),
            metadata(binary = true).replace("HAPANELD_ZIGBEE_END", "HAPANELD_ZIGBEE_TRUNCATED"),
            metadata(binary = true) + "unexpected\n",
            metadata(binary = true, packageVersion = "x".repeat(121)),
            metadata(binary = true, packageVersion = "x".repeat(2_000)),
            metadata(binary = true, packageVersion = "bad\u0000value"),
        )

        malformedFrames.forEach { frame ->
            val (z, root) = zb(metadata = frame, role = """{"role":"Repeater"}""")
            val observed = z.observe(includeRole = true)
            assertFalse("malformed frame must remain unknown, not absent", observed.probeSucceeded)
            assertFalse("frame must fail closed: ${frame.take(40)}", observed.present)
            assertEquals(ZigbeeGatewayLayout.UNKNOWN, observed.layout)
            assertFalse(observed.running)
            assertNull(observed.driver)
            assertNull(observed.role)
            assertEquals("gateway · status unavailable", observed.status)
            assertEquals(1, root.isolatedOutputRan.size)
        }
    }

    @Test fun malformedRoleFailsClosedWithoutDiscardingMetadata() {
        listOf("{", """{"role":7}""", """{"role":"bad\nrole"}""").forEach { role ->
            val (z, _) = zb(metadata = metadata(binary = true, running = true), role = role)
            val observed = z.observe(includeRole = true)
            assertTrue(observed.present)
            assertTrue(observed.running)
            assertNull(observed.role)
        }
    }

    @Test fun metadataTimeoutRemainsUnknownRatherThanAuthoritativeAbsence() {
        val (z, root) = zb(metadata = null)

        val observed = z.observe(includeRole = true)

        assertFalse(observed.probeSucceeded)
        assertFalse(observed.present)
        assertEquals("gateway · status unavailable", observed.status)
        assertEquals(1, root.isolatedOutputRan.size)
    }

    @Test fun roleTimeoutIsBoundedAndLeavesRunningMetadataIntact() {
        val root = TimeoutRoleRootShell(metadata(binary = true, running = true))
        val z = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root)

        val observed = z.observe(includeRole = true)

        assertTrue(observed.probeSucceeded)
        assertTrue(observed.present)
        assertTrue(observed.running)
        assertNull(observed.role)
        assertEquals(2, root.calls.size)
        assertEquals(1_024L, root.calls[0].maxBytes)
        assertEquals(3_500L, root.calls[0].timeoutMs)
        assertEquals(4_096L, root.calls[1].maxBytes)
        assertEquals(3_500L, root.calls[1].timeoutMs)
    }

    @Test fun publicPresenceRetainsTheCheapControlPathQuery() {
        val (z, root) = zb(
            outputs = mapOf(
                "siliconlabs_host/zgateway" to "zgateway",
            ),
        )

        assertTrue(z.present())
        assertTrue(root.outputRan.isNotEmpty())
        assertTrue(root.isolatedOutputRan.isEmpty())
    }

    @Test fun runningRetainsTheCheapEffectPathPidProbe() {
        assertTrue(zb(outputs = mapOf("pidof zgateway" to "1234")).first.running())
        assertFalse(zb(outputs = mapOf("pidof zgateway" to "")).first.running())
    }

    @Test fun capturedFourXLayoutStartsThroughItsLauncher() {
        val root = capturedRoot(fourXFiles)
        val z = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root)

        assertTrue(z.reconcile(true))
        assertTrue(root.ran.any { it.contains("sh $dir/run.sh") })
        assertTrue(root.ran.none { it.contains("sh $dir/guard_process.sh") })
    }

    @Test fun capturedLayoutsDispatchStartAndStopThroughTheirOwnMechanisms() {
        val managedRoot = capturedRoot(managedFiles)
        val managed = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), managedRoot)
        assertTrue(managed.enable())
        assertTrue(managed.disable())
        assertTrue(managedRoot.ran.any { it == "sh $dir/run_guard_process.sh" })
        assertTrue(managedRoot.ran.any { it == "sh $dir/run_guard_process.sh stop" })

        val nativeRoot = capturedRoot(nativeFiles)
        val native = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), nativeRoot)
        assertTrue(native.enable())
        assertTrue(native.disable())
        assertTrue(nativeRoot.ran.any { it.contains("nohup sh $dir/guard_process.sh") })
        assertTrue(nativeRoot.ran.any { it.contains("killall -9 zgateway") })

        val daemon = FakeDaemon(mapOf("ZIGBEECONTAIN" to "OK"))
        val fourXRoot = capturedRoot(fourXFiles)
        val fourX = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), fourXRoot, daemon)
        assertTrue(fourX.disable())
        assertEquals(listOf("ZIGBEECONTAIN"), daemon.sent)
        assertTrue(fourXRoot.ran.isEmpty())

        val source = AndroidZigbeeGatewayHealthSource(
            dir = dir,
            controller = fourX,
            root = fourXRoot,
            daemon = daemon,
            productVersion = { null },
        )
        assertEquals(ZigbeeContainmentResult.COMPLETE, source.contain(ZigbeeGatewayLayout.VENDOR_4X))
        assertEquals(ZigbeeContainmentResult.FAILED, source.contain(ZigbeeGatewayLayout.UNKNOWN))
        assertEquals(listOf("ZIGBEECONTAIN", "ZIGBEECONTAIN"), daemon.sent)
    }

    @Test fun healthSourceClassifiesTheCapturedFileLayouts() {
        listOf(
            managedFiles to ZigbeeGatewayLayout.MANAGED,
            nativeFiles to ZigbeeGatewayLayout.VENDOR_NATIVE,
            fourXFiles to ZigbeeGatewayLayout.VENDOR_4X,
            setOf("zgateway") to ZigbeeGatewayLayout.UNKNOWN,
        ).forEach { (files, expected) ->
            val root = capturedRoot(files)
            val source = AndroidZigbeeGatewayHealthSource(
                dir = dir,
                controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root),
                root = root,
                daemon = FakeDaemon(),
                productVersion = { null },
            )
            assertEquals(expected, source.observe().layout)
        }
    }

    @Test fun unidentifiedGatewayFootprintRefusesControlAndNamesWhatWasSeen() {
        // An adversarial incomplete variant of the captured 4.x footprint: the launcher is gone.
        val root = capturedRoot(setOf("zgateway"))
        val daemon = FakeDaemon(mapOf("ZIGBEECONTAIN" to "OK"))
        val z = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon)

        assertFalse(z.reconcile(true))
        assertFalse(z.disable())
        assertTrue(root.ran.isEmpty())
        assertTrue(daemon.sent.isEmpty())
        val status = zb(metadata = metadata(binary = true)).first.observe(includeRole = false).status
        assertTrue(status.contains("unknown layout"))
        assertTrue(status.contains("zgateway"))
    }

    @Test fun reconcileNeverSpawnsASecondGuardWhenAlreadyRunning() {
        val root = capturedRoot(nativeFiles, running = true)
        val z = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root)
        assertTrue(z.reconcile(true))
        assertTrue(root.ran.none { it.contains("guard_process.sh") })
        assertTrue("explicit ON must reassert Repeater mode", root.ran.any { it.contains("network-role/switch") })
    }

    @Test fun explicitOnDoesNotRepublishWhenAlreadyARepeater() {
        val root = capturedRoot(nativeFiles, running = true, role = """{"role":"Repeater"}""")
        val z = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root)
        assertTrue(z.reconcile(true))
        assertTrue(root.ran.none { it.contains("guard_process.sh") })
        assertTrue(root.ran.none { it.contains("network-role/switch") })
    }

    @Test fun roleProbeUsesIsolatedDiagnosticLaneWithoutMetadata() {
        val (z, root) = zb(role = """{"role":"Repeater"}""")

        assertEquals("Repeater", z.role())
        assertEquals(1, root.isolatedOutputRan.size)
        assertTrue(root.isolatedOutputRan.single().contains("network-role/information"))
        assertTrue(root.outputRan.none { it.contains("network-role/information") })
    }

    @Test fun healthSkipsRoleProbeWhenGatewayProcessIsAbsent() {
        val root = FakeRootShell(mapOf("for f in run_guard_process.sh" to "zgateway"))
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root)
        val source = AndroidZigbeeGatewayHealthSource(
            dir = dir,
            controller = controller,
            root = root,
            daemon = FakeDaemon(),
            productVersion = { null },
        )

        assertNull(source.observe().role)
        assertTrue(root.isolatedOutputRan.none { it.contains("network-role/information") })
    }

    private class TimeoutRoleRootShell(private val metadata: String) : RootShell {
        data class Call(val command: String, val maxBytes: Long, val timeoutMs: Long)

        val calls = mutableListOf<Call>()

        override fun available() = true
        override fun run(cmd: String) = true
        override fun runOutput(cmd: String): String? = null
        override fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long): String? {
            calls += Call(cmd, maxBytes, timeoutMs)
            return if (cmd.contains("HAPANELD_ZIGBEE_V1")) metadata else null
        }
        override fun runBytes(cmd: String): ByteArray? = null
        override fun fireAndForget(cmd: String) = true
    }
}
