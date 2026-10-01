# Task 1 independent code review

Reviewed 2026-10-02, read-only except this report. Scope: `cd99de9..15900e9`, every finding in `wp4_review_round1.md`, binding WP4/WP2 contracts, and the coordinator/implementation changes visible during review. No simulator or emulator was operated. This report does not claim that tests or device acceptance have passed: the coordinator and implementation agents own execution.

`Fixed` below means the relevant implementation was inspected. `Partial` means the original WIP left a material code or regression-test gap. `Pre-fixed` means the binding contract/document amendment at cd99de9 already handled the finding. Latest working-tree fixes are listed separately, so the original WIP assessment remains auditable.

## Original WIP findings matrix

| Finding | WIP status | Evidence and remaining work |
|---|---|---|
| D1 / WP4-S1 | Partial | iOS ImportedMapLibrary.load uses SafeStore.absentStoreStatus; quarantined/missing previously sealed stores remain Corrupt. ActiveMapSelectionStore.legacyStoreQuarantined blocks legacy migration. Ordinary Corrupt/Locked paths do not reconcile, sweep or prune. Retry rebuild still authorized destructive cleanup, contrary to the later handoff. |
| D2 / WP4-S2 | Partial | Android replaces empty reset with LibraryRebuild re-adoption. Both rebuilds still swept orphan bakes/pruned unmatched drafts. iOS also omitted originals beyond maxLibraryEntries and then reconciled them away. |
| D3 / WP4-S3 | Partial | iOS migrationRead no longer clears unreadable session bytes; pdfEntry failure blocks migration. Android blocks unreadable/nil retained sessions and hash failures. Remaining Android bypass: legacyPdf ignored sealed source.render.contentKey, bound old calibration to replacement bytes, and fabricated pageCount=1 when opening the PDF failed. |
| D4 / WP4-S4 | Fixed | Authenticated LegacyMapReader Absent drives WRITE_EMPTY_AND_CLEAR, after a successful library write. |
| D5 | Fixed | Remove Offline Tiles uses writableState after its IO hop, rather than captured next. |
| D6 | Fixed | BackgroundHashRules marks a held-back PDF without durably selecting online. |
| D7 | Fixed | Android successful verification refreshes file size/mtime through RefreshFileStamp. |
| D8 | Fixed | Legacy stores cleared at Loaded restore; migration drafts saved after the library write. |
| D9 | Fixed | iOS attachBake validates record before bakeKey, removeBake mismatches return sourceChanged, valid generated bake-name filtering aligned. |
| D10 | Pre-fixed | Contract and THREAT_MODEL explicitly describe loss/re-import when downgrading before 2.2. |
| C1 | Fixed | CalibrationSession tracks draftExists; clean leave/suspend writes inactive. Both platforms run draftActive fixture sequences. |
| C2 / F2 / WP4-S8 | Fixed | Android autoResume gated by suspect; explicit calibration of the suspect resolves OPEN_ANYWAY and publishes it. |
| C3 | Fixed | iOS calibrationDurableSelection checkpoint prevents unchanged durable selection from republishing over the calibration display on unlock/restore. |
| C4 | Fixed | clearCalibrationReturn called on suspend/durable online selection/session completion. |
| C5 | Fixed | Android first datum selection at zero points updates the start seed, without a draft or dirty state. |
| C6 | Fixed | Both reopen pending entry, write resumed draft active, and suppress zero-point resumed toast. |
| C8 | Partial | Android adopts the preview token as suspect and creates recoveryPreviewFor. iOS set guard.suspect and skipped E3 but never created pdfCrashSuspect for a non-durable preview, so no actionable recovery dialog appeared. |
| B2 | Fixed | Android next-corner hint shown at n=0; both suppress zoom hint off-sheet. Production panel status helpers are asserted. |
| E1 | Fixed | iOS confirms destructive page change; both current-page selections are no-ops. Five shared choosePage rows exercise decisions/reducers. |
| E2 | Partial tests | Code hides Cancel during Saving and rechecks cancellation after copy, inspect and probe. iOS new test originally asserted fixture strings/cancelVisible rather than executing cancellation through the real controller. |
| E3 | Fixed | iOS choosePage uses reading progress, Cancel, watchdog, and visible errors. |
| E4 | Fixed | Valid newly selected GeoPDF page activates; unreferenced page begins calibration. |
| E5 | Fixed | iOS PDF duplicate check now immediately follows copy/hash, before inspection. |
| E6 | Fixed | Progress carries per-run UUID; abandoned workers retain their own cancel flag and callbacks cannot mutate a later card. |
| E7 | Fixed | Page-used toast follows successful commit. |
| E8 | Fixed | iOS newly imported MBTiles name uses selected file stem. |
| E9 | Fixed | Verified replacement copy relinks unavailable duplicate while preserving calibration/bake metadata. |
| E10 | Fixed | Android failure detail no longer uses map display name as the error. |
| E11 | Fixed | Android page picker disables outside-tap dismissal and uses 120 dp thumbnail frames. |
| E12 | Fixed | Android pages after scan page 50 use inherited pageBoxes rather than only local CropBox dictionary. |
| E13 | Fixed | Android Generate menu is disabled for failed/current maps or other busy bakes; current running bake offers Cancel. |
| E14 | Fixed | Both Layers page pickers use map_choose_page_message, rather than claiming no page has georeferencing. |
| A2 / B1 | Fixed | Android uses standardUtmZone; generator/fixtures pin +180 to zone60 and -180 to zone1. |
| A1 | Fixed | Both datum display tables and cell-size interpretation text asserted against shared fixtures. |
| A3 | Fixed | iOS strips leading zeros before significant-figure limit and integer parsing. |
| B3 | Pre-fixed | Contract explicitly accepts degenerate before invalid, with fixture rationale. |
| F1 | Fixed | PdfMapRuntime tracks canonical georef; calibration source key is distinct/bake-free; onBakePublished cannot attach during calibration. |
| F3 | Fixed | Android restores migration on Retry/unlock and defers guard launch decision until tokens are known from Loaded restore. |
| F4 | Fixed | Suspect deletion resolves guard and dialog from the common successful delete path, including Retry. |
| F5 | Pre-fixed | Untrusted PDF parsing threat-model bullet restored at cd99de9. |
| WP4-S5 | Fixed | Android process startup removes cacheDir/pdfbox; threat model describes plaintext scratch lifetime. |
| WP4-S6 | Fixed | iOS thumbnail dimensions bounded to visible frame aspect; NSCache total cost limit set. |
| WP4-S7 | Partial tests | iOS failed commit retains registered owned copy for Retry; Not Now deletes/unregisters it. Original WIP had no production ownership/retry/dismiss regression. |
| WP4-S9 | Pre-fixed | Threat model honestly distinguishes migrated MBTiles without content hashes. |
| L1 | Pre-fixed | Contract retirement amendment records removed unreferenced strings and preservation of still-referenced affine errors; surviving legacy display hash protected. |
| T1 | Partial | Ratios, residual key sets, null predictors, unclamped zoom, production capture.status, hold-out truth/error, and error argument names strengthened. Parser cases still omitted independent truthWGS84/truthToleranceM. |
| T2 | Fixed | Android hold-out tolerance now fixture 1e-9, stored CalibrationPoint.resolved round-trip, >=105 parser case guard, inspections/entryStates guards, M8/M10/M12/M13 production-rule tests. |
| T3 | Fixed | Both platforms assert 18 draftActive rows and 12 autoResume rows. iOS actual camera framing remains device-level evidence; its pure test equates frameSheet with the resume action. |
| MapTourOverlay | Fixed | Upper clamp bound cannot be negative; production rule has unit regression. No equivalent iOS crash found. |

