package com.tacmap.calibration

import android.net.Uri
import java.util.UUID

/** Why a PDF isn't georeferenced. The UI says it out loud, it's never a silent camera box. */
sealed class PdfGeorefIssue {
    /** plain PDF, nothing declared: calibrate it */
    data object NoMetadata : PdfGeorefIssue()

    /** declared a georef we can't trust (reason code is the shared fixture's) */
    data class Rejected(val reason: GeorefRejectReason) : PdfGeorefIssue()

    /** an old session that only ever had the made up camera-centred box */
    data object LegacyPlacement : PdfGeorefIssue()

    /** a stored calibration that can't be rebuilt under the new rules (collinear etc) */
    data object CalibrationLost : PdfGeorefIssue()
}

/**
 * PDF-backed map source. [calibration] is the real georef (GeoPDF or fiduciary
 * fit). Without one the page sits on a [provisional] placement that the UI labels
 * uncalibrated and never passes off as a usable basemap, plan 02 s1.
 */
class PdfMapSource(
    val uri: Uri,
    override val displayName: String,
    override val kind: MapSourceKind,
    override val calibration: Calibration?,
    val geometry: PdfPageGeometry,
    val provisional: PdfGeoreference? = null,
    val georefIssue: PdfGeorefIssue? = null,
    /** fiduciaries left over from a calibration that couldn't be rebuilt, seeds the next attempt */
    val pendingFiduciaries: List<Fiduciary> = emptyList(),
) : MapSource {
    override val id: String = UUID.randomUUID().toString()

    /** what's on screen right now */
    val placement: PdfGeoreference? get() = calibration?.georef ?: provisional

    val isGeoreferenced: Boolean get() = calibration != null

    override val coverage: Wgs84Bounds? = placement?.wgs84Bounds()

    /** raw-space crop a new calibration is fitted against: the visible page box */
    val calibrationCrop: List<PagePoint> get() = geometry.visibleCrop()

    fun calibrated(fiduciaries: List<Fiduciary>, georef: PdfGeoreference): PdfMapSource {
        if (fiduciaries.size < 3 || !georef.isUsable() || georef.wgs84Bounds() == null) return this
        return PdfMapSource(
            uri = uri,
            displayName = displayName,
            kind = MapSourceKind.CALIBRATED_PDF,
            calibration = Calibration.Fiduciaries(fiduciaries, georef),
            geometry = geometry,
        )
    }

    companion object {
        fun geoPdf(uri: Uri, name: String, georef: PdfGeoreference, geometry: PdfPageGeometry): PdfMapSource =
            PdfMapSource(uri, name, MapSourceKind.GEO_PDF, Calibration.Parsed(georef), geometry)

        /** uncalibrated, drawn at the provisional 1:50k placement around [center] */
        fun uncalibrated(
            uri: Uri,
            name: String,
            geometry: PdfPageGeometry,
            center: Wgs84Coordinate,
            issue: PdfGeorefIssue,
            pendingFiduciaries: List<Fiduciary> = emptyList(),
        ): PdfMapSource = PdfMapSource(
            uri = uri,
            displayName = name,
            kind = MapSourceKind.CALIBRATED_PDF,
            calibration = null,
            geometry = geometry,
            provisional = PdfGeoreference.provisional(center, geometry.visibleCrop(), geometry.rotation),
            georefIssue = issue,
            pendingFiduciaries = pendingFiduciaries,
        )
    }
}
