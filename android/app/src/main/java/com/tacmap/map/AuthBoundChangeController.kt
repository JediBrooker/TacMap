package com.tacmap.map

/** Small state machine separating user authentication from key rotation. The
 * downgrade to device-bound storage can only reach [KeyProtection.setAuthBound]
 * after a fresh platform credential result has been accepted. */
class AuthBoundChangeController(
    private val keyProtection: KeyProtection,
) {
    interface KeyProtection {
        val isAuthBound: Boolean
        fun setAuthBound(enabled: Boolean)
    }

    sealed interface Request {
        data object NoChange : Request
        data object PromptCredential : Request
        data class Error(val message: String) : Request
    }

    data class Completion(val isAuthBound: Boolean, val error: String? = null)

    private var pendingTarget: Boolean? = null

    fun request(target: Boolean, deviceSecure: Boolean): Request {
        if (target == keyProtection.isAuthBound) return Request.NoChange
        if (!deviceSecure) {
            return Request.Error("Set a device PIN, pattern or password first, then try again.")
        }
        pendingTarget = target
        return Request.PromptCredential
    }

    fun cancelPending(): Completion {
        pendingTarget = null
        return Completion(keyProtection.isAuthBound)
    }

    fun completeCredential(approved: Boolean): Completion {
        val target = pendingTarget
        pendingTarget = null
        if (!approved || target == null) return Completion(keyProtection.isAuthBound)
        return runCatching { keyProtection.setAuthBound(target) }.fold(
            onSuccess = { Completion(keyProtection.isAuthBound) },
            onFailure = { Completion(keyProtection.isAuthBound, it.message ?: "Key protection could not be changed") },
        )
    }
}
