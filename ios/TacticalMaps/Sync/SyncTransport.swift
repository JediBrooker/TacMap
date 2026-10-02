import Foundation
import Network

// Transport seam for SyncManager (audit S6-02). The manager only ever talks to
// these protocols so unit tests can drive the whole state machine with a fake
// socket, a manual clock and a fake path monitor. Production wires the
// URLSession / NWPathMonitor versions below.

enum SyncSocketMessage: Equatable {
    case string(String)
    case data(Data)
}

/// One WebSocket. Every callback is delivered on the main actor. Close codes
/// and HTTP statuses are relay statements, they never carry trust.
@MainActor
protocol SyncSocket: AnyObject {
    /// Fires once when the upgrade finished (101). Optional, the manager also
    /// treats the first inbound message as "opened".
    var onOpen: (@MainActor () -> Void)? { get set }
    /// Raw close code the peer sent, 0 when there was none.
    var closeCode: Int { get }
    /// HTTP status of the upgrade response when the socket never opened.
    var httpStatusCode: Int? { get }

    func resume()
    /// completion fires when this exact frame finished writing (or failed).
    func send(text: String, completion: @escaping @MainActor (Error?) -> Void)
    func receive(completion: @escaping @MainActor (Result<SyncSocketMessage, Error>) -> Void)
    func sendPing(completion: @escaping @MainActor (Error?) -> Void)
    func cancel(closeCode: Int, reason: Data?)
}

@MainActor
protocol SyncSocketFactory: AnyObject {
    func makeSocket(request: URLRequest) -> SyncSocket
}

/// Cancellation handle for anything the scheduler queued.
@MainActor
protocol SyncCancellable: AnyObject {
    func cancel()
}

/// Clock + timers. nowMs is monotonic (uptime), wallMs is unix millis and only
/// feeds the hello epoch time floor.
@MainActor
protocol SyncScheduler: AnyObject {
    var nowMs: Int64 { get }
    var wallMs: Int64 { get }
    @discardableResult
    func schedule(afterMs: Int64, _ work: @escaping @MainActor () -> Void) -> SyncCancellable
}

struct SyncPathStatus: Equatable {
    let satisfied: Bool
    /// Sorted interface types, so a wifi -> cellular swap shows up as a change.
    let interfaces: [String]
}

@MainActor
protocol SyncPathMonitoring: AnyObject {
    var onChange: (@MainActor (SyncPathStatus) -> Void)? { get set }
    func start()
    func stop()
}

// MARK: - Production implementations

/// URLSessionWebSocketTask wrapper. Hops every callback back onto main.
@MainActor
final class URLSessionSyncSocket: SyncSocket {
    let task: URLSessionWebSocketTask
    var onOpen: (@MainActor () -> Void)?
    private weak var delegate: SyncWebSocketSessionDelegate?

    init(task: URLSessionWebSocketTask, delegate: SyncWebSocketSessionDelegate) {
        self.task = task
        self.delegate = delegate
        delegate.register(taskIdentifier: task.taskIdentifier) { [weak self] in
            Task { @MainActor in self?.onOpen?() }
        }
    }

    deinit {
        // delegate map only holds a weak-ish closure, drop it so it cant grow forever
        let identifier = task.taskIdentifier
        let delegate = delegate
        delegate?.unregister(taskIdentifier: identifier)
    }

    var closeCode: Int { task.closeCode.rawValue }

    var httpStatusCode: Int? { (task.response as? HTTPURLResponse)?.statusCode }

    func resume() { task.resume() }

    func send(text: String, completion: @escaping @MainActor (Error?) -> Void) {
        task.send(.string(text)) { error in
            Task { @MainActor in completion(error) }
        }
    }

    func receive(completion: @escaping @MainActor (Result<SyncSocketMessage, Error>) -> Void) {
        task.receive { result in
            let mapped: Result<SyncSocketMessage, Error>
            switch result {
            case .failure(let error):
                mapped = .failure(error)
            case .success(.string(let text)):
                mapped = .success(.string(text))
            case .success(.data(let data)):
                mapped = .success(.data(data))
            case .success:
                // unknown future message kind, treat like binary junk
                mapped = .success(.data(Data([0xff])))
            }
            Task { @MainActor in completion(mapped) }
        }
    }

    func sendPing(completion: @escaping @MainActor (Error?) -> Void) {
        task.sendPing { error in
            Task { @MainActor in completion(error) }
        }
    }

    func cancel(closeCode: Int, reason: Data?) {
        let code = URLSessionWebSocketTask.CloseCode(rawValue: closeCode) ?? .goingAway
        task.cancel(with: code, reason: reason)
    }
}

@MainActor
final class URLSessionSyncSocketFactory: SyncSocketFactory {
    private let delegate = SyncWebSocketSessionDelegate()
    private lazy var session = SyncWebSocketTransport.makeSession(delegate: delegate)

    func makeSocket(request: URLRequest) -> SyncSocket {
        let task = session.webSocketTask(with: request)
        SyncWebSocketTransport.configure(task)
        return URLSessionSyncSocket(task: task, delegate: delegate)
    }
}

@MainActor
final class DispatchSyncScheduler: SyncScheduler {
    private final class Token: SyncCancellable {
        var cancelled = false
        var item: DispatchWorkItem?
        func cancel() {
            cancelled = true
            item?.cancel()
            item = nil
        }
    }

    var nowMs: Int64 { Int64((ProcessInfo.processInfo.systemUptime * 1000).rounded(.down)) }
    var wallMs: Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }

    func schedule(afterMs: Int64, _ work: @escaping @MainActor () -> Void) -> SyncCancellable {
        let token = Token()
        let item = DispatchWorkItem {
            Task { @MainActor in
                // the item can fire after cancel raced it, so check again on main
                guard !token.cancelled else { return }
                token.item = nil
                work()
            }
        }
        token.item = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(Int(max(0, afterMs))),
            execute: item
        )
        return token
    }
}

/// NWPathMonitor on its own queue, results hop to main.
@MainActor
final class NWPathSyncMonitor: SyncPathMonitoring {
    var onChange: (@MainActor (SyncPathStatus) -> Void)?
    private var monitor: NWPathMonitor?

    func start() {
        guard monitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            var types: [String] = []
            if path.usesInterfaceType(.wifi) { types.append("wifi") }
            if path.usesInterfaceType(.cellular) { types.append("cellular") }
            if path.usesInterfaceType(.wiredEthernet) { types.append("wired") }
            if path.usesInterfaceType(.other) { types.append("other") }
            let status = SyncPathStatus(satisfied: path.status == .satisfied, interfaces: types.sorted())
            Task { @MainActor in self?.onChange?(status) }
        }
        // path updates are rare, a shared utility queue is plenty
        monitor.start(queue: .global(qos: .utility))
        self.monitor = monitor
    }

    func stop() {
        monitor?.cancel()
        monitor = nil
    }
}
