import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties
import java.security.MessageDigest
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.cyclonedx.bom)
}

val featureCostsEnabled = providers.gradleProperty("featureCosts").orNull
    ?.toBooleanStrictOrNull() ?: true
val keystoreProps = rootProject.file("keystore.properties")
val hasReleaseSigning = keystoreProps.exists()

// Keep packaged Android resources inside the same finite locale boundary advertised by the app.
// The product catalogue lives in assets/i18n rather than values-*/strings.xml, so AGP cannot infer
// this list through generateLocaleConfig. PlatformLocaleContractTest keeps this build-time list,
// res/xml/locales_config.xml, and AppLocale.RELEASE_LOCALES in lockstep.
val releaseLocaleFilters = listOf("en", "de", "fr", "it", "es", "zh-rCN", "nl", "pl", "uk", "cs", "pt-rBR")

// The compatibility boundary is signed into every APK as one finite manifest value. Keep the
// database schema object as the source of truth: release tags/versionNames are presentation, not a
// statement about which on-disk structures a candidate can safely open.
val entityCatalogSchemaSource = rootProject.file(
    "app/src/main/kotlin/io/panelassistant/android/dashboard/EntityCatalogSchema.kt",
)
fun entityCatalogSchemaConstant(name: String): Int {
    val match = Regex("""const\s+val\s+$name\s*=\s*(\d+)""")
        .find(entityCatalogSchemaSource.readText())
        ?: error("EntityCatalogSchema.$name is missing or is not an integer literal")
    return match.groupValues[1].toInt()
}
val databaseMinimumSchema = entityCatalogSchemaConstant("MINIMUM_SUPPORTED_VERSION")
val databaseMaximumSchema = entityCatalogSchemaConstant("CURRENT_VERSION")
check(databaseMinimumSchema in 1..databaseMaximumSchema) {
    "invalid EntityCatalogSchema boundary $databaseMinimumSchema..$databaseMaximumSchema"
}
val databaseCompatibilityContract =
    "hapaneld-db:v1:ha-paneld.db:$databaseMinimumSchema:$databaseMaximumSchema"

// The native handshake owns its range. Sign the same bounds into each APK so release tooling and
// uploaded-app admission can inspect the candidate itself without guessing from its version.
val panelAssistantProtocolSource = rootProject.file(
    "app/src/main/kotlin/io/panelassistant/android/panelassistant/PanelAssistantTransportProtocol.kt",
)
fun panelAssistantProtocolConstant(name: String): Int {
    val matches = Regex("""(?m)^\s*const\s+val\s+$name\s*=\s*([0-9]+)\s*$""")
        .findAll(panelAssistantProtocolSource.readText()).toList()
    check(matches.size == 1) { "PanelAssistantTransportProtocol.$name must be one integer literal" }
    return matches.single().groupValues[1].toInt()
}
val panelAssistantProtocolMinimum = panelAssistantProtocolConstant("PROTOCOL_MIN")
val panelAssistantProtocolMaximum = panelAssistantProtocolConstant("PROTOCOL_MAX")
check(panelAssistantProtocolMinimum in 1..panelAssistantProtocolMaximum) {
    "invalid Panel Assistant protocol range $panelAssistantProtocolMinimum..$panelAssistantProtocolMaximum"
}
val panelAssistantProtocolContract =
    "hapaneld-native:v1:$panelAssistantProtocolMinimum:$panelAssistantProtocolMaximum"

