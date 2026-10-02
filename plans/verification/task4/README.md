# Task 4 physical-pixel verification

This directory adds a separate reproducible raw-image metric. The inherited
`plans/audit/pdf-audit-scripts/grid_alignment.py` remains unchanged and is also
run against native captures. It needs multiple comparable red/gray lines;
single-line, no-ink and orange-grid views require explicit separate treatment.

Dependencies: Python 3, NumPy and Pillow. From this directory:

```
python test_physical_grid_metric.py --output selftest.json
python physical_grid_metric.py ON.png --crop 200,360,870,2080 \
  --off OFF.png --printed-color red --estimator edges \
  --uncertainty-floor 1.13 --output result.json
```

Coordinates and outputs are **physical screenshot pixels**. ON/OFF must be
native screenshots with the identical actual camera and source, obtained via
the real grid switch. Crop coordinates are retained. The metric never reads
app georeferencing or generates expected screenshots. It retains long-track
coverage, observed maxima, fitted extrema, angle/curve differences, rejected
samples and all measured scanline coordinates. Missing or obscured lines are
UNMEASURABLE. Every long printed track must have sufficient matched support.
No NaN is serialized or accepted.

`rawTargetStatus` preserves the observed maximum against the unchanged 1px
target. `status` also accounts for the explicitly supplied estimator envelope:
PASS requires the maximum plus that envelope <=1, FAIL means the interval is
wholly above 1, and BORDERLINE means uncertainty overlaps 1. Neither uncertainty
nor another estimator erases a raw failure. Retain both edge-midpoint and ink
centroid results when the broad raster stroke makes its center ambiguous.

Self-tests use independently specified analytic colored strokes and thin gray
lines: known positive/negative shifts, angle mismatches, actual coordinate
crops, no-ink negatives, paired red/orange views, and broad strokes blurred and
resampled before the overlay. The 120-case overzoom sweep per estimator reports
its actual worst error, rather than asserting a fictitious zero. The tested
32x envelope reaches 0.756px for edges and 1.120px for centroid; use >=1.13px for
that envelope. The default 0.35px applies only to the separately tested <=8x
edge envelope. Validation is not proof that every native PDF raster satisfies
the synthetic envelope.

Portable coverage/results accompany the final review report. Large immutable
native images, actual camera/source proof, absolute-host manifests and full
logs stay in scratch and are linked from `plans/resume/TASK4_DEVICE_REVIEW.md`.
Reported FAIL, BORDERLINE and UNMEASURABLE cells are retained. Completing
capture coverage does not establish universal <=1px numerical acceptance.

## Independent geometry and source artwork

`actual_cell_reference.py` reads compact `observed_android` or `observed_ios` records, binds
actual source/camera/hash/runtime nonce and uniquely matches every painted OWN
bitmap to its completed job. It compares native page-to-job transforms plus
actual physical paint bounds to independent construction/PROJ coordinates,
sampling425 viewport points and about160 printed-line points per fixture.
It does not estimate ink centers. These particular actual jobs have sampled
physical norm maxima0.534/0.761/0.044/0.488px (SF/Edge/Sydney/USGS). This is
specific observed-job evidence, not a universal bound over all allowed jobs.
The records explicitly retain renderer version 1 and its prior 0.25 bound;
Canberra iOS actual geometry exposed the 1.139918px defect that justified the
shared 0.0625 amendment and renderer version 2. Post-fix evidence is separate.

```
python actual_cell_reference.py --output actual_geometry.json
python test_actual_cell_reference.py --output actual_geometry_test.json
python actual_cell_reference.py --evidence-root observed_ios \
  --fixtures sf_iso edge_iso syd_iso cbr50k_iso --output ios_geometry.json
python test_actual_cell_reference.py --evidence-root observed_ios \
  --output ios_geometry_test.json
python usgs_local_artwork.py --pdf PATH_TO_PINNED_USGS.pdf \
  --zoom 20 --density 2.625 --output usgs_artwork.json
```

The geometry translation tests introduce known +3/-2 and -3/+2 physical-pixel
shifts into temporary copies of real observed transforms; they also reject
stale nonce/source, missing completed-job and omitted-frame evidence. The
source artwork tool requires PyMuPDF/pyproj and the repository's independent
fixture generator. It checks the pinned PDF SHA256 and intersects its actual
local orange vector segments. At the selected E550000/N4185000 intersection,
the source's0.175697m intrinsic registration error maps to+1.092946/-3.765114
physical pixels at the stated z20/density. The raw grid target remains FAIL;
correct registered geometry must not be distorted to hide source quantization.

The compact observed records retain only jobs that the actual frame painted;
the original full bounded snapshots and raw screenshots remain in scratch.
Recorded retention counters describe the original snapshot, not the filtered
portable subset. No screenshot or native expected overlay is generated here.


## Bounded log reconstruction

`audit_chunks.py` independently reconstructs complete iOS DEBUG geometry
observations using the camera-index process IDs. It validates chunk kind/ID,
index/total, duplicates, exact bytes, base64, UTF-8, finite JSON and hard caps.
Every missing/omitted/malformed record stays missing evidence; historical
truncated logs never supply successful geometry.

```
python test_audit_chunks.py
python audit_chunks.py --log native-stream.log --camera-index camera-index.json \
  --output independently-reconstructed-audits.json
```

The complete pre-amendment phase8 records independently match 68 owner records
and Canberra 15, with zero missing. Their PID/context/source/tile/timing bindings
are reviewed before their bounded painted geometry is included here.

## Actual native source-only raster comparison

`native_raster_reference.py` compares exported actual native vector/staged
bitmaps for the same actual cells against original source construction
coordinates through independent PROJ. It does not generate an expected
image. It excludes the independently located printed intersection's ±48
bitmap-pixel neighborhood and retains every admitted source scanline.
Pixel-array index i maps to bitmap coordinate i+0.5.

```
python test_native_raster_reference.py
python native_raster_reference.py --root NATIVE_OUTPUT_DIRECTORY \
  --fixture cbr50k_iso --physical-scale 32 --output native_source_reference.json
```

The source-only integral centroid uses the connected printed-contrast band
plus two pixels on each side. 270 analytic pixel-area tests with known shifts
and slopes validate its center independently. The half-contrast edge method
has up to0.08578 canonical-pixel bias (2.745 physical pixels at32×) in these
additional tests; edge maxima here are diagnostic, not rigorous confidence
intervals. Earlier screenshot interval labels with1.13px apply only to their
separately tested synthetic envelope, not universal native-raster certainty.
Raw observed failures are never removed. The clean pinned source cross-section
centroid supports the causal staged-vs-direct regression; it does not establish
whole-image ≤1px acceptance.

## Final revision and bake comparison

The35-input matrix remains renderer1 evidence. `observed_*_finalpath` is
actual repaired Canberra under renderer2/.0625/vectorMaxCells4. The same
geometry tool and known-translation tests reproduce sampled transform proof.
Final live/decoded JSON uses RGB MSE, PSNR=10log10(255²/MSE), null PSNR plus
explicit exact-match when MSE=0, original image hashes and physical crops.
Bounded±3px integer translation is diagnostic, never subpixel proof. iOS
genuine live baseline is later cold source after normal bake removal; actual
origins distinguish it from preserved final decoded screenshots. The rejected
mislabelled comparison stays diagnostic. Rotated/dense satisfy J3≥35dB;
realUSGS28.7145dB stays an additional quality failure outside that named gate.
Universal≤1px remains withheld.
