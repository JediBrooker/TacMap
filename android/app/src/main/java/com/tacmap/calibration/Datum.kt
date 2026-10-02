package com.tacmap.calibration

/**
 * Datum a calibrated map's grid references are expressed in, the calibration
 * picker's short list. The shift itself comes from the one shared table
 * ([GeoDatums], pinned by testdata/pdf_georef.json) so a GDA94 sheet lands in the
 * same place whether it was calibrated by hand or read off its GeoPDF dicts.
 */
enum class Datum(val displayName: String, val geoDatum: GeoDatum) {
    WGS84("WGS84", GeoDatums.WGS84),
    GDA94("GDA94 / MGA94", GeoDatums.GDA94),
    GDA2020("GDA2020 / MGA2020", GeoDatums.GDA2020);

    /** Shift a (lat, lng) expressed in this datum to WGS84. */
    fun toWgs84(lat: Double, lng: Double): Pair<Double, Double> =
        geoDatum.toWGS84(lat, lng)?.let { it.latitude to it.longitude } ?: (lat to lng)
}
