import Foundation

/// Session used for coordinate/AO-bearing requests. It deliberately has no
/// persistent cookies, credentials or URL cache, so sensitive query URLs and
/// responses do not survive in the app's HTTP cache on disk.
enum NetworkSession {
    enum LimitError: Error { case responseTooLarge }
    static let ephemeral: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil
        config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 12
        return URLSession(configuration: config)
    }()

    static func data(for request: URLRequest, maximumBytes: Int) async throws -> (Data, URLResponse) {
        let (bytes, response) = try await ephemeral.bytes(for: request)
        if response.expectedContentLength > Int64(maximumBytes) { throw LimitError.responseTooLarge }
        var data = Data()
        data.reserveCapacity(min(maximumBytes, max(0, Int(response.expectedContentLength))))
        for try await byte in bytes {
            if data.count >= maximumBytes { throw LimitError.responseTooLarge }
            data.append(byte)
        }
        return (data, response)
    }
}
