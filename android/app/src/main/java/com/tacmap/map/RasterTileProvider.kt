package com.tacmap.map

import com.google.android.gms.maps.model.UrlTileProvider
import com.tacmap.calibration.BasemapStyle
import java.net.MalformedURLException
import java.net.URL

/**
 * Serves standard 256px XYZ raster tiles for an online basemap [style] to a
 * Google Maps [com.google.android.gms.maps.model.TileOverlay]. No API key.
 *
 * Builds the tile URL from the style's `{z}/{x}/{y}` template (Esri uses
 * `{z}/{y}/{x}`). Use is subject to each provider's tile policy; attribution is
 * shown in-app (see AboutDialog).
 */
class RasterTileProvider(private val style: BasemapStyle) : UrlTileProvider(TILE_SIZE, TILE_SIZE) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (zoom > style.maxZoom) return null
        val url = style.urlTemplate
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
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
