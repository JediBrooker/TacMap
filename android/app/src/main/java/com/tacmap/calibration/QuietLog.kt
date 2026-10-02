package com.tacmap.calibration

import android.util.Log

/** Log.w that doesn't blow up on the host JVM (unit tests have no real android.util.Log) */
internal object QuietLog {
    fun w(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: RuntimeException) {
            // stubbed android.jar, nothing to log to
        }
    }
}
