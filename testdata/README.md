# Shared cross-platform test vectors

These JSON fixtures are the single source of truth for behaviour that is
implemented **independently on iOS (Swift) and Android (Kotlin)** and must not
diverge:

| File | Pins |
|---|---|
| `affine_fits.json` | fiduciary → affine-transform recovery (the calibration solve) |
| `mgrs_samples.json` | coordinate ↔ MGRS string formatting + crash-safe parsing |
| `geojson_geometry.json` | GeoJSON geometry shape (`[lon, lat]`, ring closure) |
| `android_waypoint_production.geojson` | byte-for-byte Android production marker export consumed by iOS importer/hash tests |
| `ios_waypoint_production.geojson` | byte-for-byte iOS production waypoint export consumed by Android importer tests |
| `sync_protocol_v3.json` | v3 key/identity derivation, signed preimages, monotonic signed hello epochs, ordering, replay rules, and relay/client ceilings |
| `tacmap_chat_v1.json` | chat session-key IDs and signatures, room/direct headers and AAD, X25519/HKDF keys, deterministic seals, and outer Ed25519 signatures |
| `presence_accuracy_v3.json` | optional signed accuracy bytes, location-quality thresholds, implausible-jump rejection, and cross-platform recovery behaviour |
| `local_store_name_v1.json` | DEK-bound opaque per-room chat/replay filenames shared by iOS and Android |
| `malicious_frames.json` | malformed and adversarial Sync frames that both clients must reject without crashing or mutating state |
| `import_identity.json` | one global waypoint/drawing ID namespace, deterministic reminting, unchanged layer references, output ordering, and idempotent partial-import retry |
| `drawing_style.json` | density-independent stroke widths, Android px conversion, and independent polygon stroke/fill semantics |
| `search_contract.json` | offline coordinate and mission-object search, deterministic ranking, selection behaviour, and opt-in Places states |
| `symbol_edit_contract.json` | selected-symbol field order, normalization, kind resets, one-commit mutation semantics, and accessibility targets |
| `symbol_list_order.json` | Symbology list sort orders and groups, natural name comparison, and haversine distances from the crosshair |
| `range_rings.json` | range-ring point count, bearings and WGS84 geodesic positions (GeographicLib reference), radii and limits, and affiliation stroke colours |
| `sun_moon_times.json` | offline twilight, sunrise/sunset, moonrise/moonset and moon illumination for local-day windows, including polar day/night and a 25-hour DST day |
| `elevation_profile.json` | elevation-profile sampling along a path, 4-decimal Open-Meteo request batches, climb/descent stats, and line of sight with curvature, refraction and dead ground |
| `kml_export.json` | exact KML export text: layer folders and hidden-layer visibility, style order and `aabbggrr` colours, symbol MGRS data, drawing geometry, escaping and coordinate rounding |
| `mgrs_grid.json` | MGRS grid overlay: levels and dp-based level of detail, line and 100 km square label text, on-screen label placement, densification (0.25 px sagitta) and zone/band clipping incl. Norway/Svalbard (PROJ reference, generator `scripts/gen_mgrs_grid_fixture.py`) |

Both test suites load these same files and assert against them:

- iOS - [`ios/TacticalMapsTests/SharedVectorsTests.swift`](../ios/TacticalMapsTests/SharedVectorsTests.swift) (walks up from `#filePath`)
- Android - [`android/app/src/test/java/com/tacmap/SharedVectorsTest.kt`](../android/app/src/test/java/com/tacmap/SharedVectorsTest.kt) (walks up from `user.dir`)
- Relay - [`sync/test/relay.test.ts`](../sync/test/relay.test.ts) imports the v3
  fixture directly and validates the chat wire contract; copied relay-only
  constants are not authoritative.

If you change an algorithm on one platform and a shared test fails, the two
platforms have drifted - fix the implementation, don't just edit the vector.
The MGRS strings were generated from the verified NGA-backed formatters; the
affine fiduciary coordinates are computed directly from each case's `transform`.

