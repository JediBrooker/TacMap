# Release readiness and parity implementation plan

Status: **Historical baseline; superseded by the 2.0.0 release candidate**  
Prepared: **2026-08-16**  
Current release candidate: **2.0.0 (64)**  
Immediate release lane: **external release-owner and physical-device gates**  
Follow-up release lane: **separate future planning**

This file records the original 1.2.3/1.3 sequencing and is retained for audit
history. The implemented work was subsequently consolidated into 2.0.0, along
with Heading Up, TacMap Chat, Unit Sync reliability, and PDF/GeoPDF/MBTiles
hardening. Version labels in the phase text below describe that historical plan,
not the current release boundary. Current store copy and gates are in
`docs/STORE_LISTING.md`, `docs/APPSTORE_CHECKLIST.md`, and
`docs/security/IOS_EXPORT_COMPLIANCE.md`.

## Outcomes

The work is complete only when:

1. An expired Android trial can always load, retry, restore, and purchase.
2. A verified permanent purchase remains usable offline on both platforms; only an authoritative store result can revoke it.
3. User-started recording has truthful permission/state UI and survives ordinary backgrounding on both platforms without reopening the general mission store.
4. Imports cannot introduce cross-store object-ID collisions, publish unsaved state, lose picker results, or block the iOS main actor with large file work.
5. GeoJSON style and symbol fields round-trip and render equivalently on Android and iOS.
6. Search, Unit Sync, layers, symbol editing, destructive actions, and accessibility expose one shared capability contract.
7. Privacy, store, README, permission, encryption, and release-signing claims describe the binaries that ship.
8. Shared fixtures, platform unit tests, lint/static checks, optimized builds, and physical-device smoke tests cover the changed contracts.

## Release boundaries

- **1.2.3:** Phases 0–5. No new portable container or multi-map catalogue is allowed into this release unless every earlier gate is green.
- **1.3.0:** Phase 6. The proposed `.tacmap` package and QoL work use separate ADRs and fixtures and do not hold up the corrective update.
- Existing uncommitted Billing 9.1.0 and monetisation-document changes are user-owned and must be preserved while these changes are layered on top.
- Version/build numbers are configured for 1.2.3 (61). Store uploads, tags, and publishing remain outside implementation until their human-owned release gates pass.

## Locked product contracts

### Entitlement and store access

- A verified affirmative non-consumable entitlement is durable and has no local seven-day expiry.
- Store transport, connection, verification, and timeout failures are inconclusive and never remove known-good access.
- A successful, authoritative store query may clear a refunded or revoked entitlement.
- Store transaction listeners and purchase-status refreshes start at app lifetime/foreground boundaries as recommended by Apple and Google; product-price loading remains paywall-scoped.
- Store communication and its limited purpose must be disclosed accurately. No mission object, map, location, or coordinate is sent with entitlement checks.
- Acknowledgement is durable work: persist pending acknowledgement, retry bounded transient failures, and never unlock a pending purchase.

### Mission data and layer deletion

- IDs occupy one global object namespace across waypoints and drawings.
- External import remints missing, invalid, repeated, or already-occupied feature UUIDs. Sync parsing does not remint authenticated IDs.
- Deleting a layer reassigns contained waypoints and drawings to a safe fallback layer in one coordinated operation; it never silently orphans or destroys them.
- Store writes persist a candidate state before publishing UI state, undo state, or sync events.

### Rendering units

- The immediate Android compatibility fix passes device density through every import/export/sync style path and renders the stored `fillColor`.
- A persisted-width schema migration to canonical dp is deferred until legacy pixel widths can be identified safely.

## Phase 0 — Documentation and contract discovery

Status: **Complete**

### Findings and allowed APIs

