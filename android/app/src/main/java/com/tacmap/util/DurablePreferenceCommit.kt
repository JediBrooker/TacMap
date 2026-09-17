package com.tacmap.util

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * Publishes a security-sensitive preference only after its synchronous disk
 * commit succeeds. `SharedPreferences.apply()` updates process memory first and
 * cannot report a failed write, which can make a UI show a safer state that
 * silently reverts after process death.
 */
internal object DurablePreferenceCommit {
    private val transactionLock = Any()

    /**
     * SharedPreferences mutates its in-process map before `commit()` writes the
     * XML file. A false result therefore requires an explicit rollback even
     * when callers suppress their StateFlow publication; otherwise an unrelated
     * later edit can flush the rejected value to disk.
     */
    @SuppressLint("ApplySharedPref")
    fun preferences(
        preferences: SharedPreferences,
        keys: Set<String>,
        mutate: SharedPreferences.Editor.() -> SharedPreferences.Editor,
        publish: () -> Unit,
    ): Boolean = publishAfter(
        capture = { PreferenceSnapshot.capture(preferences, keys) },
        commit = { preferences.edit().let(mutate).commit() },
        rollback = { snapshot -> snapshot.restore(preferences) },
        publish = publish,
    )

    /** Pure transaction core retained as a directly testable contract. */
    internal fun <Snapshot> publishAfter(
        capture: () -> Snapshot,
        commit: () -> Boolean,
        rollback: (Snapshot) -> Unit,
        publish: () -> Unit,
    ): Boolean = synchronized(transactionLock) {
        val before = try {
            capture()
        } catch (_: Exception) {
            return@synchronized false
        }
        val committed = try {
            commit()
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            // restore() calls apply(), whose in-memory mutation is synchronous.
            // Even if storage remains unavailable, a future unrelated edit sees
            // the prior values rather than the rejected candidate.
            try {
                rollback(before)
            } catch (_: Exception) {
                // The caller still receives failure and publishes nothing. A
                // corrupt/hostile SharedPreferences implementation is outside
                // Android's normal contract, but must not turn failure into success.
            }
            return@synchronized false
        }
        publish()
        true
    }

    private data class PreferenceSnapshot(
        val keys: Set<String>,
        val values: Map<String, Any>,
    ) {
        @SuppressLint("ApplySharedPref")
        fun restore(preferences: SharedPreferences) {
            val editor = preferences.edit()
            keys.forEach { key -> editor.remove(key) }
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> {
                        require(value.all { it is String }) {
                            "SharedPreferences string set contains a non-string value"
                        }
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, (value as Set<String>).toSet())
                    }
                    else -> error("Unsupported SharedPreferences value for $key")
                }
            }
            // apply() performs the in-memory rollback synchronously. Its disk
            // write can remain asynchronous because the rejected candidate's
            // commit already failed; subsequent edits observe only this snapshot.
            editor.apply()
        }

        companion object {
            fun capture(preferences: SharedPreferences, keys: Set<String>): PreferenceSnapshot {
                val all = preferences.all
                val values = buildMap {
                    keys.forEach { key ->
                        val value = all[key] ?: return@forEach
                        put(key, if (value is Set<*>) value.toSet() else value)
                    }
                }
                return PreferenceSnapshot(keys.toSet(), values)
            }
        }
    }
}
