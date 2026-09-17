package com.tacmap.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLockPreferenceTest {
    @Test fun absentCredentialRecordIsDisabled() {
        val record = decodeAppLockCredentialRecord(
            saltStored = false,
            hashStored = false,
            saltHex = null,
            hashHex = null,
        )

        assertEquals(AppLockConfigurationState.DISABLED, record.state)
        assertNull(record.salt)
        assertNull(record.hash)
    }

    @Test fun partialWrongTypeOrMalformedCredentialRecordsFailClosedWithoutThrowing() {
        val corrupt = listOf(
            decodeAppLockCredentialRecord(true, false, "00".repeat(16), null),
            decodeAppLockCredentialRecord(false, true, null, "00".repeat(32)),
            decodeAppLockCredentialRecord(true, true, null, "00".repeat(32)),
            decodeAppLockCredentialRecord(true, true, "0", "00".repeat(32)),
            decodeAppLockCredentialRecord(true, true, "zz".repeat(16), "00".repeat(32)),
            decodeAppLockCredentialRecord(true, true, "00".repeat(16), "00".repeat(31)),
        )

        corrupt.forEach { assertEquals(AppLockConfigurationState.CORRUPT, it.state) }
    }

    @Test fun exactCredentialRecordDecodesAsEnabled() {
        val record = decodeAppLockCredentialRecord(
            saltStored = true,
            hashStored = true,
            saltHex = "07".repeat(16),
            hashHex = "a5".repeat(32),
        )

        assertEquals(AppLockConfigurationState.ENABLED, record.state)
        assertArrayEquals(ByteArray(16) { 0x07 }, record.salt)
        assertArrayEquals(ByteArray(32) { 0xa5.toByte() }, record.hash)
    }

    @Test fun failedDisableThatMutatesBackendMemoryKeepsPriorCredentialArmed() {
        val backend = MutatingFailureStorage()
        val lock = testLock(backend)
        assertTrue(lock.setPin("1234"))

        backend.failCredentialWrites = true
        assertFalse(lock.disable("1234"))
        assertFalse("backend memory was cleared before commit reported failure", backend.memory.hashStored)
        assertTrue(lock.isEnabled)
        assertTrue(lock.hasStorageError)
        assertTrue(lock.canAcceptPin)
        assertTrue("known credential remains effective", lock.verify("1234"))
    }

    @Test fun failedChangeRetainsOldCredentialDespiteBackendHoldingCandidate() {
        val backend = MutatingFailureStorage()
        val lock = testLock(backend)
        assertTrue(lock.setPin("1234"))

        backend.failCredentialWrites = true
        assertFalse(lock.changePin("1234", "5678"))
        assertTrue(lock.verify("1234"))
        assertFalse(lock.verify("5678"))
        assertTrue(lock.isEnabled)
        assertTrue(lock.hasStorageError)
    }

    @Test fun failedEnableArmsCandidateInsteadOfFollowingUncertainBackendState() {
        val backend = MutatingFailureStorage().apply { failCredentialWrites = true }
        val lock = testLock(backend)

        assertFalse(lock.setPin("1234"))
        assertTrue(lock.isEnabled)
        assertTrue(lock.hasStorageError)
        assertTrue(lock.canAcceptPin)
        assertTrue(lock.verify("1234"))
    }

    @Test fun failedAttemptWriteCannotBuyMoreCurrentProcessGuesses() {
        var now = 1_000L
        val backend = MutatingFailureStorage()
        val lock = AppLock(backend, nowMs = { now }, newSalt = { ByteArray(16) { 7 } })
        assertTrue(lock.setPin("1234"))
        backend.failAttemptWrites = true

        repeat(5) { assertFalse(lock.verify("9999")) }
        assertTrue(lock.lockoutRemainingMs() >= 30_000L)
        assertTrue(lock.hasStorageError)

        // Model a failed SharedPreferences commit being rolled back on disk.
        backend.memory = backend.memory.copy(failures = 0, lockedUntilMs = 0L)
        now += 1_000L
        assertTrue("in-process floor survives backend rollback", lock.lockoutRemainingMs() > 0L)
        assertFalse(lock.verify("1234"))
    }

    @Test fun applicationScopedInstanceKeepsLifecycleAndSettingsConsumersCoherent() {
        val backend = MutatingFailureStorage()
        val applicationScoped = testLock(backend)
        val lifecycleConsumer = applicationScoped
        val settingsConsumer = applicationScoped
        assertSame(lifecycleConsumer, settingsConsumer)

        assertTrue(settingsConsumer.setPin("1234"))
        assertTrue("lifecycle gate sees settings enable", lifecycleConsumer.isEnabled)

        assertTrue(settingsConsumer.changePin("1234", "5678"))
        assertTrue("lifecycle gate sees changed credential", lifecycleConsumer.verify("5678"))

        backend.failCredentialWrites = true
        assertFalse(settingsConsumer.disable("5678"))
        assertFalse("backend memory was cleared before reporting failure", backend.memory.hashStored)
        assertTrue("lifecycle gate remains armed after failed disable", lifecycleConsumer.isEnabled)
        assertTrue(lifecycleConsumer.verify("5678"))

        backend.failCredentialWrites = false
        assertTrue(settingsConsumer.disable("5678"))
        assertFalse("lifecycle gate sees settings disable", lifecycleConsumer.isEnabled)

        backend.failCredentialWrites = true
        assertFalse(settingsConsumer.setPin("2468"))
        assertTrue("failed initial enable still arms lifecycle gate", lifecycleConsumer.isEnabled)
        assertTrue(lifecycleConsumer.hasStorageError)
        assertTrue(lifecycleConsumer.verify("2468"))
    }

    @Test fun productionLifecycleAndSettingsShareTheTacticalAppOwnedInstance() {
        val app = sourceText("app/src/main/java/com/tacmap/app/TacticalApp.kt")
        val activity = sourceText("app/src/main/java/com/tacmap/app/MainActivity.kt")
        val screen = sourceText("app/src/main/java/com/tacmap/map/MapScreen.kt")

        assertTrue(app.contains("lateinit var appLock: AppLock"))
        assertTrue(app.contains("appLock = AppLock(this)"))
        assertTrue(activity.contains("get() = (application as TacticalApp).appLock"))
        assertTrue(activity.contains("appLock = appLock,"))
        assertFalse(activity.contains("AppLock(this)"))
        assertTrue(screen.contains("appLock: AppLock,"))
        assertFalse(screen.contains("AppLock(context)"))
        assertFalse(screen.contains("remember { com.tacmap.app.AppLock"))
    }

    private fun testLock(storage: AppLockStorage): AppLock = AppLock(
        storage = storage,
        nowMs = { 1_000L },
        newSalt = { ByteArray(16) { 7 } },
    )

    private fun sourceText(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val source = File(directory, relativePath)
            if (source.exists()) return source.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath")
    }

    private class MutatingFailureStorage : AppLockStorage {
        var memory = AppLockStorageSnapshot(false, false, null, null, 0, 0L)
        var failCredentialWrites = false
        var failAttemptWrites = false

        override fun read(): AppLockStorageSnapshot = memory

        override fun persistCredential(saltHex: String?, hashHex: String?): Boolean {
            memory = if (saltHex == null || hashHex == null) {
                AppLockStorageSnapshot(false, false, null, null, 0, 0L)
            } else {
                AppLockStorageSnapshot(true, true, saltHex, hashHex, 0, 0L)
            }
            return !failCredentialWrites
        }

        override fun persistAttempts(failures: Int, lockedUntilMs: Long): Boolean {
            memory = memory.copy(failures = failures, lockedUntilMs = lockedUntilMs)
            return !failAttemptWrites
        }
    }
}
