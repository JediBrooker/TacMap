# WP2 round-1 review findings (against 5a96698; condensed by the coordinator)

## Parity / correctness review
A1 major android. PdfTileSource.kt:160-164 onWanted does `if (!failed) baseDeferred(lastBand)` and discards it; PdfRenderSession.kt:164-168 replaces a failed deferred with a fresh full-page render; only awaitBaseLive (PdfTileSource.kt:182-235, reached by loadTile for z <= baseMaxZoom) counts/sticks. So above baseMaxZoom a failed base raster re-renders on every request change, never counts, never applies blank verdict / ready / re-plan. iOS reference PDFTileSource.swift:149-181 install/adoptBase starts once and subscribes.
D1 major android. PdfBakeInstrumentedTest.kt:161,167,263 checks only listOf(baseMaxZoom, maxZoom) at 512 px; rot5 maxZoom hardcoded 14 == its baseMaxZoom, so only raster tiles; dense sheet maxZoom 11 == baseMaxZoom. Android dense sheet (1.5 pt grid, 0.6 pt strokes) differs from iOS (0.25 pt diagonals + 0.3 pt red grid on sf_iso box). iOS PDFBakeTests.swift:415-428 loops baseMaxZoom..D at 768+512 and asserts >= 3 zooms.
D2 major ios. PDFBakeTests.swift:52-54 proposal() and :540 PDFBakePublishTests.confirm() throw XCTSkip when the estimate isn't .confirming, so a J2 regression skips the S1/S5/J1/PSNR tests.
B1 minor both. iOS PDFTileSource.swift:179 adoptBase records .jobOk; Android PdfTileSource.kt:187,205 markReady only.
B2 minor android. PdfTileSource.kt:184-212 retries base OOM at half budget uncounted then changes basePlan/baseMaxZoom mid-session; iOS PDFTileSource.swift:158-168 counts, no retry.
B3 minor both. iOS PDFRenderService.swift:443-453 + PDFTileSource.swift:316-330 counts only through live waiters; Android PdfJobQueue.kt:153-158 + PdfTileSource.kt:325-332 once per job.
A6 minor both. Started-then-cancelled jobs feed EWMA (iOS PDFRenderService.swift:468-471, Android PdfJobQueue.kt:131-133,154-157); E1 text says cancelled don't.
A2 minor android. PdfJobQueue.kt:121-124,131-133; PdfTileSource.kt:307-322; PdfRenderSession.kt:64-67: timer starts before render(), includes armIfNeeded(VECTOR) fsync and withPage open(). iOS PDFRenderService.swift:539-544,679-689 starts after page(for:).
A3 minor android. TileMapView.kt:110-117 LaunchedEffect keyed on (frame.requests, frame.tileZoom, source): a pan with unchanged requests never calls setViewport. iOS TileMapView.swift:162 every layout.
A4 minor android. PdfTileGeometry.kt:387-390,396-407 no +-1 column margin / no +-1 row in the vertical branch; iOS PDFTileGeometry.swift:379-382 widens.
A5 minor android. PdfBakeManager.kt:123,129 vs :294-310 stop() sets job = null (:301) without joining; late finally can clear session.bakeRunning or guard.complete(BAKE, token) of a newer bake.
C4 minor android. PdfBaker.kt:254-258 withContext(IO){publish} then published=true; PdfBakeManager.kt:225-230. Cancel during publish: saved but reported cancelled, duplicate on next Generate.
C3 minor android. PdfBaker.kt:309-316; PdfSessionStore.kt:343-357,376-381 updateStored false for both seal failure and mismatch -> sourceChanged; iOS writeFailed.
C5 minor ios (unconfirmed). MBTilesWriter.swift fail() last error wins; PDFBakeWorker.swift run() commit(); begin(); then hadError, BEGIN error may overwrite SQLITE_FULL.
C1 minor both. Android PdfBakeManager.kt:198-206 + PdfTileSource.kt:335-338 renderForBake awaits base inside the timed span; iOS PDFBakeController.swift measure() fetches base before the timer.
C2 minor both. Android PdfBakeManager.kt:202-212, DOCUMENT_FAILURES :314-320 fails the estimate; iOS PDFBakeController.swift:314-321 skips the sample (bake fails later at PDFBakeWorker.swift:224).
C6 minor android. LayersSheet.kt PdfBakeSection Running branch shows any bake's progress; iOS LayersSheet.swift bakeRows matches contentKey.
B4 minor ios. PDFTileSource.swift:258-263 (orphan), :271-275 (bake row) check only ticket.isCancelled, not status.failure (vector/raster do, :321, :351).
B5 minor both. iOS PDFTileRenderer.swift:232-247,263-265 throws renderError on empty staged region / bad required; Android PdfTileRenderer.kt:125 + PdfRenderScheduling.kt:67-78 pad 0 / paper-white success.
B6 minor catalogue. Android LayersSheet.kt:248-255 + Messages.kt:190-195 use android-only layers_pdf_georeferenced / layers_pdf_manual_bounds / layers_pdf_no_georeferencing; iOS LayersSheet.swift:167-174 has ui_* duplicates.
B7 minor ios info. JP2 decode is off main (PDFBakeReader.tileImage on rasterQueue, RasterTileSource.swift:66-69 preparingForDisplay) except the `?? img` fallback which would decode at CA commit on main.
D3 minor android. ActiveMapSelectionStoreTest.kt:299-332 passes currentPdfBakeFile by hand; ActiveMapSelectionStore.kt:278 default { null }; MapViewModel.kt:751 wiring untested.
D4 minor both. PSNR 35.0 literals: Android PdfTileRenderFixtureTest.kt:151, PdfBakeInstrumentedTest.kt:325; iOS PDFBakeTests.swift:426-427,470.
D5 minor both. No shared stagedRegion fixture; Android PdfRenderRulesTest.kt:23-48 hand literals; no iOS stagedPlan test.
D6 minor ios. PDFTileRenderFixtureTests.swift:517-521 unknown reason -> nil (counted), path ignored; Android PdfTileRenderFixtureTest.kt:627-636 strict.
D7 minor ios. PDFTileRenderFixtureTests.swift:572,581-636 no detailZoom / option tiles == footprint sum asserts; d2 tolerance hardcoded 1e-9.
D8 minor both. Literals vs constants: iOS PDFTileRenderFixtureTests.swift:103,111-114,182,187-188; Android PdfTileRenderFixtureTest.kt:76,136-137,153-154; PdfTileRenderInstrumentedTest.kt:145-146,218-219 (0.5 vs tolerances.markerCentroidPx).
D9 docs. plans/WP2-render-shared_contract.md:428 says fallback (20000); J3 table claims the same 13 tiles but selections differ.

