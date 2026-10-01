package io.github.maxlyth.hapaneld.audio

/** A feature can release its own foreground claim without revoking a sibling's live lease. */
internal class MicrophoneForegroundClaims(private val apply: (Boolean) -> Boolean) {
    private val owners = HashSet<String>()

    @Synchronized
    fun set(owner: String, active: Boolean): Boolean {
        if ((owner in owners) == active) return true
        val needed = active || owners.any { it != owner }
        if (needed != owners.isNotEmpty() && !apply(needed)) return false
        if (active) owners += owner else owners -= owner
        return true
    }
}
