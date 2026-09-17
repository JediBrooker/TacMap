# Shared app localisation

Settings, Privacy & OPSEC → Language offers English, Deutsch, and Device language
on both platforms. The choice is saved and updates app labels immediately without
restarting the map or recording session. Device language follows the operating
system, with English as the fallback. System permission dialogs use the operating
system's language. No network translation service is used.

## Editing copy

`catalog.json` is the source for app-owned UI copy on both platforms. Each entry
contains English, German, and the platforms where it is used. `{1}`, `{2}`, etc.
are positional arguments; preserve the same arguments in each translation.
`plurals.json` contains complete phrases in named native plural categories, selected by platform plural rules. German copy uses informal “du”.

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

Store listing copy, screenshots and store-managed product metadata are separate work.
Signing material and machine-specific configuration remain ignored by Git.

## Stable messages and language configuration

`locales.json` is the supported-language manifest. It drives generated
`SupportedLanguage` choices, native resource folders, Android locale configuration
and the XcodeGen `Localizations.yml` include. Run XcodeGen after generation.
`permissions.json` owns localised iOS permission descriptions.

Each catalogue key is a stable resource ID; never regenerate it from revised
English wording. Migrated entries declare `accessor`, `context` and optional
`parameters` (named string arguments). Generated `Messages` accessors provide the
same API on both platforms. All plural families also expose integer-count
accessors. The six initial message families cover language settings, the chat
recipient heading, acknowledgement and import errors.

Legacy `L10n.text` calls continue through English aliases during incremental
migration. For new copy use a meaningful stable ID and typed accessor. When two
meanings share English wording, give them separate IDs and contexts; only one
may own the legacy alias, so set `legacy: false` on the new meaning. Stable IDs,
translation keys and formatted display text must never replace persisted or
protocol identifiers.

## Translation review

`reviews.json` records fingerprints of source wording/context/parameters and
translated wording. A change invalidates the corresponding review and fails CI.
`baseline` records wording imported from build 66, not a fresh linguistic review;
`reviewed` records an explicit review of the current text. Neither status certifies
layout, accessibility or device behaviour.

After reviewing a specific translation, record it explicitly:

```sh
python3 scripts/review_localizations.py --locale de --id import_failed
python3 scripts/generate_localizations.py
python3 scripts/check_localizations.py
python3 -m unittest discover -s scripts -p 'test_localization*.py'
```

Use `--kind plurals` or `--kind permissions` for those catalogues. Do not refresh
fingerprints just to silence a check. The tool records a decision; it cannot
judge translation quality.

To add a language, supply actual translations for every applicable message,
permission and plural category, then add its manifest entry (tag, autonym, native
folders, enum cases and required categories). Review the translations and record
their fingerprints, regenerate resources, run XcodeGen and both native test
suites, then complete the device/release review. No generator code edits are
needed. The synthetic Arabic fixture tests six plural categories; it does not
provide or enable Arabic translations. English remains the source/fallback.

The legacy-display hash fixture in `testdata/localization` protects wording during
this structural migration. An intentional future wording change requires a
separate reviewed fixture update, not automatic regeneration to make tests pass.

## Coverage and prevention

See [IMPLEMENTATION.md](IMPLEMENTATION.md) for the phased backlog and the source
inventory. `check_localizations.py` also rejects new unapproved display literals,
eager enum translations, missing source-inventory entries and stale exceptions.
The guard's regression fixtures run on both platform CI workflows. Android CI
now includes the localisation instrumentation tests in its emulator job.

Use `python3 scripts/localization_coverage_report.py` to generate the per-component
report. Counts describe scanned source, not completed linguistic/device review.

## Display formatting

Use `DisplayFormat` for migrated presentation values. It follows the app language
and device region/time zone while preserving units. See [FORMATTING.md](FORMATTING.md)
for the policy, data boundaries, covered screens and remaining migration work.
