import Foundation

// Crash-loop breaker (D5-07). Before the first render of each kind for a map
// we write "in progress" to a tiny file and fsync it; if the process dies
// before we clear it, the next launch doesnt auto-render that map and asks.
// The file only ever holds random UUIDs, no names, no paths (names give the AO
// away). State machine pinned by pdf_tile_render.json crashGuard. The bake
// has its own slot (K1) so an import or a first render never wipes it.

enum PDFGuardKind: String, Codable, CaseIterable {
    case `import`, base, vector, bake
}

/// the launch table minus the bake row, first match wins
enum PDFLaunchDecision: Equatable {
    case none
    case importInterrupted(op: String?)
    case suppress
}

/// both outcomes of one launch, an interrupted import and an interrupted
/// bake can both be true and both get their notice
struct PDFLaunchOutcome: Equatable {
    var decision: PDFLaunchDecision
    var bakeInterrupted: Bool
}

enum PDFSuspectResolution: String {
    case openAnyway, deleted, notNow
}

struct PDFRenderGuardState: Equatable {
    struct InProgress: Equatable {
        var kind: PDFGuardKind
        var token: String
        var op: String?
    }

    /// import, base or vector. an older build could leave a bake marker in
    /// here too, launch reads that as an interrupted bake
    var inProgress: InProgress?
    /// token of the bake that's running, its own slot
    var bakeInProgress: String?
    var suspect: String?
    /// oldest first, kinds in ["base", "vector"] order
    var verified: [(token: String, kinds: [PDFGuardKind])] = []

    static func == (a: Self, b: Self) -> Bool {
        a.inProgress == b.inProgress && a.bakeInProgress == b.bakeInProgress && a.suspect == b.suspect
            && a.verified.map(\.token) == b.verified.map(\.token)
            && a.verified.map(\.kinds) == b.verified.map(\.kinds)
    }

    func verifiedKinds(_ token: String) -> [PDFGuardKind] {
        verified.first(where: { $0.token == token })?.kinds ?? []
    }

    // MARK: on-disk shape {"v":1,"inProgress":..,"bakeInProgress":..,"suspect":..,"verified":{..}}

    func jsonObject() -> [String: Any] {
        var ip: Any = NSNull()
        if let p = inProgress {
            var o: [String: Any] = ["kind": p.kind.rawValue, "token": p.token]
            if let op = p.op { o["op"] = op }
            ip = o
        }
        var ver: [String: Any] = [:]
        for v in verified { ver[v.token] = v.kinds.map(\.rawValue) }
        let bake: Any = bakeInProgress.map { ["token": $0] as Any } ?? NSNull()
        return ["v": PDFTileConstants.guardFileVersion, "inProgress": ip, "bakeInProgress": bake,
                "suspect": suspect.map { $0 as Any } ?? NSNull(), "verified": ver]
    }

    /// OD-F8: the K1 schema, nothing else. The LRU order is the member order of
    /// "verified", oldest first, same as Android writes it. JSONSerialization
    /// cant keep member order so the object is put together by hand
    func encoded() -> Data? {
        func frag(_ v: Any) -> String? {
            guard let d = try? JSONSerialization.data(withJSONObject: v, options: [.fragmentsAllowed, .sortedKeys]) else {
                return nil
            }
            return String(data: d, encoding: .utf8)
        }
        let o = jsonObject()
        guard let v = frag(o["v"]!), let ip = frag(o["inProgress"]!), let bake = frag(o["bakeInProgress"]!),
              let sus = frag(o["suspect"]!) else { return nil }
        var members: [String] = []
        for entry in verified {
            guard let k = frag(entry.token), let kinds = frag(entry.kinds.map(\.rawValue)) else { return nil }
            members.append(k + ":" + kinds)
        }
        let ver = "{" + members.joined(separator: ",") + "}"
        let s = "{\"v\":\(v),\"inProgress\":\(ip),\"bakeInProgress\":\(bake),\"suspect\":\(sus),\"verified\":\(ver)}"
        return Data(s.utf8)
    }

