import XCTest
@testable import TacticalMaps

/// Fails the build 60 days before the Esri API key expires. iOS mirror of
/// Android's EsriKeyExpiryTest - see that file for the full why.
///
/// The key and its expiry come from Info.plist (ESRIApiKey / ESRIKeyExpiry),
/// substituted from Secrets.xcconfig at build time. An empty key means no Esri
/// basemap, so nothing to expire, so we skip.
final class EsriKeyExpiryTests: XCTestCase {

    func testKeyIsNotWithin60DaysOfExpiry() throws {
        let info = Bundle(for: type(of: self)).infoDictionary
        // Test bundle vs app bundle: read the app bundle's Info.plist.
        let appInfo = Bundle.main.infoDictionary ?? info ?? [:]

        let key = (appInfo["ESRIApiKey"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return } // no key configured, nothing to expire

        let expiryString = try XCTUnwrap(appInfo["ESRIKeyExpiry"] as? String,
                                         "ESRIKeyExpiry missing from Info.plist")
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.timeZone = TimeZone(identifier: "UTC")
        let expiry = try XCTUnwrap(fmt.date(from: expiryString),
                                   "ESRIKeyExpiry not yyyy-MM-dd: \(expiryString)")

        let daysLeft = Calendar.current.dateComponents([.day], from: Date(), to: expiry).day ?? 0
        XCTAssertGreaterThan(
            daysLeft, 60,
            "Esri API key expires \(expiryString) (\(daysLeft) days). Rotate it: new key in "
            + "Secrets.xcconfig, bump ESRIKeyExpiry in Info.plist + ESRI_KEY_EXPIRY in the "
            + "Android build.gradle.kts, ship an update."
        )
    }
}
