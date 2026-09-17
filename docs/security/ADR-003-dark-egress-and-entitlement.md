# ADR-003: Store Egress and Durable Entitlement

**Status**: Accepted

**Date**: 2026-08-16

**Addresses**: release 1.2.3 commerce, offline-access, refund/revocation, and store-egress contracts

## Summary

TacMap keeps a verified permanent-unlock decision on the device without a local
expiry, while observing transactions and refreshing ownership at app-lifetime
and foreground boundaries. Product and localized-price loading remains scoped
to a visible paywall.

This deliberately chooses timely store reconciliation over a guaranteed
network-dark app launch. A StoreKit or Play Billing contact can occur when the
app launches or returns to the foreground even when online maps, lookups, and
Unit Sync are disabled. Those contacts carry normal store/account, app,
product, device, and network metadata. TacMap does not add mission content,
map state, Unit Sync data, location, or coordinates to a store request.

## Context

The permanent unlock must satisfy requirements that pull in different
directions:

- a field user who has already paid must not be locked out because the device
  is offline, a store service times out, or the wall clock changes;
- an expired trial must not lead to a paywall whose only purchase button is
  indefinitely disabled because a price was never requested;
- delayed purchases and purchases completed outside TacMap must be observed;
- Google Play purchases must be acknowledged after durable delivery so Play
  does not automatically refund them;
- refunds and revocations should take effect after a conclusive store check;
- normal entitlement checks must not disclose operational or mission data;
- the implementation has no TacMap entitlement server and therefore cannot
  provide server-authoritative fraud detection or immediate revocation while
  offline.

The prior security plan preferred a user-initiated-only store session as its
darkest option, but also allowed continuous enforcement if the egress was made
truthful. This ADR chooses that allowed continuous-enforcement branch. The
online-basemap and online-lookup OPSEC switches do not disable store entitlement
traffic.

## Decision

### 1. Separate ownership reconciliation from product loading

There are two independent store operations:

1. **Entitlement observation and refresh** starts at the app lifetime. A
   refresh runs at initial startup and on a throttled transition back to the
   foreground. Store transaction updates remain observed for the app lifetime.
2. **Product and price loading** starts only while a hard or optional paywall
   is visible. Paywall appearance requests the product automatically; retry is
   explicit after a failure. Already-verified owners never need a product-price
   request to enter the app.

On iOS this means a long-lived `Transaction.updates` listener and
`Transaction.currentEntitlements` refresh are owned above `PaywallView`, while
`Product.products(for:)` remains a paywall operation. On Android the
activity/application billing owner connects to reconcile `queryPurchasesAsync`
at lifecycle boundaries, while `queryProductDetailsAsync` remains a paywall
operation. Reconnection, a foreground refresh, or an explicit Restore can also
resume pending acknowledgement work.

The paywall presents equivalent states on both platforms: loading, ready,
restoring, unavailable/error with retry, purchasing, user-cancelled/pending,
and purchased. A missing localized price never produces a permanent inert
button.

### 2. Treat the affirmative entitlement as durable, not time-limited

After a store-verified `PURCHASED`/verified non-consumable transaction is
accepted, TacMap persists an affirmative entitlement before relying on it as
delivered:

- iOS stores the affirmative marker in the device-bound Keychain;
- Android stores the affirmative marker in app-private durable preferences.

The marker has no seven-day or other wall-clock expiry. Clock rollback, clock
jump, cache age, airplane mode, a timeout, store unavailability, and process
recreation do not turn an affirmative marker into a negative one. On startup,
the marker grants offline access immediately; reconciliation may proceed in the
background without putting a known owner behind product loading.

An absent local marker is not proof of non-ownership. A reinstall, cleared app
data, or a new device may require the signed-in store account and a successful
automatic refresh or explicit Restore before TacMap can recover the unlock.

### 3. Clear only on an authoritative negative result

For this client-only design, a result is authoritative only when the platform
store API completes successfully and the relevant records are verified:

- Android: an `OK` in-app-purchase query completes and contains no active
  `PURCHASED` `unlock_full` purchase;
