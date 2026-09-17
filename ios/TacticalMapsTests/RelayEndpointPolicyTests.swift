import XCTest
@testable import TacticalMaps

final class RelayEndpointPolicyTests: XCTestCase {
    func testSecureOriginsAndLegacySuffixesCanonicalize() throws {
        let cases = [
            "wss://relay.example": "wss://relay.example",
            " WSS://RELAY.EXAMPLE/ ": "wss://relay.example",
            "wss://relay.example:443/room/": "wss://relay.example",
            "wss://relay.example:8443/v3/room/": "wss://relay.example:8443",
            "wss://[2001:db8::1]/room": "wss://[2001:db8::1]"
        ]
        for (input, expected) in cases {
            XCTAssertEqual(
                try RelayEndpointPolicy.normalize(input, allowInsecureLoopback: false),
                expected,
                input
            )
        }
    }

    func testDebugPlaintextIsRestrictedToExactLoopbackHosts() throws {
        XCTAssertEqual(
            try RelayEndpointPolicy.normalize(
                "ws://localhost:8787/v3/room/",
                allowInsecureLoopback: true
            ),
            "ws://localhost:8787"
        )
        XCTAssertEqual(
            try RelayEndpointPolicy.normalize(
                "ws://127.0.0.1:8787",
                allowInsecureLoopback: true
            ),
            "ws://127.0.0.1:8787"
        )
        XCTAssertEqual(
            try RelayEndpointPolicy.normalize(
                "ws://[::1]:8787",
                allowInsecureLoopback: true
            ),
            "ws://[::1]:8787"
        )
        XCTAssertThrowsError(
            try RelayEndpointPolicy.normalize(
                "ws://localhost:8787",
                allowInsecureLoopback: false
            )
        )
        XCTAssertThrowsError(
            try RelayEndpointPolicy.normalize(
                "ws://relay.example",
                allowInsecureLoopback: true
            )
        )
        XCTAssertThrowsError(
            try RelayEndpointPolicy.normalize(
                "ws://api.localhost",
                allowInsecureLoopback: true
            )
        )
    }

    func testCredentialsMetadataMalformedAuthorityAndArbitraryPathsAreRejected() {
        let invalid = [
            "",
            "relay.example",
            "https://relay.example",
            "wss://",
            "wss://user@relay.example",
            "wss://user:secret@relay.example",
            "wss://relay.example?tracking=1",
            "wss://relay.example#fragment",
            "wss://relay.example/custom",
            "wss://relay.example/room/extra",
            "wss://relay.example/%72oom",
            "wss://relay_example",
            "wss://999.999.999.999",
            "wss://relay.example:0",
            "wss://relay.example:",
            "wss://relay.example:65536",
            "wss://relay.example:abc"
        ]
        for value in invalid {
            XCTAssertThrowsError(
                try RelayEndpointPolicy.normalize(value, allowInsecureLoopback: false),
                value
            )
        }
    }

    func testPersistedInvalidValueFallsBackAndLegacyValueRequestsRepair() {
        let fallback = RelayEndpointPolicy.resolvePersisted(
            "wss://attacker.example/collect",
            defaultEndpoint: OpsecSettings.defaultRelay,
            allowInsecureLoopback: false
        )
        XCTAssertEqual(fallback.endpoint, OpsecSettings.defaultRelay)
        XCTAssertTrue(fallback.needsRepair)

        let legacy = RelayEndpointPolicy.resolvePersisted(
            "wss://relay.example/v3/room/",
            defaultEndpoint: OpsecSettings.defaultRelay,
            allowInsecureLoopback: false
        )
        XCTAssertEqual(legacy.endpoint, "wss://relay.example")
        XCTAssertTrue(legacy.needsRepair)

        let current = RelayEndpointPolicy.resolvePersisted(
            "wss://relay.example",
            defaultEndpoint: OpsecSettings.defaultRelay,
            allowInsecureLoopback: false
        )
        XCTAssertEqual(current.endpoint, "wss://relay.example")
        XCTAssertFalse(current.needsRepair)
    }

    func testSyncManagerRuntimeValidationCannotBypassPolicy() {
        XCTAssertEqual(
            SyncManager.validatedRelayBaseForRuntime(
                "wss://relay.example/room/",
                allowInsecureLoopback: false
            ),
            "wss://relay.example"
        )
        XCTAssertNil(
            SyncManager.validatedRelayBaseForRuntime(
                "wss://relay.example/attacker-path",
                allowInsecureLoopback: false
            )
        )
        XCTAssertNil(
            SyncManager.validatedRelayBaseForRuntime(
                "ws://relay.example",
                allowInsecureLoopback: true
            )
        )
    }

    func testWebSocketTransportRejectsRedirectsAndBindsFrameCeiling() {
        let delegate = SyncWebSocketSessionDelegate()
        let redirected = URLRequest(url: URL(string: "ws://attacker.example/room/id")!)
        XCTAssertNil(delegate.permittedRedirectRequest(redirected))

        let session = URLSession(configuration: .ephemeral)
        let task = session.webSocketTask(with: URL(string: "wss://relay.example/room/id")!)
        SyncWebSocketTransport.configure(task)
        XCTAssertEqual(task.maximumMessageSize, SyncInboundFramePolicy.maxFrameBytes)
        task.cancel()
        session.invalidateAndCancel()
    }

    func testWebSocketRequestSuppressesAutomaticCompressionOffer() throws {
        let original = URLRequest(url: try XCTUnwrap(URL(string: "wss://relay.example/v3/room/id")))
        let prepared = SyncWebSocketTransport.prepareRequest(original)

        XCTAssertEqual(
            prepared.value(forHTTPHeaderField: "Sec-WebSocket-Extensions"),
            "x-tacmap-no-compression"
        )
        XCTAssertFalse(
            prepared.value(forHTTPHeaderField: "Sec-WebSocket-Extensions")?
                .localizedCaseInsensitiveContains("permessage-deflate") ?? true
        )
    }
}