## Import identity contract

`import_identity.json` is a resolver fixture, not an exported mission document.
Its deliberately small records let Kotlin and Swift decode the same structure
without depending on either platform's complete waypoint/drawing model.

Both implementations must:

- treat waypoint and drawing IDs as one occupied UUID namespace, including
  collisions across the two object types;
- process incoming objects in fixture order, preserving the first valid,
  unoccupied UUID and consuming `injectedRemintIds` in order for an existing
  collision, a later incoming duplicate, a missing ID, or a non-UUID ID;
- leave layer records and every `layerId` reference byte-for-byte unchanged.
  The fixture intentionally gives an incoming layer the same UUID value as an
  existing waypoint to prove that layers are outside the object-ID namespace;
- preserve object count and relative input order while also producing the pinned
  per-type order and counts under `expected`;
- retain the batch's `caseKey -> resolvedId` mapping across a retry. Reapplying
  an already resolved/committed object is an idempotent upsert or skip. A retry
  after the waypoint half commits therefore adds only the three drawings,
  consumes no new remint IDs, and converges on the same ten-object final state;
- compare fixture ID strings as parsed UUID values. Lowercase spelling is for
  readability and does not require a platform to preserve UUID string casing.

`caseKey` and `batchKey` are fixture/batch metadata only. They must not be copied
into TacMap object IDs, layer IDs, or synced object payloads.

## MGRS grid overlay contract

`mgrs_grid.json` pins one grid policy for iOS (`MGRSGridRenderer` +
`MGRSGridOverlayView`) and Android (`MgrsGridRenderer` + `MgrsGridCanvas`).
Expected values come from PROJ (pyproj `etmerc` UTM) and the AA-scheme MGRS
letter math, generated by `scripts/gen_mgrs_grid_fixture.py`. The letters, cell
spans, anchors and samples were cross-checked against the real NGA
`mgrs-2.1.3` jar and an independent Krüger series. Regenerate rather than
hand-edit.

Rules (the `policy` block holds the numbers):

- **Levels** are 100 km, 10 km and 1 km. There is no 100 m level and no GZD
  line level.
- **Level of detail** is set in dp/pt:
  `spacingDp = metres * 256 * 2^zoom / (2π * 6378137 * cos(lat))`. `zoom` is
  the continuous camera zoom (256 dp tiles, Web Mercator, not rounded) and
  `lat` is the camera-centre latitude. A level is drawn at `spacingDp >= 32`
  and labelled at `spacingDp >= 40`. Nothing is drawn below 32 dp, including
  100 km lines when zoomed far out.
- **Line ownership.** A line `(zone, hemisphere, axis, value)` is emitted once,
  by the coarsest drawn level whose spacing divides its value, so
  N=4200000 is always a 100 km line. That owner level sets the stroke width
  and font.
- **Geometry.** Lines are built per GZD cell in that zone's UTM, and the
  hemisphere comes from the band.
  - Each line is densified so that its chord sagitta is at most 0.25 px
    (px = dp times the screen scale) at the zoom it is drawn at.
  - Vertices come from an accurate TM (within 5 mm of PROJ). The vendored
    NGA UTM is 1 to 4 cm off (it also rounds to 1e-7 degrees), which is more
    than the z19 case allows.
  - Pieces are clipped to the cell's longitude span (with the Norway and
    Svalbard exceptions) and to its band latitude span, and only within
    -80..84.
  - Densify each line once per zone and hemisphere, then split it at the
    cell edges, so neighbouring pieces meet at exactly the same point.
  - Cell edges are half-open: a piece that lies exactly on a cell's north or
    east edge belongs to the neighbouring cell. So the equator is drawn only
    as the northern N=0 line, and zone 31's central meridian (3E) exists only
    in 31U.
