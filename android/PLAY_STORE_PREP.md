# Play Store Prep

## Build Outputs

Debug APK:

```sh
./gradlew :app:assembleDebug
```

Release app bundle:

```sh
./gradlew :app:bundleRelease
```

## Release Signing

The release build reads signing credentials from Gradle properties or environment variables:

```sh
TACTICALMAPS_RELEASE_STORE_FILE=/absolute/path/release.jks
TACTICALMAPS_RELEASE_STORE_PASSWORD=...
TACTICALMAPS_RELEASE_KEY_ALIAS=...
TACTICALMAPS_RELEASE_KEY_PASSWORD=...
```

Equivalent Gradle properties can be placed in a private, uncommitted `~/.gradle/gradle.properties`.

## Play Console Declarations

Current Android permissions:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

The app imports PDFs through Android's document picker and keeps imported PDF map copies, drawings, waypoints, and calibration data in app-local storage.

## App Metadata

- Package: `com.tacticalmaps`
- Minimum SDK: 26
- Target SDK: 35
- Version: `0.1.0` / code `1`
