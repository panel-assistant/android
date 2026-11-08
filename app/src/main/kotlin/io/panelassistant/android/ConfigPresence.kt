package io.panelassistant.android

/** Placeholder until the presence setting lane lands; replaced by its accessor at merge. */
val Config.presenceMode: PresenceMode
    get() = PresenceMode.fromWire(prefs.getString(PresenceMode.KEY, null)) ?: PresenceMode.MANUAL
