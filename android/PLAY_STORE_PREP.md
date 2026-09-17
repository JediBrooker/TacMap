# Play Store Prep

## Build Outputs

Run these commands from the repository root.

Debug APK:

```sh
./android/gradlew -p android :app:assembleDebug
```

Release app bundle:

```sh
export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)}"
./android/gradlew -p android --no-daemon -PVERSION_CODE=64 \
  :app:verifyReleaseSigning :app:bundleRelease
jarsigner -verify android/app/build/outputs/bundle/release/app-release.aab
unzip -p android/app/build/outputs/bundle/release/app-release.aab \
  base/assets/THIRD_PARTY_NOTICES.txt \
  | cmp - android/app/src/main/assets/THIRD_PARTY_NOTICES.txt
```

Run this only after confirming that code 64 is still unused in Play Console.
The build requires JDK 17; set `JAVA_HOME` explicitly if Homebrew's
`openjdk@17` is not the installation you use.
The signing guard validates the private upload-key configuration without
printing credentials; `jarsigner` then verifies the produced bundle signature.
The final command fails if the release bundle omits or changes the Java-
WebSocket/SLF4J notices shown in About. Run all three checks against the exact
AAB selected in Play Console, after the source tree is frozen.

## Release Signing

First, generate the upload keystore (run once — it is the permanent identity
of the app on Play, so back it up offline):

```sh
scripts/android_release_keystore.sh
```

That writes `android/keystore/release.jks` (gitignored) and prints the
SHA-1/SHA-256 fingerprints. The release build then reads signing credentials
from Gradle properties or environment variables:

```sh
TACTICALMAPS_RELEASE_STORE_FILE=/absolute/path/release.jks
TACTICALMAPS_RELEASE_STORE_PASSWORD=...
TACTICALMAPS_RELEASE_KEY_ALIAS=...
TACTICALMAPS_RELEASE_KEY_PASSWORD=...
```

Put these in a private, uncommitted `~/.gradle/gradle.properties`. All four are
required together. `bundleRelease` verifies them at execution time and fails
with only the missing property names; it never prints credential values. An
unsigned `assembleRelease` remains available for local R8/build diagnostics but
is not uploadable to Play. Enable **Play App Signing**: the keystore below is the
upload key, while Google protects the final app-signing key.

**Current upload key (`release.jks`) — already configured:**

- Key alias: `tacticalmaps`
- Credentials live in `~/.gradle/gradle.properties` (the four `TACTICALMAPS_RELEASE_*` props); not committed.
- Upload key SHA-1: `F9:2B:F4:F3:06:D9:54:6F:AE:BC:2A:A5:77:2D:64:17:AD:67:84:FC`
- Upload key SHA-256: `61:AE:AE:49:95:5B:07:BE:4D:05:50:A1:7B:FB:6F:2E:E8:AC:77:6B:7B:E8:83:DE:22:AA:84:7D:A6:29:E0:61`
- ⚠️ Back up `release.jks` **and** its passwords offline (password manager). Losing either makes the app unupdatable under this upload key.

The local `android/keystore/tacticalmaps.jks` file is a distinct, legacy
keystore blob and is **not** the path selected by the current private Gradle
configuration. It has been retained rather than deleted because deleting an
unclassified signing key is irreversible. Keep both files mode `0600`; use
`release.jks` for builds, and compare the legacy certificate fingerprint with
historic Play Console/release records before deciding whether its offline
backup can be archived or destroyed. Never switch upload keys merely because a
second file exists.

## Optional Esri raster key

TacMap does not link the Google Maps SDK and needs no Google Maps API key or
certificate restriction. Both platforms render raster tiles through app-owned
map engines.

The opt-in Esri satellite/topographic/OpenStreetMap-style sources use an ArcGIS
Location Platform key. Provide it as `ESRI_API_KEY` in uncommitted
`local.properties`, as a Gradle property, or as an environment variable. The
key ships in the APK and is a quota control, not a secret:

- restrict it to only the ArcGIS services TacMap uses;
- keep pay-as-you-go disabled unless the release owner has explicitly accepted
  that billing risk;
- verify the configured expiry (`2027-06-30` in the current source) and rotate
  it before the build-time expiry guard fires; and
- test each Esri style in the internal-track bundle.

An empty key disables Esri styles without affecting imported PDF/GeoPDF or
MBTiles maps. OpenTopoMap is keyless but is opt-in, community-hosted, and should
not be treated as an availability guarantee.

