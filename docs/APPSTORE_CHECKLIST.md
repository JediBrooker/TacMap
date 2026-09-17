# App Store submission checklist

This file separates repository facts from App Store Connect and legal choices.
The current release candidate is **2.0.0 (build 64)**. It was uploaded
successfully to App Store Connect on 28 August 2026, and Apple reported it
`VALID`, `APP_STORE_ELIGIBLE`, and `usesNonExemptEncryption: false` (Delivery
UUID `4708a468-1dfc-4063-9853-f8c3e51c2c3f`). Submission, privacy answers,
TestFlight acceptance, and review remain account-holder gates.

## Apple-side release gates

- [ ] Confirm the Apple Developer Program membership, agreements, tax, and
      banking status in App Store Connect.
- [ ] Confirm the App ID and bundle identifier `com.tacticalmaps.app` and that
      the distribution profile selected by the Release configuration is valid.
- [ ] Confirm the non-consumable IAP `com.tacticalmaps.app.unlock` is active,
      localised, priced, and submitted with the app version. TacMap provides a
      three-day trial followed by a one-time unlock, not a subscription.
- [x] Generate `site/public/privacy.html` deterministically from the current
      [privacy policy](PRIVACY_POLICY.md).
- [x] Deploy and verify `https://tacmap.app/privacy` matches the release
      source before entering that URL in App Store Connect.
- [ ] Upload only the screenshot subsets approved in `docs/store/ios/README.md`.
      The checked-in iPhone 02–10 set is approved for 2.0; exclude iPhone 01 and
      iPad 01, 02, and 07 until they are recaptured from the current UI.
- [x] Regenerate iPhone Unit Sync, recording, import/export, and search screens
      from the final 2.0 candidate with the current relay disclosure and labels.
- [ ] Test the archive through TestFlight on at least one physical iPhone and,
      if iPad remains supported, one physical iPad.

## Code/archive checks

- [x] App icon, launch screen, and acknowledgements UI are present.
- [x] Imported operational maps are not exposed through iOS Files/Finder file
      sharing. Import and export use explicit system pickers/share UI.
- [x] `NSLocationWhenInUseUsageDescription` explains live position and GPX
      track recording.
- [x] `UIBackgroundModes: [location]` is declared because a user-started track
      or separately opted-in v3 Unit Sync location session can continue while
      the app is backgrounded. Independent runtime leases disable background
      access when both features stop, the system indicator remains visible, and
      the app does not request “Always” authorisation.
- [x] `CFBundleShortVersionString` and `CFBundleVersion` are configured as
      2.0.0 (64) in the source. Recheck the generated project and archive below.
- [x] Verify the archived `PrivacyInfo.xcprivacy`, Info.plist purpose strings,
      linked SDK privacy manifests, entitlements, background modes, and exact
      2.0.0 (64) metadata against the local release archive. Verified archive:
      `ios/build/archives/TacticalMaps-2.0.0-64.xcarchive`; exported IPA:
      `ios/build/exports/TacticalMaps-2.0.0-64/TacticalMaps.ipa` (5,747,168
      bytes, SHA-256
      `e0df7a66ab20ecacecc29689ab1f0c66936aa6948acc24a2552238f765d09385`).
- [x] Historical evidence: the verified 1.2.5 (63) IPA upload to App Store
      Connect reported
      `UPLOAD SUCCEEDED` with Delivery UUID
      `d363e17b-a627-4acd-b528-b344d4f31f28` on 27 August 2026; the subsequent
      build-status query reported `VALID` and `APP_STORE_ELIGIBLE`.
- [x] Upload the verified 2.0.0 (64) candidate to App Store Connect. Apple
      reported `VALID`, `APP_STORE_ELIGIBLE`, and
      `usesNonExemptEncryption: false` for delivery UUID
      `4708a468-1dfc-4063-9853-f8c3e51c2c3f` on 28 August 2026.
- [ ] Reconcile the App Store privacy answers against that exact archive in App
      Store Connect; local artifact inspection does not complete this account-
      holder declaration.
- [x] Run the complete unit/UI suites and a generic-device signed Release
      archive from the current candidate: 477 unit tests (476 passed, one
      opt-in live-relay skip) and 16 UI tests (14 passed, two opt-in fresh-room
      screenshot skips), with zero failures.
- [ ] Repeat the release build from a clean checkout after the candidate is
      committed. The current shared working tree is intentionally not clean,
      so this reproducibility gate cannot be claimed yet.

## Encryption/export compliance — confirmed for 2.0.0 (64)

The project currently declares `ITSAppUsesNonExemptEncryption: false`. The app
does **not** use only TLS: it also implements AES-256-GCM mission/Unit Sync
encryption, PBKDF2/HKDF/HMAC-SHA-256 key derivation, Ed25519 signatures, and
X25519 selected-unit Chat key agreement.

- [x] The account holder confirmed that the shipped cryptographic
      implementations are supplied by Apple operating-system frameworks and
      use Apple's “encryption limited to that within the Apple operating
      system” classification, which requires no App Store Connect documentation.
