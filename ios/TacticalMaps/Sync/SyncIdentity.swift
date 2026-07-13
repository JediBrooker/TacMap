import Foundation
import CryptoKit

/// v3 room-scoped identity derivation and binary preimage construction.
/// Pure + deterministic so it unit-tests against the shared fixture.
enum SyncIdentity {

    static let domainPut: UInt8 = 0x01
    static let domainDelete: UInt8 = 0x02
    static let domainPresence: UInt8 = 0x03
    static let domainHello: UInt8 = 0x04
    static let protocolVersion: UInt8 = 0x03

    /// Room-scoped actor ID: SHA-256("tacmap-actor-v3\0" || roomIdRaw || pubkeyRaw)
    /// -> base64url no pad. Same device in different rooms has different actorIds.
    static func actorId(roomIdRaw: Data, pubkeyRaw: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: Data("tacmap-actor-v3\0".utf8))
        hasher.update(data: roomIdRaw)
        hasher.update(data: pubkeyRaw)
        return Data(hasher.finalize()).base64URLEncodedStringNoPad()
    }

    /// Wire object ID: HMAC-SHA256(metadataKey, "tacmap-wire-obj-v3\0" || localUUID_bytes)
    /// -> base64url no pad. The relay never sees the local UUID.
    static func wireObjectId(metadataKey: Data, localUuidBytes: Data) -> String {
        let key = SymmetricKey(data: metadataKey)
        var hmac = HMAC<SHA256>(key: key)
        hmac.update(data: Data("tacmap-wire-obj-v3\0".utf8))
        hmac.update(data: localUuidBytes)
        return Data(hmac.finalize()).base64URLEncodedStringNoPad()
    }

    /// Deterministic binary preimage for Ed25519 signing (ADR-001 section 5).
    static func buildPreimage(
        domain: UInt8,
        roomIdRaw: Data,
        actorId: String,
        sessionDomain: Data,
        counterHex16: String,
        objectId: String,
        kind: String,
        payloadHash: Data
    ) -> Data {
        let actorIdBytes = Data(actorId.utf8)
        let objectIdBytes = Data(objectId.utf8)
        let kindBytes = Data(kind.utf8)
        let counterBytes = Data(counterHex16.utf8)

        var buf = Data()
        buf.reserveCapacity(1 + 1 + 32 + 2 + actorIdBytes.count + 32 + 16 +
                            2 + objectIdBytes.count + 1 + kindBytes.count + 32)
        buf.append(domain)
        buf.append(protocolVersion)
        buf.append(roomIdRaw)
        buf.appendUInt16LE(UInt16(actorIdBytes.count))
        buf.append(actorIdBytes)
        buf.append(sessionDomain)
        buf.append(counterBytes)
        buf.appendUInt16LE(UInt16(objectIdBytes.count))
        buf.append(objectIdBytes)
        buf.append(UInt8(kindBytes.count))
        buf.append(kindBytes)
        buf.append(payloadHash)

        return buf
    }

    static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    static func generateSessionDomain() -> Data {
        var raw = Data(count: 32)
        _ = raw.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        return sha256(raw)
    }

    /// Convert a UUID to its 16 raw bytes (big-endian).
    static func uuidToBytes(_ uuid: UUID) -> Data {
        withUnsafeBytes(of: uuid.uuid) { Data($0) }
    }

    static func hexToBytes(_ hex: String) -> Data {
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<nextIndex], radix: 16) else { break }
            data.append(byte)
            index = nextIndex
        }
        return data
    }

    static func bytesToHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func urlB64Encode(_ data: Data) -> String {
        data.base64URLEncodedStringNoPad()
    }

    static func urlB64Decode(_ s: String) -> Data? {
        var base64 = s.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base64)
    }

    /// Strict canonical decoding for the 32-byte base64url values used by v3.
    /// Foundation's decoder is intentionally permissive, which is useful for
    /// general data but inappropriate for self-certifying wire identities.
    static func decodeCanonical32(_ value: String) -> Data? {
        guard value.count == 43,
              value.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }),
              let raw = urlB64Decode(value), raw.count == 32,
              urlB64Encode(raw) == value else { return nil }
        return raw
    }

    static func actorBindingIsValid(actorId: String, publicKey: String, roomIdRaw: Data) -> Bool {
        guard let pubRaw = decodeCanonical32(publicKey),
              decodeCanonical32(actorId) != nil else { return false }
        return self.actorId(roomIdRaw: roomIdRaw, pubkeyRaw: pubRaw) == actorId
    }

    /// Verify the authenticated per-WebSocket actor announcement from ADR-001.
    /// This exact helper is used by the manager and by production-path tests.
    static func verifyHello(
        actorId: String,
        publicKey: String,
        sessionDomain: String,
        versionStamp: String,
        signature: String,
        roomIdRaw: Data
    ) -> Bool {
        guard actorBindingIsValid(actorId: actorId, publicKey: publicKey, roomIdRaw: roomIdRaw),
              let pubRaw = decodeCanonical32(publicKey),
              let sdRaw = decodeCanonical32(sessionDomain),
              versionStamp.count == 60,
              versionStamp.dropFirst(16) == ":\(actorId)",
              let epoch = parseHelloEpoch(String(versionStamp.prefix(16))) else { return false }
        let preimage = buildPreimage(
            domain: domainHello,
            roomIdRaw: roomIdRaw,
            actorId: actorId,
            sessionDomain: sdRaw,
            counterHex16: epoch,
            objectId: "",
            kind: "hello",
            payloadHash: sha256(pubRaw)
        )
        return SyncSigning.verify(publicKey, preimage, signature)
    }

    static func parseHelloEpoch(_ value: String) -> String? {
        guard value.count == 16, value != "0000000000000000",
              value.range(of: "^[0-9a-f]{16}$", options: .regularExpression) != nil,
              UInt64(value, radix: 16) != nil else { return nil }
        return value
    }

    static func helloAckMatches(actorId: String, sessionDomain: Data, expectedVersion: String,
                                frameActorId: String, frameSessionDomain: String, frameVersion: String) -> Bool {
        frameActorId == actorId && frameSessionDomain == urlB64Encode(sessionDomain) && frameVersion == expectedVersion
    }
}

extension Data {
    mutating func appendUInt16LE(_ value: UInt16) {
        var le = value.littleEndian
        append(Data(bytes: &le, count: 2))
    }
}
