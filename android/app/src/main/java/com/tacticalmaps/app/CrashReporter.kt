package com.tacticalmaps.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash capture (no telemetry — mirrors iOS CrashReporter and honours
 * the privacy policy). Installs a default uncaught-exception handler that writes
 * the last crash to filesDir; the About dialog surfaces it for the user to
 * export. Catches Kotlin/Java exceptions — native (NDK) crashes are out of scope.
 */
object CrashReporter {

    private fun file(context: Context) = File(context.filesDir, "last_crash.log")

    /** Install once, as early as possible (Application.onCreate). */
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
            // Hand off to the platform handler so the OS still records it.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The previous run's crash report, if any. */
    fun lastReport(context: Context): String? =
        file(context).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
