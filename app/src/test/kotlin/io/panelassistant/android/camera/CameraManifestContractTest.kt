package io.panelassistant.android.camera

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest half of the camera trial's privacy contract.
 *
 * The permission set is what a person reads in a listing, so it must describe the features that
 * exist and nothing more, and the foreground-service types must settle which component may actually
 * reach which piece of hardware. Two facts now hold at once, and it is worth being explicit about
 * why, because at first glance they look like they contradict each other.
 *
 * Camera video standing belongs to the dedicated camera service. Microphone standing belongs
 * to the boot-started agent that owns shared capture and combines voice/camera STREAM claims.
 * The camera service itself remains camera-only and never owns a second recorder.
 */
class CameraManifestContractTest {

    // Source-text reason: the shipped manifest's permissions, optional features and service types are the install contract.
    private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun declares(permission: String): Boolean = elements("uses-permission")
        .any { it.androidAttribute("name") == "android.permission.$permission" }

    /** Empty rather than throwing, so a missing declaration fails an assertion instead of erroring. */
    private fun serviceTypes(service: String): List<String> = elements("service")
        .firstOrNull { it.androidAttribute("name") == service }
        ?.androidAttribute("foregroundServiceType")?.split("|") ?: emptyList()

    private fun servicesDeclaringType(type: String): List<String> = elements("service")
        .filter { type in it.androidAttribute("foregroundServiceType").split("|") }
        .map { it.androidAttribute("name") }.sorted()

    @Test fun theCameraAndMicrophonePermissionsAreBothDeclared() {
        assertTrue("the camera trial needs the camera permission", declares("CAMERA"))
        assertTrue("and the camera foreground-service type", declares("FOREGROUND_SERVICE_CAMERA"))
        // Declared for the agent that owns shared capture, not for the camera trial. Which component
        // may actually use them is settled by the service types, not by this list.
        assertTrue("the shared microphone owner needs the record permission", declares("RECORD_AUDIO"))
        assertTrue("and the microphone foreground-service type", declares("FOREGROUND_SERVICE_MICROPHONE"))
    }

    @Test fun everyFeatureTheCameraPermissionImpliesIsDeclaredOptional() {
        // The CAMERA permission implies BOTH android.hardware.camera and android.hardware.camera.autofocus
        // as required hardware unless each is declared optional; either alone would make Android refuse
        // the install on every panel without one, which is most of them.
        listOf("android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.autofocus").forEach { feature ->
            assertTrue(
                "$feature must be declared optional",
                elements("uses-feature").any {
                    it.androidAttribute("name") == feature && it.androidAttribute("required") == "false"
                },
            )
        }
    }

    @Test fun eachCaptureTypeLivesOnExactlyTheOneServiceThatOwnsIt() {
        assertEquals(
            "the camera type belongs to the dedicated camera service and to nothing else, so the " +
                "boot-started agent can never inherit a type Android would refuse it",
            listOf(CAMERA_SERVICE),
            servicesDeclaringType("camera"),
        )
        assertEquals(
            "the microphone type belongs to the agent that owns shared capture and to nothing else",
            listOf(PANELD_SERVICE),
            servicesDeclaringType("microphone"),
        )
        assertEquals(
            "the camera service owns video standing; shared microphone standing stays on the agent",
            listOf("camera"),
            serviceTypes(CAMERA_SERVICE),
        )
        assertTrue(
            "the boot-started agent keeps the type its own persistent work runs under, was ${serviceTypes(PANELD_SERVICE)}",
            serviceTypes(PANELD_SERVICE).contains("specialUse"),
        )
    }

    @Test fun theCameraServiceIsDedicatedAndUnexported() {
        val services = elements("service").filter { it.androidAttribute("name") == CAMERA_SERVICE }
        assertEquals("one dedicated camera service is declared", 1, services.size)
        assertEquals("the camera service cannot be started by other apps", "false", services[0].androidAttribute("exported"))
    }

    private companion object {
        const val PANELD_SERVICE = "io.panelassistant.android.PaneldService"
        const val CAMERA_SERVICE = "io.panelassistant.android.camera.CameraForegroundService"
    }
}
