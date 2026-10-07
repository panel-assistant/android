package io.panelassistant.android.http

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.device.profile.ProfileCpuCoreCluster
import io.panelassistant.android.device.profile.ProfileSoc
import io.panelassistant.android.http.EntityFilterAdvice.Confidence
import io.panelassistant.android.http.EntityFilterAdvice.Level
import io.panelassistant.android.http.EntityFilterAdvice.Tier
import io.panelassistant.android.http.EntityFilterAdvice.TierSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityFilterAdviceTest {
    private fun soc(vararg clusters: Pair<String, Int>) =
        ProfileSoc(model = "test", cpuCores = clusters.map { ProfileCpuCoreCluster(it.first, it.second) })

    // ---- tiering from the declared SoC ----

    @Test fun theDeclaredSocOfEveryShippedProfileTiersWithoutFallback() {
        // Every profile that declares an `soc` block must tier from it, or the panels we actually support
        // would silently fall back to platform guessing. Read from the shipped YAML so adding a profile with
        // a core we do not recognise fails here rather than degrading quietly in front of a user.
        // Source-text reason: loads the shipped device profiles as input data.
        val dir = TestSources.appDir("src/main/assets/device-profiles")
        val declaring = dir.listFiles { f: File -> f.extension == "yaml" }.orEmpty()
            .map { it.name to it.readText() }
            .filter { (_, text) -> text.contains("\nsoc:") }
        assertTrue("expected shipped profiles declaring an soc block", declaring.size >= 6)
        declaring.forEach { (name, text) ->
            val arch = Regex("""architecture:\s*(.+)""").findAll(text).map { it.groupValues[1].trim() }.toList()
            assertTrue("$name declares no core architecture", arch.isNotEmpty())
            arch.forEach { a ->
                assertNotNull("$name: unrecognised core architecture \"$a\"", EntityFilterAdvice.tierOfArchitecture(a))
            }
        }
    }

    @Test fun aBigLittleSocIsRankedByItsBigCores() {
        // RK3576 is 4× A72 + 4× A53. Taking the weakest cluster would file it alongside a plain A53 panel and
        // understate it badly, because the render work that saturates here is single-threaded.
        assertEquals(Tier.CAPABLE, EntityFilterAdvice.tierOfSoc(soc("Arm Cortex-A72" to 4, "Arm Cortex-A53" to 4)))
    }

    @Test fun theFleetsThreeChipsTierInTheOrderTheirMeasuredLoadTimesDo() {
        // Cold time-to-interactive on the fleet was 1.3s (RK3576), 2.1s (RK3566), 3.7-7.2s (PX30/RK3326).
        // Architecture must order them the same way; two independent signals agreeing is what makes the
        // tiering defensible rather than invented.
        assertEquals(Tier.CAPABLE, EntityFilterAdvice.tierOfSoc(soc("Arm Cortex-A72" to 4, "Arm Cortex-A53" to 4)))
        assertEquals(Tier.MIDDLING, EntityFilterAdvice.tierOfSoc(soc("Arm Cortex-A55" to 4)))
        assertEquals(Tier.MODEST, EntityFilterAdvice.tierOfSoc(soc("Arm Cortex-A35" to 4)))
    }

    @Test fun anAbsentOrUnparsableSocDoesNotPretendToKnow() {
        assertEquals(Tier.UNKNOWN, EntityFilterAdvice.tierOfSoc(null))
        assertEquals(Tier.UNKNOWN, EntityFilterAdvice.tierOfSoc(ProfileSoc(model = "mystery")))
        assertNull(EntityFilterAdvice.tierOfArchitecture("Some Other Core"))
    }

    // ---- tiering a panel with no profile ----

    @Test fun theFleetsLivePlatformReadingsAgreeWithTheirDeclaredTiers() {
        // Read live from /api/v1/diag: API 34 / 8 cores / 3.8 GB, API 30 / 4 cores, API 27 / 4 cores / 1.9 GB.
        // A generic panel is tiered on these alone, so they must land where the declared SoC would.
        val gb: (Double) -> Long = { (it * 1024 * 1024 * 1024).toLong() }
        assertEquals(Tier.CAPABLE, EntityFilterAdvice.tierOfPlatform(sdkInt = 34, cores = 8, totalRamBytes = gb(3.8)))
        assertEquals(Tier.MIDDLING, EntityFilterAdvice.tierOfPlatform(sdkInt = 30, cores = 4, totalRamBytes = gb(2.0)))
        assertEquals(Tier.MODEST, EntityFilterAdvice.tierOfPlatform(sdkInt = 27, cores = 4, totalRamBytes = gb(1.9)))
    }

    @Test fun plentifulRamNeverRaisesATierAndTinyRamDropsOne() {
        // The bottleneck is CPU, not memory: the panel that drowned used 9% more memory and fourteen times
        // the renderer CPU. So RAM may only ever demote, and 2 GB in front of slow cores predicts nothing.
        val gb: (Double) -> Long = { (it * 1024 * 1024 * 1024).toLong() }
        assertEquals(
            "generous RAM must not promote an old 4-core panel",
            Tier.MODEST,
            EntityFilterAdvice.tierOfPlatform(sdkInt = 27, cores = 4, totalRamBytes = gb(8.0)),
        )
        assertEquals(
            "a genuinely tiny device drops a tier",
            Tier.MIDDLING,
            EntityFilterAdvice.tierOfPlatform(sdkInt = 34, cores = 8, totalRamBytes = gb(0.9)),
        )
    }

    @Test fun theDeclaredSocWinsAndTheSourceSaysSo() {
        val (tier, source) = EntityFilterAdvice.tier(soc("Arm Cortex-A35" to 4), sdkInt = 34, cores = 8, totalRamBytes = 0)
        assertEquals(Tier.MODEST, tier)
        assertEquals(TierSource.DECLARED_SOC, source)

        val (fallback, fallbackSource) = EntityFilterAdvice.tier(null, sdkInt = 34, cores = 8, totalRamBytes = 0)
        assertEquals(Tier.CAPABLE, fallback)
        assertEquals(TierSource.PLATFORM_INFERRED, fallbackSource)

        val (none, noneSource) = EntityFilterAdvice.tier(null, sdkInt = 0, cores = 0, totalRamBytes = 0)
        assertEquals(Tier.UNKNOWN, none)
        assertEquals(TierSource.NONE, noneSource)
    }

    // ---- the bands ----

    @Test fun theMaintainersThresholdsAreWhatTheBandsUse() {
        // Modest tops out at 1,000; the best hardware benefits from filtering above 2,000.
        assertEquals(Level.GREEN, advise(Tier.MODEST, 499).level)
        assertEquals(Level.AMBER, advise(Tier.MODEST, 500).level)
        assertEquals(Level.RED, advise(Tier.MODEST, 1_000).level)
        assertEquals(Level.GREEN, advise(Tier.CAPABLE, 1_999).level)
        assertEquals(Level.AMBER, advise(Tier.CAPABLE, 2_000).level)
        // Interpolated between the two given anchors.
        assertEquals(Level.AMBER, advise(Tier.MIDDLING, 1_000).level)
        assertEquals(Level.RED, advise(Tier.MIDDLING, 1_500).level)
    }

    @Test fun noTierClaimsItWillStruggleWithoutEvidenceThatItDoes() {
        // We have never measured a capable panel drowning. Claiming a red for it would be inventing a number,
        // and it would make the "measured" label meaningless everywhere it IS earned.
        assertNull(advise(Tier.CAPABLE, 50_000).bands.struggleAbove)
        assertEquals(Level.AMBER, advise(Tier.CAPABLE, 50_000).level)
        assertNull(advise(Tier.UNKNOWN, 50_000).bands.struggleAbove)
    }

    @Test fun anUnknownPanelIsAskedToFilterRatherThanReassured() {
        // Filtering when it was not needed costs almost nothing; not filtering a weak panel costs the bad
        // first impression the whole step exists to prevent. So the safe default is the recommendation.
        assertEquals(Level.AMBER, advise(Tier.UNKNOWN, 1_200).level)
    }

    // ---- confidence ----

    @Test fun onlyTheMeasuredTierMayClaimAMeasurement() {
        // The attended test measured a Cortex-A35 panel comfortable at ~340 entities and saturated at 3,769.
        // Those two points are the only measurements that exist; the span between them is not one.
        assertEquals(Confidence.MEASURED, advise(Tier.MODEST, 340).confidence)
        assertEquals(Confidence.MEASURED, advise(Tier.MODEST, 3_769).confidence)
        assertEquals(Confidence.ESTIMATED, advise(Tier.MODEST, 700).confidence)
        assertEquals(Confidence.ESTIMATED, advise(Tier.CAPABLE, 3_769).confidence)
        assertEquals(Confidence.ESTIMATED, advise(Tier.MIDDLING, 2_000).confidence)
        // A red just past the tier's judged ceiling is believed, not measured: the nearest real measurement
        // is at 3,769, and claiming otherwise would devalue the label everywhere it is earned.
        assertEquals(Level.RED, advise(Tier.MODEST, 1_200).level)
        assertEquals(Confidence.ESTIMATED, advise(Tier.MODEST, 1_200).confidence)
    }

    @Test fun aVerdictMidScanIsProvisionalWhateverItSays() {
        // The count is shown climbing so the user can see the wait working, which means the level can cross a
        // band while they read. It must stay labelled provisional until the scan finishes.
        val partial = EntityFilterAdvice.advise(Tier.MODEST, TierSource.DECLARED_SOC, 1_200, counting = true)
        assertEquals(Level.RED, partial.level)
        assertEquals(Confidence.COUNTING, partial.confidence)
        assertTrue(partial.counting)

        val settled = EntityFilterAdvice.advise(Tier.MODEST, TierSource.DECLARED_SOC, 1_200, counting = false)
        assertFalse(settled.counting)
        assertEquals(Confidence.ESTIMATED, settled.confidence)
    }

    @Test fun aNegativeOrZeroCountIsNeverAlarming() {
        // Before the first progress report there is nothing to recommend against; green is the honest answer.
        assertEquals(Level.GREEN, advise(Tier.MODEST, 0).level)
        assertEquals(0, EntityFilterAdvice.advise(Tier.MODEST, TierSource.DECLARED_SOC, -5, false).entityCount)
    }

    @Test fun anImpossibleReadingNeverWearsTheMeasuredLabel() {
        // Shipped and caught on hardware: a fresh panel with no scan ever run showed "0 entities" as a
        // green MEASURED verdict — the strongest confidence label, claimed from a reading a live Home
        // Assistant cannot produce. Zero is ignorance, not evidence, whatever the tier or level.
        assertNotEquals(Confidence.MEASURED, advise(Tier.MODEST, 0).confidence)
        assertNotEquals(
            Confidence.MEASURED,
            EntityFilterAdvice.advise(Tier.MODEST, TierSource.DECLARED_SOC, -5, counting = false).confidence,
        )
    }

    private fun advise(tier: Tier, count: Int) =
        EntityFilterAdvice.advise(tier, TierSource.DECLARED_SOC, count, counting = false)
}
