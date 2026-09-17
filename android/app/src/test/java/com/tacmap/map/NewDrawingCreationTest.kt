package com.tacmap.map

import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class NewDrawingCreationTest {
    @Test
    fun productionCreationPathStoresPortableThreeDpAsRendererPixelsAtEveryDensity() {
        val expected = listOf(
            1f to 3f,   // mdpi
            2f to 6f,   // xhdpi
            4f to 12f,  // xxxhdpi
        )

        expected.forEach { (density, rendererPixels) ->
            val feature = newMapDrawingFeature(
                name = "Route",
                geometry = DrawingGeometry.LINE,
                points = listOf(DrawingPoint(-33.8, 151.2), DrawingPoint(-33.9, 151.3)),
                layerId = "friendly",
                strokeColor = 0xFFFFA000.toInt(),
                fillColor = 0,
                strokeStyle = DrawingStrokeStyle.SOLID,
                density = density,
            )

            assertEquals(rendererPixels, feature.strokeWidth, 0f)
        }
    }
}
