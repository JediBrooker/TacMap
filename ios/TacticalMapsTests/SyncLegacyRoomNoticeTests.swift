import XCTest
@testable import TacticalMaps

/// 3.0.2 R1(c): the plain mixed versions note under the red LEGACY ROOM line,
/// plus the iOS side of the R1 fixture rows (iOS v2 ids stay uppercase, pins
/// are android only).
final class SyncLegacyRoomNoticeTests: XCTestCase {

    private let v2Code = "2:Kx9-delta-orchard-58"
    private let v3Code = "3:Kx9-delta-orchard-58-ridge"

    private static func load(_ name: String) -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let file = dir.appendingPathComponent(name)
            if let data = try? Data(contentsOf: file),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return object
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate \(name)")
        return [:]
    }

    // MARK: when it shows

    func testJoinFormShowsTheNoteOnlyWhileAV2CodeIsTyped() {
        XCTAssertEqual(LegacyRoomNotice.placement(joinedRoom: nil, typedCode: v2Code), .joinForm)
        // same trim the join button uses
        XCTAssertEqual(LegacyRoomNotice.placement(joinedRoom: nil, typedCode: "  \(v2Code)\n"), .joinForm)
        XCTAssertEqual(LegacyRoomNotice.placement(joinedRoom: nil, typedCode: "2:"), .joinForm)

        for code in ["", "   ", v3Code, "2", "22:abc", "x2:abc", "3:2:abc", "2;abc"] {
            XCTAssertNil(LegacyRoomNotice.placement(joinedRoom: nil, typedCode: code), code)
        }
    }

    func testJoinedRoomShowsTheNoteForAsLongAsTheRoomIsV2() {
        XCTAssertEqual(LegacyRoomNotice.placement(joinedRoom: v2Code, typedCode: ""), .joinedRoom)
        // whatever is left in the hidden join field doesn't matter once joined
        XCTAssertEqual(LegacyRoomNotice.placement(joinedRoom: v2Code, typedCode: v3Code), .joinedRoom)
        XCTAssertNil(LegacyRoomNotice.placement(joinedRoom: v3Code, typedCode: ""))
        XCTAssertNil(LegacyRoomNotice.placement(joinedRoom: v3Code, typedCode: v2Code))
    }

    func testIsLegacyMatchesTheJoinGatesPrefixRule() {
        XCTAssertTrue(LegacyRoomNotice.isLegacy(v2Code))
        XCTAssertFalse(LegacyRoomNotice.isLegacy(v3Code))
        XCTAssertFalse(LegacyRoomNotice.isLegacy(nil))
        // a 2: code never asks for the v3 location consent, a 3: one can
        XCTAssertFalse(UnitSyncJoinGate.requiresConsent(roomCode: v2Code, shareLocation: false, backgroundLocation: false))
        XCTAssertTrue(UnitSyncJoinGate.requiresConsent(roomCode: v3Code, shareLocation: false, backgroundLocation: false))
    }

    // MARK: copy

    func testNoteUsesTheCatalogCopyInEnglishAndGerman() throws {
        let catalog = Self.load("localization/catalog.json")
        let entry = try XCTUnwrap(catalog["sync_legacy_room_mixed_versions"] as? [String: Any])
        let english = try XCTUnwrap(entry["en"] as? String)
        let german = try XCTUnwrap(entry["de"] as? String)
        XCTAssertTrue(english.contains("3:"))
        XCTAssertTrue(german.contains("3:"))

        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        let message = LegacyRoomNotice.mixedVersionsMessage
        XCTAssertEqual(message.id, "id.sync_legacy_room_mixed_versions")
        language.select(.en)
        XCTAssertEqual(message.text, english)
        language.select(.de)
        XCTAssertEqual(message.text, german)
        XCTAssertNotEqual(message.text, english)
    }

    // MARK: R1 fixture rows, iOS side

    func testR1PinsAreAndroidOnlyAndIosV2IdsStayUppercase() throws {
        let contract = Self.load("testdata/sync_client_behaviour.json")
        let v2 = try XCTUnwrap(contract["v2"] as? [String: Any])
        let rules = try XCTUnwrap(v2["outboundIdRules"] as? [String: Any])
        XCTAssertTrue((rules["remember"] as? String ?? "").hasPrefix("Android only"))
        XCTAssertTrue((rules["persist"] as? String ?? "").hasPrefix("Android only"))
        let vectors = try XCTUnwrap(v2["vectors"] as? [String: Any])

        // every id the 3.0.2 pin rows use, lowercase pins included: iOS ignores
        // pins and sends uuidString, keyed by the lowercase state key
        var raws: Set<String> = []
        let remember = vectors["remember"] as? [[String: Any]] ?? []
        XCTAssertEqual(remember.count, 12)
        for row in remember {
            for event in row["events"] as? [[String: Any]] ?? [] {
                if let raw = event["raw"] as? String { raws.insert(raw) }
            }
            if let pinned = row["expectRemembered"] as? String { raws.insert(pinned) }
        }
        let outbound = vectors["outbound"] as? [[String: Any]] ?? []
        for row in outbound {
            for key in ["localId", "rememberedRawId", "lastInboundRawId", "expectFrameId"] {
                if let raw = row[key] as? String { raws.insert(raw) }
            }
        }
        XCTAssertTrue(raws.contains { $0 != $0.uppercased() }, "expected lowercase pins in the fixture")
        // a couple of rows carry a dashless raw on purpose, iOS refuses those too
        for raw in raws where LegacyV2Ids.stateKey(raw) == nil {
            raws.remove(raw)
        }
        XCTAssertFalse(raws.isEmpty)
        for raw in raws {
            let uuid = try XCTUnwrap(UUID(uuidString: raw), raw)
            XCTAssertEqual(LegacyV2Ids.outboundId(uuid), raw.uppercased(), raw)
            XCTAssertEqual(LegacyV2Ids.stateKey(raw), raw.lowercased(), raw)
            XCTAssertEqual(LegacyV2Ids.outboundId(stateKey: raw.lowercased()), raw.uppercased(), raw)
        }

        // the new row is android's, iOS keeps its two rows
        let platforms = Dictionary(grouping: outbound, by: { $0["platform"] as? String ?? "?" })
        XCTAssertEqual(platforms["ios"]?.count, 2)
        XCTAssertEqual(platforms["android"]?.count, 4)
        let afterEdit = try XCTUnwrap(outbound.first { $0["id"] as? String == "android_own_object_after_ios_edit" })
        XCTAssertEqual(afterEdit["platform"] as? String, "android")
        for row in platforms["ios"] ?? [] {
            XCTAssertEqual(row["expectFrameId"] as? String, (row["expectFrameId"] as? String)?.uppercased(), "\(row)")
        }
    }
}
