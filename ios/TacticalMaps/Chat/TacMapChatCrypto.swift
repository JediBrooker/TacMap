import Foundation
import CryptoKit

enum TacMapChatCrypto {
    static let version: Int64 = 1
    static let maximumCiphertextCharacters = 16_384
    static let domainChatKey: UInt8 = 0x05
    static let domainChat: UInt8 = 0x06

    enum CryptoError: LocalizedError, Equatable {
        case invalidContext
        case invalidPayload
        case invalidFrame
        case invalidSignature
        case keyAgreementFailed
        case encryptionFailed
        case decryptionFailed

        var errorDescription: String? {
            switch self {
            case .invalidContext: return "The secure chat session changed. Select the recipient again."
            case .invalidPayload: return "The chat text is invalid or too large."
            case .invalidFrame: return "The encrypted chat frame is malformed."
            case .invalidSignature: return "The chat frame failed sender authentication."
            case .keyAgreementFailed: return "A private channel could not be established with that unit."
            case .encryptionFailed: return "The message could not be encrypted."
            case .decryptionFailed: return "The message could not be decrypted."
            }
        }
    }

    struct PeerKey: Equatable {
        let actorId: String
        let sessionDomain: String
        let keyExchange: String
        let keyId: String
        let signature: String

        var recipient: TacMapChatRecipient {
            TacMapChatRecipient(
                actorId: actorId,
                displayName: "",
                sessionDomain: sessionDomain,
                keyId: keyId
            )
        }
    }

    struct LocalSession {
        fileprivate let privateKey: Curve25519.KeyAgreement.PrivateKey
        let actorId: String
        let sessionDomain: String
        let keyExchange: String
        let keyId: String
        let signature: String

        var advertisement: [String: Any] {
            [
                "t": "chat-key",
                "cv": 1,
                "by": actorId,
                "sd": sessionDomain,
                "kx": keyExchange,
                "kid": keyId,
                "sig": signature
            ]
        }

        var peerKey: PeerKey {
            PeerKey(
                actorId: actorId,
                sessionDomain: sessionDomain,
                keyExchange: keyExchange,
                keyId: keyId,
                signature: signature
            )
        }
    }

    struct Frame: Equatable {
        let scope: TacMapChatScope
        let senderActorId: String
        let senderSessionDomain: String
        let versionStamp: String
        let messageId: String
        let senderKeyId: String
        let recipientActorId: String?
        let recipientSessionDomain: String?
        let recipientKeyId: String?
        let ciphertext: String
        let signature: String

        var counter: Int64 { VersionStamp.parse(versionStamp)?.counter ?? 0 }

        var object: [String: Any] {
            var result: [String: Any] = [
                "t": "chat",
                "cv": 1,
                "scope": scope.rawValue,
                "by": senderActorId,
                "sd": senderSessionDomain,
                "vs": versionStamp,
                "mid": messageId,
                "fromKid": senderKeyId,
                "ct": ciphertext,
                "sig": signature
            ]
            if scope == .direct {
                result["to"] = recipientActorId
                result["toSd"] = recipientSessionDomain
                result["toKid"] = recipientKeyId
            }
            return result
        }
    }

    struct OpenedFrame {
        let frame: Frame
        let payload: TacMapChatPayload
        let fingerprint: String
    }