- Android is target SDK 36/min SDK 26 with AGP 8.13.2, Gradle 8.13, and Play Billing 9.1.0.
- Billing 9.1 supplies `enableAutoServiceReconnection()` and returns `QueryProductDetailsResult`, including `unfetchedProductList`.
- Android location foreground services may continue after a visible, user-started action; `ACCESS_BACKGROUND_LOCATION` is not required for this workflow. Precise, approximate-only, and denied are distinct states. `POST_NOTIFICATIONS` denial does not itself forbid the foreground service.
- Activity-result registrations must be unconditional and stable; document results that outlive the Compose tree belong at Activity scope.
- iOS targets 16.3/Swift 5.9. Its current When-In-Use plus conditional `allowsBackgroundLocationUpdates` design is valid for an explicit recording session.
- Apple requires `NSPrivacyAccessedAPICategoryFileTimestamp` reasons `C617.1` and `3B52.1` for the app-container and user-selected-file metadata paths in this app.
- Apple recommends installing `Transaction.updates` at launch. `Transaction.currentEntitlements` is authoritative for the non-consumable when the sequence completes without verification failure.
- Security-scoped document access must span the worker read/copy, and picker files should be coordinated. Only value-only `Sendable` import results cross back to `MainActor`.
- Keychain has no multi-item transaction; the App Lock salt/hash should become one checked, versioned credential item.

### Primary documentation

