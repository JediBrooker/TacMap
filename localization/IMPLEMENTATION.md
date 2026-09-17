# Full localisation implementation

The implementation is split into the six milestones in the [full localisation plan](PLAN.md). English and German are the current release languages. This first change establishes the measured backlog and regression guard; it does not certify every screen as visually or linguistically reviewed.

## Milestone 1: source inventory and regression guard

- Registered 279 app-owned Swift/Kotlin components in `source-inventory.json`, grouped by feature and platform. Generated Kotlin resource references are covered by the existing resource checks instead.
- Added a dependency-free, token-aware source guard to the existing localisation check on both CI workflows. It handles nested comments, raw/multiline strings, interpolation, named arguments, common display APIs and the app's positional display helpers.
- Added exact, occurrence-limited exceptions for official names, standards, example coordinates, language autonyms and technical labels that share display-like parameter names. Exceptions contain reasons. New or stale exceptions fail CI.
- Fixed the Android reinforcement, task-colour, selected-symbol-category and compass-reference labels that cached translations in enum initialisers. Display names are resolved using the current language. The “None” reinforcement option now uses the catalogue too.
- Moved remaining OK acknowledgement buttons through the shared catalogue on iOS and Android.
- Extended the Android language-switch instrumentation test to check cached-enum regressions. CI now runs the localisation instrumentation class alongside the existing PDF import test.

Generate a readable, per-component report:

```sh
python3 scripts/localization_coverage_report.py > localisation-coverage.md
```

Validate after any change:

```sh
python3 -m unittest discover -s scripts -p test_localization_audit.py
python3 scripts/check_localizations.py
```

Raw findings for review, without modifying the exception policy:

```sh
python3 scripts/localization_audit.py --report
```

Do not bulk-accept findings to make the check green. Route app-owned prose through `L10n`; add an exception only after identifying why that exact value should remain literal. Preserve protocol keys, associated-data labels and coordinates. Display-like names such as `label` also occur in cryptography/persistence code, so those false positives need narrowly scoped explanations.

Source registration and runtime/linguistic review are separate. `manual-review-needed` explicitly records work still to do; a clean scan does not automatically change that status. Valid review states are `manual-review-needed`, `reviewed-no-display-text`, and `language-and-device-reviewed`. The last two require an evidence note.

## Next milestones and measured backlog

| Milestone | Work still required | Completion evidence |
| --- | --- | --- |
| 2. Extensible catalogue | Stable semantic message IDs, contexts, source revisions, typed parameters, supported-locale manifest, general plural categories, incremental compatibility bridge | Tests prove new locales do not require generator edits and existing text is preserved |
| 3. Language/formatting behaviour | Shared presentation formatters; regional and decimal-input policy; Android system/in-app language integration with lifecycle protection; late-resolved errors | Mixed-locale tests and unchanged sync/export fixtures; recording/drafts survive changes |
| 4. Complete app-wide English/German review | Work through source/area inventory, replace concatenated sentences, review terminology and dynamic keys, inspect cached messages, default-name semantics, accessibility and notifications | Both platform screenshots/device evidence and reviewed wording for each surface |
| 5. Release verification | Expanded-text pseudolocale, regional/unsupported locale matrix, small-phone/tablet layouts, large fonts, screen readers, supported OS versions and signed resource delivery | Device test results and documented exceptions, not just catalogue counts |
| 6. Store experience | Listings, screenshots, release notes, purchase metadata, support/help/policy content | Reviewed metadata and screenshots matching the tested builds |

Priority follow-ups from source inspection:

1. `scripts/generate_localizations.py` hard-codes `en/de` and `one/other`; fix before adding another language.
2. Android `OpsecSettingsDialog.kt` and `MapScreen.kt` still assemble some complete sentences from translated fragments; migrate by message meaning, preserving placeholders.
3. iOS `MeasureSession.swift`, `WeatherSheet.swift`, `WaypointEditSheet.swift` and Android equivalents need the presentation-format audit. Do not apply display locales to sync signatures or interchange coordinates.
4. Source strings used through variables or custom wrappers remain manual review items. Dynamic lookup counts in the report identify starting points, not missing-translation counts.
5. Errors/messages that were translated before storage may remain in the previous language until regenerated; migrate them to message IDs plus parameters.

Changes in this milestone do not change the release version, enable extra languages, restore the relay editor or publish another store build.
