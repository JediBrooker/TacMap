# Localisation verification — 2.0.2 (67)

English and German are the only release languages. The shared catalogue currently has 1,720 entries, 21 plural families and two permission descriptions. Translation fingerprints and generated-resource checks pass. These numbers do not certify every possible screen state.

## Completed source and behaviour work

The source guard inventories 285 Swift/Kotlin components and 53 exact literal exceptions. The review has covered navigation/settings, map controls, coordinates and measurement, symbols and layers, search and calibration, imports/exports, track recording, Unit Sync, Chat, privacy/key protection, purchases and system integration. All existing catalogue wording has received an AI-assisted meaning/terminology review, including 48 recorded corrections to the German baseline. This is not native-speaker certification.

Deferred message IDs and arguments now cover retained purchase, sync/chat, map-selection, mutation, import, privacy and key-unlock errors. Nested app-owned errors and plural summaries resolve at display time. Their operation/retry identities and user-entered contents remain unchanged. Generated default-layer names resolve dynamically only for recognised built-in identities and unchanged built-in names; saved/custom names remain verbatim. Unit Sync has no user-editable relay URL. Both settings screens expose English, Deutsch and Device language.

A supplemental scan of prose outside the catalogue found diagnostic logs, SQL, GPX/XML, protocol assertions, exception details, official names and language-neutral technical text. These are distinct from user-facing action/recovery copy. Low-level diagnostic details remain verbatim when appended to a translated error; they are not promised to follow the app language. The guard is not arbitrary data-flow analysis. The per-file inventory retains conservative review statuses rather than marking every model as visually checked because a scan passed.

## Recorded tests

| Check | Evidence |
| --- | --- |
| Catalogue/schema/generation | 31 Python tests; 1,720 entries and all native outputs validate |
| iOS unit suite | 506 tests, one intentional skip, zero failures on iOS 27; repeated after nested error and menu changes |
| Android unit suites | 581 tests in each of the debug and release suites; one intentional skip per suite, no failures |
| Android live language regressions | 12 instrumentation tests pass on API 35 |
| Android native-locale experiment | API 35 Activity recreation observed for LocaleManager; in-app selection preserves the Activity; original preferences restored |
| Android recording notification | Live foreground service updated its existing notification to German while backgrounded; the same authorised recording generation remained active. A temporary recorder protected saved track data |
| iPhone layouts | English, German and maximum accessibility text captures; smaller iPhone 17e German captures reviewed; settings switch/relaunch test passes |
| Expanded text | Debug-only 50% padding test and captured iPhone screens pass; no synthetic language ships as a selectable release locale |
| iPad | German iPad mini and English/German 13-inch iPad screenshots captured and reviewed; first-run permission prompt follows the OS language |
| Website | Six security/CSP tests pass, including German help/privacy pages |
| Store metadata | English/German titles, subtitles, descriptions, release notes, keywords and purchase descriptions validate against current field limits |

Capture checks found and fixed German navigation-title clipping, large-text chat/weather compression, non-scrollable drawing controls, Android navigation-bar overlap, truncated large-text coordinates/status values, cramped German drawing-tool buttons, and the remaining recording “pt/pts” abbreviation. The import/export sheet now scrolls on Android. The screenshot tests include accessibility trees, but they do not replace a complete screen-reader usability session.

## Android system-language decision

The production in-app resolver remains in place as permitted by Phase 3. The native API experiment confirms that a locale change recreates MainActivity. SelectedSymbolEditorDialog still owns unsaved draft state with `remember(waypoint.id)`, so a migration would risk losing an edit. Native migration is explicitly deferred until all relevant drafts and session state have restoration coverage. The current in-app picker updates Compose and service notifications without Activity recreation. Device language uses platform resources; an explicit in-app choice takes precedence.

## Android App Bundle language delivery

Language splits are disabled (`android.bundle.language.enableSplit = false`) so English and German are both installed even on a device configured for only one language. No network download is needed when changing the in-app choice. ABI/density delivery remains managed by Play. This follows [Android’s guidance for in-app language pickers](https://developer.android.com/guide/app-bundle/configure-base). Release verification inspects the generated bundle configuration and resources.

## Store and platform boundaries

- App Store version 2.0.1 was IN_REVIEW when checked on 17 September. This update uses 2.0.2 (67); the existing review is not withdrawn or altered.
- Store field files are prepared in `docs/store/localizations`; submitting listing/product changes is separate from binary upload. Generated support/privacy pages must be deployed and verified before their URLs replace store URLs.
- The detailed technical threat model remains English and is labelled accordingly in the German overview. German help and the full existing privacy disclosure are prepared. No new legal compliance certification is implied.
- iOS system permission buttons and usage-description language follow platform language selection, not solely the custom in-app picker. Store checkout, keyboard, file picker and provider data similarly follow their owners' language rules.
- Real TestFlight and Play internal-track delivery, minimum iOS 16.3 hardware/runtime behaviour, real compass/GPS background behaviour and a complete VoiceOver/TalkBack walkthrough require their respective devices/accounts. Local simulator screenshots are not evidence of those checks.
- Android CI now includes minimum API 26 and API 36. Its results must be checked against the final pushed commit.

The plan's complete physical-device/store-delivery matrix is therefore not claimed complete. The source implementation, automated evidence, prepared store materials and remaining external checks are reported separately.

## Release handoff

App Store Connect accepted 2.0.2 (67), delivery `2cea5e08-6c6f-447a-8c04-6afefde4245b`, and reports processing state VALID. The signed Android AAB uses the existing upload certificate and embeds both release languages. Final file hashes and CI results are recorded alongside the delivered artifacts. Neither production review submission nor Play publication is performed by this handoff.