    static func decode(_ data: Data) -> PDFRenderGuardState? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (o["v"] as? Int) == PDFTileConstants.guardFileVersion else { return nil }
        var s = PDFRenderGuardState()
        if let ip = o["inProgress"] as? [String: Any], let k = (ip["kind"] as? String).flatMap(PDFGuardKind.init),
           let t = ip["token"] as? String, isToken(t) {
            s.inProgress = InProgress(kind: k, token: t, op: ip["op"] as? String)
        }
        // missing in files from before K1, reads as null
        if let b = o["bakeInProgress"] as? [String: Any], let t = b["token"] as? String, isToken(t) {
            s.bakeInProgress = t
        }
        if let sus = o["suspect"] as? String, isToken(sus) { s.suspect = sus }
        let ver = o["verified"] as? [String: [String]] ?? [:]
        // member order is the LRU order. an "order" array from an older iOS
        // build is ignored (OD-F8), that file just reads in its member order
        let order = OrderedJSONKeys.memberOrder(of: "verified", in: data) ?? ver.keys.sorted()
        for t in order where isToken(t) {
            guard let kinds = ver[t] else { continue }
            let ks = PDFRenderGuardReducer.kindOrder.filter { kinds.contains($0.rawValue) }
            s.verified.append((t, ks))
        }
        while s.verified.count > PDFTileConstants.guardMaxVerifiedTokens { s.verified.removeFirst() }
        return s
    }

    /// only random UUIDs go in here, anything else on disk is ignored
    static func isToken(_ s: String) -> Bool { UUID(uuidString: s) != nil }
}

/// Just enough of a JSON reader to get the member order of one top level
/// object, which JSONSerialization throws away. The caller already parsed the
/// whole thing with JSONSerialization, so junk here just means nil.
enum OrderedJSONKeys {
    static func memberOrder(of member: String, in data: Data) -> [String]? {
        var p = Parser(bytes: Array(data))
        guard case .object(let top)? = p.value() else { return nil }
        guard let v = top.first(where: { $0.0 == member })?.1, case .object(let inner) = v else { return nil }
        return inner.map(\.0)
    }

    indirect enum Value {
        case object([(String, Value)])
        case array([Value])
        case string(String)
        case other
    }

    struct Parser {
        let bytes: [UInt8]
        var i = 0
        var depth = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        mutating func skipSpace() {
            while i < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[i]) { i += 1 }
        }

        mutating func value() -> Value? {
            skipSpace()
            guard i < bytes.count else { return nil }
            switch bytes[i] {
            case UInt8(ascii: "{"): return object()
            case UInt8(ascii: "["): return array()
            case UInt8(ascii: "\""): return string().map { .string($0) }
            default:
                // number, true, false, null: skip to the next delimiter
                let start = i
                while i < bytes.count, ![UInt8(ascii: ","), UInt8(ascii: "}"), UInt8(ascii: "]"),
                                          0x20, 0x09, 0x0A, 0x0D].contains(bytes[i]) { i += 1 }
                return i > start ? .other : nil
            }
        }

        mutating func object() -> Value? {
            depth += 1
            defer { depth -= 1 }
            guard depth < 64 else { return nil }
            i += 1
            var out: [(String, Value)] = []
            skipSpace()
            if i < bytes.count, bytes[i] == UInt8(ascii: "}") { i += 1; return .object(out) }
            while true {
                skipSpace()
                guard i < bytes.count, bytes[i] == UInt8(ascii: "\""), let k = string() else { return nil }
                skipSpace()
                guard i < bytes.count, bytes[i] == UInt8(ascii: ":") else { return nil }
                i += 1
                guard let v = value() else { return nil }
                out.append((k, v))
                skipSpace()
                guard i < bytes.count else { return nil }
                if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                if bytes[i] == UInt8(ascii: "}") { i += 1; return .object(out) }
                return nil
            }
        }

