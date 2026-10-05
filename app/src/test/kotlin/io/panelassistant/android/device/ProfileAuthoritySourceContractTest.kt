package io.panelassistant.android.device

import io.panelassistant.android.device.profile.BundledProfileFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks out a second, compiled device catalog after YAML became the runtime authority. */
class ProfileAuthoritySourceContractTest {
    // Source-text reason: architecture rule scanned over the whole production tree (profile authority
    // types are built only at the registry and parser boundaries); no single file or name is pinned.
    private val mainKotlin = BundledProfileFixtures.mainKotlinDirectory
    private val mainSource = requireNotNull(mainKotlin.parentFile)
    private val productionKotlinSources by lazy {
        mainSource.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
    private val productionJavaSources by lazy {
        mainSource.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
    }
    private val productionSources by lazy { productionKotlinSources + productionJavaSources }

    @Test fun productionHasNoLegacyDetectorOrCompiledCatalogEntryPoints() {
        val source = productionSources.joinToString("\n") { it.readText() }
        listOf(
            "DeviceProfile.detect(",
            "DeviceProfile.match(",
            "DeviceProfile.knownProfiles",
        ).forEach { forbidden ->
            assertFalse("production source still uses $forbidden", source.contains(forbidden))
        }
    }

    @Test fun productionHasNoPerDeviceProfileImplementationsOrImports() {
        val deviceDirectory = mainKotlin.resolve("io/panelassistant/android/device")
        val implementations = productionKotlinSources.flatMap { file ->
            IMPLEMENTATION.findAll(file.readText()).map { match ->
                file.relativeTo(mainSource).invariantSeparatorsPath to match.groupValues[1]
            }.toList()
        }
        assertEquals(
            "only the final YAML adapter and capability-empty emergency contract may implement DeviceProfile",
            listOf(
                "kotlin/io/panelassistant/android/device/profile/DataDeviceProfile.kt" to "DataDeviceProfile",
                "kotlin/io/panelassistant/android/device/profile/RuntimeProfileRegistry.kt" to "EmergencyDeviceProfile",
            ).sortedWith(compareBy({ it.first }, { it.second })),
            implementations.sortedWith(compareBy({ it.first }, { it.second })),
        )
        val extensibleImplementations = productionKotlinSources.flatMap { file ->
            EXTENSIBLE_IMPLEMENTATION.findAll(file.readText()).map { match ->
                file.relativeTo(mainSource).invariantSeparatorsPath to match.groupValues[1]
            }.toList()
        }
        assertTrue(
            "a direct DeviceProfile implementation could admit unscanned indirect subclasses: $extensibleImplementations",
            extensibleImplementations.isEmpty(),
        )
        val javaImplementations = productionJavaSources.flatMap { file ->
            JAVA_IMPLEMENTATION.findAll(file.readText()).map { match ->
                file.relativeTo(mainSource).invariantSeparatorsPath to
                    match.groupValues.drop(1).first { it.isNotEmpty() }
            }.toList()
        }
        assertTrue("Java DeviceProfile implementations are forbidden: $javaImplementations", javaImplementations.isEmpty())
        val javaAnonymousImplementations = productionJavaSources.filter { file ->
            JAVA_ANONYMOUS_IMPLEMENTATION.containsMatchIn(file.readText())
        }.map { it.relativeTo(mainSource).invariantSeparatorsPath }
        assertTrue(
            "anonymous Java DeviceProfile implementations are forbidden: $javaAnonymousImplementations",
            javaAnonymousImplementations.isEmpty(),
        )
        val anonymousImplementations = productionKotlinSources.flatMap { file ->
            ANONYMOUS_IMPLEMENTATION.findAll(file.readText()).map { file.name }.toList()
        }
        assertTrue("DeviceProfile implementations must be named and auditable: $anonymousImplementations", anonymousImplementations.isEmpty())

        val contractSource = deviceDirectory.resolve("DeviceProfile.kt").readText()
        val contractTypes = DECLARATION.findAll(contractSource).map { it.groupValues[1] }.toSet()
        val imports = productionSources.flatMap { file ->
            DEVICE_IMPORT.findAll(file.readText()).map { match -> file.name to match.groupValues[1] }.toList()
        }
        val concreteImports = imports.filter { (_, symbol) ->
            symbol !in contractTypes && symbol != "EmergencyDeviceProfile"
        }
        assertTrue("production imports deleted compiled profile objects: $concreteImports", concreteImports.isEmpty())
    }

    @Test fun productionCanConstructTheYamlAdapterOnlyAtTheRegistryBoundary() {
        val adapterConstructionSites = productionSources.filter { file ->
            DATA_PROFILE_CONSTRUCTION.containsMatchIn(file.readText())
        }.map { it.name }.sorted()

        assertEquals(listOf("RuntimeProfileRegistry.kt"), adapterConstructionSites)
    }

    @Test fun productionCanConstructProfileDocumentsOnlyAtParserAndDraftBoundaries() {
        val documentConstructionSites = productionSources.flatMap { file ->
            List(PROFILE_DOCUMENT_CONSTRUCTION.findAll(file.readText()).count()) { file.name }
        }.sorted()

        assertEquals(listOf("ProfileDraftFactory.kt", "ProfileYaml.kt"), documentConstructionSites)
    }

    @Test fun productionCannotHideProfileAuthorityTypesBehindAliasesOrWildcardImports() {
        val escapes = productionSources.flatMap { file ->
            val source = file.readText()
            buildList {
                AUTHORITY_IMPORT_ESCAPE.findAll(source).forEach { match ->
                    add(file.name to match.value.trim())
                }
                AUTHORITY_TYPEALIAS.findAll(source).forEach { match ->
                    add(file.name to match.value.trim())
                }
            }
        }

        assertTrue("profile-authority scanner escapes are forbidden: $escapes", escapes.isEmpty())
    }

    @Test fun implementationScannerFindsDeviceProfileAnywhereInTheSupertypeList() {
        val source = """
            class Direct : DeviceProfile {
            }
            class Mixed : Other(), DeviceProfile {
            }
            class Constructed(
                val value: String,
            ) : Other(), DeviceProfile {
            }
            private object MixedObject : Other, DeviceProfile {
            }
            class Outer {
                protected open class Nested : Other(), DeviceProfile {
                }
            }
            enum class EnumProfile : DeviceProfile {
                VALUE;
            }
            sealed class SealedProfile : Other(), DeviceProfile {
            }
            fun interface FunctionalProfile : DeviceProfile {
            }
            open class ExtensibleProfile : DeviceProfile {
            }
            @Deprecated("scanner fixture")
            sealed interface SealedInterfaceProfile : DeviceProfile {
            }
            class Consumer(val profile: DeviceProfile) {
            }
            val anonymous = object : Other(), DeviceProfile {
            }
            import io.panelassistant.android.device.DeviceProfile as DP
            import io.panelassistant.android.device.profile.ProfileDocument as PD
            import io.panelassistant.android.device.profile.*
            typealias HiddenProfile = DeviceProfile
        """.trimIndent()

        assertEquals(
            setOf(
                "Direct",
                "Mixed",
                "Constructed",
                "MixedObject",
                "Nested",
                "EnumProfile",
                "SealedProfile",
                "FunctionalProfile",
                "ExtensibleProfile",
                "SealedInterfaceProfile",
            ),
            IMPLEMENTATION.findAll(source).map { it.groupValues[1] }.toSet(),
        )
        assertEquals(1, ANONYMOUS_IMPLEMENTATION.findAll(source).count())
        assertEquals(setOf("Nested", "SealedProfile", "FunctionalProfile", "ExtensibleProfile", "SealedInterfaceProfile"),
            EXTENSIBLE_IMPLEMENTATION.findAll(source).map { it.groupValues[1] }.toSet())
        assertEquals(3, AUTHORITY_IMPORT_ESCAPE.findAll(source).count())
        assertEquals(1, AUTHORITY_TYPEALIAS.findAll(source).count())
    }

    @Test fun constructionScannerFindsCallsCallableReferencesAndInterveningComments() {
        val source = """
            val dataCall = DataDeviceProfile /* reviewed construction */ (
                document, version, revision, trusted,
            )
            val dataReference = :: /* hidden reference */ DataDeviceProfile
            val documentCall = ProfileDocument // split call
                (schema = 2)
            val documentReference = ::ProfileDocument
        """.trimIndent()

        assertEquals(2, DATA_PROFILE_CONSTRUCTION.findAll(source).count())
        assertEquals(2, PROFILE_DOCUMENT_CONSTRUCTION.findAll(source).count())
        val javaSource = """
            final class Hidden implements DeviceProfile {}
            final class AnonymousHolder {
                final DeviceProfile hidden = new DeviceProfile() {};
                final Factory<DataDeviceProfile> dataFactory = DataDeviceProfile /* gap */ :: /* gap */ new;
                final Factory<ProfileDocument> documentFactory = ProfileDocument::new;
            }
        """.trimIndent()
        assertEquals(
            setOf("Hidden"),
            JAVA_IMPLEMENTATION.findAll(javaSource)
                .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }
                .toSet(),
        )
        assertEquals(1, JAVA_ANONYMOUS_IMPLEMENTATION.findAll(javaSource).count())
        assertEquals(1, DATA_PROFILE_CONSTRUCTION.findAll(javaSource).count())
        assertEquals(1, PROFILE_DOCUMENT_CONSTRUCTION.findAll(javaSource).count())
    }

