package com.tacmap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataKeyAuthModeTransitionTest {

    @Test
    fun successfulEnableAnchorsAndActivatesBeforeDeletingDeviceMaterial() {
        val fake = EnableFake()

        val result = DataKeyAuthModeTransition.enable(fake)

        assertTrue(result.completed)
        assertEquals(DataKeyAuthModeTransition.Stage.entries, fake.events)
        assertEquals(DataKeyProtectionMode.AUTH, fake.selected()?.mode)
        assertFalse(fake.deviceDekAccessible())
        assertFalse(fake.records.values.any { it.mode == DataKeyProtectionMode.DEVICE })
        assertFalse(fake.deviceKek)
        assertTrue(
            fake.events.indexOf(DataKeyAuthModeTransition.Stage.READ_BACK_AUTH_ACTIVATION) <
                fake.events.indexOf(DataKeyAuthModeTransition.Stage.REMOVE_DEVICE_RECORDS)
        )
        assertTrue(
            fake.events.indexOf(DataKeyAuthModeTransition.Stage.READ_BACK_DEVICE_RECORD_REMOVAL) <
                fake.events.indexOf(DataKeyAuthModeTransition.Stage.DELETE_DEVICE_KEK)
        )
    }

    @Test
    fun coldRestartPreferenceRollbackCannotSelectDeviceAfterEnable() {
        val fake = EnableFake()
        val beforeEnable = fake.snapshot()
        assertTrue(DataKeyAuthModeTransition.enable(fake).completed)

        // A cold restart with the entire mutable preference file restored to
        // its pre-enable contents still retains the Keystore anchor.
        fake.restorePreferences(beforeEnable)
        assertTrue(fake.authAnchor)
        assertNull(fake.selected())
        assertFalse(fake.deviceDekAccessible())

        // A torn snapshot containing both slots but the old DEVICE pointer is
        // recoverable: the anchor selects the only AUTH record.
        fake.records["b"] = authRecord()
        fake.activeSlot = "a"
        fake.backupSlot = "a"
        assertEquals(DataKeyProtectionMode.AUTH, fake.selected()?.mode)
        assertFalse(fake.deviceDekAccessible())
    }

    @Test
    fun everyPostAnchorFailureRemainsFailClosedAcrossRestart() {
        val postAnchorStages = DataKeyAuthModeTransition.Stage.entries.dropWhile {
            it != DataKeyAuthModeTransition.Stage.INSTALL_AUTH_ANCHOR
        }

        postAnchorStages.forEach { failedStage ->
            val fake = EnableFake(failAt = failedStage)
            val result = DataKeyAuthModeTransition.enable(fake)

            assertFalse("$failedStage unexpectedly completed", result.completed)
            assertEquals(failedStage, result.failedAt)
            assertTrue("$failedStage did not leave the protection floor anchored", fake.authAnchor)
            assertFalse("$failedStage left DEVICE selectable", fake.deviceDekAccessible())

            // Process death does not remove the Keystore anchor. Re-evaluating
            // only persisted state must still reject DEVICE.
            assertFalse("$failedStage downgraded after cold selection", fake.deviceDekAccessible())
        }
    }

    @Test
    fun preAnchorFailureNeverDeletesTheStillAuthoritativeDevicePath() {
        val stages = listOf(
            DataKeyAuthModeTransition.Stage.PERSIST_AUTH_RECORD,
            DataKeyAuthModeTransition.Stage.READ_BACK_AUTH_RECORD,
            DataKeyAuthModeTransition.Stage.VERIFY_AUTH_RECORD,
        )
        stages.forEach { failedStage ->
            val fake = EnableFake(failAt = failedStage)
            val result = DataKeyAuthModeTransition.enable(fake)

            assertFalse(result.completed)
            assertFalse(fake.authAnchor)
            assertTrue(fake.deviceKek)
            assertTrue(fake.deviceDekAccessible())
            assertFalse(fake.events.contains(DataKeyAuthModeTransition.Stage.REMOVE_DEVICE_RECORDS))
            assertFalse(fake.events.contains(DataKeyAuthModeTransition.Stage.DELETE_DEVICE_KEK))
        }
    }

    @Test
    fun disablingDeletesAnchorOnlyAfterVerifiedDeviceActivation() {
        val fake = DisableFake()

        val result = DataKeyAuthModeTransition.disable(fake)

        assertTrue(result.completed)
        assertEquals(DataKeyAuthModeTransition.DisableStage.entries, fake.events)
        assertFalse(fake.authAnchor)
        assertEquals(DataKeyProtectionMode.DEVICE, fake.selected()?.mode)
        assertTrue(
            fake.events.indexOf(
                DataKeyAuthModeTransition.DisableStage.READ_BACK_DEVICE_ACTIVATION
            ) < fake.events.indexOf(DataKeyAuthModeTransition.DisableStage.DELETE_AUTH_ANCHOR)
        )
    }

    @Test
    fun disablingFailureBeforeAnchorDeletionCannotSelectPreparedDeviceRecord() {
        val protectedStages = DataKeyAuthModeTransition.DisableStage.entries.takeWhile {
            it != DataKeyAuthModeTransition.DisableStage.DELETE_AUTH_ANCHOR
        } + DataKeyAuthModeTransition.DisableStage.DELETE_AUTH_ANCHOR

        protectedStages.forEach { failedStage ->
            val fake = DisableFake(failAt = failedStage)
            val result = DataKeyAuthModeTransition.disable(fake)

            assertFalse(result.completed)
            assertTrue("$failedStage removed the anchor early", fake.authAnchor)
            assertEquals(DataKeyProtectionMode.AUTH, fake.selected()?.mode)
        }
    }

    @Test
    fun anchoredSelectionNeverFallsBackToDeviceBackupOrUniqueDeviceSlot() {
        val device = deviceRecord()
        assertNull(
            DataKeyModePolicy.select(
                authModeAnchored = true,
                activeSlot = "a",
                backupSlot = "a",
                records = listOf(device),
            )
        )
        assertEquals(
            DataKeyProtectionMode.AUTH,
            DataKeyModePolicy.select(
                authModeAnchored = true,
                activeSlot = "a",
                backupSlot = "a",
                records = listOf(device, authRecord()),
            )?.mode
        )
    }

    private data class PreferenceSnapshot(
        val records: Map<String, DataKeySlotRecord>,
        val activeSlot: String?,
        val backupSlot: String?,
    )

    private class EnableFake(
        private val failAt: DataKeyAuthModeTransition.Stage? = null,
    ) : DataKeyAuthModeTransition.Steps {
        val records = linkedMapOf("a" to deviceRecord())
        var activeSlot: String? = "a"
        var backupSlot: String? = "a"
        var authAnchor = false
        var deviceKek = true
        val events = mutableListOf<DataKeyAuthModeTransition.Stage>()

        override fun persistAuthRecord(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.PERSIST_AUTH_RECORD,
            mutation = { records["b"] = authRecord() },
        )

        override fun readBackAuthRecord(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.READ_BACK_AUTH_RECORD
        ) && records["b"]?.mode == DataKeyProtectionMode.AUTH

        override fun verifyAuthRecord(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.VERIFY_AUTH_RECORD
        )

        override fun installAuthAnchor(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.INSTALL_AUTH_ANCHOR,
            mutation = { authAnchor = true },
            mutateWhenFailing = true,
        )

        override fun readBackAuthAnchor(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.READ_BACK_AUTH_ANCHOR
        ) && authAnchor

        override fun activateAuthRecord(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.ACTIVATE_AUTH_RECORD,
            mutation = {
                activeSlot = "b"
                backupSlot = "b"
            },
        )

        override fun readBackAuthActivation(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.READ_BACK_AUTH_ACTIVATION
        ) && activeSlot == "b" && backupSlot == "b"

        override fun removeDeviceRecords(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.REMOVE_DEVICE_RECORDS,
            mutation = { records.remove("a") },
        )

        override fun readBackDeviceRecordRemoval(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.READ_BACK_DEVICE_RECORD_REMOVAL
        ) && records.values.none { it.mode == DataKeyProtectionMode.DEVICE }

        override fun deleteDeviceKek(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.DELETE_DEVICE_KEK,
            mutation = { deviceKek = false },
        )

        override fun readBackDeviceKekDeletion(): Boolean = perform(
            DataKeyAuthModeTransition.Stage.READ_BACK_DEVICE_KEK_DELETION
        ) && !deviceKek

        fun selected(): DataKeySlotRecord? = DataKeyModePolicy.select(
            authModeAnchored = authAnchor,
            activeSlot = activeSlot,
            backupSlot = backupSlot,
            records = records.values.toList(),
        )

        fun deviceDekAccessible(): Boolean =
            selected()?.mode == DataKeyProtectionMode.DEVICE && deviceKek

        fun snapshot() = PreferenceSnapshot(records.toMap(), activeSlot, backupSlot)

        fun restorePreferences(snapshot: PreferenceSnapshot) {
            records.clear()
            records.putAll(snapshot.records)
            activeSlot = snapshot.activeSlot
            backupSlot = snapshot.backupSlot
        }

        private fun perform(
            stage: DataKeyAuthModeTransition.Stage,
            mutation: () -> Unit = {},
            mutateWhenFailing: Boolean = false,
        ): Boolean {
            events += stage
            if (stage == failAt) {
                if (mutateWhenFailing) mutation()
                return false
            }
            mutation()
            return true
        }
    }

    private class DisableFake(
        private val failAt: DataKeyAuthModeTransition.DisableStage? = null,
    ) : DataKeyAuthModeTransition.DisableSteps {
        private val records = linkedMapOf("b" to authRecord())
        private var activeSlot: String? = "b"
        private var backupSlot: String? = "b"
        var authAnchor = true
        val events = mutableListOf<DataKeyAuthModeTransition.DisableStage>()

        override fun persistDeviceRecord(): Boolean = perform(
            DataKeyAuthModeTransition.DisableStage.PERSIST_DEVICE_RECORD,
            mutation = { records["a"] = deviceRecord() },
        )

        override fun readBackDeviceRecord(): Boolean = perform(
            DataKeyAuthModeTransition.DisableStage.READ_BACK_DEVICE_RECORD
        ) && records["a"]?.mode == DataKeyProtectionMode.DEVICE

        override fun verifyDeviceRecord(): Boolean = perform(
            DataKeyAuthModeTransition.DisableStage.VERIFY_DEVICE_RECORD
        )

        override fun activateDeviceRecord(): Boolean = perform(
            DataKeyAuthModeTransition.DisableStage.ACTIVATE_DEVICE_RECORD,
            mutation = {
                activeSlot = "a"
                backupSlot = "a"
            },
        )

        override fun readBackDeviceActivation(): Boolean = perform(
            DataKeyAuthModeTransition.DisableStage.READ_BACK_DEVICE_ACTIVATION
        ) && activeSlot == "a" && backupSlot == "a"

        override fun deleteAuthAnchor(): Boolean = perform(
            DataKeyAuthModeTransition.DisableStage.DELETE_AUTH_ANCHOR,
            mutation = { authAnchor = false },
        )

        override fun readBackAuthAnchorDeletion(): Boolean = perform(
            DataKeyAuthModeTransition.DisableStage.READ_BACK_AUTH_ANCHOR_DELETION
        ) && !authAnchor

        fun selected(): DataKeySlotRecord? = DataKeyModePolicy.select(
            authModeAnchored = authAnchor,
            activeSlot = activeSlot,
            backupSlot = backupSlot,
            records = records.values.toList(),
        )

        private fun perform(
            stage: DataKeyAuthModeTransition.DisableStage,
            mutation: () -> Unit = {},
        ): Boolean {
            events += stage
            if (stage == failAt) return false
            mutation()
            return true
        }
    }

    companion object {
        private fun deviceRecord() = DataKeySlotRecord(
            slot = "a",
            wrapped = "device-wrapped-dek",
            mode = DataKeyProtectionMode.DEVICE,
        )

        private fun authRecord() = DataKeySlotRecord(
            slot = "b",
            wrapped = "auth-wrapped-dek",
            mode = DataKeyProtectionMode.AUTH,
        )
    }
}