## Security review
S1, S2, S3, S5, S6, S7, S8 fixed; S4 partial (see F5).
F1 minor both. Late cancel still publishes. Android PdfBaker.kt:245-249, PdfBakeManager.kt:265-267 (same as C4). iOS PDFBakeController.swift:507-508, 415-421; PDFBakeWorker.swift:258-270: cancel() only sets a flag, run() never re-checks after the tile loop, finishRun publishes on .success regardless.
F2 minor android. PdfBaker.kt:166-167 writer.writeMetadata(name = pdf.displayName) puts the AO-revealing sheet name into plaintext MBTiles metadata; the stored PDF is import-<sha>.pdf and the name otherwise lives only in the sealed session.
F3 minor both. Estimate sample renders run without the crash guard: iOS PDFBakeController.swift:305-346 via requestBake (PDFRenderService lane guard `.bake: break`), arm only in start() (:400); Android PdfBakeManager.kt:169-227 before runBake arms BAKE (:258).
F4 minor ios. PDFTileGeometry.swift PDFFootprint.tiles(z:limit:) edge loop ~380-392 walks the whole bbox per segment, limit checked after each segment; non-cancellable global queue in prepare (PDFBakeController.swift:261-288).
F5 minor android tests. Same as D3.
F6 info ios. offline_tiles/.partial now completeUntilFirstUserAuthentication, documented. No change.
F7 minor ios. PDFBakeController.swift:251, 524-525 `guard let key, contentKey == key` so Delete Map doesn't stop a bake when contentKey is nil (MapViewModel.swift:439-441).
