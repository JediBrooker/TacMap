import Darwin
import Foundation

/// The single parser used for persisted, edited, and live Unit Sync relay URLs.
///
/// The setting represents a WebSocket origin, not an arbitrary URL. Historical
/// TacMap room suffixes are accepted only to migrate existing self-hosted
/// settings to one unambiguous canonical origin.
enum RelayEndpointPolicy {
    struct PersistedResolution: Equatable {
        let endpoint: String
        let needsRepair: Bool
    }

    struct ValidationError: LocalizedError, Equatable {
        let message: String
        var errorDescription: String? { message }
    }

    static var allowsInsecureLoopback: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }

    private static let acceptedPaths: Set<String> = [
        "", "/", "/room", "/room/", "/v3/room", "/v3/room/"
    ]

    static func normalize(
        _ rawValue: String,
        allowInsecureLoopback: Bool = allowsInsecureLoopback
    ) throws -> String {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { throw ValidationError(message: "Enter a Unit Sync relay address.") }
        guard let components = URLComponents(string: value),
              let rawScheme = components.scheme else {
            throw ValidationError(message: "The relay address is not a valid URL.")
        }
        guard components.user == nil, components.password == nil else {
            throw ValidationError(message: "Relay addresses cannot contain a username or password.")
        }
        guard components.percentEncodedQuery == nil, components.fragment == nil else {
            throw ValidationError(message: "Relay addresses cannot contain a query or fragment.")
        }
        guard !authority(in: value).hasSuffix(":") else {
            throw ValidationError(message: "The relay address contains an invalid port.")
        }
        guard acceptedPaths.contains(components.percentEncodedPath) else {
            throw ValidationError(message: "Use the relay origin only; custom paths are not allowed.")
        }
        guard let rawHost = components.host, !rawHost.isEmpty else {
            throw ValidationError(message: "The relay address must include wss:// and a valid host.")
        }
        let host = rawHost
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            .lowercased()
        guard isValidHost(host) else {
            throw ValidationError(message: "The relay address contains an invalid host.")
        }
        if let port = components.port, !(1...65_535).contains(port) {
            throw ValidationError(message: "The relay address contains an invalid port.")
        }

        let scheme = rawScheme.lowercased()
        let loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        switch scheme {
        case "wss":
            break
        case "ws" where allowInsecureLoopback && loopback:
            break
        case "ws":
            throw ValidationError(
                message: "Unencrypted ws:// is allowed only for a loopback relay in a debug build."
            )
        default:
            throw ValidationError(message: "Unit Sync relay addresses must use wss://.")
        }

        var canonical = URLComponents()
        canonical.scheme = scheme
        canonical.host = host.contains(":") ? "[\(host)]" : host
        if let port = components.port,
           !((scheme == "wss" && port == 443) || (scheme == "ws" && port == 80)) {
            canonical.port = port
        }
        guard let endpoint = canonical.string else {
            throw ValidationError(message: "The relay address could not be normalized.")
        }
        return endpoint
    }

    static func resolvePersisted(
        _ rawValue: String?,
        defaultEndpoint: String,
        allowInsecureLoopback: Bool = allowsInsecureLoopback
    ) -> PersistedResolution {
        guard let rawValue else {
            return PersistedResolution(endpoint: defaultEndpoint, needsRepair: false)
        }
        do {
            let endpoint = try normalize(rawValue, allowInsecureLoopback: allowInsecureLoopback)
            return PersistedResolution(endpoint: endpoint, needsRepair: rawValue != endpoint)
        } catch {
            return PersistedResolution(endpoint: defaultEndpoint, needsRepair: true)
        }
    }

    private static func isValidHost(_ host: String) -> Bool {
        guard !host.isEmpty,
              host.utf8.count <= 253,
              host.unicodeScalars.allSatisfy({ $0.isASCII && $0.value >= 0x21 && $0.value <= 0x7e })
        else { return false }

        if host.contains(":") {
            guard host.allSatisfy({ $0.isHexDigit || $0 == ":" || $0 == "." }) else { return false }
            var address = in6_addr()
            return host.withCString { inet_pton(AF_INET6, $0, &address) == 1 }
        }
        if host.allSatisfy({ $0.isNumber || $0 == "." }) {
            var address = in_addr()
            return host.withCString { inet_pton(AF_INET, $0, &address) == 1 }
        }
        return host.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            guard (1...63).contains(label.count),
                  label.first?.isLetter == true || label.first?.isNumber == true,
                  label.last?.isLetter == true || label.last?.isNumber == true
            else { return false }
            return label.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
        }
    }

    private static func authority(in value: String) -> Substring {
        guard let marker = value.range(of: "://") else { return "" }
        let remainder = value[marker.upperBound...]
        let end = remainder.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" })
            ?? remainder.endIndex
        return remainder[..<end]
    }
}
