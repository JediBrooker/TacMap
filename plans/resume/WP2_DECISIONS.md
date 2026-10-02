# WP2 coordinator decisions (resolve the round-0 parity divergences)

Full round-0 review output (parity + security, issue lists with file:line): /Users/cbrooker/.claude/jobs/10094e99/tmp/wp2_review_round0.json

These are binding. Record each in plans/WP2-render-shared_contract.md (short amendment under the relevant section, marked "Amendment 2026-10-02") and pin with fixture cases where marked [fixture].

E1. Heavy EWMA scope: per document session (one estimator per open PDF render service / tile source; a newly opened document starts light). Fed by every vector page draw, live and bake alike. Jobs whose tiles are all EMPTY do not feed it. Android drops its process-global timer.
E2. Bake job formation: zooms <= baseMaxZoom (raster-sampled) always 1x1 jobs. Above baseMaxZoom, heavy is evaluated once at the start of each zoom level and fixed for that level. [fixture: add a bake job-formation case if the existing jobFormation section doesn't cover it]
E3. Scheduler inputs: setViewport always gets the real viewport centre (TileMath viewport centre unit) and the tile zoom, even when nothing at tz is requested. EMPTY tiles are never parked in the orphan cache. While the PDF layer is hidden or the camera is below the sheet's min zoom, wanted-tile callbacks are ignored on both platforms.
G1. Sticky failure display: keep the failed tile source attached, so already-cached tiles and fallback ancestors keep drawing; show the failure banner with Try Again (Android's current behaviour). iOS stops swapping to nil.
G2. Failure accounting table: immediately sticky = passwordProtected, pageMissing, pageGeometry, and the document-level blank verdict. Counted toward the run of 3 consecutive failures = per-job render errors including outOfMemory and base-raster sample failures (raster-sample path counted on both). On the 3rd consecutive counted failure the source goes sticky with the reason of that 3rd failure. A successful job resets the run. [fixture: add a failureAccounting case list]
G3. Base raster: clip to the clip polygon and paper-fill inside it before the blank check, on both platforms (iOS behaviour). Staged region padding 1.25 and the 2x pixel cap enforced identically.
H1. "Use Online Map" and the crash-guard suppress path restore the user's preferred online basemap on both (Android behaviour). iOS header uses PDFRenderStatusColors.ready instead of a literal colour.
J1. Bake confirm UI: Android's flow on both: radio option rows + Generate / Cancel, time estimate follows the selected option, options that don't fit free space are shown disabled with the needed size. Same post-bake buttons and the same toggle behaviour during calibration.
J2. Bake estimate: one pure function per platform, same formula: estimatedMs = sum over bake jobs (formed per E2) of jobMs(EWMA, or the contract fallback when no samples) + tiles * encodeMs. Same sample selection (write it down: which tiles get sampled, how many, fallback). ENOSPC / SQLITE_FULL map to noSpace with the real needed byte count on both. [fixture: add bakeEstimate cases]
J3. Bake encoding / PSNR: both platforms gate at PSNR >= 35 dB vs the live render on the rot5 fixture sheet and dense linework. Android may switch encoding (e.g. lossless WebP, measured smaller for paper/line sheets) to meet it; measure the size on samples/USGS_SF_North.pdf too and record bytes/tile in contract J; update the size estimate constants accordingly. Gates aligned in both test suites.
K1. Crash guard: the bake marker gets its own slot (bakeInProgress) so an import or other arm never overwrites it; complete(.import) never clears it; a crash during the bake still yields the bakeInterrupted notice. [fixture: import-during-bake case in crashGuard]
L1. Remove the dead iOS legacy catalogue entries (ui_bakes_this_calibrated_map_into_an_offline_tile_s_f73ad763, ui_generate_offline_tiles_d8b4f85a, ui_offline_tiles_2c47000d) if no code references them; follow testdata/README.md for the legacy-display-hashes fixture (regenerate with its tool, do not hand edit).
L2. Android Layers PDF subtitle switches on placement origin with the same catalogue entries iOS uses (Adobe /VP vs LGIDict vs manual vs provisional).

Security / correctness fixes (all from the security review; do all of them):
S1 (major, iOS) PDFBakeController.finishRun must re-load the stored session, require same fileName + renderGuardToken and bakeKey(stored georef, tilePx) == ctx.bakeKey, else fail .sourceChanged; write only the bake field into the stored session (never re-save the captured whole object).
S2 (Android) bake estimate tile counting capped like iOS (stop past MAX_TILES), level()/count() take a limit and return early, primitive arrays, ensureActive checks.
S3 (Android) fsync the .partial MBTiles before the move and fsync the offline_tiles dir before attachBake commits.
S4 (Android tests) fix the orphan-cache test so it would catch EMPTY parking; add a test that all-EMPTY jobs don't feed the EWMA; add the reconcile test the review asked for. Spot-check by mutation in a scratch copy.
S5 (iOS) PDFBakeController releases its PDFRenderService and clears service/ctx/pdf on dismiss, failure, finish, cancel, cancel(contentKey:).
S6 (Android) PdfBakeManager races: release tied to the owning job, shared fields confined to Main (or @Volatile), prepare joins the previous job.
S7 (iOS) PDFBakeWorker renderBlock/fetchBaseRaster result taken once behind a lock/atomic flag.
S8 docs/THREAT_MODEL.md: list baked offline tiles in the plaintext-at-rest bullet (location, protection class, backup exclusion, reaped by Remove Offline Tiles / Delete Map via reconcile).
Fixture test gaps (parity review): iOS adds the 0.01 px pure-geometry checks for markers/ringTargets/alphaSamples, errorBoundMet equality, sha256/bytes checks for the markers/blank PDFs, dropped-candidate tile counts, iosWhite vs the TileMapView constant; Android adds whatever the review listed as missing on its side (see the review JSON).
