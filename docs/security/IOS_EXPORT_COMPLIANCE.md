# iOS encryption export-compliance review

Status: **Confirmed for TacMap 2.0.0 (build 64)**  
Engineering audit: 2026-08-28  
Account-holder confirmation and Apple processing: 2026-08-28

This record prevents the `ITSAppUsesNonExemptEncryption` answer from being
treated as a guess or as a claim that TacMap “only uses TLS.” TacMap does use
encryption beyond transport security.

## Shipped cryptography

| Capability | Shipped implementation | Provider |
|---|---|---|
| Mission-data encryption at rest | AES-256-GCM | Apple CryptoKit |
| Sync key derivation and authentication | PBKDF2-HMAC-SHA-256, HMAC-SHA-256, SHA-256 | Apple CommonCrypto and CryptoKit |
| Sync confidentiality | AES-256-GCM | Apple CryptoKit |
| Sync identity signatures | Ed25519 (`Curve25519.Signing`) | Apple CryptoKit |
| TacMap Chat direct-session key agreement and derivation | X25519 (`Curve25519.KeyAgreement`) and HKDF-SHA-256 | Apple CryptoKit |
| TacMap Chat room/direct confidentiality and authentication | AES-256-GCM, HMAC-SHA-256, and Ed25519 | Apple CryptoKit |
| Network transport | TLS / WSS | Apple URLSession / Network stack |

The app does not bundle an independent cryptographic library or a proprietary
encryption algorithm. Its message formats and key-separation labels are
application protocols around the standard algorithms above; the cryptographic
implementations are supplied by Apple operating-system frameworks.

Relevant code: `TacticalMaps/Util/SealedEnvelope.swift`,
`TacticalMaps/Util/SafeStore.swift`, `TacticalMaps/Sync/SyncCrypto.swift`,
`TacticalMaps/Sync/SyncSigning.swift`, `TacticalMaps/Sync/SyncManager.swift`, and
`TacticalMaps/Chat/TacMapChatCrypto.swift`.

## Current engineering assessment

Apple's current reference says encryption limited to that within the Apple
operating system requires no App Store Connect documentation. On that technical
fact pattern, the existing `ITSAppUsesNonExemptEncryption = false` is a
reasonable expected outcome. It has intentionally not been changed by this
audit.

That is not a legal classification. Apple says the developer is responsible for
interpreting export rules, evaluates documentation case by case, and calls out
separate French controls. Engineering therefore cannot close this gate alone.

## Release decision and verified Apple result

The account holder confirmed on 2026-08-28 that TacMap's shipped cryptographic
implementations are supplied by Apple operating-system frameworks and fall in
Apple's “encryption limited to that within the Apple operating system” category,
for which Apple states that no App Store Connect documentation is required. The
2.0.0 candidate therefore retained
`ITSAppUsesNonExemptEncryption = false`.

Apple's remote archive validation completed with no errors. App Store Connect
then accepted build 64 under delivery UUID
`4708a468-1dfc-4063-9853-f8c3e51c2c3f` and reported all of the following:

- `processingState: VALID`
- `buildAudienceType: APP_STORE_ELIGIBLE`
- `usesNonExemptEncryption: false`

Reassess this decision if TacMap begins shipping a cryptographic implementation
outside Apple operating-system frameworks or Apple changes its documented
classification.

## Apple sources checked

- [Overview of export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance)
- [Export compliance documentation for encryption](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption/)
- [Determine and upload app encryption documentation](https://developer.apple.com/help/app-store-connect/manage-app-information/determine-and-upload-app-encryption-documentation)
- [`ITSAppUsesNonExemptEncryption`](https://developer.apple.com/documentation/bundleresources/information-property-list/itsappusesnonexemptencryption)
