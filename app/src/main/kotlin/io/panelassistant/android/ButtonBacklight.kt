package io.panelassistant.android

import io.panelassistant.android.hardware.TransferCurve

/**
 * The published button-backlight state for the last commanded [level]. A panel that has never been
 * commanded (-1) reports OFF: the bridge sends nothing to the node until the first command, the TPA10's
 * node starts at 0, and an `unknown` here showed the entity as Unavailable on a connected panel.
 */
internal fun buttonBacklightState(level: Int): String =
    if (level <= 0) """{"state":"OFF"}""" else """{"state":"ON","brightness":$level}"""

/**
 * The helper `BTN` command for a key-backlight [level] on the Home Assistant scale. The node takes the level
 * raw, so the profile [curve] is applied here; the state published back stays [level] itself.
 */
internal fun buttonBacklightCommand(level: Int, curve: TransferCurve): String =
    "BTN ${curve.toHardware(level.coerceIn(0, 255))}"
