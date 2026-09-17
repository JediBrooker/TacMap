package com.tacmap.localization

import java.text.DecimalFormatSymbols
import java.util.Locale

/** Scalar input only: dots always mean decimals. Never strip grouping or parse coordinates here. */
object DecimalInput {
    private val grammar = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    fun parse(text: String, locale: Locale = DisplayFormat.currentLocale): Double? {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val value = text.trim().replace(separator, '.')
        if (!grammar.matches(value)) return null
        return value.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
