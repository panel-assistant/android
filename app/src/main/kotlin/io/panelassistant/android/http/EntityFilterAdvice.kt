package io.panelassistant.android.http

import io.panelassistant.android.device.profile.ProfileSoc

/**
 * Should this panel filter the Home Assistant entity stream, and how confident may we sound saying so?
 *
 * The setup wizard asks this once, before the built-in renderer's first load, because an unfiltered first
 * render on a weak panel is slow and laggy and that is the first thing a new owner ever sees. To ask it
 * well the question needs two facts: how many entities Home Assistant would actually send, and how much
 * panel there is to receive them.
 *
 * Both already exist. The count comes from the catalog scan's own row counter. The hardware comes from the
 * `soc` block every device profile already declares, and for a panel with no profile from the Android API
 * level and core count that [PanelInfo] already reads. Nothing here probes or benchmarks anything: a
 * benchmark during setup would spend the user's first thirty seconds measuring instead of helping, and
 * would measure a device that is busy doing setup.
 *
 * The property that matters most here is **[Confidence]**. We have exactly two measured points, both on the
 * weakest tier, so most cells in the band table are inference from architecture and count. A recommendation
 * that cannot tell the user which it is would be a number we made up wearing the clothes of a measurement.
 * So the verdict always carries how it was reached, [Level.RED] is only claimed where evidence or a close
 * analogue supports it, and a tier we have never seen drown does not get a red band at all.
 *
 * Pure — no Android imports, unit-tested in EntityFilterAdviceTest.
 */
object EntityFilterAdvice {
    /** How much panel there is. Ordered weakest-first so comparisons read naturally. */
    enum class Tier {
        /** Cortex-A35 / A53 class. The measured tier: 4× A35 at 3,769 entities saturated the renderer. */
        MODEST,

        /** Cortex-A55 class. */
        MIDDLING,

        /** Cortex-A72 and better. */
        CAPABLE,

        /** Nothing to go on — neither a declared SoC nor a usable platform reading. */
        UNKNOWN,
    }

    /** Where the tier came from, so the UI can say "this panel" or hedge to "a panel like this". */
    enum class TierSource {
        /** The device profile's declared `soc` block: the strongest, and exact for a supported panel. */
        DECLARED_SOC,

        /** Android API level and core count. Agrees with the declared tier on every tested panel. */
        PLATFORM_INFERRED,

        NONE,
    }

    enum class Level { GREEN, AMBER, RED }

    enum class Confidence {
        /** A real measurement on this tier at about this count.  */
        MEASURED,

        /** Inference from architecture and count. Must be labelled as such to the user. */
        ESTIMATED,

        /** The scan has not finished, so this verdict is provisional and may still move. */
        COUNTING,
    }

    /**
     * @param recommendAbove entity count at or above which filtering is worth turning on.
     * @param struggleAbove entity count at or above which the panel will visibly struggle, or null where we
     *   have no evidence any count does that to this tier. Null is a deliberate refusal, not a gap to fill:
     *   inventing it would make [Confidence.MEASURED] meaningless everywhere else.
     */
    data class Bands(val recommendAbove: Int, val struggleAbove: Int?)

    /**
     * Bands per tier. MODEST's ceiling and CAPABLE's recommendation are policy choices; MIDDLING is
     * interpolated between them; MODEST's ceiling is also the one the attended measurement supports, where a
     * 4× A35 panel at 3,769 entities ran the renderer at 94% p95 and answered touches ~60% slower.
     */
    private val BANDS = mapOf(
        Tier.MODEST to Bands(recommendAbove = 500, struggleAbove = 1_000),
        Tier.MIDDLING to Bands(recommendAbove = 1_000, struggleAbove = 1_500),
        // No struggle band: we have never measured a capable panel drowning, so we do not claim it.
        Tier.CAPABLE to Bands(recommendAbove = 2_000, struggleAbove = null),
        // An unknown panel is assumed middling and asked to filter. Filtering when it was not needed costs
        // almost nothing; not filtering a weak panel costs exactly the bad first impression this exists to
        // prevent. So the safe default is the recommendation, not the reassurance.
        Tier.UNKNOWN to Bands(recommendAbove = 1_000, struggleAbove = null),
    )

    data class Verdict(
        val tier: Tier,
        val tierSource: TierSource,
        val level: Level,
        val confidence: Confidence,
        val bands: Bands,
        /** Entities counted so far; equals the final total once [counting] is false. */
        val entityCount: Int,
        val counting: Boolean,
    )

    /**
     * Rank a declared SoC by its **best** core cluster: a big.LITTLE part is as fast as its big cores for the
     * single-threaded render work that actually saturates here, so taking the weakest cluster would file
     * RK3576 (A72 + A53) alongside a plain A53 panel and understate it badly.
     */
    fun tierOfSoc(soc: ProfileSoc?): Tier {
        val ranked = soc?.cpuCores.orEmpty().mapNotNull { tierOfArchitecture(it.architecture) }
        return ranked.maxByOrNull { it.ordinal } ?: Tier.UNKNOWN
    }

