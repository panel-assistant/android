package io.github.maxlyth.hapaneld.util

/**
 * Vocabulary and pure decisions for the built-in dashboard's colour-scheme policy (`dashboard_theme`).
 *
 * This is deliberately a SEPARATE authority from `dark_mode`, which keeps its existing meaning: it
 * themes ha-paneld's own native screens and supplies the dashboard's DEFAULT scheme on panels with no
 * system dark-mode setting. `dark_mode` proposes; a theme picked inside Home Assistant still wins.
 *
 * This policy answers the different question the default cannot: "whatever Home Assistant has stored,
 * show the dashboard dark (or light)". [FOLLOW] is the default and means exactly today's behaviour —
 * ha-paneld does not decide, and never writes a value it did not already write itself.
 *
 * [AMBIENT] is not a fourth mechanism. The room's light picks Dark or Light, and [effective] resolves
 * it to that choice before anything acts on it, so every consumer that follows Dark and Light follows
 * Ambient through exactly the same path. Until the room has given a verdict it resolves to [FOLLOW].
 */
object DashboardTheme {
    /** Home Assistant owns the dashboard's scheme; ha-paneld only supplies the `dark_mode` default. */
    const val FOLLOW = "Follow Home Assistant"
    const val DARK = "Dark"
    const val LIGHT = "Light"

    /** Dark or Light, chosen by the room's light through the auto-brightness ambient model. */
    const val AMBIENT = "Ambient"

    const val DEFAULT = FOLLOW

    val OPTIONS = listOf(FOLLOW, DARK, LIGHT, AMBIENT)

    /**
     * Spellings accepted in place of the declared options. The ENUM matcher is already
     * case-insensitive, so `dark`/`light` need no entry; these exist so the long [FOLLOW] label can be
     * written as one word from a script, an automation or a config bundle. Retired spellings would go
     * here too — an option is never renamed, because a restore is all-or-nothing and one unrecognised
     * value takes the whole archive down with it.
     */
    val ALIASES = mapOf(
        "follow" to FOLLOW,
        "follow_home_assistant" to FOLLOW,
        "follow-home-assistant" to FOLLOW,
        "auto" to FOLLOW,
    )

    /**
     * Canonical policy for a persisted [raw] value; unknown or blank falls back to [DEFAULT].
     *
     * This deliberately does NOT resolve [ALIASES]. Every inbound path — the config POST, import and
     * restore — runs `SettingValue.validate` first, which resolves an alias and normalises the casing
     * before anything is persisted, so what reaches here is already a declared option or a value from
     * a build that declared a different set. Repeating the alias pass here would be unreachable code
     * that no test can fail, which is worse than no guard: it reads as coverage without being any.
     */
    fun policy(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return DEFAULT
        return OPTIONS.firstOrNull { it.equals(v, ignoreCase = true) } ?: DEFAULT
    }

    /**
     * The policy the renderer acts on: [AMBIENT] becomes [DARK] or [LIGHT] from the room's verdict
     * ([ambientDark]), or [FOLLOW] while there is none or while the model that produces it is not
     * running ([ambientModelRunning] false: auto-brightness is off, which it also is on a panel with no
     * light source). Every other policy is itself. Only this value may reach [forcedDark], [forces] or
     * a renderer signature; the stored policy is for the settings surfaces alone.
     */
    fun effective(policy: String?, ambientDark: Boolean?, ambientModelRunning: Boolean): String =
        when (val p = policy(policy)) {
            AMBIENT -> when (ambientDark.takeIf { ambientModelRunning }) {
                true -> DARK
                false -> LIGHT
                null -> FOLLOW
            }
            else -> p
        }

    /**
     * The scheme this policy forces, or null when Home Assistant keeps ownership. Null is the whole
     * point of [FOLLOW]: it is not "force whatever ha-paneld thinks", it is "do not decide".
     */
    fun forcedDark(policy: String?): Boolean? = when (policy(policy)) {
        DARK -> true
        LIGHT -> false
        else -> null
    }

    /** True when [policy] takes ownership of Home Assistant's stored theme. */
    fun forces(policy: String?): Boolean = forcedDark(policy) != null
}
