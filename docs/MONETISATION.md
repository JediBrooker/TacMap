# Monetisation — 3-day free trial → one-time $5 unlock

TacticalMaps ships **free to download**. Every install gets a **3-day free
trial** of the full app; after that a one-time, **non-consumable** in-app
purchase (~A$5 / US$4.99) permanently unlocks it. There is **no subscription**.

This replaces the earlier "paid up-front" plan — a free download gives far
better discovery, and a paid-up-front app can't offer a trial on either store.

## How it works

On first launch the app stamps a timestamp and runs in full for 3 days. When
the trial lapses (and the unlock hasn't been bought) a full-screen **paywall**
gates the app with **Unlock Full Version · {price}** and **Restore purchase**.
The entitlement is `purchased OR trial-active`, re-checked on every
foreground/launch.

- The **trial** is client-side (a timestamp). It is cleared on uninstall, so a
  reinstall restarts the trial. That's an accepted trade-off for a low-price
  unlock; a tamper-proof trial would need a server check tied to the account.
- The **purchase** is restored from the store account, so it survives reinstall
  and new devices once the user signs in (StoreKit `currentEntitlements` /
  Play `queryPurchases`).

## Code

| | iOS | Android |
| --- | --- | --- |
| Trial clock | `ios/TacticalMaps/Billing/TrialManager.swift` | `…/billing/TrialManager.kt` |
| Store wrapper | `Billing/StoreManager.swift` (StoreKit 2) | `…/billing/BillingManager.kt` (Play Billing 7) |
| Paywall UI | `Billing/PaywallView.swift` | `…/billing/PaywallScreen.kt` |
| Gate | `App/TacticalMapsApp.swift` (`RootGate`) | `app/MainActivity.kt` |
| Product ID | `com.tacticalmaps.app.unlock` (non-consumable) | `unlock_full` (one-time / managed) |

Trial length is `TrialManager.trialDays = 3` on both platforms — keep them in sync.

## Store-side setup you must do (the code is done; these are console steps)

### Both stores
- A **working payments setup is mandatory for IAP**, exactly as for a paid app:
  Apple's **Paid Applications agreement** + banking/tax, and Google's
  **merchant account**. (The Google account whose payments profile was deleted
  still can't take IAP money — this ships from the new developer account.)
- Set the price to the ~$5 tier. The UI shows the **store's** localized price,
  so nothing is hard-coded.

### Apple — App Store Connect
1. **Features → In-App Purchases → +**, type **Non-Consumable**.
2. Product ID **`com.tacticalmaps.app.unlock`**, set price (Tier ~5 / A$7.99 or
   US$4.99), add a display name + description, submit it **with** the app build.
3. Privacy: still **no data collected** — IAP purchase data is handled by Apple.

### Google — Play Console
1. **Monetise → Products → In-app products → Create product**.
2. Product ID **`unlock_full`**, set price, activate it.
3. Add **license testers** (Setup → License testing) to test purchases without
   being charged.

## Testing

- **iOS (local, no ASC needed):** the scheme references
  `ios/TacticalMaps.storekit`, so running from Xcode resolves the product and a
  sandbox purchase. To see the paywall, expire the trial: delete the app, or set
  `trialFirstLaunch` to an old date in the app container's
  `Library/Preferences/com.tacticalmaps.app.plist`. (Launching via `simctl`
  directly does **not** inject the StoreKit config — the price shows
  "Loading price…". Use the Xcode scheme for the price/purchase path.)
- **Android:** upload to **Internal testing**, install as a license tester, and
  the BillingClient resolves `unlock_full` and a test purchase.

## Verified

- Both platforms compile (`xcodebuild` BUILD SUCCEEDED; `assembleDebug` OK).
- iOS gate confirmed on simulator: fresh install → full app (trial active);
  expired trial → paywall.
