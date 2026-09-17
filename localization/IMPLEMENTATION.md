# Full localisation implementation

The implementation is split into the six milestones in the [full localisation plan](PLAN.md). English and German are the current release languages. Milestones 1 and 2 establish the measured backlog, regression guard and extensible catalogue; it does not certify every screen as visually or linguistically reviewed.

## Milestone 1: source inventory and regression guard

- Registered 285 app-owned Swift/Kotlin components in `source-inventory.json`, grouped by feature and platform. Generated resource references, typed accessors and language choices are covered by byte-for-byte generation checks instead.
- Added a dependency-free, token-aware source guard to the existing localisation check on both CI workflows. It handles nested comments, raw/multiline strings, interpolation, named arguments, common display APIs and the app's positional display helpers.
- Added exact, occurrence-limited exceptions for official names, standards, example coordinates, technical labels that share display-like parameter names. Exceptions contain reasons. New or stale exceptions fail CI.
- Fixed the Android reinforcement, task-colour, selected-symbol-category and compass-reference labels that cached translations in enum initialisers. Display names are resolved using the current language. The “None” reinforcement option now uses the catalogue too.
- Moved remaining OK acknowledgement buttons through the shared catalogue on iOS and Android.
- Extended the Android language-switch instrumentation test to check cached-enum regressions. CI now runs the localisation instrumentation class alongside the existing PDF import test.

Generate a readable, per-component report:

```sh
python3 scripts/localization_coverage_report.py > localisation-coverage.md
```

Validate after any change:

```sh
python3 -m unittest discover -s scripts -p 'test_localization*.py'
python3 scripts/check_localizations.py
```

Raw findings for review, without modifying the exception policy:

```sh
python3 scripts/localization_audit.py --report
```

Do not bulk-accept findings to make the check green. Route app-owned prose through `L10n`; add an exception only after identifying why that exact value should remain literal. Preserve protocol keys, associated-data labels and coordinates. Display-like names such as `label` also occur in cryptography/persistence code, so those false positives need narrowly scoped explanations.

Source registration and runtime/linguistic review are separate. `manual-review-needed` explicitly records work still to do; a clean scan does not automatically change that status. Valid review states are `manual-review-needed`, `reviewed-no-display-text`, and `language-and-device-reviewed`. The last two require an evidence note.

## Milestone 2: extensible catalogue foundation

- One locale manifest generates both language pickers, resource folders and platform locale declarations. English and German remain the only enabled languages.
- All 1,659 messages have stable resource IDs alongside legacy English aliases. Six message families use generated named accessors with translator context; all 21 plural families have integer-count accessors. Display arguments are currently typed strings.
- Plurals use named native categories rather than a fixed two-element array. A synthetic six-category language test proves generator extensibility without shipping that test language.
- Review fingerprints bind German wording to its English source, context and parameters. Imported wording is explicitly marked `baseline`; it is not a new linguistic approval. Source or translation changes require an explicit review.
- Regression hashes preserve the pre-migration English/German display text. Native tests exercise language switching, interpolation, generated choices and counts.

The compatibility bridge deliberately remains. Migrating every legacy call and reviewing every screen belongs to the next milestones; catalogue counts do not imply completion of that work.

## Milestone 3: first formatting batch

Shared presentation formatters now cover measurements, weather numbers, elevations and chat times on both platforms. Explicit app language keeps the device region; time formatting keeps the device time zone. Units and measurement thresholds stay the same. Tests cover German/English decimals, Swiss German, mixed language/region, thresholds, time zones and preserving an active iOS measurement while changing language. See [FORMATTING.md](FORMATTING.md) for policy and the remaining migration/lifecycle work. The next editor batch migrates symbol/drawing sizes and accessibility values, and adds a strict locale-aware elevation parser with visible input guidance. A further batch covers native percentage formatting, grid-magnetic decimal angles and drawing creation dates, and replaces four fragmented Android settings explanations with complete messages. Generated deferred messages now retain IDs and arguments for ten iOS persistence errors and four Android recovery/discard errors, translating them at display time. Android permission/service/reducer messages remain a separate migration. Phase 3 is still in progress.

## Next milestones and measured backlog

| Milestone | Work still required | Completion evidence |
| --- | --- | --- |
| 3. Language/formatting behaviour | Shared presentation formatters; regional and decimal-input policy; Android system/in-app language integration with lifecycle protection; late-resolved errors | Mixed-locale tests and unchanged sync/export fixtures; recording/drafts survive changes |
| 4. Complete app-wide English/German review | Work through source/area inventory, replace concatenated sentences, review terminology and dynamic keys, inspect cached messages, default-name semantics, accessibility and notifications | Both platform screenshots/device evidence and reviewed wording for each surface |
| 5. Release verification | Expanded-text pseudolocale, regional/unsupported locale matrix, small-phone/tablet layouts, large fonts, screen readers, supported OS versions and signed resource delivery | Device test results and documented exceptions, not just catalogue counts |
| 6. Store experience | Listings, screenshots, release notes, purchase metadata, support/help/policy content | Reviewed metadata and screenshots matching the tested builds |

Priority follow-ups from source inspection:

1. Migrate remaining legacy lookups by meaning, adding stable semantic IDs, translator contexts and typed accessors. Keep shared wording separate when its meaning differs.
2. Android `OpsecSettingsDialog.kt` and `MapScreen.kt` still assemble some complete sentences from translated fragments; migrate by message meaning, preserving placeholders.
3. iOS `MeasureSession.swift`, `WeatherSheet.swift`, `WaypointEditSheet.swift` and Android equivalents need the presentation-format audit. Do not apply display locales to sync signatures or interchange coordinates.
4. Source strings used through variables or custom wrappers remain manual review items. Dynamic lookup counts in the report identify starting points, not missing-translation counts.
5. Errors/messages that were translated before storage may remain in the previous language until regenerated; migrate them to message IDs plus parameters.

Changes in this milestone do not change the release version, enable extra languages, restore the relay editor or publish another store build.
