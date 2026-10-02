import Foundation

/// A redirect must never move Unit Sync away from the origin and path that were
/// approved by `RelayEndpointPolicy`.
final class SyncWebSocketSessionDelegate: NSObject, URLSessionWebSocketDelegate {
    // taskIdentifier -> open callback. Delegate callbacks land on the session
    // queue so guard the map with a lock.
    private let lock = NSLock()
    private var openHandlers: [Int: () -> Void] = [:]

    func register(taskIdentifier: Int, onOpen: @escaping () -> Void) {
        lock.lock(); defer { lock.unlock() }
        openHandlers[taskIdentifier] = onOpen
    }

    func unregister(taskIdentifier: Int) {
        lock.lock(); defer { lock.unlock() }
        openHandlers.removeValue(forKey: taskIdentifier)
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        lock.lock()
        let handler = openHandlers[webSocketTask.taskIdentifier]
        lock.unlock()
        handler?()
    }

    func permittedRedirectRequest(_ request: URLRequest) -> URLRequest? {
        nil
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(permittedRedirectRequest(request))
    }
}

enum SyncWebSocketTransport {
    static let maximumMessageSize = SyncInboundFramePolicy.maxFrameBytes
    /// CFNetwork otherwise adds `permessage-deflate` automatically. Advertising
    /// a valid extension token that TacMap does not implement suppresses that
    /// offer; a conforming relay ignores the unknown extension. This keeps the
    /// message ceiling independent of ambiguous pre/post-inflation accounting.
    static let compressionSuppressionExtension = "x-tacmap-no-compression"

    static func makeSession(delegate: URLSessionTaskDelegate) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(
            configuration: configuration,
            delegate: delegate,
            delegateQueue: nil
        )
    }

    static func configure(_ task: URLSessionWebSocketTask) {
        task.maximumMessageSize = maximumMessageSize
    }

    static func prepareRequest(_ request: URLRequest) -> URLRequest {
        var prepared = request
        prepared.setValue(
            compressionSuppressionExtension,
            forHTTPHeaderField: "Sec-WebSocket-Extensions"
        )
        return prepared
    }
}

enum SyncInboundFrameRejection: Equatable {
    case oversized
    case invalidUTF8
    case rateLimited
}

enum SyncInboundFrameDecision: Equatable {
    case accept(text: String, data: Data)
    case reject(SyncInboundFrameRejection)
}

/// Admission happens before JSON parsing and uses WebSocket wire bytes. A Swift
/// Character count is not a byte bound, and binary frames must be bounded before
/// attempting UTF-8 decoding.
enum SyncInboundFramePolicy {
    static let maxFrameBytes = 1_048_576

    static func inspect(text: String) -> SyncInboundFrameDecision {
        // Count the existing UTF-8 view before duplicating it into Data.
        guard text.utf8.count <= maxFrameBytes else { return .reject(.oversized) }
        let data = Data(text.utf8)
        return .accept(text: text, data: data)
    }

    static func inspect(data: Data) -> SyncInboundFrameDecision {
        guard data.count <= maxFrameBytes else { return .reject(.oversized) }
        guard let text = String(data: data, encoding: .utf8) else {
            return .reject(.invalidUTF8)
        }
        return .accept(text: text, data: data)
    }
}

/// At most one close/cancel is emitted for one connection generation even if a
/// hostile task races several queued callbacks onto the main actor.
final class SyncInboundFrameCloseGate {
    private var closedGeneration: Int64?

    func claimClose(generation: Int64) -> Bool {
        guard closedGeneration != generation else { return false }
        closedGeneration = generation
        return true
    }
}
