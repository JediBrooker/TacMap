import Foundation
import CryptoKit

// Per-record verdicts for v3 snapshot items and live put/del (contract
// section 2 + 3). A record that fails a per-record check is skipped instead
// of failing the whole snapshot: the relay can already omit any record, so
// skipping one with no side effects gives it nothing new. Structural and
// fence problems stay fatal and are handled by the manager, not here.

struct SyncValidatedRecordV3 {
    let mutation: SyncReplayState.DurableMutation
    let parsed: GeoJSONImporter.Result?
    let localId: String?
    let expectedModelHash: String?
}

enum SnapshotSkipCategory: String, Equatable {
    /// failed binding / AEAD / signature / shape, could be garbage or an attack
    case unverified
    /// authentic, but this build cant use it (newer kind, importer said no...)
    case unsupported
}

enum SnapshotRecordVerdict {
    case valid(SyncValidatedRecordV3)
    case skip(SnapshotSkipCategory, reason: String)

    var skipCategory: SnapshotSkipCategory? {
        if case .skip(let category, _) = self { return category }
        return nil
    }
}

/// Everything the classifier needs, captured up front so it stays pure.
struct SnapshotRecordContext {
    let keys: SyncCrypto.V3RoomKeys
    let roomKey: SymmetricKey
    let actorKeyIsAcceptable: (_ actorId: String, _ publicKey: String) -> Bool
    /// committed layers, or the staged set while walking a snapshot
    let layers: [DrawingLayer]
    let fallbackLayerID: UUID
    /// O(1) membership, a Set copy per live frame was O(N) (S3-10)
    let isWaypointID: (UUID) -> Bool
    let isDrawingID: (UUID) -> Bool
    let localIdForWireId: (String) -> String?
    /// wire id of a local UUID, from the room's index (or one reused HMAC
    /// key off main) instead of rebuilding the key per record
    let wireIdForUUID: (UUID) -> String

    func withLayers(_ staged: [DrawingLayer]) -> SnapshotRecordContext {
        SnapshotRecordContext(
            keys: keys, roomKey: roomKey, actorKeyIsAcceptable: actorKeyIsAcceptable,
            layers: staged, fallbackLayerID: fallbackLayerID,
            isWaypointID: isWaypointID, isDrawingID: isDrawingID,
            localIdForWireId: localIdForWireId, wireIdForUUID: wireIdForUUID)
    }
}

enum SnapshotRecordClassifier {
    static let kindPattern = "^[A-Za-z0-9_-]{1,32}$"
    static let maxCiphertextChars = 1_048_576
    static let minCiphertextBytes = 28

