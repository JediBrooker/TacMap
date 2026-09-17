package com.tacmap.sync

import com.tacmap.settings.OpsecSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEndpointPolicyTest {
    @Test
    fun secureOriginsAndLegacySuffixesCanonicalize() {
        val cases = mapOf(
            "wss://relay.example" to "wss://relay.example",
            " WSS://RELAY.EXAMPLE/ " to "wss://relay.example",
            "wss://relay.example:443/room/" to "wss://relay.example",
            "wss://relay.example:8443/v3/room/" to "wss://relay.example:8443",
            "wss://[2001:db8::1]/room" to "wss://[2001:db8::1]",
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, valid(input, allowInsecureLoopback = false))
        }
    }

    @Test
    fun debugPlaintextIsRestrictedToExactLoopbackHosts() {
        assertEquals(
            "ws://localhost:8787",
            valid("ws://localhost:8787/v3/room/", allowInsecureLoopback = true),
        )
        assertEquals(
            "ws://127.0.0.1:8787",
            valid("ws://127.0.0.1:8787", allowInsecureLoopback = true),
        )
        assertEquals(
            "ws://[::1]:8787",
            valid("ws://[::1]:8787", allowInsecureLoopback = true),
        )
        assertInvalid("ws://localhost:8787", allowInsecureLoopback = false)
        assertInvalid("ws://relay.example", allowInsecureLoopback = true)
        assertInvalid("ws://api.localhost", allowInsecureLoopback = true)
    }

    @Test
    fun credentialsMetadataMalformedAuthorityAndArbitraryPathsAreRejected() {
        listOf(
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
            "wss://relay.example:abc",
        ).forEach { assertInvalid(it, allowInsecureLoopback = false) }
    }

    @Test
    fun persistedInvalidValueFallsBackAndLegacyValueRequestsRepair() {
        val fallback = RelayEndpointPolicy.resolvePersisted(
            rawValue = "wss://attacker.example/collect",
            defaultEndpoint = OpsecSettings.DEFAULT_RELAY,
            allowInsecureLoopback = false,
        )
        assertEquals(OpsecSettings.DEFAULT_RELAY, fallback.endpoint)
        assertTrue(fallback.needsRepair)

        val legacy = RelayEndpointPolicy.resolvePersisted(
            rawValue = "wss://relay.example/v3/room/",
            defaultEndpoint = OpsecSettings.DEFAULT_RELAY,
            allowInsecureLoopback = false,
        )
        assertEquals("wss://relay.example", legacy.endpoint)
        assertTrue(legacy.needsRepair)

        val current = RelayEndpointPolicy.resolvePersisted(
            rawValue = "wss://relay.example",
            defaultEndpoint = OpsecSettings.DEFAULT_RELAY,
            allowInsecureLoopback = false,
        )
        assertEquals("wss://relay.example", current.endpoint)
        assertFalse(current.needsRepair)
    }

    @Test
    fun syncManagerRuntimeValidationCannotBypassPolicy() {
        assertEquals(
            "wss://relay.example",
            SyncManager.validatedRelayBaseForRuntime(
                "wss://relay.example/room/",
                allowInsecureLoopback = false,
            ),
        )
        assertNull(
            SyncManager.validatedRelayBaseForRuntime(
                "wss://relay.example/attacker-path",
                allowInsecureLoopback = false,
            )
        )
        assertNull(
            SyncManager.validatedRelayBaseForRuntime(
                "ws://relay.example",
                allowInsecureLoopback = true,
            )
        )
    }

    @Test
    fun webSocketTransportCannotFollowRedirectsOutsideValidatedOrigin() {
        assertFalse(SyncWebSocketTransport.FOLLOWS_REDIRECTS)
        assertFalse(SyncWebSocketTransport.OFFERS_COMPRESSION)
        assertTrue(SyncWebSocketTransport.VERIFIES_TLS_HOSTNAME)
    }

    private fun valid(value: String, allowInsecureLoopback: Boolean): String =
        (RelayEndpointPolicy.normalize(value, allowInsecureLoopback) as RelayEndpointPolicy.Result.Valid)
            .endpoint

    private fun assertInvalid(value: String, allowInsecureLoopback: Boolean) {
        assertTrue(
            value,
            RelayEndpointPolicy.normalize(value, allowInsecureLoopback) is
                RelayEndpointPolicy.Result.Invalid,
        )
    }
}
