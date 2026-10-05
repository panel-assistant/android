package io.panelassistant.android.util

import org.json.JSONObject

/**
 * Bounded projection of this panel's own device identity, for the Panel Assistant integration's
 * Home Assistant device card.
 *
 * Reading it changes nothing: no release lookup, no MQTT traffic, no settings write. Every field is
 * presentation text, so anything blank, over-long or carrying control characters is represented as
 * absence rather than guessed or truncated.
 *
 * The Android id is deliberately omitted. The mDNS `did` token identifies the app installation, and the integration registers its own
 * Home Assistant device rather than adopting the MQTT bridge's identifiers, so it has no need of
 * MQTT's `serial_number` to match on.
 */
object PanelAssistantDevice {
    /** `DeviceProfile` publishes the model with this suffix to mark the app, not the hardware. */
    const val APP_MODEL_SUFFIX = " (ha-paneld)"

    private const val MAX_FIELD_LENGTH = 128

    private fun isNonPresentationCodePoint(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.UNASSIGNED.toInt(),
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }

    /** Bounded, printable, single-line presentation text, or absence. */
    internal fun field(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_FIELD_LENGTH) return null
        if (trimmed.codePoints().anyMatch(::isNonPresentationCodePoint)) return null
        return trimmed
    }

    /** The hardware model alone; the advertised value carries the app marker. */
    internal fun hardwareModel(advertised: String?): String? =
        field(advertised?.trimEnd()?.removeSuffix(APP_MODEL_SUFFIX))

    /**
     * The additive `panel_assistant_device` status object. Always a JSON object, possibly empty;
     * a field the panel cannot state safely is left out instead of being sent blank.
     *
     * No `hw_version` or serial: those travel in [hardwareJson], because the integration refuses an
     * unknown key here and fails the whole status.
     */
    fun json(
        friendlyName: String?,
        manufacturer: String?,
        model: String?,
        area: String?,
    ): String {
        val entries = buildList {
            field(friendlyName)?.let { add("\"name\":${JSONObject.quote(it)}") }
            field(manufacturer)?.let { add("\"manufacturer\":${JSONObject.quote(it)}") }
            hardwareModel(model)?.let { add("\"model\":${JSONObject.quote(it)}") }
            field(area)?.let { add("\"area\":${JSONObject.quote(it)}") }
        }
        return "{${entries.joinToString(",")}}"
    }

    /**
     * The additive `panel_assistant_hardware` status object: facts for the card's Hardware and Serial
     * number lines. A sibling of the device object, because every integration discards an unknown
     * top-level object, so no release has to learn it first.
     *
     * The firmware is the vendor's own number (`Build.DISPLAY`), sent verbatim so it can be matched
     * against the vendor's release history. The serial is the hardware serial where this panel can read
     * it, else the Android id, which panels cloned from one factory image can share.
     */
    fun hardwareJson(
        firmware: String?,
        androidRelease: String?,
        hardwareSerial: String?,
        androidId: String?,
    ): String {
        val entries = buildList {
            field(firmware)?.let { add("\"firmware\":${JSONObject.quote(it)}") }
            field(androidRelease)?.let { add("\"android_release\":${JSONObject.quote(it)}") }
            (field(hardwareSerial) ?: field(androidId))?.let { add("\"serial_number\":${JSONObject.quote(it)}") }
        }
        return "{${entries.joinToString(",")}}"
    }
}
