package com.tacmap.util

import java.io.File

/** Injectable durable write seam used by mission-store failure regressions. */
internal fun interface MissionStorePersistence {
    fun write(file: File, label: String, text: String)

    companion object {
        val SAFE_STORE = MissionStorePersistence { file, label, text ->
            SafeStore.writeAtomically(file, label, text)
        }
    }
}
