# Custom layers and symbol packs

Both platforms already provide named custom layers. Open Layers, add a layer, then select it as the active layer before placing symbols. Existing symbol editors also let you move a placed marker to another layer.

To import artwork, open the marker symbol editor and choose **Import Symbol Pack…**. Select a TacMap symbol-pack JSON file. Choose **Custom Symbols**, select the artwork, enter the marker name and place it on the active layer. Both platforms provide offline search across pack, category and symbol names with image previews. Search ignores case and accents, and accepts multiple words (for example, `feuerwehr fuhr`). Artwork preserves its original colours and proportions. Your layer, pack and symbol names are user data and are not translated.

Imported packs work offline. Placed markers embed their artwork in the encrypted waypoint store and GeoJSON/Unit Sync payload. Recipients on a compatible version see the artwork without installing the pack. Importing a pack does not automatically share its library. Reimporting a pack with the same name replaces that library entry only after validation and durable storage; already placed markers keep their original artwork. File exports include artwork and names in plaintext, just like the rest of the exported mission objects.

## German emergency services example

`German-Emergency-Services.symbols.json` is a converted pack of 894 symbols from [Taktische-Zeichen v2.0.0](https://github.com/jonas-koeritz/Taktische-Zeichen/releases/tag/v2.0.0). The upstream README identifies release artwork as CC0-1.0, separately from the source-code licence. The pack retains attribution and category/name labels. The converter strips PNG metadata and keeps 256-pixel artwork. TacMap itself does not download the repository or contact its author.

## Make your own pack

Use `scripts/build_symbol_pack.py` with a folder of PNGs or a ZIP containing PNGs. The converter requires Python and Pillow. For the upstream German release, choose prefix `png/256/` to avoid including alternate resolutions and themes. ZIPs and SVG files are not directly imported by the mobile app; convert them into the passive JSON/PNG format first.

```sh
python3 scripts/build_symbol_pack.py ./my-pngs ./My-Symbols.json --name 'My organisation' --attribution 'Artwork by my organisation'
python3 scripts/build_symbol_pack.py release.zip ./German-Emergency-Services.symbols.json --name 'Deutsche BOS – Taktische Zeichen' --prefix 'png/256/' --attribution 'Taktische-Zeichen v2.0.0; release artwork CC0-1.0; https://github.com/jonas-koeritz/Taktische-Zeichen'
```

The JSON schema is `{"format":1,"name":"Pack name","attribution":"Credit/licence","symbols":[{"id":"lowercase SHA-256 of PNG bytes","name":"Symbol name","png":"base64 PNG bytes"}]}`. Required fields are shown. A pack contains 1–1,000 distinct artwork IDs; names are limited to 100 characters for packs and 160 for symbols, attribution to 2,000. Input is capped at 16 MiB, PNGs at 32 KiB and 256 × 256 pixels, and JSON nesting at eight levels. The installed library is capped at 32 packs / 32 MiB. Duplicate artwork is merged by the converter. New application versions are needed on both ends for custom artwork support.

## Threat-model alignment

See [THREAT_MODEL.md](THREAT_MODEL.md). Imports are untrusted data, never executable content. No external references are fetched, no web view is used, and no network entitlement or OPSEC gate changes. Imported libraries use the existing mission DEK and AES-GCM envelope with a separate authenticated store label. There is no plaintext migration path for this new store. Authentication, key-access or decoding failures preserve the existing file and prevent replacement. Custom artwork in placed markers follows the existing mission encryption and authenticated sharing pipeline. Content hashes do not establish author identity or operational accuracy. The system document provider may download a user-selected cloud file; source files and deliberate exports remain outside TacMap's encrypted store.
