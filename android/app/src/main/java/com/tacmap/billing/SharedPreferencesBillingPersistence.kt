package com.tacmap.billing

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** App-private, synchronous persistence for the permanent Play entitlement. */
internal class SharedPreferencesBillingPersistence(
    private val preferences: SharedPreferences,
) : BillingEntitlementPersistence {
    override fun readDurableEntitlement(): Boolean? =
        if (preferences.contains(KEY_DURABLE_ENTITLEMENT)) {
            preferences.getBoolean(KEY_DURABLE_ENTITLEMENT, false)
        } else {
            null
        }

    override fun readLegacyEntitlement(): Boolean =
        preferences.getBoolean(KEY_LEGACY_ENTITLEMENT, false)

    override fun readPendingAcknowledgements(): Set<String> =
        preferences.getStringSet(KEY_PENDING_ACKNOWLEDGEMENTS, emptySet())?.toSet().orEmpty()

    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun write(snapshot: BillingEntitlementSnapshot): Boolean =
        // commit() is intentional: access and acknowledgement work must not be
        // published before the app-private entitlement record reaches disk.
        preferences.edit()
            .putBoolean(KEY_DURABLE_ENTITLEMENT, snapshot.isPurchased)
            .putStringSet(KEY_PENDING_ACKNOWLEDGEMENTS, snapshot.pendingAcknowledgements.toSet())
            // This timestamp controlled the old seven-day lease. Removing it
            // makes future reads unambiguously clock-independent.
            .remove(KEY_LEGACY_LAST_VERIFIED_MS)
            .commit()

    private companion object {
        const val KEY_DURABLE_ENTITLEMENT = "durable_entitlement_v2"
        const val KEY_PENDING_ACKNOWLEDGEMENTS = "pending_acknowledgements_v1"
        const val KEY_LEGACY_ENTITLEMENT = "cached_entitlement_v1"
        const val KEY_LEGACY_LAST_VERIFIED_MS = "last_verified_ms_v1"
    }
}
