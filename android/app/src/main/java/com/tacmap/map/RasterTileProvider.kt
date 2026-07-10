package com.tacmap.map

import com.google.android.gms.maps.model.UrlTileProvider
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.EsriKey
import java.net.MalformedURLException
import java.net.URL

/**
 * Serves 256px XYZ raster tiles for an online basemap [style] to a Google Maps
 * [com.google.android.gms.maps.model.TileOverlay].
 *
 * Builds the tile URL from the style's `{z}/{x}/{y}` template (Esri uses
 * `{z}/{y}/{x}`). Esri styles are keyed: the ArcGIS token is appended here, and
 * if there's no key we return null (no tile) rather than fall back to an
 * unauthenticated endpoint. Use is subject to each provider's tile policy;
 * attribution is shown in-app (see AboutDialog).
 */
class RasterTileProvider(private val style: BasemapStyle) : UrlTileProvider(TILE_SIZE, TILE_SIZE) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (zoom > style.maxZoom) return null
        // Never hot-link Esri without a key. The caller should also hide the
        // Esri option when unavailable; this is the belt to that braces.
        if (style.requiresEsriKey && !EsriKey.isAvailable) return null

        var url = style.urlTemplate
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        if (style.requiresEsriKey) url += "?token=${EsriKey.token}"

        return try {
            URL(url)
        } catch (e: MalformedURLException) {
            null
        }
    }

    private companion object {
        const val TILE_SIZE = 256
    }
}
