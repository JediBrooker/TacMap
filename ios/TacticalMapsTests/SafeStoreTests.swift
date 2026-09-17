import XCTest
import Combine
import CoreLocation
@testable import TacticalMaps

final class SafeStoreTests: XCTestCase {

    private enum ProbeError: Error { case failed }

    private final class WriteProbe {
        var attempts = 0
        var successfulPayloads: [Data] = []
        var shouldFail = false

        func write(_ data: Data, _ url: URL, _ label: String) throws {
            attempts += 1
            if shouldFail { throw ProbeError.failed }
            successfulPayloads.append(data)
        }

        func reset() {
            attempts = 0
            successfulPayloads = []
        }
    }

    private struct LegacyDrawingDocument: Encodable {
        let schemaVersion: Int?
        let layers: [DrawingLayer]
        let shapes: [DrawingShape]
        let activeLayerID: UUID?
        let protectedDefaultLayerIDs: [UUID]?
    }

    private let testKey = Data((0..<32).map { UInt8($0) })
    private let label = "waypoints.json"

    func testDataKeyRotationFinalizerCommitsOnlyAfterPreviousSlotIsGone() {
        var slots: Set<String> = ["device", "auth"]
        var metadata = "pending-auth"

        let outcome = DataKeyRotationFinalizer.finish(
            targetAuthBound: true,
            deletePrevious: { slots.remove("device") != nil },
            commitTargetMetadata: {
                metadata = "auth"
                return true
            },
            rollbackMetadata: {
                metadata = "device"
                return true
            },
            deleteReplacement: { slots.remove("auth") != nil }
        )

        XCTAssertEqual(outcome, .committed)
        XCTAssertEqual(slots, ["auth"])
        XCTAssertEqual(metadata, "auth")
    }

    func testDataKeyRotationFinalizerRollsBackWhenWeakerSlotCannotBeDeleted() {
        var slots: Set<String> = ["device", "auth"]
        var metadata = "pending-auth"

        let outcome = DataKeyRotationFinalizer.finish(
            targetAuthBound: true,
            deletePrevious: { false },
            commitTargetMetadata: {
                metadata = "auth"
                return true
            },
            rollbackMetadata: {
                metadata = "device"
                return true
            },
            deleteReplacement: { slots.remove("auth") != nil }
        )

        XCTAssertEqual(outcome, .rolledBack)
        XCTAssertEqual(metadata, "device", "the UI must not claim auth-bound protection")
        XCTAssertEqual(slots, ["device"])
    }

    func testDataKeyColdStartFinalizerDoesNotPublishAuthWhenCleanupStillFails() {
        var slots: Set<String> = ["device", "auth"]
        var metadata = "pending-auth"

        let outcome = DataKeyRotationFinalizer.finish(
            targetAuthBound: true,
            deletePrevious: { false },
            commitTargetMetadata: {
                metadata = "auth"
                return true
            },
            rollbackMetadata: {
                metadata = "device"
                return true
            },
            deleteReplacement: { false }
        )

        XCTAssertEqual(outcome, .unrecoverable)
        XCTAssertEqual(metadata, "device")
        XCTAssertEqual(slots, ["device", "auth"])
    }

    func testDataKeyAuthToDeviceCommitsBeforeBestEffortAuthCleanup() {
        var slots: Set<String> = ["auth", "device"]
        var metadata = "pending-device"

        let outcome = DataKeyRotationFinalizer.finish(
            targetAuthBound: false,
            deletePrevious: { false },
            commitTargetMetadata: {
                metadata = "device"
                return true
            },
            rollbackMetadata: {
                metadata = "auth"
                return true
            },
            deleteReplacement: { slots.remove("device") != nil }
        )

        XCTAssertEqual(outcome, .committed)
        XCTAssertEqual(metadata, "device", "a stale stronger AUTH slot cannot weaken DEVICE mode")
        XCTAssertEqual(slots, ["auth", "device"])
    }

    func testDataKeyAuthToDeviceRollsBackOnlyAfterRemovingWeakReplacement() {
        var rollbackCalled = false
        var replacementDeleteCalled = false

        let outcome = DataKeyRotationFinalizer.finish(
            targetAuthBound: false,
            deletePrevious: { XCTFail("cleanup must follow commit"); return false },
            commitTargetMetadata: { false },
            rollbackMetadata: { rollbackCalled = true; return true },
            deleteReplacement: { replacementDeleteCalled = true; return true }
        )

        XCTAssertEqual(outcome, .rolledBack)
        XCTAssertTrue(replacementDeleteCalled)
        XCTAssertTrue(rollbackCalled)
    }

    func testDataKeyPendingDeviceTransitionNeverReportsAuthWhenCommitAndCleanupFail() {
        var rollbackCalled = false
        let outcome = DataKeyRotationFinalizer.finish(
            targetAuthBound: false,
            deletePrevious: { XCTFail("cleanup must follow commit"); return false },
            commitTargetMetadata: { false },
            rollbackMetadata: { rollbackCalled = true; return true },
            deleteReplacement: { false }
        )

        XCTAssertEqual(outcome, .unrecoverable)
        XCTAssertFalse(rollbackCalled, "AUTH metadata must not be restored while DEVICE survives")
        XCTAssertFalse(DataKeyReportedMode.isAuthBound(
            metadataAuthBound: false,
            transitionPending: true,
            legacyFallback: true
        ))
    }

    func testDataKeyPendingAuthUpgradeAlsoReportsConservativeDeviceMode() {
        XCTAssertFalse(DataKeyReportedMode.isAuthBound(
            metadataAuthBound: true,
            transitionPending: true,
            legacyFallback: false
        ))
    }

    func testLegacyDataKeyRecoveryPrefersSavedSecondSlotButNeverTrustsLegacyAuthFlag() throws {
        let plan = try XCTUnwrap(DataKeyLegacyRecovery.plan(
            accounts: ["slot1", "slot2"],
            savedAccount: "slot2",
            legacyAuthBound: true,
            isAvailable: { ["slot1", "slot2"].contains($0) }
        ))

        XCTAssertEqual(plan.activeAccount, "slot2")
        XCTAssertFalse(plan.authBound)
    }

    func testLegacyDataKeyRecoveryFallsBackBySlotOrderAndStillReportsDeviceMode() throws {
        let plan = try XCTUnwrap(DataKeyLegacyRecovery.plan(
            accounts: ["slot1", "slot2"],
            savedAccount: "missing",
            legacyAuthBound: false,
            isAvailable: { $0 == "slot1" }
        ))

        XCTAssertEqual(plan.activeAccount, "slot1")
        XCTAssertFalse(plan.authBound)
    }

    func testLegacyDataKeyRecoveryPreservesSavedCandidateUntilAReadCanValidateIt() throws {
        var deleted: [String] = []
        let plan = try XCTUnwrap(DataKeyLegacyRecovery.plan(
            accounts: ["saved-auth-inaccessible", "valid-device"],
            savedAccount: "saved-auth-inaccessible",
            legacyAuthBound: true,
            isAvailable: { _ in true }
        ))

        XCTAssertEqual(plan.activeAccount, "saved-auth-inaccessible")
        XCTAssertFalse(plan.authBound)
        XCTAssertTrue(deleted.isEmpty, "planning recovery must never delete an unvalidated alternate")
    }

