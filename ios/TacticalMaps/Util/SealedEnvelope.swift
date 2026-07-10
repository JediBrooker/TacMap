import Foundation
import CryptoKit

/// At-rest AEAD for on-device mission data. Byte-for-byte the same wire format
/// as Android's `SealedEnvelope.kt`, and the same primitives as SyncCrypto
/// (AES-256-GCM, iv(12) || ct || tag(16)) so there's one crypto shape to audit
/// across the whole app. Key comes from `DataKey`.
///
/// Two envelope shapes:
///
///  - Whole-file: MAGIC || iv || ct || tag. The magic is how a reader tells
///    "sealed" from "legacy plaintext JSON we need to migrate", without a
///    side-car marker that could drift out of sync with the actual bytes.
///
///  - One line of an append log: "v1:" + base64(iv || ct || tag). No magic,
///    it'd be 7 wasted bytes on every GPS fix and the "v1:" already gives us
///    something to bump. Legacy plaintext lines start with '{', which is not a
///    base64 character and not "v1:", so the two are never ambiguous.
///
/// `label` is bound in as AEAD associated data, so drawings.json's ciphertext
/// dropped over waypoints.json fails its tag check instead of loading as the
/// wrong document.
///
/// CryptoKit's `AES.GCM.SealedBox.combined` is exactly iv || ct || tag, which is
/// what Android's `iv + cipher.doFinal(...)` produces. That's not luck, it's the
/// reason both sides can read the shared test fixtures.
enum SealedEnvelope {

    /// "TMSEAL" + format version. Bump the byte, not the string.
    private static let magic: [UInt8] = [0x54, 0x4D, 0x53, 0x45, 0x41, 0x4C, 0x01]

    /// Prefix on a sealed append-log line.
    static let linePrefix = "v1:"

    static var magicSize: Int { magic.count }

    /// AEAD associated data. Binds a blob to the store it belongs to.
    static func aad(_ label: String) -> Data {
        Data("tacmap-atrest-v1|\(label)".utf8)
    }

    // MARK: core

    /// iv || ct || tag
    static func seal(key: Data, plaintext: Data, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext,
                                   using: SymmetricKey(data: key),
                                   authenticating: aad)
        guard let combined = box.combined else {
            throw CryptoKitError.incorrectParameterSize
        }
        return combined
    }

    /// nil on tamper / wrong key / wrong label. Never throws.
    static func open(key: Data, blob: Data, aad: Data) -> Data? {
        guard let box = try? AES.GCM.SealedBox(combined: blob) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    // MARK: whole-file envelope

    static func isSealedFile(_ bytes: Data) -> Bool {
        guard bytes.count >= magic.count else { return false }
        return Array(bytes.prefix(magic.count)) == magic
    }

    static func sealFile(key: Data, plaintext: Data, label: String) throws -> Data {
        Data(magic) + (try seal(key: key, plaintext: plaintext, aad: aad(label)))
    }

    /// nil if the magic is missing or the tag check fails.
    static func openFile(key: Data, blob: Data, label: String) -> Data? {
        guard isSealedFile(blob) else { return nil }
        // Re-base the slice: dropFirst leaves indices offset, and SealedBox
        // reads it positionally.
        return open(key: key, blob: Data(blob.dropFirst(magic.count)), aad: aad(label))
    }

    // MARK: append-log line envelope

    /// Legacy plaintext NDJSON lines are bare JSON objects.
    static func isSealedLine(_ line: String) -> Bool { line.hasPrefix(linePrefix) }

    static func sealLine(key: Data, plaintext: Data, label: String) throws -> String {
        let blob = try seal(key: key, plaintext: plaintext, aad: aad(label))
        return linePrefix + blob.base64EncodedString()
    }

    /// nil on any problem, incl. a half-written final line from a torn append.
    static func openLine(key: Data, line: String, label: String) -> Data? {
        guard isSealedLine(line) else { return nil }
        let b64 = String(line.dropFirst(linePrefix.count))
        guard let raw = Data(base64Encoded: b64) else { return nil }
        return open(key: key, blob: raw, aad: aad(label))
    }
}
