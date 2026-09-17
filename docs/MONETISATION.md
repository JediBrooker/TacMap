# Monetisation — 3-day free trial → one-time unlock

TacticalMaps ships **free to download**. Every install gets a **3-day free
trial** of the full app; after that a one-time, **non-consumable** in-app
purchase permanently unlocks it. The stores supply the localised price; there
is **no subscription**.

This replaces the earlier "paid up-front" plan — a free download gives far
better discovery, and a paid-up-front app can't offer a trial on either store.

## How it works

On first launch the app stamps a timestamp and runs in full for 3 days. When
the trial lapses (and the unlock hasn't been bought) a full-screen **paywall**
gates the app with **Unlock Full Version · {price}** and **Restore purchase**.
The entitlement is `purchased OR trial-active`. A durable, previously verified
purchase grants access immediately while offline; store ownership is reconciled
on launch and on throttled foreground transitions. Product and localized-price
loading happens only while a paywall is visible.

- The **trial** is client-side (a timestamp). It is cleared on uninstall, so a
  reinstall restarts the trial. That's an accepted trade-off for a low-price
  unlock; a tamper-proof trial would need a server check tied to the account.
- The **purchase** is restored from the store account, so it survives reinstall
  and new devices once the user signs in (StoreKit `currentEntitlements` /
  Play `queryPurchases`).
- A store timeout, offline device, or other inconclusive response never expires
  a known-good purchase. Only a successful, authoritative store result that no
  longer contains the unlock clears it (for example after a refund/revocation).
  Refund enforcement can therefore be delayed while a device stays offline.
- TacMap performs this verification on-device; it has no purchase-validation
  server. See [ADR-003](security/ADR-003-dark-egress-and-entitlement.md) for the
  trust boundary, acknowledgement/finish ordering, and operational checks.

## Code

| | iOS | Android |
| --- | --- | --- |
| Trial clock | `ios/TacticalMaps/Billing/TrialManager.swift` | `…/billing/TrialManager.kt` |
| Store wrapper | `Billing/StoreManager.swift` (StoreKit 2) | `…/billing/BillingManager.kt` (Play Billing 9) |
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
- Select the intended price in each store. The UI shows the **store's**
  localised price, so no currency or amount is hard-coded in the app.

### Apple — App Store Connect
1. **Features → In-App Purchases → +**, type **Non-Consumable**.
2. Product ID **`com.tacticalmaps.app.unlock`**, select the intended price, add
   a display name + description, and submit it **with** the app build.
3. To distribute unlock codes, create a free **Offer Code** on that IAP. TacMap
   presents Apple's native redemption sheet and supports this from iOS 16.3.
   Sandbox codes can be created during development; production one-time-use or
   custom codes require the app to be **Ready for Distribution** and the IAP to
   be **Approved**. Use one-time-use codes for individual giveaways or a custom
   code for a campaign, and choose the intended customer eligibility in App
   Store Connect.
4. Privacy: StoreKit handles standard store account, transaction, device, and
   network metadata under Apple's policies. TacMap does not attach mission,
   map, Unit Sync, location, or coordinate data to entitlement or product calls.

### Google — Play Console
1. **Monetise → Products → In-app products → Create product**.
2. Product ID **`unlock_full`**, set price, activate it.
3. Add **license testers** (Setup → License testing) to test purchases without
   being charged.

Play Billing similarly handles standard store account, purchase-token, device,
and network metadata under Google's policies. TacMap sends no mission, map,
Unit Sync, location, or coordinate payload to Play Billing.

## Testing

- **iOS (local, no ASC needed):** a `ios/TacticalMaps.storekit` config exists,
  but **xcodegen does not persist a StoreKit reference into the scheme**, so you
  must attach it manually once: Xcode → **Edit Scheme → Run → Options → StoreKit
  Configuration → TacticalMaps.storekit**. Then running from Xcode resolves the
  product + sandbox purchase. Without it (e.g. `simctl launch` or a UI test) the
  price shows "Loading price…". To see the paywall, either tap **Menu → Unlock
  Full Version** during the trial, or expire the trial (delete the app, or set
  `trialFirstLaunch` to an old date in the container plist).
- **iOS offer codes:** create a sandbox offer code for
  `com.tacticalmaps.app.unlock` in App Store Connect and redeem it on a physical
  device signed into a Sandbox Apple Account. Confirm the native sheet dismisses
  and TacMap unlocks without requiring **Restore purchase**.
- **Android:** upload to **Internal testing**, install as a license tester, and
  the BillingClient resolves `unlock_full` and a test purchase.

## Verified

- Both platforms compile (`xcodebuild` BUILD SUCCEEDED; `assembleDebug` OK).
- iOS gate confirmed on simulator: fresh install → full app (trial active);
  expired trial → paywall.
