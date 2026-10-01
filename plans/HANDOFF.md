# Handoff: finish the PDF import + MGRS grid + Unit Sync revision

For: ChatGPT / Codex (local CLI on the owner's Mac). Written by Claude Code 2026-10-02
after checking every branch, compiling both platforms and running the unit suites.

## The goal (owner's request)

1. PDF import (GeoPDF and plain PDF with fiduciary control points) must work flawlessly
   on iOS AND Android: the MGRS grid overlay sits on the sheet's printed grid, zoom in/out
   stays crisp without breaking the UX.
2. Unit Sync (Cloudflare relay + both clients) efficient and stable with very high assurance.
3. All code validated against docs/THREAT_MODEL.md (binding) and the app made compliant.

## Verified state (2026-10-02)

All branches are LOCAL worktrees under `/Users/cbrooker/Code/TacticalMaps/.claude/worktrees/`
(not pushed). Never work in the main checkout `/Users/cbrooker/Code/TacticalMaps` itself;
other sessions use it.

| Worktree / branch | What it holds | Status |
|---|---|---|
| `review-pdf-sync-tm` / `review/pdf-sync-threat-model` | INTEGRATION branch. WP1 georeference core (f2a5d25), WP3 MGRS grid (d563b56), mainline merge incl. PRs #45-#47 (7022052), design notes, this handoff. | DONE + independently reviewed. iOS 617 / Android 726 unit tests green at that point. |
| `review-wp2` / `review/pdf-render` | WP2 warped tile renderer + offline bake, one squashed commit 1a05935. | DONE + reviewed in 4 rounds (parity, security, on-device each round). Rulings in `plans/resume/`. Already merged into review-wp4. |
| `review-wp4` / `review/pdf-calibration` | WP4/5 calibration UX + import lifecycle, contains WP2 (merge a940b0b). Round-1 review + rulings (be1344b), contract amendments (cd99de9), fixes in progress (WIP 15900e9). | IN PROGRESS. Builds; unit tests green (iOS 819, Android 930). `scripts/check_localizations.py` FAILS: register `android/app/src/main/java/com/tacmap/map/ImportUiRules.kt` and `MapImportCommit.kt` in `localization/source-inventory.json`. Unknown which round-1 items the WIP fixes cover - see Task 1. |
| `review-sync` / `review/sync-relay` | SP1 relay (1e21684), integration merge (78b945d), SP2/SP3 client WIP (79eb183, cb99cab). | Relay DONE + reviewed (117 vitest, soak converges), NOT deployed. Client SP2 done on both platforms, SP3 done on Android only. Android unit 893 green. iOS unit RED: 14 failures and the run aborted after 159 tests (likely the half-finished iOS SP3 in cb99cab; try a freshly erased simulator first to rule out sim state). Client work not yet independently reviewed. |
| `review-grid` | old WP3 branch | merged; delete. |

## Read first

- `plans/02-pdf-import-revision.md` - PDF plan (binding).
- `plans/WP2-render-shared_contract.md` (with its 2026-10-02 amendment), `plans/WP4-calibration-lifecycle-shared_contract.md` - binding cross-platform contracts; the `*_ios_design.md` / `*_android_design.md` files are the per-platform designs.
- `plans/03-unit-sync-revision.md` and, on review-sync, `plans/04-sync-client-contract.md` - sync plan + binding client behaviour contract.
- `plans/resume/` on review-wp4 (and review-wp2): `RULES.md`, `wp4_review_round1.md` (open WP4 findings + rulings), `WP4_MERGE_SPEC.md`, `wp2_review_round*.md`, `sync_reports.json` (agent reports per platform with open issues).
- `plans/audit/pdf_audit.json`, `plans/audit/sync_audit.json` (on the integration branch) - the original audit findings (ids D1-D6, S1-S6) with file:line evidence and proving tests.
- `testdata/README.md` - every shared fixture. Both platforms load the same JSON fixtures; expected values come from generators under `scripts/` and `testdata/tools/` (pyproj = independent reference). Never edit expected values to pass a test.

## Tasks, in order (one package per session; commit each when green)

### Task 1 - finish WP4/5 (worktree review-wp4)
1. Diff the WIP (`git diff cd99de9 15900e9`) against every item in `plans/resume/wp4_review_round1.md` and list which are fixed.
2. Fix the rest. Priorities: **WP4-S1 BLOCKER iOS** (a corrupt/undecryptable library or corrupt legacy selection leads to reconcile/sweep/prune with an empty keep set = every imported map, bake and draft deleted; must never reconcile/sweep/prune on Corrupt/Locked/reset), WP4-S2 (Android reset must not delete orphaned files), WP4-S3 (migration must fail closed, never silently drop a legacy PDF), then C1, C3, E1, E2 (iOS), F1, F3 (Android), then minors S4-S9.
3. Each fix gets a regression test that fails without it. Register the 2 new Android sources so `python3 scripts/check_localizations.py` passes.
4. On-device check on an iOS simulator and an Android emulator: corrupt-library recovery keeps files; import, calibrate `testdata/geopdf/tacmap_grid_rot5_plain.pdf` with the 4 grid-intersection fiduciaries from `testdata/pdf_georef.json` fiduciaryFits; kill mid-calibration and relaunch; second import doesn't delete the first; delete with confirm removes files, bakes and calibration.
5. Squash `e3369bc..HEAD` (excluding the already-squashed WP2 commit) into one WP4 commit.

### Task 2 - finish sync clients (worktree review-sync)
1. Get the iOS suite green. Then finish iOS SP3 per `plans/04-sync-client-contract.md` (sections 17-21): batched persistence (no per-record / per-presence full-file reseal), journal bump batching, wire-id reverse map, snapshot validation/apply off the main thread with one commit, stationary presence suppression, presence-only redraw (no root-view re-render per presence frame), PBKDF2 off the UI thread, background presence rules (contract section 21; background reconnect stays switched off via RECONNECT_ENABLED=false like Android unless the owner decides otherwise).
2. Resolve the open items both platforms reported (see `sync_reports.json`): chat history prune order (contract says bytes first, both apps do count first - fix code to match contract), iOS pacer window leak if a superseded rid is never released, leave frame bypassing the pacer, Retry/paused-changes UX difference.
3. Land the remaining doc changes listed in plans/04 section 25 (D4: presence counters persisted in strides vs ADR-001 section 8; D9: background peers can't receive chat, ADR-004) and fix sync/README's stale SP2 sentence. THREAT_MODEL.md must match the code exactly.
4. Independent review of the whole client diff (`git diff 1e21684 -- android ios testdata plans docs`): security first (no replay, rollback acceptance, signature/AEAD bypass; a snapshot seq regression must stay a SECURITY-level notice and never be shown as a benign expiry), then cross-platform parity against plans/04 and `testdata/sync_client_behaviour.json`.
5. Live interop: `cd sync && npm ci && npx wrangler dev --port 8796 --local`, point an iOS simulator (ws://127.0.0.1:8796) and an Android emulator (ws://10.0.2.2:8796) at it via the self-host relay URL setting, join one v3 room, and verify presence, waypoint/drawing create/edit/delete, chat, app kill + relaunch, relay restart, Android background presence - both directions.
6. Must keep working against BOTH the current production relay and the SP1 relay; no wire-format changes.

### Task 3 - integrate
Merge review-wp4 (contains WP2) and review-sync into review-pdf-sync-tm. Localisation conflicts: merge `localization/catalog.json` and `reviews.json` key by key (3-way JSON merge), then `python3 scripts/generate_localizations.py` and `python3 scripts/check_localizations.py`. Full unit suites on both platforms + `cd sync && npx vitest run && npm run typecheck`.

### Task 4 - on-device verification of the merged build
Import every sheet in `testdata/geopdf/` plus `samples/USGS_SF_North.pdf` (`scripts/fetch_samples.sh`) via the DEBUG hooks (`docs/DEBUG_HOOKS.md`), MGRS grid on, screenshots at sheet-fit, z14, z16, z18, z20; measure printed red grid vs overlay with `plans/audit/pdf-audit-scripts/grid_alignment.py` (target <= 1 px); zoom sweeps, pan across tile seams, bake vs live alignment, Android config change during a bake.

### Task 5 - threat-model pass
Validate the merged code against `docs/THREAT_MODEL.md`; fix gaps or update the doc explicitly. Regenerate the site pages (`node site/build-docs.mjs`) for THREAT_MODEL and privacy-policy changes (do not deploy).

### Task 6 - release prep
Bump iOS `CFBundleVersion` (`ios/project.yml`) and Android `versionCode` (`android/app/build.gradle.kts`) above whatever main has, in the same commit as the final code. Then stop and report to the owner.

## Owner decisions still open (ask, don't decide)
- Relay retention (SP1): objects and device pins deleted after 7 idle days; counters + tombstones kept at most 90 idle days, then a full purge. Docs/privacy policies already describe this.
- Whether to enable background reconnect for background presence (built, switched off).
- Any push, PR, relay deploy (`wrangler deploy`) or website deploy.

## Rules
- Work only in the review-* worktrees. Never use Android emulators `emulator-5554`/`emulator-5556` (another session's screenshots); start your own (e.g. `emulator -avd TacMap_API_36 -read-only -port 5560 -no-window`). Use your own iOS simulators ("TacMap Review iPhone 1-4" exist).
- Code comments must sound like a human dev wrote them: no emdashes, no emoji, casual and terse, no markdown in comments.
- All user-visible text goes through `localization/catalog.json` (en + de); `scripts/check_localizations.py` must pass.
- THREAT_MODEL.md is binding: security-relevant changes update it in the same commit.
- Cross-platform behaviour is pinned by shared fixtures in `testdata/`; both suites must assert them.
- Another session has overlapping branches (`fix/android-geopdf-lpts-overhang`, `fix/mgrs-grid-label-off-by-one`, `fix/android-sync-chat-lock-bugs`, `fix/ios-sync-undo-peer-edits`). If they land on main first, merge main and keep this implementation where they conflict, keeping their tests where still meaningful.

## Commands
- iOS: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodegen generate`, then `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project ios/TacticalMaps.xcodeproj -scheme TacticalMaps -destination 'id=<simulator udid>' test -only-testing:TacticalMapsTests`. App product TacMap.app, bundle id com.tacticalmaps.app.
- Android: `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest :app:assembleDebug`. App id com.tacmap. FLAG_SECURE blocks screenshots unless the debug hook lifts it.
- Relay: `cd sync && npm ci && npx vitest run && npm run typecheck && npm run check` (check = dry run only). Soak: `npm run soak -- --spawn --port 8797 --clients 8 --seconds 90 --restart-at 30,60`.
- Fixture generators: `python3 -m venv venv && venv/bin/pip install pyproj numpy pymupdf pillow`.

## Cost control
One task per session, starting fresh each time with this file. Use high reasoning effort for Task 2 (security) and Task 5, medium for Tasks 3 and 6.