## Device-review findings matrix

These are code assessments only. A fresh device check remains required, especially layout and interaction.

| Finding | WIP status | Evidence |
|---|---|---|
| OD-F1 | Fixed | Android auto-resume frames displayed calibration fit; CalibrationPanelStatus gives off-sheet priority even if inverse page conversion fails. |
| OD-F2 | Fixed | iOS wires import controller before debug hooks and defers debug import until Loaded library. |
| OD-F3 | Fixed code | Android compact chips/icon-only Undo and datum min-width; compact German layout needs screenshot verification. |
| OD-F4 | Partial | iOS lineLimit2/minScale fixed. Android primary still maxLines3 in original WIP. |
| OD-F5 | Fixed | Android map import failures use full alert. |
| OD-F6 | Fixed | Shared byte formatting rule; 512 MiB reads 537 MB on both. |
| OD-F7 | Fixed | C5 seed-datum behavior. |
| OD-F8 | Fixed | B2 corner hint at zero points and off-sheet hint priority. |
| OD-F9 | Fixed | Inactive-entry bake confirmation uses separate localized copy on both. |
| OD-F10 | Fixed | Manual calibration Imported Map block uses calibrated state label; legacy bounds label retained for its legacy origin. |
| OD-F11 | Fixed | A1 datum names and cell-size interpretation table. |
| OD-F12 | Fixed | Shared RMS formatting cases use decimal under rounding boundary 9.95 m, whole above. |
| OD-F13 | Fixed | Migrated Android active sheet explicitly framed rather than GPS fix. |
| OD-F14 | Fixed | Footer includes valid bake bytes. Minor Android mismatch: recovered invalid MBTiles byteCount=-1 is not clamped to zero, unlike iOS. |
| OD-F15 | Fixed code | Provisional header accessibility exposes NOT GEOREFERENCED rather than made-up coordinate; copy/drop labels suppressed. |
| OD-F16 | Known | Existing iOS OCMD orthoimage limitation, no round-1 action. |
| OD-F17 | Informational | No requested action. |

