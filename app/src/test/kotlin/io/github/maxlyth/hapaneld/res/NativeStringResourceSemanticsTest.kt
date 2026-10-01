package io.github.maxlyth.hapaneld.res

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.createDirectories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStringResourceSemanticsTest {
    @Test fun concatenationSuffixesRetainTheirSeparatorsAfterAndroidResourceCompilation() {
        val expected = linkedMapOf(
            "values" to linkedMapOf(
                "guard_db_outcome" to " · outcome %1\$s",
                "guard_db_forward_deadline" to " · forward: %1\$d",
                "storage_capacity_suffix" to " (%1\$d MiB filesystem free)",
                "database_during_suffix" to " during %1\$s",
            ),
            "values-de" to linkedMapOf(
                "guard_db_outcome" to " · Ergebnis %1\$s",
                "guard_db_forward_deadline" to " · vorwärts: %1\$d",
                "storage_capacity_suffix" to " (%1\$d MiB im Dateisystem frei)",
                "database_during_suffix" to " während %1\$s",
            ),
            "values-es" to linkedMapOf(
                "guard_db_outcome" to " · resultado %1\$s",
                "guard_db_forward_deadline" to " · avance: %1\$d",
                "storage_capacity_suffix" to " (%1\$d MiB libres en el sistema de archivos)",
                "database_during_suffix" to " durante %1\$s",
            ),
            "values-fr" to linkedMapOf(
                "guard_db_outcome" to " · résultat %1\$s",
                "guard_db_forward_deadline" to " · progression : %1\$d",
                "storage_capacity_suffix" to " (%1\$d Mio libres sur le système de fichiers)",
                "database_during_suffix" to " pendant %1\$s",
            ),
            "values-it" to linkedMapOf(
                "guard_db_outcome" to " · risultato %1\$s",
                "guard_db_forward_deadline" to " · avanzamento: %1\$d",
                "storage_capacity_suffix" to " (%1\$d MiB liberi nel file system)",
                "database_during_suffix" to " durante %1\$s",
            ),
            "values-zh-rCN" to linkedMapOf(
                "guard_db_outcome" to " · 结果 %1\$s",
                "guard_db_forward_deadline" to " · 推进截止时间：%1\$d",
                "storage_capacity_suffix" to "（文件系统可用空间 %1\$d MiB）",
                "database_during_suffix" to "（%1\$s期间）",
            ),
        )
        val configuration = mapOf(
            "values" to "()",
            "values-de" to "(de)",
            "values-es" to "(es)",
            "values-fr" to "(fr)",
            "values-it" to "(it)",
            "values-zh-rCN" to "(zh-rCN)",
        )

        val sdk = checkNotNull(System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")) {
            "Android SDK location is required to verify compiled string-resource semantics"
        }
        val aapt2 = newestChild(File(sdk, "build-tools"), "aapt2")
        val androidJar = newestChild(File(sdk, "platforms"), "android.jar")
        val root = Files.createTempDirectory("native-string-semantics")
        try {
            val resources = root.resolve("res").createDirectories()
            expected.forEach { (directory, strings) ->
                // Source-text reason: compiles the shipped strings.xml resources as input data.
                writeSelectedStrings(
                    source = File("src/main/res/$directory/strings.xml"),
                    destination = resources.resolve(directory).createDirectories().resolve("strings.xml").toFile(),
                    names = strings.keys + if (directory == "values") {
                        setOf("database_failure_body", "storage_pressure_body")
                    } else {
                        emptySet()
                    },
                )
            }
            val manifest = root.resolve("AndroidManifest.xml").toFile().apply {
                writeText(
                    """<manifest xmlns:android="http://schemas.android.com/apk/res/android" """ +
                        """package="io.github.maxlyth.hapaneld.resourcesemantics">""" +
                        """<uses-sdk android:minSdkVersion="26" /></manifest>""",
                )
            }
            val compiled = root.resolve("compiled.zip").toFile()
            val linked = root.resolve("linked.ap_").toFile()
            run(aapt2, "compile", "--dir", resources.toString(), "-o", compiled.path)
            run(
                aapt2,
                "link",
                "-I",
                androidJar.path,
                "--manifest",
                manifest.path,
                "-o",
                linked.path,
                compiled.path,
            )
            val table = run(aapt2, "dump", "resources", linked.path)

            expected.forEach { (directory, strings) ->
                val qualifier = configuration.getValue(directory)
                strings.forEach { (name, value) ->
                    assertEquals(
                        "$directory string/$name compiled without its intended separator",
                        value,
                        compiledValue(table, name, qualifier),
                    )
                }
            }

            val during = compiledValue(table, "database_during_suffix", "()").replace("%1\$s", "backup")
            val databaseBody = compiledValue(table, "database_failure_body", "()").replace("%1\$s", during)
            assertTrue(databaseBody.contains("reported a failure during backup."))
            val capacity = compiledValue(table, "storage_capacity_suffix", "()").replace("%1\$d", "512")
            val storageBody = compiledValue(table, "storage_pressure_body", "()").replace("%1\$s", capacity)
            assertTrue(storageBody.contains("pressure is critical (512 MiB filesystem free)."))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeSelectedStrings(source: File, destination: File, names: Set<String>) {
        val factory = DocumentBuilderFactory.newInstance()
        val sourceDocument = factory.newDocumentBuilder().parse(source)
        val targetDocument = factory.newDocumentBuilder().newDocument()
        val resources = targetDocument.createElement("resources")
        targetDocument.appendChild(resources)
        val sourceStrings = sourceDocument.getElementsByTagName("string")
        (0 until sourceStrings.length)
            .map(sourceStrings::item)
            .filter { it.attributes.getNamedItem("name").nodeValue in names }
            .forEach { resources.appendChild(targetDocument.importNode(it, true)) }

        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }.transform(DOMSource(targetDocument), StreamResult(destination))
    }

    private fun newestChild(parent: File, childName: String): File = parent.listFiles()
        .orEmpty()
        .sortedByDescending { it.name }
        .map { File(it, childName) }
        .firstOrNull(File::isFile)
        ?: error("Cannot find $childName below ${parent.path}")

    private fun compiledValue(table: String, name: String, qualifier: String): String {
        val resource = table.substringAfter(" string/$name\n", missingDelimiterValue = "")
            .substringBefore("\n    resource ")
        assertTrue("compiled resource table is missing string/$name", resource.isNotEmpty())
        val prefix = "$qualifier \""
        val line = resource.lineSequence().map(String::trimStart).firstOrNull { it.startsWith(prefix) }
        assertTrue("compiled resource table is missing $qualifier string/$name", line != null)
        return checkNotNull(line).removePrefix(prefix).removeSuffix("\"")
    }

    private fun run(vararg command: Any): String {
        val arguments = command.map(Any::toString)
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${arguments.joinToString(" ")} failed:\n$output" }
        return output
    }
}
