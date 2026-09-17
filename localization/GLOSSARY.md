# English / German localisation conventions

German uses informal **du**, sentence case for ordinary labels and typographic German quotation marks. Preserve user names, room codes, filenames, coordinates, notes and chat text. Translate built-in layer labels by semantic identity only; never rewrite exported or persisted labels merely because the language changed.

| English | German | Context |
| --- | --- | --- |
| mission data / object | Einsatzdaten / Einsatzobjekt | App-owned operational content |
| waypoint | Wegpunkt | Stored point on the map |
| drawing / layer | Zeichnung / Ebene | Graphics and their groups |
| Friendly | Eigene Kräfte | Built-in affiliation/layer |
| Hostile / Unknown / Civilian | Feindlich / Unbekannt / Zivil | Built-in affiliations |
| callsign | Rufzeichen | Unit identity |
| fiduciary | Passpunkt | Map calibration control point |
| basemap | Grundkarte | Online or imported map beneath overlays |
| grid reference | Gitterkoordinate / Gitterreferenz | Coordinate, not a named user object |
| North Up / Heading Up | Norden oben / Blickrichtung oben | Map orientation |
| true / magnetic north | geografisch / magnetisch Nord | Keep compass T/M and geographic E/W conventions |
| recording / track | Aufzeichnung / Track | Recorded GPS path |
| Unit Sync | Einheitensynchronisierung / Unit Sync | Translated menu title; established feature name in explanations |
| relay | Relay / Relay-Server | Transport service, not a user-editable setting |
| restore purchase | Kauf wiederherstellen | Check entitlement, never imply a second purchase |
| pending / routed | ausstehend / weitergeleitet | Routed does not mean delivered or read |
| OPSEC, MGRS, UTM, APP-6, GPX, GeoJSON, MBTiles | unchanged | Standards and identifiers |

## Review evidence

On 17 September 2026, Codex read and compared all 1,701 catalogue entries, all 21 plural families and both permission descriptions for meaning, parameter preservation, German grammar, action names and terminology. This is an AI linguistic review, not a certification by a native tactical-domain translator. Compatibility-only fragments remain to avoid breaking older lookups; new messages should be complete sentences with named arguments and context.

The wording correction list in `testdata/localization/reviewed-wording-changes.json` records the exact 48 legacy German edits. The original resource hashes remain in place: the migration regression test accounts only for these explicit corrections. English source, wire values, coordinates, IDs and user content are not rebaselined.

Wording review is separate from source-flow and device/layout review. `source-inventory.json` retains outstanding device work; a catalogue review does not mark a screen tested.
