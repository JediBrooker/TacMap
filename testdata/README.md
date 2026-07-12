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

Both test suites load these same files and assert against them:

- iOS — [`ios/TacticalMapsTests/SharedVectorsTests.swift`](../ios/TacticalMapsTests/SharedVectorsTests.swift) (walks up from `#filePath`)
- Android — [`android/app/src/test/java/com/tacticalmaps/SharedVectorsTest.kt`](../android/app/src/test/java/com/tacticalmaps/SharedVectorsTest.kt) (walks up from `user.dir`)
- Relay — [`sync/test/relay.test.ts`](../sync/test/relay.test.ts) imports the v3
  fixture directly; copied relay-only constants are not authoritative.

If you change an algorithm on one platform and a shared test fails, the two
platforms have drifted — fix the implementation, don't just edit the vector.
The MGRS strings were generated from the verified NGA-backed formatters; the
affine fiduciary coordinates are computed directly from each case's `transform`.
