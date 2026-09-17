# English and German store metadata

The JSON files are the maintained source. Run `python3 scripts/export_store_localizations.py --output <folder>` for separate, paste-ready fields. This validates both stores' title/description limits, App Store keywords in UTF-8 bytes, release notes and Apple's shorter purchase-description field. Google Play descriptions reuse the same feature copy with explicit Android/store substitutions.

Prepared for the localisation update on 17 September 2026. These files have not been submitted for review or published. Product IDs are preserved; local prices come from the stores. Screenshot evidence and build identifiers belong in the release report, not these reusable descriptions.

The generated German support and privacy pages are `/de/support` and `/de/privacy`; English help is `/support`. Deploy and verify those pages before assigning their URLs in store metadata. The detailed technical threat model remains English and is clearly labelled as such on the German pages. Privacy wording is a translation of the existing disclosure, not a claim of jurisdiction-specific legal certification.

Field limits checked against primary documentation:

- [Apple app information](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information)
- [Apple platform version information](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information)
- [Apple purchase information](https://developer.apple.com/help/app-store-connect/reference/in-app-purchases-and-subscriptions/in-app-purchase-information)
- [Google Play app setup and listing](https://support.google.com/googleplay/android-developer/answer/9859152)
