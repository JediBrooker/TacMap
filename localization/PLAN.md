# TacMap full localisation plan

Prepared 17 September 2026. Baseline: `d5032ec4f945895da903ee3ef4544196404e40ad`, version 2.0.1 (66), iOS and Android.

## Outcome

Make localisation part of normal feature development across the entire app. Complete and verify English/German first, then make additional languages a catalogue-and-review task rather than a screen-by-screen rewrite. All app-owned translations ship with the app and work offline.

Localisation still requires translated wording. Codex can draft and maintain that wording, with terminology review and automated checks. The user should not need to provide translations individually for each new screen. Adding more languages is a separate rollout decision; this plan does not choose or enable any beyond English and German.

This is an implementation plan, not a claim that the work below has already been completed.

## Verified starting point

| Existing capability | Current state |
| --- | --- |
| Shared catalogue | 1,658 entries: 1,064 used by iOS and 1,027 by Android; some are shared |
| Plurals | 21 families, currently represented as singular/other pairs |
| Resource generation | Shared JSON generates native iOS strings/plurals and Android XML/resource references |
| Language selection | English, Deutsch and Device language; saved choice with live app-label updates |
| Checks | Catalogue/native resource parity, known literal lookup coverage, placeholder checks and stale generated-file detection |
| Tests | Resource and plural tests on both platforms; an iOS screen test exercises language switching and relaunch persistence |
| Android live validation | Instrumentation tests exist; their localisation cases have not yet been run on a local Android device/emulator |
| Store content | Store listings, screenshots and store-managed product descriptions have not been localised by the app catalogue work |

The entry count is not a percentage of complete screen coverage. The current checker verifies calls already routed through `L10n`; it does not find every display string that bypasses that helper. The recently corrected chat recipient heading demonstrated this gap.

Other findings: generation is hard-coded to English/German; catalogue lookup is based on English source text; some messages concatenate translated fragments; and display number formatting is scattered outside the language resolver. For example, iOS `MeasureSession.swift`, `WeatherSheet.swift` and symbol-editor formatting need review. Protocol and export formatting also exists and must be distinguished from presentation formatting.

## Scope inventory

Create one inventory with a row for each screen or shared component on each platform. Track visible text, accessibility text, empty/loading/error states, formatting, screenshots and verification status.

| Area | Required coverage |
| --- | --- |
| Navigation and settings | Main menu, language picker, privacy/OPSEC, app lock, permissions explanations, about/credits |
| Map and navigation | HUD, coordinates, compass, elevation, no-map/offline states, basemap choices, attribution |
| Symbols and waypoints | Military/tactical/airsoft/SAR/POI names, editors, colours, lists, filters, validation and confirmations |
| Drawings and layers | Tools, geometry types, styles, measurements, layer actions, labels, deletion and persistence errors |
| Search and calibration | Place/coordinate search, results, empty/offline states, input validation, GeoPDF calibration |
| Imports and exports | PDF/MBTiles/GeoJSON/GPX flows, progress, errors, share-sheet labels, human-readable export descriptions |
| Tracking, Unit Sync and chat | Recording state, notifications, location consent, connection state, recipient selection, unread counts and failure recovery |
| Weather and terrain | Weather values, units, UAV guidance, heat-map controls, unavailable/stale data |
| Purchases and onboarding | Trial text, paywall, restore/redeem flows, entitlement states and store-supplied metadata |
| Accessibility and system integration | VoiceOver/TalkBack names, hints and values; permission usage descriptions; notification channels and actions |
| Distribution | App Store/Play listing text, release notes, screenshots, product metadata, support/help and linked policy content |

User-entered names, notes and chat messages remain verbatim. Map imagery and place names supplied by external providers are not translated by the app catalogue. Preserve attribution and licence wording as required. System permission-dialog buttons and store-controlled UI have platform-controlled language behaviour, which must be documented and tested separately.

## Phase 1 — Establish coverage and prevent new omissions

**Work:** inventory every area above and audit direct UI literals, accessibility strings, notifications, error producers, enums and cached labels. Classify deliberate literals such as product names, abbreviations, units and identifiers. Add source-aware checks for common SwiftUI/Compose display APIs, backed by an explicit, reviewed exception list. Use syntax-aware scanning where regular expressions cannot distinguish code, comments and interpolation reliably.