## In-app purchase (3-day trial → one-time unlock)

The app is **free with a 3-day trial**, then a one-time managed product
unlocks it permanently (no subscription). Create the product in Play Console →
**Monetise → Products → In-app products**:

- Product ID **`unlock_full`** (must match `BillingManager.PRODUCT_ID`)
- Purchase option ID: **`unlock-full`** (hyphen — underscores are not allowed in
  this field; the app never reads it, so any value works), Purchase type **Buy**
- Set the price and **activate** it
- Add **License testers** (Setup → License testing) to test purchases for free
- IAP revenue needs the **merchant account** (same blocker as a paid app — ships
  from the new developer account)

Full design, code locations, and testing: [docs/MONETISATION.md](../docs/MONETISATION.md).

## Play Console Declarations

Current Android permissions:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+ runtime request for the active location
  foreground-service notice)
- `com.android.vending.BILLING` (Play Billing — the in-app unlock)

TacMap does not request `ACCESS_BACKGROUND_LOCATION` or broad storage/media
access. Precise foreground location plus a `location` foreground service and
ongoing notification support either a track the user explicitly starts or v3
screen-off Unit Sync presence that the user separately enables in OPSEC while
**Share my location** is on. Complete the Play foreground-service declaration
and provide demonstration video/steps for both user-visible flows.

The system document picker supplies an explicit file grant. TacMap copies PDF,
GeoPDF, and MBTiles maps into app-private storage. GeoJSON, KML, and KMZ are read
under that grant and parsed into encrypted mission-object stores; TacMap does
not retain those source files after the import completes. Waypoints,
drawings/layers, calibration metadata, and the track log are encrypted at rest;
imported map bytes remain in their original format in private storage.

## App Metadata

- Package: `com.tacmap`
- Minimum SDK: 26
- Target SDK: 36 (meets Play's 31 August 2026 app-update requirement)
- Current source: `2.0.0` / code `64`
- Current release candidate: `2.0.0` (`versionCode` 64). Inject a newer unique,
  monotonic `VERSION_CODE` in both the command and release record if this
  candidate has already been uploaded.
- Verified signed bundle:
  `android/app/build/outputs/bundle/release/app-release.aab` (9,763,686 bytes,
  SHA-256
  `75140fbc4de926c3376353f10365ce3631e1671518e8e503dcae9e6fa67285cc`).
  Debug and Release JVM suites each executed 569 tests (568 passed, one
  intentional conditional provider-key skip); the pinned API 36 emulator suite
  executed 22 tests (21 passed, one opt-in live-relay interop skip), with no
  failures.

## Play Console listing and declarations

- Verify and upload the existing 512×512 icon and 1024×500 feature graphic.
  The approved 2.0 phone subset is `01-hero.png` and `02-unit-sync.png`; it meets
  Play's two-screenshot minimum. Phone screens 03–10 and every tablet screen
  remain excluded until recaptured from the final build. Apply the preflight
  checks in `docs/store/android/README.md` before approving more.
- Short (80 char) + full (4000 char) descriptions
- Privacy policy **URL**: `https://tacmap.app/privacy` (generated
  deterministically from `docs/PRIVACY_POLICY.md`; verify production after each
  deployment)
- Data safety form reviewed against the exact release, privacy policy, and
  current Google definitions. Do not infer “No data collected” merely from the
  absence of analytics: optional providers receive coordinates/queries, Unit
  Sync crosses a Cloudflare relay that receives an admission token plus clear
  protocol/traffic metadata around encrypted mission payloads, and Play Billing
  processes commerce data. Record the account-holder’s classification decision.
- Production currently displays **No data shared** and **No data collected**
  and links to the old GitHub policy. Treat those as stale release blockers:
  complete the current form (including optional/ephemeral and end-to-end-
  encryption exceptions), publish the `tacmap.app/privacy` URL, and verify the
  public listing after review rather than copying the old answers.
- Foreground-service/location declaration for user-started background GPX
  recording and separately opted-in v3 Background Unit Sync presence, including
  notification behaviour for both.
- Content rating and target-audience questionnaires completed by the release
  owner (the app is not directed at children).
- Data deletion section should explain that there is no TacMap account or
  developer mission database, and describe on-device deletion and provider
  boundaries without promising deletion of store/provider records.

Public copy must use **Export All Mission Objects** for the GeoJSON export of
waypoints, symbols, drawings, and layers. A recorded route remains a separate
GPX export.