        mutating func array() -> Value? {
            depth += 1
            defer { depth -= 1 }
            guard depth < 64 else { return nil }
            i += 1
            var out: [Value] = []
            skipSpace()
            if i < bytes.count, bytes[i] == UInt8(ascii: "]") { i += 1; return .array(out) }
            while true {
                guard let v = value() else { return nil }
                out.append(v)
                skipSpace()
                guard i < bytes.count else { return nil }
                if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                if bytes[i] == UInt8(ascii: "]") { i += 1; return .array(out) }
                return nil
            }
        }

        /// the raw string between the quotes, escapes decoded by JSONSerialization
        mutating func string() -> String? {
            let start = i
            i += 1
            while i < bytes.count {
                if bytes[i] == UInt8(ascii: "\\") { i += 2; continue }
                if bytes[i] == UInt8(ascii: "\"") {
                    i += 1
                    let raw = Data(bytes[start..<i])
                    return (try? JSONSerialization.jsonObject(with: raw, options: .fragmentsAllowed)) as? String
                }
                i += 1
            }
            return nil
        }
    }
}

/// Pure transitions, fixture driven. Platform code persists after each.
enum PDFRenderGuardReducer {
    static let kindOrder: [PDFGuardKind] = [.base, .vector]

    @discardableResult
    static func arm(_ s: inout PDFRenderGuardState, kind: PDFGuardKind, token: String,
                    foreground: Bool = true, op: String? = nil) -> Bool {
        switch kind {
        case .import:
            s.inProgress = .init(kind: kind, token: token, op: op)
            return true
        case .bake:
            // own slot, one bake at a time, never touches inProgress
            s.bakeInProgress = token
            return true
        case .base, .vector:
            // never hide an import marker, never arm in the background. a bake
            // marker doesnt block them, first renders during a bake are guarded too
            guard foreground, !s.verifiedKinds(token).contains(kind), s.inProgress == nil else { return false }
            s.inProgress = .init(kind: kind, token: token, op: nil)
            return true
        }
    }

    static func complete(_ s: inout PDFRenderGuardState, kind: PDFGuardKind, token: String) {
        switch kind {
        case .import: verify(&s, token: token, kind: .base)
        case .base, .vector: verify(&s, token: token, kind: kind)
        case .bake:
            // verifies nothing, clears only its own token
            if s.bakeInProgress == token { s.bakeInProgress = nil }
            return
        }
        if let ip = s.inProgress, ip.kind == kind, ip.token == token { s.inProgress = nil }
    }

    static func disarmBackground(_ s: inout PDFRenderGuardState) {
        if let ip = s.inProgress, ip.kind == .base || ip.kind == .vector { s.inProgress = nil }
    }

    /// iOS only: our own base/vector marker comes off, nothing verified. for work
    /// that has to be guarded but isnt the render itself (the restore's openPage)
    static func disarm(_ s: inout PDFRenderGuardState, kind: PDFGuardKind, token: String) {
        guard kind == .base || kind == .vector, let ip = s.inProgress, ip.kind == kind, ip.token == token else { return }
        s.inProgress = nil
    }

    /// decision = first matching row of the table without the bake row, the
    /// bake is reported on its own. both markers are always cleared
    static func launch(_ s: inout PDFRenderGuardState, restoredToken: String?) -> PDFLaunchOutcome {
        var out = PDFLaunchDecision.none
        if let ip = s.inProgress, ip.kind == .import {
            out = .importInterrupted(op: ip.op)
        } else if let ip = s.inProgress, ip.kind == .base || ip.kind == .vector,
                  let rt = restoredToken, ip.token == rt {
            s.suspect = rt
            out = .suppress
        } else if let rt = restoredToken, s.suspect == rt {
            out = .suppress
        }
        let bake = s.bakeInProgress != nil || s.inProgress?.kind == .bake
        s.inProgress = nil
        s.bakeInProgress = nil
        return PDFLaunchOutcome(decision: out, bakeInterrupted: bake)
    }

