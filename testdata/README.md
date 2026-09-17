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
