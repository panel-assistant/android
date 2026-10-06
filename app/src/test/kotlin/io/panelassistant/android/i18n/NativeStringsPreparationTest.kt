package io.panelassistant.android.i18n

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's first build parses the catalogue for its native chips off the main thread, so an update
 * restart on a slow panel never spends seconds of the main thread in that parse (X2i and TPA10 ANRs,
 * 2026-10-06).
 */
class NativeStringsPreparationTest {
    private val english = """{
      "schema":1,
      "locale":"en",
      "sourceRevision":"e7c01506e9519d51b57fcf0e2b0b969a1ce44a6e",
      "strings":{
        "shell.microphone_muted":{
          "text":"Microphone muted",
          "sourceHash":"${sourceHash("Microphone muted")}",
          "surface":"shell",
          "context":"Chip shown while the hardware mute is on",
          "risk":"ordinary",
          "siblings":[],
          "placeholders":[],
          "frozen":[],
          "softMaxChars":30,
          "hardMaxChars":40
        }
      }
    }""".trimIndent()

    private val german = """{
      "schema":1,
      "locale":"de",
      "sourceRevision":"e7c01506e9519d51b57fcf0e2b0b969a1ce44a6e",
      "strings":{
        "shell.microphone_muted":{
          "text":"Mikrofon stumm",
          "sourceHash":"${sourceHash("Microphone muted")}",
          "state":"machine-cross-checked"
        }
      }
    }""".trimIndent()

    @Test fun `the catalogue parse runs off the launching thread, which stays free while it reads`() {
        runBlocking {
            val caller = Thread.currentThread()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reader = AtomicReference<Thread>()
            val loader = CatalogueLoader { path ->
                reader.compareAndSet(null, Thread.currentThread())
                assertNotEquals("catalogue read on the launching thread", caller, Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                if (path == "i18n/en.json") english else error("unexpected asset $path")
            }
            try {
                val prepared = async(start = CoroutineStart.UNDISPATCHED) {
                    loader.prepareNative(uiLanguage = null)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNotEquals(caller, reader.get())
                assertFalse("the dashboard can keep drawing while the catalogue is parsed", prepared.isCompleted)
                release.countDown()
                prepared.await()
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `after preparation the chips read their language without touching an asset on the main thread`() {
        val reads = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())
        val loader = CatalogueLoader { path ->
            reads += path to Thread.currentThread()
            when (path) {
                "i18n/en.json" -> english
                "i18n/de.json" -> german
                else -> error("unexpected asset $path")
            }
        }
        runBlocking { loader.prepareNative(uiLanguage = "de") }
        assertEquals(setOf("i18n/en.json", "i18n/de.json"), reads.map { it.first }.toSet())
        reads.clear()

        // What MicrophoneMutedChip and HaNetworkChip ask for while the dashboard builds.
        val chip = loader.strings(CatalogueLoader.nativeLocale("de"))

        assertEquals("Mikrofon stumm", chip.get("shell.microphone_muted"))
        assertEquals(emptyList<Pair<String, Thread>>(), reads.toList())
    }
}