Give each inventory item a stable identifier and platform status. Connect it to source locations and representative screenshots. Review missing paths manually: automated scanning alone cannot establish complete coverage.

**Acceptance:** all app-owned surfaces are accounted for; every identified display literal is catalogued or explicitly exempted; a newly introduced hard-coded UI label fails CI. A permitted technical identifier passes. Language-neutral wire/storage literals are not rewritten.

## Phase 2 — Make the catalogue extensible

**Work:** preserve the shared source of truth and native platform resources. Introduce stable, descriptive message IDs, such as `settings.language.title`, with source English, translation, context, parameter types, review status and source revision. Separate messages with different meanings even when their English wording is identical.

Generate typed Swift/Kotlin accessors and migrate feature areas incrementally. Keep a temporary compatibility bridge for existing English-key lookups, with a tracked removal list. An English wording edit should mark its translations for review without changing the message's identity.

Replace the fixed language list with a supported-locale manifest. Represent plural categories explicitly and generate the categories required by each supported language. Do not assume every future language has only singular and plural forms. Replace sentence fragments with complete messages and reorderable named parameters. Format numbers before inserting them into messages using shared, tested presentation formatters.

Continue generating the existing iOS resource format initially. An eventual generated String Catalog is optional; there should not be two independently edited translation sources. Apple's String Catalog tools support contextual strings and variants, but changing formats is not a prerequisite for completing this plan. [Apple String Catalog documentation](https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog)

**Acceptance:** English/German output stays equivalent through migration; missing/incorrect parameter types and required plural forms fail checks; adding a test-only locale does not require generator code edits; existing sync/export fixtures remain unchanged.

## Phase 3 — Unify language selection and regional formatting

**Work:** define one language policy on each platform and migrate the saved build-66 preference without losing the user's choice. Explicit English/German overrides Device language; unsupported device languages fall back to English. Regional variants should resolve to a supported base language when no regional translation exists.

Use the selected language with the device's region/time zone for presentation where supported, and document the fallback when that combination has no regional data. Test mixed settings explicitly. Language selection must not silently change selected measurement units, coordinate formats or time zones.

Centralise displayed dates, times, relative durations, distances, areas, decimal values and percentages. Define accepted decimal input and ambiguity handling; never interpret an ambiguous comma-containing coordinate or number by guessing. Keep canonical ASCII numeric forms and ISO timestamps for protocol signatures, storage, URLs and interchange formats.

