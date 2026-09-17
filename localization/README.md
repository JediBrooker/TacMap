# English and German localization

Settings, Privacy & OPSEC → Language offers English, Deutsch, and Device language
on both platforms. The choice is saved and updates app labels immediately without
restarting the map or recording session. Device language follows the operating
system, with English as the fallback. System permission dialogs use the operating
system's language. No network translation service is used.

## Editing copy

`catalog.json` is the source for app-owned UI copy on both platforms. Each entry
contains English, German, and the platforms where it is used. `{1}`, `{2}`, etc.
are positional arguments; preserve the same arguments in each translation.
`plurals.json` contains complete singular/plural phrases selected by the native
platform plural rules. German copy uses informal “du”.

Run from the repository root:

```sh
python3 scripts/generate_localizations.py
python3 scripts/check_localizations.py
```

Commit the catalog and generated resources together. Both platform CI workflows
check for stale output and missing or mismatched translations. The generated
Android resource index uses explicit resource references so release shrinking
retains translations. Do not edit generated files directly.

`L10n.text` resolves native resources for both UI components and text produced by
models, errors and services. Dynamic arguments are formatted after lookup, so
user-supplied names and error details are preserved verbatim. Android installs
the resolver at application startup; enum and symbol-catalog display labels are
computed on access to avoid caching the previous language. Pure JVM model tests
without an application context use English fallback text.

Only app-owned display text is localized. User names, notes, existing saved
layer names, coordinates, asset names, wire formats, room codes, signatures and
storage keys remain unchanged. Newly created default names use the current
language. German commas are accepted in the iOS elevation input; stored numbers
remain numeric. Platform/store-supplied purchase descriptions and system dialogs
are controlled by the operating system or store, rather than this catalog.

## Native tests

- iOS: `TacticalMapsTests/LocalizationTests.swift` checks bundled English/German
  resources, permission prompts, interpolation, plural forms and stable wire IDs.
- Android: `com.tacmap.localization.LocalizationTest` instrumentation tests check
  German regional variants, English fallback, interpolation and zero/one/many
  plural forms. Run with `./gradlew connectedDebugAndroidTest` on a test device.
- The host-side checker validates catalog coverage, generated files, placeholders
  and native resource parity without an SDK.

## Release verification

On iOS, generate the project with XcodeGen before building. Run the existing
native suites and the new localization tests. On Android, run unit tests, lint,
release compilation and the instrumentation localization tests.

On a small phone and a tablet, select German and check the main menu, drawing and
symbol editors, search, layers, calibration, imports/exports, track recording,
sync, privacy/app lock, weather and purchase screens. Repeat with larger text,
VoiceOver/TalkBack, and counts 0, 1 and 2. Switch between English and German and
confirm labels refresh while user-created names remain intact. Check an existing
saved mission and a GeoJSON round-trip between differently configured devices.

These changes are integrated with the 2.0.0/build 64 source at `1c023de`.
Store listing copy, screenshots and store-managed product metadata are not changed.
Device layout review and Android instrumentation tests remain release checks.
Signing material and machine-specific configuration remain ignored by Git.