- [x] Apple processed the exact 2.0.0 (64) IPA with
      `usesNonExemptEncryption: false` and marked it `VALID` and
      `APP_STORE_ELIGIBLE`.

This checklist records the release decision and Apple's processing result; it
is not legal advice. Reassess if the cryptographic provider or distribution
facts change. Use the audited capability table and decision record in
[`security/IOS_EXPORT_COMPLIANCE.md`](security/IOS_EXPORT_COMPLIANCE.md).

## App Review information

- [ ] **Sign-in credentials:** N/A — TacMap has no account/login.
- [ ] **Demo content:** the app ships without a sample operational map. Give the
      reviewer a public GeoPDF/MBTiles test source or attach review instructions.
- [ ] **Review notes:** explain all of the following:
  - TacMap is a field-navigation and mapping tool for outdoor, public-safety,
    field-GIS, training, and tactical users; it is not itself a weapons system.
  - Foreground Location is requested on the first map presentation so live
    location works immediately after consent. **Record Track** remains an
    explicit action; a recording may continue in the background and is always
    represented by the system background-location indicator.
  - The basemap is a custom raster renderer. Online Esri/OpenTopoMap tiles and
    online weather/elevation/place lookups are independently switchable and off
    for a fresh install. Coordinate/grid searches remain on-device.
  - Unit Sync is opt-in and mission payloads are end-to-end encrypted. The relay
    receives the admission token and clear envelope, actor/session,
    acknowledgement, control, IP, timing, and size metadata; displayed member
    liveness is relay-reported.
  - **Export All Mission Objects** creates GeoJSON for waypoints, symbols,
    drawings, and layers. A recorded route is exported separately as GPX.
- [ ] Complete the age-rating questionnaire from the actual content and target
      audience; do not rely on a hard-coded rating in this repository.

## App Privacy form — human console review required

TacMap has no developer analytics, advertising, TacMap account, or
developer-readable mission database. That does not by itself settle every App
Privacy answer: precise location can be recorded locally, optional providers
receive queries/coordinates, Unit Sync sends encrypted content plus visible
metadata through Cloudflare, and StoreKit handles commerce metadata.

- [ ] Complete the form against Apple’s current definitions of “collect” and
      “third-party partner,” the production relay/provider agreements, and the
      exact archive.
- [ ] Reconcile the form line-by-line with `PRIVACY_POLICY.md`, especially
      optional Precise Location and **Emails or Text Messages** (TacMap Chat),
      plus other User Content, Product Interaction/Diagnostics, and Purchases.
      The bundled privacy manifest declares the first two for App Functionality,
      without tracking or a TacMap account identity; confirm the console's
      linked/not-linked answers against Apple's current definition and record
      the account-holder's reasoning in the release ticket. Apple explicitly
      places non-SMS in-app private messaging under
      [Emails or Text Messages](https://developer.apple.com/app-store/app-privacy-details/).
- [ ] Recheck the form whenever a provider, relay logging policy, analytics or
      crash service, or purchase-validation design changes.

## Submission-day flow

1. Generate and verify the local archive without uploading:

   ```sh
   cd ios
   ./scripts/archive_testflight.sh
   xcodebuild -exportArchive \
     -archivePath build/archives/TacticalMaps-2.0.0-64.xcarchive \
     -exportPath build/exports/TacticalMaps-2.0.0-64 \
     -exportOptionsPlist ExportOptions.plist \
     -allowProvisioningUpdates
   ```

   Use a fresh export path. The archive script regenerates the project and runs
   `verify_release_metadata.sh` against the archived app; neither command
   uploads or submits the build.
2. Validate the archive, including version/build, signing, privacy manifest,
   entitlements, background modes, and export-compliance answers.
3. Upload through Organizer and wait for App Store Connect processing.
4. Attach the build and IAP to the intended app-version record; add current
   description, keywords, screenshots, review notes, support/privacy URLs, and
   privacy/export declarations.
5. Submit for review and preserve the exact archive/test evidence used.

## Physical-device smoke test

- Fresh install and previously purchased offline launch.
- Trial expiry, purchase, pending/cancelled purchase, Restore, and refunded or
  revoked entitlement after a conclusive store refresh.
- Explicit location enable/denial/Settings return.
- Start recording, lock/background the phone, stop from the app, relaunch and
  recover, export GPX, and discard.
- Import PDF/GeoPDF and MBTiles; switch online/offline sources and restore the
  retained imported map.
- Local MGRS/partial-grid/lat-lon and mission-object search with all network
  gates off; optional place lookup with the gate on.
- GeoJSON/KML/KMZ imports; **Export All Mission Objects**; verify the track is
  absent from GeoJSON and present only in the separate GPX export.
- Unit Sync between iOS and Android, including leave/reconnect and misleading
  relay/liveness failure states.
- On a physical iPhone, opt in to Background Unit Sync, lock the screen for
  longer than the selected interval, verify the Android/iOS receiver gets a new
  last-known position, then turn the OPSEC switch off and verify the system
  location indicator and screen-off presence traffic stop.
- VoiceOver, Larger Text, landscape, reduced motion, and all destructive-action
  confirmations.
