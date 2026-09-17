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

    fun area(squareMetres: Double, locale: Locale = currentLocale): String = when {
        squareMetres < 10_000 -> number(squareMetres, 0, locale) + " m²"
        squareMetres < 1_000_000 -> number(squareMetres / 10_000, 2, locale) + " ha"
        else -> number(squareMetres / 1_000_000, 2, locale) + " km²"
    }

    fun time(date: Date, locale: Locale = currentLocale, timeZone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { this.timeZone = timeZone }.format(date)
}
