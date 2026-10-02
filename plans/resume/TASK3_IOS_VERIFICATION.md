# Task 3 independent iOS integration verification

Status: ACCEPTED for the merged iOS source/build/unit-suite gate. No unresolved integration finding remains. Task 4 device/rendering acceptance is separate and has not started in this verification role.

Verified worktree: `/Users/cbrooker/Code/TacticalMaps/.claude/worktrees/review-pdf-sync-tm`. Reviewed parents: WP4 (includes WP2) `8b08cf0`, sync `92e49dd`; WP4 integration merge `664f0b0`, followed by stable staged sync merge and the narrow presence/calibration integration repair. The verifier authored no production source or test repair.

## Generated project and actual executed tests

Exclusive simulator: `6BADDA88-5E21-4E0B-983D-E69D22C5B6D8`. New derived data: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-integration/iosDD`. No shared build directory or device collision.

From `ios/`, ran `xcodegen generate`, then normal signed `xcodebuild test -project TacticalMaps.xcodeproj -scheme TacticalMaps -destination id=6BADDA88-5E21-4E0B-983D-E69D22C5B6D8 -derivedDataPath /Users/cbrooker/.claude/jobs/10094e99/tmp/codex-integration/iosDD -parallel-testing-enabled NO -only-testing:TacticalMapsTests`, using `/Applications/Xcode.app/Contents/Developer`. This builds the actual merged app/test artifacts and executes the entire unit target.

- Project generation succeeds: `codex-integration/ios-xcodegen.log`.
- Focused actual coordinator presence/calibration regression: **1 test, 0 failures, no skips**, successful command. Log `ios-merged-presence-focused-final.log`; result `iosDD/Logs/Test/Test-TacticalMaps-2026.10.02_10-38-51-+1000.xcresult`.
- Complete merged unit suite: **991 tests, 0 failures, 2 documented opt-in live skips**, successful command (`TEST SUCCEEDED`, exit 0). Log `ios-merged-units.log`; result `iosDD/Logs/Test/Test-TacticalMaps-2026.10.02_10-39-35-+1000.xcresult`.
- The only skipped tests are `SyncNativeInteropTests.testNativeAndroidRoundTripWhenLocalRelayIsProvided` and `SyncProtocolV3Tests.testLiveProductionHandshakeWhenRelayIsProvided`, because this unit command does not provide live fixtures. Those skips are not interoperability proof; Task 2 enabled native results remain documented separately.

All evidence above lives under `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-integration/`.

The first fresh focused build caught an obsolete WP4 test call at `PDFTileSourceTests.swift:886`: the direct-presence sync merge removed the `peers` parameter from `Coordinator.updateOverlays`. The integration owner adapted the test to Main ownership and `observePresence`, preserving every latest-wins/grid/camera/LOD assertion. Initial failure is retained in `ios-merged-presence-focused.log` (exit 65). The focused command's optional grid selector named the wrong test class and executed zero matching grid tests; no claim is made for that selector. The complete unit command genuinely executes and passes `PDFDebugAndMemoTests.testGridBuildsAreLatestWins` and the new presence/calibration regression.

## Independent merge-preservation checks

Recursive three-way JSON comparison against the reviewed parents' merge base found no simultaneous semantic leaf conflicts and **zero missing/incorrect merged leaves**:

| File | WP4 changed leaves | Sync changed leaves | Merged mismatches |
| --- | ---: | ---: | ---: |
| `localization/catalog.json` | 1546 | 136 | 0 |
| `localization/reviews.json` | 746 | 51 | 0 |

Exact verification: `localization-expected-conflicts.json` and `localization-merge-verification.json` in scratch. The integration owner separately ran localization generation/check successfully; the independent merged unit suite also passes `LocalizationTests`.

All changed WP4 fixture data/PDFs and sync fixture data/generator are byte-identical to their reviewed branch versions. `testdata/README.md` intentionally combines both sets of documentation. Evidence: `fixture-merge-verification.json`. The complete suite retains and passes calibration input/report/lifecycle/session, imported-map library/migration/bake lifecycle, PDF renderer/real fixture/shared georeference vectors, sync shared client behavior/SP3, held-worker persistence, hostile protocol and chat crypto tests. No missing GeoPDF/shared-fixture skip occurred.

Every iOS production file modified by only one reviewed branch remains byte-identical to that branch. Shared production changes are restricted to `ContentView.swift`, `TileMapContainer.swift`, and generated localization Messages/strings; evidence `production-merge-preservation.json`.

## Relevant source intersection review

The combined ContentView retains WP4 import/library/calibration behavior and the independently reviewed sync foreground/key-lock/background-presence lifecycle. Mission key changes still fence sync and chat. No merged change weakens signed-peer/session binding, durable replay/model application, background store detach, encrypted persistence, or import limits.

The coordinator keeps sync presence on its dedicated publisher, so location frames move presence overlays without rebuilding durable drawings or the PDF tile pipeline. The integration repair adds an effective presence visibility gate during calibration. Incoming presence publications stay hidden during calibration; exiting calibration restores the latest current model, and detaching the model clears markers. The actual coordinator regression verifies hidden marker/tap behavior after an incoming update, latest peer location after restoration, and nil-model cleanup. WP4 calibration tile generation, marker/grid masking, live/baked tile rendering and latest-wins grid scheduling remain intact.

Final `git diff --check` succeeds. No production edit, commit, push, deployment, version bump or Task 4 action was performed by this verifier. Simulator/build directory are released after the successful command.
