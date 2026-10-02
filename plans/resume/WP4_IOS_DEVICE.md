# WP4 Task 1 iOS simulator acceptance, 2026-10-02

Device: TacMap Review iPhone 4, 6BADDA88-5E21-4E0B-983D-E69D22C5B6D8.
Evidence directory: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-wp4`.
All runs used the actual debug app and XCUITest input. The headless Xcode installation has no Simulator.app, so native desktop UI control was unavailable. No push or deployment occurred.

## Completed flows

- `ios-device-exact.log`: imported `tacmap_grid_rot5_plain.pdf` through the normal DEBUG import hook; chose WGS84; captured the four raw-page fiduciaries in `pdf_georef.json` through Add point and the real coordinate entry UI. Terminated and relaunched the app after the first point and between later points. Active draft restored, all four points remained, and Finish committed the calibration. Simulator log: n=4, RMS=0.000 m, max=0.000 m, tolerance=10.0 m. Screenshots are in `ios-exact-attachments`.
- `ios-device-delete.log`: imported `tacmap_grid_sf_iso.pdf`; Layers showed both the first calibrated map and the second imported map. Used the first map's row menu and explicit Delete confirmation. The first row and original opaque file disappeared, while the second map and its file remained. Calibration belongs to the deleted library entry, so its removal also removes the saved calibration. Known bake and draft cascade deletion are asserted separately through the production MapViewModel tests running on this simulator.
- `ios-device-draft.log`: refined the remaining GeoPDF, added a point through the real entry UI, and terminated with a genuine sealed calibration draft on disk.
- `ios-device-corrupt.log`: after host-side corruption of the sealed library, the app showed the recovery issue. Retry rebuilt and immediately published the recovered library. Kept the calibration points for later, checked Layers showed Recovered map 1, and terminated/relaunched again. No recovery Retry issue remained.
- `ios-corrupt-before.json` / `ios-corrupt-after.json`: the original imported PDF and an unrelated generated-bake sentinel stayed byte-identical through recovery and relaunch. The genuine sealed draft remained; its ciphertext changed after the explicitly selected Keep points for later transition. Quarantined library copies remained. Simulator app-container paths change when Xcode reinstalls the app; checks resolve the current container by bundle ID, rather than treating the old container's disappearance as lost data.

## Device-discovered correction

Actual Retry exposed a bug unit tests with callbacks had masked: `completion?(restore())` does not evaluate restore when completion is nil. Rebuild wrote the new index but left the visible library Corrupt. Restore now always runs, and `testCorruptRetryWithoutCompletionPublishesTheRebuiltLibrary` tests the real no-callback Retry path. The final device recovery run passed.

## Regression and suite results

- `ios-red.log`: pre-fix corrupt-rebuild preservation test failed, proving the unwanted orphan bake/draft cleanup.
- `ios-counterfactual-red.log` / `ios-counterfactual-red.xcresult`: reinstating the nil-callback defect and removing the actionable preview-suspect source made both production regression tests fail. No test expectations were changed.
- Production restored byte-for-byte afterward; `ios-final-regression-green.log` passed all 3 selected tests: nil-callback Retry, preview crash recovery across Not Now and relaunch, and removal of a known superseded bake while retaining an unknown bake.
- `ios-full.log` / `ios-full.xcresult`: 837 unit tests, 0 failures, 1 skip. The skipped existing test requires a production relay and join code, which were not supplied; it is unrelated to WP4.
- Localization checker: 2074 messages, 23 plural families, 424 source components, all native outputs and translation fingerprints valid. The intro copy was shortened in English and German because the earlier text visibly truncated at the two-line limit.

## Running the staged UI audit again

Create the ignored repository marker `.wp4-device-audit`, use this dedicated simulator, and run individual methods with `-only-testing:TacticalMapsUITests/WP4CalibrationDeviceTests/<method>` in this order:

1. `testPlainMapImportShowsCalibrationAndResumesAfterTermination`, beginning with a fresh installed app.
2. `testSecondImportKeepsFirstAndConfirmedDeletionRemovesFirst`.
3. `testPrepareCalibrationDraftForRecoveryAudit`.
4. Terminate the app, obtain its current data container with `simctl get_app_container`, retain a before inventory, place a bake sentinel in Application Support/offline_tiles, and overwrite only imported-map-library.json with unreadable audit bytes.
5. `testCorruptLibraryRetryRebuildsAndRelaunches`, followed by current-container file existence and SHA256 comparison.

Remove the marker afterward. These dependent audit stages skip by default in an ordinary UI suite. `TACMAP_DEBUG_CALIBRATION_POINT` only centres the normal camera on a raw page point of the current calibration display; capture, parsing, fitting and saving remain the production UI path. The hook and its call site are inside `#if DEBUG` and documented in DEBUG_HOOKS and THREAT_MODEL.
