package io.github.maxlyth.hapaneld.assist.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MicroWakeWordModelConfigTest {
    // Source-text reason: loads the shipped wake word manifests as input data.
    private val assets = File("src/main/assets/wakeword")

    @Test
    fun parsesTheUpstreamManifestShape() {
        val config = MicroWakeWordModelConfig.parse(
            "okay_nabu",
            """
            {"type":"micro","wake_word":"Okay Nabu","author":"Kevin Ahrendt","website":"https://www.kevinahrendt.com/",
             "model":"okay_nabu.tflite","trained_languages":["en"],"version":2,
             "micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080,
                      "minimum_esphome_version":"2024.7.0"}}
            """.trimIndent(),
        )
        assertEquals("okay_nabu", config.id)
        assertEquals("Okay Nabu", config.wakeWord)
        assertEquals("Kevin Ahrendt", config.author)
        assertEquals("https://www.kevinahrendt.com/", config.website)
        assertEquals("okay_nabu.tflite", config.modelFile)
        assertEquals("wakeword/okay_nabu.tflite", config.modelAssetPath)
        assertEquals(listOf("en"), config.trainedLanguages)
        assertEquals(2, config.version)
        assertEquals(0.97f, config.probabilityCutoff, 1e-6f)
        assertEquals(10, config.featureStepSizeMs)
        assertEquals(5, config.slidingWindowSize)
        assertEquals(26080, config.tensorArenaSize)
    }

    @Test
    fun missingWebsiteIsNull() {
        val config = MicroWakeWordModelConfig.parse(
            "x",
            """{"type":"micro","model":"x.tflite","micro":{"probability_cutoff":0.5,"feature_step_size":10,"sliding_window_size":1}}""",
        )
        assertNull(config.website)
        assertEquals("x", config.wakeWord)
        assertEquals(emptyList<String>(), config.trainedLanguages)
    }

    @Test
    fun rejectsMalformedManifests() {
        val ok = """{"type":"micro","model":"x.tflite","micro":{"probability_cutoff":0.5,"feature_step_size":10,"sliding_window_size":1}}"""
        val bad = listOf(
            "not json",
            ok.replace("\"micro\",", "\"streaming\","),
            ok.replace("\"probability_cutoff\":0.5", "\"probability_cutoff\":1.5"),
            ok.replace("\"probability_cutoff\":0.5,", ""),
            ok.replace("\"feature_step_size\":10", "\"feature_step_size\":0"),
            ok.replace("\"sliding_window_size\":1", "\"sliding_window_size\":0"),
            ok.replace("\"model\":\"x.tflite\"", "\"model\":\"../x.tflite\""),
            ok.replace("\"model\":\"x.tflite\",", ""),
            """{"type":"micro","model":"x.tflite"}""",
        )
        MicroWakeWordModelConfig.parse("x", ok)
        for (json in bad) {
            val thrown = runCatching { MicroWakeWordModelConfig.parse("x", json) }.exceptionOrNull()
            assertTrue("expected rejection for $json", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun everyBundledManifestParsesAndNamesAnExistingModel() {
        val manifests = assets.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        assertEquals(listOf("alexa", "hey_jarvis", "hey_mycroft", "okay_nabu"), manifests.map { it.nameWithoutExtension })
        for (file in manifests) {
            val config = MicroWakeWordModelConfig.parse(file.nameWithoutExtension, file.readText())
            assertTrue("${config.modelFile} missing", File(assets, config.modelFile).isFile)
            assertEquals(10, config.featureStepSizeMs)
            assertTrue(config.probabilityCutoff > 0.5f)
            assertTrue(config.slidingWindowSize in 1..20)
            assertTrue(config.tensorArenaSize in 1..(64 * 1024))
        }
        assertTrue(File(assets, "LICENSE.txt").isFile)
    }
}
