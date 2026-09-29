package io.github.maxlyth.hapaneld.assist.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WakeWordCatalogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var engineAccepts = true

    private fun catalog(dir: File = folder.root) = WakeWordCatalog(
        bundled = object : WakeWordCatalog.BundledModels {
            override fun ids() = listOf("okay_nabu")
            override fun manifest(id: String) = manifest("Okay Nabu", "okay_nabu.tflite")
            override fun model(file: String) = byteArrayOf(1)
        },
        importDir = dir,
        accepts = { _, _ -> engineAccepts },
    )

    @Test fun `an imported model is listed after the bundled ones, under the id its name gives`() {
        val result = catalog().import("Hey Computer.tflite", manifest("Hey Computer", "hey.tflite").toByteArray(), byteArrayOf(9, 9))

        assertEquals("hey_computer", (result as WakeWordCatalog.ImportResult.Imported).config.id)
        val listed = catalog().available()
        assertEquals(listOf("okay_nabu", "hey_computer"), listed.map { it.id })
        assertEquals("Hey Computer", listed.last().wakeWord)
        assertArrayEquals(byteArrayOf(9, 9), File(folder.root, "hey_computer/hey.tflite").readBytes())
    }

    @Test fun `a rejected import keeps the last good model of that id`() {
        catalog().import("porch", manifest("Porch", "porch.tflite").toByteArray(), byteArrayOf(1, 2, 3))

        engineAccepts = false
        val refused = catalog().import("porch", manifest("Porch v2", "porch.tflite").toByteArray(), byteArrayOf(4))
        engineAccepts = true
        val malformed = catalog().import("porch", "{not json".toByteArray(), byteArrayOf(5))

        assertTrue(refused is WakeWordCatalog.ImportResult.Refused)
        assertTrue(malformed is WakeWordCatalog.ImportResult.Refused)
        assertEquals("Porch", catalog().available().single { it.id == "porch" }.wakeWord)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(folder.root, "porch/porch.tflite").readBytes())
        assertEquals(listOf("porch"), folder.root.list()!!.toList())
    }

    @Test fun `an import cannot replace a bundled wake word`() {
        val result = catalog().import("okay_nabu", manifest("Mine", "m.tflite").toByteArray(), byteArrayOf(1))

        assertTrue(result is WakeWordCatalog.ImportResult.Refused)
        assertEquals(listOf("okay_nabu"), catalog().available().map { it.id })
    }

    /** Home Assistant refuses a satellite's whole wake word list when one entry is outside its bounds. */
    @Test fun `a model Home Assistant could not list is refused, and the panel never holds more than it can list`() {
        val long = catalog().import("long", manifest("x".repeat(65), "long.tflite").toByteArray(), byteArrayOf(1))
        assertTrue(long is WakeWordCatalog.ImportResult.Refused)

        repeat(WakeWordCatalog.MAX_WAKE_WORDS - 1) { i ->
            val ok = catalog().import("word_$i", manifest("Word $i", "w.tflite").toByteArray(), byteArrayOf(1))
            assertTrue("import $i: $ok", ok is WakeWordCatalog.ImportResult.Imported)
        }
        val over = catalog().import("one_more", manifest("One more", "w.tflite").toByteArray(), byteArrayOf(1))
        assertTrue(over is WakeWordCatalog.ImportResult.Refused)
        // Replacing one already held is still allowed at the limit.
        val replaced = catalog().import("word_0", manifest("Word zero", "w.tflite").toByteArray(), byteArrayOf(2))
        assertTrue(replaced is WakeWordCatalog.ImportResult.Imported)
        assertEquals(WakeWordCatalog.MAX_WAKE_WORDS, catalog().available().size)
    }

    @Test fun `a name with nothing usable in it is refused`() {
        assertEquals(null, WakeWordCatalog.idFor("---.tflite"))
        assertEquals(null, WakeWordCatalog.idFor("9lives"))
        assertEquals("ok_panel_2", WakeWordCatalog.idFor("OK, Panel #2.json"))
    }

    /** A process killed between setting the working model aside and moving its replacement in. */
    @Test fun `a replacement interrupted between its two renames puts the previous model back at next use`() {
        catalog().import("porch", manifest("Porch", "porch.tflite").toByteArray(), byteArrayOf(1, 2, 3))
        assertTrue(File(folder.root, "porch").renameTo(File(folder.root, ".previous-porch")))
        File(folder.root, ".staging-porch").mkdirs()
        File(folder.root, ".staging-porch/porch.tflite").writeBytes(byteArrayOf(7))

        val recovered = catalog()
        assertEquals(listOf("okay_nabu", "porch"), recovered.available().map { it.id })
        assertArrayEquals(byteArrayOf(1, 2, 3), File(folder.root, "porch/porch.tflite").readBytes())
        assertEquals(listOf("porch"), folder.root.list()!!.toList())
        // Idempotent: a second pass finds nothing to do and changes nothing.
        assertEquals(listOf("okay_nabu", "porch"), recovered.available().map { it.id })
        assertArrayEquals(byteArrayOf(1, 2, 3), File(folder.root, "porch/porch.tflite").readBytes())
    }

    /** A process killed after the swap but before the old model was deleted: the new model stands. */
    @Test fun `a replacement interrupted after its swap keeps the new model and drops the old one`() {
        catalog().import("porch", manifest("Porch v2", "porch.tflite").toByteArray(), byteArrayOf(4))
        File(folder.root, ".previous-porch").mkdirs()
        File(folder.root, ".previous-porch/porch.tflite").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals("Porch v2", catalog().available().single { it.id == "porch" }.wakeWord)
        assertArrayEquals(byteArrayOf(4), File(folder.root, "porch/porch.tflite").readBytes())
        assertEquals(listOf("porch"), folder.root.list()!!.toList())
    }

    private fun manifest(phrase: String, model: String) = """
        {"type":"micro","wake_word":"$phrase","author":"me","model":"$model","trained_languages":["en"],"version":2,
         "micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080}}
    """.trimIndent()
}