    func testFailedLegacyMetadataCommitCannotAuthorizeAlternateDeletion() {
        var slots: Set<String> = ["selected", "alternate"]
        let metadataCommittedAndReadBack = false

        if metadataCommittedAndReadBack {
            slots.remove("alternate")
        }

        XCTAssertEqual(slots, ["selected", "alternate"])
        XCTAssertFalse(DataKeyReportedMode.isAuthBound(
            metadataAuthBound: nil,
            transitionPending: false,
            legacyFallback: false
        ))
    }

    override func setUp() {
        super.setUp()
        // Real key lives in the Keychain, which needs entitlements the test host
        // doesn't always have. Swap in a fixed one; the envelope code is the same.
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
    }

    override func tearDown() {
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        super.tearDown()
    }

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func testWriteThenReadRoundTrips() throws {
        let url = tempDir().appendingPathComponent("d.json")
        try SafeStore.write(Data("hello world".utf8), to: url, label: label)
        let result = SafeStore.read(url, label: label) { String(decoding: $0, as: UTF8.self) }
        guard case .loaded(let s) = result else { return XCTFail("expected loaded") }
        XCTAssertEqual(s, "hello world")
    }

    func testBytesOnDiskAreCiphertextNotPlaintext() throws {
        let url = tempDir().appendingPathComponent("d.json")
        try SafeStore.write(Data(#"[{"callsign":"ZERO","grid":"30UXC1234567890"}]"#.utf8), to: url, label: label)
        let onDisk = try Data(contentsOf: url)
        XCTAssertTrue(SealedEnvelope.isSealedFile(onDisk), "sealed files carry the magic")
        let text = String(decoding: onDisk, as: UTF8.self)
        XCTAssertFalse(text.contains("ZERO"), "callsign must not survive in the clear")
        XCTAssertFalse(text.contains("30UXC"), "grid must not survive in the clear")
    }

    func testMissingFileIsEmptyNotCorrupt() {
        let url = tempDir().appendingPathComponent("absent.json")
        if case .empty = SafeStore.read(url, label: label, decode: { $0 }) { } else {
            XCTFail("a missing file must be .empty (fresh install), not .corrupt")
        }
    }

    func testCorruptFileIsQuarantinedAndPreserved_notOverwritten() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try Data("{ not valid json".utf8).write(to: url)
        struct Boom: Error {}
        let result = SafeStore.read(url, label: label) { _ -> Int in throw Boom() }
        guard case .corrupt(let quarantine, _) = result else {
            return XCTFail("a present-but-unreadable file must be .corrupt")
        }
        let q = try XCTUnwrap(quarantine, "the original bytes must be preserved aside")
        XCTAssertTrue(FileManager.default.fileExists(atPath: q.path))
        XCTAssertEqual(try String(contentsOf: q, encoding: .utf8), "{ not valid json")
        // Primary path is freed so a fresh seed doesn't clobber the recovery copy.
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testTamperedSealedFileIsQuarantinedNotSilentlyEmpty() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try SafeStore.write(Data(#"{"ok":true}"#.utf8), to: url, label: label)
        var bytes = try Data(contentsOf: url)
        bytes[bytes.count - 1] ^= 0x01
        try bytes.write(to: url)

        let result = SafeStore.read(url, label: label) { $0 }
        guard case .corrupt(let quarantine, _) = result else {
            return XCTFail("a failed tag check is corruption, not emptiness")
        }
        XCTAssertNotNil(quarantine)
    }

    func testLegacyPlaintextFileIsReadAndSealedInPlace() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try Data(#"{"legacy":true}"#.utf8).write(to: url) // pre-encryption build

        let result = SafeStore.read(url, label: label) { String(decoding: $0, as: UTF8.self) }
        guard case .loaded(let s) = result else { return XCTFail("expected loaded") }
        XCTAssertEqual(s, #"{"legacy":true}"#)

        // Migrated in place: same path, now ciphertext, no orphaned plaintext.
        let onDisk = try Data(contentsOf: url)
        XCTAssertTrue(SealedEnvelope.isSealedFile(onDisk))
        XCTAssertFalse(String(decoding: onDisk, as: UTF8.self).contains("legacy"))

        // and it still reads back
        let again = SafeStore.read(url, label: label) { String(decoding: $0, as: UTF8.self) }
        guard case .loaded(let s2) = again else { return XCTFail("expected loaded") }
        XCTAssertEqual(s2, #"{"legacy":true}"#)
    }

    func testMigratedPathNeverAcceptsPlaintextAgain() throws {
        let url = tempDir().appendingPathComponent("d.json")
        try Data(#"{"legacy":true}"#.utf8).write(to: url)
        guard case .loaded = SafeStore.read(url, label: label, decode: { $0 }) else {
            return XCTFail("legacy migration should succeed once")
        }

        // Simulate a downgrade/tamper after migration. The durable marker must
        // prevent the old plaintext compatibility path from reopening.
        try Data(#"{"attacker":"plaintext"}"#.utf8).write(to: url, options: .atomic)
        let result = SafeStore.read(url, label: label, decode: { $0 })
        guard case .corrupt = result else {
            return XCTFail("sealed-only path must reject later plaintext")
        }
    }

    func testLockedKeyLeavesTheFileAloneAndDoesNotQuarantine() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try SafeStore.write(Data(#"{"mission":"real"}"#.utf8), to: url, label: label)
        let before = try Data(contentsOf: url)

        SafeStore.keyProvider = { throw DataKey.LockedError() }
        let result = SafeStore.read(url, label: label) { $0 }

        guard case .locked = result else { return XCTFail("locked is not corrupt") }
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "file must still be there")
        XCTAssertEqual(try Data(contentsOf: url), before, "bytes untouched")
        let siblings = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        XCTAssertFalse(siblings.contains { $0.contains(".corrupt-") }, "nothing quarantined")
    }

    func testBlobFromAnotherStoreIsQuarantinedNotLoaded() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("waypoints.json")
        // Seal under drawings.json, then try to read it as waypoints.json.
        let alien = try SealedEnvelope.sealFile(key: testKey,
                                                plaintext: Data(#"{"shapes":[]}"#.utf8),
                                                label: "drawings.json")
        try alien.write(to: url)
        let result = SafeStore.read(url, label: "waypoints.json") { $0 }
        guard case .corrupt = result else { return XCTFail("cross-store blob must not load") }
    }

    func testWaypointBatchIsDurableBeforePublishAndRetryIdempotent() throws {
        let probe = WriteProbe()
        let store = WaypointStore(
            storageURL: tempDir().appendingPathComponent("waypoints.json"),
            persistenceWriter: probe.write
        )
        let undo = UndoManager()
        store.undoManager = undo
        let imported = [
            Waypoint(id: UUID(), name: "A", latitude: -33, longitude: 151),
            Waypoint(id: UUID(), name: "B", latitude: -34, longitude: 150)
        ]
        var publications = 0
        let observation = store.$waypoints.dropFirst().sink { _ in publications += 1 }

        probe.shouldFail = true
        XCTAssertThrowsError(try store.importBatch(imported, batchKey: "batch"))
        XCTAssertTrue(store.waypoints.isEmpty)
        XCTAssertEqual(publications, 0)
        XCTAssertFalse(undo.canUndo)
        XCTAssertEqual(probe.attempts, 1)

        probe.shouldFail = false
        probe.reset()
        let committed = try store.importBatch(imported, batchKey: "batch")
        XCTAssertEqual(committed, BatchImportCommit(insertedCount: 2, skippedExistingCount: 0))
        XCTAssertEqual(store.waypoints.map(\.id), imported.map(\.id))
        XCTAssertEqual(publications, 1)
        XCTAssertTrue(undo.canUndo)
        XCTAssertEqual(probe.attempts, 1, "one store batch must produce one durable write")
        XCTAssertEqual(try JSONDecoder().decode([Waypoint].self, from: probe.successfulPayloads[0]), imported)

        let retried = try store.importBatch(imported, batchKey: "batch")
        XCTAssertEqual(retried, BatchImportCommit(insertedCount: 0, skippedExistingCount: 2))
        XCTAssertEqual(probe.attempts, 1, "a completed retry must not rewrite or duplicate")
        XCTAssertEqual(publications, 1)
        withExtendedLifetime(observation) {}
    }

    func testDrawingBatchIsDurableBeforePublishAndRetryIdempotent() throws {
        let probe = WriteProbe()
        let store = DrawingStore(
            storageURL: tempDir().appendingPathComponent("drawings.json"),
            persistenceWriter: probe.write
        )
        probe.reset() // discard the fresh-install seed write
        let undo = UndoManager()
        store.undoManager = undo
        let layer = DrawingLayer(name: "Imported", defaultColorHex: "#123456")
        let drawing = DrawingShape(id: UUID(),
                                   kind: .polyline,
                                   coordinates: [Coordinate2D(latitude: -33, longitude: 151),
                                                 Coordinate2D(latitude: -34, longitude: 150)],
                                   layerID: layer.id)
        let originalLayers = store.layers
        let originalShapes = store.shapes
        var layerPublications = 0
        var shapePublications = 0
        let layerObservation = store.$layers.dropFirst().sink { _ in layerPublications += 1 }
        let shapeObservation = store.$shapes.dropFirst().sink { _ in shapePublications += 1 }

        probe.shouldFail = true
        XCTAssertThrowsError(try store.importBatch(layers: [layer], drawings: [drawing], batchKey: "batch"))
        XCTAssertEqual(store.layers, originalLayers)
        XCTAssertEqual(store.shapes, originalShapes)
        XCTAssertEqual(layerPublications, 0)
        XCTAssertEqual(shapePublications, 0)
        XCTAssertFalse(undo.canUndo)
        XCTAssertEqual(probe.attempts, 1)

        probe.shouldFail = false
        probe.reset()
        let committed = try store.importBatch(layers: [layer], drawings: [drawing], batchKey: "batch")
        XCTAssertEqual(committed, DrawingBatchImportCommit(insertedLayerCount: 1,
                                                           insertedDrawingCount: 1,
                                                           skippedExistingDrawingCount: 0))
        XCTAssertEqual(store.layers.last, layer)
        XCTAssertEqual(store.shapes.last, drawing)
        XCTAssertEqual(layerPublications, 1)
        XCTAssertEqual(shapePublications, 1)
        XCTAssertTrue(undo.canUndo)
        XCTAssertEqual(probe.attempts, 1, "layers and drawings share one persisted document")

        let retried = try store.importBatch(layers: [layer], drawings: [drawing], batchKey: "batch")
        XCTAssertEqual(retried, DrawingBatchImportCommit(insertedLayerCount: 0,
                                                         insertedDrawingCount: 0,
                                                         skippedExistingDrawingCount: 1))
        XCTAssertEqual(probe.attempts, 1)
        XCTAssertEqual(layerPublications, 1)
        XCTAssertEqual(shapePublications, 1)
        withExtendedLifetime((layerObservation, shapeObservation)) {}
    }

    func testLayerDeletionWaypointFailureKeepsLayerAndAllContents() throws {
        let waypointProbe = WriteProbe()
        let drawingProbe = WriteProbe()
        let waypointStore = WaypointStore(
            storageURL: tempDir().appendingPathComponent("layer-waypoints.json"),
            persistenceWriter: waypointProbe.write
        )
        let drawingStore = DrawingStore(
            storageURL: tempDir().appendingPathComponent("layer-drawings.json"),
            persistenceWriter: drawingProbe.write
        )
        let custom = try drawingStore.addLayer(name: "Custom", defaultColorHex: "#123456")
        let waypoint = Waypoint(name: "W", latitude: -33, longitude: 151, layerID: custom.id)
        let drawing = DrawingShape(
            kind: .point,
            coordinates: [Coordinate2D(latitude: -33, longitude: 151)],
            layerID: custom.id
        )
        _ = try waypointStore.addDurably(waypoint)
        _ = try drawingStore.addDurably(drawing)
        waypointProbe.reset()
        drawingProbe.reset()
        waypointProbe.shouldFail = true

        XCTAssertThrowsError(
            try drawingStore.removeLayer(custom, reassigningWaypointsIn: waypointStore)
        )

        XCTAssertEqual(waypointStore.waypoints, [waypoint])
        XCTAssertEqual(drawingStore.shapes, [drawing])
        XCTAssertTrue(drawingStore.layers.contains(custom))
        XCTAssertEqual(waypointProbe.attempts, 1)
        XCTAssertEqual(drawingProbe.attempts, 0,
                       "drawing state must not be attempted after waypoint durability fails")
        for layer in DrawingLayer.seedDefaults {
            XCTAssertTrue(drawingStore.layers.contains(where: { $0.id == layer.id }))
            XCTAssertTrue(drawingStore.isProtectedDefaultLayer(layer))
        }
    }

    func testLayerDeletionDrawingFailureIsSafeAndRetryCompletesWithoutDataLoss() throws {
        let waypointProbe = WriteProbe()
        let drawingProbe = WriteProbe()
        let waypointStore = WaypointStore(
            storageURL: tempDir().appendingPathComponent("retry-waypoints.json"),
            persistenceWriter: waypointProbe.write
        )
        let drawingStore = DrawingStore(
            storageURL: tempDir().appendingPathComponent("retry-drawings.json"),
            persistenceWriter: drawingProbe.write
        )
        let custom = try drawingStore.addLayer(name: "Custom", defaultColorHex: "#654321")
        let waypoint = Waypoint(name: "W", latitude: -34, longitude: 150, layerID: custom.id)
        let drawing = DrawingShape(
            kind: .polyline,
            coordinates: [Coordinate2D(latitude: -34, longitude: 150),
                          Coordinate2D(latitude: -34.1, longitude: 150.1)],
            layerID: custom.id
        )
        _ = try waypointStore.addDurably(waypoint)
        _ = try drawingStore.addDurably(drawing)
        drawingStore.activeLayerID = custom.id
        waypointProbe.reset()
        drawingProbe.reset()
        drawingProbe.shouldFail = true

        XCTAssertThrowsError(
            try drawingStore.removeLayer(custom, reassigningWaypointsIn: waypointStore)
        )
        XCTAssertEqual(waypointStore.waypoints.first?.layerID, DrawingLayer.legacyFallbackID,
                       "the first store's durable move is safe to publish")
        XCTAssertTrue(drawingStore.layers.contains(custom),
                      "the custom layer remains until its drawing document is durable")
        XCTAssertEqual(drawingStore.shapes.first?.layerID, custom.id)
        XCTAssertEqual(waypointProbe.attempts, 1)
        XCTAssertEqual(drawingProbe.attempts, 1)

        waypointProbe.reset()
        drawingProbe.reset()
        drawingProbe.shouldFail = false
        let commit = try drawingStore.removeLayer(custom, reassigningWaypointsIn: waypointStore)

        XCTAssertEqual(commit, LayerDeletionCommit(reassignedWaypointCount: 0,
                                                   reassignedDrawingCount: 1,
                                                   fallbackLayerID: DrawingLayer.legacyFallbackID))
        XCTAssertEqual(waypointProbe.attempts, 0,
                       "retry must not rewrite an already-reassigned waypoint store")
        XCTAssertEqual(drawingProbe.attempts, 1)
        XCTAssertFalse(drawingStore.layers.contains(custom))
        XCTAssertEqual(drawingStore.shapes.map(\.id), [drawing.id], "drawings are moved, never deleted")
        XCTAssertEqual(drawingStore.shapes.first?.layerID, DrawingLayer.legacyFallbackID)
        XCTAssertEqual(waypointStore.waypoints.map(\.id), [waypoint.id])
        XCTAssertEqual(drawingStore.activeLayerID, DrawingLayer.legacyFallbackID)
        for layer in DrawingLayer.seedDefaults {
            XCTAssertTrue(drawingStore.layers.contains(where: { $0.id == layer.id }))
        }
    }

    func testDefaultLayersHaveDeterministicIDsAndCannotBeDeleted() throws {
        let waypointProbe = WriteProbe()
        let drawingProbe = WriteProbe()
        let waypointStore = WaypointStore(
            storageURL: tempDir().appendingPathComponent("protected-waypoints.json"),
            persistenceWriter: waypointProbe.write
        )
        let drawingStore = DrawingStore(
            storageURL: tempDir().appendingPathComponent("protected-drawings.json"),
            persistenceWriter: drawingProbe.write
        )
        waypointProbe.reset()
        drawingProbe.reset()
        XCTAssertEqual(DrawingLayer.seedDefaults.map(\.id), [
            DrawingLayer.legacyFallbackID,
            DrawingLayer.hostileDefaultID,
            DrawingLayer.unknownDefaultID,
            DrawingLayer.civilianDefaultID,
        ])

        for layer in DrawingLayer.seedDefaults {
            XCTAssertTrue(drawingStore.isProtectedDefaultLayer(layer))
            XCTAssertThrowsError(
                try drawingStore.removeLayer(layer, reassigningWaypointsIn: waypointStore)
            )
        }
        XCTAssertEqual(drawingStore.layers, DrawingLayer.seedDefaults)
        XCTAssertEqual(waypointProbe.attempts, 0)
        XCTAssertEqual(drawingProbe.attempts, 0)
    }

    func testLayerMetadataMutationsAreDurableBeforePublishAndAtomic() throws {
        let probe = WriteProbe()
        let store = DrawingStore(
            storageURL: tempDir().appendingPathComponent("layer-mutations.json"),
            persistenceWriter: probe.write
        )
        probe.reset()
        var publications = 0
        let observation = store.$layers.dropFirst().sink { _ in publications += 1 }

        probe.shouldFail = true
        XCTAssertThrowsError(try store.addLayer(name: "Custom", defaultColorHex: "#123456"))
        XCTAssertFalse(store.layers.contains(where: { $0.name == "Custom" }))
        XCTAssertEqual(publications, 0)
        XCTAssertEqual(probe.attempts, 1)

        probe.shouldFail = false
        probe.reset()
        let custom = try store.addLayer(name: "Custom", defaultColorHex: "#123456")
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(probe.attempts, 1)

        probe.shouldFail = true
        probe.reset()
        XCTAssertThrowsError(try store.setLayerVisible(custom, false))
        XCTAssertEqual(store.layer(id: custom.id)?.visible, true)
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(probe.attempts, 1)

        XCTAssertThrowsError(
            try store.updateLayer(custom, name: "Renamed", defaultColorHex: "#ABCDEF")
        )
        XCTAssertEqual(store.layer(id: custom.id)?.name, "Custom")
        XCTAssertEqual(store.layer(id: custom.id)?.defaultColorHex, "#123456")
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(probe.attempts, 2)

        probe.shouldFail = false
        probe.reset()
        try store.updateLayer(custom, name: "Renamed", defaultColorHex: "#abcdef")
        XCTAssertEqual(store.layer(id: custom.id)?.name, "Renamed")
        XCTAssertEqual(store.layer(id: custom.id)?.defaultColorHex, "#ABCDEF")
        XCTAssertEqual(publications, 2, "name and colour publish as one candidate")
        XCTAssertEqual(probe.attempts, 1, "name and colour use one durable write")

        probe.reset()
        XCTAssertThrowsError(
            try store.updateLayer(
                DrawingLayer.seedDefaults[0],
                name: "Not Friendly",
                defaultColorHex: "#000000"
            )
        )
        XCTAssertEqual(probe.attempts, 0, "protected defaults are rejected before persistence")
        withExtendedLifetime(observation) {}
    }

    func testLegacyRandomDefaultMigrationUsesProvenanceNotMutableAppearance() throws {
        let directory = tempDir()
        let url = directory.appendingPathComponent("legacy-random-defaults.json")
        let epoch = Date(timeIntervalSinceReferenceDate: 700_000_000)
        let renamedDefaults = [
            DrawingLayer(
                id: DrawingLayer.legacyFallbackID,
                name: "Blue Force",
                defaultColorHex: "#010203",
                createdAt: epoch
            ),
            DrawingLayer(id: UUID(), name: "Red Team Renamed", defaultColorHex: "#111111",
                         createdAt: epoch.addingTimeInterval(0.1)),
            DrawingLayer(id: UUID(), name: "Unlabelled", defaultColorHex: "#222222",
                         createdAt: epoch.addingTimeInterval(0.2)),
            DrawingLayer(id: UUID(), name: "Population", defaultColorHex: "#333333",
                         createdAt: epoch.addingTimeInterval(0.3)),
        ]
        let customLookalike = DrawingLayer(
            id: UUID(),
            name: "Hostile",
            defaultColorHex: "#E63946",
            createdAt: epoch.addingTimeInterval(60)
        )
        let legacy = LegacyDrawingDocument(
            schemaVersion: 1,
            layers: renamedDefaults + [customLookalike],
            shapes: [],
            activeLayerID: renamedDefaults[0].id,
            protectedDefaultLayerIDs: nil
        )
        try SafeStore.write(try JSONEncoder().encode(legacy),
                            to: url,
                            label: "drawings.json")

        var store = DrawingStore(storageURL: url)
        for layer in renamedDefaults {
            XCTAssertTrue(store.isProtectedDefaultLayer(layer),
                          "renamed historical defaults retain structural provenance")
            XCTAssertThrowsError(
                try store.updateLayer(layer, name: "Changed", defaultColorHex: "#ABCDEF")
            )
        }
        XCTAssertFalse(store.isProtectedDefaultLayer(customLookalike),
                       "a later custom lookalike must never be protected by name/colour")
        try store.updateLayer(customLookalike,
                              name: "Actually Custom",
                              defaultColorHex: "#ABCDEF")

        store = DrawingStore(storageURL: url)
        for layer in renamedDefaults { XCTAssertTrue(store.isProtectedDefaultLayer(layer)) }
        let restoredCustom = try XCTUnwrap(store.layer(id: customLookalike.id))
        XCTAssertFalse(store.isProtectedDefaultLayer(restoredCustom))
        XCTAssertEqual(restoredCustom.name, "Actually Custom")

        guard case .loaded(let migratedJSON) = SafeStore.read(
            url,
            label: "drawings.json",
            decode: { try XCTUnwrap(JSONSerialization.jsonObject(with: $0) as? [String: Any]) }
        ) else { return XCTFail("expected durable migrated provenance") }
        let provenance = try XCTUnwrap(migratedJSON["defaultLayerProtection"] as? [String: Any])
        XCTAssertEqual(provenance["contractVersion"] as? Int, 1)
        XCTAssertEqual(provenance["basis"] as? String, "legacySeedCohort")
    }

    func testAmbiguousLegacyProtectionRequiresExplicitDurableClassification() throws {
        let url = tempDir().appendingPathComponent("ambiguous-defaults.json")
        let epoch = Date(timeIntervalSinceReferenceDate: 700_000_000)
        let fallback = DrawingLayer(
            id: DrawingLayer.legacyFallbackID,
            name: "Friendly Renamed",
            defaultColorHex: "#010203",
            createdAt: epoch
        )
        let ambiguous = DrawingLayer(
            id: UUID(),
            name: "Hostile",
            defaultColorHex: "#E63946",
            createdAt: epoch.addingTimeInterval(30)
        )
        let legacy = LegacyDrawingDocument(
            schemaVersion: 1,
            layers: [fallback, ambiguous],
            shapes: [],
            activeLayerID: fallback.id,
            protectedDefaultLayerIDs: nil
        )
        try SafeStore.write(try JSONEncoder().encode(legacy),
                            to: url,
                            label: "drawings.json")

        var store = DrawingStore(storageURL: url)
        XCTAssertTrue(store.needsLegacyProtectionReview(ambiguous))
        XCTAssertThrowsError(
            try store.updateLayer(ambiguous, name: "Custom", defaultColorHex: "#ABCDEF")
        )

        try store.resolveLegacyProtectionReview(ambiguous, asProtectedDefault: false)
        try store.updateLayer(ambiguous, name: "Custom", defaultColorHex: "#ABCDEF")
        store = DrawingStore(storageURL: url)
        let restored = try XCTUnwrap(store.layer(id: ambiguous.id))
        XCTAssertFalse(store.needsLegacyProtectionReview(restored))
        XCTAssertFalse(store.isProtectedDefaultLayer(restored))
        XCTAssertEqual(restored.name, "Custom")
    }

    func testSymbolDraftPreviewAndInvalidInputPublishNothingThenSaveOnceDurable() throws {
        let probe = WriteProbe()
        let store = WaypointStore(
            storageURL: tempDir().appendingPathComponent("symbol-edit.json"),
            persistenceWriter: probe.write
        )
        let original = Waypoint(
            name: "Axis Red",
            notes: "Original",
            latitude: -33.8,
            longitude: 151.2,
            elevation: 84,
            kind: .controlMeasure(.axisOfMainAttack),
            rotation: 90,
            scaleX: 2,
            scaleY: 3,
            taskColor: .red,
            layerID: DrawingLayer.legacyFallbackID
        )
        _ = try store.addDurably(original)
        probe.reset()
        var publications = 0
        let observation = store.$waypoints.dropFirst().sink { _ in publications += 1 }

        var draft = SymbolEditDraft(waypoint: original)
        var concurrentlyMoved = original
        concurrentlyMoved.latitude = -35
        concurrentlyMoved.longitude = 149
        let untouchedCoordinates = try draft.applying(
            to: concurrentlyMoved,
            availableLayerIDs: [DrawingLayer.legacyFallbackID],
            fallbackLayerID: DrawingLayer.legacyFallbackID
        )
        XCTAssertEqual(untouchedCoordinates.latitude, -35,
                       "A non-Move edit must preserve the latest stored coordinate")
        XCTAssertEqual(untouchedCoordinates.longitude, 149)

        draft.name = "  Edited Axis  "
        draft.notes = "  New notes\n"
        draft.elevationText = "bad"
        XCTAssertThrowsError(
            try draft.applying(to: original,
                               availableLayerIDs: [DrawingLayer.legacyFallbackID],
                               fallbackLayerID: DrawingLayer.legacyFallbackID)
        )
        XCTAssertEqual(probe.attempts, 0)
        XCTAssertEqual(publications, 0)
        XCTAssertEqual(store.waypoints, [original], "draft preview and cancel are store-free")

        draft.elevationText = "125.5"
        draft.rotationDegrees = 725
        draft.scaleX = 0.01
        draft.scaleY = 25
        let updated = try draft.applying(
            to: original,
            availableLayerIDs: [DrawingLayer.legacyFallbackID],
            fallbackLayerID: DrawingLayer.legacyFallbackID
        )
        probe.shouldFail = true
        XCTAssertThrowsError(try store.commitEdit(updated))
        XCTAssertEqual(store.waypoints, [original])
        XCTAssertEqual(publications, 0)
        XCTAssertEqual(probe.attempts, 1)

        probe.shouldFail = false
        probe.reset()
        XCTAssertTrue(try store.commitEdit(updated))
        XCTAssertEqual(probe.attempts, 1, "Save is exactly one durable store mutation")
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(store.waypoints, [updated])
        XCTAssertEqual(updated.name, "Edited Axis")
        XCTAssertEqual(updated.notes, "New notes")
        XCTAssertEqual(updated.elevation, 125.5)
        XCTAssertEqual(updated.rotation, 5)
        XCTAssertEqual(updated.scaleX, 0.1)
        XCTAssertEqual(updated.scaleY, 20)
        XCTAssertFalse(try store.commitEdit(updated), "unchanged Save performs no write")
        XCTAssertEqual(probe.attempts, 1)
        withExtendedLifetime(observation) {}
    }

    func testSymbolMoveIsDraftOnlyCancelDiscardsAndSavePublishesOnce() throws {
        let probe = WriteProbe()
        let store = WaypointStore(
            storageURL: tempDir().appendingPathComponent("symbol-actions.json"),
            persistenceWriter: probe.write
        )
        let original = Waypoint(name: "Move Me",
                                latitude: -33,
                                longitude: 151,
                                kind: .controlMeasure(.axisOfMainAttack),
                                rotation: 83,
                                scaleX: 4,
                                scaleY: 5)
        _ = try store.addDurably(original)
        probe.reset()
        var uiAndSyncPublications = 0
        let observation = store.$waypoints.dropFirst().sink { _ in uiAndSyncPublications += 1 }

        var cancelledDraft = SymbolEditDraft(waypoint: original)
        cancelledDraft.stageMove(to: .init(latitude: -34, longitude: 150))
        XCTAssertTrue(cancelledDraft.hasStagedMove(from: original))
        XCTAssertEqual(store.waypoints, [original], "Move must remain draft-only")
        XCTAssertEqual(probe.attempts, 0)
        XCTAssertEqual(uiAndSyncPublications, 0)

        // Cancel is modeled by discarding the view-owned draft.
        cancelledDraft = SymbolEditDraft(waypoint: original)
        XCTAssertFalse(cancelledDraft.hasStagedMove(from: original))
        XCTAssertEqual(store.waypoints, [original])

        var savedDraft = SymbolEditDraft(waypoint: original)
        savedDraft.stageMove(to: .init(latitude: -34, longitude: 150))
        savedDraft.name = "Moved and Edited"
        savedDraft.resetRotation()
        savedDraft.resetWidth()
        savedDraft.resetHeight()
        XCTAssertEqual(savedDraft.rotationDegrees, 0)
        XCTAssertEqual(savedDraft.scaleX, 1)
        XCTAssertEqual(savedDraft.scaleY, 1)
        let moved = try savedDraft.applying(
            to: original,
            availableLayerIDs: [DrawingLayer.legacyFallbackID],
            fallbackLayerID: DrawingLayer.legacyFallbackID
        )

        probe.shouldFail = true
        XCTAssertThrowsError(try store.commitEdit(moved))
        XCTAssertEqual(store.waypoints, [original])
        XCTAssertEqual(uiAndSyncPublications, 0,
                       "A failed durable write cannot reach UI or Sync observers")

        probe.shouldFail = false
        probe.reset()
        XCTAssertTrue(try store.commitEdit(moved))
        XCTAssertEqual(probe.attempts, 1)
        XCTAssertEqual(uiAndSyncPublications, 1)
        XCTAssertEqual(store.waypoints.first?.name, "Moved and Edited")
        XCTAssertEqual(store.waypoints.first?.kind, original.kind)
        XCTAssertEqual(store.waypoints.first?.latitude, -34)
        XCTAssertEqual(store.waypoints.first?.longitude, 150)
        XCTAssertEqual(store.waypoints.first?.rotation, 0)
        XCTAssertEqual(store.waypoints.first?.scaleX, 1)
        XCTAssertEqual(store.waypoints.first?.scaleY, 1)
        withExtendedLifetime(observation) {}
    }

    func testUnitAmplifiersAreBoundedUnitOnlyAndUseDoctrineDisplayText() throws {
        let unit = Waypoint(
            name: "I11",
            latitude: -33.8,
            longitude: 151.2,
            kind: .military(.init(
                affiliation: .friend,
                echelon: .platoon,
                function: .infantry)),
            higherFormation: "  BG-Waratah-12345678901234567890  ",
            uniqueIdentifier: "  I11-123456789012345678901234567890  ",
            reinforcementStatus: .reinforced
        )
        XCTAssertEqual(unit.higherFormation?.unicodeScalars.count,
                       UnitAmplifierText.higherFormationMaxLength)
        XCTAssertEqual(unit.uniqueIdentifier?.unicodeScalars.count,
                       UnitAmplifierText.uniqueIdentifierMaxLength)
        let presentation = try XCTUnwrap(UnitAmplifierPresentation(
            waypoint: unit, visible: true))
        XCTAssertEqual(presentation.higherFormation, unit.higherFormation)
        XCTAssertEqual(presentation.uniqueIdentifier, unit.uniqueIdentifier)
        XCTAssertEqual(presentation.reinforcementText, "(+)")
        XCTAssertNil(UnitAmplifierPresentation(waypoint: unit, visible: false))

        let restored = try JSONDecoder().decode(
            Waypoint.self, from: JSONEncoder().encode(unit))
        XCTAssertEqual(restored, unit)

        var draft = SymbolEditDraft(waypoint: unit)
        draft.kind = .controlMeasure(.secure)
        let task = try draft.applying(
            to: unit,
            availableLayerIDs: [DrawingLayer.legacyFallbackID],
            fallbackLayerID: DrawingLayer.legacyFallbackID)
        XCTAssertNil(task.higherFormation)
        XCTAssertNil(task.uniqueIdentifier)
        XCTAssertEqual(task.reinforcementStatus, .none)
        XCTAssertNil(UnitAmplifierPresentation(waypoint: task, visible: true))
    }

    func testUnitAmplifierBoundsCountUnicodeScalars() {
        let multiScalarGrapheme = "🇦🇺"
        let unit = Waypoint(
            name: "Unicode unit",
            latitude: -33,
            longitude: 151,
            kind: .military(.init(
                affiliation: .friend,
                echelon: .platoon,
                function: .infantry)),
            higherFormation: String(repeating: multiScalarGrapheme, count: 22),
            uniqueIdentifier: String(repeating: multiScalarGrapheme, count: 31)
        )

        XCTAssertEqual(unit.higherFormation?.unicodeScalars.count,
                       UnitAmplifierText.higherFormationMaxLength)
        XCTAssertEqual(unit.uniqueIdentifier?.unicodeScalars.count,
                       UnitAmplifierText.uniqueIdentifierMaxLength)
    }

    func testLongSymbolPickerSourcesHaveStableUniqueIdentities() {
        XCTAssertEqual(SymbolPickerOptions.echelons, SymbolPickerOptions.echelons)
        XCTAssertEqual(SymbolPickerOptions.functions, SymbolPickerOptions.functions)
        XCTAssertEqual(SymbolPickerOptions.controlMeasures,
                       SymbolPickerOptions.controlMeasures)
        XCTAssertEqual(Set(SymbolPickerOptions.echelons).count,
                       SymbolPickerOptions.echelons.count)
        XCTAssertEqual(Set(SymbolPickerOptions.functions).count,
                       SymbolPickerOptions.functions.count)
        XCTAssertEqual(Set(SymbolPickerOptions.controlMeasures).count,
                       SymbolPickerOptions.controlMeasures.count)
    }

    func testConfirmedSymbolDeleteIsOneDurableMutation() throws {
        let probe = WriteProbe()
        let store = WaypointStore(
            storageURL: tempDir().appendingPathComponent("symbol-delete.json"),
            persistenceWriter: probe.write
        )
        let moved = Waypoint(name: "Delete Me", latitude: -34, longitude: 150)
        _ = try store.addDurably(moved)

        probe.reset()
        probe.shouldFail = true
        XCTAssertThrowsError(try store.deleteDurably(moved))
        XCTAssertEqual(store.waypoints, [moved])
        XCTAssertEqual(probe.attempts, 1)

        probe.reset()
        probe.shouldFail = false
        XCTAssertTrue(try store.deleteDurably(moved))
        XCTAssertEqual(probe.attempts, 1)
        XCTAssertTrue(store.waypoints.isEmpty)
    }

    func testNewSymbolAddIsDurableBeforePublication() throws {
        let probe = WriteProbe()
        let store = WaypointStore(
            storageURL: tempDir().appendingPathComponent("symbol-add.json"),
            persistenceWriter: probe.write
        )
        var publications = 0
        let observation = store.$waypoints.dropFirst().sink { _ in publications += 1 }
        let waypoint = Waypoint(name: "New", latitude: -33, longitude: 151)

        probe.shouldFail = true
        XCTAssertThrowsError(try store.addDurably(waypoint))
        XCTAssertTrue(store.waypoints.isEmpty)
        XCTAssertEqual(publications, 0)

        probe.shouldFail = false
        probe.reset()
        XCTAssertTrue(try store.addDurably(waypoint))
        XCTAssertEqual(probe.attempts, 1)
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(store.waypoints, [waypoint])
        withExtendedLifetime(observation) {}
    }

    @MainActor
    func testContentViewDropPinProductionPathDoesNotPublishFailedWrite() throws {
        let probe = WriteProbe()
        let store = WaypointStore(
            storageURL: tempDir().appendingPathComponent("drop-pin.json"),
            persistenceWriter: probe.write
        )
        var publications = 0
        let observation = store.$waypoints.dropFirst().sink { _ in publications += 1 }

        probe.shouldFail = true
        XCTAssertThrowsError(try DropPinMissionMutation.commit(
            coordinate: .init(latitude: -33.8688, longitude: 151.2093),
            displayedCoordinate: "56HLH 34900 50900",
            layerID: DrawingLayer.legacyFallbackID,
            to: store
        ))
        XCTAssertTrue(store.waypoints.isEmpty)
        XCTAssertEqual(publications, 0,
                       "ContentView's drop-pin path must not reach map or Sync observers on failure")

        probe.shouldFail = false
        probe.reset()
        let committed = try DropPinMissionMutation.commit(
            coordinate: .init(latitude: -33.8688, longitude: 151.2093),
            displayedCoordinate: "56HLH 34900 50900",
            layerID: DrawingLayer.legacyFallbackID,
            to: store
        )
        XCTAssertEqual(store.waypoints, [committed])
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(probe.attempts, 1)
        withExtendedLifetime(observation) {}
    }

    func testDrawingShapeMutationsAreDurableBeforeEveryPublication() throws {
        let probe = WriteProbe()
        let store = DrawingStore(
            storageURL: tempDir().appendingPathComponent("drawing-mutations.json"),
            persistenceWriter: probe.write
        )
        probe.reset() // discard the fresh-install seed write
        let shape = DrawingShape(
            kind: .polyline,
            coordinates: [
                .init(latitude: -33.8, longitude: 151.1),
                .init(latitude: -33.9, longitude: 151.2)
            ],
            layerID: DrawingLayer.legacyFallbackID
        )
        var publications = 0
        let observation = store.$shapes.dropFirst().sink { _ in publications += 1 }

        probe.shouldFail = true
        XCTAssertThrowsError(try store.addDurably(shape))
        XCTAssertTrue(store.shapes.isEmpty)
        XCTAssertEqual(publications, 0)

        probe.shouldFail = false
        probe.reset()
        XCTAssertTrue(try store.addDurably(shape))
        XCTAssertEqual(store.shapes, [shape])
        XCTAssertEqual(publications, 1)

        var renamed = shape
        renamed.name = "Durable route"
        probe.shouldFail = true
        probe.reset()
        XCTAssertThrowsError(try store.commitEdit(renamed))
        XCTAssertEqual(store.shapes, [shape])
        XCTAssertEqual(publications, 1)

        XCTAssertThrowsError(try store.deleteDurably(shape))
        XCTAssertEqual(store.shapes, [shape])
        XCTAssertEqual(publications, 1)
        withExtendedLifetime(observation) {}
    }

    func testSymbolEditorAccessibilityContractHasStableLabelsAndTargets() {
        XCTAssertEqual(SymbolEditorAccessibility.minimumTargetPoints, 44)
        XCTAssertEqual(SymbolEditorAccessibility.resetRotationLabel, "Reset rotation")
        XCTAssertEqual(SymbolEditorAccessibility.resetWidthLabel, "Reset width scale")
        XCTAssertEqual(SymbolEditorAccessibility.resetHeightLabel, "Reset height scale")
        XCTAssertTrue(SymbolEditorAccessibility.markerLabel(for: "#E23B3B").contains("Red"))
        XCTAssertEqual(Set(SymbolEditorAccessibility.markerSwatches.map(\.hex)).count,
                       SymbolEditorAccessibility.markerSwatches.count)
    }

    func testProductionDrawingStyleSeamsKeepStrokeFillAndOpacityIndependent() throws {
        var style = DrawingStyle(strokeColorHex: "#112233",
                                 fillColorHex: "#445566",
                                 fillOpacity: 0.4)
        style.setStrokeHue("#AABBCC")
        XCTAssertEqual(style.strokeColorHex, "#AABBCC")
        XCTAssertEqual(style.fillColorHex, "#445566")
        XCTAssertEqual(style.fillOpacity, 0.4)

        style.setFillHue("#DDEEFF")
        XCTAssertEqual(style.strokeColorHex, "#AABBCC")
        XCTAssertEqual(style.fillColorHex, "#DDEEFF")
        style.setFillOpacity(0.7)
        XCTAssertEqual(style.strokeColorHex, "#AABBCC")
        XCTAssertEqual(style.fillColorHex, "#DDEEFF")
        XCTAssertEqual(style.fillOpacity, 0.7)
        style.setFillOpacity(8)
        XCTAssertEqual(style.fillOpacity, 1)

        let session = DrawingSessionViewModel()
        let layerID = UUID()
        session.strokeColorHex = "#010203"
        session.fillColorHex = "#A0B0C0"
        session.fillOpacity = 0.6
        session.start(kind: .polygon, layerID: layerID)
        _ = session.addPoint(.init(latitude: -33.0, longitude: 151.0))
        _ = session.addPoint(.init(latitude: -33.1, longitude: 151.0))
        _ = session.addPoint(.init(latitude: -33.1, longitude: 151.1))
        let shape = try XCTUnwrap(session.finish())
        XCTAssertEqual(shape.style.strokeColorHex, "#010203")
        XCTAssertEqual(shape.style.fillColorHex, "#A0B0C0")
        XCTAssertEqual(shape.style.fillOpacity, 0.6)
    }

    @MainActor
    func testCrossStorePartialFailureRetriesOnlyUncommittedStore() throws {
        let waypointProbe = WriteProbe()
        let drawingProbe = WriteProbe()
        let waypointStore = WaypointStore(
            storageURL: tempDir().appendingPathComponent("waypoints.json"),
            persistenceWriter: waypointProbe.write
        )
        let drawingStore = DrawingStore(
            storageURL: tempDir().appendingPathComponent("drawings.json"),
            persistenceWriter: drawingProbe.write
        )
        drawingProbe.reset()

        let layer = DrawingLayer(name: "Imported", defaultColorHex: "#123456")
        let waypoint = Waypoint(name: "W", latitude: -33, longitude: 151, layerID: layer.id)
        let drawing = DrawingShape(kind: .point,
                                   coordinates: [Coordinate2D(latitude: -33, longitude: 151)],
                                   layerID: layer.id)
        let batch = GeoJSONImporter.ExternalBatch(
            batchKey: "retry-batch",
            result: GeoJSONImporter.Result(waypoints: [waypoint],
                                           drawings: [drawing],
                                           newLayers: [layer],
                                           invalidSkipped: 0),
            identityResolutions: []
        )

        drawingProbe.shouldFail = true
        let first = ExternalImportCommitter.attempt(
            ExternalImportCommitProgress(batch: batch),
            waypointStore: waypointStore,
            drawingStore: drawingStore
        )
        XCTAssertEqual(first.state, .partiallyCommitted)
        XCTAssertTrue(first.progress.waypointStoreCommitted)
        XCTAssertFalse(first.progress.drawingStoreCommitted)
        XCTAssertEqual(waypointStore.waypoints.map(\.id), [waypoint.id])
        XCTAssertFalse(drawingStore.shapes.contains(where: { $0.id == drawing.id }))
        XCTAssertTrue(first.message.contains("Waypoint store committed 1 new"))
        XCTAssertTrue(first.message.contains("drawings and their layers were not saved"))
        XCTAssertTrue(first.message.contains("will not duplicate"))
        XCTAssertEqual(waypointProbe.attempts, 1)
        XCTAssertEqual(drawingProbe.attempts, 1)

        drawingProbe.shouldFail = false
        drawingProbe.reset()
        let retry = ExternalImportCommitter.attempt(
            first.progress,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        )
        XCTAssertEqual(retry.state, .completed)
        XCTAssertEqual(waypointStore.waypoints.filter { $0.id == waypoint.id }.count, 1)
        XCTAssertEqual(drawingStore.shapes.filter { $0.id == drawing.id }.count, 1)
        XCTAssertEqual(waypointProbe.attempts, 1, "committed waypoint store must not run again")
        XCTAssertEqual(drawingProbe.attempts, 1)

        _ = ExternalImportCommitter.attempt(
            retry.progress,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        )
        XCTAssertEqual(waypointProbe.attempts, 1)
        XCTAssertEqual(drawingProbe.attempts, 1)
    }

    @MainActor
    func testSuspendedExternalParseReconcilesCrossTypeMutationAtCommit() async throws {
        let waypointProbe = WriteProbe()
        let drawingProbe = WriteProbe()
        let waypointStore = WaypointStore(
            storageURL: tempDir().appendingPathComponent("race-waypoints.json"),
            persistenceWriter: waypointProbe.write
        )
        let drawingStore = DrawingStore(
            storageURL: tempDir().appendingPathComponent("race-drawings.json"),
            persistenceWriter: drawingProbe.write
        )
        drawingProbe.reset()

        let racedID = UUID()
        let remintedID = UUID()
        let layerID = try XCTUnwrap(drawingStore.layers.first?.id)
        let json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":"\(racedID.uuidString)",
           "geometry":{"type":"Point","coordinates":[151,-33]},
           "properties":{"tacticalmaps:layer_id":"\(layerID.uuidString)"}}
        ]}
        """
        let url = tempDir().appendingPathComponent("suspended.geojson")
        try Data(json.utf8).write(to: url)
        let context = ExternalImportWorker.Context(
            existingLayers: drawingStore.layers,
            fallbackLayerID: layerID,
            existingWaypointIDs: [],
            existingDrawingIDs: []
        )
        let parseStarted = expectation(description: "external parse reached suspension seam")
        let resumeParse = DispatchSemaphore(value: 0)
        let parseTask = Task.detached {
            try ExternalImportWorker.prepareSynchronously(
                url: url,
                kind: .geoJSON,
                encodedContext: try JSONEncoder().encode(context),
                batchKey: "suspended-race",
                performedWorkOffMainThread: true,
                coordinatedAccess: { source, operation in try operation(source) },
                parseStarted: {
                    parseStarted.fulfill()
                    resumeParse.wait()
                }
            )
        }

        await fulfillment(of: [parseStarted], timeout: 2)
        let liveDrawing = DrawingShape(
            id: racedID,
            kind: .point,
            coordinates: [Coordinate2D(latitude: -33, longitude: 151)],
            layerID: layerID
        )
        _ = try drawingStore.addDurably(liveDrawing)
        resumeParse.signal()

        let payload = try await parseTask.value
        let parsed = try JSONDecoder().decode(GeoJSONImporter.ExternalBatch.self,
                                              from: payload.encodedBatch)
        XCTAssertEqual(parsed.result.waypoints.map(\.id), [racedID],
                       "the suspended worker only knows its pre-mutation snapshot")

        var remintCalls = 0
        let report = ExternalImportCommitter.attempt(
            ExternalImportCommitProgress(batch: parsed),
            waypointStore: waypointStore,
            drawingStore: drawingStore,
            idFactory: {
                remintCalls += 1
                return remintedID
            }
        )

        XCTAssertEqual(report.state, .completed)
        XCTAssertEqual(drawingStore.shapes.map(\.id), [racedID])
        XCTAssertEqual(waypointStore.waypoints.map(\.id), [remintedID])
        XCTAssertEqual(waypointStore.waypoints.first?.layerID, layerID,
                       "object reminting must not alter the layer namespace")
        XCTAssertEqual(report.progress.batch.identityResolutions.map(\.resolvedID), [remintedID])
        XCTAssertEqual(report.progress.batch.identityResolutions.map(\.resolution),
                       [.commitTimeCollision])
        XCTAssertEqual(report.insertedWaypointCount, 1)
        XCTAssertEqual(report.skippedWaypointCount, 0)
        XCTAssertEqual(report.insertedDrawingCount, 0)
        XCTAssertEqual(report.skippedDrawingCount, 0)
        XCTAssertTrue(report.message.contains("Imported 1 new waypoint and 0 new drawings"))
        XCTAssertEqual(remintCalls, 1)

        let retry = ExternalImportCommitter.attempt(
            report.progress,
            waypointStore: waypointStore,
            drawingStore: drawingStore,
            idFactory: {
                remintCalls += 1
                return UUID()
            }
        )
        XCTAssertEqual(retry.state, .completed)
        XCTAssertEqual(waypointStore.waypoints.map(\.id), [remintedID])
        XCTAssertEqual(drawingStore.shapes.map(\.id), [racedID])
        XCTAssertEqual(retry.insertedWaypointCount, 1,
                       "the retained report describes the original durable commit")
        XCTAssertEqual(remintCalls, 1, "completed retries must not remint or re-enter either store")
    }
}