    /**
     * Map an architecture string such as `"Arm Cortex-A55"` to a tier by its Cortex-A number. Parsed rather
     * than matched against a fixed list so a new profile naming a core we have never seen still lands
     * somewhere sensible instead of silently becoming UNKNOWN.
     */
    internal fun tierOfArchitecture(architecture: String): Tier? {
        val n = Regex("""[Cc]ortex[-\s]?[Aa](\d{2})""").find(architecture)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            n >= 70 -> Tier.CAPABLE      // A72/A73/A75/A76… — the only tier with headroom
            n >= 55 -> Tier.MIDDLING     // A55
            else -> Tier.MODEST          // A35, A53
        }
    }

    /**
     * Tier a panel with no declared SoC. Android API level tracks how old the silicon is and core count
     * separates the small parts from the large ones; on every tested panel these two agree with the
     * declared tier — API 27 / 4 cores modest, API 30 / 4 cores middling, API 34 / 8 cores capable.
     *
     * RAM is deliberately almost unused. The slowest panel measured has 1.9 GB, which is not a
     * memory-starved device, and when it drowned its memory rose 8.7% while renderer CPU went up fourteen
     * fold. So this bottleneck is CPU, and RAM may only ever *drop* a tier — a genuinely tiny device — never
     * raise one, because plentiful RAM in front of slow cores predicts nothing.
     */
    fun tierOfPlatform(sdkInt: Int, cores: Int, totalRamBytes: Long): Tier {
        if (sdkInt <= 0 || cores <= 0) return Tier.UNKNOWN
        val base = when {
            sdkInt >= 33 && cores >= 8 -> Tier.CAPABLE
            sdkInt >= 30 -> Tier.MIDDLING
            else -> Tier.MODEST
        }
        val starved = totalRamBytes in 1..RAM_STARVED_BYTES
        return if (starved) demote(base) else base
    }

    private const val RAM_STARVED_BYTES = 1_100L * 1024 * 1024   // ~1 GB, below supported panels

    private fun demote(tier: Tier): Tier = when (tier) {
        Tier.CAPABLE -> Tier.MIDDLING
        Tier.MIDDLING -> Tier.MODEST
        else -> tier
    }

    /** Prefer the declared SoC; fall back to the platform reading; say which was used. */
    fun tier(soc: ProfileSoc?, sdkInt: Int, cores: Int, totalRamBytes: Long): Pair<Tier, TierSource> {
        val declared = tierOfSoc(soc)
        if (declared != Tier.UNKNOWN) return declared to TierSource.DECLARED_SOC
        val platform = tierOfPlatform(sdkInt, cores, totalRamBytes)
        if (platform != Tier.UNKNOWN) return platform to TierSource.PLATFORM_INFERRED
        return Tier.UNKNOWN to TierSource.NONE
    }

    /**
     * The verdict for a tier at a count.
     *
     * While [counting] the level is computed from the partial count and reported as [Confidence.COUNTING].
     * That combination is the whole point of showing the count live: the number climbing is what tells the
     * user the wait is doing something, and the provisional label is what stops a verdict crossing from
     * amber to red from reading as the panel changing its mind rather than still measuring.
     */
    fun advise(
        tier: Tier,
        tierSource: TierSource,
        entityCount: Int,
        counting: Boolean,
    ): Verdict {
        val bands = BANDS.getValue(tier)
        val count = entityCount.coerceAtLeast(0)
        val level = when {
            bands.struggleAbove != null && count >= bands.struggleAbove -> Level.RED
            count >= bands.recommendAbove -> Level.AMBER
            else -> Level.GREEN
        }
        return Verdict(
            tier = tier,
            tierSource = tierSource,
            level = level,
            confidence = when {
                counting -> Confidence.COUNTING
                measured(tier, level, count) -> Confidence.MEASURED
                else -> Confidence.ESTIMATED
            },
            bands = bands,
            entityCount = count,
            counting = counting,
        )
    }

    /**
     * The two counts an attended test actually measured, both on a 4× Cortex-A35 panel: comfortable with a
     * ~340-entity filtered subscription, and saturated at 3,769 unfiltered.
     */
    private const val MEASURED_COMFORTABLE_UP_TO = 500
    private const val MEASURED_SATURATED_FROM = 2_500

    /**
     * Whether an attended measurement actually backs this verdict — which means *near the counts that were
     * measured*, not merely on the tier that was.
     *
     * The tier's band edges are a maintainer judgement, so a modest panel just past its 1,000 ceiling is a
     * red we believe but have not seen: the nearest real measurement is at 3,769. Calling that "measured"
     * would be the exact dishonesty the confidence tag exists to prevent, and it would devalue the label
     * everywhere it is earned. So the claim is bounded to the neighbourhood of the evidence.
     */
    private fun measured(tier: Tier, level: Level, count: Int): Boolean {
        // A non-positive count is an impossible reading (a live Home Assistant always has entities),
        // and an impossible reading is ignorance, not evidence — it must never wear the strongest
        // confidence label. Belt to the verdict layer's braces: that layer presents a no-reading state
        // as still-counting, so this line should be unreachable, and it stays anyway.
        if (count <= 0) return false
        if (tier != Tier.MODEST) return false
        return when (level) {
            Level.GREEN -> count <= MEASURED_COMFORTABLE_UP_TO
            Level.RED -> count >= MEASURED_SATURATED_FROM
            Level.AMBER -> false
        }
    }
}
