package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthBoundChangeControllerTest {
    private class FakeProtection(initial: Boolean) : AuthBoundChangeController.KeyProtection {
        private var currentAuthBound: Boolean = initial
        override val isAuthBound: Boolean get() = currentAuthBound
        var mutations = 0
        override fun setAuthBound(enabled: Boolean) {
            mutations++
            currentAuthBound = enabled
        }
    }

    @Test fun disableCannotMutateBeforeSuccessfulFreshCredential() {
        val key = FakeProtection(true)
        val controller = AuthBoundChangeController(key)

        assertEquals(
            AuthBoundChangeController.Request.PromptCredential,
            controller.request(target = false, deviceSecure = true),
        )
        assertTrue(key.isAuthBound)
        assertEquals(0, key.mutations)

        val completed = controller.completeCredential(approved = true)
        assertEquals(1, key.mutations)
        assertEquals(false, completed.isAuthBound)
    }

    @Test fun cancelledDisableLeavesAuthBoundProtectionEnabled() {
        val key = FakeProtection(true)
        val controller = AuthBoundChangeController(key)
        controller.request(target = false, deviceSecure = true)

        val cancelled = controller.completeCredential(approved = false)

        assertTrue(cancelled.isAuthBound)
        assertTrue(key.isAuthBound)
        assertEquals(0, key.mutations)
    }
}
