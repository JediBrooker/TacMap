# Handoff: PDF import + MGRS grid + Unit Sync revision (for Codex)

Started by Claude Code 2026-09-30, paused 2026-10-01 at the weekly usage limit.
Owner request: revise PDF import (GeoPDF + plain PDF with fiduciary input) so it
works flawlessly on iOS AND Android (MGRS grid must sit on the printed grid, zoom
in/out must stay crisp with no UX breakage); then revise Unit Sync (relay + both
clients) for efficiency and stability with very high assurance; finally validate
all code against docs/THREAT_MODEL.md and make the app compliant.

## Branches (all local worktrees under .claude/worktrees, not pushed)

| Worktree | Branch | State |
|---|---|---|
| review-pdf-sync-tm | review/pdf-sync-threat-model | INTEGRATION branch. Done + independently reviewed: WP1 georeference core (f2a5d25), WP3 MGRS grid (merge d563b56), mainline merge incl. #45-#47 (7022052). iOS 617 / Android 726 unit tests green. |
| review-sync | review/sync-relay | SP1 relay DONE + reviewed (1e21684, 117 vitest, soak OK, NOT deployed). Integration branch merged in (78b945d). SP2/SP3 client work WIP (79eb183, cb99cab) - unfinished, unreviewed. |
| review-wp2 | review/pdf-render | WP2 renderer WIP (d069d2b, 92f008e) - unfinished, unreviewed. Fixture done. |
| review-wp4 | review/pdf-calibration | WP4/5 calibration UX + import lifecycle WIP (025cd8a, 76eb58a) - unfinished, unreviewed. Fixtures done. |
| review-grid | review/mgrs-grid | already merged into the integration branch; can be deleted. |

Squash the WIP commits when finishing each package.

## Read first

- plans/02-pdf-import-revision.md - binding PDF plan (sections 1-6).
- plans/WP2-render-shared_contract.md + WP2-render-{ios,android}_design.md - renderer design (binding contract; designs were judged across platforms).
- plans/WP4-calibration-lifecycle-shared_contract.md + WP4-...-{ios,android}_design.md - calibration UX + lifecycle (states, thresholds, en+de copy).
- plans/03-unit-sync-revision.md and plans/04-sync-client-contract.md (on review/sync-relay) - sync plan and the binding client behaviour contract.
- plans/audit/pdf_audit.json, plans/audit/sync_audit.json - every audited finding (ids D1-D6, S1-S6) with file:line evidence, failure scenario, suggested fix, proving test, and a skeptic's verdict. Commit messages and plans cite these ids.
- testdata/README.md - every shared fixture. Both platforms must load and assert the same fixture files; never edit expected values to make a test pass (fix the code, or the generator under scripts/ or testdata/tools/ and regenerate).

## Remaining work, in order

