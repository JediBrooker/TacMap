import Foundation

// 3.0.2 M1: crash-loop breaker for imported MBTiles packs, same idea as the PDF
// one (PDFRenderGuard) but its own file and reducer. Armed durably before every
// foreground restore or activation opens a pack, cleared once the pack refused
// or its first draw settled. If the process dies in between, the next launch
// doesnt reopen the saved pack and asks instead. Only entry UUIDs go in the
// file. Pinned by import_limits.json mbtilesOpenGuard.

enum MBTilesLaunchDecision: String, Equatable {
    case none, suppress
}

struct MBTilesOpenGuardState: Equatable {
    static let fileVersion = 1
    static let maxInProgress = 4

    /// oldest first, one per pack
    var inProgress: [String] = []
    var suspect: String?

    static func isToken(_ s: String) -> Bool { UUID(uuidString: s) != nil }

    /// {"v":1,"inProgress":[..],"suspect":..}
    func encoded() -> Data? {
        let suspectJSON: Any = suspect.map { $0 as Any } ?? NSNull()
        guard let ip = try? JSONSerialization.data(withJSONObject: inProgress),
              let sus = try? JSONSerialization.data(withJSONObject: suspectJSON, options: .fragmentsAllowed) else {
            return nil
        }
        let s = "{\"v\":\(Self.fileVersion),\"inProgress\":\(String(decoding: ip, as: UTF8.self)),"
            + "\"suspect\":\(String(decoding: sus, as: UTF8.self))}"
        return Data(s.utf8)
    }

    /// v != 1 or junk = empty. non UUID tokens dropped, newest 4 kept
    static func decode(_ data: Data) -> MBTilesOpenGuardState {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (o["v"] as? Int) == fileVersion else { return MBTilesOpenGuardState() }
        var s = MBTilesOpenGuardState()
        for t in (o["inProgress"] as? [Any] ?? []).compactMap({ $0 as? String }) where isToken(t) {
            // the reducer never writes one twice, a hand edited file keeps the newest
            s.inProgress.removeAll { $0 == t }
            s.inProgress.append(t)
        }
        if s.inProgress.count > maxInProgress { s.inProgress.removeFirst(s.inProgress.count - maxInProgress) }
        if let sus = o["suspect"] as? String, isToken(sus) { s.suspect = sus }
        return s
    }
}

/// Pure transitions, fixture driven. The store persists after each
enum MBTilesOpenGuardReducer {
    /// background never arms, a jetsam kill there isnt the pack's fault
    @discardableResult
    static func arm(_ s: inout MBTilesOpenGuardState, token: String, foreground: Bool) -> Bool {
        guard foreground else { return false }
        s.inProgress.removeAll { $0 == token }
        s.inProgress.append(token)
        while s.inProgress.count > MBTilesOpenGuardState.maxInProgress { s.inProgress.removeFirst() }
        return true
    }

    static func complete(_ s: inout MBTilesOpenGuardState, token: String) {
        s.inProgress.removeAll { $0 == token }
    }

    static func disarmBackground(_ s: inout MBTilesOpenGuardState) {
        s.inProgress.removeAll()
    }

    /// once per launch. markers always come off
    static func launch(_ s: inout MBTilesOpenGuardState, restoredToken: String?) -> MBTilesLaunchDecision {
        var out = MBTilesLaunchDecision.none
        if let rt = restoredToken, s.inProgress.contains(rt) {
            s.suspect = rt
            out = .suppress
        } else if let rt = restoredToken, s.suspect == rt {
            out = .suppress
        }
        s.inProgress.removeAll()
        return out
    }

    static func resolve(_ s: inout MBTilesOpenGuardState, _ choice: PDFSuspectResolution) {
        if choice == .openAnyway || choice == .deleted { s.suspect = nil }
    }
}

/// The durable side. Plaintext JSON next to pdf_render_guard.json, outside
/// backups, written atomically then fsynced before the open starts. Thread safe
final class MBTilesOpenGuard {
    static let shared = MBTilesOpenGuard(url: MBTilesOpenGuard.defaultURL)
    static let fileName = "mbtiles_open_guard.json"

    static var defaultURL: URL {
        PDFRenderGuard.defaultURL.deletingLastPathComponent().appendingPathComponent(fileName)
    }

    private let url: URL
    private let lock = NSLock()
    private var state: MBTilesOpenGuardState