- iOS: enumeration of `Transaction.currentEntitlements` completes, with no
  unverified record for TacMap's product, and finds no unrevoked
  `com.tacticalmaps.app.unlock` entitlement.

That authoritative negative clears the local marker and removes permanent
access after the trial. It is the expected path for a refund, revocation, or
ownership removal.

All other outcomes are inconclusive and retain the last known-good marker,
including transport and service errors, timeouts, interrupted enumeration,
non-`OK` Play responses, and an unverified transaction for TacMap's product.
An inconclusive result may update UI diagnostics, but it must not publish
`isPurchased = false` for a known owner.

Refund/revocation enforcement is therefore eventually consistent. It happens
when an app-lifetime listener or a successful startup/foreground/Restore query
observes the authoritative negative or revoked record. A device kept offline
can retain previously verified access until it contacts the store successfully.
This is an explicit availability-over-immediate-revocation trade-off.

### 4. Deliver, acknowledge, and finish in recoverable order

Only a completed purchase unlocks TacMap. A Play purchase in `PENDING`, a
StoreKit `.pending` result, and a user-cancelled flow do not grant access.

For a completed purchase the order is:

1. validate that the transaction belongs to TacMap's non-consumable;
2. persist the affirmative entitlement and, on Android, its unacknowledged
   purchase token using a checked durable write;
3. publish access only after that write succeeds;
4. acknowledge the Play purchase or finish the StoreKit transaction last.

On Android an unacknowledged purchase token is durable pending work.
Acknowledgement success clears that work. Transient failures are retried with
bounded backoff on later connections/foreground refreshes; the app does not
pretend a callback was successful, and it does not revoke the already delivered
unlock merely because acknowledgement transport failed. An authoritative
purchase query that proves the token is no longer owned may clear obsolete
pending work. Tokens and Billing responses must not be written to release logs.

On iOS, a verified transaction is not finished until the affirmative Keychain
write succeeds. If durable publication fails, the transaction remains
unfinished so `Transaction.updates`/entitlement reconciliation can retry it.
An outside-app purchase, Ask to Buy completion, or offer-code redemption uses
the same ordering.

### 5. Bound the privacy claim

Store contact is not a TacMap mission-data upload. TacMap sends or selects the
unlock product identifier and receives product, transaction, and ownership
state through the platform store APIs. TacMap does **not** attach:

- GPS position, track points, MGRS/latitude/longitude, map centre, bounds, or
  viewed tile coordinates;
- imported map names or contents, waypoints, drawings, symbols, layers, notes,
  callsigns, affiliations, or Unit Sync room data;
- TacMap encryption keys or plaintext/ciphertext mission-store records.

Apple or Google can still process standard commerce and service metadata under
their own policies, such as the store account, app and product identifiers,
transaction/purchase-token data, device/service information, IP address, time,
and response diagnostics. The provider may be able to infer that TacMap was
launched or foregrounded from the timing of a refresh. TacMap's “no telemetry”
claim means the developer does not add analytics or receive mission/location
payloads; it must not be phrased as “the device makes no connection.”

The privacy policy, threat model, in-app OPSEC copy, and store declarations
must disclose this lifecycle contact consistently. Fully suppressing store
network activity requires an external control such as airplane mode or a
network policy; TacMap's map/lookup OPSEC gates do not promise it.

### 6. Record the client-only trust boundary

TacMap has no purchase-validation backend. iOS relies on StoreKit's on-device
verification result. Android relies on purchase state returned by the Play
Billing client. The app does not send receipts or purchase tokens to a TacMap
server for independent validation, account binding, or real-time developer
notifications.

Consequences of that choice:

- there is no TacMap-held purchase history or new server-side personal-data
  store;
- ordinary offline use remains available to a previously verified owner;
- a compromised/rooted client, modified binary, restored/tampered local state,
  or hooked store API is outside the assurance this design can provide;
- Google real-time developer notifications and server acknowledgement are not
  available, so refund/revocation and acknowledgement depend on later client
  contact;
- entitlement recovery after local-state loss depends on a successful query to
  the signed-in platform store account.