    static func resolve(_ s: inout PDFRenderGuardState, _ choice: PDFSuspectResolution) {
        if choice == .openAnyway || choice == .deleted { s.suspect = nil }
    }

    private static func verify(_ s: inout PDFRenderGuardState, token: String, kind: PDFGuardKind) {
        let had = s.verifiedKinds(token)
        s.verified.removeAll { $0.token == token }
        let kinds = kindOrder.filter { had.contains($0) || $0 == kind }
        s.verified.append((token, kinds))
        while s.verified.count > PDFTileConstants.guardMaxVerifiedTokens { s.verified.removeFirst() }
    }
}

/// The durable side. Plaintext JSON in Application Support, outside backups,
/// written atomically then fsynced before the risky render starts. Thread safe.
final class PDFRenderGuard {
    static let shared = PDFRenderGuard(url: PDFRenderGuard.defaultURL)

    static var defaultURL: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("pdf_render_guard.json")
    }

    private let url: URL
    private let lock = NSLock()
    private var state: PDFRenderGuardState

    init(url: URL) {
        self.url = url
        if let data = try? Data(contentsOf: url), let s = PDFRenderGuardState.decode(data) {
            state = s
        } else {
            state = PDFRenderGuardState()
        }
    }

    var snapshot: PDFRenderGuardState {
        lock.lock(); defer { lock.unlock() }
        return state
    }

    /// true when the marker is down and on disk. a failed write still lets the
    /// render go ahead, a broken guard shouldnt break the map
    @discardableResult
    func arm(kind: PDFGuardKind, token: String, foreground: Bool = true, op: String? = nil) -> Bool {
        guard PDFRenderGuardState.isToken(token) else { return false }
        lock.lock(); defer { lock.unlock() }
        guard PDFRenderGuardReducer.arm(&state, kind: kind, token: token, foreground: foreground, op: op) else {
            return false
        }
        persist()
        return true
    }

    func complete(kind: PDFGuardKind, token: String) {
        guard PDFRenderGuardState.isToken(token) else { return }
        lock.lock(); defer { lock.unlock() }
        let before = state
        PDFRenderGuardReducer.complete(&state, kind: kind, token: token)
        if state != before { persist() }
    }

    func disarmForBackground() {
        lock.lock(); defer { lock.unlock() }
        let before = state
        PDFRenderGuardReducer.disarmBackground(&state)
        if state != before { persist() }
    }

    func disarm(kind: PDFGuardKind, token: String) {
        lock.lock(); defer { lock.unlock() }
        let before = state
        PDFRenderGuardReducer.disarm(&state, kind: kind, token: token)
        if state != before { persist() }
    }

    func isVerified(kind: PDFGuardKind, token: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return state.verifiedKinds(token).contains(kind)
    }

    /// once per launch, before the saved map is restored
    func launchDecision(restoredToken: String?) -> PDFLaunchOutcome {
        lock.lock(); defer { lock.unlock() }
        let d = PDFRenderGuardReducer.launch(&state, restoredToken: restoredToken)
        persist()
        return d
    }

    func resolveSuspect(_ choice: PDFSuspectResolution) {
        lock.lock(); defer { lock.unlock() }
        PDFRenderGuardReducer.resolve(&state, choice)
        persist()
    }

    var suspect: String? {
        lock.lock(); defer { lock.unlock() }
        return state.suspect
    }

    private func persist() {
        guard let data = state.encoded() else { return }
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic])
            // atomic rename isnt durable until the bytes are flushed
            if let h = try? FileHandle(forUpdating: url) {
                try? h.synchronize()
                try? h.close()
            }
            try? fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                  ofItemAtPath: url.path)
            var u = url
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? u.setResourceValues(values)
        } catch {
            NSLog("[PDFRenderGuard] could not persist guard state")
        }
    }
}