- [Google Play Billing integration](https://developer.android.com/google/play/billing/integrate)
- [Google Play one-time purchase lifecycle](https://developer.android.com/google/play/billing/lifecycle/one-time)
- [Android location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Android location foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Android Activity Result API](https://developer.android.com/training/basics/intents/result)
- [Apple required-reason API types](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)
- [Apple Core Location authorization](https://developer.apple.com/documentation/corelocation/requesting-authorization-to-use-location-services)
- [Apple StoreKit current entitlements](https://developer.apple.com/documentation/storekit/transaction/currententitlements)
- [Apple StoreKit offer-code and transaction-listener guidance](https://developer.apple.com/documentation/storekit/supporting-offer-codes-in-your-app)
- [Apple security-scoped URL access](https://developer.apple.com/documentation/foundation/url/startaccessingsecurityscopedresource%28%29)

### Verification checklist

- [x] Confirm pinned SDK/library versions from local build files and resolved Billing AAR.
- [x] Map code paths for every confirmed issue on both platforms.
- [x] Identify shared-fixture and existing test-suite seams.
- [x] Record decisions that affect privacy, deletion, persistence, and release scope.

### Anti-pattern guards

- Do not derive behavior from memory when current platform policy or signatures are available.
- Do not treat a transport failure as evidence that an entitlement, permission, or saved object does not exist.
- Do not force UIKit/MapKit objects across executors with `@unchecked Sendable` merely to suppress diagnostics.

## Phase 1 — Commerce, paywall, and durable entitlement

Status: **Implementation complete; store-device smoke remains in the final gate**

### Implementation

1. Add a testable Android billing state machine with `Idle`, `Connecting`, `LoadingProduct`, `Ready`, and actionable `Error` states.
2. Start billing/entitlement observation at Activity lifetime. Query ownership on initial connection and a throttled foreground transition; load product details only when either paywall is visible.
3. Make hard and soft Android paywalls invoke product loading, expose retry/unavailable/restoring feedback, scroll at large text/landscape sizes, and never leave an indefinite disabled button.
4. Enable automatic Billing service reconnection. Handle `unfetchedProductList`, connection failures, `launchBillingFlow`'s synchronous result, `ITEM_ALREADY_OWNED`, cancellation, pending purchases, and purchase-update failures.
5. Replace the seven-day cache with durable known-good ownership. Only `OK` purchase queries may clear it; clock changes and offline age do not.
6. Persist pending acknowledgement work, retry bounded transient response codes with backoff, and clear the flag only after confirmed acknowledgement or an authoritative no-longer-owned query.
7. Start the iOS transaction listener at app lifetime rather than only from `PaywallView`. Keep price loading in the paywall. Process verified purchases as persist/grant first and `finish()` last.
8. Make iOS entitlement-cache writes checked so delivery is not reported durable if Keychain persistence fails.
9. Add `docs/security/ADR-003-dark-egress-and-entitlement.md` describing the launch/foreground store contact, known-good offline cache, revocation behavior, and client-only verification limitation.

### Repository references

- `android/app/src/main/java/com/tacmap/billing/BillingManager.kt`
- `android/app/src/main/java/com/tacmap/billing/PaywallScreen.kt`
- `android/app/src/main/java/com/tacmap/app/MainActivity.kt`
- `ios/TacticalMaps/Billing/StoreManager.swift`
- `ios/TacticalMaps/Billing/PaywallView.swift`
- `ios/TacticalMaps/App/TacticalMapsApp.swift`
- `ios/TacticalMapsTests/StoreManagerTests.swift`

### Verification checklist

- [x] Android cached ownership survives eight days, clock rollback, and every non-OK query.
- [x] Android `OK + empty` clears; `PENDING` does not unlock; `PURCHASED` does.
- [x] Pending acknowledgement survives recreation and retries transient failures.
- [x] Expired paywall loads automatically and offers retry for connection, missing-product, and unfetched-product cases.
- [x] iOS listener activates for a cached purchaser, cache publication precedes transaction finish, and cache failure leaves work retryable.
- [ ] Play Billing Lab/store sandbox: purchase, delayed purchase, restore, promo/offer code, refund, revoke, offline start, and response-code simulation.

### Anti-pattern guards

- Do not clear known-good ownership on timeout, unavailable StoreKit/Play service, unverified data, or a failed cache write.
- Do not grant `PENDING`, ignore acknowledgement callbacks, or acknowledge before durable grant publication.
- Do not make price/product network loading a prerequisite for an already-verified owner to enter the app.

## Phase 2 — Recording, location, notification, and key lifecycle

Status: **Implementation complete; physical-device matrix remains in the final gate**

### Implementation

1. Add `docs/security/ADR-002-key-lifetime-and-rotation.md` and specify `locked`, `unlocking`, `unlocked`, `recordingOnly`, and `unrecoverable` behavior.
2. On Android, create/fsync the track log and capture the recording-scoped key before publishing active state. Background appends receive the captured key explicitly and do not call `DataKey.key()`.
3. Let Activity pause lock the general mission key without stopping an active recording. Clear/zero the recording key on stop, failure, discard, or permission loss.
4. Split Android location access into `Precise`, `ApproximateOnly`, and `Denied`. Explain why precise GPS is needed and provide retry/Settings paths.
5. Start the location foreground service only from a visible, user action after prerequisites pass. Add Android 13+ contextual notification permission; denial shows an explanation but does not falsely fail recording.
6. On iOS, introduce a recording coordinator/state reducer. `notDetermined` waits for authorization, denied/restricted remains idle with Settings guidance, authorized starts exactly once after durable log setup, and mid-session revocation stops cleanly.
7. Make both UIs derive `REC` from the same state contract: awaiting permission, starting, recording, interrupted/error, and idle.
8. Migrate iOS App Lock credentials to one checked versioned Keychain record; verify the new item before deleting legacy salt/hash items.

### Documentation/API references

- Android foreground-service, permission, and notification links from Phase 0.
- [Apple `allowsBackgroundLocationUpdates`](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates)
- [Apple Keychain update](https://developer.apple.com/documentation/security/updating-and-deleting-keychain-items)
- Existing iOS scoped-key pattern in `ios/TacticalMaps/Models/TrackRecorder.swift`.

### Verification checklist

- [x] Android continues durable point appends after Home/screen lock while mission stores remain locked at the tested lifecycle seam.
- [x] No Android background append reacquires the global key; stop/failure/discard zeroes the retained key.
- [x] Permission reducer tests cover precise, approximate-only, denied, revoked, and GPS-disabled states.
- [x] iOS pending authorization never displays `REC`; grant starts once; denial and revocation are truthful.
- [ ] Physical-device matrix: Android API 26/29/33/34/36 and iOS 16.3/current, notification allowed/denied, Home, screen lock, process kill/recovery.
- [x] App Lock injected-Keychain tests prove failed update/add/migration preserves the previous working credential.

### Anti-pattern guards

- Do not request `ACCESS_BACKGROUND_LOCATION` for the explicit foreground-service workflow.
- Do not publish recording active before log creation and service/background-update activation succeed.
- Do not retain the general mission key after recording ends or claim survival after force-quit/system termination.

### Recorded follow-up hardening

- This release's “recording-scoped key” is a lifetime/API-scoped copy of the raw mission DEK on both platforms, not a cryptographically derived track-only subkey. A versioned derived-key migration remains a separate security change.
- Android can zero its mutable key copy; Swift `Data` release does not prove byte erasure. The ADR states that limitation instead of overclaiming it.
- Verified cleanup of obsolete access-control key slots after rotation remains open in the security-remediation track; current two-slot recovery behavior must not be described as completed rotation cleanup.
- General live-map location prompt timing still differs (Android user action, iOS view appearance) and is assigned to the Phase 4 UX-parity pass.

## Phase 3 — Import/export and persistence correctness

Status: **Implementation complete; provider/process-death device smoke remains in the final gate**

### Implementation

1. Add `testdata/import_identity.json` and paired resolvers used only by external GeoJSON/KML imports. Enforce one global UUID namespace across waypoints/drawings and deterministic reminting in tests.
2. Add batch-import APIs on iOS and durable-before-publish commit paths on both platforms. Emit undo and sync events only after persistence succeeds.
3. Make cross-store partial import failure explicit and retry-idempotent. Defer true two-file atomicity to a journal/combined-store design.
4. Move iOS security-scoped reading, coordinated copy, bounds checks, and parsing off `MainActor`; return value-only payloads and publish in batches.
5. Move Android PDF, GeoJSON, MBTiles, and KML document launchers to Activity scope with a typed pending import and saved-state handoff. Consume exactly once and release URI grants after copy/import.
6. Wrap Android serialization, cache creation/write, `FileProvider`, and chooser launch in one result boundary with cleanup and actionable errors.
7. Add gesture transactions for Android slider edits: memory-only preview, single persistent/undo/sync commit, and revert on cancel/failure.

### Repository references

- `android/app/src/main/java/com/tacmap/export/GeoJsonImporter.kt`
- `android/app/src/main/java/com/tacmap/map/GeoJsonImportHandler.kt`
- `android/app/src/main/java/com/tacmap/map/MapScreenHelpers.kt`
- `android/app/src/main/java/com/tacmap/waypoints/WaypointStore.kt`
- `android/app/src/main/java/com/tacmap/drawings/DrawingStore.kt`
- `ios/TacticalMaps/Export/GeoJSONImporter.swift`
- `ios/TacticalMaps/App/ContentView.swift`
- `ios/TacticalMaps/Waypoints/WaypointStore.swift`
- `ios/TacticalMaps/Drawing/DrawingStore.swift`

### Verification checklist

- [x] Re-import, within-file duplicates, invalid IDs, and cross-type collisions produce globally unique stable objects on both platforms.
- [x] Sync-authenticated object IDs remain untouched by the external-import resolver; the supported v2 path now validates canonical IDs before state mutation.
- [x] Failed writes leave published arrays, undo stacks, and sync events unchanged.
- [x] Each imported store persists once; retry after a partial failure is idempotent.
- [x] iOS large-import read/copy/validation work is detached; security scope is balanced and chunk cancellation cleans owned partials at tested seams.
- [x] Android picker results survive pause, Activity recreation, and key unlock through a durable typed coordinator; object imports and map copies are retry-idempotent.
- [x] Every Android export-stage exception surfaces a useful message and removes partial cache output.

### Anti-pattern guards

- Do not remint IDs inside the generic parser used by Unit Sync.
- Do not publish optimistic mission state before an encrypted durable write succeeds.
- Do not pass PDFKit/MapKit/UI objects between executors or leave persistent URI grants indefinitely.

### Recorded follow-up hardening

- Waypoints and drawings remain two encrypted files. Imports are explicit and retry-safe after a partial commit, but true cross-file atomicity still requires a journaled transaction or combined store.
- A document provider that refuses a persistable Android URI grant cannot resume after process death before the first successful private copy; the UI must request reselection rather than claim recovery.
- Android's delayed shared-export cleanup is process-local. A process death may leave cache output until the next stale-cleanup pass or OS eviction.
- Real Files/iCloud/SAF providers and maximum-size PDF/KMZ/MBTiles cancellation/storage behavior remain physical-device stress tests.

## Phase 4 — Rendering, feature, and UI/UX parity

Status: **Complete (automated gates); physical-device matrix remains a release checklist item**

### Implementation

1. Consume `testdata/drawing_style.json`; pass Android density through every file and Sync serialization path and render polygon `fillColor` independently from stroke.
2. Consume `testdata/search_contract.json`. Extract pure offline engines with the same ordering for full/partial MGRS, decimal coordinates, waypoint/drawing names, notes, type, and layer; append opt-in provider results afterward.
3. Bring Android Unit Sync UI to parity: all function values, online member count/list, persistent error detail, and successful-state clearing.
4. Add Android master Symbology/Drawings toggles and drawing-layer rename/recolour/delete. Apply the locked fallback reassignment policy on both platforms.
5. Add a retained-imported-map return row on Android as the safe short-term map switcher.
6. Consume `testdata/symbol_edit_contract.json`; introduce a cross-platform `SymbolEditDraft` and matched quick editor for name, kind, notes, elevation, layer, rotation, scale, task colour, move-to-crosshair, and confirmed deletion. Commit once.
7. Label actionable Android controls, add roles/value semantics, and meet minimum platform touch targets; verify VoiceOver/TalkBack wording is equivalent, not necessarily pixel-identical.

### Repository references

- `android/app/src/main/java/com/tacmap/map/render/CustomMapOverlays.kt`
- `android/app/src/main/java/com/tacmap/map/SearchDialog.kt`
- `ios/TacticalMaps/Search/SearchSheet.swift`
- `android/app/src/main/java/com/tacmap/sync/SyncDialog.kt`
- `ios/TacticalMaps/Sync/SyncSheet.swift`
- platform Layers/DrawingLayers and SymbolEditor/WaypointEdit files.

### Verification checklist

- [x] Shared style fixtures preserve physical width and independent polygon fill at multiple Android densities and on iOS.
- [x] Shared offline search corpus returns equivalent result kinds and order without network access.
- [x] Sync member/function/error states and layer actions use scroll-safe layouts with matched state/copy contracts.
- [x] Layer deletion reassigns all contained objects and never orphans or deletes them.
- [x] Every waypoint field survives relaunch, export, and sync on both platforms.
- [ ] Physical VoiceOver/TalkBack, maximum text-size, and landscape smoke tests cover delete/cancel/reset, transform sliders, colour choices, and editor submission.

### Anti-pattern guards

- Do not silently convert the existing Android drawing store to dp without a schema discriminator.
- Do not copy iOS's current orphaning layer-delete behavior.
- Do not make parity mean identical native widgets; require identical capabilities, labels, states, order, and outcomes.

## Phase 5 — Privacy, store, and release controls

Status: **Implementation and local release artifacts complete; account-holder gates remain**

Final local verification: **PASS**. A fresh signed iOS 1.2.3 (61) archive and IPA
were produced and inspected, including their embedded version and privacy
metadata. A real upload-key-signed Android 1.2.3 (61) AAB passed `jarsigner` and
bundletool verification. The iOS IPA was uploaded successfully to App Store
Connect on 17 August 2026 (Delivery UUID
`09c24e77-f3b7-46fa-85cd-78b16b883db9`) and is awaiting Apple's processing; no
Play upload is claimed. Store-console submission, legal/account-holder
approvals, live-site publication, and physical-device acceptance remain release
gates. Phase 6 stays deferred until those gates pass.

### Implementation

1. Add FileTimestamp `C617.1` and `3B52.1` to iOS `PrivacyInfo.xcprivacy` and test the source plus built/archive copy.
2. Reconcile `PRIVACY_POLICY.md`, README, store listings, acknowledgements/OPSEC copy, `PLAY_STORE_PREP.md`, `APPSTORE_CHECKLIST.md`, site copy, and screenshot coverage with actual storage, background recording, tile providers, sync, encryption, file sharing, and permissions.
3. Define the corrective-release action as **Export All Mission Objects**: symbols, waypoints, drawings, and layers in GeoJSON. Tracks remain a separate GPX export until the versioned mission-package feature can bundle both formats safely. Rename both UIs and make every listing/help string match.
4. Add an execution-time Android `verifyReleaseSigning` task. Guard `bundleRelease` while preserving unsigned `assembleRelease` for CI R8 checks; report missing variable names but never values.
5. Verify Apple export-compliance answers against AES-GCM, PBKDF2, Ed25519, and TLS use and update the checklist without changing `ITSAppUsesNonExemptEncryption` blindly.
6. Add Android/iOS screenshot parity to public site/store materials or narrow any “matching” claim until both are represented.

### Verification checklist

- [x] The source iOS privacy manifest contains the required categories/reasons, and local automated manifest/plist checks pass.
- [x] Build a fresh signed production iOS archive and IPA and inspect their embedded version and privacy metadata before upload.
- [x] Repository privacy/store assertions are traceable to current code/config paths, and local stale-claim, link, asset, HTML, and store-limit checks pass.
- [ ] Record legal/export-compliance and Apple/Google store-console review by the relevant account holder.
- [x] `assembleRelease` succeeds unsigned; missing/partial credentials make guarded `bundleRelease` fail early without exposing secret values.
- [x] Produce a real upload-key-signed AAB and pass `jarsigner` plus bundletool verification before Play upload.
- [x] Android permission listing includes foreground service/location and notifications; obsolete Maps-key and Block Store instructions are gone.
- [x] Checked-in public screenshots and feature copy use platform-specific captions and do not overclaim cross-platform parity.
- [x] Generated threat-model HTML is deterministic and passes local content/link/asset validation.
- [ ] Publish the corrected site and store copy through the owning accounts, then verify the live privacy/threat-model URLs.
- [ ] Complete physical-device accessibility, background-recording, import/export, purchase, and release-candidate acceptance on iOS and Android.

### Anti-pattern guards

- Do not edit generated iOS plist output without changing `project.yml`/source manifest.
- Do not describe local encrypted mission storage as RAM-only or user-visible Files storage.
- Do not make legal/export-compliance conclusions solely from an engineering inference; flag human counsel/store-console confirmation.

## Phase 6 — historical 1.3.0 feature and QoL lane

Status: **Superseded; retain as unapproved future-product notes**

### One new feature: encrypted mission package

1. Ratify `docs/security/ADR-004-mission-package-v1.md` before implementation.
2. Version 1 contains a manifest, mission GeoJSON, and optional GPX track. PDF/MBTiles assets are excluded initially unless the archive-bomb, streaming, storage, and export-compliance expansion is explicitly accepted.
3. Use a portable passphrase-derived key, random package salt, authenticated header, strict size/count/path limits, streaming I/O, tamper detection, and preview plus Merge/Replace before mutation.
4. Add shared KDF/header/AAD/ciphertext/tamper fixtures. Require iOS→Android and Android→iOS round trips with identical IDs, fields, styles, layers, and track data.

### Three QoL features

1. **Unified field search:** Phase 4 provides the common engine; 1.3 adds sectioned presentation, centre/highlight behavior, and opt-in Places states.
2. **Saved basemap library:** add encrypted catalogues for multiple PDF/MBTiles entries, calibration/name/last view, cold-start repair, active checkmark, rename, confirmed delete, and safe fallback.
3. **Unified symbol quick editor:** Phase 4 establishes field parity and one-commit drafts; 1.3 polishes one-tap selected-symbol presentation, move-to-crosshair, and complete native accessibility.

### Verification checklist

- [ ] Mission-package ADR and shared fixtures are approved before either platform writes production packages.
- [ ] Wrong password, tamper, unsupported version, duplicate IDs, and cancelled Merge/Replace are non-destructive and use matched copy.
- [ ] Two saved maps survive cold relaunch and online/offline switching; deleting active selects the same safe fallback.
- [ ] Feature/QoL acceptance matrices pass on both platforms before any future release that adopts this historical scope.

### Anti-pattern guards

- Do not reuse the device-bound mission-data envelope for a portable package.
- Do not buffer multi-gigabyte assets in memory or add map assets to v1 without explicit scope approval.
- Do not ship a feature on one platform while the other exposes only a stub or different data contract.

## Final release verification

1. Android: unit tests, lint, instrumentation smoke tests, dependency/lock verification, `assembleRelease`, guarded `bundleRelease`, R8, and signature verification.
2. iOS: all unit/UI smoke tests, optimized build/archive, generated plist inspection, built privacy-manifest inspection, StoreKit configuration tests, and physical background-recording checks.
3. Shared: fixture consumers on Android/iOS (and relay where applicable), clean diff review, no secrets or generated residue, documentation links, accessibility smoke, and one explicit parity checklist signed off per surface.
4. Preserve unrelated user changes; do not commit, tag, push, upload, or publish unless separately requested.
