package com.tacmap.localization

import java.math.RoundingMode
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Presentation only. Never use for coordinates, storage, signatures or exports. */
object DisplayFormat {
    fun locale(languageTag: String?, device: Locale = Locale.getDefault()): Locale {
        if (languageTag == null) return device
        return Locale.Builder().setLanguageTag(languageTag).apply {
            if (device.country.isNotEmpty()) setRegion(device.country)
        }.build()
    }

    val currentLocale: Locale get() = locale(
        AppLanguage.selection.takeUnless { it == SupportedLanguage.SYSTEM }?.tag
    )

    /**
     * The app's UI language, "en" or "de", nothing regional. For numbers that have to match
     * iOS whatever the device region is, the bake rows say (PAR-R2-1)
     */
    fun uiLanguage(
        selection: SupportedLanguage = AppLanguage.selection,
        system: () -> String = { L10n.systemUiLanguage() },
    ): String = when (selection) {
        SupportedLanguage.GERMAN -> "de"
        SupportedLanguage.ENGLISH -> "en"
        SupportedLanguage.SYSTEM -> system()
    }

    fun number(value: Double, decimals: Int, locale: Locale = currentLocale): String {
        if (!value.isFinite()) return "—"
        return NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            roundingMode = RoundingMode.HALF_EVEN
        }.format(value)
    }

    fun distance(metres: Double, locale: Locale = currentLocale): String = when {
        metres < 1000 -> number(metres, 0, locale) + " m"
        else -> number(metres / 1000, if (metres < 100_000) 2 else 0, locale) + " km"
    }

    /** A terrain or eye height in whole metres, which can be negative. */
    fun height(metres: Double, locale: Locale = currentLocale): String = number(metres, 0, locale) + " m"

    fun area(squareMetres: Double, locale: Locale = currentLocale): String = when {
        squareMetres < 10_000 -> number(squareMetres, 0, locale) + " m²"
        squareMetres < 1_000_000 -> number(squareMetres / 10_000, 2, locale) + " ha"
        else -> number(squareMetres / 1_000_000, 2, locale) + " km²"
    }

    fun percent(wholePercent: Int, locale: Locale = currentLocale): String =
        NumberFormat.getPercentInstance(locale).apply {
            isGroupingUsed = false
            maximumFractionDigits = 0
        }.format(wholePercent / 100.0)

    fun dateTime(date: Date, locale: Locale = currentLocale, timeZone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply {
            this.timeZone = timeZone
        }.format(date)

    fun time(date: Date, locale: Locale = currentLocale, timeZone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { this.timeZone = timeZone }.format(date)
}
