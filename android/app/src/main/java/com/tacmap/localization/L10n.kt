package com.tacmap.localization

import android.content.Context
import java.util.Locale

/** Native resource lookup for UI copy produced by screens, models and services.
 * The Application owns the context; never retain an Activity. Resources are read
 * on each lookup so Android's per-app language setting is respected after a change.
 * JVM model tests without an Application retain the original English fallback.
 */
object L10n {
    @Volatile private var application: Context? = null

    fun install(context: Context) {
        application = context.applicationContext
    }

    fun text(key: String, vararg arguments: Any?): String {
        val context = application
        val id = localizedStringIds[key]
        val format = if (context != null && id != null) context.getString(id) else key
        if (arguments.isEmpty()) return format
        val locale = context?.resources?.configuration?.locales?.get(0) ?: Locale.ENGLISH
        return String.format(locale, format, *arguments.map { it.toString() }.toTypedArray())
    }

    fun quantity(noun: String, count: Int): String {
        val context = application
        val id = localizedPluralIds.getValue(noun)
        if (context != null) return context.resources.getQuantityString(id, count, count)
        val forms = englishPlurals.getValue(noun)
        return String.format(Locale.ENGLISH, if (count == 1) forms.first else forms.second, count)
    }
}