val helperIdentityFiles = rootProject.fileTree("helper/src") {
    include("*.c", "*.h", "*.def")
}.files.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
val helperCompileContract = "contract:android-api=26;optimization=O2;strip=true"
val helperBuildId = MessageDigest.getInstance("SHA-256").let { digest ->
    digest.update(helperCompileContract.toByteArray())
    digest.update(0)
    helperIdentityFiles.forEach { file ->
        digest.update(file.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
        digest.update(0)
        digest.update(file.length().toString().toByteArray())
        digest.update(0)
        digest.update(file.readBytes())
        digest.update(0)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

dependencyLocking {
    lockAllConfigurations()
}

// The release identity lives in app/version.properties rather than in this script, so a version bump
// neither changes the Gradle cache key nor matches the build-file paths that start the emulator tests.
val appVersion = Properties().apply { file("version.properties").inputStream().use { load(it) } }

// Stable development identity: release numbering must not invalidate debug compilation or lint.
val debugVersionName = "1.0.0"
val debugAssetsDirectory = layout.buildDirectory.dir("debug-assets")

android {
    namespace = "io.panelassistant.android"
    compileSdk = 37

    // Pinned independently of AGP's newer default so CI and local builds continue to produce the
    // same native helper and LED-driver binaries for the supported ARM32/ARM64 panel fleet.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.panelassistant.android"
        // minSdk 26 covers the supported panels (NSPanel Pro Android 8.1 = API 27, TPA10 Android 11 = API 30).
        minSdk = 26
        targetSdk = 35
        // versionCode bumps on EVERY internal build (it drives upgrades + the /health build token);
        // versionName identifies the public release; publication remains a separate explicit action.
        versionCode = requireNotNull(appVersion.getProperty("versionCode")) { "app/version.properties must define versionCode" }.toInt()
        versionName = requireNotNull(appVersion.getProperty("versionName")) { "app/version.properties must define versionName" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Local paired performance runs can build an otherwise identical no-op arm with
        // `-PfeatureCosts=false`; release/default builds retain the fixed-key event counters.
        buildConfigField("boolean", "FEATURE_COSTS_ENABLED", featureCostsEnabled.toString())
        buildConfigField("String", "HELPER_BUILD_ID", "\"$helperBuildId\"")
        buildConfigField("String", "DATABASE_COMPATIBILITY", "\"$databaseCompatibilityContract\"")
        manifestPlaceholders["databaseCompatibility"] = databaseCompatibilityContract
        manifestPlaceholders["panelAssistantProtocol"] = panelAssistantProtocolContract

        // Only the fleet's ARM ABIs — bounds the native LED lib (libhapaneld_led.so) + APK size.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    androidResources {
        localeFilters += releaseLocaleFilters
    }

    // Clean-room rk3576 /dev/ledjni ioctl driver (app/src/main/cpp/led_jni.c → libhapaneld_led.so).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Optional local release signing — drop a gitignored `keystore.properties` (storeFile, storePassword,
    // keyAlias, keyPassword) in the repo root to sign release builds with your real key on a laptop /
    // devcontainer, so `assembleRelease` installs in place over the public releases (no uninstall dance).
    // CI has no such file: it builds an unsigned release APK and post-signs with apksigner from Actions
    // secrets, so this is a no-op there. NEVER commit keystore.properties or the .jks (both gitignored).
    // See https://panel-assistant.io/go/docs?page=local-builds.
    signingConfigs {
        // Committed debug keystore keeps secretless CI and emulator artifacts deterministic. These
        // artifacts are test-only. Local builds with keystore.properties use the configured release
        // signing key for both variants below.
        getByName("debug") {
            storeFile = rootProject.file("gradle/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
        if (hasReleaseSigning) {
            val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    sourceSets {
        // Lint fingerprints assets too. Debug uses the same assets with stable OpenAPI metadata;
        // release keeps the original published contract. Keep debug's directory a normal source
        // directory because lint excludes generated asset directories from its inputs.
        getByName("main").assets.setSrcDirs(emptyList<String>())
        getByName("release").assets.srcDir("src/main/assets")
        getByName("debug").assets.srcDir(debugAssetsDirectory.get().asFile)
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // A checkout with a configured private release key produces one signer across debug and
            // release artifacts. Secretless public checkouts retain
            // the deterministic debug signer and still produce a normal installable development APK.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // Keep production ABIs unchanged while allowing the optional emulator jobs to install the
            // real app/native library on an x86_64 Android emulator.
            ndk.abiFilters += "x86_64"
            isPseudoLocalesEnabled = true
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        // Ktor requires Java 8 language features.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM unit tests exercise code that touches android.util.Log etc.; return stub defaults instead of
        // throwing "not mocked", so controllers that legitimately log on the tested path stay unit-testable.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Translations follow English in a planned pass, so a new string without them is reported, never a failed build.
        warning += "MissingTranslation"
    }

    packaging {
        resources {
            // These metadata files collide across the dependency graph.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

// Treat the paired-build switch as an explicit generated-source input. This prevents a locally
// cached BuildConfig from the enabled arm being reused by a disabled performance comparison.
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BuildConfig") }.configureEach {
    inputs.property("featureCostsEnabled", featureCostsEnabled)
    inputs.files(helperIdentityFiles)
    inputs.property("helperBuildId", helperBuildId)
    inputs.file(entityCatalogSchemaSource)
    inputs.property("databaseCompatibility", databaseCompatibilityContract)
}

tasks.matching { it.name.startsWith("process") && it.name.endsWith("MainManifest") }.configureEach {
    inputs.file(entityCatalogSchemaSource)
    inputs.property("databaseCompatibility", databaseCompatibilityContract)
}

// Execute the platform implementations behind provider receipts and package facts on the host.
// Selected Android classes replace SDK stubs; tests use their host-executable paths without a framework runner or device.
val providerAndroidRuntime = configurations.create("providerAndroidRuntime")
val providerInstallSourceRuntime = configurations.create("providerInstallSourceRuntime")
abstract class ProviderArchiveServices {
    @get:javax.inject.Inject abstract val archives: org.gradle.api.file.ArchiveOperations
}
val providerAndroidRuntimeJar = tasks.register<Jar>("providerAndroidRuntimeJar") {
    val archives = objects.newInstance<ProviderArchiveServices>().archives
    archiveFileName.set("provider-android-runtime.jar")
    destinationDirectory.set(layout.buildDirectory.dir("provider-android-runtime"))
    from(providerAndroidRuntime.elements.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        include("android/util/AtomicFile*.class", "android/os/FileUtils*.class")
        include("android/content/pm/Signature*.class", "android/content/pm/SigningInfo*.class")
        include("android/content/pm/PackageInfo*.class", "android/content/pm/PackageParser\$SigningDetails*.class")
        include("android/webkit/WebView.class", "android/webkit/WebView\$*.class")
        include("android/webkit/WebViewFactory*.class", "android/webkit/WebViewProvider*.class")
        include("android/view/ViewDebug\$HierarchyHandler.class", "com/android/internal/util/SizedInputStream.class")
    }
    from(providerInstallSourceRuntime.elements.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        include("android/content/pm/InstallSourceInfo*.class")
    }
}
val androidMockPackageManager = androidComponents.sdkComponents.bootClasspath.map { files ->
    files.first { it.asFile.name == "android.jar" }.asFile.resolveSibling("optional/android.test.mock.jar")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Pull-to-refresh for the built-in dashboard renderer (drag down from the top = light page reload).
    implementation(libs.androidx.swiperefreshlayout)
    // WebSettingsCompat force-dark for the built-in renderer on pre-Android-13 panels.
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)

    // HTTP command surface (:8888) — Ktor CIO engine.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    // Ktor WS client — used by HaLink to read HA's non-admin `config/entity_registry/list_for_display`
    // (which carries each entity's device id) to resolve this panel's device-settings URL.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    // mDNS advertise (_ha-paneld._tcp).
    implementation(libs.jmdns)

    // JmDNS/Ktor use SLF4J 2; keep their warnings observable in Android's stderr logcat.
    implementation(libs.slf4j.simple)

    // Strict YAML 1.2 parser for runtime-loadable device profiles. ProfileYaml applies tighter
    // byte/depth/alias/key bounds and maps only into the app's closed schema (never Java objects).
    implementation(libs.snakeyaml.engine)

    // QR code for the on-device config URL (pure-Java encoder; no Android transitive deps).
    implementation("com.google.zxing:core:3.5.4")

    providerAndroidRuntime("org.robolectric:android-all:10-robolectric-5803371")
    providerInstallSourceRuntime("org.robolectric:android-all:11-robolectric-6757853")
    testImplementation(files(androidMockPackageManager))

    // JVM unit tests: pure logic, coroutine serialization, and narrow host platform adapters.
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    // Real org.json — the android.jar stub's returnDefaultValues would silently no-op JSON code under test.
    testImplementation(libs.org.json)
    // Real SQLite over JDBC — deterministic reproduction of cross-connection WAL BUSY contention
    // (Issue #91) that android.jar stubs cannot exercise; never packaged in the APK.
    testImplementation(libs.sqlite.jdbc)
    // Deterministic interleaving exploration for process-global ownership races; never packaged in the APK.
    testImplementation(libs.lincheck)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    projectType.set(Component.Type.APPLICATION)
    componentGroup.set("io.panelassistant")
    componentName.set("ha-paneld")
    componentVersion.set(android.defaultConfig.versionName ?: "unspecified")
    // GitHub's SBOM attestation parser requires a CycloneDX serialNumber.
    includeBomSerialNumber.set(true)
    includeBuildSystem.set(false)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/bom.json"))
    xmlOutput.unsetConvention()
}

// Compile the CDP relay (helper/cdprelay.c) into assets at build time for the fleet ABIs, using the
// pinned NDK that's already present in the Docker toolchain image and CI — so the repo ships source,
// not prebuilt binaries. Extracted + launched at runtime by control/CdpRelay.kt.
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.outputs.forEach { output ->
        output.versionCode.set(1)
        output.versionName.set(debugVersionName)
    }
}

val ndkDirectory = androidComponents.sdkComponents.sdkDirectory.map {
    it.dir("ndk/${android.ndkVersion}")
}
val ndkToolchainBin = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")

val compileCdpRelayArm64 = tasks.register<Exec>("compileCdpRelayArm64") {
    val src = rootProject.file("helper/cdprelay.c")
    val policy = rootProject.file("helper/cdprelay_policy.h")
    val output = file("src/main/assets/cdprelay-arm64")
    inputs.files(src, policy).withPropertyName("relaySources").withPathSensitivity(PathSensitivity.RELATIVE)
    // The NDK version pins the compiler; fingerprinting its ~8,700 files on every fresh runner cost more
    // than the compile. The output is a pure function of the declared inputs, so it can come from the cache.
    outputs.cacheIf { true }
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(output)
    workingDir(rootDir)
    commandLine(
        ndkToolchainBin.resolve("aarch64-linux-android26-clang"), "-O2", "-s",
        "-o", output.relativeTo(rootDir).invariantSeparatorsPath, src.relativeTo(rootDir).invariantSeparatorsPath,
    )
}

val compileCdpRelayArm32 = tasks.register<Exec>("compileCdpRelayArm32") {
    val src = rootProject.file("helper/cdprelay.c")
    val policy = rootProject.file("helper/cdprelay_policy.h")
    val output = file("src/main/assets/cdprelay-arm")
    inputs.files(src, policy).withPropertyName("relaySources").withPathSensitivity(PathSensitivity.RELATIVE)
    // The NDK version pins the compiler; fingerprinting its ~8,700 files on every fresh runner cost more
    // than the compile. The output is a pure function of the declared inputs, so it can come from the cache.
    outputs.cacheIf { true }
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(output)
    workingDir(rootDir)
    commandLine(
        ndkToolchainBin.resolve("armv7a-linux-androideabi26-clang"), "-O2", "-s",
        "-o", output.relativeTo(rootDir).invariantSeparatorsPath, src.relativeTo(rootDir).invariantSeparatorsPath,
    )
}

val compileCdpRelay = tasks.register("compileCdpRelay") {
    dependsOn(compileCdpRelayArm64, compileCdpRelayArm32)
}

// Carry the matching root-helper protocol inside the APK as a migration backstop. Provisioning remains
// the durable installation path; after an in-app self-update, direct-su panels can atomically launch the
// new helper before exposing helper-versioned features. API 26 matches the app's supported floor.
val bundledRootHelperSources = rootProject.fileTree("helper/src") { include("*.c") }
val bundledRootHelperArm64 = file("src/main/assets/hapaneld-helper-arm64")
val bundledRootHelperArm32 = file("src/main/assets/hapaneld-helper-arm")

val compileBundledRootHelperArm64 = tasks.register<Exec>("compileBundledRootHelperArm64") {
    // Relative arguments and input paths keep the cache key free of the checkout's location, so a new
    // worktree reuses a compile from another one instead of rebuilding the helper.
    val sourcePaths = bundledRootHelperSources.files.sortedBy(File::getName).map { it.relativeTo(rootDir).invariantSeparatorsPath }
    inputs.files(helperIdentityFiles).withPropertyName("helperSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("helper/source-id.sh")).withPropertyName("sourceId").withPathSensitivity(PathSensitivity.RELATIVE)
    // The NDK version pins the compiler; fingerprinting its ~8,700 files on every fresh runner cost more
    // than the compile. The output is a pure function of the declared inputs, so it can come from the cache.
    outputs.cacheIf { true }
    inputs.property("helperBuildId", helperBuildId)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(bundledRootHelperArm64)
    commandLine(
        ndkToolchainBin.resolve("aarch64-linux-android26-clang"), "-O2", "-s", "-Ihelper/src",
        "-DHAPANELD_BUILD_ID=\"$helperBuildId\"",
        "-o", bundledRootHelperArm64.relativeTo(rootDir).invariantSeparatorsPath, *sourcePaths.toTypedArray(), "-llog",
    )
    workingDir(rootDir)
}

val compileBundledRootHelperArm32 = tasks.register<Exec>("compileBundledRootHelperArm32") {
    // Relative arguments and input paths keep the cache key free of the checkout's location, so a new
    // worktree reuses a compile from another one instead of rebuilding the helper.
    val sourcePaths = bundledRootHelperSources.files.sortedBy(File::getName).map { it.relativeTo(rootDir).invariantSeparatorsPath }
    inputs.files(helperIdentityFiles).withPropertyName("helperSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("helper/source-id.sh")).withPropertyName("sourceId").withPathSensitivity(PathSensitivity.RELATIVE)
    // The NDK version pins the compiler; fingerprinting its ~8,700 files on every fresh runner cost more
    // than the compile. The output is a pure function of the declared inputs, so it can come from the cache.
    outputs.cacheIf { true }
    inputs.property("helperBuildId", helperBuildId)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.file(bundledRootHelperArm32)
    commandLine(
        ndkToolchainBin.resolve("armv7a-linux-androideabi26-clang"), "-O2", "-s", "-Ihelper/src",
        "-DHAPANELD_BUILD_ID=\"$helperBuildId\"",
        "-o", bundledRootHelperArm32.relativeTo(rootDir).invariantSeparatorsPath, *sourcePaths.toTypedArray(), "-llog",
    )
    workingDir(rootDir)
}

val compileBundledRootHelper = tasks.register("compileBundledRootHelper") {
    dependsOn(compileBundledRootHelperArm64, compileBundledRootHelperArm32)
}
val verifyBundledRootHelperBuildIdentity = tasks.register("verifyBundledRootHelperBuildIdentity") {
    dependsOn(compileBundledRootHelper)
    inputs.files(bundledRootHelperArm64, bundledRootHelperArm32)
    inputs.property("helperBuildId", helperBuildId)
    // Capture plain values: an action that reads script-level vals holds a reference to the build
    // script object, which the configuration cache cannot serialize.
    val binaries = listOf(bundledRootHelperArm64, bundledRootHelperArm32)
    val expectedRecord = "BUILDID $helperBuildId"
    doLast {
        val recordPattern = Regex("""BUILDID [0-9a-f]{64}""")
        binaries.forEach { binary ->
            val bytes = binary.readBytes()
            check(
                bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                    bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte(),
            ) { "${binary.name} is not an ELF binary" }
            val records = recordPattern.findAll(bytes.toString(Charsets.ISO_8859_1))
                .map { it.value }
                .toList()
            check(records == listOf(expectedRecord)) {
                "${binary.name} must contain exactly one '$expectedRecord' record; found $records"
            }
        }
    }
}
// Refuse stale API metadata before packaging any variant after a release version allocation.
val verifyOpenApiVersion = tasks.register("verifyOpenApiVersion") {
    val document = file("src/main/assets/openapi.json")
    val expectedVersion = requireNotNull(appVersion.getProperty("versionName"))
    inputs.file(document)
    inputs.property("versionName", expectedVersion)
    doLast {
        val info = (JsonSlurper().parse(document) as Map<*, *>)["info"] as Map<*, *>
        check(info["version"] == expectedVersion) {
            "openapi.json info.version ${info["version"]} must match versionName $expectedVersion in app/version.properties"
        }
    }
}
tasks.named("preBuild") { dependsOn(compileCdpRelay, verifyBundledRootHelperBuildIdentity, verifyOpenApiVersion) }

val prepareDebugAssets = tasks.register<Sync>("prepareDebugAssets") {
    dependsOn(compileCdpRelay, verifyBundledRootHelperBuildIdentity, verifyOpenApiVersion)
    from("src/main/assets")
    into(debugAssetsDirectory)
    val openApi = debugAssetsDirectory.get().file("openapi.json").asFile
    val versionName = debugVersionName
    doLast {
        @Suppress("UNCHECKED_CAST")
        val document = JsonSlurper().parse(openApi) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val info = document["info"] as MutableMap<String, Any?>
        info["version"] = versionName
        openApi.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n")
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(prepareDebugAssets) }

// Built outside helper/build, which the helper Makefile's clean deletes while a JVM run may be using it.
val helperSocketTestServer = layout.buildDirectory.file("helper-socket-test/socket-test-server").get().asFile
val buildHelperSocketTestServer = tasks.register<Exec>("buildHelperSocketTestServer") {
    workingDir(rootProject.file("helper"))
    commandLine("make", "BUILD=${helperSocketTestServer.parentFile.absolutePath}", "socket-test-server")
    inputs.files(
        rootProject.file("helper/Makefile"),
        rootProject.fileTree("helper/src"),
        rootProject.file("helper/test/socket_server.c"),
        rootProject.file("helper/test/sysexec_stub.c"),
        rootProject.file("helper/test/sysexec_stub.h"),
    )
    outputs.file(helperSocketTestServer)
}

// The JNI libraries' binding code built for the host, so NativeLibraryBindingTest loads both against
// the real Kotlin classes (a missed package rename otherwise fails only on a panel).
val hostJniLibraries = layout.buildDirectory.dir("host-jni")
val buildHostJniLibraries = tasks.register<Exec>("buildHostJniLibraries") {
    commandLine("bash", file("src/test/native/build-host-jni.sh").absolutePath, hostJniLibraries.get().asFile.absolutePath)
    inputs.files(
        file("src/test/native"),
        file("src/main/cpp/led_jni.c"),
        file("src/main/cpp/home_lock_jni.c"),
        file("src/main/cpp/microwakeword/MicroWakeWord_jni.cpp"),
        file("src/main/cpp/microwakeword/MicroWakeWordEngine.h"),
        file("src/main/cpp/microwakeword/MicroFrontendWrapper.h"),
        file("src/main/cpp/microwakeword/Logging.h"),
    )
    outputs.dir(hostJniLibraries)
}

// Files the JVM unit tests read from the working tree at run time, outside the test classpath: whole-tree
// source scans, source-slicing wiring contracts, shipped web assets, the manifest, resource XML, build
// scripts and a few repository documents and fixtures. Gradle fingerprints only declared inputs, so an
// undeclared read lets an edit leave the Test task UP-TO-DATE, or lets the build cache replay a green
// recorded under different bytes as FROM-CACHE. RuntimeReadInputsContractTest checks every runtime path
// literal in app/src/test against these lists, so a new undeclared read fails that test instead of
// silently caching. Entries are app-module relative; `../` reaches the repository root.
val unitTestRuntimeReadDirectories = listOf(
    "src/main/kotlin", // StringTemplateEscapeContractTest and HaTimestampTest walk every source file
    "src/main/assets", // web assets, OpenAPI, i18n catalogues, device profiles, wake-word models
    "src/main/res", // native strings, drawables, backup rules and locale configuration
    "../docs/profiles/examples", // BundledProfileParityTest
    "../docs/profiles/unofficial", // BundledProfileFixtures
    "../helper/src", // HelperSocketCompositionTest reads screen.c and runs a server built from this tree
    "src/test/js", // Node scripts the entity UI contract tests execute; not on the test classpath
)
val unitTestRuntimeReadFiles = listOf(
    "build.gradle.kts",
    "version.properties", // ReleaseIdentityContractTest compares BuildConfig with the declared release identity
    "src/main/AndroidManifest.xml",
    "src/test/resources/panel-assistant-contract/android_producer_v1.json", // producer recorder reads the checked-in corpus
    "../settings.gradle.kts",
    "../gradle/libs.versions.toml",
    "../helper/Makefile", // the socket test server recipe, with the three test sources below
    "../helper/source-id.sh",
    "../helper/test/replacement_handoff.py",
    "../helper/test/socket_server.c",
    "../helper/test/sysexec_stub.c",
    "../helper/test/sysexec_stub.h",
    "../tools/tests/fixtures/database-compatibility-vectors.tsv",
    "../tools/test/fixtures/dashboard-path-parity.json",
    "../tools/test/fixtures/info-fixture.html",
    "../tools/profile-editor/package.json", // ProfileUiSourceTest reads these three, never builds the bundle
    "../tools/profile-editor/package-lock.json",
    "../tools/profile-editor/build.mjs",
)
// Build outputs written into the assets directory by the native compile tasks above. Tests compare
// their names only, never their bytes, so a recompiled binary must not invalidate the test task.
val unitTestGeneratedAssetExcludes = listOf("cdprelay-arm*", "hapaneld-helper-arm*")

// The JVM suites are fork-safe: debug and release running concurrently with three forks each pass on the
// project runner. Half the cores, capped at six, is the measured knee on an 18-core workstation: a warm
// debug run took 91 s with three forks, 70 s with six, 69 s with eight and 65 s with ten, where ten also
// surfaced a load-sensitive race in LogCaptureTest. hapaneld.testForks overrides.
val unitTestForks = providers.gradleProperty("hapaneld.testForks").map { it.toInt() }
    .orElse((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 6))

tasks.withType<Test>().configureEach {
    maxParallelForks = unitTestForks.get()
    // PicklesPageFixtureTest writes the localized pages the browser tests render. Declaring the
    // directory as an output makes a build-cache hit restore that fixture instead of leaving it absent.
    outputs.dir(layout.buildDirectory.dir("test-fixtures")).withPropertyName("testFixtures")
    if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
        dependsOn(buildHelperSocketTestServer)
        // Relative to the project directory (the Test working directory) so the cache key matches across checkouts.
        systemProperty("hapaneld.helper.socketTestServer", helperSocketTestServer.relativeTo(projectDir).invariantSeparatorsPath)
        dependsOn(buildHostJniLibraries)
        systemProperty("hapaneld.test.hostJniDirectory", hostJniLibraries.get().asFile.relativeTo(projectDir).invariantSeparatorsPath)
    }
    // RELATIVE path sensitivity keys the fingerprint on content plus tree-relative path, so a clean
    // checkout at another location reuses the cache while any byte change invalidates it.
    unitTestRuntimeReadDirectories.forEach { relative ->
        val declaration = if (relative == "src/main/assets") {
            inputs.files(fileTree(relative) { exclude(unitTestGeneratedAssetExcludes) })
        } else {
            inputs.dir(relative)
        }
        declaration.withPropertyName("runtimeRead:$relative").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    unitTestRuntimeReadFiles.forEach { relative ->
        inputs.file(relative).withPropertyName("runtimeRead:$relative").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    systemProperty("hapaneld.test.runtimeReadDirectories", unitTestRuntimeReadDirectories.joinToString(File.pathSeparator))
    systemProperty("hapaneld.test.runtimeReadFiles", unitTestRuntimeReadFiles.joinToString(File.pathSeparator))
    systemProperty("hapaneld.test.runtimeReadAssetExcludes", unitTestGeneratedAssetExcludes.joinToString(File.pathSeparator))
    val repositoryDirectory = rootProject.projectDir
    doFirst {
        val repository = repositoryDirectory.toPath().toAbsolutePath().normalize()
        val activeInputs = inputs.files.files.map { it.toPath().toAbsolutePath().normalize() }
            .filter { it.startsWith(repository) }
            .map { repository.relativize(it).toFile().invariantSeparatorsPath }
            .sorted()
        // This generated observation is not an input to itself; keep its size out of JVM arguments.
        val snapshot = temporaryDir.resolve("runtime-inputs.txt")
        snapshot.writeText(activeInputs.joinToString("\n"))
        systemProperty("hapaneld.test.activeTaskInputs", snapshot.absolutePath)
    }
    providers.gradleProperty("autoSleepReplayInput").orNull?.let {
        systemProperty("hapaneld.autoSleepReplay.input", it)
        inputs.file(it)
        inputs.property("autoSleepReplayInputPath", it)
    }
    providers.gradleProperty("autoSleepReplayOutput").orNull?.let {
        systemProperty("hapaneld.autoSleepReplay.output", it)
        inputs.property("autoSleepReplayOutputPath", it)
    }
}

// Keep real platform classes in their own supported Test worker so unrelated JVM fixtures retain SDK stubs.
afterEvaluate {
    val debugTests = tasks.named<Test>("testDebugUnitTest")
    val callerClasses = listOf(
        "io.panelassistant.android.util.AndroidWebViewUpdateIoTest",
        "io.panelassistant.android.http.PaneldServerWebViewApprovalTest",
    )
    // Caller class and method selections run in the dependency worker, including class-name globs.
    val requestedArgs = gradle.startParameter.taskRequests.flatMap { it.args }
    val requestedTests = requestedArgs.mapIndexedNotNull { index, argument ->
        when {
            argument == "--tests" -> requestedArgs.getOrNull(index + 1)
            argument.startsWith("--tests=") -> argument.substringAfter('=')
            else -> null
        }
    }
    val ordinaryUnitTasks = setOf(":app:testDebugUnitTest", "testDebugUnitTest", ":app:testReleaseUnitTest", "testReleaseUnitTest")
    fun callerSelector(pattern: String): Boolean = callerClasses.any { className ->
        val simpleName = className.substringAfterLast('.')
        // Keep broad globs on the ordinary path: they can also select SDK-stub tests.
        val classPattern = pattern.substringBefore("$simpleName.", pattern).let {
            if (it != pattern) it + simpleName else pattern
        }
        val namesCaller = classPattern.endsWith(simpleName)
        val regex = classPattern.split('*').joinToString(".*") { Regex.escape(it) }.toRegex()
        namesCaller && (regex.matches(className) || regex.matches(simpleName))
    }
    val singleOrdinaryTask = requestedArgs.firstOrNull() in ordinaryUnitTasks && requestedArgs.drop(1).all {
        it == "--tests" || it == "--rerun" || it in requestedTests ||
            (it.startsWith("--tests=") && it.substringAfter('=') in requestedTests)
    }
    val callerOnlySelection = singleOrdinaryTask && requestedTests.isNotEmpty() && requestedTests.all(::callerSelector)
    val webViewCallerTests = tasks.register<Test>("testWebViewCaller") {
        description = "Exercises native WebView admission and host-executable Android adapter paths."
        group = "verification"
        dependsOn(providerAndroidRuntimeJar)
        testClassesDirs = debugTests.get().testClassesDirs
        classpath = files(providerAndroidRuntimeJar) + debugTests.get().classpath
        useJUnit()
        if (callerOnlySelection) {
            requestedTests.forEach { filter.includeTestsMatching(it) }
        } else {
            callerClasses.forEach { filter.includeTestsMatching(it) }
        }
    }
    tasks.withType<Test>().matching { it.name.endsWith("UnitTest") }.configureEach {
        dependsOn(webViewCallerTests)
        callerClasses.forEach { filter.excludeTestsMatching(it) }
        onlyIf("Caller-only selections execute in the isolated worker") { !callerOnlySelection }
    }
}
