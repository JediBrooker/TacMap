
**Where both lenses agreed, the design adopts it unchanged on both platforms:**
- one render function serves both the live view and the bake;
- the PDF is shown as a normal raster tile source;
- the per-cell 3-corner affine uses a 0.25 px bound and depth 4;
- tiles are 256 logical units at `min(density, 3)`;
- a base raster serves the low zooms;
- parent and child fallback applies to every source;
- the pinch clamp is removed;
- the bake becomes an accelerator attached to the PDF instead of a replacement.

## Judge decisions and flaws fixed after checking the real code

1. **iOS tile view.** I chose CALayer compositing (perf lens) over keeping `draw(_:)` (robustness lens).
   - HEAD's `TileMapView.draw` (71-94) CPU-composites the whole 3x backing store on every camera change. The perf lens measured 12-17 ms per frame on an M4 Pro, which is about 22-31 ms on an A15, before fallback overdraw is added.
   - A grep found no `drawHierarchy` or `render(in:)` users, so removing the backing store is safe.
   - The risky parts sit behind a pure planner plus snapshot tests: contentsRect orientation, seams and heading.

2. **detailZoom definition.** The two iOS lenses disagreed: 1 dp = 1/4 pt versus 1 px = 1/4 pt.
   - The px form depends on device density, so the same sheet would get a different maxZoom and different bake tile counts on an iPad, an iPhone and each Android density.
   - I chose the density-independent form, restated in Web Mercator metres per point. That removes the cos φ ambiguity and makes the value exactly what tiles need, so it can be pinned in the fixture.
   - USGS comes out at 15.83 → 16, which matches both perf lenses.

3. **Base-raster threshold.** The lenses proposed 1.0 (iOS), 1.1 (Android perf) and 1.5 (Android robust). I unified on 1.0 plus a staged path.
   - Staged means one page draw per job, then a warp. It was taken from iOS robust and iOS perf's "local raster".
   - This keeps every zoom above baseMaxZoom crisp while bounding the cost to one PDF call per job, where per-cell vector draws would cost 4-16 calls.
   - The Android lenses' per-cell `page.render` would issue 16 pdfium calls per z11 tile on small-scale sheets.

4. **Coalescing.** Both perf lenses showed that per-call floors dominate heavy sheets. Fixed aligned 2x2 blocks overdraw 1.5-2x on phone viewports, so I adopted iOS perf's greedy growth over pending visible tiles.
   - It is capped at 6 tiles and 3 per side, which bounds scratch memory.
   - It is deterministic and fixture-pinned, and gated by a shared EWMA "heavy" rule.
   - The bake reuses the same function with fixed aligned blocks.
   - I dropped Android perf's image-row tie-break as speculative; the benchmark gate decides.

5. **Bake threading.** iOS perf gave the bake its own queue and a third `CGPDFDocument` (+75 MB), and iOS robust used its own detached document. I instead run the bake as the lowest band on the same lanes/executor, with at most one bake job in flight and none while visible work is pending. This bounds memory, shares the document, and matches Android, where the global pdfium lock forces this model anyway.

6. **Reconcile deletes in-progress bakes.** I verified at HEAD that both `ManagedImportedMapFileLifecycle` implementations treat `*.mbtiles.partial` in `offline_tiles` as crash residue. A basemap switch mid-bake would therefore delete it.
   - Both platforms now bake into `pdf_bake_work/`, which is outside the reconcile roots.
   - The keep-set gains the bake file. iOS `keep` was a single URL at ActiveMapSelectionStore.swift:240-268; Android's is at 283-287.

7. **Crash guard.**
   - I rejected strike counting (iOS perf) in favour of the plan's first-render semantics: verified kinds per token (iOS robust), plus a persistent `suspect` so that "Not Now" does not let the next launch auto-render.
   - The guard is file-based with fsync on both platforms. UserDefaults is not a crash-durable ordering guarantee, and Android already has the fsync-and-atomic-move pattern.
   - Android perf skipped the base raster at import, which would leave a crash window after persistence. Instead, both platforms run the base-raster probe inside the import worker before the map is persisted. Android also abandons the replayed pending import, because MapScreen 595-613 would otherwise loop.
   - The guard file stores no display name, because filenames reveal the AO. That is why the import-interrupted copy is nameless.

8. **CropBox.**
   - The USGS fixture crop extends outside its page box (x = -12.2, y = 2098 against 1728 × 2088).
   - CoreGraphics is verified not to clip to the CropBox, and pdfium with a matrix may not either.
   - So both platforms clip explicitly to crop ∩ CropBox ∩ MediaBox.

9. **Android bitmap lifetime.** HEAD's LruCache recycles bitmaps on eviction. With fallback drawing of ancestors, a Compose display list could reference a recycled bitmap. Eviction now drops the reference instead of recycling (minSdk 26 uses NativeAllocationRegistry). `EMPTY` is never recycled.
   - Bake tiles stay software ARGB, because HARDWARE bitmaps cannot feed the encoder.

10. **Latent HEAD bugs fixed in passing.**
    - Wrapped-x tile placement across the antimeridian: layout now uses unwrapped columns on both platforms.
    - iOS `inFlight` could wedge on a synchronous completion.
    - An underzoom guard is needed once the clamp is removed. Without it, MBTiles with minZoom 10 at camera z2 would request thousands of tiles.

11. **Visibility.**
    - The iOS toggle is dead at HEAD (`pdfOverlayVisible` is only referenced in LayersSheet and LayerVisibility). Its key is renamed so a persisted `false` does not suddenly hide maps.
    - Android had no toggle and gains one with the same key and copy.
    - When hidden, the map shows the background only and no online substitution, so there is no surprise network use.

12. **Optional content and annotations.** Android robust's analysis says the USGS orthoimage strips sit in a default-OFF optional-content layer. Whether CoreGraphics honours that is unverified, so a new synthetic OCG-off fixture is a gate, with a documented PDFKit inverse-transform fallback. The annotation difference (pdfium draws annotations, CoreGraphics does not) is recorded as the one accepted parity gap.

13. **Android page frame.** I combined perf's fail-closed `validate()` with robust's runtime display-size probe. HEAD's rotation rejection (MapScreenHelpers.kt:433-437) is removed only once the frame handles /Rotate.

14. **Ownership for configuration changes.** Android's runtime and tile cache move into MapViewModel, and the bake manager moves to app scope (like `trackRecorder`). Rotation then keeps expensive heavy-sheet tiles, and the bake survives sheet dismissal, recreation and Activity finish. iOS mirrors this with a MapViewModel-owned `PDFMapRuntime` and a `PDFBakeController.shared` singleton.

15. **iOS bake file protection.** The `.partial` is created with `completeUntilFirstUserAuthentication` before SQLite opens it, and uses `journal_mode=OFF`. At HEAD the directory is created with `.complete` (PDFTiler.swift:44-45), which fails bakes when the phone locks during background tracking (D3-10).
    - The background-task expiry handler only ends the assertion, rather than cancelling (iOS robust). A suspended bake resumes; a killed one is reported at launch.

## Not included

- Prefetch rings.
- A sealed on-disk base-raster cache.
- A foreground service.
- Resumable bakes.
- Stale-generation fallback for WP4 live refits.

These are follow-ups. They keep each platform implementable in one session, in the phased order given, with shippable checkpoints.
