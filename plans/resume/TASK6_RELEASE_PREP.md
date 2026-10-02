# Task 6 — release preparation

All six prescribed work steps have been handled sequentially. Final security fixes and release build numbers are ready for one scoped local commit after coordinator approval. **Universal ≤1 physical-screen-pixel numerical acceptance is WITHHELD.** Functional native verification, measured geometry and explicit raw raster/source limits are separate; this work does not certify physical-device key, biometric, screenshot or OS behavior. No push, deployment, tag or PR was performed, and owner retention/background-reconnect/distribution decisions were not changed.

## Latest main and version choice

The coordinator successfully fetched origin before Task6. `origin/main` is `9b4b809bf894047a3e5a229be3831bfacdc7993f` (Android parity: map tools, drawing and symbols #47), an ancestor of integration base `5c072b386b9a253db6c0dbea0c1c8430aec1fef9`. This agent independently read the fetched commit and both version files and verified ancestry. Its iOS build number and Android fallback versionCode are **69**; marketing version is **2.1.0**.

- `ios/project.yml`: CFBundleVersion **69→70**; CFBundleShortVersionString **2.1.0**, unchanged.
- Xcodegen regenerated the tracked `ios/TacticalMaps/Resources/Info.plist`: CFBundleVersion **70**, marketing **2.1.0**. Its only diff is69→70.
- `android/app/build.gradle.kts`: fallback versionCode **69→70**; versionName **2.1.0**, unchanged. Existing CI `-PVERSION_CODE` injection remains supported. Verification explicitly passes **-PVERSION_CODE=70** to override any ambient injected Gradle property; the actual Debug and Release manifests both confirm70.

Only build numbers change in Task6. Renderer/cache version2 is the separately approved Task4 cache invalidation, not a marketing version bump. The latest full security suites were not rerun for this pure version change.

## Actual build70 artifacts

| Artifact | Actual manifest/plist | Immutable identity and evidence |
|---|---|---|
| Android Debug APK | `com.tacmap`, versionCode70, versionName2.1.0 | SHA256 `c39b81e40331ae70cdc6c83c49a5a315ab5c515f3dc8e3cd0574ea90960c3ec4`; `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-release/android/debug70.apk` |
| Android Release unsigned APK | `com.tacmap`, versionCode70, versionName2.1.0 | SHA256 `7112aff78b0117e3b7ed13c55a280b0a45407e099a41e1be47a33380db4f4244`; `codex-release/android/release70.apk`. This is a build-validation artifact, not a published/signed Android store release |
| iOS normally signed Release simulator product | `com.tacticalmaps.app`, CFBundleVersion70, CFBundleShortVersionString2.1.0 | Binary SHA256 `1b447a3973fe7ed3e6e3ca156ebde2102c747f3e83f36e69fc97f6307830ccaf`; `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-version/ios/releaseDD/Build/Products/Release-iphonesimulator/TacticalMaps.app/TacticalMaps` |

Android normal build tasks `:app:assembleDebug :app:assembleRelease -PVERSION_CODE=70` pass in37s using Java17 and configured Android SDK. Actual binary manifest versions are read with SDK aapt2; `codex-release/android/artifact70-proof.json` records full paths/hashes/manifest lines. All audit observer/buffer/intent/frame/job and Google Maps/Firebase analytics DEX markers are zero; R8 mapping omits the observer and usage confirms removal. APKs remain scratch/build outputs outside commit scope.

iOS owner used normal Xcodegen and normal signed command:

```
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild \
  -project ios/TacticalMaps.xcodeproj -scheme TacticalMaps build \
  -configuration Release -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /Users/cbrooker/.claude/jobs/10094e99/tmp/codex-version/ios/releaseDD
```

Project generation and build exit0; `codesign --verify --deep --strict` exit0. No secure-signing guard bypass. `codex-version/ios/release-version-exclusion.json` records actual plist, binary hash, twelve sensitive harness/audit markers absent and five observer symbols absent. Cosmetic first-run TOUR marker remains and is not represented as excluded. Simulator signing is not App Store archive/distribution or physical-device validation.

## Validation carried into release preparation

| Work step | Final evidence |
|---|---|
| Task1 WP4/blocker | [WP4_TASK1_REVIEW](WP4_TASK1_REVIEW.md), platform device reports and reviewed squash `8b08cf0` |
| Task2 sync | [TASK2_FINAL_REVIEW](TASK2_FINAL_REVIEW.md), [TASK2_INTEROP](TASK2_INTEROP.md): actual authenticated native local SP1 and shipped-TLS pair, room/direct chat, sealed kill/relaunch, relay restart, background challenge/foreground snapshot. Conditional live-test skips are not counted as these enabled runs |
| Task3 integration | [TASK3_INTEGRATION](TASK3_INTEGRATION.md), independent merged-source/JSON/fixture review: iOS991/0/2, Android1128/0/1, relay117/0, eight-client90s restart soak; recursive key-wise catalog/review merge and inherited audit preservation |
| Task4 merged native PDF checks | [TASK4_DEVICE_REVIEW](TASK4_DEVICE_REVIEW.md), [Android](TASK4_ANDROID_DEVICE.md), [iOS](TASK4_IOS_DEVICE.md): all34 binding fixtures plus pinned real USGS, strict expected blank outcomes, three actual UI calibrations, zoom/fit/seams, current live/bake provenance, real generation/configuration/rotation/recreation. Authorized shared warp/vector/cache corrections verified. Functional gates accepted; raw/source/raster numerical FAIL/BORDERLINE statuses retained and universal1px acceptance withheld |
| Task5 threat model | [TASK5_THREAT_MODEL_REVIEW](TASK5_THREAT_MODEL_REVIEW.md), [Android](TASK5_ANDROID_THREAT_MODEL.md), [iOS](TASK5_IOS_THREAT_MODEL.md): Android1137/0/1, actual relevant native48/0 and final metadata14/0; iOS1003/0/3 and affected76/0; relay118/0, typecheck/dry-run; site7/0, generated EN/DE disclosures, links/content. Concrete metadata/lazy-extension/Release-override/relay retention race repairs independently accepted; no retention/wire change |
| Task6 version metadata | Actual Debug/Release APKs and signed Release simulator plist are70/2.1.0; independent review follows coordinator freeze notice before local commit |

Shared generators, localization/inventory/fingerprints and whitespace checks passed at Task5 freeze; Task6 has no new user-visible message or fixture behavior. Marketing version remains2.1.0. Security fixes and their documentation are included together in the final scoped commit.

## Commit scope, preservation and remaining limits

`codex-release/final-commit-scope.json` lists the exact25 tracked modifications and four named new reports for review: Task5 source/tests, canonical documents/generated site/fixtures, both version sources plus generated plist, and TASK5_ANDROID/TASK5_IOS/TASK5_THREAT_MODEL/TASK6 reports. No raw APK/screenshot/MBTiles/cache, original audit script/data, ignored project/build output or dependency cache is in scope. The133 inherited untracked files are hash-checked against the pre-integration baseline and stay outside the commit.

The unexpected root `.wrangler/cache/cf.json` was a reviewer-owned local Wrangler check cache, not source. The reviewer independently established provenance, preserved proof in `codex-threat/wrangler-cache-cleanup-proof.json`, and removed only the owned cache. This agent neither staged nor deleted it. No unknown or inherited artifact was removed.

The physical-device checklist and cryptographic least-privilege/erasure limitations remain as disclosed. Keychain/Keystore enforcement, background GPS/service delivery, biometrics, secure snapshots, whole-state rollback/native engine exploitation and deployed-service operators cannot be certified by these emulator/simulator/build tests. The owner retains all decisions about relay retention, background reconnect (stillfalse), publishing, signed store artifacts and release rollout. **Numerical universal1px acceptance is not granted by completing release preparation.**