1. **WP2 renderer** (review-wp2): finish per the WP2 contract on both platforms - on-demand warped Web Mercator tile source via PdfGeoreference.toPage with the adaptive warp planner, base-raster fast path, crop clip, transparent off-sheet, display-density tiles, detailZoom + overzoom, parent/child tile fallback for all raster sources, memory-only LRU, bake reusing the same renderer (keeps source PDF, cancellable, survives sheet dismissal / Android config change, iOS completeUntilFirstUserAuthentication), visibility toggle, render-failure surfacing, crash-loop breaker; remove the old single-image overlay. Also: raster tile rounding (D4-16); iOS cancel stale MGRS grid builds; Android skip tiny 100 km squares near the poles and stroke grid levels fine-to-coarse like iOS; DEBUG-only hooks (TACMAP_DEBUG_IMPORT_PDF / _CAMERA / _GRID / Android _OPSEC_ALLOW_SCREENSHOTS) documented in docs/DEBUG_HOOKS.md and THREAT_MODEL section 9, compiled out of release.
2. **WP4/5 calibration + lifecycle** (review-wp4): crosshair + Add point with live pan/zoom, page point = georef.toPage(crosshair), coordinate entry per testdata/calibration_input.json, full datum picker, live refit keeping the page point under the crosshair, fit panel per testdata/calibration_fit_report.json, move/edit/delete/undo, pending markers, sealed in-progress persistence tied to the content key; library of imported maps (no silent deletion), delete with confirm, multi-page choice, budgets per testdata/import_limits.json, iOS backup exclusion + opaque names, Android no plaintext URI prefs. WP1 leftovers: iOS PDFSessionStore lock across migration parse; Android saveToLibrary under SESSION_LOCK, legacyPdfMigrationPending check, catalogue the English-only WP1 strings.
3. **SP2/SP3 sync clients** (review-sync): every rule in plans/04 on both platforms - transport seam + deterministic state-machine tests, poison-record skip (seq regression stays a SECURITY-level notice, never a benign expiry), nack table (stale is benign, counter-window pauses writes), progress watchdogs, Android connect timeout, outbound pacer under relayLimits.clientPacing incl. retries and the hello-ack tombstone resend, lifecycle parity, chat fence pruning + byte cap, batched persistence, wire-id reverse map, off-main snapshot apply, presence suppression, PBKDF2 off main, background presence robustness. Must work against BOTH the current production relay and the SP1 relay; no wire format changes.
4. **Merge** review-wp2, review-wp4, review-sync into review/pdf-sync-threat-model; resolve localisation conflicts by a key-wise JSON merge of localization/catalog.json + reviews.json, then `python3 scripts/generate_localizations.py`.
5. **On-device verification** (iOS simulator + Android emulator): import every sheet in testdata/geopdf plus samples/USGS_SF_North.pdf (scripts/fetch_samples.sh), MGRS grid on, screenshots at sheet-fit/z14/z16/z18/z20, measure printed red grid vs overlay with plans/audit/pdf-audit-scripts/grid_alignment.py (target <= 1 px); calibrate the *_plain sheets with the fiduciaryFits points; zoom sweeps, tile seams, bake vs live; live sync interop of iOS sim + Android emulator in one room on `cd sync && npx wrangler dev --port 8796 --local` (presence, objects, edits, deletes, chat, app kill, relay restart, Android background presence).
6. **Threat-model pass**: validate all code against docs/THREAT_MODEL.md (binding); fix gaps or update the doc explicitly; regenerate site pages (site/build-docs.mjs) for THREAT_MODEL / privacy policy changes.
7. Bump iOS CFBundleVersion (ios/project.yml) and Android versionCode (android/app/build.gradle.kts) in the same commit as the final code, above whatever main has.

## Rules

- Never work in the main checkout (/Users/cbrooker/Code/TacticalMaps itself); other sessions use it. Use the worktrees. Never use emulator-5554/5556 (screenshot session).
- Code comments must sound like a human dev: no emdashes, no emoji, casual and terse, no markdown in comments.
- All user-visible text through localization/catalog.json (en + de) and `python3 scripts/check_localizations.py` must pass.
- THREAT_MODEL.md is binding; any security-relevant change updates it in the same change. Cross-platform behaviour is pinned by shared fixtures in testdata/.
- Do NOT deploy the relay or the website and do not push without the owner's go-ahead. Owner still has to confirm the SP1 retention rule (objects/pins deleted after 7 idle days; counters + tombstones kept at most 90 idle days, then a full purge).
- Overlapping branches from another session: fix/android-geopdf-lpts-overhang, fix/mgrs-grid-label-off-by-one, fix/android-sync-chat-lock-bugs, fix/ios-sync-undo-peer-edits. If they land on main first, merge main and keep this branch's implementation where they conflict (it supersedes them), keeping their tests where still meaningful.

## Commands

- iOS: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodegen generate` then `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project ios/TacticalMaps.xcodeproj -scheme TacticalMaps -destination 'id=<sim udid>' test -only-testing:TacticalMapsTests` (app product is TacMap.app, bundle com.tacticalmaps.app).
- Android: `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest :app:assembleDebug` (app id com.tacmap; FLAG_SECURE blocks screencap unless the debug hook lifts it).
- Relay: `cd sync && npm ci && npx vitest run && npm run typecheck && npm run check` (check = dry run only); soak: `npm run soak -- --spawn --port 8797 --clients 8 --seconds 90 --restart-at 30,60`.
- Fixture generators need `pip install pyproj numpy pymupdf pillow`.

## Review

After Codex finishes, have an independent reviewer (e.g. Claude Code once its quota resets) review each package's diff before merging to main, especially the SP2 security relaxations and the renderer.