    static func makeLocalSession(roomIdRaw: Data,
                                 actorId: String,
                                 sessionDomain: Data,
                                 signingSeed: Data,
                                 privateKeyRaw: Data? = nil) throws -> LocalSession {
        guard roomIdRaw.count == 32,
              sessionDomain.count == 32,
              TacMapChatMessage.isCanonical32(actorId) else {
            throw CryptoError.invalidContext
        }
        let privateKey: Curve25519.KeyAgreement.PrivateKey
        do {
            if let privateKeyRaw {
                privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKeyRaw)
            } else {
                privateKey = Curve25519.KeyAgreement.PrivateKey()
            }
        } catch {
            throw CryptoError.invalidContext
        }
        let publicRaw = privateKey.publicKey.rawRepresentation
        guard !publicRaw.allSatisfy({ $0 == 0 }) else { throw CryptoError.invalidContext }
        let sessionString = SyncIdentity.urlB64Encode(sessionDomain)
        let keyExchange = SyncIdentity.urlB64Encode(publicRaw)
        let keyId = makeKeyId(
            roomIdRaw: roomIdRaw,
            actorId: actorId,
            sessionDomain: sessionDomain,
            keyExchangeRaw: publicRaw
        )
        var signedPayload = Data([UInt8(version)])
        signedPayload.append(publicRaw)
        guard let keyIdRaw = SyncIdentity.decodeCanonical32(keyId) else {
            throw CryptoError.invalidContext
        }
        signedPayload.append(keyIdRaw)
        let preimage = SyncIdentity.buildPreimage(
            domain: domainChatKey,
            roomIdRaw: roomIdRaw,
            actorId: actorId,
            sessionDomain: sessionDomain,
            counterHex16: "0000000000000000",
            objectId: "",
            kind: "chat-key-v1",
            payloadHash: SyncIdentity.sha256(signedPayload)
        )
        guard let signature = SyncSigning.sign(signingSeed, preimage) else {
            throw CryptoError.invalidSignature
        }
        return LocalSession(
            privateKey: privateKey,
            actorId: actorId,
            sessionDomain: sessionString,
            keyExchange: keyExchange,
            keyId: keyId,
            signature: signature
        )
    }

    static func decodeAndVerifyPeerKey(_ object: [String: Any],
                                       roomIdRaw: Data,
                                       signingPublicKey: String) -> PeerKey? {
        guard Set(object.keys) == Set(["t", "cv", "by", "sd", "kx", "kid", "sig"]),
              object["t"] as? String == "chat-key",
              strictInteger(object["cv"]) == version,
              let actorId = object["by"] as? String,
              TacMapChatMessage.isCanonical32(actorId),
              let sessionString = object["sd"] as? String,
              let sessionRaw = SyncIdentity.decodeCanonical32(sessionString),
              let keyExchange = object["kx"] as? String,
              let keyExchangeRaw = SyncIdentity.decodeCanonical32(keyExchange),
              !keyExchangeRaw.allSatisfy({ $0 == 0 }),
              let keyId = object["kid"] as? String,
              let keyIdRaw = SyncIdentity.decodeCanonical32(keyId),
              let signature = object["sig"] as? String,
              canonicalURLData(signature, count: 64) != nil,
              roomIdRaw.count == 32 else { return nil }
        let expectedKeyId = makeKeyId(
            roomIdRaw: roomIdRaw,
            actorId: actorId,
            sessionDomain: sessionRaw,
            keyExchangeRaw: keyExchangeRaw
        )
        guard constantTimeEqual(keyId, expectedKeyId) else { return nil }

        var signedPayload = Data([UInt8(version)])
        signedPayload.append(keyExchangeRaw)
        signedPayload.append(keyIdRaw)
        let preimage = SyncIdentity.buildPreimage(
            domain: domainChatKey,
            roomIdRaw: roomIdRaw,
            actorId: actorId,
            sessionDomain: sessionRaw,
            counterHex16: "0000000000000000",
            objectId: "",
            kind: "chat-key-v1",
            payloadHash: SyncIdentity.sha256(signedPayload)
        )
        guard SyncSigning.verify(signingPublicKey, preimage, signature) else { return nil }
        return PeerKey(
            actorId: actorId,
            sessionDomain: sessionString,
            keyExchange: keyExchange,
            keyId: keyId,
            signature: signature
        )
    }

    static func seal(payload: TacMapChatPayload,
                     scope: TacMapChatScope,
                     roomIdRaw: Data,
                     roomKey: SymmetricKey,
                     localSession: LocalSession,
                     signingSeed: Data,
                     counter: Int64,
                     messageId: String,
                     recipient: PeerKey? = nil) throws -> Frame {
        guard payload.isValid,
              roomIdRaw.count == 32,
              counter > 0,
              counter <= VersionStamp.maxCounter,
              TacMapChatMessage.isCanonicalMessageID(messageId) else {
            throw CryptoError.invalidPayload
        }
        switch scope {
        case .room:
            guard recipient == nil else { throw CryptoError.invalidContext }
        case .direct:
            guard let recipient,
                  recipient.actorId != localSession.actorId else {
                throw CryptoError.invalidContext
            }
        }

        let stamp = VersionStamp(counter: counter, actorId: localSession.actorId).encode()
        var unsigned = Frame(
            scope: scope,
            senderActorId: localSession.actorId,
            senderSessionDomain: localSession.sessionDomain,
            versionStamp: stamp,
            messageId: messageId,
            senderKeyId: localSession.keyId,
            recipientActorId: recipient?.actorId,
            recipientSessionDomain: recipient?.sessionDomain,
            recipientKeyId: recipient?.keyId,
            ciphertext: "",
            signature: ""
        )
        let header = try makeHeader(roomIdRaw: roomIdRaw, frame: unsigned)
        let key: SymmetricKey
        switch scope {
        case .room:
            key = roomChatKey(roomKey)
        case .direct:
            key = try directChatKey(
                localPrivateKey: localSession.privateKey,
                peer: recipient!,
                roomIdRaw: roomIdRaw,
                header: header
            )
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let plaintext = try encoder.encode(payload)
        guard plaintext.count <= TacMapChatPayload.maximumPlaintextBytes else {
            throw CryptoError.invalidPayload
        }
        let aad = chatAAD(header: header)
        guard let box = try? AES.GCM.seal(plaintext, using: key, authenticating: aad),
              let combined = box.combined else { throw CryptoError.encryptionFailed }
        let ciphertext = combined.base64EncodedString()
        guard ciphertext.count <= maximumCiphertextCharacters else {
            throw CryptoError.invalidPayload
        }
        unsigned = Frame(
            scope: unsigned.scope,
            senderActorId: unsigned.senderActorId,
            senderSessionDomain: unsigned.senderSessionDomain,
            versionStamp: unsigned.versionStamp,
            messageId: unsigned.messageId,
            senderKeyId: unsigned.senderKeyId,
            recipientActorId: unsigned.recipientActorId,
            recipientSessionDomain: unsigned.recipientSessionDomain,
            recipientKeyId: unsigned.recipientKeyId,
            ciphertext: ciphertext,
            signature: ""
        )
        let preimage = signaturePreimage(header: header, sealed: combined)
        guard let signature = SyncSigning.sign(signingSeed, preimage) else {
            throw CryptoError.invalidSignature
        }
        return Frame(
            scope: unsigned.scope,
            senderActorId: unsigned.senderActorId,
            senderSessionDomain: unsigned.senderSessionDomain,
            versionStamp: unsigned.versionStamp,
            messageId: unsigned.messageId,
            senderKeyId: unsigned.senderKeyId,
            recipientActorId: unsigned.recipientActorId,
            recipientSessionDomain: unsigned.recipientSessionDomain,
            recipientKeyId: unsigned.recipientKeyId,
            ciphertext: ciphertext,
            signature: signature
        )
    }

    /// Signature and all routed context are verified before any AEAD open.
    static func open(_ object: [String: Any],
                     roomIdRaw: Data,
                     roomKey: SymmetricKey,
                     localSession: LocalSession,
                     senderKey: PeerKey,
                     senderSigningPublicKey: String) throws -> OpenedFrame {
        let frame = try decodeFrame(object)
        guard frame.senderActorId == senderKey.actorId,
              frame.senderSessionDomain == senderKey.sessionDomain,
              frame.senderKeyId == senderKey.keyId,
              frame.senderActorId != localSession.actorId else {
            throw CryptoError.invalidContext
        }
        if frame.scope == .direct {
            guard frame.recipientActorId == localSession.actorId,
                  frame.recipientSessionDomain == localSession.sessionDomain,
                  frame.recipientKeyId == localSession.keyId else {
                throw CryptoError.invalidContext
            }
        }
        let header = try makeHeader(roomIdRaw: roomIdRaw, frame: frame)
        guard let sealed = canonicalStandardBase64(
            frame.ciphertext,
            maximumCharacters: maximumCiphertextCharacters,
            minimumBytes: 28
        ),
              let signatureRaw = canonicalURLData(frame.signature, count: 64) else {
            throw CryptoError.invalidFrame
        }
        let preimage = signaturePreimage(header: header, sealed: sealed)
        guard SyncSigning.verify(senderSigningPublicKey, preimage, frame.signature) else {
            throw CryptoError.invalidSignature
        }

        let key: SymmetricKey
        switch frame.scope {
        case .room:
            key = roomChatKey(roomKey)
        case .direct:
            key = try directChatKey(
                localPrivateKey: localSession.privateKey,
                peer: senderKey,
                roomIdRaw: roomIdRaw,
                header: header
            )
        }
        guard let box = try? AES.GCM.SealedBox(combined: sealed),
              let plaintext = try? AES.GCM.open(box, using: key, authenticating: chatAAD(header: header)),
              plaintext.count <= TacMapChatPayload.maximumPlaintextBytes else {
            throw CryptoError.decryptionFailed
        }
        let payload = try decodePayload(plaintext)
        var fingerprintInput = preimage
        fingerprintInput.append(signatureRaw)
        let fingerprint = SyncIdentity.urlB64Encode(SyncIdentity.sha256(fingerprintInput))
        return OpenedFrame(frame: frame, payload: payload, fingerprint: fingerprint)
    }

    static func decodeFrame(_ object: [String: Any]) throws -> Frame {
        guard strictInteger(object["cv"]) == version,
              object["t"] as? String == "chat",
              let scopeText = object["scope"] as? String,
              let scope = TacMapChatScope(rawValue: scopeText) else {
            throw CryptoError.invalidFrame
        }
        let expected = scope == .room
            ? Set(["t", "cv", "scope", "by", "sd", "vs", "mid", "fromKid", "ct", "sig"])
            : Set(["t", "cv", "scope", "by", "sd", "vs", "mid", "fromKid", "to", "toSd", "toKid", "ct", "sig"])
        guard Set(object.keys) == expected,
              let actorId = object["by"] as? String,
              TacMapChatMessage.isCanonical32(actorId),
              let session = object["sd"] as? String,
              TacMapChatMessage.isCanonical32(session),
              let stampText = object["vs"] as? String,
              let stamp = VersionStamp.parse(stampText),
              stamp.counter > 0,
              stamp.actorId == actorId,
              let messageId = object["mid"] as? String,
              TacMapChatMessage.isCanonicalMessageID(messageId),
              let senderKeyId = object["fromKid"] as? String,
              TacMapChatMessage.isCanonical32(senderKeyId),
              let ciphertext = object["ct"] as? String,
              canonicalStandardBase64(
                ciphertext,
                maximumCharacters: maximumCiphertextCharacters,
                minimumBytes: 28
              ) != nil,
              let signature = object["sig"] as? String,
              canonicalURLData(signature, count: 64) != nil else {
            throw CryptoError.invalidFrame
        }
        var recipientActorId: String?
        var recipientSession: String?
        var recipientKeyId: String?
        if scope == .direct {
            guard let actor = object["to"] as? String,
                  TacMapChatMessage.isCanonical32(actor),
                  actor != actorId,
                  let session = object["toSd"] as? String,
                  TacMapChatMessage.isCanonical32(session),
                  let keyId = object["toKid"] as? String,
                  TacMapChatMessage.isCanonical32(keyId) else {
                throw CryptoError.invalidFrame
            }
            recipientActorId = actor
            recipientSession = session
            recipientKeyId = keyId
        }
        return Frame(
            scope: scope,
            senderActorId: actorId,
            senderSessionDomain: session,
            versionStamp: stampText,
            messageId: messageId,
            senderKeyId: senderKeyId,
            recipientActorId: recipientActorId,
            recipientSessionDomain: recipientSession,
            recipientKeyId: recipientKeyId,
            ciphertext: ciphertext,
            signature: signature
        )
    }

    static func makeHeader(roomIdRaw: Data, frame: Frame) throws -> Data {
        guard roomIdRaw.count == 32,
              let senderSession = SyncIdentity.decodeCanonical32(frame.senderSessionDomain),
              let messageId = canonicalURLData(frame.messageId, count: 16),
              let senderKeyId = SyncIdentity.decodeCanonical32(frame.senderKeyId),
              let stamp = VersionStamp.parse(frame.versionStamp),
              stamp.actorId == frame.senderActorId,
              stamp.counter > 0 else { throw CryptoError.invalidFrame }
        let sender = Data(frame.senderActorId.utf8)
        guard sender.count <= Int(UInt16.max) else { throw CryptoError.invalidFrame }

        var recipient = Data()
        var recipientSession = Data(repeating: 0, count: 32)
        var recipientKeyId = Data(repeating: 0, count: 32)
        if frame.scope == .direct {
            guard let recipientActor = frame.recipientActorId,
                  let sessionText = frame.recipientSessionDomain,
                  let keyIdText = frame.recipientKeyId,
                  let session = SyncIdentity.decodeCanonical32(sessionText),
                  let keyId = SyncIdentity.decodeCanonical32(keyIdText),
                  recipientActor != frame.senderActorId else {
                throw CryptoError.invalidFrame
            }
            recipient = Data(recipientActor.utf8)
            recipientSession = session
            recipientKeyId = keyId
        } else if frame.recipientActorId != nil
                    || frame.recipientSessionDomain != nil
                    || frame.recipientKeyId != nil {
            throw CryptoError.invalidFrame
        }
        guard recipient.count <= Int(UInt16.max) else { throw CryptoError.invalidFrame }

        var header = Data([UInt8(version), frame.scope == .room ? 0x01 : 0x02])
        header.append(roomIdRaw)
        header.appendUInt16LE(UInt16(sender.count))
        header.append(sender)
        header.append(senderSession)
        header.append(Data(frame.versionStamp.prefix(16).utf8))
        header.append(messageId)
        header.append(senderKeyId)
        header.appendUInt16LE(UInt16(recipient.count))
        header.append(recipient)
        header.append(recipientSession)
        header.append(recipientKeyId)
        return header
    }

    static func roomChatKey(_ roomKey: SymmetricKey) -> SymmetricKey {
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data("tacmap-chat-room-v1".utf8),
            using: roomKey
        )
        return SymmetricKey(data: Data(mac))
    }

    private static func directChatKey(localPrivateKey: Curve25519.KeyAgreement.PrivateKey,
                                      peer: PeerKey,
                                      roomIdRaw: Data,
                                      header: Data) throws -> SymmetricKey {
        guard let peerRaw = SyncIdentity.decodeCanonical32(peer.keyExchange),
              !peerRaw.allSatisfy({ $0 == 0 }),
              let publicKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerRaw),
              let secret = try? localPrivateKey.sharedSecretFromKeyAgreement(with: publicKey) else {
            throw CryptoError.keyAgreementFailed
        }
        let isAllZero = secret.withUnsafeBytes { bytes in
            bytes.allSatisfy { $0 == 0 }
        }
        guard !isAllZero else { throw CryptoError.keyAgreementFailed }
        var saltInput = Data("tacmap-chat-direct-salt-v1\0".utf8)
        saltInput.append(roomIdRaw)
        var infoInput = Data("tacmap-chat-direct-info-v1\0".utf8)
        infoInput.append(header)
        return secret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: SyncIdentity.sha256(saltInput),
            sharedInfo: SyncIdentity.sha256(infoInput),
            outputByteCount: 32
        )
    }

    private static func makeKeyId(roomIdRaw: Data,
                                  actorId: String,
                                  sessionDomain: Data,
                                  keyExchangeRaw: Data) -> String {
        var data = Data("tacmap-chat-kid-v1\0".utf8)
        data.append(roomIdRaw)
        let actor = Data(actorId.utf8)
        data.appendUInt16LE(UInt16(actor.count))
        data.append(actor)
        data.append(sessionDomain)
        data.append(keyExchangeRaw)
        return SyncIdentity.urlB64Encode(SyncIdentity.sha256(data))
    }

    private static func chatAAD(header: Data) -> Data {
        Data("tacmap-chat-aad-v1\0".utf8) + header
    }

    private static func signaturePreimage(header: Data, sealed: Data) -> Data {
        Data([domainChat, SyncIdentity.protocolVersion])
            + header
            + SyncIdentity.sha256(sealed)
    }

    /// Internal so the strict plaintext schema can be pinned independently in
    /// focused interoperability tests. Production callers reach it only after
    /// signature verification and AEAD authentication in `open`.
    static func decodePayload(_ data: Data) throws -> TacMapChatPayload {
        guard data.count <= TacMapChatPayload.maximumPlaintextBytes,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CryptoError.invalidPayload
        }
        let required = Set(["pv", "kind", "body", "createdAt"])
        let keys = Set(object.keys)
        guard keys == required || keys == required.union(["replyTo"]),
              strictInteger(object["pv"]) == Int64(TacMapChatPayload.version),
              let kindText = object["kind"] as? String,
              let kind = TacMapChatContentKind(rawValue: kindText),
              let body = object["body"] as? String,
              !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              body.utf8.count <= TacMapChatPayload.maximumBodyBytes,
              let createdAt = strictInteger(object["createdAt"]),
              createdAt >= 0 else { throw CryptoError.invalidPayload }
        let replyTo: String?
        if keys.contains("replyTo") {
            guard let value = object["replyTo"] as? String,
                  TacMapChatMessage.isCanonicalMessageID(value) else {
                throw CryptoError.invalidPayload
            }
            replyTo = value
        } else {
            replyTo = nil
        }
        let payload = TacMapChatPayload(
            kind: kind,
            body: body,
            createdAt: createdAt,
            replyTo: replyTo
        )
        guard payload.isValid else { throw CryptoError.invalidPayload }
        return payload
    }

    private static func strictInteger(_ value: Any?) -> Int64? {
        SyncManager.strictJSONInteger(value, minimum: 0, maximum: Int64.max)
    }

    private static func canonicalURLData(_ value: String, count: Int) -> Data? {
        guard value.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }),
              let raw = SyncIdentity.urlB64Decode(value),
              raw.count == count,
              SyncIdentity.urlB64Encode(raw) == value else { return nil }
        return raw
    }

    private static func canonicalStandardBase64(_ value: String,
                                                maximumCharacters: Int,
                                                minimumBytes: Int) -> Data? {
        guard !value.isEmpty,
              value.count <= maximumCharacters,
              value.count.isMultiple(of: 4),
              value.range(
                of: "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$",
                options: .regularExpression
              ) != nil,
              let data = Data(base64Encoded: value),
              data.count >= minimumBytes,
              data.base64EncodedString() == value else { return nil }
        return data
    }

    private static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8)
        let b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for index in a.indices { diff |= a[index] ^ b[index] }
        return diff == 0
    }
}
