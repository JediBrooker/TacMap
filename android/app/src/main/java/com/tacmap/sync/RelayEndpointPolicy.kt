package com.tacmap.sync

import com.tacmap.localization.L10n

import com.tacmap.BuildConfig
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * The single parser used for persisted, edited, and live Unit Sync relay URLs.
 *
 * A relay setting is an origin, not an arbitrary WebSocket URL. Keeping that
 * distinction here prevents credentials, tracking parameters, or attacker-
 * controlled paths from being smuggled into the transport. Historical TacMap
 * suffixes are accepted only so existing self-hosted settings can migrate to
 * the canonical origin form.
 */
internal object RelayEndpointPolicy {
    data class PersistedResolution(
        val endpoint: String,
        val needsRepair: Boolean,
    )

    sealed interface Result {
        data class Valid(val endpoint: String) : Result
        data class Invalid(val message: String) : Result
    }

    private val acceptedPaths = setOf("", "/", "/room", "/room/", "/v3/room", "/v3/room/")
    private val dnsLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private val decimalIpv4 = Regex("[0-9.]+")
    private val ipv6Characters = Regex("[0-9A-Fa-f:.]+")

    fun normalize(
        rawValue: String,
        allowInsecureLoopback: Boolean = BuildConfig.DEBUG,
    ): Result {
        val value = rawValue.trim()
        if (value.isEmpty()) return Result.Invalid(L10n.text("Enter a Unit Sync relay address."))

        val uri = runCatching { URI(value) }.getOrNull()
            ?: return Result.Invalid(L10n.text("The relay address is not a valid URL."))
        if (!uri.isAbsolute || uri.isOpaque) {
            return Result.Invalid(L10n.text("The relay address must include wss:// and a host."))
        }
        if (uri.rawUserInfo != null) {
            return Result.Invalid(L10n.text("Relay addresses cannot contain a username or password."))
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return Result.Invalid(L10n.text("Relay addresses cannot contain a query or fragment."))
        }
        if (uri.rawAuthority?.endsWith(':') == true) {
            return Result.Invalid(L10n.text("The relay address contains an invalid port."))
        }
        val path = uri.rawPath ?: ""
        if (path !in acceptedPaths) {
            return Result.Invalid(L10n.text("Use the relay origin only; custom paths are not allowed."))
        }

        val host = uri.host
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.lowercase(Locale.US)
            ?: return Result.Invalid(L10n.text("The relay address must contain a valid host."))
        if (!isValidHost(host)) {
            return Result.Invalid(L10n.text("The relay address contains an invalid host."))
        }
        val port = uri.port
        if (port == 0 || port > 65_535) {
            return Result.Invalid(L10n.text("The relay address contains an invalid port."))
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        when {
            scheme == "wss" -> Unit
            scheme == "ws" && allowInsecureLoopback && loopback -> Unit
            scheme == "ws" -> return Result.Invalid(
                L10n.text("Unencrypted ws:// is allowed only for a loopback relay in a debug build.")
            )
            else -> return Result.Invalid(L10n.text("Unit Sync relay addresses must use wss://."))
        }

        val canonicalPort = when {
            scheme == "wss" && port == 443 -> -1
            scheme == "ws" && port == 80 -> -1
            else -> port
        }
        val canonical = runCatching {
            URI(scheme, null, host, canonicalPort, null, null, null).toASCIIString()
        }.getOrNull() ?: return Result.Invalid(L10n.text("The relay address could not be normalized."))
        return Result.Valid(canonical)
    }

    fun resolvePersisted(
        rawValue: String?,
        defaultEndpoint: String,
        allowInsecureLoopback: Boolean = BuildConfig.DEBUG,
    ): PersistedResolution {
        if (rawValue == null) {
            return PersistedResolution(defaultEndpoint, needsRepair = false)
        }
        return when (val parsed = normalize(rawValue, allowInsecureLoopback)) {
            is Result.Valid -> PersistedResolution(
                endpoint = parsed.endpoint,
                needsRepair = rawValue != parsed.endpoint,
            )
            is Result.Invalid -> PersistedResolution(defaultEndpoint, needsRepair = true)
        }
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253 || !host.all { it.code in 0x21..0x7e }) return false
        if (host.contains(':')) {
            if (!ipv6Characters.matches(host)) return false
            return runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
        }
        if (decimalIpv4.matches(host)) {
            val octets = host.split('.')
            return octets.size == 4 && octets.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.toIntOrNull() in 0..255
            }
        }
        return host.length <= 253 && host.split('.').all { dnsLabel.matches(it) }
    }
}
