package com.tacmap.util

/** Persisted protection mode carried beside each wrapped-DEK slot. */
internal enum class DataKeyProtectionMode(val persisted: String) {
    DEVICE("device"),
    AUTH("auth");

    companion object {
        fun fromPersisted(value: String?): DataKeyProtectionMode? =
            entries.firstOrNull { it.persisted == value }
    }
}

/** One complete wrapped-DEK slot. */
internal data class DataKeySlotRecord(
    val slot: String,
    val wrapped: String,
    val mode: DataKeyProtectionMode,
)

/**
 * Selects a wrapped-DEK record without allowing mutable preferences to lower
 * the protection floor established in Android Keystore.
 */
internal object DataKeyModePolicy {
    fun select(
        authModeAnchored: Boolean,
        activeSlot: String?,
        backupSlot: String?,
        records: List<DataKeySlotRecord>,
    ): DataKeySlotRecord? {
        val eligible = if (authModeAnchored) {
            records.filter { it.mode == DataKeyProtectionMode.AUTH }
        } else {
            records
        }
        return eligible.firstOrNull { it.slot == activeSlot }
            ?: eligible.firstOrNull { it.slot == backupSlot }
            ?: eligible.singleOrNull()
    }
}

/**
 * Exact fail-safe ordering for enabling auth-bound mission-key protection.
 * Each operation has a separate read-back fence so tests can inject a failure
 * at every durability boundary. Once [Stage.INSTALL_AUTH_ANCHOR] mutates the
 * Keystore, [DataKeyModePolicy] refuses DEVICE records even if a later step
 * fails or preferences are restored from an older snapshot.
 */
internal object DataKeyAuthModeTransition {
    enum class Stage {
        PERSIST_AUTH_RECORD,
        READ_BACK_AUTH_RECORD,
        VERIFY_AUTH_RECORD,
        INSTALL_AUTH_ANCHOR,
        READ_BACK_AUTH_ANCHOR,
        ACTIVATE_AUTH_RECORD,
        READ_BACK_AUTH_ACTIVATION,
        REMOVE_DEVICE_RECORDS,
        READ_BACK_DEVICE_RECORD_REMOVAL,
        DELETE_DEVICE_KEK,
        READ_BACK_DEVICE_KEK_DELETION,
    }

    data class Result(val completed: Boolean, val failedAt: Stage? = null)

    enum class DisableStage {
        PERSIST_DEVICE_RECORD,
        READ_BACK_DEVICE_RECORD,
        VERIFY_DEVICE_RECORD,
        ACTIVATE_DEVICE_RECORD,
        READ_BACK_DEVICE_ACTIVATION,
        DELETE_AUTH_ANCHOR,
        READ_BACK_AUTH_ANCHOR_DELETION,
    }

    data class DisableResult(
        val completed: Boolean,
        val failedAt: DisableStage? = null,
    )

    interface Steps {
        fun persistAuthRecord(): Boolean
        fun readBackAuthRecord(): Boolean
        fun verifyAuthRecord(): Boolean
        fun installAuthAnchor(): Boolean
        fun readBackAuthAnchor(): Boolean
        fun activateAuthRecord(): Boolean
        fun readBackAuthActivation(): Boolean
        fun removeDeviceRecords(): Boolean
        fun readBackDeviceRecordRemoval(): Boolean
        fun deleteDeviceKek(): Boolean
        fun readBackDeviceKekDeletion(): Boolean
    }

    interface DisableSteps {
        fun persistDeviceRecord(): Boolean
        fun readBackDeviceRecord(): Boolean
        fun verifyDeviceRecord(): Boolean
        fun activateDeviceRecord(): Boolean
        fun readBackDeviceActivation(): Boolean
        fun deleteAuthAnchor(): Boolean
        fun readBackAuthAnchorDeletion(): Boolean
    }

    fun enable(steps: Steps): Result {
        val ordered = listOf(
            Stage.PERSIST_AUTH_RECORD to steps::persistAuthRecord,
            Stage.READ_BACK_AUTH_RECORD to steps::readBackAuthRecord,
            Stage.VERIFY_AUTH_RECORD to steps::verifyAuthRecord,
            Stage.INSTALL_AUTH_ANCHOR to steps::installAuthAnchor,
            Stage.READ_BACK_AUTH_ANCHOR to steps::readBackAuthAnchor,
            Stage.ACTIVATE_AUTH_RECORD to steps::activateAuthRecord,
            Stage.READ_BACK_AUTH_ACTIVATION to steps::readBackAuthActivation,
            Stage.REMOVE_DEVICE_RECORDS to steps::removeDeviceRecords,
            Stage.READ_BACK_DEVICE_RECORD_REMOVAL to steps::readBackDeviceRecordRemoval,
            Stage.DELETE_DEVICE_KEK to steps::deleteDeviceKek,
            Stage.READ_BACK_DEVICE_KEK_DELETION to steps::readBackDeviceKekDeletion,
        )
        ordered.forEach { (stage, operation) ->
            if (!operation()) return Result(completed = false, failedAt = stage)
        }
        return Result(completed = true)
    }

    fun disable(steps: DisableSteps): DisableResult {
        val ordered = listOf(
            DisableStage.PERSIST_DEVICE_RECORD to steps::persistDeviceRecord,
            DisableStage.READ_BACK_DEVICE_RECORD to steps::readBackDeviceRecord,
            DisableStage.VERIFY_DEVICE_RECORD to steps::verifyDeviceRecord,
            DisableStage.ACTIVATE_DEVICE_RECORD to steps::activateDeviceRecord,
            DisableStage.READ_BACK_DEVICE_ACTIVATION to steps::readBackDeviceActivation,
            DisableStage.DELETE_AUTH_ANCHOR to steps::deleteAuthAnchor,
            DisableStage.READ_BACK_AUTH_ANCHOR_DELETION to steps::readBackAuthAnchorDeletion,
        )
        ordered.forEach { (stage, operation) ->
            if (!operation()) return DisableResult(completed = false, failedAt = stage)
        }
        return DisableResult(completed = true)
    }
}