This limitation is acceptable for the low-price non-consumable in release
1.2.3. A future server-verification design requires a separate privacy review,
threat model, retention policy, and migration ADR.

## Platform parity contract

| Contract | iOS | Android |
|---|---|---|
| Immediate offline grant for a known owner | Keychain affirmative marker | App-private affirmative marker |
| App-lifetime observation | `Transaction.updates` | `PurchasesUpdatedListener` while billing owner is active |
| Launch/foreground reconciliation | `Transaction.currentEntitlements` | `queryPurchasesAsync(INAPP)` |
| Paywall-only product/price load | `Product.products(for:)` | `queryProductDetailsAsync` |
| Negative may clear only after | completed verified entitlement enumeration | `OK` completed purchase query |
| Pending purchase grants access | No | No |
| Recoverable delivery completion | finish only after durable grant | retry durable pending acknowledgement |
| Explicit recovery | Restore / `AppStore.sync()` | Restore / ownership query |

Native wording and widgets can differ, but both platforms must expose the same
user outcomes and must preserve known-good access for the same classes of
inconclusive failure.

## Verification

### Automated checks

Android tests must prove:

- a cached affirmative survives more than eight days, clock rollback/jump,
  restart, and every non-`OK` purchase query;
- `OK` plus no active unlock clears the cache, `PENDING` does not grant, and
  `PURCHASED` does grant;
- pending acknowledgement survives manager/activity recreation, transient
  failures retry, confirmed acknowledgement clears the work, and an
  authoritative no-longer-owned result removes obsolete work;
- both paywalls request the product, expose loading/ready/error/retry state,
  handle an unfetched/missing product and synchronous launch failure, and do
  not require a price query for an existing owner.

iOS tests must prove:

- the transaction listener starts when the app gate starts, including for an
  owner admitted from the Keychain, and not only when `PaywallView` appears;
- product loading does not occur until a paywall appears and supports
  loading/unavailable/failed/retry states;
- an unavailable or unverified entitlement refresh retains an affirmative,
  while a completed verified empty enumeration clears it;
- successful cache publication precedes `Transaction.finish()`, and an
  injected Keychain-write failure leaves the transaction retryable.

### Store-sandbox and operational checks

Before release, exercise Play Billing Lab/Internal Testing and StoreKit
Configuration/Sandbox on physical devices:

1. fresh install, known owner offline, more-than-eight-day offline use, clock
   change, foreground throttling, process recreation, and explicit Restore;
2. purchase success, cancellation, delayed/Ask to Buy or pending purchase,
   outside-app completion, offer/promo-code redemption, and already-owned flow;
3. acknowledgement/finish interruption, network loss at each delivery step,
   retry after relaunch, and no duplicate charge or permanent loading state;
4. refund/revoke, then confirm offline retention followed by removal only after
   one conclusive online refresh;
5. large text, landscape, screen-reader focus, visible progress, actionable
   retry, and restore-result messaging on both hard and optional paywalls;
6. sanitized diagnostics and a proxy/instrumented request audit confirming no
   app-generated mission, map, Unit Sync, or coordinate field enters the store
   integration. Never capture production account credentials or purchase
   tokens in test artefacts.

Store-console privacy answers and public policy wording require a final human
review because Apple/Google commerce processing and legal disclosure are not
determined solely by the app's source code.

## Consequences

- Paid users keep working through long offline periods and store outages.
- Paywalls recover from missing products and connection failures without
  weakening the trial gate.
- Refund and revocation enforcement is delayed while a device is offline.
- Launch/foreground is no longer accurately described as store-dark, even with
  all optional online features disabled; public documentation must say so.
- The app retains no TacMap server dependency, but accepts client-only purchase
  verification limitations.

## Follow-up

- Reconcile `docs/THREAT_MODEL.md`, `docs/PRIVACY_POLICY.md`, store listings,
  and in-app OPSEC text in Phase 5; until then, any user-initiated-only store
  wording is stale.
- Record sandbox/device results, including refund/revocation timing and Android
  acknowledgement retry, in the 1.2.3 release evidence.
- Revisit server-side verification only if fraud, support, or revocation needs
  justify its additional privacy and operational surface.