    init(url: URL) {
        self.url = url
        state = (try? Data(contentsOf: url)).map(MBTilesOpenGuardState.decode) ?? MBTilesOpenGuardState()
    }

    var snapshot: MBTilesOpenGuardState {
        lock.lock(); defer { lock.unlock() }
        return state
    }

    var suspect: String? { snapshot.suspect }

    /// true when the marker is down and on disk (or at least tried, a broken
    /// guard shouldnt break the map)
    @discardableResult
    func arm(token: String, foreground: Bool) -> Bool {
        guard MBTilesOpenGuardState.isToken(token) else { return false }
        lock.lock(); defer { lock.unlock() }
        guard MBTilesOpenGuardReducer.arm(&state, token: token, foreground: foreground) else { return false }
        persist()
        return true
    }

    func complete(token: String) {
        lock.lock(); defer { lock.unlock() }
        let before = state
        MBTilesOpenGuardReducer.complete(&state, token: token)
        if state != before { persist() }
    }

    func disarmForBackground() {
        lock.lock(); defer { lock.unlock() }
        let before = state
        MBTilesOpenGuardReducer.disarmBackground(&state)
        if state != before { persist() }
    }

    func launchDecision(restoredToken: String?) -> MBTilesLaunchDecision {
        lock.lock(); defer { lock.unlock() }
        let d = MBTilesOpenGuardReducer.launch(&state, restoredToken: restoredToken)
        persist()
        return d
    }

    func resolveSuspect(_ choice: PDFSuspectResolution) {
        lock.lock(); defer { lock.unlock() }
        let before = state
        MBTilesOpenGuardReducer.resolve(&state, choice)
        if state != before { persist() }
    }

    private func persist() {
        guard let data = state.encoded() else { return }
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic])
            // the rename isnt durable until the bytes are flushed
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
            NSLog("[MBTilesOpenGuard] could not persist guard state")
        }
    }
}

/// When an opened pack's first draw has settled: a read got delivered and none
/// has been pending for quietMs, or nothing asked within noReadMs of
/// publication. settle() runs onSettled once, also when the source is
/// replaced, closed or dropped first. Main thread only, calls hop there
final class MBTilesFirstDrawWatch {
    static let firstDrawQuietMs = 500
    static let noReadCompleteMs = 2000

    typealias Scheduler = (_ afterMs: Int, _ work: @escaping () -> Void) -> Void
    static let mainScheduler: Scheduler = { ms, work in
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(ms), execute: work)
    }

    private let schedule: Scheduler
    /// taken once under the lock, settle can come from deinit or any thread
    private let settleLock = NSLock()
    private var onSettled: (() -> Void)?
    private var started = false
    private var requested = 0
    private var pending = 0
    private var delivered = 0
    /// bumped by every new read so an older quiet timer does nothing
    private var generation = 0

    init(schedule: @escaping Scheduler = MBTilesFirstDrawWatch.mainScheduler, onSettled: @escaping () -> Void) {
        self.schedule = schedule
        self.onSettled = onSettled
    }

    deinit { onSettled?() }

    var isSettled: Bool {
        settleLock.lock(); defer { settleLock.unlock() }
        return onSettled == nil
    }

    private func onMain(_ f: @escaping () -> Void) {
        if Thread.isMainThread { f() } else { DispatchQueue.main.async(execute: f) }
    }

    /// the source just got published, the no read clock starts now
    func start() {
        onMain { [self] in
            guard !started, !isSettled else { return }
            started = true
            schedule(Self.noReadCompleteMs) { [weak self] in
                guard let self, self.requested == 0 else { return }
                self.settle()
            }
        }
    }

    func readStarted() {
        onMain { [self] in
            requested += 1
            pending += 1
            generation += 1
        }
    }

    /// delivered = the read ran and its answer went to the renderer, not cancelled
    func readFinished(delivered got: Bool) {
        onMain { [self] in
            pending = max(0, pending - 1)
            if got { delivered += 1 }
            guard pending == 0, delivered > 0, !isSettled else { return }
            let gen = generation
            schedule(Self.firstDrawQuietMs) { [weak self] in
                guard let self, self.generation == gen, self.pending == 0 else { return }
                self.settle()
            }
        }
    }

    func settle() {
        settleLock.lock()
        let done = onSettled
        onSettled = nil
        settleLock.unlock()
        done?()
    }
}
