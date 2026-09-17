import Foundation

/// A redirect must never move Unit Sync away from the origin and path that were
/// approved by `RelayEndpointPolicy`.
final class SyncWebSocketSessionDelegate: NSObject, URLSessionTaskDelegate {
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

/// Generation-scoped aggregate receive budget for complete string/data
/// messages delivered by URLSessionWebSocketTask. The native API consumes
/// RFC 6455 control frames internally, so those frames cannot be counted here.
/// The transition from the bounded initial snapshot allowance to live mode
/// resets into a smaller rolling window.
final class SyncLiveReceiveBudget {
    enum Phase: Equatable { case initial, live }

    static let maxInitialFrames = 10_000
    static let maxInitialBytes = 54_525_952
    static let maxFrames = 200
    static let maxBytes = 4 * 1_048_576
    static let windowSeconds: TimeInterval = 10

    private var generation: Int64?
    private var phase: Phase?
    private var windowStart: TimeInterval = 0
    private var frames = 0
    private var bytes = 0

    func admit(
        generation newGeneration: Int64,
        byteCount: Int,
        phase newPhase: Phase,
        now: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> Bool {
        guard byteCount >= 0 else { return false }
        if generation != newGeneration || phase != newPhase {
            generation = newGeneration
            phase = newPhase
            windowStart = now
            frames = 0
            bytes = 0
        }
        if newPhase == .initial {
            guard frames < Self.maxInitialFrames,
                  byteCount <= Self.maxInitialBytes - bytes else { return false }
            frames += 1
            bytes += byteCount
            return true
        }
        if now < windowStart || now - windowStart >= Self.windowSeconds {
            windowStart = now
            frames = 0
            bytes = 0
        }
        guard frames < Self.maxFrames,
              byteCount <= Self.maxBytes - bytes else { return false }
        frames += 1
        bytes += byteCount
        return true
    }
}
