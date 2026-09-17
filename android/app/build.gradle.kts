import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Properties
import java.util.zip.ZipFile
import jdk.security.jarsigner.JarSigner

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// ArcGIS Location Platform key for the Esri satellite basemap. Same resolution
// order as the Maps key. It ends up in BuildConfig, which means it ends up in
// the APK: this is a quota/billing control, not a secret, and anyone can pull
// it out of the binary. Keep pay-as-you-go OFF on the Esri account so the worst
// a leaked key can do is burn the free tier, not run up a bill. Empty is fine;
// users can still disable online basemaps, and offline packs never touch it.
val esriApiKey: String = localProperties.getProperty("ESRI_API_KEY")
    ?: providers.gradleProperty("ESRI_API_KEY").orNull?.takeIf { it.isNotBlank() }
    ?: System.getenv("ESRI_API_KEY")
    ?: ""

// CI injects a monotonic build number via -PVERSION_CODE so releases don't
// require a manual bump; local/dev and the default fall back to the pinned
// value below. Only the marketing versionName stays hardcoded.
val injectedVersionCode: Int? = providers.gradleProperty("VERSION_CODE").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }?.toInt()

val releaseStoreFilePath = providers.gradleProperty("TACTICALMAPS_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("TACTICALMAPS_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("TACTICALMAPS_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("TACTICALMAPS_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_KEY_PASSWORD"))

private val releaseBundleTaskPattern =
    Regex("^bundle((?:[A-Z][A-Za-z0-9]*)?Release)$")
private val releaseBundleStageTaskPattern =
    Regex("^(?:package|sign)((?:[A-Z][A-Za-z0-9]*)?Release)Bundle$")

private fun releaseBundleVariant(taskPath: String): String? {
    val taskName = taskPath.substringAfterLast(':')
    return releaseBundleTaskPattern.matchEntire(taskName)?.groupValues?.get(1)
        ?: releaseBundleStageTaskPattern.matchEntire(taskName)?.groupValues?.get(1)
}

private fun isReleaseBundleEntryPoint(taskName: String): Boolean =
    releaseBundleVariant(taskName) != null

// Store-upload bundles must never be produced silently unsigned. Validation
// stays inside doLast so merely configuring Debug/tests/lint/R8 does not read
// signing secrets or require a release keystore. assembleRelease deliberately
// remains outside this guard: it is the local unsigned R8 verification path.
val releaseSigningInputs = linkedMapOf(
    "TACTICALMAPS_RELEASE_STORE_FILE" to releaseStoreFilePath,
    "TACTICALMAPS_RELEASE_STORE_PASSWORD" to releaseStorePassword,
    "TACTICALMAPS_RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "TACTICALMAPS_RELEASE_KEY_PASSWORD" to releaseKeyPassword,
)

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Checks release signing inputs before producing a store-upload bundle."

    doLast {
        val missingNames = releaseSigningInputs
            .filterValues { it.orNull.isNullOrBlank() }
            .keys
            .sorted()
        if (missingNames.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing property/environment name(s): " +
                    missingNames.joinToString(", ")
            )
        }

        val configuredPath = releaseStoreFilePath.get().trim()
        val configuredFile = runCatching { project.file(configuredPath) }.getOrElse {
            throw GradleException(
                "Release signing validation failed: " +
                    "TACTICALMAPS_RELEASE_STORE_FILE is not a valid file reference."
            )
        }
        if (!configuredFile.isFile || !configuredFile.canRead()) {
            throw GradleException(
                "Release signing validation failed: " +
                    "TACTICALMAPS_RELEASE_STORE_FILE is missing, unreadable, or not a regular file."
            )
        }

        val configuredStorePassword = releaseStorePassword.get()
        val configuredAlias = releaseKeyAlias.get().trim()
        val configuredKeyPassword = releaseKeyPassword.get()
        val storePasswordChars = configuredStorePassword.toCharArray()
        val keyPasswordChars = configuredKeyPassword.toCharArray()
        try {
            val keyStore = listOf("JKS", "PKCS12").firstNotNullOfOrNull { storeType ->
                runCatching {
                    KeyStore.getInstance(storeType).also { candidate ->
                        configuredFile.inputStream().use { input ->
                            candidate.load(input, storePasswordChars)
                        }
                    }
                }.getOrNull()
            } ?: throw GradleException(
                "Release signing validation failed: TACTICALMAPS_RELEASE_STORE_FILE is not a " +
                    "JKS/PKCS12 keystore, or TACTICALMAPS_RELEASE_STORE_PASSWORD is invalid."
            )

            if (!keyStore.containsAlias(configuredAlias)) {
                throw GradleException(
                    "Release signing validation failed: TACTICALMAPS_RELEASE_KEY_ALIAS does not " +
                        "identify an entry in the configured keystore."
                )
            }
            if (!keyStore.isKeyEntry(configuredAlias)) {
                throw GradleException(
                    "Release signing validation failed: TACTICALMAPS_RELEASE_KEY_ALIAS does not " +
                        "identify a private-key entry."
                )
            }
            val signingKey = runCatching {
                keyStore.getKey(configuredAlias, keyPasswordChars)
            }.getOrNull()
            if (signingKey !is PrivateKey) {
                throw GradleException(
                    "Release signing validation failed: TACTICALMAPS_RELEASE_KEY_PASSWORD cannot " +
                        "unlock a private signing key in the configured entry."
                )
            }
            if (keyStore.getCertificate(configuredAlias) == null) {
                throw GradleException(
                    "Release signing validation failed: TACTICALMAPS_RELEASE_KEY_ALIAS does not " +
                        "identify an entry with a signing certificate."
                )
            }
        } finally {
            storePasswordChars.fill('\u0000')
            keyPasswordChars.fill('\u0000')
        }
    }
}

