package io.panelassistant.android.sensors

/** Attended 2026-11-08 captures: HTTP snapshots of raw proximity plus touch times, relative milliseconds. */
internal data class ProximityTouchReplay(val samples: List<Pair<Long, Float>>, val touches: List<Long>) {
    /** Events in time order; a touch sorts before a sample at the same millisecond. */
    fun events(): List<Pair<Long, Float?>> =
        (samples.map { it.first to it.second as Float? } + touches.map { it to null })
            .sortedWith(compareBy<Pair<Long, Float?>> { it.first }.thenBy { it.second != null })

    companion object {
        fun load(name: String): ProximityTouchReplay {
            val text = checkNotNull(ProximityTouchReplay::class.java.classLoader!!.getResource("proximity-touch/$name")) { name }.readText()
            val samples = mutableListOf<Pair<Long, Float>>()
            val touches = mutableListOf<Long>()
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split(',')
                when (parts[0]) {
                    "s" -> samples.add(parts[1].toLong() to parts[2].toFloat())
                    "t" -> touches.add(parts[1].toLong())
                }
            }
            return ProximityTouchReplay(samples, touches)
        }
    }
}
