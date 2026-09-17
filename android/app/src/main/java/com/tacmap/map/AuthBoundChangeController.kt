package com.tacmap.map

import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage
import com.tacmap.localization.displayMessage

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
        data class Error(val pendingMessage: LocalizedMessage) : Request {
            val message: String get() = pendingMessage.text
        }
    }

    data class Completion(val isAuthBound: Boolean, val pendingError: LocalizedMessage? = null) {
        val error: String? get() = pendingError?.text
    }

    private var pendingTarget: Boolean? = null

    fun request(target: Boolean, deviceSecure: Boolean): Request {
        if (target == keyProtection.isAuthBound) return Request.NoChange
        if (!deviceSecure) {
            return Request.Error(Messages.displaySetADevicePinPatternOrPasswordFirstThenMessage())
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
            onFailure = { Completion(keyProtection.isAuthBound, if (it.message.isNullOrBlank()) Messages.displayKeyProtectionCouldNotBeChangedMessage() else it.displayMessage) },
        )
    }
}
