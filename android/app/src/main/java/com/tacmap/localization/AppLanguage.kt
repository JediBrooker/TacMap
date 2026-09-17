package com.tacmap.localization

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Observable copy preference: changing it does not recreate the map or Activity. */
object AppLanguage {
    enum class Choice(val tag: String) {
        SYSTEM("system"), ENGLISH("en"), GERMAN("de");
        val label: String get() = when (this) {
            SYSTEM -> L10n.text("Device language")
            ENGLISH -> "English"
            GERMAN -> "Deutsch"
        }
    }

    private const val PREFERENCES = "app_language"
    private const val KEY = "display_language"
    var selection by mutableStateOf(Choice.SYSTEM)
        private set

    fun install(context: Context) {
        val tag = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
        selection = Choice.entries.firstOrNull { it.tag == tag } ?: Choice.SYSTEM
    }

    fun select(context: Context, choice: Choice): Boolean {
        if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY, choice.tag).commit()) return false
        selection = choice
        return true
    }

    fun localizedContext(context: Context): Context {
        val choice = selection // Compose tracks this read, including reads through L10n.
        if (choice == Choice.SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(choice.tag))
        return context.createConfigurationContext(configuration)
    }
}