    @Test fun unofficialCatalogIdentityNeverLeaksIntoCoreOrApkAssets() {
        val unofficial = BundledProfileFixtures.unofficial
        assertTrue("unofficial profile catalog is empty", unofficial.isNotEmpty())
        assertEquals(UNOFFICIAL_PROFILE_IDS, unofficial.mapTo(mutableSetOf()) { it.document.id })

        val identities = unofficial.flatMap { loaded ->
            buildList {
                add(loaded.document.id)
                add(loaded.document.displayName)
                loaded.document.identity.manufacturer?.let(::add)
                loaded.document.identity.model?.let(::add)
                loaded.document.match.any.flatMap { it.all }.flatMap { it.values }.forEach(::add)
            }
        }.map(String::trim).filter { it.length >= 4 }.map(String::lowercase).toSet()

        val productionTextFiles = mainSource.walkTopDown().filter { file ->
            file.isFile && file.extension.lowercase() in PRODUCTION_TEXT_EXTENSIONS
        }.toList()
        val sourceLeaks = productionTextFiles.flatMap { file ->
            val content = file.readText().lowercase()
            identities.filter { it in content }.map { identity -> file.relativeTo(mainSource).path to identity }
        }
        assertTrue("unofficial catalog identities leaked into APK source: $sourceLeaks", sourceLeaks.isEmpty())

        val assetIds = BundledProfileFixtures.bundled.map { it.document.id }.toSet()
        assertEquals(emptySet<String>(), identities.intersect(assetIds))
        val bundledYaml = BundledProfileFixtures.bundled.joinToString("\n") { it.rawYaml }.lowercase()
        val assetLeaks = identities.filter { it in bundledYaml }
        assertTrue("unofficial catalog identities leaked into bundled YAML: $assetLeaks", assetLeaks.isEmpty())
    }

