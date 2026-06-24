package com.tacmap.map

import com.google.android.gms.maps.model.UrlTileProvider
import java.net.MalformedURLException
import java.net.URL

/**
 * Serves standard 256px OpenStreetMap raster tiles to a Google Maps
 * [com.google.android.gms.maps.model.TileOverlay]. No API key required.
 *
 * Use is subject to the OSM Foundation tile-usage policy
 * (https://operations.osmfoundation.org/policies/tiles/): light/personal use
 * only, valid attribution shown in-app (see AboutDialog "OpenStreetMap
 * contributors"). For heavier traffic, switch the template to a self-hosted or
 * commercial tile endpoint.
 */
class OsmTileProvider : UrlTileProvider(TILE_SIZE, TILE_SIZE) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? =
        try {
            URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
        } catch (e: MalformedURLException) {
            null
        }

    private companion object {
        const val TILE_SIZE = 256
    }
}
