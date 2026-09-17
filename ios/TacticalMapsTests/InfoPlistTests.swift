import XCTest
@testable import TacticalMaps

/// Guards against plist regressions that Apple flags at review time or that
/// leave users without a required usage description.
final class InfoPlistTests: XCTestCase {

    private let expectedAccessedAPIReasons: [String: Set<String>] = [
        "NSPrivacyAccessedAPICategoryFileTimestamp": ["C617.1", "3B52.1"],
        "NSPrivacyAccessedAPICategorySystemBootTime": ["35F9.1"],
        "NSPrivacyAccessedAPICategoryUserDefaults": ["CA92.1"]
    ]

    private var appInfo: [String: Any] {
        Bundle.main.infoDictionary ?? Bundle(for: type(of: self)).infoDictionary ?? [:]
    }

    private var iosRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    private var sourceInfoURL: URL {
        iosRoot.appendingPathComponent("TacticalMaps/Resources/Info.plist")
    }

    private var sourcePrivacyURL: URL {
        iosRoot.appendingPathComponent("TacticalMaps/Resources/PrivacyInfo.xcprivacy")
    }

    /// Hosted unit tests live at TacticalMaps.app/PlugIns/*.xctest. Walk from
    /// both relevant bundles so this remains valid under Xcode and xcodebuild.
    private var builtAppURL: URL? {
        for start in [Bundle.main.bundleURL, Bundle(for: type(of: self)).bundleURL] {
            var candidate = start.standardizedFileURL
            while candidate.path != candidate.deletingLastPathComponent().path {
                if candidate.pathExtension == "app" { return candidate }
                candidate.deleteLastPathComponent()
            }
        }
        return nil
    }

    private func plist(at url: URL) throws -> [String: Any] {
        let data = try Data(contentsOf: url)
        let decoded = try PropertyListSerialization.propertyList(
            from: data,
            options: [],
            format: nil
        )
        return try XCTUnwrap(decoded as? [String: Any], "Invalid plist dictionary at \(url.path)")
    }

    private func accessedAPIReasons(in manifest: [String: Any]) throws -> [String: Set<String>] {
        let entries = try XCTUnwrap(
            manifest["NSPrivacyAccessedAPITypes"] as? [[String: Any]],
            "NSPrivacyAccessedAPITypes must be an array of dictionaries"
        )
        var result: [String: Set<String>] = [:]
        for entry in entries {
            let category = try XCTUnwrap(entry["NSPrivacyAccessedAPIType"] as? String)
            let reasons = try XCTUnwrap(entry["NSPrivacyAccessedAPITypeReasons"] as? [String])
            XCTAssertNil(result[category], "Duplicate privacy API category: \(category)")
            result[category] = Set(reasons)
            XCTAssertEqual(reasons.count, Set(reasons).count,
                           "Duplicate reason in privacy API category: \(category)")
        }
        return result
    }

    func testFaceIDUsageDescriptionIsPresent() {
        let desc = appInfo["NSFaceIDUsageDescription"] as? String ?? ""
        XCTAssertFalse(desc.isEmpty,
            "NSFaceIDUsageDescription missing from Info.plist. Add it in project.yml "
            + "so Apple doesn't reject the build and users see why Face ID is needed.")
    }

    func testSourcePrivacyManifestIsValidAndComplete() throws {
        let manifest = try plist(at: sourcePrivacyURL)
        XCTAssertEqual(try accessedAPIReasons(in: manifest), expectedAccessedAPIReasons)
        XCTAssertEqual(manifest["NSPrivacyTracking"] as? Bool, false)
        XCTAssertEqual(manifest["NSPrivacyTrackingDomains"] as? [String], [])

        let collected = try XCTUnwrap(
            manifest["NSPrivacyCollectedDataTypes"] as? [[String: Any]]
        )
        XCTAssertEqual(collected.count, 2)
        let byType = Dictionary(
            uniqueKeysWithValues: try collected.map { entry in
                let type = try XCTUnwrap(entry["NSPrivacyCollectedDataType"] as? String)
                return (type, entry)
            }
        )
        XCTAssertEqual(
            Set(byType.keys),
            [
                "NSPrivacyCollectedDataTypePreciseLocation",
                "NSPrivacyCollectedDataTypeEmailsOrTextMessages"
            ]
        )
        for type in byType.keys {
            let declaration = try XCTUnwrap(byType[type])
            XCTAssertEqual(declaration["NSPrivacyCollectedDataTypeLinked"] as? Bool, false)
            XCTAssertEqual(declaration["NSPrivacyCollectedDataTypeTracking"] as? Bool, false)
            XCTAssertEqual(
                declaration["NSPrivacyCollectedDataTypePurposes"] as? [String],
                ["NSPrivacyCollectedDataTypePurposeAppFunctionality"]
            )
        }
    }

    func testBuiltAppPrivacyManifestMatchesSourceDeclarations() throws {
        let appURL = try XCTUnwrap(builtAppURL, "Hosted TacticalMaps.app bundle not found")
        let builtURL = appURL.appendingPathComponent("PrivacyInfo.xcprivacy")
        XCTAssertTrue(FileManager.default.fileExists(atPath: builtURL.path),
                      "PrivacyInfo.xcprivacy is not bundled in TacticalMaps.app")

        let source = try plist(at: sourcePrivacyURL)
        let built = try plist(at: builtURL)
        XCTAssertEqual(try accessedAPIReasons(in: built), try accessedAPIReasons(in: source))
        XCTAssertEqual(
            built["NSPrivacyCollectedDataTypes"] as? NSArray,
            source["NSPrivacyCollectedDataTypes"] as? NSArray
        )
        XCTAssertEqual(built["NSPrivacyTracking"] as? Bool,
                       source["NSPrivacyTracking"] as? Bool)
        XCTAssertEqual(built["NSPrivacyTrackingDomains"] as? [String],
                       source["NSPrivacyTrackingDomains"] as? [String])
    }

    func testLocationAndReleaseDeclarationsMatchActualCapabilities() throws {
        let source = try plist(at: sourceInfoURL)
        let appURL = try XCTUnwrap(builtAppURL, "Hosted TacticalMaps.app bundle not found")
        let built = try plist(at: appURL.appendingPathComponent("Info.plist"))

        let description = try XCTUnwrap(source["NSLocationWhenInUseUsageDescription"] as? String)
        for phrase in [
            "user-started GPX tracks",
            "background",
            "encrypted position",
            "joined Sync peers",
            "only when you enable Share my location",
            "Background Unit Sync in OPSEC"
        ] {
            XCTAssertTrue(description.contains(phrase),
                          "Location permission copy must disclose: \(phrase)")
        }
        XCTAssertEqual(source["UIBackgroundModes"] as? [String], ["location"])
        XCTAssertNil(source["NSLocationAlwaysUsageDescription"])
        XCTAssertNil(source["NSLocationAlwaysAndWhenInUseUsageDescription"])

        XCTAssertEqual(built["NSLocationWhenInUseUsageDescription"] as? String, description)
        XCTAssertEqual(built["UIBackgroundModes"] as? [String], ["location"])

        // This test deliberately checks declaration parity, not the legal
        // exemption conclusion. The App Store Connect/account-holder review is
        // recorded separately in docs/security/IOS_EXPORT_COMPLIANCE.md.
        let sourceEncryption = try XCTUnwrap(source["ITSAppUsesNonExemptEncryption"] as? Bool)
        let builtEncryption = try XCTUnwrap(built["ITSAppUsesNonExemptEncryption"] as? Bool)
        XCTAssertEqual(builtEncryption, sourceEncryption)
    }
}