For Android, prototype supported per-app locale APIs so the in-app and operating-system choices agree. Native locale changes can affect the Activity lifecycle, so first prove that drafts, map position, imports, recording and sync survive. Migrate only after that test passes; otherwise retain the current resolver while addressing state restoration. [Android per-app language guidance](https://developer.android.com/guide/topics/resources/app-languages)

For iOS, retain the working in-app choice and centralise observation/locale propagation. Resolve app-owned errors from message IDs and arguments at display time, rather than retaining old translated text. Document platform-controlled dialogs instead of promising that a custom picker changes all system UI.

**Acceptance:** switching languages and selecting Device language work after relaunch and system-language changes; no mission, draft or recording state is lost; ordinary values use the agreed formatting policy; canonical sync/export bytes stay identical across locales.

## Phase 4 — Complete English/German throughout the app

**Work:** migrate and review the inventory in manageable feature batches: navigation/settings; map/search/calibration; symbols/drawings/layers; import/export/tracking; sync/chat/weather; billing/accessibility. Each batch includes both platforms and its success, failure, offline and empty states.

Maintain a shared glossary for tactical, mapping and safety terminology. Preserve recognised standards and abbreviations such as MGRS, UTM, APP-6 and OPSEC where appropriate, with translated explanations. Keep the existing informal German “du” consistently. Use AI for drafts; review terminology, meaning, button intent and grammatical context before marking translations ready.

Review generated default names separately from user names. New defaults follow the chosen language. Existing user-visible saved names must not be destructively renamed; dynamic translation of built-in names requires a distinct semantic ID and a migration that respects user edits.

**Acceptance:** every inventory item has completed English/German text and behaviour checks on both platforms, with no unexplained English fallback in German. Accessibility labels and notifications are included, not just visible screen titles.

## Phase 5 — Make verification a release requirement

| Check | Minimum cases |
| --- | --- |
| Locale resolution | English/German, de-DE/de-AT/de-CH, unsupported language, Device language, mixed app/device language |
| Persistence | Upgrade from build 66, relaunch, background/foreground, device-language change |
| State safety | Switch during draft editing, recording, import progress and an active sync session |
| Text and formatting | Counts 0/1/2/large values, negative/decimal values, dates/time zones, percentages, long names, emoji and placeholders |
| Layout/accessibility | Small phone and tablet, supported orientations, large text, VoiceOver/TalkBack, expanded pseudo-language |
| Compatibility | Minimum supported and current OS versions; Android before and after system per-app language support |
| Data integrity | Same fixture signed/exported/imported across locales, unchanged IDs and coordinates, user content preserved |
| Distribution | Install from signed release artifacts; verify language resources survive Android shrinking and delivery splits |

Use pseudolocalisation to expose untranslated literals and clipping. Add right-to-left layout testing as a readiness check before any RTL-language release, with separate review of geographic directions and non-mirroring map symbols. [Android pseudolocale testing](https://developer.android.com/guide/topics/resources/pseudolocales)

CI should reject missing/stale translations, invalid placeholders/plurals, new unapproved display literals, stale generation and a failed language-switch smoke test. Run focused tests on pull requests and the full device/layout matrix for releases. Add an Android emulator job for localisation instrumentation; retain the existing iOS live-switch/relaunch test.

**Acceptance:** both platform device tests run successfully, inventory coverage is complete, and no unexplained clipping or untranslated app-owned text remains. Translation completeness, linguistic review and device verification are reported separately.

## Phase 6 — Localise the store experience and release

Prepare English/German listing descriptions, relevant metadata, release notes, screenshots and purchase descriptions. Screenshots must show the actual translated build. Review screenshot overlays, support/help pages and linked policy text; preserve their meaning and use appropriate review for legal content. Check each store's current field limits and available localisation fields during implementation.

Store listing localisation is separate from binary localisation. App Store Connect and Google Play both provide their own localisation workflows. [App Store Connect](https://developer.apple.com/help/app-store-connect/manage-app-information/localize-app-information), [Google Play](https://support.google.com/googleplay/android-developer/answer/9844778)

Verify the signed builds through TestFlight and a Play internal test track before production distribution. Record source commit, supported locales, review status and test results. If a regression appears, ship a corrective build using the last verified resources; do not assume an uploaded store binary can be replaced in place.

**Acceptance:** the installed app, screenshots, purchase text and listing language agree, and the release report identifies any platform-owned exceptions.

## Ongoing development workflow

For each feature: add contextual message IDs, draft English/German wording, review glossary terms, regenerate resources, run checks and exercise the affected screen in both languages. Editing English marks dependent translations stale. Remove unused messages only after verifying references and compatibility bridges.

Additional languages use the same workflow, with a readiness checklist for plural rules, fonts, layout direction, terminology, store assets and actual-device checks. Only fully verified languages appear as supported choices. Translation automation uses source UI copy and synthetic examples; mission data and private user content are not inputs.

## Delivery sequence and completion definition

Deliver as six reviewable milestones corresponding to the phases above. Phase 1 establishes the measured backlog. Phase 2 enables incremental migrations; Phase 3 establishes formatting/state behaviour before completing Phase 4. Build the Phase 5 tests alongside each migration rather than postponing testing. Phase 6 follows verified app wording and layouts.

The first implementation milestone is the coverage inventory and CI guard. Estimate subsequent effort from that inventory, especially the number of concatenated messages, cached errors and lifecycle-sensitive screens; a reliable calendar estimate is not yet established.

The work is complete when all inventory surfaces pass on iOS and Android, English/German meet the review criteria, protocol/data behaviour is unchanged, store content is aligned, and a new feature cannot silently bypass localisation checks. No further languages or runtime translation service are required to meet that outcome.
