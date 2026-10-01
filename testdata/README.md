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
| `sync_protocol_v3.json` | v3 key/identity derivation, signed preimages, monotonic signed hello epochs, ordering, replay rules, relay/client ceilings, and (`relayLimits`) every relay limit, rate-window rule, close code, nack code and retention rule clients must pace and react to |
| `sync_client_behaviour.json` | Unit Sync client behaviour both apps must share (`plans/04-sync-client-contract.md`): outbound pacing and in-flight window, retransmit timers, receive budgets, watchdogs, full-jitter backoff, close-code / HTTP-status / op-nack action tables, snapshot skip vs reject classes, hello-epoch recovery, chat fence pruning and history byte cap, presence suppression and counter persistence, lifecycle and background rules, new UX strings, and scenario vectors |
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

Both test suites load these same files and assert against them:

- iOS - [`ios/TacticalMapsTests/SharedVectorsTests.swift`](../ios/TacticalMapsTests/SharedVectorsTests.swift) (walks up from `#filePath`)
- Android - [`android/app/src/test/java/com/tacmap/SharedVectorsTest.kt`](../android/app/src/test/java/com/tacmap/SharedVectorsTest.kt) (walks up from `user.dir`)
- Relay - [`sync/test/relay.test.ts`](../sync/test/relay.test.ts) imports the v3
  fixture directly and validates the chat wire contract.
  [`sync/test/contract.test.ts`](../sync/test/contract.test.ts) fails if
  `relayLimits.values` and the relay's `sync/src/limits.ts` differ, or if the
  relay can send a close code the fixture doesn't document.

`sync_protocol_v3.json` vectors are regenerated from their inputs by
[`sync/scripts/gen_v3_fixtures.mjs`](../sync/scripts/gen_v3_fixtures.mjs)
(`npm run fixtures:check` in `sync/`), a third, node-only implementation that
must reproduce every derivable value byte for byte.

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

## Unit Sync client behaviour contract

`sync_client_behaviour.json` is the machine-readable half of
[`plans/04-sync-client-contract.md`](../plans/04-sync-client-contract.md). It
holds client policy, not wire format, so the relay does not load it.

- `relayLimitsDependencies` copies the relay limits the client numbers are
  derived from. Each platform's fixture test must check they still equal
  `sync_protocol_v3.json` `relayLimits`, and that every entry in `invariants`
  holds (for example the pacer buckets never exceed `clientPacing`).
- The action tables (`closeCodeActions`, `httpStatusActions`, `opNackActions`,
  `localCloseActions`) must have a row for every code the relay fixture lists.
- `scenarios` are deterministic vectors for the named policy units
  (`SyncBackoffPolicy`, `SyncOutboundPacer`, `SyncNackPolicy`, ...). Times are
  milliseconds from the scenario start; pacer send times allow 1 ms tolerance.
- The file is generated by
  [`tools/gen_sync_client_behaviour.py`](tools/gen_sync_client_behaviour.py),
  which simulates the pacer and budgets to compute the scenario values and
  asserts the invariants against the live `relayLimits`. Change constants there
  and regenerate (`--check` fails if the JSON is stale); don't nudge vectors
  until a test passes.
