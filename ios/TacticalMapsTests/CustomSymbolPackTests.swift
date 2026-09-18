import XCTest
@testable import TacticalMaps

final class CustomSymbolPackTests: XCTestCase {
    private let fixture = #"{"format":1,"name":"Test Pack","attribution":"Test fixture from CC0 Taktische-Zeichen v2.0.0","symbols":[{"id":"6e5cdd96a6e02a921b45134ccc23b3bc644c3f6c9c05dd365a2b1eb20f3200b9","name":"Bundeswehr Einheiten / Einheit der Bundeswehr","png":"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"}]}"#.data(using: .utf8)!
    func testSearchMatchesCategoriesMultipleWordsAndUmlautsOffline() {
        XCTAssertTrue(CustomSymbolSearch.matches("Deutsche BOS / Feuerwehr / Führungsstelle", query: "feuerwehr fuhr"))
        XCTAssertTrue(CustomSymbolSearch.matches("THW / Zugführer", query: "ZUGFUHRER"))
        XCTAssertTrue(CustomSymbolSearch.matches("Any symbol", query: "  "))
        XCTAssertFalse(CustomSymbolSearch.matches("Feuerwehr", query: "polizei"))
    }
    private enum Locked: Error { case unavailable }

    func testEncryptedPersistenceLockFailureAndTamperPreserveExistingStore() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let old = SafeStore.keyProvider
        defer { SafeStore.keyProvider = old }
        let key = Data(repeating: 7, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        let store = CustomSymbolStore(directory: root)
        let pack = try store.importPack(fixture)
        let file = root.appendingPathComponent("custom-symbol-packs.sealed")
        let original = try Data(contentsOf: file)
        XCTAssertTrue(SealedEnvelope.isSealedFile(original))
        XCTAssertNil(String(data: original, encoding: .utf8))
        let restored = CustomSymbolStore(directory: root)
        try restored.reload()
        XCTAssertEqual(restored.packs.first?.symbols, pack.symbols)
        SafeStore.keyProvider = { throw Locked.unavailable }
        XCTAssertThrowsError(try restored.importPack(fixture))
        XCTAssertTrue(restored.packs.isEmpty)
        XCTAssertEqual(try Data(contentsOf: file), original)
        SafeStore.keyProvider = { key }
        var corrupt = original; corrupt[corrupt.count-1] ^= 1
        try corrupt.write(to: file)
        XCTAssertThrowsError(try restored.importPack(fixture))
        XCTAssertEqual(try Data(contentsOf: file), corrupt)
        // This new store has no plaintext migration path.
        try fixture.write(to: file)
        XCTAssertThrowsError(try restored.reload())
        XCTAssertEqual(try Data(contentsOf: file), fixture)
    }

    func testPortableArtworkSurvivesGeoJSONWithoutInstalledLibrary() throws {
        let pack = try JSONDecoder().decode(CustomSymbolPack.self, from: fixture)
        let symbol = try XCTUnwrap(pack.symbols.first)
        XCTAssertTrue(CustomSymbolStore.valid(pack))
        let marker = MarkerSymbol(set: .custom, symbolID: symbol.id, colorHex: "#3B7BE0", custom: symbol)
        let waypoint = Waypoint(name: "Crew", latitude: 51, longitude: 9, kind: .marker(marker))
        let text = try GeoJSONExporter.export(waypoints: [waypoint])
        let result = try GeoJSONImporter.parse(Data(text.utf8), existingLayers: DrawingLayer.seedDefaults, fallbackLayerID: DrawingLayer.legacyFallbackID)
        let restored = try XCTUnwrap(result.waypoints.first?.kind.markerSymbol)
        XCTAssertEqual(restored.custom, symbol)
        XCTAssertNotNil(restored.custom?.image())
    }

    func testMalformedAndExcessivelyNestedArtworkIsRejected() throws {
        let pack = try JSONDecoder().decode(CustomSymbolPack.self, from: fixture)
        let symbol = pack.symbols[0]
        XCTAssertNil(CustomSymbol(id: "wrong", name: symbol.name, png: symbol.png).image())
        XCTAssertNil(CustomSymbol(id: symbol.id, name: symbol.name, png: "https://example.invalid/image.png").image())
        XCTAssertFalse(CustomSymbolStore.valid(CustomSymbolPack(format: 2, name: pack.name, attribution: "", symbols: pack.symbols)))
        XCTAssertFalse(CustomSymbolStore.valid(CustomSymbolPack(format: 1, name: pack.name, attribution: "", symbols: [symbol, symbol])))
        XCTAssertThrowsError(try CustomSymbolStore.preflight(Data((String(repeating: "[", count: 100) + String(repeating: "]", count: 100)).utf8)))
        XCTAssertNoThrow(try CustomSymbolStore.preflight(fixture))
    }
}
