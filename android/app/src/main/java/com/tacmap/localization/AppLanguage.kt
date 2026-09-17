package com.tacmap.localization

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Observable copy preference: changing it does not recreate the map or Activity. */
object AppLanguage {
    private const val PREFERENCES = "app_language"
    private const val KEY = "display_language"
    var selection by mutableStateOf(SupportedLanguage.SYSTEM)
        private set

    fun install(context: Context) {
        val tag = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
        selection = SupportedLanguage.entries.firstOrNull { it.tag == tag } ?: SupportedLanguage.SYSTEM
    }

    fun select(context: Context, choice: SupportedLanguage): Boolean {
        if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY, choice.tag).commit()) return false
        selection = choice
        return true
    }

    fun localizedContext(context: Context): Context {
        val choice = selection // Compose tracks this read, including reads through L10n.
        if (choice == SupportedLanguage.SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(choice.tag))
        return context.createConfigurationContext(configuration)
    }
}
