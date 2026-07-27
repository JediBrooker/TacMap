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

Put these in a private, uncommitted `~/.gradle/gradle.properties`. If all four
are absent the release build still assembles, but **unsigned** (Play will
reject it). Enable **Play App Signing** when you create the app in Play Console
(recommended): you keep the upload key above, Google holds the final signing
key, and you can recover if the upload key is ever lost.

**Current upload key (`release.jks`) — already configured:**

- Key alias: `tacticalmaps`
- Credentials live in `~/.gradle/gradle.properties` (the four `TACTICALMAPS_RELEASE_*` props); not committed.
- Upload key SHA-1: `F9:2B:F4:F3:06:D9:54:6F:AE:BC:2A:A5:77:2D:64:17:AD:67:84:FC`
- Upload key SHA-256: `61:AE:AE:49:95:5B:07:BE:4D:05:50:A1:7B:FB:6F:2E:E8:AC:77:6B:7B:E8:83:DE:22:AA:84:7D:A6:29:E0:61`
- ⚠️ Back up `release.jks` **and** its passwords offline (password manager). Losing either makes the app unupdatable under this upload key.

## Google Maps production key (do not skip)

The Maps key is injected from `local.properties` (`MAPS_API_KEY=…`) or the
`MAPS_API_KEY` env var. A production key **must** be restricted in Google Cloud
Console → **APIs & Services → Credentials → (the Maps key)**:

- **Application restrictions:** Android apps
- **API restrictions:** allow **Maps SDK for Android** (otherwise requests are
  rejected no matter what the fingerprint is)

Under Android apps, add `com.tacmap` paired with **all three** SHA-1
fingerprints below — one entry per fingerprint, same package name each time:

| Which key | Where to get the SHA-1 | Makes the map work for… |
| --- | --- | --- |
| **Play App Signing** | Play Console → **Protected with Play** → *Play Store protection* → **Protect app signing key** row → **Manage Play app signing** → *App signing key certificate* | everyone who installs **from the store** |
| **Upload key** | `F9:2B:F4:F3:06:D9:54:6F:AE:BC:2A:A5:77:2D:64:17:AD:67:84:FC` (alias `tacticalmaps`) | local **release** builds |
| **Debug key** | `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android` | **debug** builds (Android Studio / emulator) |

> ⚠️ **Account change (June 2026):** the original developer account could not
> be used to sell the app — its Google payments profile had been deleted and
> Google does not allow re-attaching one. The app is being published from a
> **new developer account** (published as **TacMap**, package `com.tacmap`).
> **Play App Signing generates a new key per developer account**, so the old
> account's SHA-1 below is **obsolete**. The **upload key** SHA-1 is unaffected
> (same `release.jks`).

**New account's Play App Signing SHA-1 — paste here once copied from Protected
with Play → Manage Play app signing → App signing key certificate, and add it
(package `com.tacmap`) to the Maps key in Cloud Console:**

```
<PASTE NEW ACCOUNT APP-SIGNING SHA-1 HERE>   # required for the store map to render
```

Old account's Play App Signing SHA-1 (reference only — **do not use**):

```
B5:07:69:E9:28:FC:FD:7C:6B:79:2F:1F:4B:59:01:06:B8:FB:32:79   # OLD account — obsolete
```

> The same certificate box also lists a **SHA-256** (32 byte-pairs). The Maps
> Android restriction wants the **SHA-1** (20 pairs) — don't paste the SHA-256
> there. Keep the SHA-256 only for Digital Asset Links / App Links if you ever
> add them.

Finding it in the current Play Console layout: there is **no longer** an "App
integrity" sidebar item. Go to **Protected with Play** (shield icon), expand
**Play Store protection**, and click **Manage Play app signing** on the
*Protect app signing key* row.

Without the Play App Signing SHA-1, the map renders **blank** for everyone who
installs from the store even though it works in your local release build. After
adding or changing a fingerprint, allow **~5 minutes** for it to propagate.

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
- `com.android.vending.BILLING` (Play Billing — the in-app unlock)

The app imports PDFs through Android's document picker and keeps imported PDF map copies, drawings, waypoints, and calibration data in app-local storage.

## App Metadata

- Package: `com.tacmap`
- Minimum SDK: 26
- Target SDK: 36 (meets Play's 31 August 2026 app-update requirement)
- Version: `1.2.2` / code `60` (bump `versionCode` for every new upload)

## Play Console store-listing assets still needed

- 512×512 hi-res icon (PNG, 32-bit)
- 1024×500 feature graphic
- ≥2 phone screenshots (plus 7"/10" tablet screenshots if you keep tablet support)
- Short (80 char) + full (4000 char) descriptions
- Privacy policy **URL** (host `docs/PRIVACY_POLICY.md` publicly)
- Data safety form → "No data collected" (mirrors the privacy policy)
- Content rating questionnaire (IARC) → expected: Everyone
- Target audience & content (not directed at children)
