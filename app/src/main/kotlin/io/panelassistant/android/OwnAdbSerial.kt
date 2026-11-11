package io.panelassistant.android

import java.util.concurrent.atomic.AtomicReference

/**
 * This panel's ADB serial as its own adbd announces it over mDNS (`_adb._tcp`, instance `adb-<serial>`).
 * Apps cannot read `ro.serialno` without root, so the advertiser learns it from an answer whose address is
 * one of its own, and Panel Assistant uses it to fold Android's ADB discovery card into this app's card.
 *
 * The value is derived from the instance name exactly as Panel Assistant derives it, so a serial that mDNS
 * renamed on a collision (`adb-X (2)`) fails validation and stays unknown rather than matching the wrong
 * card. Reads are a volatile load: the health line must never start a shell, root or network operation.
 */
internal class OwnAdbSerial {
    private data class Learned(val instance: String, val serial: String)

    private val learned = AtomicReference<Learned?>(null)

    val current: String? get() = learned.get()?.serial

    /** Accept [instance] (an `_adb._tcp` instance name) as this panel's; false when it carries no valid serial. */
    fun learn(instance: String): Boolean {
        val serial = serialOf(instance) ?: return false
        learned.set(Learned(instance, serial))
        return true
    }

    /** Forget the serial only when the instance it came from is removed; another panel's removal is ignored. */
    fun forget(instance: String): Boolean {
        val now = learned.get() ?: return false
        return now.instance == instance && learned.compareAndSet(now, null)
    }

    companion object {
        const val SERVICE_TYPE = "_adb._tcp.local."
        private val VALID = Regex("[A-Za-z0-9._:-]{1,128}")

        val shared = OwnAdbSerial()

        fun serialOf(instance: String): String? =
            instance.removeSuffix(".$SERVICE_TYPE").removePrefix("adb-").takeIf { VALID.matches(it) }
    }
}