tasks.register("verifyReleaseSigningGuardCoverage") {
    group = "verification"
    description = "Checks release-bundle entry-point matching, including flavored variants."
    doLast {
        val expectedVariants = linkedMapOf(
            "bundleRelease" to "Release",
            "packageReleaseBundle" to "Release",
            "signReleaseBundle" to "Release",
            "bundleFieldRelease" to "FieldRelease",
            "packageFieldReleaseBundle" to "FieldRelease",
            "signFieldReleaseBundle" to "FieldRelease",
        )
        expectedVariants.forEach { (taskName, expectedVariant) ->
            check(releaseBundleVariant(taskName) == expectedVariant) {
                "$taskName is not covered by the release signing guard"
            }
        }
        listOf("assembleRelease", "bundleDebug", "signDebugBundle").forEach { taskName ->
            check(releaseBundleVariant(taskName) == null) {
                "$taskName must remain outside the release signing guard"
            }
        }
    }
}

tasks.configureEach {
    if (isReleaseBundleEntryPoint(name)) {
        dependsOn(verifyReleaseSigning)
    }
    if (name == "preBuild") {
        // Every AGP variant enters through preBuild. This ordering rule does
        // not add the guard to a graph, so standalone assembleRelease remains
        // unsigned. When an exact, abbreviated, flavored, or aggregate bundle
        // selector pulls a guarded bundle task into the graph, however, the
        // already-present guard must finish before any variant work can begin.
        mustRunAfter(verifyReleaseSigning)
    }
    if (name.startsWith("sign") && releaseBundleVariant(name) != null) {
        // AGP does not accept Provider-backed signing credentials and freezes
        // its concrete signing DSL during configuration. Keep AGP's bundle
        // output unsigned, then sign that exact output in-process here after
        // the execution-time guard has validated every credential. Always run
        // this finalizer so a rotated upload key cannot reuse a stale signature.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Release bundle signatures are credential-specific") { true }
        doLast {
            val bundleFile = outputs.files.files.singleOrNull { output ->
                output.isFile && output.extension.equals("aab", ignoreCase = true)
            } ?: throw GradleException(
                "Release bundle signing failed: AGP did not expose exactly one AAB output."
            )

            val configuredFile = runCatching {
                project.file(releaseStoreFilePath.get().trim())
            }.getOrElse {
                throw GradleException(
                    "Release bundle signing failed after sanitized credential validation."
                )
            }
            val configuredStorePassword = releaseStorePassword.get()
            val configuredAlias = releaseKeyAlias.get().trim()
            val configuredKeyPassword = releaseKeyPassword.get()
            val storePasswordChars = configuredStorePassword.toCharArray()
            val keyPasswordChars = configuredKeyPassword.toCharArray()
            val signedTemporaryFile = File(temporaryDir, "verified-release-bundle.aab")
            try {
                val keyStore = listOf("JKS", "PKCS12").firstNotNullOfOrNull { storeType ->
                    runCatching {
                        KeyStore.getInstance(storeType).also { candidate ->
                            configuredFile.inputStream().use { input ->
                                candidate.load(input, storePasswordChars)
                            }
                        }
                    }.getOrNull()
                } ?: throw IllegalStateException("validated keystore became unavailable")
                val signingEntry = keyStore.getEntry(
                    configuredAlias,
                    KeyStore.PasswordProtection(keyPasswordChars),
                ) as? KeyStore.PrivateKeyEntry
                    ?: throw IllegalStateException("validated signing entry became unavailable")

                ZipFile(bundleFile).use { unsignedBundle ->
                    FileOutputStream(signedTemporaryFile).use { output ->
                        JarSigner.Builder(signingEntry)
                            .signerName("TACMAP")
                            .build()
                            .sign(unsignedBundle, output)
                    }
                }
                try {
                    Files.move(
                        signedTemporaryFile.toPath(),
                        bundleFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        signedTemporaryFile.toPath(),
                        bundleFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (failure: Throwable) {
                signedTemporaryFile.delete()
                val failureTypes = generateSequence(failure) { it.cause }
                    .map { it.javaClass.simpleName }
                    .joinToString(" -> ")
                logger.error("Release bundle signing failure type(s): $failureTypes")
                throw GradleException(
                    "Release bundle signing failed after sanitized credential validation."
                )
            } finally {
                storePasswordChars.fill('\u0000')
                keyPasswordChars.fill('\u0000')
            }
        }
    }
}

android {
    namespace = "com.tacmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tacmap"
        minSdk = 26
        targetSdk = 36
        versionCode = injectedVersionCode ?: 66
        versionName = "2.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "ESRI_API_KEY", "\"$esriApiKey\"")
        // Esri API keys expire. When this one lapses the Esri basemap starts
        // 401ing in the field, so EsriKeyExpiryTest fails the build 60 days out
        // to force a rotation before users notice. ISO-8601, UTC.
        buildConfigField("String", "ESRI_KEY_EXPIRY", "\"2027-06-30\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

configurations.all {
    resolutionStrategy {
        // CVE-2024-30172: crafted Ed25519 key/sig infinite-loops BC <= 1.78.
        // PDFBox 2.0.27.0 pulls bcprov/bcpkix/bcutil transitively at 1.72;
        // Bouncy Castle's 1.85 Android artifacts shipped a duplicate-class
        // packaging bug. Its point releases are intentionally module-specific:
        // provider 1.85.2 and utility 1.85.1 patch the 1.85 family while PKIX
        // remains at 1.85. Pin that published compatible set so nothing can
        // resolve the vulnerable transitive release or the broken base jars.
        force("org.bouncycastle:bcprov-jdk15to18:1.85.2")
        force("org.bouncycastle:bcpkix-jdk15to18:1.85")
        force("org.bouncycastle:bcutil-jdk15to18:1.85.1")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Pin a current fragment (a transitive dep otherwise resolves to the
    // long-outdated 1.1.0 that Play flags). 1.8.x targets modern SDKs.
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // SVG rasteriser used by SymbolIconFactory to render the milsymbol
    // assets into bitmaps for the custom renderer's waypoint symbols.
    implementation("com.caverock:androidsvg-aar:1.4")

    // No Google Maps SDK. The map is a custom Compose/Canvas renderer
    // (com.tacmap.map.render.*) that draws raster tiles + overlays itself, so
    // nothing here phones home to Google for map content.

    // Location is read through the platform LocationManager (GPS_PROVIDER,
    // on-device), not Google's fused provider — see LocationService. Play
    // Billing brings play-services-location transitively, but app code does
    // not call that SDK for location.

    // Google Play Billing — the one-time "unlock_full" in-app product that
    // converts the 3-day free trial into permanent access. The one Google
    // dependency genuinely unavoidable for a Play-Store paid app. It checks
    // entitlement on launch/throttled foreground transitions and loads product
    // details only while a paywall is visible; no mission data is included.
    implementation("com.android.billingclient:billing:9.1.0")

    // (Trial clock is local-only now - no Block Store, no Play Services. See
    // TrialManager: the trade is that the Android trial resets on reinstall.)

    // MGRS conversion (NGA).
    implementation("mil.nga:mgrs:2.1.3")

    // JSON / GeoJSON export.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // WebSocket client for real-time unit sync.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Unit Sync uses this RFC 6455 transport instead of OkHttp's WebSocket
    // reader because Draft_6455 rejects each oversized declared frame before
    // allocating its payload. TacMap's bounded subclass additionally checks
    // every intermediate continuation before retaining fragmented aggregates.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    // Deliberately discard the library's optional diagnostic logging: Unit Sync
    // exposes user-facing failures itself and must not emit relay metadata.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")

    // Ed25519 for per-device unit-sync identity (presence signing), via the
    // low-level org.bouncycastle.crypto API (no JCA provider to register) so it
    // interops with iOS CryptoKit Curve25519 (both RFC 8032). minSdk 26 has no
    // platform Ed25519 (that landed in API 33). Bouncycastle is already on the
    // classpath transitively (pdfbox-android); pin the same artifact so it's an
    // explicit, single dependency rather than an accidental transitive one.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.85.2")

    // PDF parsing — used to extract OGC GeoPDF / Adobe LGIDict
    // georeferencing dictionaries so imported GeoPDFs land in the
    // correct geographic position without manual calibration.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM unit tests (run on the host with `./gradlew testDebugUnitTest` —
    // no emulator). The pure-logic suites for the affine solve, MGRS
    // formatting, and GeoJSON export live in src/test.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
