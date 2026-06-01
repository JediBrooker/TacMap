# App Store Submission Checklist

## Apple-side (one-off)

- [ ] Enrol in the **Apple Developer Program** ($99/yr) at <https://developer.apple.com/programs/>
- [ ] Reserve the app name in **App Store Connect**
- [ ] Generate App ID `com.tacticalmaps.app` in the Developer portal
- [ ] Create a **Distribution provisioning profile** (Xcode will auto-handle this if signed in)
- [ ] **Privacy policy URL** — host `docs/PRIVACY_POLICY.md` somewhere public (GitHub Pages, Notion, your own domain). Fill in the date and contact email first.
- [ ] **Marketing screenshots** — 6.7" iPhone is mandatory (1290×2796). Use the simulator's screenshot tool. Need 3–10 screenshots.
- [ ] **App icon** — already generated via `scripts/generate_icon.swift`. Re-run if you tweak the design.
- [ ] **App preview video** (optional but recommended) — 15–30 seconds, screen recording from simulator.

## Code-side prep

- [x] App icon (`AppIcon.appiconset` populated from script)
- [x] Launch screen (`UILaunchScreen.UIColorName` → `LaunchBackground`)
- [x] Acknowledgements / credits screen (hamburger → About & Credits)
- [x] Holsworthy demo PDF removed from `samples/` (ADF copyright — don't bundle)
- [x] Privacy policy template in `docs/PRIVACY_POLICY.md`
- [x] `ITSAppUsesNonExemptEncryption: false` declared (we only use TLS — exempt)
- [ ] Bump `CFBundleShortVersionString` from `0.1.7` → `1.0.0` for the first submission
- [ ] Crash reporting (Sentry / Firebase Crashlytics / built-in only) — strongly recommended
- [ ] Test on a **real device** via TestFlight before submitting

## App Review Information (filled in App Store Connect)

- [ ] **Sign-in credentials** — N/A (no login)
- [ ] **Demo content** — the App ships without sample PDFs. Reviewers can test with any open GeoPDF (US Topo from USGS works: <https://www.usgs.gov/programs/national-geospatial-program/us-topo-maps-america>)
- [ ] **Notes**: explain (a) why we use "Always" location background mode (route tracking, planned), (b) what TacticalMaps is for (field navigation — hiking, search-and-rescue, military training, outdoor sports). Spell out that it is *not* itself a defence/weapons system.
- [ ] **Age rating** — 4+ (no objectionable content)

## Privacy Nutrition Labels (App Store Connect form)

Declare:

- **Data Not Collected**
  - We collect *no* personal data, no usage analytics, no advertising identifiers. Tick "No" for every category.
- **Third-party SDKs**
  - None in current build. NGA mgrs-ios is vendored open source, not an SDK in Apple's sense.

## Submission-day flow

1. In Xcode: **Product → Archive** (build target = Any iOS Device)
2. Organizer → **Distribute App** → **App Store Connect** → **Upload**
3. Wait ≈10–30 min for processing in App Store Connect
4. Add the build to your Version 1.0.0 record → fill the form (description, keywords, screenshots, privacy URL) → **Submit for Review**
5. Apple review typically returns in **1–3 days** for a first submission. Tactical/military naming may add a week.

## Known rejection risks for this app

- **Name** “TacticalMaps” may invite extra scrutiny under guideline 1.1.6
  (depictions of weapons / violence) or 4.7 (HTML5 game-loaders) by association.
  If rejected on naming grounds, candidate names: FieldGrid, GridNav, OrienteerPro,
  TerrainBrief, BushNav.
- **5.1.1 Data collection without privacy policy** — fix is to put the privacy
  policy URL into App Store Connect *before* submitting.
- **2.1 Crashes** — run the app through TestFlight on at least one real device
  and exercise: search by place name, search by partial grid (e.g. `1885`),
  import PDF, draw line/area/point, export GeoJSON, calibrate PDF.