## Latest fixes independently re-reviewed

- Persistent sealed recoveryPreservesOrphans flag now disables all automatic reconcile, bake sweep and draft prune on successful rebuild, later writes and cold launches. Both reducers preserve it. Low-level managed-file APIs guard it as well. Explicit Delete still directly removes its known entry/bake files; Remove Offline Tiles retains its direct delete. Known superseded bake records are removed after successful transitions, without sweeping files of unknown ownership. This resolves the handoff preservation requirement and the original reset deletion defect. Contract, threat model and fixture generator amendments explicitly supersede earlier recovery permission to sweep derived bakes.
- iOS recovery no longer applies the new-import count cap to existing files. testRecoveryAdoptsExistingMapsBeyondTheNewImportLimit covers max+1 candidates. This closes the original cap deletion and parity gap.
- iOS preview crash now creates pdfCrashSuspect from the active draft entry, recognizes both in-progress and persisted suspect tokens, and Open Anyway requests calibration resume. testPreviewCrashOffersRecoveryAgainAfterNotNowAndColdLaunch covers first suppression, Not Now, next launch and explicit resume without a durable selection change. The code was read; test execution is owned by the coordinator.
- Android LegacyMapReader now compares current hash with source.render.contentKey and blocks mismatch; failed/nonpositive pageCount blocks migration instead of fabricating page1. This closes the discovered content-binding bypass. LegacyMapReaderFailClosedInstrumentedTest now adds sealed-v2-session hash-substitution and present-unreadable-document regressions; device execution is recorded below.
- iOS parser test now checks independent truthWGS84 against truthToleranceM. Android status maxLines is now2. Both changes were inspected.
- Actual iOS UI recovery found an additional bug: optional completion invocation skipped its restore argument when no callback was supplied. The restore now always runs; testCorruptRetryWithoutCompletionPublishesTheRebuiltLibrary covers the real UI Retry path.
- iOS pendingRetry is cleared on persistence issue dismissal, avoiding a stale retry closure after dropping an owned import copy.

## Concrete test coverage reviewed

Android:

