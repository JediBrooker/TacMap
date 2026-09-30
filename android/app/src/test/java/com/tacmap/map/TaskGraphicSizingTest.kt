package com.tacmap.map

import com.tacmap.map.render.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * Expected values come from the iOS formulas, evaluated independently:
 * `MapGeometry.zoomScaleFactor(metresPerPoint:reference: 1.0)`,
 * `TacticalSymbolOverlay.bubbleSize` (max(8, 64 × scale × zoom factor)) and
 * `MapViewModel.defaultControlMeasureScale` (clamp(1.18 × mpp, 0.1, 20)), at
 * the metres per point of the shared WebMercator ground resolution. A task
 * synced between platforms must come out the same size on both.
 */
class TaskGraphicSizingTest {

    private data class Fixture(
        val latitude: Double,
        val zoom: Double,
        val metresPerPoint: Double,
        val zoomScaleFactor: Double,
        val side1x: Double,
        val side2point5x: Double,
        val sideHalfX: Double,
        val defaultScale: Double,
    )

    private val fixtures = listOf(
        Fixture(0.0, 17.0, 1.194328566955879, 0.8372905309874766,
            53.586593983198505, 133.96648495799627, 26.793296991599252, 1.4093077090079371),
        Fixture(51.5, 15.0, 2.9739480555378712, 0.3362533512103119,
            21.520214477459962, 53.800536193649904, 10.760107238729981, 3.509258705534688),
        Fixture(51.5, 18.0, 0.3717435069422339, 2.6900268096824953,
            172.1617158196797, 430.40428954919923, 86.08085790983985, 0.438657338191836),
        // Zoomed out: every side hits the 8 pt floor; the default scale hits 20×.
        Fixture(-33.87, 12.0, 31.732993038205038, 0.031512942973770136, 8.0, 8.0, 8.0, 20.0),
        // Zoomed right in: the default scale hits the 0.1× floor.
        Fixture(0.0, 22.0, 0.03732276771737122, 26.793296991599252,
            1714.7710074623521, 4286.927518655881, 857.3855037311761, 0.1),
        // Continental: the zoom factor hits its 0.005 floor.
        Fixture(60.0, 3.0, 9783.939620502562, 0.005, 8.0, 8.0, 8.0, 20.0),
    )

    private fun close(expected: Double, actual: Double) =
        assertEquals(expected, actual, 1e-9 * maxOf(1.0, kotlin.math.abs(expected)))

    @Test fun groundResolutionMatchesTheIosCamera() {
        fixtures.forEach { close(it.metresPerPoint, WebMercator.groundResolution(it.latitude, it.zoom)) }
    }

    @Test fun zoomFactorIsOneAtOneMetrePerPointAndClamped() {
        close(1.0, TaskGraphicSizing.zoomScaleFactor(1.0))
        close(2.0, TaskGraphicSizing.zoomScaleFactor(0.5))
        close(0.5, TaskGraphicSizing.zoomScaleFactor(2.0))
        close(50.0, TaskGraphicSizing.zoomScaleFactor(0.001))
        close(0.005, TaskGraphicSizing.zoomScaleFactor(1_000.0))
        fixtures.forEach { close(it.zoomScaleFactor, TaskGraphicSizing.zoomScaleFactor(it.metresPerPoint)) }
    }

    @Test fun displaySizeFollowsTheIosBubbleFormula() {
        fixtures.forEach { f ->
            val uniform = TaskGraphicSizing.displaySize(1.0, 1.0, f.metresPerPoint)
            close(f.side1x, uniform.width)
            close(f.side1x, uniform.height)
            val stretched = TaskGraphicSizing.displaySize(2.5, 0.5, f.metresPerPoint)
            close(f.side2point5x, stretched.width)
            close(f.sideHalfX, stretched.height)
        }
    }

    @Test fun scaleOneCoversSixtyFourMetresOfGround() {
        // One point per metre is the reference zoom: 1× is 64 pt, i.e. 64 m.
        val size = TaskGraphicSizing.displaySize(1.0, 1.0, 1.0)
        close(64.0, size.width)
        // Zooming in one level doubles the on-screen size, keeping the ground size.
        val zoomedIn = TaskGraphicSizing.displaySize(1.0, 1.0, 0.5)
        close(128.0, zoomedIn.width)
        // The editor's full 20× range is honoured rather than capped.
        close(1280.0, TaskGraphicSizing.displaySize(20.0, 20.0, 1.0).width)
    }

    @Test fun defaultScaleSuitsTheZoomWithinTheSliderRange() {
        fixtures.forEach { close(it.defaultScale, TaskGraphicSizing.defaultScale(it.metresPerPoint)) }
        close(1.18, TaskGraphicSizing.defaultScale(1.0))
        close(1.0, TaskGraphicSizing.defaultScale(Double.NaN))
    }

    @Test fun defaultScaleUsesTheViewportCentre() {
        close(1.0, TaskGraphicSizing.defaultScale(null as MapViewportState?))
        val viewport = MapViewportState(latitude = 51.5, longitude = -0.12, zoom = 15.0, bearingDegrees = 0.0)
        close(3.509258705534688, TaskGraphicSizing.defaultScale(viewport))
    }

    @Test fun artworkKeepsItsAspectInsideTheBox() {
        // A 480 × 90 px asset (e.g. Cover) fits the width of the box.
        val wide = TaskGraphicSizing.artworkSize(TaskGraphicSize(128.0, 64.0), 480.0 / 90.0)
        close(128.0, wide.width)
        close(12.0, wide.height)
        val tall = TaskGraphicSizing.artworkSize(TaskGraphicSize(100.0, 100.0), 0.5)
        close(50.0, tall.width)
        close(100.0, tall.height)
    }

    @Test fun boundsRotateInsideTheSquareBeforeStretching() {
        val box = TaskGraphicSize(128.0, 64.0)
        val square = TaskGraphicSizing.screenBounds(box, 1.0, 0.0)
        close(128.0, square.width)
        close(64.0, square.height)
        // A 2:1 graphic turned 90° inside the unit square, then stretched 2:1.
        val turned = TaskGraphicSizing.screenBounds(box, 2.0, 90.0)
        close(64.0, turned.width)
        close(64.0, turned.height)
        val diagonal = TaskGraphicSizing.screenBounds(box, 1.0, 45.0)
        close(128.0 * sqrt(2.0), diagonal.width)
        close(64.0 * sqrt(2.0), diagonal.height)
    }
}
