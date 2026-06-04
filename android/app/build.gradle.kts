import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY")
    ?: System.getenv("MAPS_API_KEY")
    ?: ""

val releaseStoreFilePath = providers.gradleProperty("TACTICALMAPS_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("TACTICALMAPS_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("TACTICALMAPS_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("TACTICALMAPS_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("TACTICALMAPS_RELEASE_KEY_PASSWORD"))
val hasReleaseSigning = releaseStoreFilePath.isPresent &&
    releaseStorePassword.isPresent &&
    releaseKeyAlias.isPresent &&
    releaseKeyPassword.isPresent

android {
    namespace = "com.tacticalmaps"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tacticalmaps"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // SVG rasteriser used by SymbolIconFactory to render the milsymbol
    // assets into BitmapDescriptors for Google Maps markers.
    implementation("com.caverock:androidsvg-aar:1.4")

    // Google Maps SDK + Compose bindings. API key is read from
    // local.properties (MAPS_API_KEY=…) or the MAPS_API_KEY env var.
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Google Play Billing — the one-time "unlock_full" in-app product that
    // converts the 3-day free trial into permanent access.
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // MGRS conversion (NGA).
    implementation("mil.nga:mgrs:2.1.3")

    // JSON / GeoJSON export.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

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
}