- LibraryRecoveryTest: 14+ libraryLoad rows, quarantine second-read behavior, authenticated missing-store marker behavior, candidate re-adoption, failed/crashed parse, watchdog, 12 autoResume rows, preview-suspect guard resolution, M8 held-back hash mismatch rules, D7 stamp reducer. Current recovery test also loads the sealed rebuilt library and checks preservation on later reconcile/sweep and unmatched drafts.
- CalibrationDraftActiveFixtureTest: all18 sequences run through production CalibrationController and draft storage.
- ImportPipelineContractTest: production PdfImportStages/PdfImportCommitter stage order, duplicate inspection skipping, unavailable duplicate relink, failed relink, cancellation after copy/inspection/probe, failed probe committing nothing, post-write page-used toast.
- ImportUiRulesContractTest: byte/error arguments, five Choose page rows, footer with bakes, calibrated block, bake confirm/Generate gating, tour corner bounds.
- PdfBakeAttachRulesTest: exact canonical match outside calibration and refusals during calibration. This tests the production rule, not full native renderer assembly.
- LegacyMapReaderFailClosedInstrumentedTest: unreadable sealed legacy active record blocks and stays present; authenticated calibration-library-only state writes empty/clears after success.
- PdfImportHardeningInstrumentedTest adds inherited CropBox/page51 regression.

iOS:

- CalibrationLifecycleContractTests:18 real-session draft sequences,12 autoResume decisions/real resumed-session effects, five Choose page decision/reducer rows, progress cancel visibility.
- CalibrationContractTests: numerical/null/key/predictor/anchor/capture assertions strengthened, datum/cell-size/RMS/byte-display tables; latest parser independent truth check.
- LibraryBakeLifecycleTests: second quarantined restore keeps orphan bake, invalid attach-record error ordering, established known Delete/bake lifecycle tests.
- Coordinator additions in ImportedMapLibraryTests: corrupt Retry/cold read preservation, vanished previously sealed store remains Corrupt/Locked, recovery/cold-launch orphan bake and unmatched draft preservation, preview recovery across Not Now/relaunch, recovery beyond import cap.
- iOS implementation-test agent was asked to add actual S3 corrupt selector/session/document migration tests, S7 owned-copy Retry/dismissal tests, and E2 real-controller cancellation tests. Their final results must be incorporated before marking Task1 fully accepted.

## Quality review and remaining validation

No new confirmed blocker remains in the latest inspected code. The original serious problems were ownership authority after resetting an unreadable index, binding old Android calibration to newly substituted bytes during migration, and iOS preview recovery suppressing the draft without an actionable dialog. Latest fixes address those mechanisms directly instead of weakening fixture expectations.

Completion evidence (implementation and device verification followed the independent review):

1. Fresh iOS suite:837 tests,0 failures,1 existing live-production-handshake skip. Android unit suite:930 tests,0 failures. Android focus instruments:38 green, including both new sealed-session substitution/document-open tests.
2. Actual device import/calibration four-fiduciary fits, kill/relaunch, second-import retention, confirmed delete and corruption recovery passed on both platforms. See WP4_IOS_DEVICE.md and WP4_ANDROID_DEVICE.md for exact evidence and distinctions between UI checks and production file-cascade tests. German compact appearance still belongs to the broader merged-device visual pass in Task4.
3. Both builds/full unit suites and localization checker are green after the edits. Checker validates2074 messages,23 plural families and424 sources. Negative regression proof for corrupt recovery, preview recovery and nil-callback Retry is retained with unchanged expectations.
4. Added testRecoveredLibraryRemovesKnownSupersededBakeAndKeepsUnknownBake, driving production MapViewModel.changePage while orphan cleanup is disabled.

Android footerBytes now clamps recovered-invalid byte counts to zero, matching iOS. Best-effort parse-marker writes and swallowed draft-save failures during migration are broader robustness concerns; this review found no new proven loss path beyond the addressed findings. Existing pure helper extraction usefully makes Android import stages testable, but tests must keep exercising the call sites as well as the helper predicates. Automatic cleanup remains disabled indefinitely after recovery by design because reconstructed ownership is insufficient to authorize deleting unknown files; the threat model now states that tradeoff.