- **Line label text.** `L` is the finest labelled level among 10 km and 1 km.
  Every drawn line whose value is a multiple of L's spacing is labelled
  `%02d` of `(value mod 100000) / 1000`, for example N 4183000 gives `83`,
  N 4170000 gives `70` and E 400000 gives `00`. The rule is the same in both
  hemispheres; southern northings include the 10,000,000 false northing. When
  only 100 km is labelled there are no line labels.
- **Square labels.** When 100 km is labelled, each 100 km square in each GZD
  cell gets its two letters. The column letter comes from set `zone mod 3`.
  The row letter is `floor(N / 100000) mod 20`, plus 5 for even zones.
- **Label region.** Labels are placed in screen space every frame against the
  visible viewport, not the coverage square. The label region is the
  viewport inset by 16 dp, intersected with the line's zone span pulled in by
  16 dp at each zone edge (band edges are not pulled in).
- **Line label placement.** An easting label is centred on the north end of
  its line's visible part inside the label region, rotated -90 degrees as
  today. A northing label is centred on the west end.
- **Square label placement.** `P` is the square intersected with its cell's
  label region.
  - Skip the label if P's screen bounding box is under 40 dp in either
    direction.
  - Otherwise take `t`, the vertex of P with the smallest `x + y`. The anchor
    is `t + (32, 32)` if that point is inside P. If it is not, move from `t`
    toward P's area centroid by 32√2 dp, or stop at the centroid if it is
    closer.
- **Declutter.** Place square labels first, then line labels ordered by owner
  level from coarse to fine: eastings from west to east, then northings from
  north to south. Drop any label whose text box (plus 2 dp) overlaps a label
  already placed.
- **Cache.** Rebuild when any of these happens:
  - the drawn or labelled set changes,
  - `|zoom - buildZoom| >= 0.5`,
  - the centre moves 64 dp or more,
  - the viewport size changes.

  Densify for `buildZoom + 0.5`. Build a coverage square of half-side
  `(hypot(w, h) / 2 + 64) * 2^0.5 + 32` dp at `buildZoom`, so panning and
  zooming out never expose an area with no geometry before the next rebuild.

What each section asks of a platform test:

- `lod`: spacing within 1e-3 dp, and the exact `drawn` and `labelled` sets.
- `lineLabels` / `squareLabels`: the exact text (`null` means no label).
- `geometry`:
  - Build for `bounds` at `zoom` and `pxPerDp`, with LOD from `lodLat`, and
    take the emitted pieces for the `line` key.
  - Each piece must have the owner level `line.level`.
  - Every sample must lie within `tolMetres` of the emitted polyline. Measure
    in Web Mercator metres times `cos(sample lat)`.
  - Every emitted vertex inside the sampled extent must lie within
    `tolMetres` of the sample polyline.
  - `singleChordErrorMetres` is what today's one-chord-per-cell drawing
    misses by. It is informational.
- `clip`: build for `bounds`, then check that:
  - every emitted point lies inside its own cell's lon/lat span within
    `clipEpsilonDegrees` (spans come from `policy`);
  - for each `(zone, hemisphere, axis, value)`, pieces do not overlap by more
    than 1e-9 degrees in latitude for eastings or longitude for northings;
  - every `expectedPieces` entry is present with its `level` and reaches
    `from` and `to` within `fromTolMetres` and `toTolMetres`. The allowance is
    larger where a line crosses an edge at a shallow angle, because a linear
    clip is sagitta / sin(angle) off along that edge;
  - `absentKeys` never appear;
  - `keyBounds.latMax` holds.

  Extra pieces outside `bounds` are fine.
- `visibleLabels` (north-up camera):
  - Every produced label must match an expected one on kind, key and text,
    with its anchor within `anchorTolDp` and inside `insetRectDp`.
  - Every expected label with `mayDrop: false` must be produced.
  - `mayDrop` marks labels that might collide depending on font metrics, or
    that are borderline on a size or placement rule.
