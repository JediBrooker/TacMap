package com.tacmap.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash capture, no telemetry. Mirrors iOS CrashReporter and
 * honors the privacy policy. Drops an uncaught-exception handler that writes
 * last crash to filesDir; About dialog lets the user export it.
 * Only catches Kotlin/Java exceptions, NDK crashes are out of scope.
 */
object CrashReporter {

    private fun file(context: Context) = File(context.filesDir, "last_crash.log")

    /** Call once, early as possible (Application.onCreate). */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
                file(appContext).writeText(
                    "TacMap crash\n$stamp\nthread=${thread.name}\n\n$sw\n"
                )
            }
            // let the platform handler do its thing too
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Last run's crash report if there is one. */
    fun lastReport(context: Context): String? =
        file(context).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
