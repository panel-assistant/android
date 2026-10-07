package io.panelassistant.android.upgrade

import io.panelassistant.android.testsupport.TestSources
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class UpgradeReceiverManifestContractTest {
    // Source-text reason: parses the shipped AndroidManifest.xml as input; the receiver's permission is
    // the only thing stopping any installed app from stopping the service mid-upgrade.
    @Test fun upgradeControlReceiverIsDumpProtectedAndAcceptsRenewal() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(TestSources.appFile("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val receivers = manifest.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.getAttributeNS(android, "name").endsWith(".UpgradeControlReceiver") }

        assertEquals("android.permission.DUMP", receiver.getAttributeNS(android, "permission"))
        val actions = receiver.getElementsByTagName("action")
        val names = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(android, "name") }
        assertTrue("renewal must reach the receiver: $names", names.any { it.endsWith(".action.RENEW_UPGRADE") })
        // The manifest and the receiver's dispatch name the same three actions, under the app's own id.
        assertEquals(listOf(PREPARE_UPGRADE_ACTION, RELEASE_UPGRADE_ACTION, RENEW_UPGRADE_ACTION), names)
        assertTrue(names.all { it.startsWith("io.panelassistant.android.action.") })
    }
}
