package com.tacmap.calibration

/**
 * pdf_georef.json lgiRules.numericStrings + the text trim rule, shared by the
 * PDFBox reader and the JVM fixture stand-ins so both read a value the same way.
 * Kotlin's toDouble takes way more than a pdf number string should (10d, 10f,
 * hex floats, Infinity, NaN) so we gate on the grammar before converting.
 */
internal object PdfValueRules {
    private val NUMBER = Regex("^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?$")

    /** fiduciaryFits.whiteSpaceCodePoints off both ends only */
    fun trim(s: String): String = s.trim { FiduciaryReferenceParser.isReferenceSpace(it) }

    /**
     * a pdf string standing in for a number: the grammar or null (wrong type).
     * A match too big for a double comes back infinite, same as an overflowing real
     */
    fun numericString(s: String): Double? {
        val t = trim(s)
        if (!NUMBER.matches(t)) return null
        return t.toDoubleOrNull()
    }
}
