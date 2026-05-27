plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

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
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables { useSupportLibrary = true }
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
            isMinifyEnabled = false
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

    // OpenStreetMap tiles via osmdroid: no map API key required.
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.caverock:androidsvg-aar:1.4")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // MGRS conversion (NGA).
    implementation("mil.nga:mgrs:2.1.3")

    // JSON / GeoJSON export.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