    static func classify(
        _ rec: [String: Any],
        deleted: Bool,
        context: SnapshotRecordContext
    ) -> SnapshotRecordVerdict {
        func unverified(_ reason: String) -> SnapshotRecordVerdict { .skip(.unverified, reason: reason) }
        func unsupported(_ reason: String) -> SnapshotRecordVerdict { .skip(.unsupported, reason: reason) }

        guard let wireId = rec["id"] as? String,
              SyncIdentity.decodeCanonical32(wireId) != nil else {
            return unverified("item_id_not_canonical")
        }
        guard let vsString = rec["vs"] as? String,
              let stamp = VersionStamp.parse(vsString) else { return unverified("vs_unparseable") }
        guard let by = rec["by"] as? String, stamp.actorId == by else {
            return unverified("by_not_equal_vs_actor")
        }
        guard let publicKey = rec["pub"] as? String,
              SyncIdentity.decodeCanonical32(publicKey) != nil,
              let sessionString = rec["sd"] as? String,
              let sessionRaw = SyncIdentity.decodeCanonical32(sessionString) else {
            return unverified("pub_or_sd_not_canonical")
        }
        guard SyncIdentity.actorBindingIsValid(
            actorId: by, publicKey: publicKey, roomIdRaw: context.keys.roomIdRaw
        ) else { return unverified("actor_binding_mismatch") }
        guard context.actorKeyIsAcceptable(by, publicKey) else {
            return unverified("pub_differs_from_pinned_key")
        }
        guard let kind = rec["kind"] as? String,
              kind.range(of: kindPattern, options: .regularExpression) != nil else {
            return unverified("kind_syntax_invalid")
        }
        guard deleted == (kind == "del") else { return unverified("type_and_deleted_inconsistent") }
        guard let ctBase64 = rec["ct"] as? String,
              ctBase64.utf8.count <= maxCiphertextChars,
              let blob = Data(base64Encoded: ctBase64),
              blob.base64EncodedString() == ctBase64,
              blob.count >= minCiphertextBytes else {
            return unverified("ct_not_canonical_or_too_short_or_too_long")
        }
        guard let plain = SyncCrypto.open(
            context.roomKey, blob,
            aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vsString, kind: kind)
        ) else { return unverified("aead_open_failed") }
        guard let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
            return unverified("inner_json_invalid")
        }
        guard let signature = inner["sig"] as? String, !signature.isEmpty else {
            return unverified("signature_missing_or_invalid")
        }
        if deleted, Set(inner.keys) != ["sig"] {
            return unverified("tombstone_inner_has_extra_keys")
        }
        let content = deleted ? "" : (inner["c"] as? String ?? "")
        let contentData = Data(content.utf8)
        let payloadHash = SyncIdentity.sha256(contentData)
        let preimage = SyncIdentity.buildPreimage(
            domain: deleted ? SyncIdentity.domainDelete : SyncIdentity.domainPut,
            roomIdRaw: context.keys.roomIdRaw, actorId: by,
            sessionDomain: sessionRaw,
            counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: wireId, kind: kind, payloadHash: payloadHash)
        guard SyncSigning.verify(publicKey, preimage, signature) else {
            return unverified("signature_missing_or_invalid")
        }

        // Authentic from here on. Anything else is "this build cant use it".
        if deleted {
            return .valid(SyncValidatedRecordV3(
                mutation: .init(wireObjectId: wireId, stamp: stamp, publicKey: publicKey, kind: .delete),
                parsed: nil,
                localId: context.localIdForWireId(wireId),
                expectedModelHash: nil))
        }
        guard kind == "waypoint" || kind == "drawing" else { return unsupported("kind_unknown_but_authentic") }
        guard !content.isEmpty else { return unsupported("put_content_missing_or_empty") }
        guard let imported = try? GeoJSONImporter.parse(
            contentData, existingLayers: context.layers, fallbackLayerID: context.fallbackLayerID
        ) else { return unsupported("importer_failed") }
        guard imported.invalidSkipped == 0 else { return unsupported("importer_invalid_skipped_nonzero") }
        guard imported.waypoints.count + imported.drawings.count == 1 else {
            return unsupported("object_count_not_exactly_one")
        }
        guard kind == "waypoint" ? imported.waypoints.count == 1 : imported.drawings.count == 1 else {
            return unsupported("kind_content_mismatch")
        }
        let embeddedID = kind == "waypoint" ? imported.waypoints[0].id : imported.drawings[0].id
        let computedWireID = context.wireIdForUUID(embeddedID)
        guard computedWireID == wireId else { return unsupported("embedded_uuid_does_not_match_wire_id") }
        let collides = kind == "waypoint"
            ? context.isDrawingID(embeddedID)
            : context.isWaypointID(embeddedID)
        guard !collides else { return unsupported("identity_collision_with_other_object_kind") }
        guard let expected = receiverModelHash(parsed: imported, localId: embeddedID, layers: context.layers) else {
            return unsupported("expected_model_hash_unavailable")
        }
        return .valid(SyncValidatedRecordV3(
            mutation: .init(wireObjectId: wireId, stamp: stamp, publicKey: publicKey,
                            kind: .put(contentHash: SyncIdentity.bytesToHex(payloadHash))),
            parsed: imported,
            localId: embeddedID.uuidString,
            expectedModelHash: expected))
    }

    /// Receiver-local fixed point: what our own exporter will produce once
    /// this record (and its new layers) are applied on top of `layers`.
    static func receiverModelHash(parsed: GeoJSONImporter.Result, localId: UUID, layers base: [DrawingLayer]) -> String? {
        var layers = base
        for layer in parsed.newLayers where !layers.contains(where: { $0.id == layer.id }) {
            layers.append(layer)
        }
        let content: String?
        if let waypoint = parsed.waypoints.first(where: { $0.id == localId }) {
            content = try? GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: layers)
        } else if let drawing = parsed.drawings.first(where: { $0.id == localId }) {
            content = try? GeoJSONExporter.export(waypoints: [], drawings: [drawing], layers: layers)
        } else {
            content = nil
        }
        guard let content else { return nil }
        return SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
    }
}

/// Snapshot items get parsed in order against committed layers plus the
/// layers adopted by earlier items of the same snapshot, first one wins,
/// exactly like the apply step (S3-08). Without this a second record that
/// names an unknown layer differently fails its post-apply hash check.
struct SnapshotLayerStaging {
    private(set) var layers: [DrawingLayer]

    init(committed: [DrawingLayer]) {
        layers = committed
    }

    mutating func adopt(_ newLayers: [DrawingLayer]) {
        for layer in newLayers where !layers.contains(where: { $0.id == layer.id }) {
            layers.append(layer)
        }
    }
}