    private companion object {
        val IMPLEMENTATION = Regex(
            "(?ms)^[\\t ]*(?:@[A-Za-z0-9_.]+(?:\\([^\\n]*\\))?\\s+)*" +
                "(?:(?:public|private|protected|internal|expect|actual|final|open|abstract|sealed|data|enum|" +
                "annotation|value|inline|fun)\\s+)*(?:object|class|interface)\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*)(?:\\s*<[^{}>]{1,500}>)?" +
                "(?:\\s+(?:internal|private|protected|public)\\s+constructor)?\\s*" +
                "(?:\\((?:(?!\\n[\\t ]*(?:(?:public|private|protected|internal|final|open|abstract|" +
                "sealed|data|enum|value|inline|override)\\s+)*(?:fun|class|object|interface)\\b)[^{}]){0,4000}?" +
                "\\)\\s*)?:\\s*[^{}]{0,2000}\\bDeviceProfile\\b[^{}]{0,2000}\\{",
        )
        val ANONYMOUS_IMPLEMENTATION = Regex("(?s)\\bobject\\s*:\\s*[^{}]{0,2000}\\bDeviceProfile\\b[^{}]{0,2000}\\{")
        val EXTENSIBLE_IMPLEMENTATION = Regex(
            "(?ms)^[\\t ]*(?:@[A-Za-z0-9_.]+(?:\\([^\\n]*\\))?\\s+)*" +
                "(?:(?:public|private|protected|internal|expect|actual)\\s+)*(?:" +
                "(?:(?:open|abstract|sealed)\\s+class)|(?:(?:(?:fun|sealed|abstract)\\s+)*interface))\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*)(?:\\s*<[^{}>]{1,500}>)?[^{}]{0,4000}:" +
                "[^{}]{0,2000}\\bDeviceProfile\\b[^{}]{0,2000}\\{",
        )
        val JAVA_IMPLEMENTATION = Regex(
            "(?s)\\b(?:class|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)[^{};]{0,4000}" +
                "\\bimplements\\b[^{};]{0,2000}\\bDeviceProfile\\b|" +
                "\\binterface\\s+([A-Za-z_][A-Za-z0-9_]*)[^{};]{0,4000}" +
                "\\bextends\\b[^{};]{0,2000}\\bDeviceProfile\\b",
        )
        val JAVA_ANONYMOUS_IMPLEMENTATION = Regex(
            "(?s)\\bnew\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\.)*DeviceProfile\\s*\\([^)]*\\)\\s*\\{",
        )
        val DECLARATION = Regex("(?:interface|class|object)\\s+([A-Za-z0-9_]+)")
        val DEVICE_IMPORT = Regex(
            "(?m)^[\\t ]*import\\s+io\\.panelassistant\\.android\\.device\\." +
                "([A-Z][A-Za-z0-9_]*|\\*)(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*$",
        )
        private const val CONSTRUCTION_GAP = "(?:(?:\\s+)|(?:/\\*.*?\\*/)|(?://[^\\r\\n]*(?:\\r?\\n|$)))*"
        val DATA_PROFILE_CONSTRUCTION = Regex(
            "(?s)(?:\\bDataDeviceProfile" + CONSTRUCTION_GAP + "\\(|::" + CONSTRUCTION_GAP +
                "DataDeviceProfile\\b|\\bDataDeviceProfile" + CONSTRUCTION_GAP + "::" +
                CONSTRUCTION_GAP + "new\\b)",
        )
        val PROFILE_DOCUMENT_CONSTRUCTION = Regex(
            "(?s)(?:(?<!class )\\bProfileDocument" + CONSTRUCTION_GAP + "\\(|::" +
                CONSTRUCTION_GAP + "ProfileDocument\\b|\\bProfileDocument" + CONSTRUCTION_GAP +
                "::" + CONSTRUCTION_GAP + "new\\b)",
        )
        val AUTHORITY_IMPORT_ESCAPE = Regex(
            "(?m)^[\\t ]*import\\s+io\\.panelassistant\\.android\\.device\\." +
                "(?:DeviceProfile\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*|\\*|profile\\.(?:" +
                "(?:DataDeviceProfile|ProfileDocument)\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*|\\*))\\s*$",
        )
        val AUTHORITY_TYPEALIAS = Regex(
            "(?m)^[\\t ]*(?:(?:public|private|protected|internal)\\s+)?typealias\\s+" +
                "[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*(?:io\\.panelassistant\\.android\\.device\\." +
                "(?:profile\\.)?)?(?:DeviceProfile|DataDeviceProfile|ProfileDocument)\\b",
        )
        val UNOFFICIAL_PROFILE_IDS = setOf(
            "community.cronos-lineageos18",
            "community.lenovo-thinksmart-view-lineageos",
            "community.rpi4-konstakang-lineageos",
            "community.sunworld-yc-sm55p-p76s01",
            "community.yc-sm10p",
        )
        val PRODUCTION_TEXT_EXTENSIONS = setOf(
            "aidl",
            "c",
            "cc",
            "cpp",
            "css",
            "h",
            "hpp",
            "html",
            "java",
            "js",
            "json",
            "kt",
            "properties",
            "txt",
            "xml",
            "yaml",
            "yml",
        )
    }
}
