# WP4 Android device verification

Verified 2026-10-02 in `review-wp4`, using only `emulator-5562` (API 36). No production code changes by this verifier, no commit, push or deploy.

## Build and regression results

The debug APK was rebuilt after the final footer byte-count clamp and shortened calibration intro copy. APK SHA-256: `6a4d04bb3296d99dc86d32fb7e6069615a70c49e771f0afc2f8227c469e9401b`.

Latest-device focused regressions: **38 tests, zero failures** (`instrumentation-final.log`, 13.977 seconds):

- `PdfImportSmokeTest`: real document-picker import, plain-PDF calibration entry, rendering, rejected metadata.
- `LegacyMapReaderFailClosedInstrumentedTest`: sealed unreadable record, substituted legacy PDF bytes, invalid existing PDF, authenticated absence/migration.
- `PdfDeleteMapInstrumentedTest`: delete with stored bake, delete while an actual bake is running, offline-tile removal and main-thread reconciliation.
- `PdfImportHardeningInstrumentedTest`: actual PDF preflight, invalid/password PDFs, page rotation, embedded metadata size limits and projection checks, real bake attachment, missing-source bake refusal.
- `PdfStoredFileRecoveryInstrumentedTest`: missing/substituted file recovery, cached-session revalidation, trimmed/reopened page handling.
- `PdfBakeBaseIsolationInstrumentedTest`: bake/live base-raster isolation, queued bake reuse, failure isolation.
- `PdfSessionMigrationInstrumentedTest`: actual stored-file migration and reparsing.

The first exploratory grouping ran map fixtures before the UI picker and produced 37/38, with the picker failing to enter calibration. The picker passes alone with fresh app data. The final grouping starts with fresh app data and runs picker tests first, then the isolated regression fixtures, and passes all 38. This documents a test-order isolation dependency; it does not discard the picker test or alter production behavior.

## Real activity lifecycle acceptance

Added `Wp4LifecycleDeviceAcceptanceTest` in the Android instrumented test sources. It uses the actual `MainActivity`, its actual `MapViewModel`, DEBUG import hooks, production library/draft stores, and UiAutomator coordinate entry/save/finish/delete/retry controls. Stages run as separate instrumentation invocations with `adb -s emulator-5562 shell am force-stop com.tacmap` between them. It is skipped in an ordinary suite unless `-e wp4Stage <stage>` is supplied; the staged lifecycle deliberately persists state between invocations.

Successful stages:

1. `seed`: import `testdata/geopdf/tacmap_grid_rot5_plain.pdf`, choose WGS84, enter and save the first two grid references through the real UI. The sealed draft contains two points.
2. `resume`: after a process force-stop, the app automatically resumes exactly those two points. Enter the remaining two references, tap Finish, verify a four-point manual library record, no active calibration and no remaining draft. Device log: `committed n=4 rmsM=0.0 maxM=0.0 tauM=10.0 grade=good`.
3. `second`: import `tacmap_grid_sf_iso.pdf` through the real debug import pipeline. Verify both entries, the first PDF still present and its four-point manual calibration retained.
4. `delete`: actual Menu > Layers and Labels > scroll to Imported maps > first map's action menu > Delete… . Verify the PDF remains while the confirmation is visible. Tap final Delete; verify PDF and library/manual calibration are gone and no draft for that content key remains. Bake-file deletion and cancellation are independently verified by the real device regression tests above.
5. `recoverySeed`: create a real saved one-point draft for the retained PDF and an orphan offline bake sentinel, then force-stop. Corrupt the actual sealed library file only.
6. `recovery`: cold activity launch reports the corrupt library. Tap the actual Retry control. Library rebuild succeeds, cleanup remains disabled, and PDF, orphan bake and sealed draft remain byte-for-byte unchanged.
7. `recoveryCold`: force-stop/relaunch the rebuilt library; cleanup remains disabled and all three files remain unchanged again.
8. `guardSeed`: import/render the GeoPDF and seed the production guard's serialized BASE-interrupted state in its real `noBackupFilesDir` location.
9. `guardCold`: force-stop/relaunch. The actual app holds the PDF back, shows the online source in memory, and retains the durable active entry and file. Tap actual Open Anyway; the PDF renders again.

The four plain-sheet page targets and exact coordinate inputs come directly from `pdf_georef.json` `fiduciaryFits.sets[name=rot5_plain_4_grid_fids]`. Only the crosshair capture is injected at those exact fixture page coordinates; text entry, point save, Finish and all lifecycle persistence use production/UI paths. This avoids manual panning error while testing the calibrated PDF on an actual emulator. It does not claim a manual gesture accuracy or pixel-alignment measurement; that belongs to merged-build Task 4.

## Recovery byte evidence

The same hashes were measured before corruption, after UI Retry and after cold launch:

| Artifact | SHA-256 |
|---|---|
| Retained PDF | `d2e56f6a8b7259d652b3f3c1cfb7ed3697267b5d79d40e4fb96265a703606e75` |
| Orphan bake sentinel | `afe46cdfc86c2aeae6bcec2a8d8f34f0cda0158464c4a70f31de2aec23a1d930` |
| Real sealed calibration draft | `97443a33672459e7702a17df14a3a35a50e030b90f448812d8d426ee1e6136d7` |

The orphan bake is a deliberately inert byte sentinel, not a claim of a valid rendered MBTiles bake. Actual valid bake creation/attachment and deletion during a running bake were exercised in the regression tests.

## Reproduce and evidence

Evidence directory: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-wp4/android-device/`.

- `build.log`, `instrumentation-final.log`, `calibration.log`.
- `seed.log`, `resume.log`, `second.log`, `delete.log`, `recoverySeed.log`, `recovery.log`, `recoveryCold.log`, `guardSeed.log`, `guardCold.log`.
- `recovery-before-hashes.txt`, `recovery-after-retry-hashes.txt`, `recovery-after-cold-hashes.txt`; both comparisons have no differences.
- `wp4-mid-calibration.png`, `wp4-four-points-finished.png`, `wp4-second-import-preserves-first.png`, `wp4-delete-confirmation.png`.
- `wp4-corrupt-before-retry.png`, `wp4-corrupt-retry-preserves-files.png`, `wp4-corrupt-cold-preserves-files.png`.
- `wp4-guard-cold-suppressed.png`, `wp4-guard-explicit-reopen.png`.

Staged invocation:

```sh
adb -s emulator-5562 shell am force-stop com.tacmap
adb -s emulator-5562 shell am instrument -w -r \
  -e wp4Stage seed \
  -e class com.tacmap.map.Wp4LifecycleDeviceAcceptanceTest \
  com.tacmap.test/androidx.test.runner.AndroidJUnitRunner
```

For lifecycle, start with a clean disposable emulator app data set and run `seed`, `resume`, `second`, `delete` in order. For recovery continue with `recoverySeed`, force-stop, corrupt only `files/imported_map_library.json`, then run `recovery`, `recoveryCold`. Guard verification runs `guardSeed`, `guardCold`. Never run destructive fixture-data setup on a user's actual app installation.

The localization checker passed after the test source addition: 424 source components, 2074 messages, 23 plural families. Task 4 zoom/grid seam measurements and actual physical-device behavior are not asserted by this Task 1 report.
