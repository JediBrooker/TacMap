import XCTest
@testable import TacticalMaps

final class OpsecSettingsPersistenceTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUpWithError() throws {
        try super.setUpWithError()
        suiteName = "OpsecSettingsPersistenceTests.\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        try super.tearDownWithError()
    }

    func testFreshInstallOnlineFeaturesDefaultOff() {
        let settings = OpsecSettings(defaults: defaults, environment: [:])
        XCTAssertFalse(settings.onlineLookups)
        XCTAssertFalse(settings.onlineBasemaps)
        XCTAssertFalse(settings.backgroundUnitSyncLocation)
    }

    func testPersistedOnlineChoicesTakePrecedence() {
        defaults.set(true, forKey: "opsec.onlineLookups")
        defaults.set(true, forKey: "opsec.onlineBasemaps")
        defaults.set(true, forKey: "opsec.backgroundUnitSyncLocation")

        var settings = OpsecSettings(defaults: defaults, environment: [:])
        XCTAssertTrue(settings.onlineLookups)
        XCTAssertTrue(settings.onlineBasemaps)
        XCTAssertTrue(settings.backgroundUnitSyncLocation)

        defaults.set(false, forKey: "opsec.onlineLookups")
        defaults.set(false, forKey: "opsec.onlineBasemaps")
        defaults.set(false, forKey: "opsec.backgroundUnitSyncLocation")

        settings = OpsecSettings(defaults: defaults, environment: [:])
        XCTAssertFalse(settings.onlineLookups)
        XCTAssertFalse(settings.onlineBasemaps)
        XCTAssertFalse(settings.backgroundUnitSyncLocation)
    }

    func testFailedSecuritySettingWriteRollsBackBeforePublicationAndLaterFlush() throws {
        defaults.set(true, forKey: "opsec.onlineLookups")
        defaults.set(true, forKey: "opsec.onlineBasemaps")
        defaults.set(true, forKey: "opsec.backgroundUnitSyncLocation")
        XCTAssertTrue(defaults.synchronize())

        let settings = OpsecSettings(
            defaults: defaults,
            environment: [:],
            synchronize: { _ in false }
        )
        XCTAssertTrue(settings.onlineLookups)
        XCTAssertTrue(settings.onlineBasemaps)
        XCTAssertTrue(settings.backgroundUnitSyncLocation)

        XCTAssertFalse(settings.setOnlineLookups(false))
        XCTAssertFalse(settings.setOnlineBasemaps(false))
        XCTAssertFalse(settings.setBackgroundUnitSyncLocation(false))
        XCTAssertTrue(settings.onlineLookups)
        XCTAssertTrue(settings.onlineBasemaps)
        XCTAssertTrue(settings.backgroundUnitSyncLocation)
        XCTAssertNotNil(settings.persistenceIssue)

        // A later unrelated successful defaults flush must not resurrect any
        // failed candidate value that briefly entered the process cache.
        defaults.set("flush", forKey: "unrelated.preference")
        XCTAssertTrue(defaults.synchronize())
        let restarted = OpsecSettings(defaults: defaults, environment: [:])
        XCTAssertTrue(restarted.onlineLookups)
        XCTAssertTrue(restarted.onlineBasemaps)
        XCTAssertTrue(restarted.backgroundUnitSyncLocation)
    }

    func testSuccessfulSecuritySettingWritePublishesAfterReadback() {
        let settings = OpsecSettings(defaults: defaults, environment: [:])

        XCTAssertTrue(settings.setOnlineLookups(true))
        XCTAssertTrue(settings.setOnlineBasemaps(true))
        XCTAssertTrue(settings.setBackgroundUnitSyncLocation(true))
        XCTAssertTrue(settings.onlineLookups)
        XCTAssertTrue(settings.onlineBasemaps)
        XCTAssertTrue(settings.backgroundUnitSyncLocation)
        XCTAssertNil(settings.persistenceIssue)

        let restarted = OpsecSettings(defaults: defaults, environment: [:])
        XCTAssertTrue(restarted.onlineLookups)
        XCTAssertTrue(restarted.onlineBasemaps)
        XCTAssertTrue(restarted.backgroundUnitSyncLocation)
    }

    func testPersistedUnsafeRelayFallsBackAndIsDurablyRepairedAtStartup() {
        defaults.set("wss://attacker.example/collect", forKey: "opsec.relayURL")
        XCTAssertTrue(defaults.synchronize())

        let settings = OpsecSettings(defaults: defaults, environment: [:])

        XCTAssertEqual(settings.relayURL, OpsecSettings.defaultRelay)
        XCTAssertEqual(defaults.string(forKey: "opsec.relayURL"), OpsecSettings.defaultRelay)
        XCTAssertNil(settings.persistenceIssue)
    }

    func testLegacyRelaySuffixCanonicalizesAndInvalidEditNeverPublishes() {
        defaults.set("wss://relay.example/v3/room/", forKey: "opsec.relayURL")
        XCTAssertTrue(defaults.synchronize())
        let settings = OpsecSettings(defaults: defaults, environment: [:])

        XCTAssertEqual(settings.relayURL, "wss://relay.example")
        XCTAssertEqual(defaults.string(forKey: "opsec.relayURL"), "wss://relay.example")
        XCTAssertFalse(settings.setRelayURL("wss://relay.example/collect"))
        XCTAssertEqual(settings.relayURL, "wss://relay.example")
        XCTAssertEqual(defaults.string(forKey: "opsec.relayURL"), "wss://relay.example")
        XCTAssertNotNil(settings.relayValidationIssue)
    }

    func testFailedRelayWriteRollsBackBeforePublication() {
        defaults.set(OpsecSettings.defaultRelay, forKey: "opsec.relayURL")
        XCTAssertTrue(defaults.synchronize())
        let settings = OpsecSettings(
            defaults: defaults,
            environment: [:],
            synchronize: { _ in false }
        )

        XCTAssertFalse(settings.setRelayURL("wss://relay.example"))
        XCTAssertEqual(settings.relayURL, OpsecSettings.defaultRelay)
        XCTAssertEqual(defaults.string(forKey: "opsec.relayURL"), OpsecSettings.defaultRelay)
        XCTAssertNotNil(settings.persistenceIssue)
    }
}
